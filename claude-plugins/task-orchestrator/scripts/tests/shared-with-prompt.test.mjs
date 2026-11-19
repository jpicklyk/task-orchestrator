// SHARED WITH prompt part: a writing seat of a shared worktree whose locked files overlap a sibling's
// declared files is told whose content it is changing. Oracle: the line text in the item specification
// (SHARED WITH <short>: <paths>. then one instruction sentence), never the implementation.
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { planFixture, itemFixture, stages, fakeAgent, fakeParallel } from './workflow-harness.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import * as execLib from '../run-exec-lib.mjs'

const A = 'aaaaaaaa'
const B = 'bbbbbbbb'
const C = 'cccccccc'
const INSTRUCTION =
  'These files are shared with the sibling item(s) named above in this worktree. Never change an assertion, claim or rule a ' +
  'sibling wrote in them; when your change needs one changed, leave it as it is and record it as a deviation (DEVIATION ' +
  'below), so the orchestrator routes it.'

function plannerOut(mainFiles, over = {}) {
  return {
    proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
    decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
    mainFiles, docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none', sweep: [],
    ...over,
  }
}

function envelope(output) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'fake', entry: { applied: true, newRole: 'work' }, output,
  }
}

function makePlan(shorts, over = {}) {
  const items = shorts.map((short) => itemFixture({ short, stages: stages.featureTaskLike() }))
  return planFixture({ items, ...over })
}

const count = (text, needle) => text.split(needle).length - 1

/** Method A: run the plan through the harness; returns the prompt of the later implementer call. */
async function runA(plan, planners) {
  const script = {}
  for (const [short, out] of Object.entries(planners)) script[`planner:${short}`] = envelope(out)
  const { agent, calls } = fakeAgent(script)
  await loadWaveCore().runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })
  const impl = calls.filter((c) => c.label.startsWith('implementer:'))
  return { impl, calls, last: impl[impl.length - 1] }
}

/** Method B: a state in which the given planner stages are done, then prompt() for one implementer. */
function promptB(plan, planners, doneShorts, target) {
  const doc = { contract: 'run-wave/plan-doc-v1', args: plan, meta: {} }
  const state = execLib.initState(doc)
  for (const [short, out] of Object.entries(planners)) {
    state.outs[`${short}:planner`] = out
    if (doneShorts.includes(short)) state.stages[`${short}:planner`] = { status: 'done', reason: 'ok' }
  }
  return execLib.prompt(loadWaveCore(), doc, state, target, 'implementer')
}

const SHARED = { [A]: plannerOut(['src/shared.js', 'src/a.js']), [B]: plannerOut(['src/shared.js']) }

test('S1: Method A, the later implementer of two overlapping siblings carries one SHARED WITH line and the sentence once', async () => {
  const { impl, last } = await runA(makePlan([A, B]), SHARED)
  assert.equal(impl.length, 2)
  const firstShort = impl[0].label.split(':')[1]
  const lastShort = last.label.split(':')[1]
  assert.notEqual(firstShort, lastShort)
  assert.equal(count(last.prompt, `SHARED WITH ${firstShort}: src/shared.js.`), 1)
  assert.equal(count(last.prompt, INSTRUCTION), 1)
  assert.ok(!last.prompt.includes('SHARED WITH ' + lastShort), 'never names itself')
  const line = last.prompt.split('\n').find((l) => l.startsWith('SHARED WITH '))
  assert.ok(!line.includes('src/a.js'), 'only the overlapping path is named')
})

test('S2: Method B, the same plan yields the same prompt as Method A for the later implementer', async () => {
  const plan = makePlan([A, B])
  const { impl, last } = await runA(plan, SHARED)
  const lastShort = last.label.split(':')[1]
  const textB = promptB(plan, SHARED, [A, B], lastShort)
  assert.equal(textB, last.prompt)
  assert.equal(count(textB, INSTRUCTION), 1)
  assert.equal(impl.length, 2)
})

test('S3a: per-item worktree mode renders no line', () => {
  const plan = makePlan([A, B], { worktreeMode: 'per-item' })
  const text = promptB(plan, SHARED, [A, B], B)
  assert.ok(!text.includes('SHARED WITH'))
})

test('S3b: shared mode with disjoint files renders no line', () => {
  const planners = { [A]: plannerOut(['src/a.js']), [B]: plannerOut(['src/b.js']) }
  const text = promptB(makePlan([A, B]), planners, [A, B], B)
  assert.ok(!text.includes('SHARED WITH'))
})

test('S3c: a non-writing stage (planner) renders no line', () => {
  const plan = makePlan([A, B])
  const doc = { contract: 'run-wave/plan-doc-v1', args: plan, meta: {} }
  const state = execLib.initState(doc)
  state.outs[`${A}:planner`] = SHARED[A]
  state.stages[`${A}:planner`] = { status: 'done', reason: 'ok' }
  const text = execLib.prompt(loadWaveCore(), doc, state, B, 'planner')
  assert.ok(!text.includes('SHARED WITH'))
})

test('S3d: a sibling whose planner is not done renders no line', () => {
  const text = promptB(makePlan([A, B]), SHARED, [B], B)
  assert.ok(!text.includes('SHARED WITH'))
})

test('S3e: a whole-tree claim on either side renders no line', () => {
  const wholeSibling = { [A]: plannerOut(['.']), [B]: plannerOut(['src/shared.js']) }
  assert.ok(!promptB(makePlan([A, B]), wholeSibling, [A, B], B).includes('SHARED WITH'))
  const wholeMine = { [A]: plannerOut(['src/shared.js']), [B]: plannerOut([]) }
  assert.ok(!promptB(makePlan([A, B]), wholeMine, [A, B], B).includes('SHARED WITH'))
})

test('S4a: two overlapping siblings give two lines in plan.items order and one sentence', () => {
  const planners = {
    [A]: plannerOut(['src/shared.js']),
    [B]: plannerOut(['src/other.js', 'src/shared.js']),
    [C]: plannerOut(['src/shared.js', 'src/other.js']),
  }
  const text = promptB(makePlan([A, B, C]), planners, [A, B, C], C)
  const lines = text.split('\n').filter((l) => l.startsWith('SHARED WITH '))
  assert.deepEqual(lines, [
    `SHARED WITH ${A}: src/shared.js.`,
    `SHARED WITH ${B}: src/other.js, src/shared.js.`,
  ])
  assert.equal(count(text, INSTRUCTION), 1)
})

test('S4b: a sibling declaring a directory overlaps a file under it and the line names the file', () => {
  const planners = { [A]: plannerOut(['src/']), [B]: plannerOut(['src/x.js']) }
  const text = promptB(makePlan([A, B]), planners, [A, B], B)
  assert.equal(count(text, `SHARED WITH ${A}: src/x.js.`), 1)
})

test('S4c: an appendOnlyFiles match is not exempt', () => {
  const planners = { [A]: plannerOut(['CHANGELOG.md']), [B]: plannerOut(['CHANGELOG.md']) }
  const plan = makePlan([A, B], { project: { appendOnlyFiles: ['CHANGELOG.md'] } })
  assert.equal(count(promptB(plan, planners, [A, B], B), `SHARED WITH ${A}: CHANGELOG.md.`), 1)
})

test('S5: reseat of an implementer fix seat carries the SHARED WITH line from the result', () => {
  const plan = makePlan([A, B])
  const doc = { contract: 'run-wave/plan-doc-v1', args: plan, meta: {} }
  const row = (item, planner, status = 'done') => ({
    id: item.id, short: item.short, status: 'done', reason: 'ok',
    stages: item.stages.map((s) => ({
      seat: s.seat, status: s.seat === 'planner' ? status : 'done', reason: 'ok', modelReported: 'sonnet',
      notes: [], commits: { pre: '', post: '' }, files: [],
    })),
    outputs: { planner },
  })
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: plan.runId, planDocSlug: 'run/x',
    items: [row(plan.items[0], SHARED[A]), row(plan.items[1], SHARED[B])], refused: [], deferred: [],
  }
  const r = execLib.reseat(loadWaveCore(), doc, result, B, 'implementer-a1', { cause: 'review' })
  assert.equal(r.ok, true)
  assert.equal(count(r.prompt, `SHARED WITH ${A}: src/shared.js.`), 1)
  const notDone = { ...result, items: [row(plan.items[0], SHARED[A], 'stopped'), result.items[1]] }
  const r2 = execLib.reseat(loadWaveCore(), doc, notDone, B, 'implementer-a1', { cause: 'review' })
  assert.ok(!r2.prompt.includes('SHARED WITH'))
})

test('S6: makeMilestones.peek is undefined before settle, the value after, null after releaseFrom, and creates no promise', async () => {
  const items = makePlan([A, B]).items
  const ms = loadWaveCore().makeMilestones(items)
  assert.equal(ms.peek(items[0].id, 'planner'), undefined)
  ms.settle(items[0].id, 'planner', { status: 'done', output: { x: 1 } })
  assert.deepEqual(ms.peek(items[0].id, 'planner'), { status: 'done', output: { x: 1 } })
  assert.equal(ms.peek(items[1].id, 'planner'), undefined)
  ms.releaseFrom(items[1], 0)
  assert.equal(ms.peek(items[1].id, 'planner'), null)
  assert.deepEqual(await ms.get(items[0].id, 'planner'), { status: 'done', output: { x: 1 } })
})

test('S7: the existing four-argument seatPrompt call renders without a SHARED WITH line', () => {
  const plan = makePlan([A, B])
  const core = loadWaveCore()
  const text = core.seatPrompt(plan, plan.items[1], plan.items[1].stages[1], { planner: SHARED[B] })
  assert.ok(!text.includes('SHARED WITH'))
})
