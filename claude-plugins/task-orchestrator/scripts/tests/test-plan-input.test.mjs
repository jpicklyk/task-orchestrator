// Coverage for item 6544aa95: the test-author seat treats the frozen test plan as a required input.
// Scenario ids S1-S12 match the item's frozen `test-plan` note; every scenario is NEW-SURFACE.
// Oracles: parent 416134d6 interface-contract facts 4, 6 and 7 ([C4] [C6] [C7]) and the task-scope
// acceptance criteria AC1-AC5 with planner decisions D1-D3 ([T]) - never "what the code returns".
// This suite never reads the source text of workflows/implement-wave.js or run-exec-lib.mjs; it uses
// only their exported surface, workflow-harness.mjs, and the agents/test-author.md document.
// SENT(k) is the contract fact 7 sentence, spelled out below as sent().

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCore, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { initState, prompt, reseat, stageResult } from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = join(HERE, '..', '..')
const SCRIPT_PATH = join(PLUGIN, 'workflows', 'implement-wave.js')
const TEST_AUTHOR_MD = join(PLUGIN, 'agents', 'test-author.md')

const SHORT = 'aaaaaaaa'
const ITEM_ID = `item-${SHORT}`
const SERVER_RUN_ID = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const PROJECT = { searchScope: 'claude-plugins/task-orchestrator', scratchDir: '/tmp/scratch', shell: 'bash', verify: [] }
const PRE = 'b'.repeat(40)

const MANIFEST_LINE = 'manifestScenarios: list every test-plan scenario id your test-manifest maps or marks dropped, exactly as numbered.'

/** [C7] the fact 7 sentence for note key k on item id id. */
function sent(k, id = ITEM_ID) {
  return `TEST PLAN: before anything else, read notes_read(select:"get", itemId:"${id}", key:"${k}"); when it is absent or empty, write no test and return status "stopped", reason "no ${k}".`
}

function authorStage(overrides = {}) {
  return {
    seat: 'test-author', phase: 'work', writes: true, notes: ['test-manifest'], dispatch: {},
    output: 'test-author-v1', inputNotes: ['test-plan'], ...overrides,
  }
}

function withoutField(stage, field) {
  const copy = { ...stage }
  delete copy[field]
  return copy
}

const IMPLEMENTER_STAGE = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }

function makeItem(stageList) {
  return itemFixture({ short: SHORT, stages: stageList })
}

function unboundPlan(item) {
  return planFixture({ items: [item], project: PROJECT })
}

function boundPlan(item) {
  return planFixture({ items: [item], project: PROJECT, serverRunId: SERVER_RUN_ID })
}

function paragraphs(text) {
  return text.split('\n\n')
}

function testPlanParagraphs(text) {
  return paragraphs(text).filter((p) => p.startsWith('TEST PLAN:'))
}

function countOf(text, needle) {
  return text.split(needle).length - 1
}

function seatPromptOf(plan, item, stage, outs = {}) {
  return loadCore(SCRIPT_PATH).seatPrompt(plan, item, stage, outs)
}

// ---- S1 ----

test('S1: an unbound test-author stage with inputNotes ["test-plan"] gets exactly one TEST PLAN paragraph carrying SENT("test-plan")', () => {
  const stage = authorStage()
  const item = makeItem([stage])
  const p = seatPromptOf(unboundPlan(item), item, stage)
  const parts = testPlanParagraphs(p)
  assert.equal(parts.length, 1, 'exactly one paragraph starts with "TEST PLAN:"')
  assert.ok(parts[0].includes(sent('test-plan')), 'the paragraph carries the fact 7 sentence verbatim')
  assert.equal(countOf(p, sent('test-plan')), 1, 'the sentence appears once in the whole prompt')
})

// ---- S2 ----

test('S2: a stage with inputNotes absent, [] or null renders no TEST PLAN text (fixture condition: S1 renders it)', () => {
  const base = authorStage()
  const item0 = makeItem([base])
  assert.ok(seatPromptOf(unboundPlan(item0), item0, base).includes('TEST PLAN:'), 'fixture condition: the same stage with the field renders it')
  for (const [label, stage] of [
    ['absent', withoutField(base, 'inputNotes')],
    ['empty array', authorStage({ inputNotes: [] })],
    ['null', authorStage({ inputNotes: null })],
  ]) {
    const item = makeItem([stage])
    for (const plan of [unboundPlan(item), boundPlan(item)]) {
      const p = seatPromptOf(plan, item, stage)
      assert.equal(p.includes('TEST PLAN:'), false, `${label}: no TEST PLAN text`)
    }
  }
})

// ---- S3 ----

test('S3: inputNotes ["test-plan","spec-plan"] gives exactly two TEST PLAN paragraphs in key order, each with its own sentence', () => {
  const stage = authorStage({ inputNotes: ['test-plan', 'spec-plan'] })
  const item = makeItem([stage])
  const p = seatPromptOf(unboundPlan(item), item, stage)
  const parts = testPlanParagraphs(p)
  assert.equal(parts.length, 2)
  assert.ok(parts[0].includes(sent('test-plan')), 'first paragraph is the first key')
  assert.ok(parts[1].includes(sent('spec-plan')), 'second paragraph is the second key')
  assert.equal(parts[0].includes('"spec-plan"'), false, 'the first paragraph does not name the second key')
  // order sensitivity: reversing the keys reverses the paragraphs
  const rev = authorStage({ inputNotes: ['spec-plan', 'test-plan'] })
  const itemR = makeItem([rev])
  const partsR = testPlanParagraphs(seatPromptOf(unboundPlan(itemR), itemR, rev))
  assert.equal(partsR.length, 2)
  assert.ok(partsR[0].includes(sent('spec-plan')) && partsR[1].includes(sent('test-plan')))
})

// ---- S4 / S5 ----

test('S4: on a bound plan the TEST PLAN paragraph also tells the seat to end its seat as failed', () => {
  const stage = authorStage()
  const item = makeItem([stage])
  const parts = testPlanParagraphs(seatPromptOf(boundPlan(item), item, stage))
  assert.equal(parts.length, 1)
  assert.ok(parts[0].includes(sent('test-plan')))
  assert.ok(parts[0].includes('end_seat'), 'names end_seat')
  assert.ok(parts[0].includes('failed'), 'names the failed outcome')
})

test('S5: on an unbound plan the TEST PLAN paragraph has neither end_seat nor failed (fixture condition: S4 bound plan has both)', () => {
  const stage = authorStage()
  const item = makeItem([stage])
  const bound = testPlanParagraphs(seatPromptOf(boundPlan(item), item, stage))[0]
  assert.ok(bound.includes('end_seat') && bound.includes('failed'), 'fixture condition')
  const parts = testPlanParagraphs(seatPromptOf(unboundPlan(item), item, stage))
  assert.equal(parts.length, 1)
  assert.equal(parts[0].includes('end_seat'), false)
  assert.equal(parts[0].includes('failed'), false)
})

// ---- S6 ----

function methodBDoc(method) {
  const list = stages.bugFixLike().map((s) => (s.seat === 'test-author' ? { ...s, notes: ['test-manifest'], inputNotes: ['test-plan'] } : s))
  const item = makeItem(list)
  const args = planFixture({ items: [item], project: PROJECT })
  return { contract: 'run-wave/plan-doc-v1', args, meta: method ? { method } : {} }
}

test('S6: run-exec-lib prompt() for the test-author seat carries SENT("test-plan") exactly once, with meta {} and meta {method:"B"}', () => {
  const core = loadWaveCore()
  for (const method of [undefined, 'B']) {
    const doc = methodBDoc(method)
    const state = initState(doc, method === 'B' ? 'B' : 'A')
    const text = prompt(core, doc, state, SHORT, 'test-author')
    assert.equal(countOf(text, sent('test-plan')), 1, `method ${method}: SENT once`)
    assert.equal(testPlanParagraphs(text).length, 1, `method ${method}: one TEST PLAN paragraph`)
  }
})

// ---- S7 ----

const PLANNER_OUT = {
  proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
  decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none', sweep: [],
}
const IMPL_OUT = {
  mainFilesChanged: ['src/x.js'], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}
const DECL_NONE = { declarations: 'none', harnessPointers: 'ptr-kept', gaps: 'none' }

function reseatDoc(bound) {
  const list = stages.bugFixLike().map((s) => (s.seat === 'test-author' ? { ...s, notes: ['test-manifest'], inputNotes: ['test-plan'] } : s))
  const item = makeItem(list)
  const args = planFixture({ items: [item], project: PROJECT, ...(bound ? { serverRunId: SERVER_RUN_ID } : {}) })
  return { contract: 'run-wave/plan-doc-v1', args, meta: {} }
}

function reseatResult(doc) {
  const item = doc.args.items[0]
  return {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, serverRunId: doc.args.serverRunId ?? null,
    items: [{
      id: item.id, short: item.short, status: 'done', reason: 'ok',
      stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })),
      outputs: { planner: PLANNER_OUT, implementer: IMPL_OUT, 'declarations-extractor': DECL_NONE },
    }],
    refused: [], deferred: [],
  }
}

test('S7: a test-author-a1 reseat prompt carries SENT("test-plan") once for cause review and breach; bound adds an END SEAT line naming failed', () => {
  const core = loadWaveCore()
  for (const cause of ['review', 'breach']) {
    for (const bound of [false, true]) {
      const doc = reseatDoc(bound)
      const out = reseat(core, doc, reseatResult(doc), SHORT, 'test-author-a1', { cause, pre: PRE, declarations: 'export function f(x)' })
      assert.equal(out.ok, true, JSON.stringify(out))
      assert.equal(countOf(out.prompt, sent('test-plan')), 1, `${cause}/${bound ? 'bound' : 'unbound'}: SENT once`)
      if (bound) {
        const endLine = out.prompt.split('\n').find((l) => l.includes('END SEAT:'))
        assert.ok(endLine, `${cause}: bound reseat has an END SEAT line`)
        assert.ok(endLine.includes('failed'), `${cause}: the END SEAT line names the failed outcome: ${endLine}`)
      }
    }
  }
})

// ---- S8 ----

test('S8: a resumed item whose stages have no planner-v1 stage still renders the TEST PLAN paragraph', () => {
  for (const list of [[authorStage()], [IMPLEMENTER_STAGE, authorStage()]]) {
    const item = makeItem(list)
    assert.equal(list.some((s) => s.output === 'planner-v1'), false, 'fixture condition: no planner-v1 stage')
    const stage = item.stages.find((s) => s.seat === 'test-author')
    const p = seatPromptOf(unboundPlan(item), item, stage)
    assert.equal(testPlanParagraphs(p).length, 1)
    assert.ok(p.includes(sent('test-plan')))
  }
})

// ---- S9 ----

test('S9: OUTPUT_SCHEMAS["test-author-v1"] requires manifestScenarios as an array of strings, also inside envelopeSchema', () => {
  const core = loadCore(SCRIPT_PATH)
  const expected = { type: 'array', items: { type: 'string' } }
  const schema = core.OUTPUT_SCHEMAS['test-author-v1']
  assert.ok(schema.required.includes('manifestScenarios'))
  assert.deepEqual(schema.properties.manifestScenarios, expected)
  const env = core.envelopeSchema('test-author-v1')
  const out = env.properties.output
  assert.ok(out.required.includes('manifestScenarios'))
  assert.deepEqual(out.properties.manifestScenarios, expected)
})

// ---- S10 ----

const AUTHOR_OUT = {
  returnLine: 'none', testFiles: [], scenariosCovered: 'none', manifestScenarios: [], verify: [],
  redAuthorTests: [], missingDeclaration: 'none', breachDisclosure: 'none',
}

function authorEnvelopeText(output) {
  return JSON.stringify({ status: 'done', reason: 'ok', notes: [], commits: { pre: 'p1', post: 'p2' }, files: [], modelReported: 'sonnet', output })
}

test('S10: Method B stageResult rejects a test-author envelope lacking manifestScenarios or holding a string, and accepts ["S1"]', () => {
  const core = loadWaveCore()
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [makeItem([authorStage()])] }), meta: { method: 'B' } }
  const run = (output) => stageResult(core, doc, initState(doc, 'B'), SHORT, 'test-author', authorEnvelopeText(output))
  const lacking = { ...AUTHOR_OUT }
  delete lacking.manifestScenarios
  assert.notEqual(run(lacking).status, 'done', 'missing manifestScenarios is not done')
  assert.notEqual(run({ ...AUTHOR_OUT, manifestScenarios: 'S1' }).status, 'done', 'a string is not an array')
  const ok = run({ ...AUTHOR_OUT, manifestScenarios: ['S1'] })
  assert.equal(ok.status, 'done', `${ok.status}/${ok.reason}`)
})

// ---- S11 ----

test('S11: every test-author-v1 prompt carries the manifestScenarios line once in its RETURN paragraph; planner and implementer prompts do not', () => {
  const core = loadCore(SCRIPT_PATH)
  for (const bound of [false, true]) {
    for (const withInput of [true, false]) {
      const stage = withInput ? authorStage() : withoutField(authorStage(), 'inputNotes')
      const item = makeItem([stage])
      const plan = bound ? boundPlan(item) : unboundPlan(item)
      const p = core.seatPrompt(plan, item, stage, {})
      assert.equal(countOf(p, MANIFEST_LINE), 1, `bound=${bound} inputNotes=${withInput}: the line appears once`)
      const ret = paragraphs(p).filter((x) => x.startsWith('RETURN'))
      assert.ok(ret.some((x) => x.includes(MANIFEST_LINE)), `bound=${bound} inputNotes=${withInput}: the line is in the RETURN paragraph`)
    }
  }
  const list = stages.bugFixLike()
  const item = makeItem(list)
  for (const seat of ['planner', 'implementer']) {
    const stage = list.find((s) => s.seat === seat)
    const p = core.seatPrompt(unboundPlan(item), item, stage, {})
    assert.equal(p.includes('manifestScenarios:'), false, `${seat}: no manifestScenarios line`)
  }
})

// ---- S12 ----

test('S12: agents/test-author.md has an "## Inputs" section naming test-plan as required on every attempt, a reseat and a breach', () => {
  const text = readFileSync(TEST_AUTHOR_MD, 'utf8').replace(/\r\n/g, '\n')
  const start = text.indexOf('\n## Inputs')
  assert.notEqual(start, -1, 'an "## Inputs" heading exists')
  const rest = text.slice(start + 1)
  const next = rest.indexOf('\n## ', 1)
  const section = next === -1 ? rest : rest.slice(0, next)
  assert.ok(section.includes('test-plan'), 'names test-plan')
  assert.match(section, /every attempt/i, 'says it is required on every attempt')
  assert.match(section, /reseat/i, 'names a reseat')
  assert.match(section, /breach/i, 'names a breach')
})

// ---- probes ----

test('P-replay: seatPrompt for the same inputs twice gives identical text', () => {
  const stage = authorStage({ inputNotes: ['test-plan', 'spec-plan'] })
  const item = makeItem([stage])
  const plan = boundPlan(item)
  assert.equal(seatPromptOf(plan, item, stage), seatPromptOf(plan, item, stage))
})
