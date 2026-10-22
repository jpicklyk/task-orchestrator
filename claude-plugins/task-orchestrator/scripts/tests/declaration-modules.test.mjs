// Ported to 4.0 from main commit e225afdb (item cac1bffa, task-scope D11): the main test file, adapted only where AC names it (actor fields, planDocSlug -> serverRunId, ledger assignment, 4.0 file paths).
// Coverage for item f5f65854: planner `declarationModules`, extractor `modules`, and the core
// `declarationGap` pre-dispatch refusal of a test-author seat naming an undeclared module.
// Scenario ids S1-S13 + probes match the item's frozen `test-plan` note.
// Oracles: the item's task-scope Build B1-B6 and proposal 5377c6b9 Change (1)-(3) - never what
// the code returns. This suite imports only exported surface (loadCore / loadWaveCore /
// run-exec-lib exports) plus workflow-harness.mjs; it never reads implementation source text.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCore as harnessLoadCore, planFixture, itemFixture, stages, fakeAgent, fakeParallel } from './workflow-harness.mjs'
import { loadWaveCore, CORE_NAMES } from '../lib/wave-core.mjs'
import { initState, next, resultFromState } from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const IMPLEMENT_WAVE = join(HERE, '..', '..', 'workflows', 'implement-wave.js')

const D = ['src/a.mjs', 'src/b.mjs']
const SHORT = 'aaaaaaaa'
const BS = String.fromCharCode(92) // a single backslash, for Windows-separator spellings

// ---- builders (required-field shapes taken from the existing suites' builders) ----

function plannerOut(overrides = {}) {
  return {
    proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
    decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
    mainFiles: [], docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
    ...overrides,
  }
}
function implementerOut(overrides = {}) {
  return {
    mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [],
    preExistingFailures: [], publicSurface: 'none', deviations: 'none',
    ...overrides,
  }
}
function declarationsOut(overrides = {}) {
  return { declarations: 'export function alpha(x)', harnessPointers: 'none', gaps: 'none', ...overrides }
}
function testAuthorOut(overrides = {}) {
  return {
    returnLine: 'none', testFiles: [], scenariosCovered: 'none', verify: [],
    redAuthorTests: [], missingDeclaration: 'none', breachDisclosure: 'none',
    ...overrides,
  }
}
function env(output, extra = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'fake', entry: { applied: true, newRole: 'work' }, output, ...extra,
  }
}

// Fixture F: planner-v1 > implementer-v1 > declarations-v1 > test-author-v1.
function fixtureF(short = SHORT, extra = {}) {
  return itemFixture({ short, stages: stages.bugFixLike(), ...extra })
}
function stageOf(item, seat) {
  return item.stages.find((s) => s.seat === seat)
}

// Method A scripted envelopes. `modules` undefined means the field is absent from the output.
function methodAScript(short, { declared = D, modules } = {}) {
  const extractor = declarationsOut(modules === undefined ? {} : { modules })
  return {
    [`planner:${short}`]: env(plannerOut(declared === undefined ? {} : { declarationModules: declared })),
    [`implementer:${short}`]: env(implementerOut()),
    [`declarations-extractor:${short}`]: env(extractor),
    [`test-author:${short}`]: env(testAuthorOut()),
  }
}
async function runMethodA(items, scripts) {
  const core = harnessLoadCore(IMPLEMENT_WAVE)
  const { agent, calls } = fakeAgent(Object.assign({}, ...scripts))
  const result = await core.runPlan(planFixture({ items }), { agent, parallel: fakeParallel, log: () => {} })
  return { result, calls }
}

// Method B: state with planner, implementer and extractor stages done.
function methodBState({ declared = D, modules } = {}, short = SHORT) {
  const item = fixtureF(short)
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
  const state = initState(doc, 'B')
  const pOut = plannerOut(declared === undefined ? {} : { declarationModules: declared })
  const iOut = implementerOut()
  const xOut = declarationsOut(modules === undefined ? {} : { modules })
  state.stages[`${short}:planner`] = env(pOut)
  state.outs[`${short}:planner`] = pOut
  state.stages[`${short}:implementer`] = env(iOut)
  state.outs[`${short}:implementer`] = iOut
  state.stages[`${short}:declarations-extractor`] = env(xOut)
  state.outs[`${short}:declarations-extractor`] = xOut
  return { item, doc, state }
}

// ---- S1 (A): all declared modules extracted -> test-author is dispatched ----

test('S1: Method A - extractor modules equal the declared set, item reaches done and the test-author agent is called [B3,B4]', async () => {
  const item = fixtureF()
  const { result, calls } = await runMethodA([item], [methodAScript(SHORT, { modules: D })])
  const r = result.items.find((i) => i.short === SHORT)
  assert.equal(r.status, 'done', `reason: ${r.reason}`)
  assert.ok(calls.some((c) => c.label === `test-author:${SHORT}`), 'test-author must be dispatched')
})

// ---- S2 (B): same state under Method B ----

test('S2: Method B - next() dispatches the test-author and settles nothing when modules cover the declared set [B5]', () => {
  const core = loadWaveCore()
  const { doc, state } = methodBState({ modules: D })
  const r = next(core, doc, state)
  assert.ok(r.dispatch.some((d) => d.item === SHORT && d.seat === 'test-author'), JSON.stringify(r))
  assert.deepEqual(r.settled, [])
  assert.equal(r.complete, false)
})

// ---- S3: extractor handoff lines ----

function lineWith(prompt, key) {
  return prompt.split('\n').find((l) => l.includes(key))
}

test('S3: extractor prompt carries `declarationModules:` and the ordered, deduped `extractFrom:` union [B2]', () => {
  const core = harnessLoadCore(IMPLEMENT_WAVE)
  const item = fixtureF()
  const plan = planFixture({ items: [item] })
  const outs = {
    planner: plannerOut({ mainFiles: ['src/c.mjs'], declarationModules: ['src/a.mjs', 'src/b.mjs'] }),
    implementer: implementerOut({ mainFilesChanged: ['src/a.mjs', 'src/c.mjs'] }),
  }
  const prompt = core.seatPrompt(plan, item, stageOf(item, 'declarations-extractor'), outs)
  const dm = lineWith(prompt, 'declarationModules:')
  const ef = lineWith(prompt, 'extractFrom:')
  assert.ok(dm, 'declarationModules line missing')
  assert.ok(ef, 'extractFrom line missing')
  assert.ok(dm.trimEnd().endsWith('declarationModules: src/a.mjs, src/b.mjs'), dm)
  assert.ok(ef.trimEnd().endsWith('extractFrom: src/a.mjs, src/c.mjs, src/b.mjs'), ef)
})

test('S3 (edge): extractFrom dedupes case-insensitively keeping the first spelling and relativizes worktree-absolute paths [B2]', () => {
  const core = harnessLoadCore(IMPLEMENT_WAVE)
  const item = fixtureF()
  const plan = planFixture({ items: [item] })
  const outs = {
    planner: plannerOut({ mainFiles: [`${item.worktree}/src/D.mjs`], declarationModules: ['SRC/a.mjs', 'src/d.mjs'] }),
    implementer: implementerOut({ mainFilesChanged: ['src/A.mjs'] }),
  }
  const prompt = core.seatPrompt(plan, item, stageOf(item, 'declarations-extractor'), outs)
  const ef = lineWith(prompt, 'extractFrom:')
  assert.ok(ef, 'extractFrom line missing')
  assert.ok(ef.trimEnd().endsWith('extractFrom: src/A.mjs, src/D.mjs'), ef)
})

test('S3 (edge): with no planner or implementer output both lines read `none` [B2]', () => {
  const core = harnessLoadCore(IMPLEMENT_WAVE)
  const item = fixtureF()
  const plan = planFixture({ items: [item] })
  const prompt = core.seatPrompt(plan, item, stageOf(item, 'declarations-extractor'), {})
  const dm = lineWith(prompt, 'declarationModules:')
  const ef = lineWith(prompt, 'extractFrom:')
  assert.ok(dm && dm.trimEnd().endsWith('declarationModules: none'), String(dm))
  assert.ok(ef && ef.trimEnd().endsWith('extractFrom: none'), String(ef))
})

// ---- S4: planner prompt asks for declarationModules only when a test-author-v1 stage exists ----

test('S4: planner prompt names declarationModules on F and not on an item without a test-author-v1 stage [B1]', () => {
  const core = harnessLoadCore(IMPLEMENT_WAVE)
  const f = fixtureF()
  const withTa = core.seatPrompt(planFixture({ items: [f] }), f, stageOf(f, 'planner'), {})
  assert.ok(withTa.includes('declarationModules'), 'planner prompt on F must ask for declarationModules')

  const g = itemFixture({ short: 'gggggggg', stages: stages.featureTaskLike() })
  const without = core.seatPrompt(planFixture({ items: [g] }), g, stageOf(g, 'planner'), {})
  assert.ok(!without.includes('declarationModules'), 'planner prompt without a test-author stage must not mention it')
})

// ---- S5 (A): a missing module stops the test-author dispatch ----

test('S5: Method A - a declared module absent from extractor modules stops the item with the exact reason and never calls the test-author [B3,B4]', async () => {
  const item = fixtureF()
  const { result, calls } = await runMethodA([item], [methodAScript(SHORT, { modules: ['src/a.mjs'] })])
  const r = result.items.find((i) => i.short === SHORT)
  assert.equal(r.status, 'stopped')
  assert.equal(r.reason, 'missingDeclaration: src/b.mjs')
  const ta = r.stages.find((s) => s.seat === 'test-author')
  assert.ok(ta, 'a test-author stage record must exist')
  assert.equal(ta.status, 'stopped')
  assert.equal(ta.reason, 'missingDeclaration: src/b.mjs')
  assert.ok(!calls.some((c) => c.label === `test-author:${SHORT}`), 'test-author agent must never be called')
  assert.ok(calls.some((c) => c.label === `declarations-extractor:${SHORT}`), 'precondition: the extractor did run')
})

test('S5 (milestone): the refused test-author settles its milestone so a waitsFor dependent defers instead of hanging [B4]', async () => {
  const a = fixtureF(SHORT)
  const b = itemFixture({
    short: 'bbbbbbbb', stages: stages.featureTaskLike(), waitsFor: [{ item: a.id, milestone: 'test-author' }],
  })
  const { result } = await runMethodA([a, b], [methodAScript(SHORT, { modules: ['src/a.mjs'] })])
  const rb = result.items.find((i) => i.short === 'bbbbbbbb')
  assert.equal(rb.status, 'deferred')
  assert.equal(rb.reason, `in-run blocker ${SHORT} did not reach test-author`)
})

// ---- S6 (B): same gap under Method B ----

test('S6: Method B - next() settles the item stopped with the exact reason, dispatches nothing, and resultFromState agrees [B5]', () => {
  const core = loadWaveCore()
  const { doc, state } = methodBState({ modules: ['src/a.mjs'] })
  const r = next(core, doc, state)
  assert.ok(!r.dispatch.some((d) => d.seat === 'test-author'), 'no test-author dispatch')
  const settled = r.settled.find((s) => s.item === SHORT)
  assert.ok(settled, JSON.stringify(r))
  assert.equal(settled.status, 'stopped')
  assert.equal(settled.reason, 'missingDeclaration: src/b.mjs')
  assert.equal(r.complete, true)

  const result = resultFromState(core, doc, state)
  const item = result.items.find((i) => i.short === SHORT)
  assert.equal(item.status, 'stopped')
  assert.equal(item.reason, 'missingDeclaration: src/b.mjs')
})

test('S6 (parity): Method A and Method B report the identical reason string for the same gap', async () => {
  const item = fixtureF()
  const { result } = await runMethodA([item], [methodAScript(SHORT, { modules: ['src/a.mjs'] })])
  const reasonA = result.items.find((i) => i.short === SHORT).reason

  const core = loadWaveCore()
  const { doc, state } = methodBState({ modules: ['src/a.mjs'] })
  const reasonB = next(core, doc, state).settled.find((s) => s.item === SHORT).reason
  assert.equal(reasonB, reasonA)
})

test('PROBE replay: next() twice on one unmutated state gives byte-identical output [B5]', () => {
  const core = loadWaveCore()
  const { doc, state } = methodBState({ modules: ['src/a.mjs'] })
  const first = JSON.stringify(next(core, doc, state))
  const second = JSON.stringify(next(core, doc, state))
  assert.equal(second, first)
})

// ---- S7: absent modules field ----

test('S7: extractor output without a modules field reports every declared module missing, in declared order [B3]', async () => {
  const item = fixtureF()
  const { result, calls } = await runMethodA([item], [methodAScript(SHORT, {})])
  const r = result.items.find((i) => i.short === SHORT)
  assert.equal(r.status, 'stopped')
  assert.equal(r.reason, 'missingDeclaration: src/a.mjs, src/b.mjs')
  assert.ok(!calls.some((c) => c.label === `test-author:${SHORT}`))
})

// ---- declarationGap direct (S8-S11, probes) ----

function gap(item, stageSeat, outs, plan) {
  const core = harnessLoadCore(IMPLEMENT_WAVE)
  return core.declarationGap(plan || planFixture({ items: [item] }), item, stageOf(item, stageSeat), outs)
}
function outsFor({ declared, modules }) {
  const outs = { planner: plannerOut(declared === undefined ? {} : { declarationModules: declared }) }
  outs['declarations-extractor'] = declarationsOut(modules === undefined ? {} : { modules })
  return outs
}

test('S8: declarationGap on the implementer stage returns null even when modules are missing [B3]', () => {
  const item = fixtureF()
  assert.equal(gap(item, 'implementer', outsFor({ declared: D, modules: [] })), null)
})

test('S8 (edge): declarationGap on the planner and extractor stages also returns null [B3]', () => {
  const item = fixtureF()
  assert.equal(gap(item, 'planner', outsFor({ declared: D, modules: [] })), null)
  assert.equal(gap(item, 'declarations-extractor', outsFor({ declared: D, modules: [] })), null)
})

test('S9: declarationModules absent -> null; empty array -> null [B3]', () => {
  const item = fixtureF()
  assert.equal(gap(item, 'test-author', outsFor({ declared: undefined, modules: [] })), null)
  assert.equal(gap(item, 'test-author', outsFor({ declared: [], modules: [] })), null)
})

test('S9 (probe): no planner output at all, and a non-array declarationModules, are not enforced [B3]', () => {
  const item = fixtureF()
  assert.equal(gap(item, 'test-author', {}), null)
  assert.equal(gap(item, 'test-author', outsFor({ declared: null, modules: [] })), null)
  assert.equal(gap(item, 'test-author', outsFor({ declared: 'src/a.mjs', modules: [] })), null)
})

test('S10: no declarations-v1 stage with non-empty declared modules -> null [B3]', () => {
  const item = itemFixture({
    short: 'cccccccc',
    stages: stages.bugFixLike().filter((s) => s.output !== 'declarations-v1'),
  })
  assert.ok(!item.stages.some((s) => s.output === 'declarations-v1'), 'fixture precondition')
  assert.equal(gap(item, 'test-author', { planner: plannerOut({ declarationModules: D }) }), null)
})

test('S11: separator, ./, worktree-absolute and case spellings of a declared module count as covered [B3]', () => {
  const item = fixtureF()
  const declared = ['./src/a.mjs', `${item.worktree}/src/B.mjs`, `src${BS}a.mjs`]
  const modules = ['SRC/A.mjs', 'src/b.mjs']
  assert.equal(gap(item, 'test-author', outsFor({ declared, modules })), null)
})

test('S11 (dup): duplicate declared spellings of one missing module are reported once with the first relativized spelling [B3]', () => {
  const item = fixtureF()
  const r = gap(item, 'test-author', outsFor({ declared: ['./src/x.mjs', 'src/x.mjs'], modules: [] }))
  assert.deepEqual(r, { status: 'stopped', reason: 'missingDeclaration: src/x.mjs' })
})

test('S11 (order): multiple missing modules are listed in first-seen order, deduped, with the stopped shape [B3]', () => {
  const item = fixtureF()
  const r = gap(item, 'test-author', outsFor({ declared: ['src/z.mjs', 'src/a.mjs', 'src/Z.mjs', 'src/m.mjs'], modules: ['src/m.mjs'] }))
  assert.deepEqual(r, { status: 'stopped', reason: 'missingDeclaration: src/z.mjs, src/a.mjs' })
})

test('S11 (raw-compare guard): a declared module spelled with other case and separators than the listed one is covered, not reported [B3]', () => {
  const item = fixtureF()
  const declared = [['Src', 'Deep', 'File.MJS'].join(BS)]
  assert.equal(gap(item, 'test-author', outsFor({ declared, modules: ['src/deep/file.mjs'] })), null)
})

test('PROBE: modules listing more than the declared set is not a gap [B3]', () => {
  const item = fixtureF()
  assert.equal(gap(item, 'test-author', outsFor({ declared: ['src/a.mjs'], modules: ['src/a.mjs', 'src/extra.mjs'] })), null)
})

test('PROBE: a declared ../ path and an outside-root absolute path listed identically count as covered (literal canonical compare) [B3]', () => {
  const item = fixtureF()
  const declared = ['../x.mjs', '/elsewhere/y.mjs']
  assert.equal(gap(item, 'test-author', outsFor({ declared, modules: declared.slice() })), null)
})

test('PROBE: a glob in declarationModules is compared literally, never expanded [B3]', () => {
  const item = fixtureF()
  const r = gap(item, 'test-author', outsFor({ declared: ['src/*.mjs'], modules: ['src/a.mjs', 'src/b.mjs'] }))
  assert.deepEqual(r, { status: 'stopped', reason: 'missingDeclaration: src/*.mjs' })
})

test('PROBE: empty modules array and absent modules field give the same reason [B3]', () => {
  const item = fixtureF()
  const empty = gap(item, 'test-author', outsFor({ declared: D, modules: [] }))
  const absent = gap(item, 'test-author', outsFor({ declared: D, modules: undefined }))
  assert.deepEqual(empty, absent)
  assert.equal(empty.reason, 'missingDeclaration: src/a.mjs, src/b.mjs')
})

test('PROBE: declarationGap is idempotent over identical inputs and does not mutate outs [B3]', () => {
  const item = fixtureF()
  const outs = outsFor({ declared: D, modules: ['src/a.mjs'] })
  const snapshot = JSON.stringify(outs)
  const r1 = gap(item, 'test-author', outs)
  const r2 = gap(item, 'test-author', outs)
  assert.deepEqual(r1, r2)
  assert.equal(JSON.stringify(outs), snapshot)
})

// ---- S12: schema fields ----

test('S12: OUTPUT_SCHEMAS planner-v1.declarationModules and declarations-v1.modules are optional string arrays [B1,B2]', () => {
  const core = harnessLoadCore(IMPLEMENT_WAVE)
  const planner = core.OUTPUT_SCHEMAS['planner-v1']
  const decl = core.OUTPUT_SCHEMAS['declarations-v1']
  assert.deepEqual(planner.properties.declarationModules, { type: 'array', items: { type: 'string' } })
  assert.deepEqual(decl.properties.modules, { type: 'array', items: { type: 'string' } })
  assert.ok(!(planner.required || []).includes('declarationModules'), 'declarationModules must stay optional')
  assert.ok(!(decl.required || []).includes('modules'), 'modules must stay optional')
})

// ---- S13: export ----

test('S13: both loaders expose declarationGap as a function and CORE_NAMES lists it [B6]', () => {
  assert.equal(typeof harnessLoadCore(IMPLEMENT_WAVE).declarationGap, 'function')
  assert.equal(typeof loadWaveCore().declarationGap, 'function')
  assert.ok(CORE_NAMES.includes('declarationGap'))
})
