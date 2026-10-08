// Regression coverage for the "entering seat returns no entry report" defect (item 91544425).
// Scenario ids S1-S14 match the item's frozen `test-plan` note. Oracles are the frozen planning
// decisions D1-D4 and corrections C1-C5 in the item's `diagnosis` note, never observed output.
// Only the exported core surface of workflows/implement-wave.js is used (via workflow-harness.mjs).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { loadCore, planFixture, itemFixture, stages, fakeAgent, fakeParallel } from './workflow-harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT_PATH = join(HERE, '..', '..', 'workflows', 'implement-wave.js')

const R7 = ['status', 'reason', 'notes', 'commits', 'files', 'modelReported', 'output']

// D2 frozen prompt lines, verbatim.
const SEAT_LINE =
  'ENTRY REPORT (required): copy the advance_item result into entry (applied, newRole, previousRole, errorCode, contendedResources; missingNotes as key strings; blockers as fromItemId strings; retried true only after a config_unavailable retry). Role "work": entry {"applied":false,"alreadyInPhase":true}.'
const PRE_ENTERED_LINE =
  'ENTRY REPORT (required): role "work" -> entry {"applied":false,"alreadyInPhase":true}; else entry {"applied":false,"alreadyInPhase":false} and status "stopped".'

function item(short, stageList) {
  return itemFixture({ short, stages: stageList, traits: ['delegated'] })
}

function plan(items, overrides = {}) {
  return planFixture({ items, ...overrides })
}

function find(calls, label) {
  return calls.find((c) => c.label === label)
}

const ENTERING_STAGE = { seat: 'owner', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'implementer-v1' }

async function run(p, script) {
  const fa = fakeAgent(script)
  const result = await loadCore(SCRIPT_PATH).runPlan(p, { agent: fa.agent, parallel: fakeParallel, log: () => {} })
  return { result, calls: fa.calls }
}

// ---- D1: schema requires entry for entering stages ----

test('S1: seat-mode entering implementer is dispatched with required = the seven base fields plus entry (last)', async () => {
  const it = item('s1aaaaaa', stages.featureTaskLike())
  const { calls } = await run(plan([it]), {})
  const call = find(calls, 'implementer:s1aaaaaa')
  assert.ok(call, 'implementer dispatched')
  assert.deepEqual(call.opts.schema.required, [...R7, 'entry'])
})

test('S2: pre-entered entering implementer is dispatched with required = the seven base fields plus entry (last)', async () => {
  const it = item('s2aaaaaa', stages.featureTaskLike())
  const { calls } = await run(plan([it], { entryMode: 'pre-entered' }), {})
  const call = find(calls, 'implementer:s2aaaaaa')
  assert.ok(call, 'implementer dispatched')
  assert.deepEqual(call.opts.schema.required, [...R7, 'entry'])
})

test('S3: the non-entering planner is dispatched with required exactly the seven base fields (guard: entry is not added always)', async () => {
  const it = item('s3aaaaaa', stages.featureTaskLike())
  const { calls } = await run(plan([it]), {})
  const call = find(calls, 'planner:s3aaaaaa')
  assert.ok(call, 'planner dispatched')
  assert.deepEqual(call.opts.schema.required, R7)
})

test('S4: envelopeSchema opts is backward compatible; opts.enters true appends entry last, properties unchanged', () => {
  const core = loadCore(SCRIPT_PATH)
  const base = JSON.stringify(core.envelopeSchema('generic-v1'))
  assert.equal(JSON.stringify(core.envelopeSchema('generic-v1', {})), base)
  assert.equal(JSON.stringify(core.envelopeSchema('generic-v1', {}, {})), base)
  assert.equal(JSON.stringify(core.envelopeSchema('generic-v1', {}, { enters: false })), base)
  assert.deepEqual(core.envelopeSchema('generic-v1').required, R7)
  const entering = core.envelopeSchema('generic-v1', {}, { enters: true })
  assert.deepEqual(entering.required, [...R7, 'entry'])
  assert.deepEqual(entering.properties, core.envelopeSchema('generic-v1').properties)
  assert.ok('entry' in entering.properties)
})

// ---- D2: prompt lines ----

test('S8: seat-mode implementer prompt carries the D2 seat line and still forbids start from work; test-author prompt has no ENTRY REPORT', () => {
  const core = loadCore(SCRIPT_PATH)
  const it = item('s8aaaaaa', stages.bugFixLike())
  const p = plan([it])
  const impl = core.seatPrompt(p, it, it.stages.find((s) => s.seat === 'implementer'), {})
  assert.ok(impl.includes(SEAT_LINE), 'seat-mode implementer prompt must contain the D2 seat line verbatim')
  assert.ok(impl.toLowerCase().includes('never call start from work'))
  const ta = core.seatPrompt(p, it, it.stages.find((s) => s.seat === 'test-author'), {})
  assert.ok(!ta.includes('ENTRY REPORT'), 'non-entering test-author prompt must not carry an ENTRY REPORT line')
})

test('S9: pre-entered implementer prompt carries the D2 pre-entered line, not the seat line, and no advance_item tool', () => {
  const core = loadCore(SCRIPT_PATH)
  const it = item('s9aaaaaa', stages.bugFixLike())
  const p = plan([it], { entryMode: 'pre-entered' })
  const impl = core.seatPrompt(p, it, it.stages.find((s) => s.seat === 'implementer'), {})
  assert.ok(impl.includes(PRE_ENTERED_LINE), 'pre-entered implementer prompt must contain the D2 pre-entered line verbatim')
  assert.ok(!impl.includes(SEAT_LINE))
  assert.ok(!impl.includes('copy the advance_item result into entry'))
  assert.ok(!impl.includes('mcp__mcp-task-orchestrator__advance_item'))
})

// ---- D3: no fabricated entry ----

test('S10: entering stage, status done, no entry -> stopped "no entry report" (guard)', () => {
  const core = loadCore(SCRIPT_PATH)
  const r = core.mapStageResult(ENTERING_STAGE, { status: 'done', reason: 'ok' }, 'seat')
  assert.deepEqual(r, { status: 'stopped', reason: 'no entry report' })
})

// ---- D4: stopped / deferred reasons survive a missing entry ----

test('S11: entering stage stopped or deferred with a reason and no entry keeps its own status and reason', () => {
  const core = loadCore(SCRIPT_PATH)
  assert.deepEqual(
    core.mapStageResult(ENTERING_STAGE, { status: 'stopped', reason: 'agent threw: boom' }, 'seat'),
    { status: 'stopped', reason: 'agent threw: boom' },
  )
  assert.deepEqual(
    core.mapStageResult(ENTERING_STAGE, { status: 'deferred', reason: 'held' }, 'seat'),
    { status: 'deferred', reason: 'held' },
  )
})

test('S12: an implementer agent throw surfaces as item stopped "agent threw: boom", not "no entry report"', async () => {
  const it = item('s12aaaaa', stages.featureTaskLike())
  const { result } = await run(plan([it]), { 'implementer:s12aaaaa': { throw: 'boom' } })
  const r = result.items.find((x) => x.short === 's12aaaaa')
  assert.equal(r.status, 'stopped')
  assert.equal(r.reason, 'agent threw: boom')
})

test('S13a: entry {applied:false} with stopped "rule x" -> stopped "rule x"', () => {
  const core = loadCore(SCRIPT_PATH)
  const r = core.mapStageResult(ENTERING_STAGE, { status: 'stopped', reason: 'rule x', entry: { applied: false } }, 'seat')
  assert.deepEqual(r, { status: 'stopped', reason: 'rule x' })
})

test('S13b: stopped with empty reason and no entry -> stopped "no entry report" (guard)', () => {
  const core = loadCore(SCRIPT_PATH)
  const r = core.mapStageResult(ENTERING_STAGE, { status: 'stopped', reason: '' }, 'seat')
  assert.deepEqual(r, { status: 'stopped', reason: 'no entry report' })
})

test('S13c: pre-entered, not in work, stopped -> stopped "not pre-entered" (guard)', () => {
  const core = loadCore(SCRIPT_PATH)
  const r = core.mapStageResult(
    ENTERING_STAGE,
    { status: 'stopped', reason: 'whatever', entry: { applied: false, alreadyInPhase: false } },
    'pre-entered',
  )
  assert.equal(r.status, 'stopped')
  assert.equal(r.reason, 'not pre-entered')
})

test('S13d: entry dependency_blocked with blockers [b1] and stopped -> deferred "blocked by b1" (guard)', () => {
  const core = loadCore(SCRIPT_PATH)
  const r = core.mapStageResult(
    ENTERING_STAGE,
    { status: 'stopped', reason: 'x', entry: { applied: false, errorCode: 'dependency_blocked', blockers: ['b1'] } },
    'seat',
  )
  assert.deepEqual(r, { status: 'deferred', reason: 'blocked by b1' })
})

// ---- Symptom: a correctly entered seat proceeds through the later stages ----

for (const [name, short, entry] of [
  ['applied true', 's14aaaaa', { applied: true, newRole: 'work' }],
  ['already in phase', 's14bbbbb', { applied: false, alreadyInPhase: true }],
]) {
  test(`S14: bug-fix item with implementer entry (${name}) runs declarations-extractor and test-author and finishes done "all stages done"`, async () => {
    const it = item(short, stages.bugFixLike())
    const { result, calls } = await run(plan([it]), {
      [`implementer:${short}`]: {
        status: 'done', reason: 'ok', notes: [], commits: { pre: 'a', post: 'b' }, files: [], modelReported: 'fake', entry,
        output: { mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [], preExistingFailures: [], publicSurface: 'none', deviations: 'none' },
      },
    })
    assert.ok(find(calls, `declarations-extractor:${short}`), 'declarations-extractor ran')
    assert.ok(find(calls, `test-author:${short}`), 'test-author ran')
    const r = result.items.find((x) => x.short === short)
    assert.equal(r.status, 'done')
    assert.equal(r.reason, 'all stages done')
  })
}