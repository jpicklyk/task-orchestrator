// Unit + integration coverage for run-exec-lib.mjs / lib/wave-core.mjs (Method B executor).
// Scenario ids (S1-S16 + probes) match the item's `test-plan` note (f8d3232e).
// Oracles: the b2-b3-front-door plan (P), the B1 dispatch contract Appendix B (B1B) / B1 plan
// (B1P), and this item's own frozen dispatch-contract Appendix D (C) — never "what the code
// returns". This suite never imports run-exec-lib.mjs's, wave-core.mjs's, provenance-lib.mjs's,
// run-planner.mjs's, or workflows/implement-wave.js's own source text — only their exported
// surface, plus workflow-harness.mjs and the run-planner CLI as a subprocess.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync, execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCore as harnessLoadCore, planFixture, itemFixture, stages, fakeAgent, fakeParallel } from './workflow-harness.mjs'
import { loadWaveCore, CORE_NAMES } from '../lib/wave-core.mjs'
import {
  STATE_CONTRACT, REVIEW_RULES,
  initState, checkState, resolvePlan, findItem, findStage, outsBySeat,
  next, prompt, validateAgainst, stageResult, scanReport, resultFromState,
  verify, expectedActors, auditActors, provenance, reviewPrompt,
} from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..') // claude-plugins/task-orchestrator
const IMPLEMENT_WAVE = join(PLUGIN_ROOT, 'workflows', 'implement-wave.js')
const RUN_PLANNER = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')

function realCore() {
  return loadWaveCore()
}

// ── shared envelope/output builders (required-field shapes from the B1 contract Appendix B) ──

function outputFor(id, overrides = {}) {
  const bases = {
    'planner-v1': {
      proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
      decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
      mainFiles: [], docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
    },
    'implementer-v1': {
      mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [],
      preExistingFailures: [], publicSurface: 'none', deviations: 'none',
    },
    'declarations-v1': { declarations: 'none', harnessPointers: 'none', gaps: 'none' },
    'test-author-v1': {
      returnLine: 'none', testFiles: [], scenariosCovered: 'none', verify: [],
      redAuthorTests: [], missingDeclaration: 'none', breachDisclosure: 'none',
    },
    'generic-v1': { summary: 'none' },
  }
  return { ...bases[id], ...overrides }
}

function envelope({ status = 'done', reason = 'ok', output = {}, commits = { pre: 'p1', post: 'p2' }, files = [], modelReported = 'sonnet', notes = [], extra = {} } = {}) {
  return { status, reason, notes, commits, files, modelReported, output, ...extra }
}

function singleItemDoc(short, seatStage, overrides = {}) {
  const item = itemFixture({ short, stages: [seatStage], ...overrides })
  const args = planFixture({ items: [item] })
  return { doc: { contract: 'run-wave/plan-doc-v1', args, meta: {} }, item }
}

function runCli(args, opts = {}) {
  return spawnSync(process.execPath, [RUN_PLANNER, ...args], { encoding: 'utf8', ...opts })
}

// git helpers for the S11 temp-git-repo case (array-form execFileSync — no shell quoting risk)
function git(dir, args) {
  return execFileSync('git', args, { cwd: dir, encoding: 'utf8' })
}
function makeTempGitRepo() {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-git-'))
  git(dir, ['init', '-q'])
  git(dir, ['config', 'user.email', 'test@example.com'])
  git(dir, ['config', 'user.name', 'Test'])
  return dir
}
function makeCommit(dir, relPath, content, subject, body) {
  const full = join(dir, relPath)
  mkdirSync(dirname(full), { recursive: true })
  writeFileSync(full, content)
  git(dir, ['add', relPath])
  const args = ['commit', '-q', '-m', subject]
  if (body) args.push('-m', body)
  git(dir, args)
  return git(dir, ['rev-parse', 'HEAD']).trim()
}

// ── T-core: parity of the two ways to load the B1 core (harness vs wave-core) ────────────────

test('T-core: loadWaveCore key set matches the harness loadCore key set and CORE_NAMES (S6 parity precondition)', () => {
  const harnessCore = harnessLoadCore(IMPLEMENT_WAVE)
  const waveCore = loadWaveCore()
  assert.deepEqual(Object.keys(waveCore).sort(), Object.keys(harnessCore).sort())
  assert.deepEqual(CORE_NAMES.slice().sort(), Object.keys(waveCore).sort())
})

test('T-core: loadWaveCore throws "implement-wave.js not found at <path>" for a missing root', () => {
  const badRoot = join(HERE, 'fixtures', 'does-not-exist-plugin-root')
  assert.throws(() => loadWaveCore({ pluginRoot: badRoot }), (err) => {
    assert.equal(err.message, `implement-wave.js not found at ${join(badRoot, 'workflows', 'implement-wave.js')}`)
    return true
  })
})

test('T-core: loadWaveCore throws "markers not found in <path>" when core markers are absent (synthetic fixture)', () => {
  const scratchRoot = mkdtempSync(join(tmpdir(), 'wave-core-nomarkers-'))
  mkdirSync(join(scratchRoot, 'workflows'), { recursive: true })
  const p = join(scratchRoot, 'workflows', 'implement-wave.js')
  writeFileSync(p, 'export const meta = {}\nconst x = 1;\n', 'utf8')
  assert.throws(() => loadWaveCore({ pluginRoot: scratchRoot }), (err) => {
    assert.match(err.message, /^markers not found in /)
    return true
  })
})

test('T-core: loadWaveCore throws "core lacks <name>" for a synthetic core slice whose export evaluates to undefined', () => {
  const scratchRoot = mkdtempSync(join(tmpdir(), 'wave-core-missing-'))
  mkdirSync(join(scratchRoot, 'workflows'), { recursive: true })
  const body = CORE_NAMES
    .map((n) => {
      if (n === 'runPlan') return 'const runPlan = undefined;'
      if (n === 'ENVELOPE_VERSION') return "const ENVELOPE_VERSION = 'envelope-v1';"
      if (n === 'OUTPUT_SCHEMAS') return 'const OUTPUT_SCHEMAS = {};'
      return `const ${n} = () => {};`
    })
    .join('\n')
  writeFileSync(join(scratchRoot, 'workflows', 'implement-wave.js'), `export const meta = {}\n// @core-begin\n${body}\n// @core-end\n`, 'utf8')
  assert.throws(() => loadWaveCore({ pluginRoot: scratchRoot }), (err) => {
    assert.match(err.message, /core lacks runPlan/)
    return true
  })
})

// ── S1: next honors waitsFor milestones ───────────────────────────────────────────────────────

test('S1: next dispatches an unblocked item and reports the blocked item waiting on "<blocker>:<milestone>"', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), waitsFor: [{ item: A.id, milestone: 'implementer' }] })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B] }), meta: {} }
  const state = initState(doc, 'B')

  const r1 = next(core, doc, state)
  assert.equal(r1.dispatch.length, 1)
  assert.equal(r1.dispatch[0].item, 'aaaaaaaa')
  assert.equal(r1.dispatch[0].seat, 'planner')
  assert.deepEqual(r1.waiting, [{ item: 'bbbbbbbb', seat: 'planner', on: 'aaaaaaaa:implementer' }])
  assert.equal(r1.complete, false)

  // A's planner completes, A's implementer stage not yet reached — B still waits on the same milestone
  const plannerOut = outputFor('planner-v1')
  state.stages['aaaaaaaa:planner'] = envelope({ output: plannerOut })
  state.outs['aaaaaaaa:planner'] = plannerOut
  const r2 = next(core, doc, state)
  assert.equal(r2.dispatch.length, 1)
  assert.equal(r2.dispatch[0].item, 'aaaaaaaa')
  assert.equal(r2.dispatch[0].seat, 'implementer')
  assert.deepEqual(r2.waiting, [{ item: 'bbbbbbbb', seat: 'planner', on: 'aaaaaaaa:implementer' }])

  // A reaches the implementer milestone (done) -> B is unblocked
  state.stages['aaaaaaaa:implementer'] = envelope({ output: outputFor('implementer-v1') })
  const r3 = next(core, doc, state)
  assert.equal(r3.waiting.length, 0)
  assert.ok(r3.dispatch.some((d) => d.item === 'bbbbbbbb' && d.seat === 'planner'))
})

// ── S2: a stopped in-run blocker settles the waiter deferred ──────────────────────────────────

test('S2: a stopped blocker settles the waiting item deferred "in-run blocker <short> did not reach <seat>", run reports complete', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), waitsFor: [{ item: A.id, milestone: 'implementer' }] })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B] }), meta: {} }
  const state = initState(doc, 'B')
  state.stages['aaaaaaaa:planner'] = envelope({ output: outputFor('planner-v1') })
  state.outs['aaaaaaaa:planner'] = outputFor('planner-v1')
  state.stages['aaaaaaaa:implementer'] = envelope({ status: 'stopped', reason: 'agent threw: boom' })

  const r = next(core, doc, state)
  const settled = r.settled.find((s) => s.item === 'bbbbbbbb')
  assert.ok(settled, 'expected bbbbbbbb to be settled')
  assert.equal(settled.status, 'deferred')
  assert.equal(settled.reason, 'in-run blocker aaaaaaaa did not reach implementer')
  assert.equal(r.dispatch.length, 0)
  assert.equal(r.waiting.length, 0)
  assert.equal(r.complete, true)

  // determinism: repeating with the same (unmutated) state gives the identical result
  const r2 = next(core, doc, state)
  assert.equal(JSON.stringify(r), JSON.stringify(r2))
})

// ── S3: locks in shared mode ────────────────────────────────────────────────────────────────

test('S3: overlapping planner mainFiles serialize via locks in shared mode (args-first wins; other waits "lock file:<p>")', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B], worktreeMode: 'shared' }), meta: {} }
  const state = initState(doc, 'B')
  const plannerOutA = outputFor('planner-v1', { mainFiles: ['src/shared.js'] })
  const plannerOutB = outputFor('planner-v1', { mainFiles: ['src/shared.js'] })
  state.stages['aaaaaaaa:planner'] = envelope({ output: plannerOutA })
  state.outs['aaaaaaaa:planner'] = plannerOutA
  state.stages['bbbbbbbb:planner'] = envelope({ output: plannerOutB })
  state.outs['bbbbbbbb:planner'] = plannerOutB

  const r = next(core, doc, state)
  assert.deepEqual(r.dispatch.map((d) => d.item), ['aaaaaaaa'])
  const expectedKeys = core.lockKeysFor(A, A.stages[1], outsBySeat(A, state), doc.args)
  assert.deepEqual(r.dispatch[0].lockKeys, expectedKeys)
  assert.ok(expectedKeys.includes('file:src/shared.js'))
  const waiting = r.waiting.find((w) => w.item === 'bbbbbbbb')
  assert.ok(waiting)
  assert.equal(waiting.on, 'lock file:src/shared.js')
})

test('S3: disjoint planner mainFiles dispatch both implementer stages together in shared mode', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B], worktreeMode: 'shared' }), meta: {} }
  const state = initState(doc, 'B')
  const plannerOutA = outputFor('planner-v1', { mainFiles: ['src/a.js'] })
  const plannerOutB = outputFor('planner-v1', { mainFiles: ['src/b.js'] })
  state.stages['aaaaaaaa:planner'] = envelope({ output: plannerOutA })
  state.outs['aaaaaaaa:planner'] = plannerOutA
  state.stages['bbbbbbbb:planner'] = envelope({ output: plannerOutB })
  state.outs['bbbbbbbb:planner'] = plannerOutB

  const r = next(core, doc, state)
  assert.deepEqual(r.dispatch.map((d) => d.item).sort(), ['aaaaaaaa', 'bbbbbbbb'])
})

// ── S4: per-item mode overlap deferral ─────────────────────────────────────────────────────────

test('S4: per-item mode defers a lower-priority item whose planner output overlaps a higher one: deferred "overlap <higher-short>"', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B], worktreeMode: 'per-item' }), meta: {} }
  const state = initState(doc, 'B')
  const plannerOutA = outputFor('planner-v1', { mainFiles: ['src/shared.js'] })
  const plannerOutB = outputFor('planner-v1', { mainFiles: ['src/shared.js'] })
  state.stages['aaaaaaaa:planner'] = envelope({ output: plannerOutA })
  state.outs['aaaaaaaa:planner'] = plannerOutA
  state.stages['bbbbbbbb:planner'] = envelope({ output: plannerOutB })
  state.outs['bbbbbbbb:planner'] = plannerOutB

  const r = next(core, doc, state)
  const settled = r.settled.find((s) => s.item === 'bbbbbbbb')
  assert.ok(settled)
  assert.equal(settled.status, 'deferred')
  assert.equal(settled.reason, 'overlap aaaaaaaa')
  assert.ok(r.dispatch.some((d) => d.item === 'aaaaaaaa' && d.seat === 'implementer'))
})

// ── S5: liveness properties ──────────────────────────────────────────────────────────────────

test('S5: next never returns dispatch:[] together with a non-empty waiting list while nothing is in flight', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  const r = next(core, doc, state)
  assert.equal(r.dispatch.length === 0 && r.waiting.length > 0, false)
})

test('S5: next is deterministic — repeated calls on the same state produce identical JSON', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), waitsFor: [{ item: 'aaaaaaaa', milestone: 'implementer' }] })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B] }), meta: {} }
  const state = initState(doc, 'B')
  const r1 = next(core, doc, state)
  const r2 = next(core, doc, state)
  assert.equal(JSON.stringify(r1), JSON.stringify(r2))
})

test('S5: at most one in-flight stage per item — a dispatched first stage blocks a second stage from dispatching', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  state.stages['aaaaaaaa:planner'] = { status: 'dispatched' }
  const r = next(core, doc, state)
  assert.equal(r.dispatch.some((d) => d.item === 'aaaaaaaa'), false)
})

test('S5: a preflight-refused item (non-absolute worktree) settles as status refused and the run completes', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike(), worktree: 'relative/wt' })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  const r = next(core, doc, state)
  assert.deepEqual(r.dispatch, [])
  assert.deepEqual(r.waiting, [])
  const settled = r.settled.find((s) => s.item === 'aaaaaaaa')
  assert.ok(settled)
  assert.equal(settled.status, 'refused')
  assert.equal(settled.reason, 'worktree not absolute')
  assert.equal(r.complete, true)
})

// ── S6: Method A / Method B prompt parity ──────────────────────────────────────────────────────

test('S6: Method A (harness runPlan+fakeAgent) and a hand-rolled Method B next/prompt/stageResult loop produce byte-identical prompts per stage label', async () => {
  const core = realCore()
  const harnessCore = harnessLoadCore(IMPLEMENT_WAVE)

  const item = itemFixture({ short: '11111111', stages: stages.bugFixLike() })
  const plan = planFixture({ items: [item] })

  const scripted = {
    'planner:11111111': envelope({ output: outputFor('planner-v1', { mainFiles: ['src/x.js'], testFiles: ['scripts/tests/x.test.mjs'] }), extra: { entry: { applied: true, newRole: 'work' } } }),
    'implementer:11111111': envelope({ output: outputFor('implementer-v1'), extra: { entry: { applied: true, newRole: 'work' } } }),
    'declarations-extractor:11111111': envelope({ output: outputFor('declarations-v1', { declarations: 'the function returns a value', gaps: 'none' }) }),
    'test-author:11111111': envelope({ output: outputFor('test-author-v1') }),
  }

  // Method A
  const { agent: agentA, calls: callsA } = fakeAgent(scripted)
  await harnessCore.runPlan(plan, { agent: agentA, parallel: fakeParallel, log: () => {} })
  const promptsA = {}
  for (const c of callsA) promptsA[c.label] = c.prompt

  // Method B
  const doc = { contract: 'run-wave/plan-doc-v1', args: plan, meta: {} }
  let state = initState(doc, 'B')
  const promptsB = {}
  for (let i = 0; i < 20; i += 1) {
    const r = next(core, doc, state)
    if (r.complete) break
    for (const d of r.dispatch) {
      const label = `${d.seat}:${d.item}` // matches fakeAgent's call.label convention (seat:short)
      promptsB[label] = prompt(core, doc, state, d.item, d.seat)
      const finalText = JSON.stringify(scripted[label])
      const res = stageResult(core, doc, state, d.item, d.seat, finalText)
      assert.equal(res.status, 'done', `expected ${label} to resolve done, got ${res.status}/${res.reason}`)
      state = res.state
    }
  }

  assert.deepEqual(Object.keys(promptsB).sort(), Object.keys(promptsA).sort())
  for (const label of Object.keys(promptsA)) {
    assert.equal(promptsB[label], promptsA[label], `prompt mismatch for ${label}`)
  }
  assert.ok(promptsA['declarations-extractor:11111111'])
  assert.ok(promptsA['test-author:11111111'])
})

// ── S7: stage-result / mapStageResult rows ──────────────────────────────────────────────────────

test('S7 row: env.reason "schema-changed" -> stopped "schema-changed" (checked before stage.enters)', () => {
  const core = realCore()
  const stage = { seat: 'owner', phase: 'work', notes: [], writes: true, dispatch: {}, output: 'generic-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const env = envelope({ reason: 'schema-changed', output: outputFor('generic-v1') })
  const direct = core.mapStageResult(stage, env, doc.args.entryMode)
  assert.equal(direct.status, 'stopped')
  assert.equal(direct.reason, 'schema-changed')
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'owner', JSON.stringify(env))
  assert.equal(res.status, direct.status)
  assert.equal(res.reason, direct.reason)
})

test('S7 row: env.reason "config-unavailable" -> deferred "config-unavailable"', () => {
  const core = realCore()
  const stage = { seat: 'owner', phase: 'work', notes: [], writes: true, dispatch: {}, output: 'generic-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const env = envelope({ reason: 'config-unavailable', output: outputFor('generic-v1') })
  const direct = core.mapStageResult(stage, env, doc.args.entryMode)
  assert.equal(direct.status, 'deferred')
  assert.equal(direct.reason, 'config-unavailable')
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'owner', JSON.stringify(env))
  assert.equal(res.status, direct.status)
  assert.equal(res.reason, direct.reason)
})

test('S7 row: an entering stage with entry.applied + newRole work -> done', () => {
  const core = realCore()
  const stage = { seat: 'owner', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'implementer-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const env = envelope({ output: outputFor('implementer-v1'), extra: { entry: { applied: true, newRole: 'work' } } })
  const direct = core.mapStageResult(stage, env, doc.args.entryMode)
  assert.equal(direct.status, 'done')
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'owner', JSON.stringify(env))
  assert.equal(res.status, 'done')
  assert.equal(res.reason, direct.reason)
})

test('S7 row: an entering stage with entry.resource_unavailable -> deferred "resource <contendedResources>"', () => {
  const core = realCore()
  const stage = { seat: 'owner', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'implementer-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const env = envelope({ output: outputFor('implementer-v1'), extra: { entry: { errorCode: 'resource_unavailable', contendedResources: ['scratch-db'] } } })
  const direct = core.mapStageResult(stage, env, doc.args.entryMode)
  assert.equal(direct.status, 'deferred')
  assert.equal(direct.reason, 'resource scratch-db')
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'owner', JSON.stringify(env))
  assert.equal(res.status, direct.status)
  assert.equal(res.reason, direct.reason)
})

test('S7 row: an entering stage with entry.blockers -> deferred "blocked by <ids>"', () => {
  const core = realCore()
  const stage = { seat: 'owner', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'implementer-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const env = envelope({ output: outputFor('implementer-v1'), extra: { entry: { blockers: ['xxxx1111', 'yyyy2222'] } } })
  const direct = core.mapStageResult(stage, env, doc.args.entryMode)
  assert.equal(direct.status, 'deferred')
  assert.equal(direct.reason, 'blocked by xxxx1111,yyyy2222')
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'owner', JSON.stringify(env))
  assert.equal(res.status, direct.status)
  assert.equal(res.reason, direct.reason)
})

test('S7 row: planner-v1 proceed:false -> stopped "planner: <blockReason>"', () => {
  const core = realCore()
  const stage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const env = envelope({ output: outputFor('planner-v1', { proceed: false, blockReason: 'needs revision' }) })
  const direct = core.mapStageResult(stage, env, doc.args.entryMode)
  assert.equal(direct.status, 'stopped')
  assert.equal(direct.reason, 'planner: needs revision')
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'planner', JSON.stringify(env))
  assert.equal(res.status, direct.status)
  assert.equal(res.reason, direct.reason)
})

test('S7 row: test-author-v1 missingDeclaration != none -> stopped "missing declaration: <v>"', () => {
  const core = realCore()
  const stage = { seat: 'test-author', phase: 'work', notes: [], writes: true, dispatch: {}, output: 'test-author-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const env = envelope({ output: outputFor('test-author-v1', { missingDeclaration: 'core.mapEntry signature' }) })
  const direct = core.mapStageResult(stage, env, doc.args.entryMode)
  assert.equal(direct.status, 'stopped')
  assert.equal(direct.reason, 'missing declaration: core.mapEntry signature')
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'test-author', JSON.stringify(env))
  assert.equal(res.status, direct.status)
  assert.equal(res.reason, direct.reason)
})

// ── S8: envelope parsing / retry semantics ──────────────────────────────────────────────────────

test('S8: stageResult parses a prose-wrapped JSON envelope from agent text', () => {
  const core = realCore()
  const stage = { seat: 'owner', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'generic-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const env = envelope({ output: outputFor('generic-v1'), extra: { entry: { applied: true, newRole: 'work' } } })
  const text = `Here is my reasoning before the answer.\n${JSON.stringify(env)}\nDone.`
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'owner', text)
  assert.equal(res.status, 'done')
})

test('S8: a missing "commits" field retries once (status retry, reason "invalid envelope: ..."), then stops on the second failure', () => {
  const core = realCore()
  const stage = { seat: 'owner', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'generic-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const badEnv = { status: 'done', reason: 'ok', notes: [], files: [], modelReported: 'x', output: outputFor('generic-v1') }
  const text = JSON.stringify(badEnv)

  const first = stageResult(core, doc, state, 'aaaaaaaa', 'owner', text)
  assert.equal(first.status, 'retry')
  assert.match(first.reason, /^invalid envelope: /)
  assert.equal(first.state.stages['aaaaaaaa:owner'].status, 'retry-pending')
  assert.equal(first.state.stages['aaaaaaaa:owner'].envelopeRetries, 1)

  const second = stageResult(core, doc, first.state, 'aaaaaaaa', 'owner', text)
  assert.equal(second.status, 'stopped')
  assert.equal(second.reason, 'invalid envelope')
})

test('S8: empty/whitespace agent text is treated as unparseable and retries', () => {
  const core = realCore()
  const stage = { seat: 'owner', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'generic-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'owner', '   ')
  assert.equal(res.status, 'retry')
})

// ── S9: state — unknown keys survive; outs hold unredacted output ──────────────────────────────

test('S9: unknown top-level and stage-entry keys survive next() (identical result with/without) and stageResult()', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  state.helper = '/path/to/helper'
  state.wfRunId = 'wf-123'
  state.wfScriptPath = '/path/script.js'
  state.helperFallback = true
  state.x = 'unrelated'
  state.stages['aaaaaaaa:planner'] = { status: 'dispatched', someExtra: 'kept' }

  const withUnknown = next(core, doc, state)

  const bareState = initState(doc, 'B')
  bareState.stages['aaaaaaaa:planner'] = { status: 'dispatched' }
  const withoutUnknown = next(core, doc, bareState)
  assert.deepEqual(withUnknown, withoutUnknown)

  const env = envelope({ output: outputFor('planner-v1') })
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'planner', JSON.stringify(env))
  assert.equal(res.state.helper, '/path/to/helper')
  assert.equal(res.state.wfRunId, 'wf-123')
  assert.equal(res.state.wfScriptPath, '/path/script.js')
  assert.equal(res.state.helperFallback, true)
  assert.equal(res.state.x, 'unrelated')
})

test('S9: outs stores the UNREDACTED stage output even when it contains scan words', () => {
  const core = realCore()
  const stage = { seat: 'declarations-extractor', phase: 'work', inserted: true, notes: [], writes: false, dispatch: {}, output: 'declarations-v1' }
  const { doc } = singleItemDoc('aaaaaaaa', stage)
  const state = initState(doc, 'B')
  const declOutput = outputFor('declarations-v1', { declarations: 'this returns a value and throws on failure' })
  const env = envelope({ output: declOutput })
  const res = stageResult(core, doc, state, 'aaaaaaaa', 'declarations-extractor', JSON.stringify(env))
  assert.deepEqual(res.state.outs['aaaaaaaa:declarations-extractor'], declOutput)
  assert.match(res.state.outs['aaaaaaaa:declarations-extractor'].declarations, /returns/)
})

// ── S10: scan-declarations ──────────────────────────────────────────────────────────────────────

test('S10: scanReport finds behaviour-word hits with 1-based line numbers; the "runtime call order:" line is exempt', () => {
  const core = realCore()
  const text = [
    'Line one is plain.',
    'This function returns a value when called.',
    'runtime call order: calls foo if bar otherwise baz.',
    'A boundary line with the word if in it.',
  ].join('\n')
  const report = scanReport(core, text)
  assert.equal(report.hits.some((h) => h.line === 2 && h.word === 'returns'), true)
  assert.equal(report.hits.some((h) => h.line === 3), false, 'the "runtime call order:" line is exempt')
  assert.equal(report.hits.some((h) => h.word === 'if' && h.line === 4), true)
  assert.equal(report.clean, false)
  assert.equal(report.redacted, core.scanDeclarations(text).text)
  assert.equal(report.hits.length, core.scanDeclarations(text).stripped.length)
})

test('S10: scanReport is clean with no hits and redacted text equals core.scanDeclarations(text).text', () => {
  const core = realCore()
  const text = 'export function foo(x) {}\nconst BAR = 1;'
  const report = scanReport(core, text)
  assert.equal(report.clean, true)
  assert.deepEqual(report.hits, [])
  assert.equal(report.redacted, core.scanDeclarations(text).text)
})

// ── S11: verify — pure gitFacts cases + one temp-git CLI case ──────────────────────────────────

function verifyFixture() {
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: { agent: 'task-orchestrator:implementer' }, output: 'implementer-v1' }
  const testStage = { seat: 'test-author', phase: 'work', writes: true, notes: [], dispatch: {}, output: 'test-author-v1', readsExclude: [] }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage, testStage] })
  const args = planFixture({ items: [item] })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const plannerOut = outputFor('planner-v1', { mainFiles: ['src/x.js'], testFiles: ['scripts/tests/x.test.mjs'] })
  return { doc, item, plannerOut }
}

test('S11: verify passes (ok:true, no findings) for a fully owned, well-formed commit set', () => {
  const { doc, item, plannerOut } = verifyFixture()
  const implOut = outputFor('implementer-v1')
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'aaaa000', post: 'bbbb111' }, files: ['src/x.js'] },
        { seat: 'test-author', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'cccc222', post: 'dddd333' }, files: ['scripts/tests/x.test.mjs'] },
      ],
      outputs: { planner: plannerOut, implementer: implOut },
    }],
    refused: [], deferred: [],
  }
  const gitFacts = {
    exists: { aaaa000: true, bbbb111: true, cccc222: true, dddd333: true },
    commits: [
      { sha: 'bbbb111', subject: 'feat(x): implement [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] },
      { sha: 'dddd333', subject: 'test(x): tests [aaaaaaaa]', body: 'why\n\nSeat: test-author', files: ['scripts/tests/x.test.mjs'] },
    ],
  }
  const out = verify(doc, result, gitFacts)
  const row = out.items.find((i) => i.short === 'aaaaaaaa')
  assert.ok(row)
  assert.equal(row.ok, true)
  assert.deepEqual(row.findings, [])
  assert.equal(out.ok, true)
})

test('S11: verify reports "missing sha <sha>" when a stage commit is absent from gitFacts.exists', () => {
  const { doc, item, plannerOut } = verifyFixture()
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'aaaa000', post: 'ffff999' }, files: ['src/x.js'] },
      ],
      outputs: { planner: plannerOut, implementer: outputFor('implementer-v1') },
    }],
    refused: [], deferred: [],
  }
  const gitFacts = { exists: { aaaa000: true, ffff999: false }, commits: [{ sha: 'ffff999', subject: 'feat(x): implement [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] }] }
  const out = verify(doc, result, gitFacts)
  const row = out.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(row.ok, false)
  assert.ok(row.findings.includes('missing sha ffff999'))
  assert.equal(out.ok, false)
})

test('S11: verify reports "missing Seat trailer <sha7>" when a writing stage commit body lacks the trailer', () => {
  const { doc, item, plannerOut } = verifyFixture()
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'aaaa000', post: '1234567890abcdef' }, files: ['src/x.js'] },
      ],
      outputs: { planner: plannerOut, implementer: outputFor('implementer-v1') },
    }],
    refused: [], deferred: [],
  }
  const gitFacts = {
    exists: { aaaa000: true, '1234567890abcdef': true },
    commits: [{ sha: '1234567890abcdef', subject: 'feat(x): implement [aaaaaaaa]', body: 'why, no trailer here', files: ['src/x.js'] }],
  }
  const out = verify(doc, result, gitFacts)
  const row = out.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(row.ok, false)
  assert.ok(row.findings.includes('missing Seat trailer 1234567'))
})

test('S11: verify reports "implementer wrote unowned <file>" when the implementer commit touches a file outside mainFiles/docFiles', () => {
  const { doc, item, plannerOut } = verifyFixture()
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'aaaa000', post: 'bbbb111' }, files: ['src/x.js', 'src/unowned.js'] },
      ],
      outputs: { planner: plannerOut, implementer: outputFor('implementer-v1') },
    }],
    refused: [], deferred: [],
  }
  const gitFacts = {
    exists: { aaaa000: true, bbbb111: true },
    commits: [{ sha: 'bbbb111', subject: 'feat(x): implement [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js', 'src/unowned.js'] }],
  }
  const out = verify(doc, result, gitFacts)
  const row = out.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(row.ok, false)
  assert.ok(row.findings.includes('implementer wrote unowned src/unowned.js'))
})

test('S11: verify reports "test-author commit <sha7> precedes implementer" when the author\'s commit is earlier in gitFacts.commits', () => {
  const { doc, item, plannerOut } = verifyFixture()
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'aaaa000', post: 'bbbb111' }, files: ['src/x.js'] },
        { seat: 'test-author', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '9999999', post: '8888888' }, files: ['scripts/tests/x.test.mjs'] },
      ],
      outputs: { planner: plannerOut, implementer: outputFor('implementer-v1') },
    }],
    refused: [], deferred: [],
  }
  // test-author's commit (8888888) appears BEFORE implementer's (bbbb111) in oldest-first order
  const gitFacts = {
    exists: { aaaa000: true, bbbb111: true, '9999999': true, '8888888': true },
    commits: [
      { sha: '8888888', subject: 'test(x): tests [aaaaaaaa]', body: 'why\n\nSeat: test-author', files: ['scripts/tests/x.test.mjs'] },
      { sha: 'bbbb111', subject: 'feat(x): implement [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] },
    ],
  }
  const out = verify(doc, result, gitFacts)
  const row = out.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(row.ok, false)
  assert.ok(row.findings.includes('test-author commit 8888888 precedes implementer'))
})

test('S11: verify puts an untagged commit (no [<short>] in subject) into warnings, not findings', () => {
  const { doc, item, plannerOut } = verifyFixture()
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'aaaa000', post: 'bbbb111' }, files: ['src/x.js'] },
      ],
      outputs: { planner: plannerOut, implementer: outputFor('implementer-v1') },
    }],
    refused: [], deferred: [],
  }
  const gitFacts = {
    exists: { aaaa000: true, bbbb111: true, ffffeee: true },
    commits: [
      { sha: 'bbbb111', subject: 'feat(x): implement [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] },
      { sha: 'ffffeee', subject: 'chore: unrelated cleanup', body: 'no tag here', files: ['README.md'] },
    ],
  }
  const out = verify(doc, result, gitFacts)
  assert.ok(out.warnings.some((w) => w.includes('untagged commit ffffee')))
})

test('S11 (temp-git CLI): the verify CLI row exits 0 for a clean repo and 3 for a repo missing a declared sha', () => {
  const repo = makeTempGitRepo()
  const baseSha = makeCommit(repo, 'README.md', '# base\n', 'chore: base commit')
  const implSha = makeCommit(repo, 'src/x.js', 'module.exports = 1;\n', 'feat(x): implement [aaaaaaaa]', 'why\n\nSeat: implementer')

  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage], worktree: repo })
  const args = planFixture({ items: [item], baseSha })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const plannerOut = outputFor('planner-v1', { mainFiles: ['src/x.js'] })
  const okResult = {
    contract: 'implement-wave/result-v1', started: true, runId: args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: baseSha, post: baseSha }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: baseSha, post: implSha }, files: ['src/x.js'] },
      ],
      outputs: { planner: plannerOut, implementer: outputFor('implementer-v1') },
    }],
    refused: [], deferred: [],
  }
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-verify-'))
  const planPath = join(dir, 'plan.json')
  const resultPath = join(dir, 'result.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(resultPath, JSON.stringify(okResult))
  const okRes = runCli(['verify', '--plan', planPath, '--result', resultPath])
  assert.equal(okRes.status, 0, okRes.stderr)
  assert.equal(JSON.parse(okRes.stdout).ok, true)

  // now reference a sha that was never committed -> exit 3
  const badResult = JSON.parse(JSON.stringify(okResult))
  badResult.items[0].stages[1].commits.post = 'ffffffffffffffffffffffffffffffffffffff'
  const badResultPath = join(dir, 'result-bad.json')
  writeFileSync(badResultPath, JSON.stringify(badResult))
  const badRes = runCli(['verify', '--plan', planPath, '--result', badResultPath])
  assert.equal(badRes.status, 3)
  assert.equal(JSON.parse(badRes.stdout).ok, false)
})

// ── S12: actors ──────────────────────────────────────────────────────────────────────────────

test('S12: expectedActors lists one row per stage note in args/stage/note order, actorId "<seat>:<short>:<runId>", excluding inserted-stage and orchestratorNotes rows', () => {
  const plannerStage = { seat: 'planner', phase: 'queue', notes: ['specification'], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: ['session-tracking', 'implementation-notes'], dispatch: {}, output: 'implementer-v1' }
  const insertedStage = { seat: 'declarations-extractor', phase: 'work', inserted: true, notes: [], writes: false, dispatch: {}, output: 'declarations-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage, insertedStage], orchestratorNotes: ['delegation-metadata'] })
  const args = planFixture({ items: [item], runId: 'r-test-9999' })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }

  const rows = expectedActors(doc)
  assert.deepEqual(rows, [
    { itemId: item.id, key: 'specification', actorId: 'planner:aaaaaaaa:r-test-9999' },
    { itemId: item.id, key: 'session-tracking', actorId: 'implementer:aaaaaaaa:r-test-9999' },
    { itemId: item.id, key: 'implementation-notes', actorId: 'implementer:aaaaaaaa:r-test-9999' },
  ])
  assert.equal(rows.some((r) => r.key === 'delegation-metadata'), false)
})

test('S12: auditActors reports missing and mismatched actor ids against the expected table', () => {
  const plannerStage = { seat: 'planner', phase: 'queue', notes: ['specification'], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: ['session-tracking'], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage] })
  const args = planFixture({ items: [item], runId: 'r-test-1234' })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }

  const observed = [{ itemId: item.id, key: 'specification', actorId: 'wrong-actor' }]
  const audit = auditActors(doc, observed)
  const row = audit.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(audit.ok, false)
  assert.equal(row.ok, false)
  assert.deepEqual(row.missing, ['session-tracking'])
  assert.deepEqual(row.mismatched, [{ key: 'specification', expected: 'planner:aaaaaaaa:r-test-1234', actual: 'wrong-actor' }])
})

test('S12: auditActors is ok:true when every expected actor id matches', () => {
  const plannerStage = { seat: 'planner', phase: 'queue', notes: ['specification'], writes: false, dispatch: {}, output: 'planner-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage] })
  const args = planFixture({ items: [item], runId: 'r-test-4321' })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const observed = [{ itemId: item.id, key: 'specification', actorId: 'planner:aaaaaaaa:r-test-4321' }]
  const audit = auditActors(doc, observed)
  assert.equal(audit.ok, true)
})

// ── S13 (run-exec-lib half): provenance() ───────────────────────────────────────────────────────

function provenanceFixture(runId) {
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: { model: 'opus' }, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: { model: 'sonnet' }, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage], worktree: '/repo/.claude/worktrees/x-aaaaaaaa' })
  const args = planFixture({ items: [item], runId })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: { inRunEdges: [] } }
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId, planDocSlug: `run/${runId}`,
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'p', post: 'q' }, files: [] },
      ],
      outputs: {},
    }],
    refused: [], deferred: [],
  }
  return { doc, item, result }
}

test('S13: run-exec-lib provenance() defaults model-source to self-report and adapter to claude-workflow for method A', () => {
  const { doc, item, result } = provenanceFixture('r-test-0007')
  const lines = provenance({ core: realCore(), doc, result, method: 'A', turns: 2 })
  const line = lines[item.id]
  assert.match(line, /^adapter=claude-workflow /)
  assert.match(line, /model-source=self-report/)
  assert.match(line, /\bagents=2\b/)
  assert.match(line, /orchestrator-turns=2\b/)
})

test('S13: run-exec-lib provenance() uses adapter=claude-agent for method B', () => {
  const { doc, item, result } = provenanceFixture('r-test-0008')
  const lines = provenance({ core: realCore(), doc, result, method: 'B', turns: 1 })
  assert.match(lines[item.id], /^adapter=claude-agent /)
})

// ── S14: CLI rows / exit codes ───────────────────────────────────────────────────────────────

function writeCliDoc(dir) {
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const args = planFixture({ items: [A] })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const state = initState(doc, 'B')
  const planPath = join(dir, 'plan.json')
  const statePath = join(dir, 'state.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(statePath, JSON.stringify(state))
  return { planPath, statePath, doc, state }
}

test('S14: CLI next --plan --state exits 0 on valid input', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-'))
  const { planPath, statePath } = writeCliDoc(dir)
  const res = runCli(['next', '--plan', planPath, '--state', statePath])
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.ok(Array.isArray(out.dispatch))
})

test('S14: CLI exits 2 on malformed JSON input', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-'))
  const planPath = join(dir, 'plan.json')
  const statePath = join(dir, 'state.json')
  writeFileSync(planPath, '{not valid json')
  writeFileSync(statePath, '{}')
  const res = runCli(['next', '--plan', planPath, '--state', statePath])
  assert.equal(res.status, 2)
  const err = JSON.parse(res.stderr)
  assert.ok(err.error)
})

test('S14: CLI exits 2 for an unknown item id', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-'))
  const { planPath, statePath } = writeCliDoc(dir)
  const res = runCli(['prompt', '--plan', planPath, '--state', statePath, '--item', 'zzzzzzzz', '--seat', 'planner'])
  assert.equal(res.status, 2)
  const err = JSON.parse(res.stderr)
  assert.equal(err.error, 'invalid args')
  assert.match(err.detail, /unknown item zzzzzzzz/)
})

test('S14: CLI exits 2 for an unknown seat on a known item', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-'))
  const { planPath, statePath } = writeCliDoc(dir)
  const res = runCli(['prompt', '--plan', planPath, '--state', statePath, '--item', 'aaaaaaaa', '--seat', 'nope'])
  assert.equal(res.status, 2)
  const err = JSON.parse(res.stderr)
  assert.equal(err.error, 'invalid args')
  assert.match(err.detail, /unknown seat nope for aaaaaaaa/)
})

test('S14: CLI exits 2 on a state runId mismatch (Appendix D exit-2 list; RED if the CLI does not enforce checkState — see test-manifest)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-'))
  const { planPath, statePath, state } = writeCliDoc(dir)
  const mismatched = { ...state, runId: 'r-different' }
  writeFileSync(statePath, JSON.stringify(mismatched))
  const res = runCli(['next', '--plan', planPath, '--state', statePath])
  assert.equal(res.status, 2)
  assert.match(JSON.parse(res.stderr).detail ?? JSON.parse(res.stderr).error ?? '', /state runId mismatch/)
})

test('S14: CLI actors --plan --notes exits 3 when an expected actor row is missing', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-'))
  const plannerStage = { seat: 'planner', phase: 'queue', notes: ['specification'], writes: false, dispatch: {}, output: 'planner-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage] })
  const args = planFixture({ items: [item], runId: 'r-test-cli-actors' })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const planPath = join(dir, 'plan.json')
  const notesPath = join(dir, 'notes.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(notesPath, JSON.stringify([]))
  const res = runCli(['actors', '--plan', planPath, '--notes', notesPath])
  assert.equal(res.status, 3)
  assert.equal(JSON.parse(res.stdout).ok, false)
})

test('S14: CLI actors --plan (no --notes) is audit-free and exits 0', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-'))
  const { planPath } = writeCliDoc(dir)
  const res = runCli(['actors', '--plan', planPath])
  assert.equal(res.status, 0, res.stderr)
})

// ── S15: review-prompt ───────────────────────────────────────────────────────────────────────

test('S15: reviewPrompt includes the seat line, run id, scoped diff command, REVIEW_RULES keys, and the reviewer actor id', () => {
  const core = realCore()
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', title: 'Sample Item', stages: [plannerStage, implStage], worktree: '/repo/.claude/worktrees/x-aaaaaaaa' })
  const args = planFixture({ items: [item], runId: 'r-test-0055', baseSha: 'e77c3e95' })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }

  const text = reviewPrompt(core, doc, 'aaaaaaaa')
  assert.match(text, /SEAT: reviewer for item .*\(aaaaaaaa\)/)
  assert.match(text, /Run r-test-0055/)
  assert.match(text, /git -C \/repo\/\.claude\/worktrees\/x-aaaaaaaa diff e77c3e95\.\.HEAD/)
  for (const key of REVIEW_RULES) {
    assert.ok(text.includes(key), `expected review-prompt to reference rule key ${key}`)
  }
  assert.match(text, /reviewer:aaaaaaaa:r-test-0055/)
  assert.match(text, /query_rules/)
})

test('S15: reviewPrompt with a result narrows the diff to the owned files from the planner output', () => {
  const core = realCore()
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage], worktree: '/repo/wt-aaaaaaaa' })
  const args = planFixture({ items: [item], runId: 'r-test-0056', baseSha: 'e77c3e95' })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const plannerOut = outputFor('planner-v1', { mainFiles: ['src/x.js'], docFiles: ['docs/x.md'] })
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: 'r-test-0056', planDocSlug: 'run/r-test-0056',
    items: [{ id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok', stages: [], outputs: { planner: plannerOut } }],
    refused: [], deferred: [],
  }
  const text = reviewPrompt(core, doc, 'aaaaaaaa', result)
  assert.match(text, /src\/x\.js/)
  assert.match(text, /docs\/x\.md/)
})

test('S15: reviewPrompt without a result states "owned files: planner output" and no pathspec', () => {
  const core = realCore()
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage], worktree: '/repo/wt-aaaaaaaa' })
  const args = planFixture({ items: [item], runId: 'r-test-0057', baseSha: 'e77c3e95' })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const text = reviewPrompt(core, doc, 'aaaaaaaa')
  assert.match(text, /owned files: planner output/)
})

// ── S16: every B2a fixture plan passes core normalizeArgs + preflight ──────────────────────────

test('S16: every B2a run-planner snapshot fixture, when planned, produces args that pass core normalizeArgs + preflight', () => {
  const core = realCore()
  const files = readdirSync(FIXTURES).filter((f) => f.endsWith('.json') && f !== 'args-v1.schema.json' && f !== 'plan-doc-stale.json')
  assert.ok(files.length > 0, 'expected snapshot fixtures under fixtures/run-planner/')
  let checked = 0
  for (const file of files) {
    const scratch = mkdtempSync(join(tmpdir(), 'b2a-scratch-'))
    const res = spawnSync(process.execPath, [RUN_PLANNER, 'plan', '--in', join(FIXTURES, file), '--now', '2026-09-28T12:00:00Z', '--scratchpad', scratch], { encoding: 'utf8' })
    assert.ok(res.status === 0 || res.status === 3, `unexpected exit ${res.status} for ${file}: ${res.stderr}`)
    const out = JSON.parse(res.stdout) // plan-doc-v1: {contract, args, meta}; args is null on a refusal
    if (!out.args) continue // a legitimate refusal has nothing to validate against the core
    checked += 1
    const normalized = core.normalizeArgs(out.args)
    assert.equal(normalized.ok, true, `normalizeArgs failed for ${file}: ${normalized.reason}`)
    assert.doesNotThrow(() => core.preflight(normalized.plan), `preflight threw for ${file}`)
  }
  assert.ok(checked > 0, 'expected at least one fixture to produce a validatable plan')
})

// ── Probes ───────────────────────────────────────────────────────────────────────────────────

test('probe: preflight refuses a duplicate seat within one item', () => {
  const core = realCore()
  const s1 = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const s2 = { seat: 'implementer', phase: 'work', writes: true, notes: [], dispatch: {}, output: 'generic-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [s1, s2] })
  const args = planFixture({ items: [item] })
  const normalized = core.normalizeArgs(args)
  assert.equal(normalized.ok, true)
  const pf = core.preflight(normalized.plan)
  const refused = pf.refused.find((r) => r.id === item.id)
  assert.ok(refused)
  assert.equal(refused.reason, 'duplicate seat implementer')
})

test('probe: a backslash worktree path normalizes to forward slashes and a lowercased drive letter', () => {
  const core = realCore()
  assert.equal(core.normalizePath('C:\\repo\\wt'), 'c:/repo/wt')
  assert.equal(core.normalizePath('./relative\\path'), 'relative/path')
})

test('probe: CLI exits 2 for an uppercase form of a real short id ("unknown item")', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-'))
  const { planPath, statePath } = writeCliDoc(dir)
  const res = runCli(['prompt', '--plan', planPath, '--state', statePath, '--item', 'AAAAAAAA', '--seat', 'planner'])
  assert.equal(res.status, 2)
  const err = JSON.parse(res.stderr)
  assert.equal(err.error, 'invalid args')
  assert.match(err.detail, /unknown item AAAAAAAA/)
})

test('probe: next/prompt tolerate an absent state.outs entry for a not-yet-run stage; outsBySeat returns {} (defined-only)', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  assert.doesNotThrow(() => next(core, doc, state))
  assert.doesNotThrow(() => prompt(core, doc, state, 'aaaaaaaa', 'planner'))
  assert.deepEqual(outsBySeat(A, state), {})
})

// ── Follow-up (reviewer-reproduced blockers B1-B6, O2) ──────────────────────────────────────────
// Oracles: Appendix D (verify/resultFromState/next/provenance semantics), the b2-b3-front-door
// plan §7-§8, master §6.3.5 (runPlan's "items: runnable items in args order" contract cited from
// the B1 dispatch-contract Appendix B), and the coordinator's follow-up dispatch itself (B3's
// same-pass cascade requirement, B5's state-v1/result-v1 CLI equivalence, O6's CRLF tolerance).
// These are expected to be RED against HEAD until the implementer's fix lands; per rule 7 they
// are left red, not weakened.

test('B1 (CLI, real git log): verify surfaces a per-commit unowned-file finding from a realistic multi-commit repo (real files must not always be [])', () => {
  const repo = makeTempGitRepo()
  const baseSha = makeCommit(repo, 'README.md', '# base\n', 'chore: base commit')
  makeCommit(repo, 'src/x.js', 'module.exports = 1;\n', 'feat(x): implement [aaaaaaaa]', 'why\n\nSeat: implementer')
  makeCommit(repo, 'src/unowned.js', 'module.exports = 2;\n', 'feat(x): more work [aaaaaaaa]', 'why\n\nSeat: implementer')
  const headSha = git(repo, ['rev-parse', 'HEAD']).trim()

  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage], worktree: repo })
  const args = planFixture({ items: [item], baseSha })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const plannerOut = outputFor('planner-v1', { mainFiles: ['src/x.js'] }) // src/unowned.js is NOT declared owned
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: baseSha, post: baseSha }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: baseSha, post: headSha }, files: [] },
      ],
      outputs: { planner: plannerOut, implementer: outputFor('implementer-v1') },
    }],
    refused: [], deferred: [],
  }
  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-verify-b1-'))
  const planPath = join(dir, 'plan.json')
  const resultPath = join(dir, 'result.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(resultPath, JSON.stringify(result))
  const res = runCli(['verify', '--plan', planPath, '--result', resultPath])
  assert.equal(res.status, 3, `expected verify to fail on the unowned file but got exit ${res.status}: ${res.stdout}`)
  const out = JSON.parse(res.stdout)
  assert.equal(out.ok, false)
  const row = out.items.find((i) => i.short === 'aaaaaaaa')
  assert.ok(row.findings.some((f) => f.includes('unowned') && f.includes('src/unowned.js')), `expected an unowned-file finding, got: ${JSON.stringify(row.findings)}`)
})

test('B2: verify attributes each stage\'s (pre..post] commits correctly — a normal implementer->test-author chain yields no false findings', () => {
  const { doc, item, plannerOut } = verifyFixture()
  const implOut = outputFor('implementer-v1')
  const baseSha = 'a'.repeat(40)
  const implSha = 'b'.repeat(40)
  const authorSha = 'c'.repeat(40)
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: baseSha, post: baseSha }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: baseSha, post: implSha }, files: ['src/x.js'] },
        { seat: 'test-author', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: implSha, post: authorSha }, files: ['scripts/tests/x.test.mjs'] },
      ],
      outputs: { planner: plannerOut, implementer: implOut },
    }],
    refused: [], deferred: [],
  }
  // gitFacts oldest-first: the implementer's commit (sha === test-author's own `pre`) must NOT be
  // attributed to the test-author stage — only commits strictly after `pre` up to `post` belong to it.
  const gitFacts = {
    exists: { [baseSha]: true, [implSha]: true, [authorSha]: true },
    commits: [
      { sha: implSha, subject: 'feat(x): implement [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] },
      { sha: authorSha, subject: 'test(x): tests [aaaaaaaa]', body: 'why\n\nSeat: test-author', files: ['scripts/tests/x.test.mjs'] },
    ],
  }
  const out = verify(doc, result, gitFacts)
  const row = out.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(row.ok, true, `expected no false findings for a normal impl->author chain, got: ${JSON.stringify(row.findings)}`)
  assert.deepEqual(row.findings, [])
})

test('B2\': a first writing stage whose `pre` is the unresolved wave baseSha attributes an unowned-file finding to its own first commit; a later stage\'s commit inside that naive range still yields "precedes", not an ownership finding', () => {
  // Oracle: Appendix D verify findings + the frozen range rule "(pre..post]; when pre does not
  // resolve, from the first unattributed commit through post" (coordinator follow-up).
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const testStage = { seat: 'test-author', phase: 'work', writes: true, notes: [], dispatch: {}, output: 'test-author-v1', readsExclude: [] }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage, testStage] })
  const baseSha = 'a'.repeat(40) // the wave's baseSha — the implementer stage's `pre`, absent from gitFacts.commits
  const args = planFixture({ items: [item], baseSha })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const plannerOut = outputFor('planner-v1', { mainFiles: ['src/x.js'], testFiles: ['scripts/tests/x.test.mjs'] })
  const implOut = outputFor('implementer-v1')

  const implSha1 = 'b'.repeat(40) // implementer's FIRST commit — touches an unowned file
  const authorSha = 'c'.repeat(40) // test-author's real commit — appears BEFORE implementer's post
  const implPostSha = 'd'.repeat(40) // implementer's declared `post` — chronologically LAST

  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: baseSha, post: baseSha }, files: [] },
        { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: baseSha, post: implPostSha }, files: [] },
        { seat: 'test-author', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: implPostSha, post: authorSha }, files: [] },
      ],
      outputs: { planner: plannerOut, implementer: implOut },
    }],
    refused: [], deferred: [],
  }
  const gitFacts = {
    exists: { [baseSha]: true, [implSha1]: true, [authorSha]: true, [implPostSha]: true },
    commits: [
      { sha: implSha1, subject: 'feat(x): step1 [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js', 'src/unowned.js'] },
      { sha: authorSha, subject: 'test(x): early tests [aaaaaaaa]', body: 'why\n\nSeat: test-author', files: ['scripts/tests/x.test.mjs'] },
      { sha: implPostSha, subject: 'feat(x): finish [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] },
    ],
  }
  const out = verify(doc, result, gitFacts)
  const row = out.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(row.ok, false)
  assert.ok(
    row.findings.includes('implementer wrote unowned src/unowned.js'),
    `expected the first stage's own first commit to be attributed to it (pre unresolved -> from the first unattributed commit through post); findings: ${JSON.stringify(row.findings)}`
  )
  assert.ok(
    row.findings.includes('test-author commit ccccccc precedes implementer'),
    `expected the out-of-order test-author commit to yield the "precedes" finding; findings: ${JSON.stringify(row.findings)}`
  )
  assert.equal(
    row.findings.some((f) => f.includes('scripts/tests/x.test.mjs') && !f.startsWith('test-author commit')),
    false,
    `the test-author-trailer commit inside implementer's naive range must not ALSO produce an ownership finding for it; findings: ${JSON.stringify(row.findings)}`
  )
})

test('B3: next settles an entire in-run dependency chain in ONE call when the root blocker is stopped (A->B->C, same-pass cascade)', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), waitsFor: [{ item: A.id, milestone: 'implementer' }] })
  const C = itemFixture({ short: 'cccccccc', stages: stages.featureTaskLike(), waitsFor: [{ item: B.id, milestone: 'implementer' }] })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B, C] }), meta: {} }
  const state = initState(doc, 'B')
  state.stages['aaaaaaaa:planner'] = envelope({ output: outputFor('planner-v1') })
  state.outs['aaaaaaaa:planner'] = outputFor('planner-v1')
  state.stages['aaaaaaaa:implementer'] = envelope({ status: 'stopped', reason: 'agent threw: boom' })

  const r = next(core, doc, state)
  const bSettled = r.settled.find((s) => s.item === 'bbbbbbbb')
  const cSettled = r.settled.find((s) => s.item === 'cccccccc')
  assert.ok(bSettled, 'expected bbbbbbbb to be settled in this same call')
  assert.equal(bSettled.status, 'deferred')
  assert.ok(cSettled, 'expected cccccccc to ALSO be settled in this SAME call (same-pass cascade), not left waiting forever')
  assert.equal(cSettled.status, 'deferred')
  assert.equal(r.dispatch.length, 0)
  assert.equal(r.waiting.length, 0)
  assert.equal(r.complete, true)
})

test('B4: resultFromState reports a deferred stage\'s own reason, not "agent returned null"', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  state.stages['aaaaaaaa:planner'] = { status: 'deferred', reason: 'overlap bbbbbbbb' }
  const result = resultFromState(core, doc, state)
  const item = result.items.find((i) => i.short === 'aaaaaaaa')
  assert.ok(item)
  assert.equal(item.status, 'deferred')
  assert.equal(item.reason, 'overlap bbbbbbbb')
})

test('B4: resultFromState lists a preflight-refused item only under result.refused, matching runPlan\'s "items: runnable items" contract (B1 Appendix B)', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike(), worktree: 'relative/wt' })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  const result = resultFromState(core, doc, state)
  assert.ok(result.refused.some((r) => r.id === A.id || r.id === 'aaaaaaaa'))
  assert.equal(result.items.some((i) => i.short === 'aaaaaaaa'), false, 'a refused item must not also appear in items — runPlan lists only runnable items there')
})

test('B5 (CLI, real git log): verify given a run-wave/state-v1 --result behaves identically to the equivalent result-v1', () => {
  const repo = makeTempGitRepo()
  const baseSha = makeCommit(repo, 'README.md', '# base\n', 'chore: base commit')
  const bogusSha = '9'.repeat(40) // never actually committed

  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage], worktree: repo })
  const args = planFixture({ items: [item], baseSha })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }

  const state = initState(doc, 'B')
  state.stages['aaaaaaaa:planner'] = envelope({ output: outputFor('planner-v1'), commits: { pre: baseSha, post: baseSha } })
  state.outs['aaaaaaaa:planner'] = outputFor('planner-v1')
  state.stages['aaaaaaaa:implementer'] = envelope({
    output: outputFor('implementer-v1'), commits: { pre: baseSha, post: bogusSha },
    extra: { entry: { applied: true, newRole: 'work' } },
  })

  const core = realCore()
  const result = resultFromState(core, doc, state)

  const dir = mkdtempSync(join(tmpdir(), 'run-exec-cli-verify-b5-'))
  const planPath = join(dir, 'plan.json')
  const statePath = join(dir, 'state-as-result.json')
  const resultPath = join(dir, 'result.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(statePath, JSON.stringify(state))
  writeFileSync(resultPath, JSON.stringify(result))

  const viaResult = runCli(['verify', '--plan', planPath, '--result', resultPath])
  const viaState = runCli(['verify', '--plan', planPath, '--result', statePath])

  assert.equal(viaResult.status, 3, `sanity: expected the result-v1 run to fail on the missing sha, got ${viaResult.status}: ${viaResult.stdout}`)
  assert.equal(viaState.status, viaResult.status, `state-v1 --result must behave identically to the equivalent result-v1; got exit ${viaState.status} vs ${viaResult.status}`)
  assert.deepEqual(JSON.parse(viaState.stdout), JSON.parse(viaResult.stdout))
})

test('O2/S5: the liveness invariant also holds at a later, non-initial state — not only from a blank state', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), waitsFor: [{ item: A.id, milestone: 'implementer' }] })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B] }), meta: {} }
  const state = initState(doc, 'B')
  state.stages['aaaaaaaa:planner'] = envelope({ output: outputFor('planner-v1') })
  state.outs['aaaaaaaa:planner'] = outputFor('planner-v1')
  state.stages['aaaaaaaa:implementer'] = envelope({ output: outputFor('implementer-v1') })
  state.outs['aaaaaaaa:implementer'] = outputFor('implementer-v1')
  const r = next(core, doc, state)
  assert.equal(r.dispatch.length === 0 && r.waiting.length > 0, false)
  assert.ok(r.dispatch.some((d) => d.item === 'bbbbbbbb'))
})

// ── T-*: small direct-export unit coverage (initState/checkState/findItem/findStage/validateAgainst) ─

test('T-state: initState defaults contract/phase/turns/stages/outs from STATE_CONTRACT', () => {
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A], runId: 'r-test-cc' }), meta: {} }
  const state = initState(doc, 'B')
  assert.equal(state.contract, STATE_CONTRACT)
  assert.equal(state.runId, 'r-test-cc')
  assert.equal(state.phase, 'planned')
  assert.equal(state.method, 'B')
  assert.equal(state.turns, 0)
  assert.deepEqual(state.stages, {})
  assert.deepEqual(state.outs, {})
})

test('T-state: checkState ok for a matching runId/contract; errors on mismatch; an absent contract is not checked', () => {
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A], runId: 'r-test-aa' }), meta: {} }
  const state = initState(doc, 'B')
  assert.deepEqual(checkState(doc, state), { ok: true })

  const wrongRun = { ...state, runId: 'r-different' }
  assert.deepEqual(checkState(doc, wrongRun), { ok: false, error: 'state runId mismatch' })

  const wrongContract = { ...state, contract: 'bogus/contract' }
  assert.deepEqual(checkState(doc, wrongContract), { ok: false, error: 'state contract bogus/contract' })

  const noContract = { ...state }
  delete noContract.contract
  assert.deepEqual(checkState(doc, noContract), { ok: true })
})

test('T-state: findItem resolves by full id or short; findStage resolves a known seat and throws "unknown seat <seat> for <short>" otherwise', () => {
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const plan = planFixture({ items: [A] })
  assert.equal(findItem(plan, 'aaaaaaaa').id, A.id)
  assert.equal(findItem(plan, A.id).id, A.id)
  const stagePlanner = findStage(A, 'planner')
  assert.equal(stagePlanner.seat, 'planner')
  assert.throws(() => findStage(A, 'nope'), (err) => {
    assert.equal(err.message, 'unknown seat nope for aaaaaaaa')
    return true
  })
})

test('T-state: resolvePlan(core, doc) normalizes doc.args and throws "invalid args: <reason>" on failure', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const okDoc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const { plan, preflight } = resolvePlan(core, okDoc)
  assert.equal(plan.runId, okDoc.args.runId)
  assert.ok(Array.isArray(preflight.runnable))

  const badDoc = { contract: 'run-wave/plan-doc-v1', args: { contract: 'implement-wave/args-v1' }, meta: {} }
  assert.throws(() => resolvePlan(core, badDoc), (err) => {
    assert.match(err.message, /^invalid args: /)
    return true
  })
})

test('validateAgainst: reports "<$.path>: <problem>" strings for required/type/enum violations, [] when the value is valid', () => {
  const schema = {
    type: 'object', required: ['a', 'b'],
    properties: {
      a: { type: 'string' },
      b: { type: 'number' },
      c: { type: 'array', items: { type: 'string' } },
      d: { enum: ['x', 'y'] },
    },
  }
  const errors = validateAgainst(schema, { a: 1, c: [1, 2], d: 'z' })
  assert.ok(errors.some((e) => e.startsWith('$.a:')))
  assert.ok(errors.some((e) => e.startsWith('$.b:')), 'missing required "b" should be reported')
  assert.ok(errors.some((e) => e.startsWith('$.c[0]:') || e.startsWith('$.c:')))
  assert.ok(errors.some((e) => e.startsWith('$.d:')))

  assert.deepEqual(validateAgainst({ type: 'object', required: ['a'], properties: { a: { type: 'string' } } }, { a: 'ok' }), [])
})

test('resultFromState: a pending stage with no entry maps to stopped "agent returned null"', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  const result = resultFromState(core, doc, state)
  const item = result.items.find((i) => i.short === 'aaaaaaaa')
  assert.ok(item)
  assert.equal(item.status, 'stopped')
  assert.equal(item.reason, 'agent returned null')
})

test('resultFromState: --result accepts state-v1 input by contract (STATE_CONTRACT) as well as result-v1', () => {
  const core = realCore()
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A] }), meta: {} }
  const state = initState(doc, 'B')
  state.stages['aaaaaaaa:planner'] = envelope({ output: outputFor('planner-v1') })
  state.outs['aaaaaaaa:planner'] = outputFor('planner-v1')
  state.stages['aaaaaaaa:implementer'] = envelope({ output: outputFor('implementer-v1') })
  const result = resultFromState(core, doc, state)
  assert.equal(result.contract, 'implement-wave/result-v1')
  const item = result.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(item.status, 'done')
})
