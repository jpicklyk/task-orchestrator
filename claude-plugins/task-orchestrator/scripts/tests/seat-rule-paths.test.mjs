// Item 6f4e4c77: every seat is given its rule keys from one data source on every dispatch path.
// Scenarios S1, S2, S4, S5, S6, S8, S9, S10, S14 and the probes of the frozen test-plan (S3, S7 and S11 are the hint, in
// hooks/tests/seat-rules-hint.test.mjs; S12 in hooks/tests/seat-rules-audit-docs.test.mjs; S13 in gen-seat-rules.test.mjs).
//
// Oracles: task-scope rulings R1 (three classes of rule), R4 (a declared protocol.* key that is not the role's own is
// dropped and reported), R5 (closure-notes is the implementer default), R6 (hint), acceptance criteria AC1-AC3 and the
// frozen design D1 (the table rows), D2 (resolver contract and the degradation text), D4, D5, D6. The expected key lists are
// the literal lists of test-plan "Lists": P = planner, I = implementer, V = reviewer. Never the code's output.
// BLIND test author: no production file was opened; the functions below are reached through their declared exports only.
//
// Fixture condition for each absence assertion: the paired list in the same test (or the next) does contain the key, so a
// resolver that kept the dropped key, or a path that kept its own list, fails.
// Forbidden-construct declaration: none.

import '../../hooks/tests/hermetic-claude-config.mjs'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadSeatRules, seatRoleOf, seatRulesFor, promptKeysFor, SEAT_RULES_PREFIX } from '../../hooks/lib/seat-rules.mjs'
import { deriveStages, buildPlanDoc } from '../run-planner-lib.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { loadCore, planFixture, itemFixture } from './workflow-harness.mjs'
import { loadCoreNamed } from './workflow-harness-ext.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const IMPLEMENT = join(PLUGIN, 'workflows', 'implement-wave.js')
const REVIEW = join(PLUGIN, 'workflows', 'review-wave.js')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'

const P = ['protocol.in-phase-seat', 'planning-sweeps', 'review-cited-claims']
const I = ['protocol.entry-seat', 'commit-discipline', 'closure-notes']
const V = ['protocol.read-only-agent', 'review-scoping', 'review-cited-claims']

const served = (...keys) => keys.map((key) => ({ key, rulesVersion: '1' }))
const ALL = ['commit-discipline', 'planning-sweeps', 'review-cited-claims', 'closure-notes', 'review-scoping', 'declarations-extractor', 'x-rule', 'y-rule']
const SERVED = served(...ALL)
const TABLE = loadSeatRules()

// ---- the table (D1) -----------------------------------------------------------------------------------------------

test('D1: the table holds the rows of the frozen design: protocol key, plugin-chosen keys and defaults per role', () => {
  const row = (protocol, pluginChosen, defaults) => ({ protocol, pluginChosen, defaults })
  assert.deepEqual(TABLE, {
    capabilities: {
      entry: 'protocol.entry-seat',
      inPhase: 'protocol.in-phase-seat',
      readOnly: 'protocol.read-only-agent',
      writerChosen: ['commit-discipline'],
      seatRows: ['planner', 'implementer', 'test-author', 'reviewer', 'declarations-extractor'],
    },
    roles: {
      planner: row('protocol.in-phase-seat', [], ['planning-sweeps', 'review-cited-claims']),
      implementer: row('protocol.entry-seat', ['commit-discipline'], ['closure-notes']),
      'test-author': row('protocol.in-phase-seat', ['commit-discipline'], []),
      reviewer: row('protocol.read-only-agent', [], ['review-scoping', 'review-cited-claims']),
      'declarations-extractor': row('protocol.read-only-agent', [], ['declarations-extractor']),
      'work-seat': row('protocol.in-phase-seat', ['commit-discipline'], []),
      'queue-seat': row('protocol.in-phase-seat', [], []),
      'read-only': row('protocol.read-only-agent', [], []),
    },
  })
  assert.equal(Object.hasOwn(TABLE.roles, 'orchestrator'), false, 'D1: no orchestrator row')
  assert.equal(SEAT_RULES_PREFIX, 'SEAT RULES:')
})

// ---- seatRoleOf (D2) ----------------------------------------------------------------------------------------------

test('D2 seatRoleOf: orchestrator none; review phase reviewer; output class, then seat name with an -a<n> suffix read as its base', () => {
  assert.equal(seatRoleOf({ seat: 'orchestrator', phase: 'work', writes: true, notes: ['session-tracking'] }), null)
  assert.equal(seatRoleOf({ seat: 'anything', phase: 'review', writes: false, notes: ['review-checklist'] }), 'reviewer')
  assert.equal(seatRoleOf({ seat: 'x', phase: 'queue', output: 'planner-v1', writes: false, notes: ['task-scope'] }), 'planner')
  assert.equal(seatRoleOf({ seat: 'x', phase: 'work', output: 'implementer-v1', enters: true, writes: true, notes: ['n'] }), 'implementer')
  assert.equal(seatRoleOf({ seat: 'x', phase: 'work', output: 'test-author-v1', writes: true, notes: ['n'] }), 'test-author')
  assert.equal(seatRoleOf({ seat: 'planner', phase: 'queue', writes: false, notes: ['task-scope'] }), 'planner')
  assert.equal(seatRoleOf({ seat: 'implementer', phase: 'work', enters: true, writes: true, notes: ['n'] }), 'implementer')
  assert.equal(seatRoleOf({ seat: 'implementer-a1', phase: 'work', enters: false, writes: true, notes: ['n'] }), 'implementer')
  assert.equal(seatRoleOf({ seat: 'test-author', phase: 'work', writes: true, notes: ['n'] }), 'test-author')
})

test('D2 seatRoleOf: no writes and no notes is read-only; a work seat is implementer when it enters, else work-seat; a queue seat is queue-seat', () => {
  assert.equal(seatRoleOf({ seat: 'looker', phase: 'work', writes: false, notes: [] }), 'read-only')
  assert.equal(seatRoleOf({ seat: 'builder', phase: 'work', enters: true, writes: true, notes: ['n'] }), 'implementer')
  assert.equal(seatRoleOf({ seat: 'builder', phase: 'work', enters: false, writes: true, notes: ['n'] }), 'work-seat')
  assert.equal(seatRoleOf({ seat: 'drafter', phase: 'queue', writes: false, notes: ['n'] }), 'queue-seat')
})

// ---- promptKeysFor (D2) -------------------------------------------------------------------------------------------

test('D2 promptKeysFor: own protocol key, plugin-chosen keys, then the stage rules, once each', () => {
  const impl = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: ['n'], rules: ['x-rule', 'commit-discipline'] }
  assert.deepEqual(promptKeysFor(TABLE, impl), ['protocol.entry-seat', 'commit-discipline', 'x-rule'])
  const planner = { seat: 'planner', phase: 'queue', writes: false, notes: ['task-scope'], rules: [...P.slice(1)] }
  assert.deepEqual(promptKeysFor(TABLE, planner), P)
  const reviewer = { seat: 'reviewer', phase: 'review', writes: false, notes: ['review-checklist'], rules: [...V.slice(1)] }
  assert.deepEqual(promptKeysFor(TABLE, reviewer), V)
})

test('D2 promptKeysFor probes: test author, orchestrator, extractor and the non-entering implementer', () => {
  const author = { seat: 'test-author', phase: 'work', output: 'test-author-v1', writes: true, notes: ['test-manifest'], rules: [] }
  assert.deepEqual(promptKeysFor(TABLE, author), ['protocol.in-phase-seat', 'commit-discipline'])
  assert.deepEqual(promptKeysFor(TABLE, { seat: 'orchestrator', phase: 'work', writes: true, notes: ['session-tracking'], rules: [] }), [])
  const extractor = { seat: 'declarations-extractor', phase: 'work', output: 'declarations-v1', writes: false, notes: [], rules: ['declarations-extractor'] }
  assert.deepEqual(promptKeysFor(TABLE, extractor), ['protocol.read-only-agent', 'declarations-extractor'])
  const fix = { seat: 'implementer-a1', phase: 'work', enters: false, writes: true, notes: ['implementation-notes'], rules: ['closure-notes'] }
  assert.deepEqual(promptKeysFor(TABLE, fix), ['protocol.in-phase-seat', 'commit-discipline', 'closure-notes'])
})

test('D2 promptKeysFor (R4): a stage rule that is a protocol key of another role is left out of the Keys; the own one stays once', () => {
  const planner = { seat: 'planner', phase: 'queue', writes: false, notes: ['task-scope'], rules: ['protocol.entry-seat', 'x-rule', 'protocol.in-phase-seat', 'protocol.read-only-agent'] }
  assert.deepEqual(promptKeysFor(TABLE, planner), ['protocol.in-phase-seat', 'x-rule'])
})

// ---- seatRulesFor (D2, R4, R5) ------------------------------------------------------------------------------------

const servedSet = (...keys) => new Set(keys)
const call = (over) => seatRulesFor(TABLE, { role: 'planner', seat: 'planner', enters: undefined, declared: undefined, noteRules: [], served: null, ...over })

test('S5 (R4, D2): planner rules [protocol.entry-seat, x-rule] -> [x-rule] and the stated degradation', () => {
  const res = call({ declared: ['protocol.entry-seat', 'x-rule'] })
  assert.deepEqual(res.rules, ['x-rule'])
  assert.deepEqual(res.degradations, ['seat rule protocol.entry-seat dropped: protocol rule of another role (planner)'])
})

test('S6 (R4, D2): a note skill that is a protocol key of another role is dropped with one degradation each', () => {
  const impl = call({ role: 'implementer', seat: 'implementer', enters: true, noteRules: ['protocol.read-only-agent', 'x-rule'] })
  assert.deepEqual(impl.rules, ['x-rule', 'closure-notes'], 'note rules first, then the defaults')
  assert.deepEqual(impl.degradations, ['seat rule protocol.read-only-agent dropped: protocol rule of another role (implementer)'])
  const rev = call({ role: 'reviewer', seat: 'reviewer', noteRules: ['protocol.in-phase-seat'] })
  assert.deepEqual(rev.rules, ['review-scoping', 'review-cited-claims'])
  assert.deepEqual(rev.degradations, ['seat rule protocol.in-phase-seat dropped: protocol rule of another role (reviewer)'])
})

test('S8 (R4): planner rules [protocol.in-phase-seat] (its own) is kept once with no degradation', () => {
  const res = call({ declared: ['protocol.in-phase-seat'], served: servedSet('protocol.in-phase-seat') })
  assert.deepEqual(res.rules, ['protocol.in-phase-seat'])
  assert.deepEqual(res.degradations, [])
  const stage = { seat: 'planner', phase: 'queue', writes: false, notes: ['task-scope'], rules: res.rules }
  assert.deepEqual(promptKeysFor(TABLE, stage), ['protocol.in-phase-seat'], 'listed once in the Keys')
})

test('S9 (R5, D2, D6): implementer with no rules -> [closure-notes]; non-entering the same; [x-rule] replaces it; unserved is absent and silent', () => {
  assert.deepEqual(call({ role: 'implementer', seat: 'implementer', enters: true, served: servedSet('closure-notes') }).rules, ['closure-notes'])
  assert.deepEqual(call({ role: 'implementer', seat: 'implementer-a1', enters: false, served: servedSet('closure-notes') }).rules, ['closure-notes'])
  const declared = call({ role: 'implementer', seat: 'implementer', enters: true, declared: ['x-rule'], served: servedSet('closure-notes', 'x-rule') })
  assert.deepEqual(declared.rules, ['x-rule'])
  const unserved = call({ role: 'implementer', seat: 'implementer', enters: true, served: servedSet('commit-discipline') })
  assert.deepEqual(unserved.rules, [])
  assert.deepEqual(unserved.degradations, [], 'an unserved default is dropped silently')
})

test('S10 (R1): reviewer rules [x-rule] -> Keys [protocol.read-only-agent, x-rule]; S4: reviewer rules [] -> [protocol.read-only-agent]', () => {
  const some = call({ role: 'reviewer', seat: 'reviewer', declared: ['x-rule'], served: servedSet('x-rule', 'review-scoping') })
  assert.deepEqual(some.rules, ['x-rule'])
  assert.deepEqual(promptKeysFor(TABLE, { seat: 'reviewer', phase: 'review', writes: false, notes: ['review-checklist'], rules: some.rules }), ['protocol.read-only-agent', 'x-rule'])
  const none = call({ role: 'reviewer', seat: 'reviewer', declared: [], served: servedSet('review-scoping', 'review-cited-claims') })
  assert.deepEqual(none.rules, [])
  assert.deepEqual(promptKeysFor(TABLE, { seat: 'reviewer', phase: 'review', writes: false, notes: ['review-checklist'], rules: none.rules }), ['protocol.read-only-agent'])
})

test('probe: implementer-a1 (not entering) declaring protocol.entry-seat has it dropped and reported', () => {
  const res = call({ role: 'implementer', seat: 'implementer-a1', enters: false, declared: ['protocol.entry-seat'] })
  assert.deepEqual(res.rules, [])
  assert.deepEqual(res.degradations, ['seat rule protocol.entry-seat dropped: protocol rule of another role (implementer-a1)'])
})

test('probe: declared absent, null and [] are three states for defaults; absent and null give the defaults, [] gives none', () => {
  assert.deepEqual(call({ declared: undefined }).rules, ['planning-sweeps', 'review-cited-claims'])
  assert.deepEqual(call({ declared: null }).rules, ['planning-sweeps', 'review-cited-claims'])
  assert.deepEqual(call({ declared: [] }).rules, [])
})

test('probe: a served Set that is empty filters everything (unserved declared reported, defaults silent); null filters nothing', () => {
  const emptySet = call({ declared: ['x-rule'], served: new Set() })
  assert.deepEqual(emptySet.rules, [])
  assert.deepEqual(emptySet.degradations, ['seat rule x-rule not served (planner)'])
  assert.deepEqual(call({ declared: undefined, served: new Set() }).degradations, [])
  assert.deepEqual(call({ declared: ['x-rule'], served: null }).rules, ['x-rule'])
})

test('probe: note rules come before declared rules and a key in both appears once', () => {
  const res = call({ declared: ['y-rule', 'x-rule'], noteRules: ['x-rule'] })
  assert.deepEqual(res.rules, ['x-rule', 'y-rule'])
})

// ---- the three dispatch paths (AC2) -------------------------------------------------------------------------------

const keysOf = (prompt) => {
  const line = prompt.split('\n').find((l) => l.includes('Keys:'))
  assert.ok(line, 'the prompt carries a Keys line')
  return line.slice(line.indexOf('Keys:') + 5).replace(/[.\s]+$/, '').split(',').map((k) => k.trim())
}

const candidate = (type = 'scratch-seat') => ({
  id: 'b0000000-0000-4000-8000-000000000001', short: 'b0000000', title: 'Item b0000000', priority: 'high', complexity: 2,
  type, role: 'queue', parentId: null, traits: [], hasChildren: false, source: 'ready',
})
const seatSchema = ({ seats, notes } = {}) => ({
  status: 'ok', type: 'scratch-seat', configFingerprint: 'fp-paths', configSource: 'per-root',
  notes: notes ?? [
    { key: 'diagnosis', role: 'queue', required: true, seat: 'planner' },
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
  ],
  seats: seats ?? [{ name: 'planner', phase: 'queue' }, { name: 'implementer', phase: 'work', enters: true }],
})
const seatlessSchema = () => ({
  status: 'ok', type: 'scratch-seat', configFingerprint: 'fp-seatless', configSource: 'per-root',
  notes: [
    { key: 'specification', role: 'queue', required: true, seat: null },
    { key: 'session-tracking', role: 'work', required: true, seat: null },
  ],
})

/** Path 1 (the workflow's seatPrompt) and path 2 (run-exec prompt) for one stage of a derived item. */
function dispatchKeys(entry, seat, rulesServed = SERVED) {
  const result = deriveStages(candidate(), entry, { rulesServed, noteOwners: [] })
  const stage = result.stages.find((s) => s.seat === seat)
  assert.ok(stage, `${seat} stage exists`)
  const item = itemFixture({ short: 'b0000000', stages: result.stages })
  const plan = planFixture({ items: [item], rootId: 'root-b0', baseSha: 'e77c3e95' })
  const viaWorkflow = loadCore(IMPLEMENT).seatPrompt(plan, item, stage, {})
  const doc = { contract: 'run-wave/plan-doc-v1', args: plan, meta: {} }
  const viaExec = execLib.prompt(loadWaveCore(), doc, execLib.initState(doc, 'B'), 'b0000000', seat)
  return { result, stage, workflow: keysOf(viaWorkflow), exec: keysOf(viaExec), viaWorkflow }
}

test('S1 (AC2, R1, R5): planner and implementer with no declared rules get P and I on the workflow prompt and the run-exec prompt alike', () => {
  const planner = dispatchKeys(seatSchema(), 'planner')
  assert.deepEqual(planner.workflow, P)
  assert.deepEqual(planner.exec, P)
  const impl = dispatchKeys(seatSchema(), 'implementer')
  assert.deepEqual(impl.workflow, I)
  assert.deepEqual(impl.exec, I)
})

test('S2 (R5): a seatless schema gives the implicit planner P and the implicit implementer I on both prompts', () => {
  const planner = dispatchKeys(seatlessSchema(), 'planner')
  assert.deepEqual(planner.workflow, P)
  assert.deepEqual(planner.exec, P)
  const impl = dispatchKeys(seatlessSchema(), 'implementer')
  assert.deepEqual(impl.workflow, I)
  assert.deepEqual(impl.exec, I)
  assert.deepEqual(impl.stage.rules, ['closure-notes'], 'carry-in-7d6144e9: the seatless implementer stage is told to fetch closure-notes')
})

test('S5 (R4, D5): a planner declaring [protocol.entry-seat, x-rule] has the stage rules [x-rule], one degradation, and no entry-seat in either Keys', () => {
  const entry = seatSchema({ seats: [{ name: 'planner', phase: 'queue', rules: ['protocol.entry-seat', 'x-rule'] }, { name: 'implementer', phase: 'work', enters: true }] })
  const out = dispatchKeys(entry, 'planner')
  assert.deepEqual(out.stage.rules, ['x-rule'])
  assert.deepEqual((out.result.degradations ?? []).map(String).filter((d) => d.includes('protocol.entry-seat')), ['seat rule protocol.entry-seat dropped: protocol rule of another role (planner)'])
  assert.deepEqual(out.workflow, ['protocol.in-phase-seat', 'x-rule'])
  assert.deepEqual(out.exec, ['protocol.in-phase-seat', 'x-rule'])
})

test('S6 (R4, D5): a served protocol key named as a note skill on a note of another role is dropped from the stage and both Keys, with one degradation', () => {
  const entry = seatSchema({
    notes: [
      { key: 'diagnosis', role: 'queue', required: true, seat: 'planner' },
      { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer', skill: 'protocol.read-only-agent' },
    ],
  })
  const served = SERVED.concat([{ key: 'protocol.read-only-agent', rulesVersion: '1' }])
  const out = dispatchKeys(entry, 'implementer', served)
  assert.equal(out.stage.rules.includes('protocol.read-only-agent'), false, JSON.stringify(out.stage.rules))
  assert.deepEqual((out.result.degradations ?? []).map(String).filter((d) => d.includes('protocol.read-only-agent')), ['seat rule protocol.read-only-agent dropped: protocol rule of another role (implementer)'])
  assert.deepEqual(out.workflow, I)
  assert.deepEqual(out.exec, I)
})

test('S9 (R5, D6): a declared implementer list replaces the default on both prompts; a schema that is not found yields no closure-notes', () => {
  const declared = seatSchema({ seats: [{ name: 'planner', phase: 'queue' }, { name: 'implementer', phase: 'work', enters: true, rules: ['x-rule'] }] })
  const out = dispatchKeys(declared, 'implementer')
  assert.deepEqual(out.workflow, ['protocol.entry-seat', 'commit-discipline', 'x-rule'])
  assert.deepEqual(out.exec, ['protocol.entry-seat', 'commit-discipline', 'x-rule'])
  const missing = deriveStages(candidate(), { status: 'not-found' }, { rulesServed: SERVED, noteOwners: [] })
  for (const stage of missing.stages) assert.equal((stage.rules ?? []).includes('closure-notes'), false, stage.seat)
})

test('probe: an extractor stage gets its accuracy rule when served and the run records the gap when not (protocol.read-only-agent first)', () => {
  const entry = seatSchema({
    notes: [
      { key: 'diagnosis', role: 'queue', required: true, seat: 'planner' },
      { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
      { key: 'test-manifest', role: 'work', required: true, seat: 'test-author' },
    ],
    seats: [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true },
      { name: 'test-author', phase: 'work', after: ['implementer'], readsExclude: ['implementation-notes'] },
    ],
  })
  const withRule = dispatchKeys(entry, 'declarations-extractor')
  assert.deepEqual(withRule.workflow, ['protocol.read-only-agent', 'declarations-extractor'])
  assert.deepEqual(withRule.exec, ['protocol.read-only-agent', 'declarations-extractor'])
  const without = deriveStages(candidate(), entry, { rulesServed: served('commit-discipline'), noteOwners: [] })
  assert.ok(without.degradations.map(String).includes('extractor without accuracy rule'), JSON.stringify(without.degradations))
  assert.deepEqual(without.stages.find((s) => s.seat === 'declarations-extractor').rules ?? [], [])
})

// ---- the review paths (S1, S4, S6, S10) ---------------------------------------------------------------------------

const ITEM = 'aaaaaaaa-1111-4111-8111-111111111111'
const RW = loadCoreNamed(REVIEW, ['normalizeArgs', 'preflight', 'lanePrompt'])
const reviewNote = (key, extra) => ({ key, role: 'review', required: true, seat: 'reviewer', ...extra })

function reviewKeys({ reviewerRules, servedKeys, reviewNotes = [reviewNote('review-checklist')] }) {
  const snap = JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))
  snap.rulesServed = served('commit-discipline', ...servedKeys)
  const entry = snap.schemas[ITEM]
  entry.notes = [
    { key: 'specification', role: 'queue', required: true, seat: 'planner' },
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
    { key: 'session-tracking', role: 'work', required: true, seat: 'orchestrator' },
    ...reviewNotes,
  ]
  entry.seats = [
    { name: 'planner', phase: 'queue' },
    { name: 'implementer', phase: 'work', enters: true },
    { name: 'orchestrator', phase: 'work' },
    reviewerRules === undefined ? { name: 'reviewer', phase: 'review' } : { name: 'reviewer', phase: 'review', rules: reviewerRules },
  ]
  entry.dispatchBySeat = { review: { reviewer: { agent: 'task-orchestrator:reviewer', model: 'opus', effort: 'high' } } }
  const built = buildPlanDoc(snap, { now: NOW })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  const item = built.doc.args.items.find((i) => i.id === ITEM)
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: built.doc.args.runId, planDocSlug: 'run/x',
    items: [{ id: item.id, short: item.short, status: 'done', reason: 'ok', stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })), outputs: {} }],
    refused: [], deferred: [],
  }
  const args = execLib.reviewWaveArgs(loadWaveCore(), built.doc, snap, result, [item.short])
  const norm = RW.normalizeArgs(args)
  assert.equal(norm.ok, true, norm.reason)
  assert.deepEqual(RW.preflight(norm.plan).refused, [])
  const planItem = norm.plan.items[0]
  const lanes = planItem.stages.map((stage) => ({ stage, keys: keysOf(RW.lanePrompt(norm.plan, planItem, stage, {})) }))
  const exec = keysOf(execLib.reviewPrompt(loadWaveCore(), built.doc, 'aaaaaaaa'))
  return { lanes, exec, args }
}

test('S1 (AC2, R1): a reviewer with no declared rules and the defaults served gets V on the lane prompt and the review prompt', () => {
  const out = reviewKeys({ servedKeys: ['review-scoping', 'review-cited-claims'] })
  assert.ok(out.lanes.length >= 1, 'fixture: at least one lane')
  for (const lane of out.lanes) assert.deepEqual(lane.keys, V, lane.stage.lane)
  assert.deepEqual(out.exec, V)
})

test('S4 (AC3): a reviewer declaring rules [] gets the protocol key only on both review paths, though the defaults are served', () => {
  const out = reviewKeys({ reviewerRules: [], servedKeys: ['review-scoping', 'review-cited-claims'] })
  for (const lane of out.lanes) assert.deepEqual(lane.keys, ['protocol.read-only-agent'], lane.stage.lane)
  assert.deepEqual(out.exec, ['protocol.read-only-agent'])
})

test('S10 (R1): a reviewer declaring [x-rule] gets [protocol.read-only-agent, x-rule] on both review paths; review-scoping is not added', () => {
  const out = reviewKeys({ reviewerRules: ['x-rule'], servedKeys: ['x-rule', 'review-scoping', 'review-cited-claims'] })
  for (const lane of out.lanes) assert.deepEqual(lane.keys, ['protocol.read-only-agent', 'x-rule'], lane.stage.lane)
  assert.deepEqual(out.exec, ['protocol.read-only-agent', 'x-rule'])
})

test('S6 (R4, D5): a review note whose skill is the in-phase protocol key is dropped from the lane rules and both Keys, and the lane carries no such rule', () => {
  const out = reviewKeys({
    servedKeys: ['protocol.in-phase-seat'],
    reviewNotes: [reviewNote('review-checklist', { skill: 'protocol.in-phase-seat' })],
  })
  for (const lane of out.lanes) {
    assert.equal((lane.stage.rules ?? []).includes('protocol.in-phase-seat'), false, lane.stage.lane)
    assert.deepEqual(lane.keys, ['protocol.read-only-agent'], lane.stage.lane)
  }
  assert.deepEqual(out.exec, ['protocol.read-only-agent'])
})

// ---- S14 (AC1): no path states its own list -----------------------------------------------------------------------

const IN_PLAY = [...new Set(Object.values(TABLE.roles).flatMap((r) => [r.protocol, ...r.pluginChosen, ...r.defaults]))]
const REGION_BEGIN = '// @generated:seat-rules-begin'
const REGION_END = '// @generated:seat-rules-end'

function withoutRegion(text) {
  const a = text.indexOf(REGION_BEGIN)
  const b = text.indexOf(REGION_END)
  if (a === -1 && b === -1) return text
  assert.ok(a !== -1 && b > a, 'both region markers are present, in order')
  return text.slice(0, a) + text.slice(b + REGION_END.length)
}

function literalsEqualTo(text, key) {
  const hits = []
  for (const quote of ["'", '"', String.fromCharCode(96)]) {
    let from = 0
    const needle = quote + key + quote
    for (;;) {
      const at = text.indexOf(needle, from)
      if (at === -1) break
      hits.push(`${quote}${key}${quote} at offset ${at}`)
      from = at + 1
    }
  }
  return hits
}

for (const rel of ['scripts/run-planner-lib.mjs', 'scripts/run-exec-lib.mjs', 'hooks/seat-skill-hint.mjs', 'workflows/implement-wave.js', 'workflows/review-wave.js']) {
  test(`S14 (AC1): ${rel} holds no string literal equal to a key of the table outside the generated region`, () => {
    assert.ok(IN_PLAY.length >= 9, 'fixture: the table yields its keys')
    const text = withoutRegion(readFileSync(join(PLUGIN, rel), 'utf8'))
    for (const key of IN_PLAY) assert.deepEqual(literalsEqualTo(text, key), [], `${rel} states its own ${key}`)
  })
}

test('S14 control: the literal scan finds a key that is present (so an empty result above can fail)', () => {
  assert.equal(literalsEqualTo("const k = 'commit-discipline'", 'commit-discipline').length, 1)
  assert.equal(literalsEqualTo('const k = "commit-discipline"', 'commit-discipline').length, 1)
  assert.equal(literalsEqualTo('const k = commit-discipline-x', 'commit-discipline').length, 0)
})
