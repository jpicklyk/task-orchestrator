// Item 2f3833fb: the plugin takes seat facts from the server (served enters, writes, transitions and note owner)
// instead of guessing them from seat names and phases. Scenarios AC1-AC8 and AC10 of the item's specification.
//
// Oracles: the specification's literal lists (AC1 Keys lists, AC3 transitions and TRANSITIONS line, AC6 finding
// text, AC7 FIX CYCLE endings, AC8 token file name) and the server's DEFAULT_TRANSITIONS line (AC10), never the
// code's own output. Each absence assertion has a paired presence assertion in the same test.
// Forbidden-construct declaration: none.

import '../../hooks/tests/hermetic-claude-config.mjs'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadSeatRules, seatProtocolFor, seatDefaultsRoleFor, promptKeysFor } from '../../hooks/lib/seat-rules.mjs'
import { deriveStages, ownership, laneSeatOf, SERVER_DEFAULT_TRANSITIONS } from '../run-planner-lib.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { loadCore, planFixture, itemFixture, stages } from './workflow-harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const REPO = resolve(PLUGIN, '..', '..')
const IMPLEMENT = join(PLUGIN, 'workflows', 'implement-wave.js')

const TABLE = loadSeatRules()
const SERVED_FEATURES = ['seats', 'dispatchBySeat', 'rules', 'seatWrites', 'orchestratorNotes']
const OLD_FEATURES = ['seats', 'dispatchBySeat', 'rules']
const served = (...keys) => keys.map((key) => ({ key, rulesVersion: '1' }))
const RULES = served('planning-sweeps', 'review-cited-claims', 'commit-discipline', 'closure-notes', 'zero-warnings', 'review-scoping')

const candidate = () => ({
  id: 'b0000000-0000-4000-8000-000000000001', short: 'b0000000', title: 'Item b0000000', priority: 'high', complexity: 2,
  type: 'feature-task', role: 'queue', parentId: null, traits: [], hasChildren: false, source: 'ready',
})

// The dogfood feature-task: planner (queue), implementer (work, enters), reviewer (review); served writes false/true/false.
const dogfood = (over = {}) => ({
  status: 'ok', type: 'feature-task', configFingerprint: 'fp-cap', configSource: 'per-root',
  notes: [
    { key: 'specification', role: 'queue', required: true, owningSeat: 'planner' },
    { key: 'session-tracking', role: 'work', required: true, owningSeat: 'implementer' },
    { key: 'review-checklist', role: 'review', required: true, owningSeat: 'reviewer' },
    { key: 'orchestrator-confirmation', role: 'review', required: false, owningSeat: 'orchestrator' },
  ],
  seats: [
    { name: 'planner', phase: 'queue', writes: false },
    { name: 'implementer', phase: 'work', enters: true, writes: true, rules: ['closure-notes', 'zero-warnings'] },
    { name: 'reviewer', phase: 'review', writes: false },
  ],
  ...over,
})

const keysOf = (prompt) => {
  const line = prompt.split('\n').find((l) => l.includes('Keys:'))
  assert.ok(line, 'the prompt carries a Keys line')
  return line.slice(line.indexOf('Keys:') + 5).replace(/[.\s]+$/, '').split(',').map((k) => k.trim())
}

function derive(entry, features = SERVED_FEATURES) {
  return deriveStages(candidate(), entry, { rulesServed: RULES, noteOwners: [], features })
}

function prompts(result, seat) {
  const stage = result.stages.find((s) => s.seat === seat)
  assert.ok(stage, `${seat} stage exists`)
  const item = itemFixture({ short: 'b0000000', stages: result.stages })
  const plan = planFixture({ items: [item], rootId: 'root-b0', baseSha: 'e77c3e95' })
  const viaWorkflow = loadCore(IMPLEMENT).seatPrompt(plan, item, stage, {})
  const doc = { contract: 'run-wave/plan-doc-v1', args: plan, meta: {} }
  const viaExec = execLib.prompt(loadWaveCore(), doc, execLib.initState(doc, 'B'), 'b0000000', seat)
  return { stage, workflow: viaWorkflow, exec: viaExec }
}

// ---- AC1 ---------------------------------------------------------------------------------------------------------

test('AC1: served dogfood feature-task: planner is read-only with its defaults, implementer keeps the entry protocol, on both prompts', () => {
  const result = derive(dogfood())
  const planner = prompts(result, 'planner')
  const impl = prompts(result, 'implementer')
  const P = ['protocol.read-only-agent', 'planning-sweeps', 'review-cited-claims']
  const I = ['protocol.entry-seat', 'commit-discipline', 'closure-notes', 'zero-warnings']
  assert.deepEqual(keysOf(planner.workflow), P)
  assert.deepEqual(keysOf(planner.exec), P)
  assert.deepEqual(keysOf(impl.workflow), I)
  assert.deepEqual(keysOf(impl.exec), I)
  assert.equal(planner.stage.writes, false)
  assert.equal(impl.stage.writes, true)
})

// ---- AC2 ---------------------------------------------------------------------------------------------------------

test('AC2: protocol from enters and writes, defaults by seat name (resolver level)', () => {
  assert.equal(seatProtocolFor(TABLE, { enters: true, writes: true }), 'protocol.entry-seat')
  assert.equal(seatProtocolFor(TABLE, { enters: false, writes: true }), 'protocol.in-phase-seat')
  assert.equal(seatProtocolFor(TABLE, { enters: false, writes: false }), 'protocol.read-only-agent')
  assert.equal(seatProtocolFor({ roles: {}, capabilities: null }, { enters: true, writes: true }), null)
  assert.equal(seatDefaultsRoleFor(TABLE, 'test-author-a2'), 'test-author')
  assert.equal(seatDefaultsRoleFor(TABLE, 'fixer'), null)
  assert.equal(seatDefaultsRoleFor(TABLE, 'work-seat'), null, 'a roles row that is not a seat row gives no defaults')
  // an entering work seat named test-author with writes true; a review seat fixer with writes true
  assert.deepEqual(
    promptKeysFor(TABLE, { seat: 'test-author', phase: 'work', enters: true, writes: true, protocol: seatProtocolFor(TABLE, { enters: true, writes: true }), rules: [] }),
    ['protocol.entry-seat', 'commit-discipline']
  )
  assert.deepEqual(
    promptKeysFor(TABLE, { seat: 'fixer', phase: 'review', writes: true, protocol: seatProtocolFor(TABLE, { enters: false, writes: true }), rules: [] }),
    ['protocol.in-phase-seat', 'commit-discipline']
  )
  assert.deepEqual(
    promptKeysFor(TABLE, { seat: 'drafter', phase: 'queue', writes: false, protocol: seatProtocolFor(TABLE, { enters: false, writes: false }), rules: [] }),
    ['protocol.read-only-agent']
  )
})

test('AC2: a queue seat drafter with no row and no rules or skills gets only its protocol key and one advisory naming it', () => {
  const entry = dogfood({
    notes: [
      { key: 'draft', role: 'queue', required: true, owningSeat: 'drafter' },
      { key: 'session-tracking', role: 'work', required: true, owningSeat: 'implementer' },
    ],
    seats: [
      { name: 'drafter', phase: 'queue', writes: false },
      { name: 'implementer', phase: 'work', enters: true, writes: true },
    ],
  })
  const result = derive(entry)
  const drafter = result.stages.find((s) => s.seat === 'drafter')
  assert.deepEqual(drafter.rules, [])
  assert.equal(drafter.protocol, 'protocol.read-only-agent')
  const adv = result.degradations.filter((d) => d.includes('drafter'))
  assert.deepEqual(adv, ['seat drafter of feature-task has no defaults row: no rule or skill defaults (declare rules: and skills:)'])
  assert.equal(result.degradations.some((d) => d.includes('implementer')), false, 'implementer has a row: no advisory')
  assert.deepEqual(keysOf(prompts(result, 'drafter').workflow), ['protocol.read-only-agent'])
  // declaring both rules and skills silences it
  const declared = dogfood({
    ...entry,
    seats: [
      { name: 'drafter', phase: 'queue', writes: false, rules: [], skills: [] },
      { name: 'implementer', phase: 'work', enters: true, writes: true },
    ],
  })
  assert.deepEqual(derive(declared).degradations.filter((d) => d.includes('drafter')), [])
})

test('AC2: a served test-author seat that enters with writes true gets the entry protocol first and commit-discipline', () => {
  const entry = dogfood({
    notes: [
      { key: 'specification', role: 'queue', required: true, owningSeat: 'planner' },
      { key: 'test-manifest', role: 'work', required: true, owningSeat: 'test-author' },
      { key: 'session-tracking', role: 'work', required: true, owningSeat: 'implementer' },
    ],
    seats: [
      { name: 'planner', phase: 'queue', writes: false },
      { name: 'test-author', phase: 'work', enters: true, writes: true },
      { name: 'implementer', phase: 'work', writes: true, after: ['test-author'] },
    ],
  })
  const result = derive(entry)
  const author = result.stages.find((s) => s.seat === 'test-author')
  assert.equal(author.protocol, 'protocol.entry-seat')
  assert.deepEqual(keysOf(prompts(result, 'test-author').workflow).slice(0, 2), ['protocol.entry-seat', 'commit-discipline'])
  const impl = result.stages.find((s) => s.seat === 'implementer')
  assert.equal(impl.protocol, 'protocol.in-phase-seat')
  assert.deepEqual(result.stages.filter((s) => s.writes).map((s) => s.seat), ['test-author', 'implementer'])
})

// ---- AC3 ---------------------------------------------------------------------------------------------------------

test('AC3: served writes and transitions: entering seat with none declared, declared [complete] without entering, the exact line', () => {
  const entry = dogfood({
    seats: [
      { name: 'planner', phase: 'queue', writes: false, transitions: ['complete'] },
      { name: 'implementer', phase: 'work', enters: true, writes: true },
      { name: 'reviewer', phase: 'review', writes: false },
    ],
  })
  const result = derive(entry)
  const planner = result.stages.find((s) => s.seat === 'planner')
  const impl = result.stages.find((s) => s.seat === 'implementer')
  assert.deepEqual(planner.transitions, ['complete'])
  assert.deepEqual(impl.transitions, ['start', 'block', 'hold'])
  assert.match(prompts(result, 'implementer').workflow, /^TRANSITIONS: in an open run the server accepts only these triggers from your seat on item b0000000: start, block, hold\. Send no other; your protocol rule says which of these you send\.$/m)
  assert.match(prompts(result, 'planner').exec, /^TRANSITIONS: in an open run the server accepts only these triggers from your seat on item b0000000: complete\. /m)
})

test('AC3: a seat whose served transitions are empty and that does not enter gets the "accepts no trigger" line', () => {
  const entry = dogfood({
    seats: [
      { name: 'planner', phase: 'queue', writes: false, transitions: [] },
      { name: 'implementer', phase: 'work', enters: true, writes: true },
      { name: 'reviewer', phase: 'review', writes: false },
    ],
  })
  const result = derive(entry)
  assert.deepEqual(result.stages.find((s) => s.seat === 'planner').transitions, [])
  assert.match(prompts(result, 'planner').workflow, /^TRANSITIONS: in an open run the server accepts no trigger from your seat on item b0000000\. Send none\.$/m)
})

test('AC3: a seat-less schema carries neither transitions nor the line, and the served stage writes equals the served value', () => {
  const seatless = {
    status: 'ok', type: 'feature-task', configFingerprint: 'fp-sl', configSource: 'per-root',
    notes: [
      { key: 'specification', role: 'queue', required: true, owningSeat: 'planner' },
      { key: 'session-tracking', role: 'work', required: true, owningSeat: 'implementer' },
    ],
  }
  const result = derive(seatless)
  for (const stage of result.stages) assert.equal('transitions' in stage, false, stage.seat)
  assert.equal(prompts(result, 'implementer').workflow.includes('TRANSITIONS:'), false)
  assert.equal(result.stages.find((s) => s.seat === 'implementer').protocol, 'protocol.entry-seat')
  assert.equal(result.stages.find((s) => s.seat === 'planner').protocol, 'protocol.read-only-agent')
  // a queue seat declared writes true is a writer on the served path (the old path guessed from the phase)
  const writer = dogfood({
    seats: [
      { name: 'planner', phase: 'queue', writes: true },
      { name: 'implementer', phase: 'work', enters: true, writes: false },
      { name: 'reviewer', phase: 'review', writes: false },
    ],
  })
  const w = derive(writer)
  assert.equal(w.stages.find((s) => s.seat === 'planner').writes, true)
  assert.equal(w.stages.find((s) => s.seat === 'implementer').writes, false)
  const oldNotes = writer.notes.map(({ owningSeat, ...n }) => ({ ...n, seat: owningSeat }))
  assert.equal(derive({ ...writer, notes: oldNotes }, OLD_FEATURES).stages.find((s) => s.seat === 'planner').writes, false, 'old path: writes from the phase')
})

test('AC3: a stage with a malformed protocol or transitions is refused by the workflow, a well-formed one is accepted', () => {
  const core = loadCore(IMPLEMENT)
  const mk = (extra) => {
    const stage = { seat: 'implementer', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'implementer-v1', ...extra }
    const item = itemFixture({ short: 'b0000000', stages: [stage] })
    return planFixture({ items: [item] })
  }
  const norm = (extra) => core.normalizeArgs(mk(extra))
  assert.equal(norm({ protocol: 'protocol.entry-seat', transitions: ['start'] }).ok, true)
  assert.equal(norm({ protocol: 'entry-seat' }).ok, false)
  assert.equal(norm({ protocol: 3 }).ok, false)
  assert.equal(norm({ transitions: 'start' }).ok, false)
  assert.equal(norm({ transitions: [1] }).ok, false)
})

// ---- AC4 ---------------------------------------------------------------------------------------------------------

test('AC4: without seatWrites or orchestratorNotes the stages carry no protocol or transitions and writes follow the phase', () => {
  const entry = dogfood({
    notes: [
      { key: 'specification', role: 'queue', required: true, seat: 'planner' },
      { key: 'session-tracking', role: 'work', required: true, seat: 'implementer' },
      { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer' },
    ],
  })
  const result = derive(entry, OLD_FEATURES)
  for (const stage of result.stages) {
    assert.equal('protocol' in stage, false, stage.seat)
    assert.equal('transitions' in stage, false, stage.seat)
  }
  assert.deepEqual(result.stages.map((s) => [s.seat, s.writes]), [['planner', false], ['implementer', true]])
  assert.deepEqual(keysOf(prompts(result, 'planner').workflow), ['protocol.in-phase-seat', 'planning-sweeps', 'review-cited-claims'])
  assert.deepEqual(result.stages.find((s) => s.seat === 'implementer').notes, ['session-tracking'], 'old path: note.seat')
  // the table without capabilities also takes the old path although the server advertises seatWrites
  const noCaps = deriveStages(candidate(), entry, { rulesServed: RULES, noteOwners: [], features: SERVED_FEATURES, seatRules: { roles: TABLE.roles, capabilities: null } })
  for (const stage of noCaps.stages) assert.equal('protocol' in stage, false, stage.seat)
})

// ---- AC5 ---------------------------------------------------------------------------------------------------------

test('AC5: served owner, seat-aware schema with no declared orchestrator seat: session-tracking is orchestrator-owned and in no stage', () => {
  const entry = dogfood({
    notes: [
      { key: 'specification', role: 'queue', required: true, owningSeat: 'planner' },
      { key: 'implementation-notes', role: 'work', required: true, owningSeat: 'implementer' },
      { key: 'session-tracking', role: 'work', required: true, owningSeat: 'orchestrator' },
    ],
  })
  const served = derive(entry)
  assert.equal(served.exclude, null, 'the item is admitted')
  assert.deepEqual(served.orchestratorNotes, ['session-tracking'])
  for (const stage of served.stages) assert.equal(stage.notes.includes('session-tracking'), false, stage.seat)
  assert.deepEqual(served.stages.find((s) => s.seat === 'implementer').notes, ['implementation-notes'])
  // the old path reads note.seat: a seat-orchestrator note with no declared orchestrator seat is unowned and excludes the item
  const oldNotes = entry.notes.map(({ owningSeat, ...n }) => ({ ...n, seat: owningSeat }))
  const old = derive({ ...entry, notes: oldNotes }, OLD_FEATURES)
  assert.equal(old.exclude, 'unowned required note(s) session-tracking')
})

test('AC5: a required note with a null served owner is unowned on the served path', () => {
  const schema = { seats: [{ name: 'planner', phase: 'queue' }], notes: [{ key: 'a', role: 'queue', required: true, owningSeat: null }, { key: 'b', role: 'queue', required: false, owningSeat: null }, { key: 'c', role: 'queue', required: true, owningSeat: 'planner' }] }
  assert.deepEqual(ownership(schema, ['queue'], true), { unowned: ['a'], orchestratorNotes: [] })
})

test('AC5: seat-less schema: a served orchestrator note is in orchestratorNotes; an unannotated delegation-metadata goes to the implicit implementer on the served path only', () => {
  const seatless = {
    status: 'ok', type: 'feature-task', configFingerprint: 'fp-sl', configSource: 'per-root',
    notes: [
      { key: 'specification', role: 'queue', required: true, owningSeat: null },
      { key: 'session-tracking', role: 'work', required: true, owningSeat: null },
      { key: 'delegation-metadata', role: 'work', required: false, owningSeat: null },
      { key: 'orchestrator-confirmation', role: 'review', required: false, owningSeat: 'orchestrator' },
    ],
  }
  const servedResult = derive(seatless)
  assert.deepEqual(servedResult.orchestratorNotes, ['orchestrator-confirmation'])
  assert.deepEqual(servedResult.stages.find((s) => s.seat === 'implementer').notes, ['session-tracking', 'delegation-metadata'])
  const oldResult = derive(seatless, OLD_FEATURES)
  assert.deepEqual(oldResult.orchestratorNotes.sort(), ['delegation-metadata', 'orchestrator-confirmation'])
  assert.deepEqual(oldResult.stages.find((s) => s.seat === 'implementer').notes, ['session-tracking'])
})

// ---- AC6 ---------------------------------------------------------------------------------------------------------

const PLANNER_OUT = {
  proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
  decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none', sweep: [],
}
const IMPL_OUT = {
  mainFilesChanged: ['src/x.js'], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}
const SERVER_RUN_ID = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'

function verifyRow(stageList, rows, commits) {
  const item = itemFixture({ short: 'aaaaaaaa', stages: stageList })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, serverRunId: SERVER_RUN_ID,
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: [
        { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
        ...rows.map((r) => ({ seat: r.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: r.pre, post: r.post }, files: r.files })),
      ],
      outputs: { planner: PLANNER_OUT, implementer: IMPL_OUT },
    }],
    refused: [], deferred: [],
  }
  const exists = {}
  for (const r of rows) { exists[r.pre] = true; exists[r.post] = true }
  const out = execLib.verify(doc, result, { exists, commits })
  return out.items.find((i) => i.short === 'aaaaaaaa')
}

const planner = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
const author = { seat: 'test-author', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'test-author-v1', readsExclude: [] }
const implAfter = { seat: 'implementer', phase: 'work', writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
const authorRow = { seat: 'test-author', pre: '9999999', post: '8888888', files: ['scripts/tests/x.test.mjs'] }
const implRow = { seat: 'implementer', pre: 'aaaa000', post: 'bbbb111', files: ['src/x.js'] }
const authorCommit = { sha: '8888888', subject: 'test(x): tests [aaaaaaaa]', body: 'why\n\nSeat: test-author', files: ['scripts/tests/x.test.mjs'] }
const implCommit = { sha: 'bbbb111', subject: 'feat(x): implement [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] }

test('AC6: plan order test-author then implementer: author commits first is clean, reversed names the later seat', () => {
  const ok = verifyRow([planner, author, implAfter], [authorRow, implRow], [authorCommit, implCommit])
  assert.equal(ok.findings.some((f) => f.includes('precedes')), false, JSON.stringify(ok.findings))
  const reversed = verifyRow([planner, author, implAfter], [authorRow, implRow], [implCommit, authorCommit])
  assert.ok(reversed.findings.includes('implementer commit bbbb111 precedes test-author'), JSON.stringify(reversed.findings))
})

test('AC6: the old order (implementer then test-author) keeps its finding text', () => {
  const impl = { ...implAfter, enters: true }
  const authorAfter = { ...author, enters: false }
  const bad = verifyRow([planner, impl, authorAfter], [implRow, authorRow], [authorCommit, implCommit])
  assert.ok(bad.findings.includes('test-author commit 8888888 precedes implementer'), JSON.stringify(bad.findings))
  const good = verifyRow([planner, impl, authorAfter], [implRow, authorRow], [implCommit, authorCommit])
  assert.equal(good.findings.some((f) => f.includes('precedes')), false, JSON.stringify(good.findings))
})

// ---- AC7 ---------------------------------------------------------------------------------------------------------

test('AC7: reseat of a non-writing base has no commit sentence, a writing base keeps it; the reseat loses start and takes in-phase', () => {
  const list = stages.bugFixLike()
  const plannerStage = list.find((s) => s.seat === 'planner')
  const implStage = list.find((s) => s.seat === 'implementer')
  Object.assign(plannerStage, { writes: false, protocol: 'protocol.read-only-agent', transitions: ['complete'] })
  Object.assign(implStage, { writes: true, protocol: 'protocol.entry-seat', transitions: ['start', 'block', 'hold'] })
  const item = itemFixture({ short: 'aaaaaaaa', stages: list })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok',
      stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })),
      outputs: { planner: PLANNER_OUT, implementer: IMPL_OUT, 'declarations-extractor': { declarations: 'none', harnessPointers: 'p', gaps: 'none' } },
    }],
    refused: [], deferred: [],
  }
  const core = loadWaveCore()
  const line = (p) => p.split('\n').find((l) => l.startsWith('FIX CYCLE A'))
  const p = execLib.reseat(core, doc, result, 'aaaaaaaa', 'planner-a1', { cause: 'review', pre: 'c'.repeat(40) })
  assert.equal(p.ok, true, JSON.stringify(p))
  assert.equal(line(p.prompt).includes('Commit your owned paths'), false, line(p.prompt))
  const i = execLib.reseat(core, doc, result, 'aaaaaaaa', 'implementer-a1', { cause: 'review', pre: 'c'.repeat(40) })
  assert.equal(i.ok, true, JSON.stringify(i))
  assert.ok(line(i.prompt).includes('Commit your owned paths'), line(i.prompt))
  assert.ok(line(i.prompt).endsWith('never the whole tree.'))
  assert.match(i.prompt, /^TRANSITIONS: .* from your seat on item aaaaaaaa: block, hold\./m)
  assert.deepEqual(keysOf(i.prompt).slice(0, 2), ['protocol.in-phase-seat', 'commit-discipline'])
  assert.deepEqual(keysOf(p.prompt)[0], 'protocol.read-only-agent')
})

// ---- AC8 ---------------------------------------------------------------------------------------------------------

test('AC8: a schema whose only review seat is auditor and whose review note is unowned gets lane seat auditor', () => {
  const entry = dogfood({
    notes: [
      { key: 'specification', role: 'queue', required: true, owningSeat: 'planner' },
      { key: 'session-tracking', role: 'work', required: true, owningSeat: 'implementer' },
      { key: 'review-checklist', role: 'review', required: false, owningSeat: null },
    ],
    seats: [
      { name: 'planner', phase: 'queue', writes: false },
      { name: 'implementer', phase: 'work', enters: true, writes: true },
      { name: 'auditor', phase: 'review', writes: false },
    ],
  })
  assert.equal(laneSeatOf(entry.notes[2], entry, true), 'auditor')
  assert.equal(laneSeatOf({ key: 'x', role: 'review', owningSeat: 'reviewer' }, entry, true), 'reviewer', 'an owner wins')
  assert.equal(laneSeatOf({ key: 'x', role: 'review', reviewer: 'lead' }, entry, true), 'lead', 'a note reviewer wins over the one declared seat')
  assert.equal(laneSeatOf(entry.notes[2], { seats: [] }, true), 'reviewer', 'no declared review seat: the server default')
  assert.equal(laneSeatOf(entry.notes[2], { seats: [{ name: 'a', phase: 'review' }, { name: 'b', phase: 'review' }] }, true), 'reviewer', 'two review seats: not guessed')
  const result = derive(entry)
  assert.deepEqual(result.reviewSeats, ['auditor'])
  assert.deepEqual(Object.keys(result.reviewRules || {}).filter((k) => k !== 'auditor'), [])
  // the review prompt reads the lane's token file
  const item = itemFixture({ short: 'aaaaaaaa', stages: [planner, { ...implAfter, enters: true }], reviewSeats: ['auditor'] })
  const plan = planFixture({ items: [item], serverRunId: SERVER_RUN_ID, project: { scratchDir: '/scratch/run' } })
  const doc = { contract: 'run-wave/plan-doc-v1', args: plan, meta: {} }
  assert.ok(execLib.reviewPrompt(loadWaveCore(), doc, 'aaaaaaaa').includes('/scratch/run/tokens/aaaaaaaa.auditor.token'))
  const plain = itemFixture({ short: 'aaaaaaaa', stages: [planner, { ...implAfter, enters: true }] })
  const plainDoc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [plain], serverRunId: SERVER_RUN_ID, project: { scratchDir: '/scratch/run' } }), meta: {} }
  assert.ok(execLib.reviewPrompt(loadWaveCore(), plainDoc, 'aaaaaaaa').includes('/scratch/run/tokens/aaaaaaaa.reviewer.token'), 'dogfood fixtures keep reviewer')
})

test('AC8: the dogfood schema keeps the reviewer lane on both paths', () => {
  assert.deepEqual(derive(dogfood()).reviewSeats, ['reviewer'])
  const old = dogfood({ notes: dogfood().notes.map(({ owningSeat, ...n }) => ({ ...n, seat: owningSeat })) })
  assert.deepEqual(derive(old, OLD_FEATURES).reviewSeats, ['reviewer'])
})

// ---- AC10 --------------------------------------------------------------------------------------------------------

test('AC10: SERVER_DEFAULT_TRANSITIONS equals the server DEFAULT_TRANSITIONS list', () => {
  const src = readFileSync(join(REPO, 'current', 'src', 'main', 'kotlin', 'io', 'github', 'jpicklyk', 'mcptask', 'current', 'model', 'schema', 'SeatDefinition.kt'), 'utf8')
  const m = /val DEFAULT_TRANSITIONS: List<String> = listOf\(([^)]*)\)/.exec(src)
  assert.ok(m, 'the server line is found')
  const list = [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])
  assert.deepEqual([...SERVER_DEFAULT_TRANSITIONS], list)
  assert.deepEqual(list, ['block', 'hold'])
})
