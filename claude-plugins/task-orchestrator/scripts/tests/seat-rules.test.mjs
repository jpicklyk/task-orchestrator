// Seat-declared rules, plugin half (item b02b8bdf / U3, G8). Scenarios S6-S10 match the item's frozen
// `test-plan` note. Oracles: task-scope AC2 (a stage's rules are the note-skill rules then the seat's declared rules, each
// once; the seat's output class supplies defaults only when the seat declares nothing), D2 (a declared rule that is not served
// is dropped with the degradation text "seat rule <key> not served (<seat>)"), D3 (a reviewer seat's declared rules reach the
// review lane and the review prompt after the reviewer protocol key) and the run-planner and run-exec declarations supplied to the seat.
// BLIND test author: no implementation source was opened. Node 22, node:test, no dependencies.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { deriveStages } from '../run-planner-lib.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { loadCore, planFixture, itemFixture } from './workflow-harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const WORKFLOW = join(PLUGIN, 'workflows', 'implement-wave.js')
const CLI = join(PLUGIN, 'scripts', 'run-planner.mjs')
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'

const served = (...keys) => keys.map((key) => ({ key, rulesVersion: '1' }))
const SERVED = served('commit-discipline', 'note-rule', 'x-rule', 'y-rule', 'planning-sweeps', 'review-cited-claims')

function candidate(role = 'queue') {
  return {
    id: 'b0000000-0000-4000-8000-000000000001',
    short: 'b0000000',
    title: 'Item b0000000',
    priority: 'high',
    complexity: 2,
    type: 'scratch-seat',
    role,
    parentId: null,
    traits: [],
    hasChildren: false,
    source: role === 'queue' ? 'ready' : 'work',
  }
}

/** A seat-aware schema entry; seatsOverride replaces the seat list, notesOverride the notes. */
function schema({ seats, notes } = {}) {
  return {
    status: 'ok',
    type: 'scratch-seat',
    configFingerprint: 'fp-seat-rules',
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

const derive = (entry, rulesServed = SERVED, cand = candidate()) =>
  deriveStages(cand, entry, { rulesServed, noteOwners: [] })
const stageOf = (result, seat) => result.stages.find((s) => s.seat === seat)
const rulesOf = (result, seat) => (stageOf(result, seat) || {}).rules ?? []
const degradationsOf = (result) => (result.degradations ?? []).map(String)

// ---- S6: note-skill rules first, then the seat's declared rules, once each --------------------

test('S6: a planner owning a note whose skill is a served rule gets [note rule, seat rules] in that order', () => {
  const entry = schema({
    notes: [{ key: 'diagnosis', role: 'queue', required: true, seat: 'planner', skill: 'note-rule' }],
    seats: [
      { name: 'planner', phase: 'queue', rules: ['x-rule'] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  const result = derive(entry)
  assert.deepEqual(rulesOf(result, 'planner'), ['note-rule', 'x-rule'])
  // Fixture condition: planning-sweeps and review-cited-claims are served, so a stage that kept the
  // planner-v1 defaults beside a declared list would fail the deepEqual above.
  assert.deepEqual(rulesOf(result, 'implementer'), [], 'the implementer owns no note rule and declares none')
})

test('S6: a rule that is both a note skill and a declared seat rule appears once', () => {
  const entry = schema({
    notes: [{ key: 'diagnosis', role: 'queue', required: true, seat: 'planner', skill: 'x-rule' }],
    seats: [
      { name: 'planner', phase: 'queue', rules: ['x-rule'] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  assert.deepEqual(rulesOf(derive(entry), 'planner'), ['x-rule'])
})

test('S6 probe: duplicate keys inside the declared list collapse and keep their first-order position', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue', rules: ['y-rule', 'x-rule', 'y-rule'] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  assert.deepEqual(rulesOf(derive(entry), 'planner'), ['y-rule', 'x-rule'])
})

// ---- S7: a declared rule that is not served ---------------------------------------------------

test('S7: a declared seat rule that is not served is absent from the stage and degrades with the stated text', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue', rules: ['x-rule', 'ghost-rule'] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  const result = derive(entry)
  assert.deepEqual(rulesOf(result, 'planner'), ['x-rule'])
  assert.ok(degradationsOf(result).some((d) => d.includes('seat rule ghost-rule not served (planner)')), JSON.stringify(result.degradations))
  assert.equal(degradationsOf(result).some((d) => d.includes('x-rule')), false, 'a served rule raises no degradation')
  // Twin: served, the same declaration yields both rules and no degradation.
  const twin = derive(entry, [...SERVED, ...served('ghost-rule')])
  assert.deepEqual(rulesOf(twin, 'planner'), ['x-rule', 'ghost-rule'])
  assert.equal(degradationsOf(twin).some((d) => d.includes('seat rule')), false)
})

test('S7 probe: a served key differing only in case does not satisfy a declared key', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue', rules: ['x-rule'] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  const result = derive(entry, served('commit-discipline', 'X-Rule'))
  assert.deepEqual(rulesOf(result, 'planner'), [])
  assert.ok(degradationsOf(result).some((d) => d.includes('seat rule x-rule not served (planner)')))
})

// ---- S8: defaults by output class -------------------------------------------------------------

test('S8: a seat-aware planner that declares no rules gets the planner defaults, other seats get none', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true },
      { name: 'orchestrator', phase: 'work', after: ['implementer'] },
      { name: 'test-author', phase: 'work', after: ['implementer'] },
    ],
  })
  const result = derive(entry)
  assert.deepEqual(rulesOf(result, 'planner'), ['planning-sweeps', 'review-cited-claims'])
  assert.deepEqual(rulesOf(result, 'implementer'), [])
  assert.deepEqual(rulesOf(result, 'test-author'), [])
})

test('S8: the implicit planner of a seat-less schema gets the planner defaults', () => {
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
  const result = derive(entry)
  assert.deepEqual(rulesOf(result, 'planner'), ['planning-sweeps', 'review-cited-claims'])
  assert.deepEqual(rulesOf(result, 'implementer'), [])
})

test('S8: unserved defaults are dropped silently, and an explicit empty list means no rules', () => {
  const entry = schema()
  const unserved = derive(entry, served('commit-discipline'))
  assert.deepEqual(rulesOf(unserved, 'planner'), [])
  assert.equal(degradationsOf(unserved).some((d) => d.includes('seat rule')), false, JSON.stringify(unserved.degradations))
  const emptyDeclared = derive(
    schema({
      seats: [
        { name: 'planner', phase: 'queue', rules: [] },
        { name: 'implementer', phase: 'work', enters: true },
      ],
    })
  )
  assert.deepEqual(rulesOf(emptyDeclared, 'planner'), [], 'declared empty overrides the defaults even though they are served')
})

test('S8: a reviewer seat that declares no rules gets the review default when it is served', () => {
  const entry = schema({
    notes: [
      { key: 'diagnosis', role: 'queue', required: true, seat: 'planner' },
      { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer' },
    ],
    seats: [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true },
      { name: 'reviewer', phase: 'review' },
    ],
  })
  assert.deepEqual(derive(entry).reviewRules, { reviewer: ['review-cited-claims'] })
  assert.equal(derive(entry, served('commit-discipline')).reviewRules ?? null, null, 'unserved default -> no review rules')
})

// ---- S9: the seat prompt's Keys line ----------------------------------------------------------

function keysOf(prompt) {
  const m = /^[ \t]*Keys:[^\n]*/m.exec(prompt)
  assert.ok(m, "the seat prompt carries a line starting with 'Keys:'")
  return m[0].replace(/^[ \t]*Keys:\s*/, '').replace(/\.\s*$/, '').split(/\s*,\s*/).filter(Boolean)
}

function promptFor(entry, seat, rulesServed = SERVED) {
  const result = derive(entry, rulesServed)
  const stage = stageOf(result, seat)
  assert.ok(stage, `${seat} stage exists`)
  const item = itemFixture({ short: 'b0000000', stages: result.stages })
  const plan = planFixture({ items: [item], rootId: 'root-b0', baseSha: 'e77c3e95' })
  return { prompt: loadCore(WORKFLOW).seatPrompt(plan, item, stage, {}), stage }
}

test('S9: a writing seat Keys line is protocol key, commit-discipline, then the stage rules, in that order', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true, rules: ['x-rule', 'y-rule'] },
    ],
  })
  const { prompt, stage } = promptFor(entry, 'implementer')
  assert.equal(stage.writes, true, 'fixture: the implementer writes')
  const keys = keysOf(prompt)
  assert.match(keys[0], /^protocol\./)
  assert.deepEqual(keys.slice(1), ['commit-discipline', 'x-rule', 'y-rule'])
})

test('S9: a stage rule equal to commit-discipline is listed once', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true, rules: ['commit-discipline', 'x-rule'] },
    ],
  })
  const keys = keysOf(promptFor(entry, 'implementer').prompt)
  assert.deepEqual(keys.slice(1), ['commit-discipline', 'x-rule'])
  assert.equal(keys.filter((k) => k === 'commit-discipline').length, 1)
})

test('S9: a read-only seat Keys line has no commit-discipline and carries its rules', () => {
  const entry = schema({
    seats: [
      { name: 'planner', phase: 'queue', rules: ['x-rule'] },
      { name: 'implementer', phase: 'work', enters: true },
    ],
  })
  const { prompt, stage } = promptFor(entry, 'planner')
  assert.equal(stage.writes, false, 'fixture: the planner is read-only')
  const keys = keysOf(prompt)
  assert.match(keys[0], /^protocol\./)
  assert.deepEqual(keys.slice(1), ['x-rule'])
})

// ---- S10: reviewer seat rules reach the review lane and the review prompt ---------------------

const A = 'aaaaaaaa-1111-4111-8111-111111111111'
const CHECKLIST = { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer', skill: 'review-quality' }
const SEATS = (reviewerRules) => [
  { name: 'planner', phase: 'queue' },
  { name: 'implementer', phase: 'work', enters: true },
  { name: 'orchestrator', phase: 'work' },
  reviewerRules === undefined ? { name: 'reviewer', phase: 'review' } : { name: 'reviewer', phase: 'review', rules: reviewerRules },
]

function cli(args, input) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', input: input === undefined ? undefined : JSON.stringify(input) })
}

function reviewFixture({ reviewerRules, servedKeys }) {
  const dir = mkdtempSync(join(tmpdir(), 'seat-rules-'))
  const snap = JSON.parse(readFileSync(join(HERE, 'fixtures', 'run-planner', 'independent-two.json'), 'utf8'))
  snap.rulesServed = served('commit-discipline', ...servedKeys)
  const entry = snap.schemas[A]
  entry.notes = [
    { key: 'specification', role: 'queue', required: true, seat: 'planner' },
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
    { key: 'session-tracking', role: 'work', required: true, seat: 'orchestrator' },
    CHECKLIST,
  ]
  entry.seats = SEATS(reviewerRules)
  entry.dispatchBySeat = { review: { reviewer: { agent: 'task-orchestrator:reviewer', model: 'opus', effort: 'high' } } }
  const planRes = cli(['plan', '--now', '2026-09-28T12:00:00Z', '--scratchpad', join(dir, 'sp')], snap)
  assert.equal(planRes.status, 0, planRes.stderr)
  const planPath = join(dir, 'plan.json')
  writeFileSync(planPath, planRes.stdout)
  const bound = cli(['bind-run', '--in', planPath, '--server-run', SERVER_RUN])
  assert.equal(bound.status, 0, bound.stderr)
  const boundPath = join(dir, 'bound.json')
  writeFileSync(boundPath, bound.stdout)
  const snapPath = join(dir, 'snapshot.json')
  writeFileSync(snapPath, JSON.stringify(snap))
  const doc = JSON.parse(bound.stdout)
  const args = cli(['review-args', '--plan', boundPath, '--snapshot', snapPath, '--items', 'aaaaaaaa'])
  assert.equal(args.status, 0, args.stderr + args.stdout)
  const reviewArgs = JSON.parse(args.stdout)
  return {
    doc,
    planItem: doc.args.items.find((i) => i.short === 'aaaaaaaa'),
    lane: reviewArgs.items[0].stages.find((s) => (s.notes || []).includes('review-checklist')),
  }
}

const keysOfReview = (text) => {
  const line = text.split('\n').find((l) => l.includes('Keys:'))
  assert.ok(line, 'the review prompt lists its rule keys on a Keys line')
  return line.slice(line.indexOf('Keys:') + 5).replace(/[.\s]+$/, '').split(',').map((k) => k.trim())
}

test('S10: a reviewer seat declaring a served rule yields plan item reviewRules, a lane with rules, and a prompt Keys [protocol.read-only-agent, x-rule]', () => {
  const fx = reviewFixture({ reviewerRules: ['x-rule'], servedKeys: ['x-rule'] })
  assert.deepEqual(fx.planItem.reviewRules, { reviewer: ['x-rule'] })
  assert.deepEqual(fx.lane.rules, ['x-rule'])
  const text = execLib.reviewPrompt(loadWaveCore(), fx.doc, 'aaaaaaaa')
  // Oracle R1/R4/D4: the reviewer's own protocol key, then the declared list; review-scoping is a default, replaced by it.
  assert.deepEqual(keysOfReview(text), ['protocol.read-only-agent', 'x-rule'])
})

test('S10: with no review rules there is no reviewRules key, no lane rules member, and the prompt lists only the protocol key', () => {
  const fx = reviewFixture({ reviewerRules: undefined, servedKeys: [] })
  assert.equal(Object.hasOwn(fx.planItem, 'reviewRules'), false)
  assert.equal(Object.hasOwn(fx.lane, 'rules'), false)
  const text = execLib.reviewPrompt(loadWaveCore(), fx.doc, 'aaaaaaaa')
  assert.deepEqual(keysOfReview(text), ['protocol.read-only-agent'])
})

test('S10 probe: a reviewer rule that is not served leaves no reviewRules key and no lane rules', () => {
  const fx = reviewFixture({ reviewerRules: ['ghost-rule'], servedKeys: [] })
  assert.equal(Object.hasOwn(fx.planItem, 'reviewRules'), false)
  assert.equal(Object.hasOwn(fx.lane, 'rules'), false)
})

test('S10 probe: a reviewer declaring an empty list has no review rules even when review-cited-claims is served', () => {
  const fx = reviewFixture({ reviewerRules: [], servedKeys: ['review-cited-claims'] })
  assert.equal(Object.hasOwn(fx.planItem, 'reviewRules'), false)
  assert.equal(Object.hasOwn(fx.lane, 'rules'), false)
})
