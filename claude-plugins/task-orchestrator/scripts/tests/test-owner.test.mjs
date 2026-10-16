// Coverage for item c5007c88: an item whose planner lists testFiles but has no test-author stage
// gets an explicit test owner (stop with "no-test-owner" or a TESTS ARE YOURS handoff line),
// identically in Method A (workflows/implement-wave.js runPlan) and Method B (run-exec-lib.mjs).
// Scenario ids S1-S10 and the probes match the item's frozen `test-plan` note.
// Oracles: the item's `task-scope` Build 1-3 and AC1-AC5 (the exact reason and line strings are
// spec text), never "what the code returns". SA = seat-aware planner + implementer stages, no
// test-author stage, no implicitOwner. This suite never reads the source text of
// implement-wave.js, run-exec-lib.mjs or wave-core.mjs; it uses only their exported surface plus
// workflow-harness.mjs. existingTestEdits entries carry file and edit (the Method B envelope validator requires both).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCore as harnessLoadCore, planFixture, itemFixture, stages, fakeAgent, fakeParallel } from './workflow-harness.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { initState, next, prompt, stageResult } from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const IMPLEMENT_WAVE = join(PLUGIN_ROOT, 'workflows', 'implement-wave.js')

const SHORT = 'aaaaaaaa'
const HANDOFF_MARKER = 'HANDOFF from planner:'
const BS = String.fromCharCode(92)

// ---- spec strings (task-scope Build 1 and 3) ----

function R(testFiles) {
  return `no-test-owner: add needs-test-author or clear testFiles (${testFiles.join(', ')})`
}

function L(paths) {
  return `TESTS ARE YOURS: ${paths.join(', ')} - no test-author seat runs on this item; write, run and commit them yourself.`
}

// ---- builders (required-field shapes from the B1 contract, as in run-exec.test.mjs) ----

function plannerOut(overrides = {}) {
  return {
    proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
    decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
    mainFiles: [], docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
    ...overrides,
  }
}

function implementerOut() {
  return {
    mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [],
    preExistingFailures: [], publicSurface: 'none', deviations: 'none',
  }
}

function env(output, extra = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: 'p1', post: 'p2' }, files: [],
    modelReported: 'sonnet', output, ...extra,
  }
}

const ENTERED = { entry: { applied: true, newRole: 'work' } }

// SA item: planner + implementer (entering), no test-author, no implicitOwner.
function saItem(overrides = {}) {
  return itemFixture({ short: SHORT, stages: stages.featureTaskLike(), ...overrides })
}

// Seat-less item: the implementer stage is the implicit owner of every note.
function seatlessItem() {
  const st = stages.featureTaskLike()
  st[1].implicitOwner = true
  return itemFixture({ short: SHORT, stages: st })
}

// Item WITH a test-author stage (and the declarations-extractor that precedes it).
function withTestAuthorItem() {
  return itemFixture({ short: SHORT, stages: stages.bugFixLike() })
}

function plannerStageOf(item) {
  return item.stages.find((s) => s.seat === 'planner')
}

const CORES = [
  ['A', () => harnessLoadCore(IMPLEMENT_WAVE)],
  ['B', () => loadWaveCore()],
]

// ---- Method A runner: runPlan over a single item with a scripted planner ----

async function runA(item, plannerOutput, extraScript = {}) {
  const core = harnessLoadCore(IMPLEMENT_WAVE)
  const plan = planFixture({ items: [item] })
  const script = {
    [`planner:${item.short}`]: env(plannerOutput),
    [`implementer:${item.short}`]: env(implementerOut(), ENTERED),
    ...extraScript,
  }
  const { agent, calls } = fakeAgent(script)
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })
  const r = result.items.find((it) => it.short === item.short)
  const implCall = calls.find((c) => c.label === `implementer:${item.short}`)
  return { result, r, calls, implCall, implPrompt: implCall ? implCall.prompt : null }
}

// ---- Method B runner: planner stageResult then (if done) implementer prompt + next ----

function runB(item, plannerOutput) {
  const core = loadWaveCore()
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
  const state = initState(doc, 'B')
  const res = stageResult(core, doc, state, item.short, 'planner', JSON.stringify(env(plannerOutput)))
  const after = res.state || state
  const nxt = next(core, doc, after)
  const implPrompt = res.status === 'done' ? prompt(core, doc, after, item.short, 'implementer') : null
  return { core, doc, state, res, after, nxt, implPrompt }
}

function ownedLines(text) {
  return text.split('\n').filter((l) => l.includes('TESTS ARE YOURS'))
}

function assertOneOwnedLine(text, expected, label) {
  assert.ok(text.includes(HANDOFF_MARKER), `${label}: prompt carries the "${HANDOFF_MARKER}" block`)
  const lines = ownedLines(text)
  assert.equal(lines.length, 1, `${label}: exactly one TESTS ARE YOURS line, got ${lines.length}`)
  assert.equal(lines[0].trim(), expected, `${label}: line text`)
  assert.ok(text.indexOf(lines[0]) > text.indexOf(HANDOFF_MARKER), `${label}: line sits after the HANDOFF marker`)
}

// ============================================================================================
// S1 - direct mapStageResult stop (AC1), both cores
// ============================================================================================

for (const [name, load] of CORES) {
  test(`S1 (${name} core): SA planner proceed:true with testFiles [a,b] -> stopped "no-test-owner: ... (a, b)"`, () => {
    const core = load()
    const item = saItem()
    const out = core.mapStageResult(plannerStageOf(item), env(plannerOut({ testFiles: ['t/a.test.mjs', 't/b.test.mjs'] })), 'seat', item)
    assert.equal(out.status, 'stopped')
    assert.equal(out.reason, R(['t/a.test.mjs', 't/b.test.mjs']))
  })
}

// ============================================================================================
// S2 - Method A end to end (AC1)
// ============================================================================================

test('S2 (Method A): runPlan over an SA item whose planner returns testFiles [a] stops the item and never dispatches the implementer', async () => {
  const item = saItem()
  const { r, calls } = await runA(item, plannerOut({ testFiles: ['t/a.test.mjs'], mainFiles: ['src/m.js'] }))
  assert.ok(r, 'item result present')
  assert.equal(r.status, 'stopped')
  assert.equal(r.reason, R(['t/a.test.mjs']))
  assert.equal(calls.some((c) => c.label === `implementer:${item.short}`), false, 'implementer must not be dispatched')
})

// ============================================================================================
// S3 - Method B end to end (AC1)
// ============================================================================================

test('S3 (Method B): run-exec stageResult for the SA planner seat stops with the exact reason and next() dispatches no implementer', () => {
  const item = saItem()
  const { res, nxt } = runB(item, plannerOut({ testFiles: ['t/a.test.mjs'], mainFiles: ['src/m.js'] }))
  assert.equal(res.status, 'stopped')
  assert.equal(res.reason, R(['t/a.test.mjs']))
  assert.equal(nxt.dispatch.some((d) => d.item === item.short && d.seat === 'implementer'), false, 'no implementer dispatch after the stop')
})

// ============================================================================================
// S4 - existingTestEdits only -> done + TESTS ARE YOURS line (AC2), Method A and B
// ============================================================================================

test('S4 (Method A): SA item, testFiles [] + existingTestEdits [e] -> done; implementer prompt carries exactly L(e) after the HANDOFF marker', async () => {
  const item = saItem()
  const { r, implPrompt } = await runA(item, plannerOut({ mainFiles: ['src/m.js'], existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] }))
  assert.equal(r.status, 'done')
  assert.ok(implPrompt, 'implementer was dispatched')
  assertOneOwnedLine(implPrompt, L(['t/e.test.mjs']), 'A')
})

test('S4 (Method B): SA item, testFiles [] + existingTestEdits [e] -> planner done; implementer prompt carries exactly L(e) after the HANDOFF marker', () => {
  const item = saItem()
  const { res, implPrompt } = runB(item, plannerOut({ mainFiles: ['src/m.js'], existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] }))
  assert.equal(res.status, 'done')
  assertOneOwnedLine(implPrompt, L(['t/e.test.mjs']), 'B')
})

// ============================================================================================
// S5 - item WITH a test-author stage: no stop, no line (AC3)
// ============================================================================================

for (const [name, load] of CORES) {
  test(`S5 (${name} core, direct): item with a test-author stage + testFiles [a] -> planner done, not stopped`, () => {
    const core = load()
    const item = withTestAuthorItem()
    const out = core.mapStageResult(plannerStageOf(item), env(plannerOut({ testFiles: ['t/a.test.mjs'] })), 'seat', item)
    assert.equal(out.status, 'done')
  })
}

test('S5 (Method A): bug-fix-like item with test-author stage runs to done and the implementer prompt has no TESTS ARE YOURS text', async () => {
  const item = withTestAuthorItem()
  const { r, implPrompt } = await runA(item, plannerOut({ mainFiles: ['src/x.js'], testFiles: ['t/a.test.mjs'], existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] }), {
    [`declarations-extractor:${SHORT}`]: env({ declarations: 'the function returns a value', harnessPointers: 'none', gaps: 'none' }),
    [`test-author:${SHORT}`]: env({
      returnLine: 'none', testFiles: [], scenariosCovered: 'none', verify: [],
      redAuthorTests: [], missingDeclaration: 'none', breachDisclosure: 'none',
    }),
  })
  assert.equal(r.status, 'done', `reason: ${r.reason}`)
  assert.ok(implPrompt, 'implementer was dispatched')
  assert.ok(implPrompt.includes(HANDOFF_MARKER), 'HANDOFF block still rendered')
  assert.equal(implPrompt.includes('TESTS ARE YOURS'), false)
})

test('S5 (Method B): bug-fix-like item with test-author stage -> planner done; implementer prompt has no TESTS ARE YOURS text', () => {
  const item = withTestAuthorItem()
  const { res, implPrompt } = runB(item, plannerOut({ mainFiles: ['src/x.js'], testFiles: ['t/a.test.mjs'], existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] }))
  assert.equal(res.status, 'done')
  assert.ok(implPrompt.includes(HANDOFF_MARKER), 'HANDOFF block still rendered')
  assert.equal(implPrompt.includes('TESTS ARE YOURS'), false)
})

// ============================================================================================
// S6 - seat-less (implicitOwner) implementer: no stop; line lists testFiles then edits (AC4)
// ============================================================================================

for (const [name, load] of CORES) {
  test(`S6 (${name} core, direct): seat-less item + testFiles [a] -> planner done, not stopped`, () => {
    const core = load()
    const item = seatlessItem()
    const out = core.mapStageResult(plannerStageOf(item), env(plannerOut({ testFiles: ['t/a.test.mjs'] })), 'seat', item)
    assert.equal(out.status, 'done')
  })
}

test('S6 (Method A): seat-less item, testFiles [a] + edits [e] -> done; line == L(a, e)', async () => {
  const item = seatlessItem()
  const { r, implPrompt } = await runA(item, plannerOut({ testFiles: ['t/a.test.mjs'], existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] }))
  assert.equal(r.status, 'done')
  assertOneOwnedLine(implPrompt, L(['t/a.test.mjs', 't/e.test.mjs']), 'A')
})

test('S6 (Method B): seat-less item, testFiles [a] + edits [e] -> done; line == L(a, e)', () => {
  const item = seatlessItem()
  const { res, implPrompt } = runB(item, plannerOut({ testFiles: ['t/a.test.mjs'], existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] }))
  assert.equal(res.status, 'done')
  assertOneOwnedLine(implPrompt, L(['t/a.test.mjs', 't/e.test.mjs']), 'B')
})

// ============================================================================================
// S7 - planner stop precedence: proceed:false wins over the test-owner stop
// ============================================================================================

for (const [name, load] of CORES) {
  test(`S7 (${name} core, direct): proceed:false with testFiles [a] -> reason "planner: <blockReason>"`, () => {
    const core = load()
    const item = saItem()
    const out = core.mapStageResult(plannerStageOf(item), env(plannerOut({ proceed: false, blockReason: 'needs revision', testFiles: ['t/a.test.mjs'] })), 'seat', item)
    assert.equal(out.status, 'stopped')
    assert.equal(out.reason, 'planner: needs revision')
  })
}

test('S7 (Method B): stageResult proceed:false with testFiles [a] -> reason "planner: <blockReason>"', () => {
  const { res } = runB(saItem(), plannerOut({ proceed: false, blockReason: 'needs revision', testFiles: ['t/a.test.mjs'] }))
  assert.equal(res.status, 'stopped')
  assert.equal(res.reason, 'planner: needs revision')
})

// ============================================================================================
// S8 - back-compat: the item param is optional; absent item = no check
// ============================================================================================

for (const [name, load] of CORES) {
  test(`S8 (${name} core): 3-arg call and an explicit undefined 4th arg with testFiles [a] -> done`, () => {
    const core = load()
    const item = saItem()
    const e = env(plannerOut({ testFiles: ['t/a.test.mjs'] }))
    assert.equal(core.mapStageResult(plannerStageOf(item), e, 'seat').status, 'done')
    assert.equal(core.mapStageResult(plannerStageOf(item), e, 'seat', undefined).status, 'done')
  })
}

// ============================================================================================
// S9 - empty owned set: done and no line
// ============================================================================================

for (const [name, load] of CORES) {
  test(`S9 (${name} core, direct): SA item, testFiles [] and edits [] -> done`, () => {
    const core = load()
    const item = saItem()
    const out = core.mapStageResult(plannerStageOf(item), env(plannerOut({ mainFiles: ['src/m.js'] })), 'seat', item)
    assert.equal(out.status, 'done')
  })
}

test('S9 (Method A): SA item, empty owned set with mainFiles [m] -> done; HANDOFF present, no TESTS ARE YOURS', async () => {
  const { r, implPrompt } = await runA(saItem(), plannerOut({ mainFiles: ['src/m.js'] }))
  assert.equal(r.status, 'done')
  assert.ok(implPrompt.includes(HANDOFF_MARKER))
  assert.ok(implPrompt.includes('src/m.js'), 'mainFiles still handed off')
  assert.equal(implPrompt.includes('TESTS ARE YOURS'), false)
})

test('S9 (Method B): SA item, empty owned set with mainFiles [m] -> done; HANDOFF present, no TESTS ARE YOURS', () => {
  const { res, implPrompt } = runB(saItem(), plannerOut({ mainFiles: ['src/m.js'] }))
  assert.equal(res.status, 'done')
  assert.ok(implPrompt.includes(HANDOFF_MARKER))
  assert.ok(implPrompt.includes('src/m.js'), 'mainFiles still handed off')
  assert.equal(implPrompt.includes('TESTS ARE YOURS'), false)
})

// ============================================================================================
// S10 - Method A / Method B implementer prompts byte-identical (AC5)
// ============================================================================================

const PARITY_CASES = [
  ['S4 SA edits-only', saItem, () => plannerOut({ mainFiles: ['src/m.js'], existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] }), L(['t/e.test.mjs'])],
  ['S6 seat-less testFiles+edits', seatlessItem, () => plannerOut({ testFiles: ['t/a.test.mjs'], existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] }), L(['t/a.test.mjs', 't/e.test.mjs'])],
  ['S9 SA empty set', saItem, () => plannerOut({ mainFiles: ['src/m.js'] }), null],
]

for (const [label, mkItem, mkOut, expectedLine] of PARITY_CASES) {
  test(`S10: Method A and Method B implementer prompts are byte-identical (${label})`, async () => {
    const a = await runA(mkItem(), mkOut())
    const b = runB(mkItem(), mkOut())
    assert.ok(a.implPrompt && b.implPrompt, 'both methods produced an implementer prompt')
    assert.equal(b.implPrompt, a.implPrompt)
    if (expectedLine !== null) {
      assert.ok(a.implPrompt.includes(expectedLine), 'parity is checked on a prompt that carries the line')
    }
  })
}

// ============================================================================================
// Probes
// ============================================================================================

test('PROBE duplicates: seat-less testFiles [a] + edits [{file:a}] -> a listed once (A and B)', async () => {
  const mk = () => plannerOut({ testFiles: ['t/a.test.mjs'], existingTestEdits: [{ file: 't/a.test.mjs', edit: 'adjust assertion' }] })
  const a = await runA(seatlessItem(), mk())
  assertOneOwnedLine(a.implPrompt, L(['t/a.test.mjs']), 'A')
  const b = runB(seatlessItem(), mk())
  assertOneOwnedLine(b.implPrompt, L(['t/a.test.mjs']), 'B')
})

test('PROBE duplicates: repeated entries inside testFiles and inside edits collapse to one occurrence each', async () => {
  const { implPrompt } = await runA(seatlessItem(), plannerOut({
    testFiles: ['t/a.test.mjs', 't/a.test.mjs'],
    existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }, { file: 't/e.test.mjs', edit: 'adjust assertion' }],
  }))
  assertOneOwnedLine(implPrompt, L(['t/a.test.mjs', 't/e.test.mjs']), 'A')
})

test('PROBE case-variant paths are distinct raw strings and kept verbatim (no normalization)', async () => {
  const { implPrompt } = await runA(seatlessItem(), plannerOut({
    testFiles: ['Tests/A.test.mjs'],
    existingTestEdits: [{ file: 'tests/a.test.mjs', edit: 'adjust assertion' }],
  }))
  assertOneOwnedLine(implPrompt, L(['Tests/A.test.mjs', 'tests/a.test.mjs']), 'A')
})

test('PROBE order sensitivity: testFiles come before existingTestEdits regardless of path sort order', async () => {
  const { implPrompt } = await runA(seatlessItem(), plannerOut({
    testFiles: ['t/z.test.mjs'],
    existingTestEdits: [{ file: 't/a.test.mjs', edit: 'adjust assertion' }],
  }))
  assertOneOwnedLine(implPrompt, L(['t/z.test.mjs', 't/a.test.mjs']), 'A')
})

test('PROBE edits entries without a file, and a null entry, are skipped (Method A; the fake agent does no schema validation)', async () => {
  const { r, implPrompt } = await runA(seatlessItem(), plannerOut({
    existingTestEdits: [{}, null, { file: 't/e.test.mjs', edit: 'adjust assertion' }],
  }))
  assert.equal(r.status, 'done', `reason: ${r.reason}`)
  assertOneOwnedLine(implPrompt, L(['t/e.test.mjs']), 'A')
})

for (const [name, load] of CORES) {
  test(`PROBE empty-string testFiles entry counts as non-empty (${name} core): [''] -> stopped`, () => {
    const core = load()
    const item = saItem()
    const out = core.mapStageResult(plannerStageOf(item), env(plannerOut({ testFiles: [''] })), 'seat', item)
    assert.equal(out.status, 'stopped')
    assert.equal(out.reason, R(['']))
  })

  test(`PROBE absent vs [] vs null testFiles are all "no test files" (${name} core): done, no stop`, () => {
    const core = load()
    const item = saItem()
    const base = plannerOut()
    const absent = { ...base }
    delete absent.testFiles
    const variants = [{ ...base, testFiles: [] }, { ...base, testFiles: null }, absent]
    for (const output of variants) {
      const out = core.mapStageResult(plannerStageOf(item), env(output), 'seat', item)
      assert.equal(out.status, 'done', `variant testFiles=${JSON.stringify(output.testFiles)}`)
    }
  })

  test(`PROBE Windows absolute path appears verbatim in the reason (${name} core)`, () => {
    const core = load()
    const item = saItem()
    const win = `C:${BS}Users${BS}dev${BS}proj${BS}t${BS}a.test.mjs`
    const out = core.mapStageResult(plannerStageOf(item), env(plannerOut({ testFiles: [win, 'T/B.test.mjs'] })), 'seat', item)
    assert.equal(out.status, 'stopped')
    assert.equal(out.reason, R([win, 'T/B.test.mjs']))
  })

  test(`PROBE replay: the same mapStageResult call twice gives the same result (${name} core)`, () => {
    const core = load()
    const item = saItem()
    const e = env(plannerOut({ testFiles: ['t/a.test.mjs'] }))
    const one = core.mapStageResult(plannerStageOf(item), e, 'seat', item)
    const two = core.mapStageResult(plannerStageOf(item), e, 'seat', item)
    assert.equal(one.status, 'stopped')
    assert.deepEqual(two, one)
  })

  test(`PROBE existingTestEdits alone never stops an SA item (${name} core)`, () => {
    const core = load()
    const item = saItem()
    const out = core.mapStageResult(plannerStageOf(item), env(plannerOut({ existingTestEdits: [{ file: 't/e.test.mjs', edit: 'adjust assertion' }] })), 'seat', item)
    assert.equal(out.status, 'done')
  })
}

test('PROBE replay (Method B): the same stageResult twice from the same state gives the same reason', () => {
  const item = saItem()
  const core = loadWaveCore()
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
  const state = initState(doc, 'B')
  const text = JSON.stringify(env(plannerOut({ testFiles: ['t/a.test.mjs'] })))
  const one = stageResult(core, doc, state, item.short, 'planner', text)
  const two = stageResult(core, doc, state, item.short, 'planner', text)
  assert.equal(one.status, 'stopped')
  assert.equal(two.status, one.status)
  assert.equal(two.reason, one.reason)
})
