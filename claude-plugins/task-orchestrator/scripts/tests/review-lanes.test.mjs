// Item 775f55e6 (U4): plugin side of declarable review lanes (a note's `lane: {output, after}`).
// Scenarios S4, S5, S6, S7, S10, S11, S12, S14 of the frozen test-plan (blind test-author seat; no
// production file was opened, only exported surfaces reached through loadCoreNamed, loadWaveCore and
// the declared reviewWaveArgs).
//
// Oracle: the frozen task-scope "Plugin" clauses and decisions D4/D5, recomputed by hand:
//  - a note's lane output is 'findings-v1' iff its `lane.output` is 'findings', else 'review-v1';
//  - each `lane.after` note key maps to the lane holding that note; stage.after = those lane ids, deduped, first-seen
//    order, present only when non-empty; an unknown key, an orchestrator-owned note, or a note of the note's own lane throws;
//  - preflight refuses `lane <l> after <x> names no sibling lane`, then `after cycle at lane <first in stage order that a
//    topological pass cannot schedule>`; coverage and owns-no-notes checks span every lane; `no review lanes` when no lane is
//    review-v1;
//  - a findings lane never sets the verdict (D4); a stopped lane of ANY output stops the item; fail-blocking from a verdict
//    lane comes first; `after` orders and never halts (D5); observations stay review-v1 only; lanes[] carry `output`.
// Fixture condition for each absence assertion: the paired lane in the same fixture does carry the member.
// Forbidden-construct declaration: none.

import '../../hooks/tests/hermetic-claude-config.mjs'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { fakeParallel } from './workflow-harness.mjs'
import { loadCoreNamed, autoAgent } from './workflow-harness-ext.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { buildPlanDoc } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const REVIEW_PATH = join(PLUGIN, 'workflows', 'review-wave.js')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'
const ITEM = 'aaaaaaaa-1111-4111-8111-111111111111'

const RW = loadCoreNamed(REVIEW_PATH, [
  'normalizeArgs', 'preflight', 'laneSchema', 'lanePrompt', 'mapLaneResult', 'aggregateVerdict',
  'runItem', 'runReview', 'REVIEW_OUTPUT_SCHEMAS',
])

// ---------------------------------------------------------------------------------------------
// reviewWaveArgs fixtures (S4, S10, S14)
// ---------------------------------------------------------------------------------------------

const snapshot = () => JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))

function argsFor(reviewNotes) {
  const snap = snapshot()
  snap.schemas[ITEM].notes.push(...reviewNotes)
  const built = buildPlanDoc(snap, { now: NOW })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  const doc = built.doc
  const item = doc.args.items.find((i) => i.id === ITEM)
  assert.ok(item, 'the fixture item is in the plan')
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: item.short, status: 'done', reason: 'ok',
      stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })),
      outputs: {},
    }],
    refused: [], deferred: [],
  }
  return execLib.reviewWaveArgs(loadWaveCore(), doc, snap, result, [item.short])
}

const note = (key, extra) => ({ key, role: 'review', required: true, seat: 'reviewer', ...extra })
const lanesOf = (args) => Object.fromEntries(args.items[0].stages.map((s) => [s.lane, s]))

test('S4: a findings lane that is after a verdict lane is stage output findings-v1 with after [the verdict lane]; the verdict lane is review-v1 with no after member', () => {
  const args = argsFor([
    note('n-a', { skill: 'rule-a' }),
    note('n-b', { skill: 'rule-b', lane: { output: 'findings', after: ['n-a'] } }),
  ])
  const lanes = lanesOf(args)
  assert.deepEqual(Object.keys(lanes).sort(), ['reviewer.rule-a', 'reviewer.rule-b'])
  assert.equal(lanes['reviewer.rule-a'].output, 'review-v1')
  assert.equal(Object.hasOwn(lanes['reviewer.rule-a'], 'after'), false, 'no after member on a lane that declares none')
  assert.equal(lanes['reviewer.rule-b'].output, 'findings-v1')
  assert.deepEqual(lanes['reviewer.rule-b'].after, ['reviewer.rule-a'])
  // the args are accepted by the workflow as built
  const norm = RW.normalizeArgs(args)
  assert.equal(norm.ok, true, norm.reason)
  assert.deepEqual(RW.preflight(norm.plan).refused, [])
})

test('S4: lane output verdict and an empty lane declare review-v1 and no after', () => {
  const lanes = lanesOf(argsFor([
    note('n-a', { skill: 'rule-a', lane: { output: 'verdict' } }),
    note('n-b', { skill: 'rule-b', lane: { output: 'verdict', after: [] } }),
  ]))
  for (const id of ['reviewer.rule-a', 'reviewer.rule-b']) {
    assert.equal(lanes[id].output, 'review-v1', id)
    assert.equal(Object.hasOwn(lanes[id], 'after'), false, id)
  }
})

test('S4 probe: two after keys held by one lane give one deduped lane id', () => {
  const lanes = lanesOf(argsFor([
    note('n-a1', { skill: 'rule-a' }),
    note('n-a2', { skill: 'rule-a' }),
    note('n-b', { skill: 'rule-b', lane: { output: 'findings', after: ['n-a1', 'n-a2'] } }),
  ]))
  assert.deepEqual(lanes['reviewer.rule-b'].after, ['reviewer.rule-a'])
})

test('S4 probe: after keeps first-seen order across lanes', () => {
  const lanes = lanesOf(argsFor([
    note('n-a', { skill: 'rule-a' }),
    note('n-b', { skill: 'rule-b' }),
    note('n-c', { skill: 'rule-c', lane: { after: ['n-b', 'n-a'] } }),
  ]))
  assert.deepEqual(lanes['reviewer.rule-c'].after, ['reviewer.rule-b', 'reviewer.rule-a'])
  assert.equal(lanes['reviewer.rule-c'].output, 'review-v1', 'a lane with only after is still a verdict lane')
})

test('S14: notes with no lane derive review-v1 stages with no after member', () => {
  const stages = argsFor([note('n-a', { skill: 'rule-a' }), note('n-b', { skill: 'rule-b' })]).items[0].stages
  assert.equal(stages.length, 2)
  for (const s of stages) {
    assert.equal(s.output, 'review-v1', s.lane)
    assert.equal(Object.hasOwn(s, 'after'), false, s.lane)
  }
})

test('S10: two notes of one lane with different outputs are refused as mixing lane outputs', () => {
  assert.throws(
    () => argsFor([
      note('n-1', { skill: 'rule-a', lane: { output: 'findings' } }),
      note('n-2', { skill: 'rule-a' }),
    ]),
    { message: 'invalid args: aaaaaaaa lane reviewer.rule-a mixes lane outputs' },
  )
})

test('S10: two notes of one lane that agree on the output are one lane', () => {
  const lanes = lanesOf(argsFor([
    note('n-1', { skill: 'rule-a', lane: { output: 'findings' } }),
    note('n-2', { skill: 'rule-a', lane: { output: 'findings' } }),
  ]))
  assert.equal(lanes['reviewer.rule-a'].output, 'findings-v1')
  assert.deepEqual(lanes['reviewer.rule-a'].notes.slice().sort(), ['n-1', 'n-2'])
})

test('S10: an after key that names no note is refused as in no lane', () => {
  assert.throws(
    () => argsFor([
      note('n-a', { skill: 'rule-a' }),
      note('n-b', { skill: 'rule-b', lane: { after: ['nope'] } }),
    ]),
    { message: 'invalid args: aaaaaaaa note n-b is after nope, which is in no lane' },
  )
})

test('S10: an after key that names an orchestrator-owned review note is refused as in no lane', () => {
  assert.throws(
    () => argsFor([
      note('n-orch', { seat: 'orchestrator' }),
      note('n-b', { skill: 'rule-b', lane: { after: ['n-orch'] } }),
    ]),
    { message: 'invalid args: aaaaaaaa note n-b is after n-orch, which is in no lane' },
  )
})

test('S10: an after key held by the note\'s own lane is refused as after itself', () => {
  assert.throws(
    () => argsFor([
      note('n-b1', { skill: 'rule-b' }),
      note('n-b2', { skill: 'rule-b', lane: { after: ['n-b1'] } }),
    ]),
    { message: 'invalid args: aaaaaaaa lane reviewer.rule-b is after itself' },
  )
})

// ---------------------------------------------------------------------------------------------
// review-wave plan fixtures (S5, S6, S7, S11, S12)
// ---------------------------------------------------------------------------------------------

function stage(lane, notes, extra = {}) {
  return {
    seat: 'reviewer', lane, phase: 'review', notes, writes: false,
    protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1', ...extra,
  }
}

function rawPlan(stages, { required } = {}) {
  const notes = stages.flatMap((s) => s.notes)
  return {
    contract: 'review-wave/args-v1', runId: 'r-test-0001', rootId: 'root-test-0001', baseSha: 'e77c3e95',
    worktreeMode: 'shared', entryMode: 'pre-entered',
    capabilities: { features: ['seats', 'dispatchBySeat', 'rules'], phase0Hooks: true },
    items: [{
      id: 'item-aaaaaaaa', short: 'aaaaaaaa', type: 'test-item', configFingerprint: 'fp-1', traits: [],
      worktree: '/tmp/wt-aaaaaaaa', stages,
      review: {
        ownedFiles: { implementer: ['a.js'], testAuthor: ['a.test.js'], docs: [] },
        commits: { implementer: ['aaaaaaa1'], testAuthor: ['bbbbbbb2'], fixtureRepairs: [] },
        implementerActors: ['implementer:aaaaaaaa:r-impl-0001'],
        testAuthorActors: ['test-author:aaaaaaaa:r-impl-0001'],
        requiredReviewNotes: required || notes,
      },
    }],
    deferred: [],
  }
}

function planOf(stages, opts) {
  const r = RW.normalizeArgs(rawPlan(stages, opts))
  assert.equal(r.ok, true, r.reason)
  return r.plan
}

const FINDING = { confidence: 'high', file: 'f.js', line: 3, expected: 'e', found: 'g' }

function verdictEnv(notes, { verdict = 'pass', findings = [] } = {}) {
  return {
    status: 'done', reason: 'ok', notes: notes.map((key) => ({ key })), commits: { pre: 'p', post: 'q' }, files: [],
    modelReported: 'fake-model', entry: { alreadyInPhase: true, previousRole: 'review' },
    output: { verdict, findings, independence: { mode: 'independent', violations: [], evidence: 'checked' }, notesFilled: notes },
  }
}

function findingsEnv(notes, { findings = [], leak = {} } = {}) {
  return {
    status: 'done', reason: 'ok', notes: notes.map((key) => ({ key })), commits: { pre: 'p', post: 'q' }, files: [],
    modelReported: 'fake-model', entry: { alreadyInPhase: true, previousRole: 'review' },
    output: { findings, notesFilled: notes, ...leak },
  }
}

function stoppedEnv(reason) {
  return {
    status: 'stopped', reason, notes: [], commits: { pre: '', post: '' }, files: [], modelReported: 'fake-model',
    entry: { alreadyInPhase: true, previousRole: 'review' }, output: {},
  }
}

const laneOfLabel = (label) => label.split(':')[0]
const callOf = (calls, lane) => calls.find((c) => laneOfLabel(c.label) === lane)

// ---- S5: after orders, never halts ----

test('S5: a lane after another starts only once the named lane has settled', async () => {
  const plan = planOf([
    stage('reviewer.a', ['na']),
    stage('reviewer.b', ['nb'], { output: 'findings-v1', after: ['reviewer.a'] }),
  ])
  const { agent, calls } = autoAgent(async (label) => (laneOfLabel(label) === 'reviewer.a' ? verdictEnv(['na']) : findingsEnv(['nb'])))
  const result = await RW.runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.equal(calls.length, 2)
  assert.ok(callOf(calls, 'reviewer.a').t1 < callOf(calls, 'reviewer.b').t0, 'b starts after a resolves')
  assert.equal(result.status, 'done')
})

test('S5: a stopped named lane does not halt the dependent lane and nothing is skipped', async () => {
  const plan = planOf([
    stage('reviewer.a', ['na']),
    stage('reviewer.b', ['nb'], { output: 'findings-v1', after: ['reviewer.a'] }),
  ])
  const { agent, calls } = autoAgent(async (label) => (laneOfLabel(label) === 'reviewer.a' ? stoppedEnv('boom') : findingsEnv(['nb'])))
  const result = await RW.runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.equal(calls.length, 2, `labels: ${calls.map((c) => c.label)}`)
  assert.ok(callOf(calls, 'reviewer.a').t1 < callOf(calls, 'reviewer.b').t0)
  assert.equal(result.lanes.length, 2)
  assert.equal(result.lanes.find((l) => l.lane === 'reviewer.b').status, 'done')
  assert.equal(JSON.stringify(result).includes('skipped'), false)
})

test('S5: lanes with no after all start before any resolves', async () => {
  const plan = planOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb']), stage('reviewer.c', ['nc'])])
  const { agent, calls } = autoAgent(async (label) => verdictEnv([{ 'reviewer.a': 'na', 'reviewer.b': 'nb', 'reviewer.c': 'nc' }[laneOfLabel(label)]]))
  await RW.runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.equal(calls.length, 3)
  assert.ok(Math.max(...calls.map((c) => c.t0)) < Math.min(...calls.map((c) => c.t1)), 'every lane started before any settled')
})

test('S5 probe: a three-lane chain runs in after order whatever the args order, and lanes[] stays in args order', async () => {
  const plan = planOf([
    stage('reviewer.z', ['nz'], { after: ['reviewer.y'] }),
    stage('reviewer.y', ['ny'], { output: 'findings-v1', after: ['reviewer.x'] }),
    stage('reviewer.x', ['nx']),
  ])
  const notes = { 'reviewer.z': 'nz', 'reviewer.y': 'ny', 'reviewer.x': 'nx' }
  const { agent, calls } = autoAgent(async (label) => {
    const lane = laneOfLabel(label)
    return lane === 'reviewer.y' ? findingsEnv([notes[lane]]) : verdictEnv([notes[lane]])
  })
  const result = await RW.runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.deepEqual(calls.slice().sort((p, q) => p.t0 - q.t0).map((c) => laneOfLabel(c.label)), ['reviewer.x', 'reviewer.y', 'reviewer.z'])
  assert.ok(callOf(calls, 'reviewer.x').t1 < callOf(calls, 'reviewer.y').t0)
  assert.ok(callOf(calls, 'reviewer.y').t1 < callOf(calls, 'reviewer.z').t0)
  assert.deepEqual(result.lanes.map((l) => l.lane), ['reviewer.z', 'reviewer.y', 'reviewer.x'])
})

test('S5: a stage with no after is dispatched through runReview exactly as before (E: all start before any resolves)', async () => {
  const plan = planOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'])])
  const { agent, calls } = autoAgent(async (label) => verdictEnv([laneOfLabel(label) === 'reviewer.a' ? 'na' : 'nb']))
  const result = await RW.runReview(plan, { agent, parallel: fakeParallel, log: () => {} })
  assert.equal(calls.length, 2)
  assert.deepEqual(result.refused, [])
  assert.ok(Math.max(...calls.map((c) => c.t0)) < Math.min(...calls.map((c) => c.t1)))
})

// ---- S6: a findings lane never sets the verdict ----

test('S6: a verdict lane that passes and a findings lane whose output says fail-blocking give done/pass', async () => {
  const plan = planOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { output: 'findings-v1' })])
  const leak = { verdict: 'fail-blocking', independence: { mode: 'not-independent', violations: [{ key: 'k', constraint: 'c' }], evidence: 'e' } }
  const { agent } = autoAgent(async (label) => (laneOfLabel(label) === 'reviewer.a' ? verdictEnv(['na']) : findingsEnv(['nb'], { findings: [FINDING], leak })))
  const result = await RW.runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.equal(result.status, 'done')
  assert.equal(result.verdict, 'pass')
  assert.equal(result.reason, 'review passed')
  const b = result.lanes.find((l) => l.lane === 'reviewer.b')
  assert.equal(b.output, 'findings-v1')
  assert.equal(b.verdict, null)
  assert.equal(result.lanes.find((l) => l.lane === 'reviewer.a').output, 'review-v1')
})

test('S6: a stopped findings lane stops the item with "<lane> <reason>"', async () => {
  const plan = planOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { output: 'findings-v1' })])
  const { agent } = autoAgent(async (label) => (laneOfLabel(label) === 'reviewer.a' ? verdictEnv(['na']) : stoppedEnv('boom')))
  const result = await RW.runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.equal(result.status, 'stopped')
  assert.equal(result.verdict, null)
  assert.equal(result.reason, 'reviewer.b boom')
})

test('S6: a fail-blocking verdict lane beats a stopped findings lane that comes first in args order', async () => {
  const plan = planOf([stage('reviewer.b', ['nb'], { output: 'findings-v1' }), stage('reviewer.a', ['na'])])
  const { agent } = autoAgent(async (label) => (laneOfLabel(label) === 'reviewer.a' ? verdictEnv(['na'], { verdict: 'fail-blocking' }) : stoppedEnv('boom')))
  const result = await RW.runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.equal(result.status, 'stopped')
  assert.equal(result.verdict, 'fail-blocking')
  assert.equal(result.reason, 'review-fail reviewer.a')
})

test('S6: findings of a findings lane are in lanes[] and never in observations', async () => {
  const plan = planOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { output: 'findings-v1' })])
  const obs = { severity: 'observation', confidence: 'low', file: 'o.js', line: 1, expected: 'p', found: 'q' }
  const { agent } = autoAgent(async (label) => (laneOfLabel(label) === 'reviewer.a'
    ? verdictEnv(['na'], { verdict: 'pass-with-observations', findings: [obs] })
    : findingsEnv(['nb'], { findings: [FINDING, { ...FINDING, line: 9 }] })))
  const result = await RW.runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.deepEqual(result.observations, [{ lane: 'reviewer.a', ...obs }])
  const b = result.lanes.find((l) => l.lane === 'reviewer.b')
  assert.equal(b.findingsCount, 2)
  assert.deepEqual(b.findings, [FINDING, { ...FINDING, line: 9 }])
  assert.equal(result.verdict, 'pass-with-observations')
})

test('S6: aggregateVerdict ignores a findings-v1 lane carrying fail-blocking and passes on the verdict lane', () => {
  const result = RW.aggregateVerdict([
    { lane: 'a', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'pass' },
    { lane: 'b', output: 'findings-v1', status: 'done', reason: 'ok', verdict: 'fail-blocking' },
  ])
  assert.deepEqual(result, { status: 'done', verdict: 'pass', reason: 'review passed' })
})

test('S6: aggregateVerdict with only findings lanes is stopped "no review lanes"; a stopped findings lane still stops', () => {
  assert.deepEqual(
    RW.aggregateVerdict([{ lane: 'b', output: 'findings-v1', status: 'done', reason: 'ok', verdict: null }]),
    { status: 'stopped', verdict: null, reason: 'no review lanes' },
  )
  assert.deepEqual(
    RW.aggregateVerdict([
      { lane: 'a', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'pass' },
      { lane: 'b', output: 'findings-v1', status: 'stopped', reason: 'boom', verdict: null },
    ]),
    { status: 'stopped', verdict: null, reason: 'b boom' },
  )
})

test('S6: mapLaneResult gives a findings-v1 lane verdict null whatever its output says', () => {
  const leak = { verdict: 'fail-blocking', independence: { mode: 'not-independent', violations: [], evidence: 'e' } }
  const result = RW.mapLaneResult({ output: 'findings-v1', notes: ['nb'] }, findingsEnv(['nb'], { leak }))
  assert.equal(result.status, 'done')
  assert.equal(result.verdict, null)
  // control: the same leak on a review-v1 lane does change the verdict
  const control = RW.mapLaneResult({ output: 'review-v1', notes: ['nb'] }, verdictEnv(['nb'], { verdict: 'fail-blocking' }))
  assert.equal(control.verdict, 'fail-blocking')
})

// ---- S7: findings-v1 prompt and schema ----

const FINDINGS_V1 = {
  type: 'object',
  required: ['findings', 'notesFilled'],
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        required: ['confidence', 'file', 'line', 'expected', 'found'],
        properties: {
          confidence: { enum: ['high', 'medium', 'low'] },
          file: { type: 'string' },
          line: { type: 'integer' },
          expected: { type: 'string' },
          found: { type: 'string' },
        },
      },
    },
    notesFilled: { type: 'array', items: { type: 'string' } },
  },
}

test('S7: the findings-v1 prompt carries a FINDINGS LANE line and no VERDICT, INDEPENDENCE or BOUNDARY line; the review-v1 prompt carries the reverse', () => {
  const plan = planOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { output: 'findings-v1' })])
  const item = plan.items[0]
  const fp = RW.lanePrompt(plan, item, item.stages.find((s) => s.lane === 'reviewer.b'))
  const vp = RW.lanePrompt(plan, item, item.stages.find((s) => s.lane === 'reviewer.a'))

  assert.match(fp, /^FINDINGS LANE:/m)
  for (const word of ['VERDICT:', 'INDEPENDENCE:', 'BOUNDARY:']) {
    assert.equal(new RegExp(`^\\s*${word}`, 'm').test(fp), false, `findings prompt has a ${word} line`)
    assert.equal(vp.includes(word), true, `control: the review-v1 prompt carries ${word}`)
  }
  assert.equal(/^FINDINGS LANE:/m.test(vp), false)
})

test('S7: REVIEW_OUTPUT_SCHEMAS findings-v1 equals the declared literal', () => {
  assert.deepEqual(RW.REVIEW_OUTPUT_SCHEMAS['findings-v1'], FINDINGS_V1)
})

test('S7: laneSchema findings-v1 has the same envelope members as review-v1 and its own output schema', () => {
  const f = RW.laneSchema('findings-v1')
  const v = RW.laneSchema('review-v1')
  assert.deepEqual(f.required, v.required)
  assert.deepEqual(Object.keys(f.properties), Object.keys(v.properties))
  assert.deepEqual(f.properties.output, FINDINGS_V1)
  for (const key of Object.keys(v.properties).filter((k) => k !== 'output')) {
    assert.deepEqual(f.properties[key], v.properties[key], key)
  }
  assert.notDeepEqual(f.properties.output, v.properties.output)
})

// ---- S11: normalizeArgs ----

test('S11: a findings-v1 stage is accepted', () => {
  const r = RW.normalizeArgs(rawPlan([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { output: 'findings-v1' })]))
  assert.equal(r.ok, true, r.reason)
  assert.equal(r.plan.items[0].stages.find((s) => s.lane === 'reviewer.b').output, 'findings-v1')
})

test('S11: simplify-v1 is a bad stage output', () => {
  const r = RW.normalizeArgs(rawPlan([stage('reviewer.a', ['na'], { output: 'simplify-v1' })]))
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'invalid args: bad stage output')
})

test('S11: an after that is a string, holds a blank, or holds a non-string is a bad stage after', () => {
  for (const bad of ['x', [''], [5]]) {
    const r = RW.normalizeArgs(rawPlan([stage('reviewer.a', ['na'], { after: bad })]))
    assert.equal(r.ok, false, JSON.stringify(bad))
    assert.equal(r.reason, 'invalid args: bad stage after', JSON.stringify(bad))
  }
  // control: an array of non-empty strings and an absent after are accepted
  assert.equal(RW.normalizeArgs(rawPlan([stage('reviewer.a', ['na'], { after: ['reviewer.b'] }), stage('reviewer.b', ['nb'])])).ok, true)
  assert.equal(RW.normalizeArgs(rawPlan([stage('reviewer.a', ['na'])])).ok, true)
})

// ---- S12: preflight ----

const refusedOf = (stages, opts) => RW.preflight(planOf(stages, opts)).refused

test('S12: an after naming an unknown lane or the lane itself is refused as naming no sibling lane', () => {
  assert.deepEqual(
    refusedOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { after: ['zzz'] })]),
    [{ id: 'item-aaaaaaaa', reason: 'lane reviewer.b after zzz names no sibling lane' }],
  )
  assert.deepEqual(
    refusedOf([stage('reviewer.a', ['na'], { after: ['reviewer.a'] })]),
    [{ id: 'item-aaaaaaaa', reason: 'lane reviewer.a after reviewer.a names no sibling lane' }],
  )
})

test('S12: a valid after between sibling lanes is not refused', () => {
  assert.deepEqual(refusedOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { after: ['reviewer.a'] })]), [])
})

test('S12: a two-lane cycle is refused at the first lane in stage order', () => {
  assert.deepEqual(
    refusedOf([stage('reviewer.a', ['na'], { after: ['reviewer.b'] }), stage('reviewer.b', ['nb'], { after: ['reviewer.a'] })]),
    [{ id: 'item-aaaaaaaa', reason: 'after cycle at lane reviewer.a' }],
  )
})

test('S12: a schedulable lane ahead of a cycle is not the one named', () => {
  assert.deepEqual(
    refusedOf([
      stage('reviewer.c', ['nc']),
      stage('reviewer.a', ['na'], { after: ['reviewer.b'] }),
      stage('reviewer.b', ['nb'], { after: ['reviewer.a'] }),
    ]),
    [{ id: 'item-aaaaaaaa', reason: 'after cycle at lane reviewer.a' }],
  )
})

test('S12: a findings lane with no notes owns no notes', () => {
  assert.deepEqual(
    refusedOf([stage('reviewer.a', ['na']), stage('reviewer.b', [], { output: 'findings-v1' })], { required: ['na'] }),
    [{ id: 'item-aaaaaaaa', reason: 'lane reviewer.b owns no notes' }],
  )
})

test('S12: a required note held only by a findings lane is covered', () => {
  assert.deepEqual(
    refusedOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { output: 'findings-v1' })], { required: ['na', 'nb'] }),
    [],
  )
})

test('S12: a required note held by no lane of either output is refused as uncovered', () => {
  const refused = refusedOf([stage('reviewer.a', ['na']), stage('reviewer.b', ['nb'], { output: 'findings-v1' })], { required: ['na', 'nb', 'nc'] })
  assert.equal(refused.length, 1)
  assert.equal(refused[0].id, 'item-aaaaaaaa')
  assert.ok(refused[0].reason.includes('nc'), refused[0].reason)
})

test('S12: an item whose lanes are all findings lanes is refused as having no review lanes', () => {
  assert.deepEqual(
    refusedOf([stage('reviewer.b', ['nb'], { output: 'findings-v1' })]),
    [{ id: 'item-aaaaaaaa', reason: 'no review lanes' }],
  )
})
