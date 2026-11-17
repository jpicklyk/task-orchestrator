// Tests for item 82a250a0: the plugin scripts and workflows carry no hard-coded process defaults.
//
// BLIND test author: written from the frozen test-plan note and the supplied declarations. No
// implementation file was opened with any tool. The surfaces are reached through public entry
// points only: the run-planner CLI (review-args), run-planner-lib deriveStages, review-wave.js via
// loadCoreNamed, and implement-wave.js via loadCore. Harness shapes were read from existing tests
// (review-wave.test.mjs, run-planner-closure-notes.test.mjs, artifact-guard.test.mjs).
//
// Scenario ids S1-S14 and the probes match the item's test-plan note. Oracles: task-scope D1-D7
// [TS], domain-audit-plugin C2/C3/C10/C11/C13/C14 [AU], planning-sweeps rule 1 [PS]; never what
// the code returns. Every scenario is EXISTING-SURFACE.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { deriveStages } from '../run-planner-lib.mjs'
import { loadCore, planFixture, itemFixture, stages, fakeParallel } from './workflow-harness.mjs'
import { loadCoreNamed, autoAgent } from './workflow-harness-ext.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const CLI = join(PLUGIN, 'scripts', 'run-planner.mjs')
const REVIEW_WAVE = join(PLUGIN, 'workflows', 'review-wave.js')
const IMPLEMENT_WAVE = join(PLUGIN, 'workflows', 'implement-wave.js')

const RW = loadCoreNamed(REVIEW_WAVE, ['normalizeArgs', 'preflight', 'lanePrompt', 'runReview', 'REVIEW_OUTPUT_SCHEMAS'])

function cli(args, input) {
  return spawnSync(process.execPath, [CLI, ...args], {
    encoding: 'utf8',
    input: input === undefined ? undefined : JSON.stringify(input),
  })
}

// ---------------------------------------------------------------------------------------------
// AC1: review-args lanes derive from the note's own skill, with no default skill.
// ---------------------------------------------------------------------------------------------

const RA_SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const RA_A = 'aaaaaaaa-1111-4111-8111-111111111111'

function reviewFixture(reviewNotes) {
  const dir = mkdtempSync(join(tmpdir(), 'process-defaults-'))
  const snap = JSON.parse(readFileSync(join(PLUGIN, 'scripts', 'tests', 'fixtures', 'run-planner', 'independent-two.json'), 'utf8'))
  snap.schemas[RA_A].notes.push(...reviewNotes)
  const planRes = cli(['plan', '--now', '2026-09-28T12:00:00Z', '--scratchpad', join(dir, 'sp')], snap)
  assert.equal(planRes.status, 0, planRes.stderr)
  const planPath = join(dir, 'plan.json')
  writeFileSync(planPath, planRes.stdout)
  const bound = cli(['bind-run', '--in', planPath, '--server-run', RA_SERVER_RUN])
  assert.equal(bound.status, 0, bound.stderr)
  const boundPath = join(dir, 'bound.json')
  writeFileSync(boundPath, bound.stdout)
  const snapPath = join(dir, 'snapshot.json')
  writeFileSync(snapPath, JSON.stringify(snap))
  return { planPath: boundPath, snapPath }
}

function reviewArgsOf(reviewNotes) {
  const fx = reviewFixture(reviewNotes)
  const res = cli(['review-args', '--plan', fx.planPath, '--snapshot', fx.snapPath, '--items', 'aaaaaaaa'])
  assert.equal(res.status, 0, res.stderr)
  return JSON.parse(res.stdout)
}

const lanesOf = (args) => Object.fromEntries(args.items[0].stages.map((s) => [s.lane, s]))

test('S1: review notes group by their own skill; a skill-less note gets the bare seat lane with no skills field', () => {
  const args = reviewArgsOf([
    { key: 'note-a', role: 'review', required: true, seat: 'reviewer', skill: 'review-quality' },
    { key: 'note-b', role: 'review', required: true, seat: 'reviewer' },
  ])
  const lanes = lanesOf(args)
  assert.deepEqual(Object.keys(lanes).sort(), ['reviewer', 'reviewer.review-quality'])
  assert.deepEqual(lanes['reviewer.review-quality'].notes, ['note-a'])
  assert.deepEqual(lanes.reviewer.notes, ['note-b'])
  assert.equal(Object.hasOwn(lanes.reviewer, 'skills'), false, 'a skill-less lane carries no skills field')
  assert.deepEqual(lanes['reviewer.review-quality'].skills, ['review-quality'])
  const norm = RW.normalizeArgs(args)
  assert.equal(norm.ok, true, norm.reason)
  assert.deepEqual(RW.preflight(norm.plan).refused, [])
})

test('S2: only skill-less review notes across two seats yield bare seat lanes and no review-quality anywhere', () => {
  const args = reviewArgsOf([
    { key: 'note-a', role: 'review', required: true, seat: 'reviewer' },
    { key: 'note-s', role: 'review', required: true, seat: 'security' },
  ])
  const lanes = lanesOf(args)
  assert.deepEqual(Object.keys(lanes).sort(), ['reviewer', 'security'])
  assert.equal(JSON.stringify(args.items[0].stages).includes('review-quality'), false, 'stages carry no default skill')
  const norm = RW.normalizeArgs(args)
  assert.equal(norm.ok, true, norm.reason)
  for (const stage of norm.plan.items[0].stages) {
    assert.equal(RW.lanePrompt(norm.plan, norm.plan.items[0], stage).includes('review-quality'), false, `lanePrompt ${stage.lane}`)
  }
})

// ---------------------------------------------------------------------------------------------
// AC2: review-wave has no simplify lane; `after` orders lanes without halting.
// ---------------------------------------------------------------------------------------------

function reviewStage(lane, notes, extra = {}) {
  return {
    seat: 'reviewer', lane, phase: 'review', notes, writes: false,
    protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1', ...extra,
  }
}

function rawItem(short, laneStages) {
  const required = laneStages.flatMap((s) => s.notes)
  return {
    id: `item-${short}`, short, type: 'test-item', configFingerprint: 'fp-1', traits: [],
    worktree: `/tmp/wt-${short}`, stages: laneStages,
    review: {
      ownedFiles: { implementer: ['a.js'], testAuthor: ['a.test.js'], docs: [] },
      commits: { implementer: ['aaaaaaa1'], testAuthor: ['bbbbbbb2'], fixtureRepairs: [] },
      implementerActors: [`implementer:${short}:r-impl-0001`],
      testAuthorActors: [`test-author:${short}:r-impl-0001`],
      requiredReviewNotes: required,
    },
  }
}

function rawPlan(laneStages) {
  return {
    contract: 'review-wave/args-v1', runId: 'r-test-0001', rootId: 'root-test-0001', baseSha: 'e77c3e95',
    worktreeMode: 'shared', entryMode: 'pre-entered',
    capabilities: { features: ['seats', 'dispatchBySeat', 'rules'], phase0Hooks: true },
    items: [rawItem('aaaaaaaa', laneStages)], deferred: [],
  }
}

const twoLanes = (afterFirst) => [
  reviewStage('reviewer.review-quality', ['review-checklist'], afterFirst ? { after: afterFirst } : {}),
  reviewStage('reviewer.security-review', ['security-assessment'], { skills: ['security-review'] }),
]

test("S3: a stage with output 'simplify-v1' is refused by normalizeArgs as a bad stage output", () => {
  const raw = rawPlan([
    { seat: 'simplify', lane: 'simplify', phase: 'review', notes: [], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'simplify-v1' },
    reviewStage('reviewer.review-quality', ['review-checklist']),
  ])
  const res = RW.normalizeArgs(raw)
  assert.equal(res.ok, false)
  assert.equal(res.reason, 'invalid args: bad stage output')
})

test("S4: a stage whose 'after' names no sibling lane is refused by preflight; an empty 'after' is not", () => {
  const refusedPlan = RW.normalizeArgs(rawPlan(twoLanes(['x'])))
  assert.equal(refusedPlan.ok, true, refusedPlan.reason)
  assert.deepEqual(RW.preflight(refusedPlan.plan).refused, [
    { id: 'item-aaaaaaaa', reason: 'lane reviewer.review-quality after x names no sibling lane' },
  ])
  const okPlan = RW.normalizeArgs(rawPlan(twoLanes([])))
  assert.equal(okPlan.ok, true, okPlan.reason)
  assert.deepEqual(RW.preflight(okPlan.plan).refused, [])
})

function envelope(notesFilled, { verdict = 'pass', findings = [] } = {}) {
  return {
    status: 'done', reason: 'ok', notes: notesFilled.map((key) => ({ key })),
    commits: { pre: 'p', post: 'q' }, files: [], modelReported: 'fake-model',
    entry: { alreadyInPhase: true, previousRole: 'review' },
    output: { verdict, findings, independence: { mode: 'independent', violations: [], evidence: 'checked' }, notesFilled },
  }
}

test('S5: runReview dispatches both lanes when a lane has after naming a sibling lane; the named lane goes first and nothing is refused or skipped', async () => {
  // Oracle: task-scope D5 (after orders and never halts). A mutant that starts every lane at once
  // fails the ordering assertion; a mutant that refuses or skips fails the call-count assertion.
  const raw = rawPlan([
    reviewStage('reviewer.review-quality', ['review-checklist']),
    reviewStage('reviewer.security-review', ['security-assessment'], { skills: ['security-review'], after: ['reviewer.review-quality'] }),
  ])
  const norm = RW.normalizeArgs(raw)
  assert.equal(norm.ok, true, norm.reason)
  const { agent, calls } = autoAgent(async (label) => envelope(label.startsWith('reviewer.review-quality:') ? ['review-checklist'] : ['security-assessment']))
  const result = await RW.runReview(norm.plan, { agent, parallel: fakeParallel, log: () => {} })
  assert.equal(calls.length, 2, `labels: ${calls.map((c) => c.label).join(',')}`)
  assert.deepEqual(result.refused, [])
  assert.equal(result.items.length, 1)
  const first = calls.find((c) => c.label === 'reviewer.review-quality:aaaaaaaa')
  const second = calls.find((c) => c.label === 'reviewer.security-review:aaaaaaaa')
  assert.ok(first && second, `labels: ${calls.map((c) => c.label).join(',')}`)
  assert.ok(first.t1 < second.t0, 'the named lane settles before the dependent lane starts')
  assert.equal(JSON.stringify(result).includes('skipped'), false, 'no lane is skipped')
})

test("S6: REVIEW_OUTPUT_SCHEMAS holds 'review-v1' and 'findings-v1' and SIMPLIFY_ANGLES is gone from the core", () => {
  assert.deepEqual(Object.keys(RW.REVIEW_OUTPUT_SCHEMAS), ['review-v1', 'findings-v1'])
  assert.throws(() => loadCoreNamed(REVIEW_WAVE, ['SIMPLIFY_ANGLES']), ReferenceError)
})

// ---------------------------------------------------------------------------------------------
// AC3: deriveStages keys the test-author seat by name and the input notes by the note's skill.
// ---------------------------------------------------------------------------------------------

const CANDIDATE = {
  id: '82000000-0000-4000-8000-000000000001', short: '82000000', title: 'Item 82000000', priority: 'high',
  complexity: 2, type: 'scratch-x', role: 'queue', parentId: null, traits: [], hasChildren: false, source: 'ready',
}
const EXTRACTOR_SERVED = [{ key: 'declarations-extractor', rulesVersion: '1' }]

function seatSchema(notes, seats) {
  return { status: 'ok', type: 'scratch-x', configFingerprint: 'fp-pd', configSource: 'per-root', notes, seats }
}
const SEATS = (name, extra = {}) => [
  { name: 'planner', phase: 'queue' },
  { name: 'implementer', phase: 'work', enters: true },
  { name, phase: 'work', after: ['implementer'], ...extra },
]
const BASE_NOTES = [
  { key: 'task-scope', role: 'queue', required: true, seat: 'planner' },
  { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
]
const derive = (schema, served = EXTRACTOR_SERVED) =>
  deriveStages(CANDIDATE, schema, { rulesServed: served, noteOwners: [] })
const stageOf = (result, seat) => result.stages.find((s) => s.seat === seat)

test("S7: the seat named test-author owning a skill-less test-manifest is a test-author-v1 stage with the extractor directly before it", () => {
  const result = derive(seatSchema(
    [...BASE_NOTES, { key: 'test-manifest', role: 'work', required: true, seat: 'test-author' }],
    SEATS('test-author', { readsExclude: ['implementation-notes'] }),
  ))
  const idx = result.stages.findIndex((s) => s.seat === 'test-author')
  assert.ok(idx > 0, 'test-author stage exists')
  assert.equal(result.stages[idx].output, 'test-author-v1')
  assert.equal(result.stages[idx - 1].seat, 'declarations-extractor')
  assert.equal(result.stages[idx - 1].inserted, true)
})

test("S8: another seat owning a note with skill 'test-author' is generic-v1 with no extractor", () => {
  const result = derive(seatSchema(
    [...BASE_NOTES, { key: 'review-note', role: 'work', required: true, seat: 'reviewer2', skill: 'test-author' }],
    SEATS('reviewer2', { readsExclude: ['implementation-notes'] }),
  ))
  assert.equal(stageOf(result, 'reviewer2').output, 'generic-v1')
  assert.equal(result.stages.some((s) => s.seat === 'declarations-extractor'), false)
})

const QUEUE_NOTES = [
  { key: 'task-scope', role: 'queue', required: true, seat: 'planner' },
  { key: 'p1', role: 'queue', required: true, seat: 'planner', skill: 'tm' },
  { key: 'p2', role: 'queue', required: true, seat: 'planner', skill: 'test-author' },
  { key: 'p3', role: 'queue', required: true, seat: 'planner' },
]

test("S9: inputNotes are the queue notes sharing the test-manifest's skill; a skill-less test-manifest has no inputNotes field", () => {
  const withSkill = derive(seatSchema(
    [...QUEUE_NOTES, { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
      { key: 'test-manifest', role: 'work', required: true, seat: 'test-author', skill: 'tm' }],
    SEATS('test-author', { readsExclude: ['implementation-notes'] }),
  ))
  assert.deepEqual(stageOf(withSkill, 'test-author').inputNotes, ['p1'])
  // Fixture condition: p2 carries skill 'test-author', so a hard-coded skill match would list it here.
  const skillless = derive(seatSchema(
    [...QUEUE_NOTES, { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
      { key: 'test-manifest', role: 'work', required: true, seat: 'test-author' }],
    SEATS('test-author', { readsExclude: ['implementation-notes'] }),
  ))
  assert.equal(Object.hasOwn(stageOf(skillless, 'test-author'), 'inputNotes'), false)
})

// ---------------------------------------------------------------------------------------------
// AC4: closure-notes reaches a stage only through a served rule named by a note's skill.
// ---------------------------------------------------------------------------------------------

const CLOSURE = 'closure-notes'
const CLOSURE_SERVED = [...EXTRACTOR_SERVED, { key: CLOSURE, rulesVersion: '1' }]
const countOf = (list, key) => (list ?? []).filter((k) => k === key).length
const implementerRules = (workNotes) =>
  stageOf(derive(seatSchema([{ key: 'task-scope', role: 'queue', required: true, seat: 'planner' }, ...workNotes],
    [{ name: 'planner', phase: 'queue' }, { name: 'implementer', phase: 'work', enters: true }]), CLOSURE_SERVED), 'implementer').rules

test('S10: implementation-notes with skill closure-notes yields the rule once; the same note without a skill yields none', () => {
  assert.equal(countOf(implementerRules([{ key: 'implementation-notes', role: 'work', required: true, seat: 'implementer', skill: CLOSURE }]), CLOSURE), 1)
  assert.equal(countOf(implementerRules([{ key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' }]), CLOSURE), 0)
})

test("S11: a skill-less session-tracking yields no closure-notes; any note named 'wrap-up' with that skill does", () => {
  assert.equal(countOf(implementerRules([{ key: 'session-tracking', role: 'work', required: true, seat: 'implementer' }]), CLOSURE), 0)
  assert.equal(countOf(implementerRules([{ key: 'wrap-up', role: 'work', required: true, seat: 'implementer', skill: CLOSURE }]), CLOSURE), 1)
})

// ---------------------------------------------------------------------------------------------
// AC5: seat prompts (implement-wave.js seatPrompt).
// ---------------------------------------------------------------------------------------------

function promptOf({ seat, outs = {}, project, make = stages.bugFixLike }) {
  const worktree = '/tmp/wt-aaaaaaaa'
  const item = itemFixture({ short: 'aaaaaaaa', stages: make(), worktree })
  const plan = planFixture({ items: [item], repoRoot: worktree, ...(project === undefined ? {} : { project }) })
  const stage = item.stages.find((s) => s.seat === seat)
  assert.ok(stage, `fixture has a ${seat} stage`)
  return loadCore(IMPLEMENT_WAVE).seatPrompt(plan, item, stage, outs)
}

const STOCK_SCOPE = 'claude-plugins/, .claude/ and current/docs/'

test("S12: the docs sweep line names the project's own searchScope, not the stock directories", () => {
  const prompt = promptOf({ seat: 'planner', project: { searchScope: ['/w/a', '/w/b'] } })
  assert.ok(prompt.includes('across /w/a, /w/b;'), 'scope rendered')
  assert.equal(prompt.includes(STOCK_SCOPE), false, 'stock directories absent')
})

test("S13: an absent or empty searchScope sweeps the whole repository", () => {
  for (const project of [undefined, {}, { searchScope: [] }]) {
    const prompt = promptOf({ seat: 'planner', project })
    assert.ok(prompt.includes('across the whole repository;'), JSON.stringify(project))
    assert.equal(prompt.includes(STOCK_SCOPE), false, JSON.stringify(project))
  }
})

test("S14: the smoke-root line is on every implementer-v1 prompt, never on planner-v1 or test-author-v1", () => {
  const SMOKE = /throwaway run root/
  assert.match(promptOf({ seat: 'implementer', outs: { planner: { mainFiles: ['current/src/Main.kt'] } } }), SMOKE)
  assert.match(promptOf({ seat: 'implementer' }), SMOKE)
  assert.doesNotMatch(promptOf({ seat: 'planner', outs: { planner: { mainFiles: ['current/src/Main.kt'] } } }), SMOKE)
  assert.doesNotMatch(promptOf({ seat: 'test-author', outs: { planner: { mainFiles: ['claude-plugins/a.mjs'] } } }), SMOKE)
})

// ---------------------------------------------------------------------------------------------
// Probes.
// ---------------------------------------------------------------------------------------------

test("probe: seat 'Test-Author' (case differs) is not a test-author-v1 stage and gets no extractor", () => {
  const result = derive(seatSchema(
    [...BASE_NOTES, { key: 'test-manifest', role: 'work', required: true, seat: 'Test-Author' }],
    SEATS('Test-Author', { readsExclude: ['implementation-notes'] }),
  ))
  assert.notEqual(stageOf(result, 'Test-Author').output, 'test-author-v1')
  assert.equal(result.stages.some((s) => s.seat === 'declarations-extractor'), false)
})

test("probe: an empty-string skill is no skill (closure-notes and review lanes)", () => {
  assert.equal(countOf(implementerRules([{ key: 'implementation-notes', role: 'work', required: true, seat: 'implementer', skill: '' }]), CLOSURE), 0)
  const args = reviewArgsOf([{ key: 'note-e', role: 'review', required: true, seat: 'reviewer', skill: '' }])
  assert.deepEqual(Object.keys(lanesOf(args)), ['reviewer'])
})

test('probe: two skill-less review notes of one seat share one lane', () => {
  const args = reviewArgsOf([
    { key: 'note-x', role: 'review', required: true, seat: 'reviewer' },
    { key: 'note-y', role: 'review', required: false, seat: 'reviewer' },
  ])
  const lanes = lanesOf(args)
  assert.deepEqual(Object.keys(lanes), ['reviewer'])
  assert.deepEqual([...lanes.reviewer.notes].sort(), ['note-x', 'note-y'])
})

test("probe: a worktree-style searchScope entry is rendered verbatim", () => {
  const prompt = promptOf({ seat: 'planner', project: { searchScope: ['<worktree>/x'] } })
  assert.ok(prompt.includes('across <worktree>/x;'))
})

test('probe: a note whose skill is review-quality still lands in reviewer.review-quality', () => {
  const args = reviewArgsOf([{ key: 'note-q', role: 'review', required: true, seat: 'reviewer', skill: 'review-quality' }])
  const lanes = lanesOf(args)
  assert.deepEqual(Object.keys(lanes), ['reviewer.review-quality'])
  assert.deepEqual(lanes['reviewer.review-quality'].notes, ['note-q'])
})
