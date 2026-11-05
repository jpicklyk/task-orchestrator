// Tests for the --out [--force] artifact overwrite guard (#643): the pure artifactWriteGuard truth
// table, the run-planner CLI (plan, bind-run, follow-ups) and the implementer smoke-root prompt line.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { artifactWriteGuard } from '../run-planner-lib.mjs'
import { loadCore, planFixture, itemFixture, stages } from './workflow-harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const CLI = join(HERE, '..', 'run-planner.mjs')
const SCRIPT = join(HERE, '..', '..', 'workflows', 'implement-wave.js')
const SNAPSHOT = join(HERE, 'fixtures', 'run-planner', 'independent-two.json')
const UUID = '11111111-2222-4333-8444-555555555555'

const run = (args, opts = {}) => spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', ...opts })
const j = (v) => JSON.stringify(v)
const tmp = () => mkdtempSync(join(tmpdir(), 'artifact-guard-'))

// ---- guard truth table ----

test('guard: absent target is allowed', () => {
  assert.deepEqual(artifactWriteGuard({ targetExists: false, existingText: '', stateText: j({ phase: 'running' }), force: false }), { ok: true })
})

test('guard: state planned allows', () => {
  assert.equal(artifactWriteGuard({ targetExists: true, existingText: '{}', stateText: j({ phase: 'planned', runId: 'r1' }), force: false }).ok, true)
})

test('guard: state launched refuses and names the state runId', () => {
  const r = artifactWriteGuard({ targetExists: true, existingText: '{}', stateText: j({ phase: 'launched', runId: 'r-x' }), force: false })
  assert.equal(r.ok, false)
  assert.equal(r.runId, 'r-x')
  assert.match(r.error, /--force/)
})

test('guard: bound plan without state refuses, runId falls back to args.runId then serverRunId', () => {
  const r = artifactWriteGuard({ targetExists: true, existingText: j({ args: { runId: 'r-a', serverRunId: UUID } }), stateText: '', force: false })
  assert.equal(r.ok, false)
  assert.equal(r.runId, 'r-a')
  const r2 = artifactWriteGuard({ targetExists: true, existingText: j({ args: { serverRunId: UUID } }), stateText: '', force: false })
  assert.equal(r2.runId, UUID)
})

test('guard: unbound plan without state allows', () => {
  assert.equal(artifactWriteGuard({ targetExists: true, existingText: j({ args: { runId: 'r-a' } }), stateText: '', force: false }).ok, true)
})

test('guard: unparseable state counts as absent', () => {
  assert.equal(artifactWriteGuard({ targetExists: true, existingText: 'not json', stateText: '{{{', force: false }).ok, true)
})

test('guard: force allows a launched run', () => {
  assert.equal(artifactWriteGuard({ targetExists: true, existingText: '{}', stateText: j({ phase: 'launched' }), force: true }).ok, true)
})

// ---- CLI ----

const snapshotText = () => readFileSync(SNAPSHOT, 'utf8')

test('cli: plan --out over a launched run plan.json exits 3, file untouched; --force replaces it', () => {
  const dir = tmp()
  try {
    const target = join(dir, 'plan.json')
    writeFileSync(target, j({ args: { runId: 'r-old', serverRunId: UUID } }))
    writeFileSync(join(dir, 'state.json'), j({ phase: 'launched', runId: 'r-old' }))
    const before = readFileSync(target, 'utf8')
    const refused = run(['plan', '--out', target], { input: snapshotText() })
    assert.equal(refused.status, 3)
    assert.match(refused.stderr, /refused/)
    assert.equal(readFileSync(target, 'utf8'), before)
    const forced = run(['plan', '--out', target, '--force'], { input: snapshotText() })
    assert.equal(forced.status, 0, forced.stderr)
    assert.equal(forced.stdout, '')
    assert.notEqual(readFileSync(target, 'utf8'), before)
    assert.ok(JSON.parse(readFileSync(target, 'utf8')).args)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('cli: bind-run --out over a bound plan.json without state.json exits 3', () => {
  const dir = tmp()
  try {
    const target = join(dir, 'plan.json')
    const bound = j({ args: { serverRunId: UUID, project: { scratchDir: dir } } })
    writeFileSync(target, bound)
    const r = run(['bind-run', '--server-run', '22222222-2222-4333-8444-555555555555', '--out', target], { input: bound })
    assert.equal(r.status, 3)
    assert.equal(readFileSync(target, 'utf8'), bound)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('cli: follow-ups --out refuses an existing file in a launched run dir, writes a new name', () => {
  const dir = tmp()
  try {
    writeFileSync(join(dir, 'state.json'), j({ phase: 'running', runId: 'r-1' }))
    const input = join(dir, 'answer.json')
    writeFileSync(input, j({ ok: true, data: { results: [] } }))
    const existing = join(dir, 'result.json')
    writeFileSync(existing, 'precious')
    const refused = run(['follow-ups', '--in', input, '--out', existing])
    assert.equal(refused.status, 3)
    assert.equal(readFileSync(existing, 'utf8'), 'precious')
    const fresh = join(dir, 'follow-ups.json')
    const ok = run(['follow-ups', '--in', input, '--out', fresh])
    assert.equal(ok.status, 0, ok.stderr)
    assert.equal(ok.stdout, '')
    assert.ok(existsSync(fresh))
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('cli: --out without a value exits 2; no --out leaves stdout unchanged', () => {
  assert.equal(run(['plan', '--out']).status, 2)
  const r = run(['plan'], { input: snapshotText() })
  assert.equal(r.status, 0, r.stderr)
  assert.ok(JSON.parse(r.stdout).args)
})

/test('cli: --out --force exits 2 with a JSON refusal on stderr, no file named --force', () => {
  const dir = tmp()
  try {
    const r = run(['plan', '--out', '--force'], { input: snapshotText(), cwd: dir })
    assert.equal(r.status, 2)
    assert.match(JSON.parse(r.stderr.trim()).detail, /--out needs a path/)
    assert.equal(existsSync(join(dir, '--force')), false)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('cli: a lone --force without --out exits 2 with a JSON refusal on stderr', () => {
  const r = run(['plan', '--force'], { input: snapshotText() })
  assert.equal(r.status, 2)
  assert.equal(r.stdout, '')
  assert.match(JSON.parse(r.stderr.trim()).detail, /--force applies only with --out/)
})

// ---- prompt smoke-root line ----

const SMOKE = /throwaway run root/
function promptFor({ planner, seat = 'implementer' }) {
  const worktree = '/tmp/wt-aaaaaaaa'
  const core = loadCore(SCRIPT)
  const item = itemFixture({ short: 'aaaaaaaa', stages: stages.bugFixLike(), worktree })
  const plan = planFixture({ items: [item], repoRoot: worktree })
  const stage = item.stages.find((s) => s.seat === seat)
  return core.seatPrompt(plan, item, stage, planner ? { planner } : {})
}

test('prompt: smoke line renders for a claude-plugins path, absolute worktree path included', () => {
  assert.match(promptFor({ planner: { mainFiles: ['claude-plugins/task-orchestrator/scripts/x.mjs'] } }), SMOKE)
  assert.match(promptFor({ planner: { mainFiles: ['/tmp/wt-aaaaaaaa/claude-plugins/task-orchestrator/scripts/x.mjs'] } }), SMOKE)
})

test('prompt: smoke line absent for a current/-only item, no planner output, or a non-implementer stage', () => {
  assert.doesNotMatch(promptFor({ planner: { mainFiles: ['current/src/Main.kt'] } }), SMOKE)
  assert.doesNotMatch(promptFor({}), SMOKE)
  assert.doesNotMatch(promptFor({ planner: { mainFiles: ['claude-plugins/a.mjs'] }, seat: 'planner' }), SMOKE)
})
