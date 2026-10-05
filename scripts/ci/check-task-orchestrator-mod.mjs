#!/usr/bin/env node
// CI gate for the task-orchestrator-mod plugin. Usage:
//   node scripts/ci/check-task-orchestrator-mod.mjs [modDir]
// modDir defaults to claude-plugins/task-orchestrator-mod (relative to cwd).
// CLAUDE_BIN overrides the `claude` binary (used to prove a pinned CLI version).
//
// Steps, in order, stopping at the first failure (exit 1; exit 0 when all pass):
//   1 validate     `claude plugin validate --json` (non-strict; warnings never fail)
//   2 =? gate      no `=?` (unresolved matcher) in the validator's hooks note
//   3 source scan  no imported / undeclared identifier in an `on(...)` matcher
//                  (the validator truncates its hooks note, so tail hooks need this)
//   4 tests        `claude plugin test`, and at least one test must have run
//
// No model call, no credentials: the child gets a fresh CLAUDE_CONFIG_DIR and
// CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 so the cached rollout flag
// `tengu_plugin_hooks_modules` cannot be saved off by an earlier session.

import { spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync, readdirSync, readFileSync, existsSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, relative, resolve } from 'node:path'

let cfgDir
const cleanup = () => cfgDir && rmSync(cfgDir, { recursive: true, force: true })
const IN_CI = process.env.GITHUB_ACTIONS === 'true'
const modDir = resolve(process.argv[2] ?? 'claude-plugins/task-orchestrator-mod')
const claudeBin = process.env.CLAUDE_BIN || 'claude'

function fail(step, msg) {
  console.log(`FAIL ${step}`)
  console.log(`${IN_CI ? '::error::' : 'error: '}${step}: ${msg}`)
  cleanup()
  process.exit(1)
}
const pass = (step, detail = '') => console.log(`PASS ${step}${detail ? ' - ' + detail : ''}`)

// ---- step 0: environment ---------------------------------------------------
if (!existsSync(modDir) || !statSync(modDir).isDirectory()) fail('setup', `mod directory not found: ${modDir}`)
cfgDir = mkdtempSync(join(tmpdir(), 'to-mod-ci-cfg-'))
const env = {
  ...process.env,
  CLAUDE_CONFIG_DIR: cfgDir,
  CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
  DISABLE_AUTOUPDATER: '1',
  CLAUDE_CODE_ENABLE_FUNCTION_HOOKS: '1',
}
delete env.ANTHROPIC_API_KEY

const q = a => (/[\s"&()^|<>]/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a)
function claude(args, opts = {}) {
  const shell = /\.(cmd|bat)$/i.test(claudeBin) // npm shims on Windows
  const cmd = shell ? q(claudeBin) : claudeBin
  return spawnSync(cmd, shell ? args.map(q) : args, {
    env,
    encoding: 'utf8',
    shell,
    maxBuffer: 64 * 1024 * 1024,
    ...opts,
  })
}

function main() {
  const ver = claude(['--version'])
  if (ver.error || ver.status !== 0) fail('setup', `cannot run ${claudeBin} --version: ${ver.error?.message ?? ver.stderr}`)
  console.log(`claude ${ver.stdout.trim()}`)

  // ---- step 1: validate ----------------------------------------------------
  const v = claude(['plugin', 'validate', '--json', modDir])
  let report
  try {
    report = JSON.parse(v.stdout)
  } catch {
    fail('1 validate', `output is not JSON (exit ${v.status}): ${(v.stdout + v.stderr).slice(0, 500)}`)
  }
  const sections = [report.manifest, ...(report.contents ?? [])].filter(Boolean)
  const errors = sections.flatMap(s => (s.errors ?? []).map(e => `${e.path ?? s.file}: ${e.message ?? JSON.stringify(e)}`))
  if (v.status !== 0 || errors.length > 0) {
    fail('1 validate', `exit ${v.status}; ${errors.length ? errors.join(' | ') : (v.stderr || 'no error detail')}`)
  }
  let warnCount = 0
  for (const s of sections) {
    for (const w of s.warnings ?? []) {
      warnCount++
      console.log(`${IN_CI ? '::warning::' : 'warning: '}${w.path ?? s.file}: ${w.message ?? JSON.stringify(w)}`)
    }
  }
  pass('1 validate', `${warnCount} warning(s), not failing`)

  // ---- step 2: =? gate -----------------------------------------------------
  const notes = sections.flatMap(s => s.notes ?? []).filter(n => typeof n === 'string')
  const hookNotes = notes.filter(n => /^\S+ hooks: /.test(n))
  if (hookNotes.length === 0) fail('2 =? gate', 'no "<file> hooks: ..." note in the validator output (format changed?)')
  const bad = hookNotes.flatMap(n => n.split(', ').filter(t => t.includes('=?')))
  if (bad.length > 0) fail('2 =? gate', `unresolved matcher(s): ${bad.join('; ')}`)
  if (hookNotes.some(n => /\[\+\d+ chars\]/.test(n))) {
    console.log('info: validator truncated the hooks note; the unchecked tail is covered by step 3')
  }
  pass('2 =? gate', `${hookNotes.length} hooks note(s), no =?`)

  // ---- step 3: source scan -------------------------------------------------
  scanSource()

  // ---- step 4: tests -------------------------------------------------------
  const t = claude(['plugin', 'test', modDir])
  const out = (t.stdout ?? '') + (t.stderr ?? '')
  process.stdout.write(out)
  if (t.status !== 0) fail('4 tests', `claude plugin test exited ${t.status}`)
  const m = /Ran (\d+) tests/.exec(out)
  if (!m) fail('4 tests', 'no "Ran N tests" line in the output')
  if (Number(m[1]) < 1) fail('4 tests', 'zero tests ran')
  pass('4 tests', `Ran ${m[1]} tests`)
}

// Scan non-test *.ts/*.tsx under src/ and hooks/ for on(...) matchers whose
// identifier values are not safe: the engine resolves a matcher only when it is
// a literal or a const declared in the registering file.
// Known limit: a matcher regex literal containing `}` can end the object early.
// That can only cause a miss, never a false FAIL.
function scanSource() {
  const step = '3 source scan'
  const files = []
  const walk = d => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, e.name)
      if (e.isDirectory()) {
        if (e.name !== 'node_modules') walk(p)
      } else if (/\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) && !/\.d\.ts$/.test(e.name)) files.push(p)
    }
  }
  for (const sub of ['src', 'hooks']) if (existsSync(join(modDir, sub))) walk(join(modDir, sub))

  let calls = 0
  let idUses = 0
  const violations = []
  for (const file of files) {
    const text = readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
    const rel = relative(modDir, file).replace(/\\/g, '/')
    const imported = importedNames(text)
    const lineOf = i => text.slice(0, i).split('\n').length
    const re = /(?<![\w$.])on\(\s*(['"`])[^'"`]*\1\s*,\s*/g
    let m
    while ((m = re.exec(text))) {
      calls++
      const rest = m.index + m[0].length
      const ids = []
      if (text[rest] === '{') {
        const end = matchBrace(text, rest)
        if (end > 0) for (const v of propertyValues(text.slice(rest + 1, end))) ids.push(v)
      } else {
        const b = /^([A-Za-z_$][\w$]*)\s*,/.exec(text.slice(rest))
        if (b && !NOT_ID.has(b[1])) ids.push(b[1])
      }
      for (const id of ids) {
        idUses++
        const declared = new RegExp(`^(?:export\\s+)?const\\s+${id.replace(/\$/g, '\\$')}\\s*=`, 'm').test(text)
        if (imported.has(id)) violations.push(`${rel}:${lineOf(m.index)}: matcher identifier "${id}" is imported; declare it as a const in this file`)
        else if (!declared) violations.push(`${rel}:${lineOf(m.index)}: matcher identifier "${id}" has no const declaration in this file`)
      }
    }
  }
  if (calls === 0) fail(step, `no on() calls found in ${files.length} file(s) (scanner broken?)`)
  if (violations.length) fail(step, violations.join(' | '))
  pass(step, `scanned ${files.length} files, ${calls} on() calls, ${idUses} identifier matchers`)
}

const NOT_ID = new Set(['true', 'false', 'null', 'undefined', 'async', 'function'])

function importedNames(text) {
  const names = new Set()
  const re = /import\s+(?:type\s+)?([\s\S]*?)\s+from\s+['"][^'"]+['"]/g
  let m
  while ((m = re.exec(text))) {
    for (const part of m[1].replace(/[{}]/g, ',').split(',')) {
      const p = part.trim().replace(/^type\s+/, '')
      if (!p) continue
      const name = p.includes(' as ') ? p.split(/\s+as\s+/)[1] : p.replace(/^\*\s+as\s+/, '')
      if (/^[A-Za-z_$][\w$]*$/.test(name.trim())) names.add(name.trim())
    }
  }
  return names
}

// Index of the `}` matching the `{` at `start`, skipping quoted strings and
// regex literals (a `/` right after `:` or `,`). Returns -1 if unbalanced.
function matchBrace(s, start) {
  let depth = 0
  for (let i = start; i < s.length; i++) {
    const c = s[i]
    if (c === "'" || c === '"' || c === '`') {
      for (i++; i < s.length && s[i] !== c; i++) if (s[i] === '\\') i++
    } else if (c === '/' && /[:,]\s*$/.test(s.slice(Math.max(0, i - 8), i))) {
      for (i++; i < s.length && s[i] !== '/'; i++) {
        if (s[i] === '\\') i++
        else if (s[i] === '[') for (i++; i < s.length && s[i] !== ']'; i++) if (s[i] === '\\') i++
      }
    } else if (c === '{') depth++
    else if (c === '}' && --depth === 0) return i
  }
  return -1
}

// Bare-identifier values of an object literal body (shorthand `{ tool }` too).
function propertyValues(body) {
  const out = []
  let depth = 0
  let cur = ''
  const flush = () => {
    const t = cur.trim()
    cur = ''
    if (!t) return
    const kv = /^(?:[A-Za-z_$][\w$]*|'[^']*'|"[^"]*")\s*:\s*([\s\S]*)$/.exec(t)
    const val = (kv ? kv[1] : t).trim()
    if (/^[A-Za-z_$][\w$]*$/.test(val) && !NOT_ID.has(val)) out.push(val)
  }
  for (let i = 0; i < body.length; i++) {
    const c = body[i]
    if (c === "'" || c === '"' || c === '`') {
      const st = i
      for (i++; i < body.length && body[i] !== c; i++) if (body[i] === '\\') i++
      cur += body.slice(st, i + 1)
      continue
    }
    if (c === '/' && /:\s*$/.test(cur)) {
      const st = i
      for (i++; i < body.length && body[i] !== '/'; i++) {
        if (body[i] === '\\') i++
        else if (body[i] === '[') for (i++; i < body.length && body[i] !== ']'; i++) if (body[i] === '\\') i++
      }
      cur += body.slice(st, i + 1)
      continue
    }
    if ('([{'.includes(c)) depth++
    else if (')]}'.includes(c)) depth--
    if (c === ',' && depth === 0) flush()
    else cur += c
  }
  flush()
  return out
}

try {
  main()
} finally {
  cleanup()
}
