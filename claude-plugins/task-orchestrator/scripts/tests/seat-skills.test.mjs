// Item 679fcaeb (F3-B): seat skill resolver, defaults table, planner/exec/workflow wiring. Scenarios S1-S17 of the frozen
// test-plan note (blind test-author seat; no production .mjs or .js file was opened, only exported surfaces).
//
// Oracles: task-scope AC1-AC5 and D1-D5 (resolution: a declared list is used as is, [] means none, absent falls back to
// the defaults for that seat; seat name from callerSeat, then agent type, then phase; stage seatSkills set only when
// non-empty and only when features lists seatSkills; reviewSeatSkills over review lane seats, orchestrator excluded;
// the SEAT SKILLS prompt line, literal text), plan Decision 4 (P4) and carry-in-f3a-server-contract (CI, AC5).
// Fixture condition for each absence assertion: a twin fixture in the same test carries the member.
// Forbidden-construct declaration: none.

import '../../hooks/tests/hermetic-claude-config.mjs'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  SEAT_SKILLS_FEATURE,
  loadSeatSkillDefaults,
  seatSkillsFor,
  seatNameFor,
  resolveSeatSkills,
} from '../../hooks/lib/seat-skills.mjs'
import { deriveStages, buildPlanDoc } from '../run-planner-lib.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { loadCore, planFixture, itemFixture, stages as stageFixtures } from './workflow-harness.mjs'
import { loadCoreNamed } from './workflow-harness-ext.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const IMPLEMENT_PATH = join(PLUGIN, 'workflows', 'implement-wave.js')
const REVIEW_PATH = join(PLUGIN, 'workflows', 'review-wave.js')
const CLI = join(PLUGIN, 'scripts', 'run-planner.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const DEFAULTS_JSON = join(PLUGIN, 'hooks', 'lib', 'seat-skill-defaults.json')
const NOW = '2026-09-28T12:00:00Z'
const ITEM = 'aaaaaaaa-1111-4111-8111-111111111111'
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'

const RW = loadCoreNamed(REVIEW_PATH, ['normalizeArgs', 'preflight', 'lanePrompt'])

const LINE = (keys) =>
  `SEAT SKILLS: invoke each skill below with the Skill tool, in this order, before your seat's first file edit or note write: ${keys}.`

// ---- S1-S5: seatSkillsFor ------------------------------------------------------------------------

test('S1: a declared list is used as is and the defaults are not merged', () => {
  assert.deepEqual(seatSkillsFor(['p:a', 'b'], 'implementer', { defaults: { implementer: ['d'] } }), ['p:a', 'b'])
})

test('S2: a declared empty list means none, not the defaults', () => {
  assert.deepEqual(seatSkillsFor([], 'implementer', { defaults: { implementer: ['d'] } }), [])
  // Twin: absent falls back, so the defaults are reachable in the same fixture.
  assert.deepEqual(seatSkillsFor(undefined, 'implementer', { defaults: { implementer: ['d'] } }), ['d'])
})

test('S3: absent or null declared falls back to the seat defaults; seat names match exactly', () => {
  const defaults = { implementer: ['d'] }
  assert.deepEqual(seatSkillsFor(undefined, 'implementer', { defaults }), ['d'])
  assert.deepEqual(seatSkillsFor(null, 'implementer', { defaults }), ['d'])
  assert.deepEqual(seatSkillsFor(undefined, 'planner', { defaults }), [])
  assert.deepEqual(seatSkillsFor(undefined, 'Implementer', { defaults }), [], 'no case folding')
})

test('S4: supported false ignores the declared list and uses the defaults only', () => {
  assert.deepEqual(seatSkillsFor(['x'], 'implementer', { supported: false, defaults: { implementer: ['d'] } }), ['d'])
  assert.deepEqual(seatSkillsFor(['x'], 'implementer', { supported: true, defaults: { implementer: ['d'] } }), ['x'])
  assert.deepEqual(seatSkillsFor(['x'], 'implementer', { supported: false, defaults: {} }), [])
})

test('S5: blank, duplicate and non-string entries are dropped, names verbatim; a non-array declared is absent', () => {
  assert.deepEqual(seatSkillsFor(['a', '', 'a', 3, ' b'], 'implementer', { defaults: {} }), ['a', ' b'])
  assert.deepEqual(seatSkillsFor('a', 'implementer', { defaults: { implementer: ['d'] } }), ['d'])
  // The same filtering applies to the defaults list.
  assert.deepEqual(seatSkillsFor(undefined, 'implementer', { defaults: { implementer: ['d', '', 'd', 7, 'e'] } }), ['d', 'e'])
})

test('SEAT_SKILLS_FEATURE names the server feature', () => {
  assert.equal(SEAT_SKILLS_FEATURE, 'seatSkills')
})

// ---- S6: seatNameFor -----------------------------------------------------------------------------

test('S6: callerSeat beats the agent type and the phase', () => {
  assert.equal(seatNameFor({ callerSeat: 'reviewer', agentType: 'task-orchestrator:implementer', phase: 'work' }), 'reviewer')
  assert.equal(seatNameFor({ agentType: 'task-orchestrator:implementer', phase: 'work' }), 'implementer')
  assert.equal(seatNameFor({ agentType: 'task-orchestrator:implementer', phase: 'review' }), 'implementer', 'agent type beats phase')
})

test('S6: the agent type last segment names a known seat only when it is exactly that seat', () => {
  assert.equal(seatNameFor({ agentType: 'x:test-author' }), 'test-author')
  assert.equal(seatNameFor({ agentType: 'planner' }), 'planner')
  assert.equal(seatNameFor({ agentType: 'x:implementer-helper', phase: 'review' }), 'reviewer')
  assert.equal(seatNameFor({ agentType: 'workflow-subagent', phase: 'queue' }), 'planner')
})

test('S6: the phase maps queue/work/review; anything else is null', () => {
  assert.equal(seatNameFor({ phase: 'queue' }), 'planner')
  assert.equal(seatNameFor({ phase: 'work' }), 'implementer')
  assert.equal(seatNameFor({ phase: 'review' }), 'reviewer')
  assert.equal(seatNameFor({ phase: 'terminal' }), null)
  assert.equal(seatNameFor({}), null)
  assert.equal(seatNameFor(), null)
})

test('S6: a blank callerSeat is skipped', () => {
  assert.equal(seatNameFor({ callerSeat: '', agentType: 'x:planner', phase: 'work' }), 'planner')
  assert.equal(seatNameFor({ callerSeat: '   ', phase: 'review' }), 'reviewer')
})

// ---- S7: resolveSeatSkills -----------------------------------------------------------------------

test('S7: a seat-less schema takes the seat from callerSeat and the defaults', () => {
  assert.deepEqual(
    resolveSeatSkills({ seats: [], callerSeat: 'reviewer', defaults: { reviewer: ['r'] } }),
    { seat: 'reviewer', skills: ['r'], source: 'default' }
  )
})

test('S7: a declared seat entry wins over the defaults when the capability is supported', () => {
  const seats = [{ name: 'reviewer', phase: 'review', skills: ['s'] }]
  assert.deepEqual(
    resolveSeatSkills({ seats, supported: true, phase: 'review', defaults: { reviewer: ['r'] } }),
    { seat: 'reviewer', skills: ['s'], source: 'declared' }
  )
  const unsupported = resolveSeatSkills({ seats, supported: false, phase: 'review', defaults: { reviewer: ['r'] } })
  assert.deepEqual(unsupported.skills, ['r'], 'unsupported ignores the declaration')
  assert.equal(unsupported.source, 'default')
})

test('S7: no seat resolvable gives seat null, no skills, source none', () => {
  assert.deepEqual(resolveSeatSkills({ seats: [], defaults: { reviewer: ['r'] } }), { seat: null, skills: [], source: 'none' })
  assert.deepEqual(resolveSeatSkills({}), { seat: null, skills: [], source: 'none' })
  assert.deepEqual(resolveSeatSkills(), { seat: null, skills: [], source: 'none' })
})

test('S7: among same-named seat entries the one whose phase matches wins, else the first', () => {
  const seats = [
    { name: 'reviewer', phase: 'queue', skills: ['q'] },
    { name: 'reviewer', phase: 'review', skills: ['s'] },
  ]
  assert.deepEqual(resolveSeatSkills({ seats, callerSeat: 'reviewer', phase: 'review', defaults: {} }).skills, ['s'])
  assert.deepEqual(resolveSeatSkills({ seats, callerSeat: 'reviewer', phase: 'work', defaults: {} }).skills, ['q'])
})

test('S7: a declared empty list on the seat entry means none even with defaults', () => {
  const seats = [{ name: 'implementer', phase: 'work', skills: [] }]
  assert.deepEqual(resolveSeatSkills({ seats, supported: true, phase: 'work', defaults: { implementer: ['d'] } }).skills, [])
})

// ---- S8: the defaults table ----------------------------------------------------------------------

const SHIPPED_DEFAULTS = {
  planner: ['task-orchestrator:planning-standards'],
  implementer: ['task-orchestrator:implementation-standards'],
  reviewer: ['task-orchestrator:review-method'],
  'test-author': ['task-orchestrator:test-authoring'],
}

test('S8: the shipped defaults table names one engineering skill per seat and the loader returns it', () => {
  assert.deepEqual(JSON.parse(readFileSync(DEFAULTS_JSON, 'utf8')), SHIPPED_DEFAULTS)
  assert.deepEqual(loadSeatSkillDefaults(), SHIPPED_DEFAULTS)
})

test('S8 probe: two loadSeatSkillDefaults calls are equal (replay)', () => {
  assert.deepEqual(loadSeatSkillDefaults(), loadSeatSkillDefaults())
})

// ---- planner fixtures ------------------------------------------------------------------------------

function candidate(extra = {}) {
  return {
    id: 'b0000000-0000-4000-8000-000000000001',
    short: 'b0000000',
    title: 'Item b0000000',
    priority: 'high',
    complexity: 2,
    type: 'scratch-seat',
    role: 'queue',
    parentId: null,
    traits: [],
    hasChildren: false,
    source: 'ready',
    ...extra,
  }
}

function schema({ seats, notes } = {}) {
  return {
    status: 'ok',
    type: 'scratch-seat',
    configFingerprint: 'fp-seat-skills',
    configSource: 'per-root',
    notes: notes ?? [
      { key: 'diagnosis', role: 'queue', required: true, seat: 'planner' },
      { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
    ],
    seats: seats ?? [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  }
}

const FEATURES = ['seats', 'dispatchBySeat', 'independent_of', 'rules', 'seatSkills']
const derive = (entry, ctx = {}, cand = candidate()) => deriveStages(cand, entry, { rulesServed: [], noteOwners: [], ...ctx })
const stageOf = (result, seat) => result.stages.find((s) => s.seat === seat)
const hasSkills = (stage) => Object.hasOwn(stage, 'seatSkills')

// ---- S9: deriveStages with the feature --------------------------------------------------------------

test('S9: a declared list reaches the stage, an undeclared seat gets its default', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue', skills: ['p:a'] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  const result = derive(entry, { features: FEATURES, seatSkillDefaults: { implementer: ['d'] } })
  assert.deepEqual(stageOf(result, 'planner').seatSkills, ['p:a'])
  assert.deepEqual(stageOf(result, 'implementer').seatSkills, ['d'])
})

test('S9: a seat-less schema gives the implementer its default and the planner none', () => {
  const entry = {
    status: 'ok',
    type: 'scratch-seat',
    configFingerprint: 'fp-seatless',
    configSource: 'per-root',
    notes: [
      { key: 'specification', role: 'queue', required: true, seat: null },
      { key: 'session-tracking', role: 'work', required: true, seat: null },
    ],
  }
  const result = derive(entry, { features: FEATURES, seatSkillDefaults: { implementer: ['d'] } })
  assert.deepEqual(stageOf(result, 'implementer').seatSkills, ['d'])
  assert.equal(hasSkills(stageOf(result, 'planner')), false, 'the planner has no default (twin: the implementer has)')
})

test('S9: a schema-free item (not-found) gives the implementer stage its default', () => {
  const result = derive({ status: 'not-found' }, { features: FEATURES, seatSkillDefaults: { implementer: ['d'] } })
  assert.deepEqual(stageOf(result, 'implementer').seatSkills, ['d'])
})

test('S9: the inserted declarations-extractor stage carries no seatSkills', () => {
  const entry = schema({
    notes: [
      { key: 'test-plan', role: 'queue', required: true, seat: 'planner', skill: 'test-author' },
      { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
      { key: 'test-manifest', role: 'work', required: true, seat: 'test-author', skill: 'test-author' },
    ],
    seats: [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true },
      { name: 'orchestrator', phase: 'work' },
      { name: 'test-author', phase: 'work', after: ['implementer'], readsExclude: ['implementation-notes', 'session-tracking'] },
    ],
  })
  const result = derive(entry, { features: FEATURES, seatSkillDefaults: { implementer: ['d'], 'declarations-extractor': ['d'] } }, candidate({ traits: ['needs-test-author'] }))
  const extractor = stageOf(result, 'declarations-extractor')
  assert.ok(extractor, `fixture: an extractor stage is inserted; stages: ${result.stages.map((s) => s.seat)}`)
  assert.equal(hasSkills(extractor), false)
  assert.deepEqual(stageOf(result, 'implementer').seatSkills, ['d'], 'twin: another stage of the same plan carries skills')
})

test('S9 probe: a declared empty list is no key even when the seat has a default', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue', skills: [] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  const result = derive(entry, { features: FEATURES, seatSkillDefaults: { planner: ['d'], implementer: ['d'] } })
  assert.equal(hasSkills(stageOf(result, 'planner')), false, 'an empty list is never emitted')
  assert.deepEqual(stageOf(result, 'implementer').seatSkills, ['d'])
})

// ---- S10, S11: without the capability ---------------------------------------------------------------

test('S10: features without seatSkills ignore a declared list; defaults still apply', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue', skills: ['p:a'] },
      { name: 'implementer', phase: 'work', enters: true, skills: ['i:a'] },
    ],
  })
  const none = derive(entry, { features: ['seats', 'rules'], seatSkillDefaults: {} })
  for (const s of none.stages) assert.equal(hasSkills(s), false, s.seat)
  const withDefault = derive(entry, { features: ['seats', 'rules'], seatSkillDefaults: { implementer: ['d'] } })
  assert.deepEqual(stageOf(withDefault, 'implementer').seatSkills, ['d'], 'defaults only, declared ignored')
  assert.equal(hasSkills(stageOf(withDefault, 'planner')), false)
})

test('S10 probe: features [] behaves like features absent', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue', skills: ['p:a'] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  const empty = derive(entry, { features: [], seatSkillDefaults: {} })
  const absent = derive(entry, { seatSkillDefaults: {} })
  for (const r of [empty, absent]) for (const s of r.stages) assert.equal(hasSkills(s), false, s.seat)
  // Twin: with the feature the declared planner list is present.
  assert.deepEqual(stageOf(derive(entry, { features: FEATURES, seatSkillDefaults: {} }), 'planner').seatSkills, ['p:a'])
})

test('S11: a ctx without features and with an empty defaults table adds no seatSkills and reviewSeatSkills is null', () => {
  const entry = schema({
    notes: [
      { key: 'diagnosis', role: 'queue', required: true, seat: 'planner' },
      { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
      { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer' },
    ],
    seats: [
      { name: 'planner', phase: 'queue', skills: ['p:a'] },
      { name: 'implementer', phase: 'work', enters: true, skills: ['i:a'] },
      { name: 'reviewer', phase: 'review', skills: ['rv'] },
    ],
  })
  const result = derive(entry, { seatSkillDefaults: {} })
  for (const s of result.stages) assert.equal(hasSkills(s), false, s.seat)
  assert.equal(result.reviewSeatSkills ?? null, null)
  // Twin: the same schema with the feature carries them.
  const twin = derive(entry, { features: FEATURES, seatSkillDefaults: {} })
  assert.deepEqual(stageOf(twin, 'planner').seatSkills, ['p:a'])
  assert.deepEqual(twin.reviewSeatSkills, { reviewer: ['rv'] })
})

// ---- S12: review seat skills --------------------------------------------------------------------------

test('S12: deriveStages reviewSeatSkills lists the review lane seats; the orchestrator is excluded', () => {
  const entry = schema({
    notes: [
      { key: 'diagnosis', role: 'queue', required: true, seat: 'planner' },
      { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
      { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer' },
    ],
    seats: [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true },
      { name: 'orchestrator', phase: 'work', after: ['implementer'], skills: ['o'] },
      { name: 'reviewer', phase: 'review', skills: ['rv'] },
    ],
  })
  const result = derive(entry, { features: FEATURES, seatSkillDefaults: {} })
  assert.deepEqual(result.reviewSeatSkills, { reviewer: ['rv'] })
})

function makeSnap(reviewSeats, { features = FEATURES, reviewNotes } = {}) {
  const snap = JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))
  snap.rulesServed = [{ key: 'commit-discipline', rulesVersion: '1' }]
  if (features === null) delete snap.features
  else snap.features = features
  const entry = snap.schemas[ITEM]
  entry.notes = [
    { key: 'specification', role: 'queue', required: true, seat: 'planner' },
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
    { key: 'session-tracking', role: 'work', required: true, seat: 'orchestrator' },
    ...(reviewNotes ?? [{ key: 'review-checklist', role: 'review', required: true, seat: 'reviewer', skill: 'plain-a' }]),
  ]
  entry.seats = [
    { name: 'planner', phase: 'queue' },
    { name: 'implementer', phase: 'work', enters: true },
    { name: 'orchestrator', phase: 'work' },
    ...reviewSeats,
  ]
  entry.dispatchBySeat = { review: { reviewer: { agent: 'task-orchestrator:reviewer', model: 'opus', effort: 'high' } } }
  return snap
}

function argsFor(snap) {
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
  return { doc, item, args: execLib.reviewWaveArgs(loadWaveCore(), doc, snap, result, [item.short]) }
}

const laneStages = (args, seat) => args.items[0].stages.filter((s) => String(s.lane).startsWith(`${seat}.`))

test('S12: the plan item carries reviewSeatSkills and the reviewer lane stage carries seatSkills', () => {
  const { item, args } = argsFor(makeSnap([{ name: 'reviewer', phase: 'review', skills: ['rv'] }]))
  assert.deepEqual(item.reviewSeatSkills, { reviewer: ['rv'] })
  const lanes = laneStages(args, 'reviewer')
  assert.ok(lanes.length >= 1, `fixture: a reviewer lane exists; lanes: ${args.items[0].stages.map((s) => s.lane)}`)
  for (const l of lanes) assert.deepEqual(l.seatSkills, ['rv'], l.lane)
})

test('S12: a lane of another review seat that declares nothing carries no seatSkills', () => {
  const reviewNotes = [
    { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer', skill: 'plain-a' },
    { key: 'audit-note', role: 'review', required: true, seat: 'auditor', skill: 'plain-b' },
  ]
  const snap = makeSnap([{ name: 'reviewer', phase: 'review', skills: ['rv'] }, { name: 'auditor', phase: 'review' }], { reviewNotes })
  const { item, args } = argsFor(snap)
  assert.deepEqual(item.reviewSeatSkills, { reviewer: ['rv'] })
  assert.ok(laneStages(args, 'reviewer').length >= 1)
  const other = laneStages(args, 'auditor')
  assert.ok(other.length >= 1, `fixture: an auditor lane exists; lanes: ${args.items[0].stages.map((s) => s.lane)}`)
  for (const l of other) assert.equal(hasSkills(l), false, l.lane)
})

// ---- S11 (plan level), S10 (plan level) ---------------------------------------------------------------

test("S11: a snapshot without features gives each stage only its seat's shipped default and ignores the declared list", () => {
  const snap = makeSnap([{ name: 'reviewer', phase: 'review', skills: ['rv'] }], { features: null })
  const built = buildPlanDoc(snap, { now: NOW })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  const item = built.doc.args.items.find((i) => i.id === ITEM)
  assert.ok(item, 'the fixture item is in the plan')
  assert.deepEqual(item.reviewSeatSkills, { reviewer: ['task-orchestrator:review-method'] }, 'the declared rv is ignored')
  for (const s of item.stages) {
    if (s.seat === 'orchestrator' || !Object.hasOwn(SHIPPED_DEFAULTS, s.seat)) assert.equal(hasSkills(s), false, s.seat)
    else assert.deepEqual(s.seatSkills, SHIPPED_DEFAULTS[s.seat], s.seat)
  }
  // Twin: the same seats with the feature listed carry the member.
  const twin = buildPlanDoc(makeSnap([{ name: 'reviewer', phase: 'review', skills: ['rv'] }]), { now: NOW })
  assert.deepEqual(twin.doc.args.items.find((i) => i.id === ITEM).reviewSeatSkills, { reviewer: ['rv'] })
})

test('S10: a snapshot whose features lack seatSkills ignores the declared list and keeps the shipped default', () => {
  const { item } = argsFor(makeSnap([{ name: 'reviewer', phase: 'review', skills: ['rv'] }], { features: ['seats', 'dispatchBySeat', 'independent_of', 'rules'] }))
  assert.deepEqual(item.reviewSeatSkills, { reviewer: ['task-orchestrator:review-method'] })
})

// ---- S13-S15: prompt lines ----------------------------------------------------------------------------

const lineIndex = (lines, pred) => lines.findIndex(pred)

function implementPrompt(extra) {
  const base = stageFixtures.featureTaskLike().find((s) => s.seat === 'implementer')
  const stage = { ...base, rules: ['x-rule'], ...extra }
  const item = itemFixture({ short: 'b0000000', stages: [stage] })
  const plan = planFixture({ items: [item], rootId: 'root-b0', baseSha: 'e77c3e95' })
  return loadCore(IMPLEMENT_PATH).seatPrompt(plan, item, stage, {})
}

test('S13: implement-wave seat prompt names the seat skills in order, after Keys and before the not_found line', () => {
  const lines = implementPrompt({ seatSkills: ['p:a', 'b'] }).split('\n')
  const at = lines.map((l) => l.trim()).indexOf(LINE('p:a, b'))
  assert.ok(at !== -1, lines.join('\n'))
  const keys = lineIndex(lines, (l) => /^[ \t]*Keys:/.test(l))
  const notFound = lineIndex(lines, (l) => l.includes('A not_found result'))
  assert.ok(keys !== -1 && notFound !== -1, 'fixture: Keys and not_found lines exist')
  assert.ok(keys < at && at < notFound, `keys ${keys}, line ${at}, not_found ${notFound}`)
  assert.equal(lines.filter((l) => l.includes('SEAT SKILLS')).length, 1)
})

function laneArgs(skills) {
  const seat = skills === undefined ? { name: 'reviewer', phase: 'review' } : { name: 'reviewer', phase: 'review', skills }
  return argsFor(makeSnap([seat])).args
}

function lanePromptOf(args) {
  const norm = RW.normalizeArgs(args)
  assert.equal(norm.ok, true, norm.reason)
  assert.deepEqual(RW.preflight(norm.plan).refused, [])
  const item = norm.plan.items[0]
  const stage = item.stages.find((s) => String(s.lane).startsWith('reviewer.'))
  assert.ok(stage, 'a reviewer lane exists')
  return RW.lanePrompt(norm.plan, item, stage, {})
}

test('S14: review-wave lane prompt carries the same line in the same order and position', () => {
  const lines = lanePromptOf(laneArgs(['p:a', 'b'])).split('\n')
  const at = lines.map((l) => l.trim()).indexOf(LINE('p:a, b'))
  assert.ok(at !== -1, lines.join('\n'))
  const keys = lineIndex(lines, (l) => /^[ \t]*Keys:/.test(l))
  const notFound = lineIndex(lines, (l) => l.includes('A not_found result'))
  assert.ok(keys !== -1, 'fixture: a Keys line exists')
  assert.ok(keys < at, `keys ${keys}, line ${at}`)
  if (notFound !== -1) assert.ok(at < notFound, `line ${at}, not_found ${notFound}`)
})

test('S15: absent or empty seatSkills adds no SEAT SKILLS line and leaves the rest of both prompts unchanged', () => {
  const withSkills = implementPrompt({ seatSkills: ['p:a'] })
  assert.ok(withSkills.includes('SEAT SKILLS'), 'twin: the field produces the line')
  const baseline = implementPrompt({})
  assert.equal(baseline.includes('SEAT SKILLS'), false)
  assert.equal(implementPrompt({ seatSkills: [] }), baseline)
  const stripped = withSkills.split('\n').filter((l) => !l.includes('SEAT SKILLS')).join('\n')
  assert.equal(stripped, baseline, 'the only difference is the one line')

  const laneWith = lanePromptOf(laneArgs(['rv']))
  assert.ok(laneWith.includes('SEAT SKILLS'), 'twin: the lane field produces the line')
  const laneNone = lanePromptOf(laneArgs([]))
  assert.equal(laneNone.includes('SEAT SKILLS'), false)
  assert.equal(laneWith.split('\n').filter((l) => !l.includes('SEAT SKILLS')).join('\n'), laneNone)
  // An absent list takes the shipped reviewer default.
  assert.ok(lanePromptOf(laneArgs(undefined)).split('\n').map((l) => l.trim()).includes(LINE('task-orchestrator:review-method')))
})

// ---- S16: Method B review prompt ----------------------------------------------------------------------

function cli(args, input) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', input: input === undefined ? undefined : JSON.stringify(input) })
}

function boundDoc() {
  const dir = mkdtempSync(join(tmpdir(), 'seat-skills-'))
  const snap = makeSnap([{ name: 'reviewer', phase: 'review', skills: [] }])
  const planRes = cli(['plan', '--now', NOW, '--scratchpad', join(dir, 'sp')], snap)
  assert.equal(planRes.status, 0, planRes.stderr)
  const planPath = join(dir, 'plan.json')
  writeFileSync(planPath, planRes.stdout)
  const bound = cli(['bind-run', '--in', planPath, '--server-run', SERVER_RUN])
  assert.equal(bound.status, 0, bound.stderr)
  return JSON.parse(bound.stdout)
}

test('S16: reviewPrompt names the union of the review seat skills once, after the rules, when the item carries them', () => {
  const baseDoc = boundDoc()
  const baseline = execLib.reviewPrompt(loadWaveCore(), baseDoc, 'aaaaaaaa')
  assert.equal(baseline.includes('SEAT SKILLS'), false, 'fixture: no skills, no line')
  const doc = JSON.parse(JSON.stringify(baseDoc))
  const item = doc.args.items.find((i) => i.short === 'aaaaaaaa')
  assert.ok(item, 'the bound plan holds the fixture item')
  item.reviewSeatSkills = { reviewer: ['a'], other: ['a', 'b'] }
  const text = execLib.reviewPrompt(loadWaveCore(), doc, 'aaaaaaaa')
  const baseLines = new Set(baseline.split('\n'))
  const added = text.split('\n').filter((l) => !baseLines.has(l))
  assert.equal(added.length, 1, JSON.stringify(added))
  assert.ok(added[0].includes('a, b'), added[0])
  assert.ok(!added[0].includes('a, a'), 'the union has no duplicate')
  const lines = text.split('\n')
  const rulesAt = lineIndex(lines, (l) => l.includes('protocol.read-only-agent'))
  assert.ok(rulesAt !== -1 && rulesAt < lines.indexOf(added[0]), 'the line follows the rules')
})

// ---- S17: lib-adoption pickup -------------------------------------------------------------------------

test('S17: lib-adoption lists seat-skills among the lib modules', () => {
  const text = readFileSync(join(PLUGIN, 'hooks', 'tests', 'lib-adoption.test.mjs'), 'utf8')
  assert.match(text, /LIB_MODULES\s*=\s*\[[^\]]*'seat-skills'[^\]]*\]/)
})

// ---- S19: null options (item ec7874fe, task-scope AC8 / D9) ---------------------------------------------

test('S19: a null options object is read as {} by seatSkillsFor, seatNameFor and resolveSeatSkills', () => {
  assert.deepEqual(seatSkillsFor(['a'], 'planner', null), ['a'])
  assert.equal(seatNameFor(null), null)
  assert.deepEqual(resolveSeatSkills(null), { seat: null, skills: [], source: 'none' })
})

test('S19 probe: undefined options behave like null, and a declared list still wins over an absent one', () => {
  // Twin fixture: the same call with a non-null options object resolves a seat, so the null result above is not a constant.
  assert.equal(seatNameFor({ callerSeat: 'planner' }), 'planner')
  assert.deepEqual(seatSkillsFor(['a'], 'planner', undefined), ['a'])
  assert.deepEqual(seatSkillsFor(undefined, 'planner', null), ['task-orchestrator:planning-standards'])
  assert.equal(seatNameFor(undefined), null)
})
