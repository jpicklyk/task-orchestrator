// Tests for the director-guard gaps closed in item dbb45373 (AC7, scenarios S13-S14). BLIND author:
// written from the frozen task-scope and test-plan notes, the supplied declarations and the existing
// director-guard tests. Oracles: task-scope AC7 (git log patch flags, git branch listing flags only, file
// tools denied like Read, the hooks-config matcher string). The subprocess test redirects TEMP so no real
// marker path is touched.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { bashAllowed, guardDecision, REASON_PREFIX } from '../director-guard.mjs'
import { directorMarkerPath } from '../execution-mode.mjs'

const HOOK = fileURLToPath(new URL('../director-guard.mjs', import.meta.url))
const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url))

const tracked = []
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true })
})

// ---- S13 bashAllowed ----

test('S13: git log with a patch-producing flag is denied: -p, -u, --patch*, -L*, --output*, --word-diff*, and clusters with p, u or L', () => {
  const denied = [
    'git log -p', 'git log -u', 'git log --patch', 'git log --patch-with-stat', 'git log --patch-with-raw',
    'git log -L1,2:f', 'git log -Lfoo:file.js', 'git log --output=x', 'git log --output x',
    'git log --word-diff', 'git log --word-diff=color', 'git log --word-diff-regex=x',
    'git log -np', 'git log -pn', 'git log -nu', 'git log -nL1,2:f',
  ]
  for (const c of denied) assert.equal(bashAllowed(c), false, c)
})

test('S13: the patch flag is caught in any position, in a later segment, and after a tab separator', () => {
  for (const c of [
    'git log --oneline -p', 'git log -p --oneline', 'git log --oneline -5 -p',
    'git status && git log -p', 'git status; git log --patch', 'git log --oneline | git log -u',
    'git log\t-p',
  ]) {
    assert.equal(bashAllowed(c), false, JSON.stringify(c))
  }
})

test('S13: plain git log forms stay allowed, including double-dash flags that merely contain p, u or L', () => {
  for (const c of [
    'git log', 'git log --oneline -5', 'git log --oneline --graph', 'git log -n 5', 'git log -5',
    'git log --pretty=oneline', 'git log --author=pat', 'git log --stat', 'git log --since=2.days',
    'git log -P', 'git status && git log --oneline',
  ]) {
    assert.equal(bashAllowed(c), true, c)
  }
})

test('S13: git branch that mutates or creates is denied: -D, -d, a bare name, --delete, move, copy, upstream, force', () => {
  for (const c of [
    'git branch -D x', 'git branch -d x', 'git branch new', 'git branch --delete x', 'git branch -m a b',
    'git branch -M a b', 'git branch --move a b', 'git branch -c a b', 'git branch --copy a b',
    'git branch --set-upstream-to=origin/x', 'git branch -u origin/x', 'git branch --unset-upstream',
    'git branch -f x HEAD', 'git branch --edit-description', 'git branch -a new', 'git branch -r extra',
    'git branch --list -D x',
  ]) {
    assert.equal(bashAllowed(c), false, c)
  }
})

test('S13: git branch listing forms stay allowed, with at most the one operand after a --merged-style flag', () => {
  for (const c of [
    'git branch', 'git branch -a', 'git branch --all', 'git branch -r', 'git branch --remotes', 'git branch -v',
    'git branch -vv', 'git branch --verbose', 'git branch --list', 'git branch --show-current',
    'git branch --contains abc', 'git branch --no-contains abc', 'git branch --merged main',
    'git branch --no-merged main', 'git branch --points-at HEAD', 'git branch --color', 'git branch --no-color',
    'git branch -a -v', 'git branch -a --contains abc',
  ]) {
    assert.equal(bashAllowed(c), true, c)
  }
})

test('S13: a second operand after --merged, or an operand after a plain listing flag, is denied', () => {
  for (const c of ['git branch --merged main extra', 'git branch --contains abc def', 'git branch -v extra', 'git branch --list extra']) {
    assert.equal(bashAllowed(c), false, c)
  }
})

test('S13: the existing allow-list entries and denials around the new rules are unchanged', () => {
  for (const c of ['git status', 'gh pr view 1', 'gh pr list', 'docker ps']) assert.equal(bashAllowed(c), true, c)
  for (const c of ['git push', 'git commit -m x', 'cat f', 'git log `x`', 'git log > f', 'git log $(x)']) assert.equal(bashAllowed(c), false, c)
})

// ---- S14 file tools and registration ----

const HOOK_INPUT = {
  Grep: { pattern: 'x', path: 'src' },
  Glob: { pattern: '**/*.mjs' },
  NotebookEdit: { notebook_path: 'n.ipynb', new_source: 'x' },
  Read: { file_path: 'f' },
}

test('S14: Grep, Glob and NotebookEdit are denied in a director session, with the same reason prefix as Read', () => {
  for (const [tool, input] of Object.entries(HOOK_INPUT)) {
    const reason = guardDecision({ tool_name: tool, tool_input: input, session_id: 's' }, { env: {}, director: true })
    assert.equal(typeof reason, 'string', tool)
    assert.ok(reason.startsWith(REASON_PREFIX), `${tool}: ${reason}`)
  }
})

test('S14: outside a director session, and inside a subagent, the same tools are allowed', () => {
  for (const [tool, input] of Object.entries(HOOK_INPUT)) {
    assert.equal(guardDecision({ tool_name: tool, tool_input: input, session_id: 's' }, { env: {}, director: false }), null, `${tool} non-director`)
    assert.equal(guardDecision({ tool_name: tool, tool_input: input, session_id: 's', agent_id: 'a1' }, { env: {}, director: true }), null, `${tool} subagent`)
  }
})

test('S14: SendMessage and ListAgents are never denied in a director session', () => {
  for (const tool of ['SendMessage', 'ListAgents']) {
    assert.equal(guardDecision({ tool_name: tool, tool_input: {}, session_id: 's' }, { env: {}, director: true }), null, tool)
  }
})

test('S14: Bash through guardDecision denies git log -p and git branch -D but allows git log --oneline', () => {
  const g = (command) => guardDecision({ tool_name: 'Bash', tool_input: { command }, session_id: 's' }, { env: {}, director: true })
  assert.ok(g('git log -p').startsWith(REASON_PREFIX))
  assert.ok(g('git branch -D x').startsWith(REASON_PREFIX))
  assert.equal(g('git log --oneline -5'), null)
  assert.equal(g('git branch -a'), null)
})

test('S14: hooks-config matcher for the guard covers Grep, Glob and NotebookEdit exactly', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf8'))
  const groups = cfg.hooks.PreToolUse.filter((g) => g.hooks.some((h) => h.command.includes('director-guard.mjs')))
  assert.equal(groups.length, 1)
  assert.equal(groups[0].matcher, 'Skill|Read|Edit|Write|Bash|Agent|Grep|Glob|NotebookEdit')
  assert.equal(groups[0].hooks[0].onFailure, 'block')
  const re = new RegExp(`^(?:${groups[0].matcher})$`)
  for (const tool of ['Grep', 'Glob', 'NotebookEdit', 'Read', 'Bash']) assert.ok(re.test(tool), tool)
  assert.ok(!re.test('SendMessage'))
})

test('S14: through the real hook process, Grep under the director marker is denied and without it is allowed', () => {
  const tmpDir = mkdtempSync(join(tmpdir(), 'to-gaps-tmp-'))
  const cwd = mkdtempSync(join(tmpdir(), 'to-gaps-cwd-'))
  tracked.push(tmpDir, cwd)
  const saved = {}
  for (const k of ['TMPDIR', 'TEMP', 'TMP']) {
    saved[k] = process.env[k]
    process.env[k] = tmpDir
  }
  let marker
  try {
    marker = directorMarkerPath('gaps-session')
  } finally {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete process.env[k]
      else process.env[k] = saved[k]
    }
  }
  const exec = (input) => {
    const r = spawnSync(process.execPath, [HOOK], {
      env: { ...process.env, TASK_ORCHESTRATOR_MODE: '', TEMP: tmpDir, TMP: tmpDir, TMPDIR: tmpDir },
      cwd, input: JSON.stringify(input), encoding: 'utf8',
    })
    assert.equal(r.status, 0, r.stderr)
    return JSON.parse(r.stdout)
  }
  const grep = { tool_name: 'Grep', tool_input: { pattern: 'x' }, session_id: 'gaps-session' }
  assert.deepEqual(exec(grep), {}, 'control: no marker, no denial')
  mkdirSync(dirname(marker), { recursive: true })
  writeFileSync(marker, '')
  const out = exec(grep)
  assert.equal(out.hookSpecificOutput.permissionDecision, 'deny')
  assert.ok(out.hookSpecificOutput.permissionDecisionReason.startsWith(REASON_PREFIX))
})