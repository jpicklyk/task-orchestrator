// Item 2f3833fb: ralph assigns the seat the item's schema declares for entering the work phase (AC9), falling back to
// the implementer seat when the schema declares none or cannot be read.
//
// Oracles: the specification's AC9 (an item whose schema declares `builder` (work, enters) is assigned builder; seat-less or a
// failed schema read assigns implementer; token file names unchanged). Driver runs are subprocesses against fixtures/ralph/fake-api.mjs.
// Forbidden-construct declaration: none.

import '../../hooks/tests/hermetic-claude-config.mjs';
import { test, after, describe } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { startFakeApi } from './fixtures/ralph/fake-api.mjs'
import { DEFAULT_IDLE_BACKOFF_MS, HEADLESS_ITERATION_MODE, REDACTED_TOKEN } from '../ralph-lib.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const LOOP = path.resolve(HERE, '..', 'ralph-loop.mjs')
const FAKE_CLAUDE = path.join(HERE, 'fixtures', 'ralph', 'fake-claude.mjs')
const ROOT = '11111111-2222-4333-8444-555555555555'
const BEARER = 'bearer-0a1b2c3d4e5f60718293a4b5c6d7e8f9'
const A = 'aaaa1111'
const B = 'bbbb2222'

// ---- harness -----------------------------------------------------------------------------------------

const tmpDirs = []
function tmp(prefix) {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  tmpDirs.push(d)
  return d
}
after(() => {
  for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true })
})

function baseEnv(extra) {
  const env = { ...process.env }
  for (const k of Object.keys(env)) {
    if (k.startsWith('TASK_ORCHESTRATOR_') || k === 'AGENT_CONFIG_DIR' || k.startsWith('FAKE_CLAUDE_')) delete env[k]
  }
  return { ...env, ...extra }
}

function runDriver({ argv, env, cwd, timeoutMs, onStart }) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [LOOP, ...argv], { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    let timedOut = false
    const t0 = Date.now()
    child.stdout.on('data', (d) => (stdout += d))
    child.stderr.on('data', (d) => (stderr += d))
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGKILL')
    }, timeoutMs)
    child.on('close', (code, signal) => {
      clearTimeout(timer)
      resolve({ code, signal, stdout, stderr, out: stdout + '\n' + stderr, timedOut, elapsedMs: Date.now() - t0 })
    })
    if (onStart) onStart(child)
  })
}

function readJsonl(file) {
  if (!fs.existsSync(file)) return []
  return fs
    .readFileSync(file, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l))
}

// One driver run against a fresh stub. `items` are the queue ids (oldest first). `plan` is the fake claude's
// per-invocation behaviour. `args` extend/override the defaults (a later duplicate string option wins).
async function scenario(o = {}) {
  const { items = [], apiOpts = {}, plan = [], args = [], env = {}, timeoutMs = 30000, onStart, useApi = true } = o
  const cwd = tmp('ralph-cwd-')
  const home = tmp('ralph-home-')
  const aux = tmp('ralph-aux-')
  const scratch = path.join(tmp('ralph-scratch-'), 'run')
  try {
    execFileSync('git', ['init', '-q'], { cwd, stdio: 'ignore' })
  } catch {
    // the worktree cleanup is disabled by --no-cleanup; a repository is only for realism
  }
  const api = useApi ? await startFakeApi({ items: items.map((id) => ({ id })), ...apiOpts }) : null
  const logFile = path.join(aux, 'claude.jsonl')
  const argv = ['--claude-bin', FAKE_CLAUDE, '--root', ROOT, '--scratch-dir', scratch, '--no-cleanup', '--ttl', '120', '--budget', '1', '--max', '1', ...args]
  const envv = baseEnv({
    TASK_ORCHESTRATOR_HOME: home,
    TASK_ORCHESTRATOR_CEILING: cwd,
    FAKE_CLAUDE_LOG: logFile,
    FAKE_CLAUDE_PLAN: JSON.stringify(plan),
    FAKE_CLAUDE_ITEM_IDS: items.join(','),
    ...(api ? { TASK_ORCHESTRATOR_API_URL: api.base, TASK_ORCHESTRATOR_API_TOKEN: BEARER } : {}),
    ...env,
  })
  const res = await runDriver({ argv, env: envv, cwd, timeoutMs, onStart: onStart ? (child) => onStart(child, { logFile }) : undefined })
  if (api) await api.stop()
  const log = readJsonl(logFile)
  return { api, res, cwd, scratch, logFile, log, starts: log.filter((r) => r.type === 'start'), finishes: log.filter((r) => r.type === 'finish') }
}

const once = (fn) => {
  let p
  return () => (p ??= fn())
}
const tail = (s) => `\n--- driver output (tail) ---\n${s.res.out.slice(-1500)}`
const opsNamed = (s, name) => s.api.trace.filter((t) => t.op === name)
const writes = (s) => s.api.trace.filter((t) => t.tool === 'runs_write' || t.tool === 'advance')

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else out.push(p)
  }
  return out
}
function filesContaining(dirs, needles) {
  const hits = []
  for (const d of dirs) {
    for (const f of walk(d)) {
      if (f.includes(`${path.sep}.git${path.sep}`)) continue
      const text = fs.readFileSync(f, 'utf8')
      if (needles.some((n) => text.includes(n))) hits.push(f)
    }
  }
  return hits
}


import { ralphSeatOf, RALPH_SEAT, seatTokenFilePath } from '../ralph-lib.mjs'

test('AC9: ralphSeatOf picks the first entering work seat, else the first work seat, else implementer', () => {
  assert.equal(ralphSeatOf({ seats: [{ name: 'planner', phase: 'queue', enters: true }, { name: 'builder', phase: 'work', enters: true }] }), 'builder')
  assert.equal(ralphSeatOf({ seats: [{ name: 'helper', phase: 'work' }, { name: 'builder', phase: 'work', enters: true }] }), 'builder', 'entering wins over order')
  assert.equal(ralphSeatOf({ seats: [{ name: 'helper', phase: 'work' }, { name: 'other', phase: 'work' }] }), 'helper', 'no entering seat: the first work seat')
  assert.equal(ralphSeatOf({ seats: [{ name: 'planner', phase: 'queue' }] }), RALPH_SEAT)
  assert.equal(ralphSeatOf({ notes: [] }), RALPH_SEAT, 'seat-less')
  assert.equal(ralphSeatOf(null), RALPH_SEAT)
  assert.equal(ralphSeatOf(undefined), RALPH_SEAT)
})

test('AC9: an item whose schema declares builder (work, enters) is assigned builder', async () => {
  const schema = { type: 'feature-task', seats: [{ name: 'planner', phase: 'queue' }, { name: 'builder', phase: 'work', enters: true }], notes: [] }
  const s = await scenario({ items: [A], apiOpts: { schema } })
  assert.equal(s.res.timedOut, false, tail(s))
  const assigns = opsNamed(s, 'assign')
  assert.ok(assigns.length >= 1, 'fixture condition: an assign was sent' + tail(s))
  assert.ok(assigns.every((a) => a.seat === 'builder'), JSON.stringify(assigns.map((a) => a.seat)))
  assert.ok(s.api.trace.some((t) => t.op === 'schema' && t.body.itemId.toLowerCase() === A), 'the schema was read for the item')
  assert.ok(s.api.seats.every((x) => x.seat === 'builder'))
})

test('AC9: a seat-less schema assigns implementer', async () => {
  const s = await scenario({ items: [A], apiOpts: { schema: { type: 'task', notes: [] } } })
  assert.equal(s.res.timedOut, false, tail(s))
  const assigns = opsNamed(s, 'assign')
  assert.ok(assigns.length >= 1 && assigns.every((a) => a.seat === 'implementer'), tail(s))
})

test('AC9: a failed schema read assigns implementer (fail-open) and the token file name stays implementer-named', async () => {
  const s = await scenario({ items: [A] })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.ok(s.api.trace.some((t) => t.op === 'schema'), 'fixture condition: the schema read was attempted and the fake refused it')
  const assigns = opsNamed(s, 'assign')
  assert.ok(assigns.length >= 1 && assigns.every((a) => a.seat === 'implementer'), tail(s))
  // the token file name does not follow the assigned seat name
  assert.equal(path.basename(seatTokenFilePath('/scratch', 3)), '3.implementer.token')
})
