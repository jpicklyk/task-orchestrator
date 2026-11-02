// Blind-author tests for item cd48f32c: review lanes skip orchestrator-owned review notes, and the
// reseat fix-cycle line routes to the amendments note. Scenarios S1-S8 and the probes match the
// item frozen `test-plan` note. Oracles: task-scope AC-B1 (a-d) and AC5b, the carry-in text and
// plan 4b - never "what the code returns". Implementation sources were not opened; the suite uses
// the run-planner CLI as a subprocess, the exported surface of run-exec-lib.mjs, loadWaveCore,
// and the existing review-wave.js core via loadCoreNamed.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { loadCoreNamed } from './workflow-harness-ext.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import * as execLib from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const CLI = join(PLUGIN, 'scripts', 'run-planner.mjs')
const { normalizeArgs, preflight } = loadCoreNamed(join(PLUGIN, 'workflows', 'review-wave.js'), ['normalizeArgs', 'preflight'])

const A = 'aaaaaaaa-1111-4111-8111-111111111111'
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'

function cli(args, input) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', input: input === undefined ? undefined : JSON.stringify(input) })
}

const SEATS = [
  { name: 'planner', phase: 'queue' },
  { name: 'implementer', phase: 'work', enters: true },
  { name: 'orchestrator', phase: 'work' },
  { name: 'reviewer', phase: 'review' },
]

function seatAwareNotes(extra) {
  return [
    { key: 'specification', role: 'queue', required: true, seat: 'planner' },
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
    { key: 'session-tracking', role: 'work', required: true, seat: 'orchestrator' },
    ...extra,
  ]
}

const CHECKLIST = { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer', skill: 'review-quality' }
const CONFIRM = (over = {}) => ({ key: 'orchestrator-confirmation', role: 'review', required: false, seat: 'orchestrator', ...over })

// reviewNotes: the item review-phase notes; seatAware: declare seats, else the schema is seat-less.
function fixture({ reviewNotes, seatAware, bind = true }) {
  const dir = mkdtempSync(join(tmpdir(), 'review-lane-orch-'))
  const snap = JSON.parse(readFileSync(join(HERE, 'fixtures', 'run-planner', 'independent-two.json'), 'utf8'))
  const schema = snap.schemas[A]
  if (seatAware) {
    schema.notes = seatAwareNotes(reviewNotes)
    schema.seats = SEATS
  } else {
    schema.notes.push(...reviewNotes)
  }
  schema.dispatchBySeat = { review: { reviewer: { agent: 'task-orchestrator:reviewer', model: 'opus', effort: 'high' } } }
  const planRes = cli(['plan', '--now', '2026-09-28T12:00:00Z', '--scratchpad', join(dir, 'sp')], snap)
  assert.equal(planRes.status, 0, planRes.stderr)
  let doc = JSON.parse(planRes.stdout)
  let planPath = join(dir, 'plan.json')
  writeFileSync(planPath, JSON.stringify(doc))
  if (bind) {
    const b = cli(['bind-run', '--in', planPath, '--server-run', SERVER_RUN])
    assert.equal(b.status, 0, b.stderr)
    doc = JSON.parse(b.stdout)
    planPath = join(dir, 'bound.json')
    writeFileSync(planPath, b.stdout)
  }
  const snapPath = join(dir, 'snapshot.json')
  writeFileSync(snapPath, JSON.stringify(snap))
  return { dir, doc, planPath, snapPath }
}

function reviewArgs(fx, items = 'aaaaaaaa') {
  return cli(['review-args', '--plan', fx.planPath, '--snapshot', fx.snapPath, '--items', items])
}

function okArgs(fx) {
  const res = reviewArgs(fx)
  assert.equal(res.status, 0, res.stderr + res.stdout)
  return JSON.parse(res.stdout)
}

function itemOf(args) {
  return args.items[0]
}

function lanes(item) {
  return item.stages.map((s) => s.lane).sort()
}

function plannedItem(fx) {
  return fx.doc.args.items.find((i) => i.short === 'aaaaaaaa')
}

// ---- S1: seat-aware, optional orchestrator-confirmation ----------------------------------

test('S1: seat-aware optional orchestrator-confirmation forms no stage and no lane note', () => {
  const fx = fixture({ reviewNotes: [CHECKLIST, CONFIRM()], seatAware: true })
  const args = okArgs(fx)
  const item = itemOf(args)
  assert.equal(item.stages.some((s) => s.seat === 'orchestrator'), false, 'no orchestrator stage')
  assert.equal(item.stages.some((s) => (s.notes || []).includes('orchestrator-confirmation')), false, 'no stage holds the key')
  assert.deepEqual(lanes(item), ['reviewer.review-quality'])
  assert.equal((item.review.optionalReviewNotes || []).includes('orchestrator-confirmation'), false)
  const norm = normalizeArgs(args)
  assert.equal(norm.ok, true, norm.reason)
  assert.deepEqual(preflight(norm.plan).refused, [])
})

// ---- S2: seat-less ------------------------------------------------------------------------

test('S2: seat-less orchestrator-confirmation lands in orchestratorNotes and out of the reviewer lane', () => {
  const noSeat = (n) => ({ ...n, seat: null })
  const fx = fixture({ reviewNotes: [noSeat(CHECKLIST), noSeat(CONFIRM())], seatAware: false })
  assert.ok(plannedItem(fx).orchestratorNotes.includes('orchestrator-confirmation'), 'plan orchestratorNotes')
  const item = itemOf(okArgs(fx))
  const laneNotes = item.stages.flatMap((s) => s.notes || [])
  assert.ok(laneNotes.includes('review-checklist'))
  assert.equal(laneNotes.includes('orchestrator-confirmation'), false)
})

// ---- S3: required orchestrator-confirmation -----------------------------------------------

test('S3: a required orchestrator-confirmation does not exclude the item and is in neither review list', () => {
  const fx = fixture({ reviewNotes: [CHECKLIST, CONFIRM({ required: true })], seatAware: true })
  const args = okArgs(fx)
  const item = itemOf(args)
  assert.deepEqual(lanes(item), ['reviewer.review-quality'])
  assert.equal(item.review.requiredReviewNotes.includes('orchestrator-confirmation'), false)
  assert.equal((item.review.optionalReviewNotes || []).includes('orchestrator-confirmation'), false)
  const norm = normalizeArgs(args)
  assert.equal(norm.ok, true, norm.reason)
  assert.deepEqual(preflight(norm.plan).refused, [])
})

// ---- S4: only orchestrator-owned review notes ---------------------------------------------

test('S4: an item whose only review note is orchestrator-owned exits 2', () => {
  const fx = fixture({ reviewNotes: [CONFIRM({ required: true })], seatAware: true })
  const res = reviewArgs(fx)
  assert.equal(res.status, 2, res.stdout + res.stderr)
})

// ---- S5: planner seat prompt ---------------------------------------------------------------

test('S5: the seat-aware planner prompt lists orchestrator-confirmation among the orchestrator-owned notes', () => {
  const fx = fixture({ reviewNotes: [CHECKLIST, CONFIRM()], seatAware: true })
  const state = execLib.initState(fx.doc, 'B')
  const out = execLib.prompt(loadWaveCore(), fx.doc, state, 'aaaaaaaa', 'planner')
  const text = typeof out === 'string' ? out : out.prompt
  assert.match(text, /Orchestrator-owned notes [[][^\]]*orchestrator-confirmation[^\]]*[\]]: never write them/)
})

// ---- S6: review prompt ----------------------------------------------------------------------

test('S6: reviewPrompt names the orchestrator-owned keys as never-write; an item without them has no such line', () => {
  const fx = fixture({ reviewNotes: [CHECKLIST, CONFIRM()], seatAware: true })
  const core = loadWaveCore()
  const text = execLib.reviewPrompt(core, fx.doc, 'aaaaaaaa')
  assert.match(text, /Never write orchestrator-owned notes [[][^\]]*orchestrator-confirmation[^\]]*[\]]: the orchestrator records them[.]/)

  const planner = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const impl = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const plain = itemFixture({ short: 'dddddddd', stages: [planner, impl], worktree: '/repo/wt-dddddddd' })
  const plainDoc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [plain], baseSha: 'e77c3e95' }), meta: {} }
  assert.equal(execLib.reviewPrompt(core, plainDoc, 'dddddddd').includes('Never write orchestrator-owned notes'), false)
})

// ---- S7: reseat amendments line --------------------------------------------------------------

const PLANNER_OUT = {
  proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
  decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none',
}
const IMPL_OUT = {
  mainFilesChanged: ['src/x.js'], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}

function reseatDoc(mutate) {
  const list = stages.bugFixLike()
  if (mutate) mutate(list)
  const item = itemFixture({ short: 'aaaaaaaa', stages: list })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: item.short, status: 'done', reason: 'ok',
      stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })),
      outputs: { planner: PLANNER_OUT, implementer: IMPL_OUT, 'declarations-extractor': { declarations: 'none', harnessPointers: 'ptr-kept', gaps: 'none' } },
    }],
    refused: [], deferred: [],
  }
  return { doc, result }
}

const reseatOf = (doc, result, seat) =>
  execLib.reseat(loadWaveCore(), doc, result, 'aaaaaaaa', seat, seat.startsWith('test-author') ? { pre: 'c'.repeat(40), declarations: 'export function f(x)' } : { pre: 'c'.repeat(40) })

test('S7: with an amendments note owned by the planner stage the fix-cycle line names the ruling, not a section', () => {
  const { doc, result } = reseatDoc((list) => { list.find((s) => s.seat === 'planner').notes = ['amendments'] })
  const out = reseatOf(doc, result, 'implementer-a1')
  assert.equal(out.ok, true, JSON.stringify(out))
  assert.ok(out.prompt.includes("FIX CYCLE A1: work only ruling A1 in the item's amendments note and the blocking findings it cites."))
  assert.equal(out.prompt.includes('## Amendment A1'), false)
})

test('S7 control: without an amendments note the existing section line is unchanged', () => {
  const { doc, result } = reseatDoc()
  const out = reseatOf(doc, result, 'implementer-a1')
  assert.equal(out.ok, true, JSON.stringify(out))
  assert.ok(out.prompt.includes("FIX CYCLE A1: work only section \"## Amendment A1\" of the item's planning note and the blocking findings it cites."))
  assert.equal(out.prompt.includes('amendments note'), false)
})

test('S7 probe: the amendments key on any stage of the item switches the line, and the cycle number follows the seat', () => {
  const { doc, result } = reseatDoc((list) => { list.find((s) => s.seat === 'implementer').notes = ['implementation-notes', 'amendments'] })
  const out = reseatOf(doc, result, 'test-author-a2')
  assert.equal(out.ok, true, JSON.stringify(out))
  assert.ok(out.prompt.includes("FIX CYCLE A2: work only ruling A2 in the item's amendments note and the blocking findings it cites."))
  assert.equal(out.prompt.includes('## Amendment A2'), false)
})

// ---- S8 and probes ----------------------------------------------------------------------------

test('S8: a seat-aware review note owned by reviewer and absent from orchestratorNotes still forms a lane', () => {
  const extra = { key: 'review-extra', role: 'review', required: false, seat: 'reviewer' }
  const fx = fixture({ reviewNotes: [CHECKLIST, extra, CONFIRM()], seatAware: true })
  assert.equal(plannedItem(fx).orchestratorNotes.includes('review-extra'), false)
  const item = itemOf(okArgs(fx))
  const laneNotes = item.stages.flatMap((s) => s.notes || [])
  assert.ok(laneNotes.includes('review-extra'))
  assert.ok(laneNotes.includes('review-checklist'))
})

test('probe: a note owned by a differently cased seat is not dropped as orchestrator-owned (exact match)', () => {
  const fx = fixture({ reviewNotes: [CHECKLIST, CONFIRM({ key: 'case-probe', seat: 'Orchestrator' })], seatAware: true })
  const res = reviewArgs(fx)
  // Either the note is kept in a stage or review list, or the plan refuses the unknown seat; it is never silently dropped.
  if (res.status === 0) {
    const item = itemOf(JSON.parse(res.stdout))
    const all = item.stages.flatMap((s) => s.notes || []).concat(item.review.requiredReviewNotes || [], item.review.optionalReviewNotes || [])
    assert.ok(all.includes('case-probe'), 'case-probe present in a stage or review list')
  } else {
    assert.equal(res.status, 2, res.stderr)
  }
})

test('probe: a key that is both seat orchestrator and listed in orchestratorNotes is excluded once', () => {
  const fx = fixture({ reviewNotes: [CHECKLIST, CONFIRM()], seatAware: true })
  assert.equal(plannedItem(fx).orchestratorNotes.filter((k) => k === 'orchestrator-confirmation').length, 1)
  const item = itemOf(okArgs(fx))
  assert.equal(item.stages.flatMap((s) => s.notes || []).filter((k) => k === 'orchestrator-confirmation').length, 0)
})

test('probe: orchestratorNotes [] and absent give the same review-args output', () => {
  const fx = fixture({ reviewNotes: [CHECKLIST], seatAware: true })
  const doc = JSON.parse(readFileSync(fx.planPath, 'utf8'))
  const target = doc.args.items.find((i) => i.short === 'aaaaaaaa')
  target.orchestratorNotes = []
  writeFileSync(fx.planPath, JSON.stringify(doc))
  const withEmpty = reviewArgs(fx)
  delete target.orchestratorNotes
  writeFileSync(fx.planPath, JSON.stringify(doc))
  const absent = reviewArgs(fx)
  assert.equal(withEmpty.status, 0, withEmpty.stderr)
  assert.equal(absent.status, 0, absent.stderr)
  assert.equal(withEmpty.stdout, absent.stdout)
})

test('probe: review-args run twice over the same inputs is identical', () => {
  const fx = fixture({ reviewNotes: [CHECKLIST, CONFIRM()], seatAware: true })
  assert.equal(reviewArgs(fx).stdout, reviewArgs(fx).stdout)
})