// Coverage for auditActors() accepting a fix-cycle / breach-replacement actor '<seat>-a<n>' as the same
// lane as '<seat>' (item 7d7dec13). Scenario ids S1-S10 match the item's frozen `test-plan` note; the
// probes (P-*) are additions recorded in the test-manifest.
// Oracles: task-scope acceptance criteria AC1-AC5 and the reseat grammar '<stageSeat>-a<n>' with
// n >= 1 and no leading zero (reseat doc comment, post-run.md) - never "what the code returns".
// This suite never reads run-exec-lib.mjs or run-planner.mjs source text; it uses only the exported
// auditActors surface, the run-planner CLI as a subprocess, and workflow-harness.mjs.
// run-exec-lib.mjs is imported as a namespace so a missing export fails each test individually.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { planFixture, itemFixture } from './workflow-harness.mjs'
import * as execLib from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const RUN_PLANNER = join(HERE, '..', 'run-planner.mjs')

const SHORT = 's37fix01'
const RUN_ID = 'r-test-aud1'

const PLANNER_STAGE = { seat: 'planner', phase: 'queue', notes: ['specification'], writes: false, dispatch: {}, output: 'planner-v1' }
const IMPL_STAGE = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: ['implementation-notes'], dispatch: {}, output: 'implementer-v1' }

function makeDoc({ short = SHORT, runId = RUN_ID, list = [PLANNER_STAGE, IMPL_STAGE] } = {}) {
  const item = itemFixture({ short, stages: list })
  const args = planFixture({ items: [item], runId })
  return { doc: { contract: 'run-wave/plan-doc-v1', args, meta: {} }, item }
}

function stageRow(seat, status = 'done') {
  return { seat, status, reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] }
}

// `reseats` undefined => the row carries no `reseats` field at all.
function makeResult(item, reseats, { statuses = {} } = {}) {
  const row = {
    id: item.id, short: item.short, status: 'done', reason: 'ok',
    stages: item.stages.map((s) => stageRow(s.seat, statuses[s.seat] ?? 'done')),
    outputs: {},
  }
  if (reseats !== undefined) row.reseats = reseats
  return {
    contract: 'implement-wave/result-v1', started: true, runId: RUN_ID, planDocSlug: 'run/x',
    items: [row], refused: [], deferred: [],
  }
}

const base = (item, key, seat, short = SHORT, runId = RUN_ID) => ({ itemId: item.id, key, actorId: `${seat}:${short}:${runId}` })
const SPEC_OK = (item) => base(item, 'specification', 'planner')
const rowOf = (audit, short = SHORT) => audit.items.find((i) => i.short === short)

function runCli(args) {
  return spawnSync(process.execPath, [RUN_PLANNER, ...args], { encoding: 'utf8' })
}

function cliActors(doc, observed, result) {
  const dir = mkdtempSync(join(tmpdir(), 'audit-actors-reseat-'))
  const planPath = join(dir, 'plan.json')
  const notesPath = join(dir, 'notes.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(notesPath, JSON.stringify(observed))
  const args = ['actors', '--plan', planPath, '--notes', notesPath]
  if (result) {
    const resultPath = join(dir, 'result.json')
    writeFileSync(resultPath, JSON.stringify(result))
    args.push('--result', resultPath)
  }
  return runCli(args)
}

// ---- Happy ----

test('S1: no result - implementer-a2 on implementation-notes matches the implementer lane (AC1, AC3)', () => {
  const { doc, item } = makeDoc()
  const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` }]
  const audit = execLib.auditActors(doc, observed)
  const row = rowOf(audit)
  assert.equal(audit.ok, true)
  assert.equal(row.ok, true)
  assert.deepEqual(row.mismatched, [])
  assert.deepEqual(row.missing, [])
})

test('S2: result whose ledger lists implementer-a2 under baseSeat implementer is accepted (AC2)', () => {
  const { doc, item } = makeDoc()
  const result = makeResult(item, [{ seat: 'implementer-a2', baseSeat: 'implementer', cycle: 2 }])
  const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` }]
  const audit = execLib.auditActors(doc, observed, undefined, { result })
  assert.equal(audit.ok, true)
  assert.deepEqual(rowOf(audit).mismatched, [])
  assert.deepEqual(rowOf(audit).missing, [])
})

test('S3: planner reseat - specification under planner-a1 matches the planner lane, no result (AC1: any non-reviewer base)', () => {
  const { doc, item } = makeDoc()
  const observed = [
    { itemId: item.id, key: 'specification', actorId: `planner-a1:${SHORT}:${RUN_ID}` },
    base(item, 'implementation-notes', 'implementer'),
  ]
  const audit = execLib.auditActors(doc, observed)
  assert.equal(audit.ok, true)
  assert.deepEqual(rowOf(audit).mismatched, [])
})

// ---- Failure ----

test('S4: ledger lists only implementer-a1; observed implementer-a2 is mismatched with the unchanged shape (AC2)', () => {
  const { doc, item } = makeDoc()
  const result = makeResult(item, [{ seat: 'implementer-a1', baseSeat: 'implementer', cycle: 1 }])
  const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` }]
  const audit = execLib.auditActors(doc, observed, undefined, { result })
  assert.equal(audit.ok, false)
  assert.equal(rowOf(audit).ok, false)
  assert.deepEqual(rowOf(audit).mismatched, [
    { key: 'implementation-notes', expected: `implementer:${SHORT}:${RUN_ID}`, actual: `implementer-a2:${SHORT}:${RUN_ID}` },
  ])
  assert.deepEqual(rowOf(audit).missing, [])
})

test('S4b: ledger entry with the right seat but a different baseSeat does not vouch (AC2 is seat AND baseSeat)', () => {
  const { doc, item } = makeDoc()
  const result = makeResult(item, [{ seat: 'implementer-a2', baseSeat: 'planner', cycle: 2 }])
  const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` }]
  const audit = execLib.auditActors(doc, observed, undefined, { result })
  assert.equal(audit.ok, false)
  assert.deepEqual(rowOf(audit).mismatched, [
    { key: 'implementation-notes', expected: `implementer:${SHORT}:${RUN_ID}`, actual: `implementer-a2:${SHORT}:${RUN_ID}` },
  ])
})

test('S5: cross-lane - implementation-notes under planner-a1 is mismatched with and without a ledger that vouches planner-a1 (AC4)', () => {
  const { doc, item } = makeDoc()
  const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `planner-a1:${SHORT}:${RUN_ID}` }]
  const expectedMismatch = [{ key: 'implementation-notes', expected: `implementer:${SHORT}:${RUN_ID}`, actual: `planner-a1:${SHORT}:${RUN_ID}` }]

  const noResult = execLib.auditActors(doc, observed)
  assert.equal(noResult.ok, false)
  assert.deepEqual(rowOf(noResult).mismatched, expectedMismatch)

  const result = makeResult(item, [{ seat: 'planner-a1', baseSeat: 'planner', cycle: 1 }])
  const withLedger = execLib.auditActors(doc, observed, undefined, { result })
  assert.equal(withLedger.ok, false)
  assert.deepEqual(rowOf(withLedger).mismatched, expectedMismatch)
})

test('S6: malformed reseat suffixes on the implementer lane are mismatched, no result (AC4)', () => {
  const badSeats = ['implementer-a0', 'implementer-a01', 'implementer-aX', 'implementer-a', 'implementer-A2', 'implementer-a1-a2', 'implementer-a2 ']
  for (const seat of badSeats) {
    const { doc, item } = makeDoc()
    const actual = `${seat}:${SHORT}:${RUN_ID}`
    const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: actual }]
    const audit = execLib.auditActors(doc, observed)
    assert.equal(audit.ok, false, `expected mismatch for ${JSON.stringify(seat)}`)
    assert.deepEqual(
      rowOf(audit).mismatched,
      [{ key: 'implementation-notes', expected: `implementer:${SHORT}:${RUN_ID}`, actual }],
      `mismatch shape for ${JSON.stringify(seat)}`,
    )
  }
})

test('S7: right reseat seat with a foreign short or a foreign runId is mismatched (AC4)', () => {
  for (const actual of [`implementer-a2:OTHER:${RUN_ID}`, `implementer-a2:${SHORT}:r-other`]) {
    const { doc, item } = makeDoc()
    const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: actual }]
    const audit = execLib.auditActors(doc, observed)
    assert.equal(audit.ok, false, actual)
    assert.deepEqual(
      rowOf(audit).mismatched,
      [{ key: 'implementation-notes', expected: `implementer:${SHORT}:${RUN_ID}`, actual }],
      actual,
    )
  }
})

// ---- Edge ----

test('S8: result row without a reseats field falls back to the grammar; reseats: [] is authoritative and rejects (AC3 vs AC2)', () => {
  const { doc, item } = makeDoc()
  const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a1:${SHORT}:${RUN_ID}` }]

  const absent = execLib.auditActors(doc, observed, undefined, { result: makeResult(item, undefined) })
  assert.equal(absent.ok, true)
  assert.deepEqual(rowOf(absent).mismatched, [])

  const empty = execLib.auditActors(doc, observed, undefined, { result: makeResult(item, []) })
  assert.equal(empty.ok, false)
  assert.deepEqual(rowOf(empty).mismatched, [
    { key: 'implementation-notes', expected: `implementer:${SHORT}:${RUN_ID}`, actual: `implementer-a1:${SHORT}:${RUN_ID}` },
  ])
})

test('S9: base ids still match; a required note absent stays missing beside a reseat id; D7 all-deferred item stays skipped (AC5)', () => {
  // (a) every base id matches
  const a = makeDoc()
  const baseObserved = [SPEC_OK(a.item), base(a.item, 'implementation-notes', 'implementer')]
  const baseAudit = execLib.auditActors(a.doc, baseObserved)
  assert.equal(baseAudit.ok, true)
  assert.deepEqual(rowOf(baseAudit).mismatched, [])
  assert.deepEqual(rowOf(baseAudit).missing, [])

  // (b) one note under a reseat id, another required note never written
  const twoNotes = { ...IMPL_STAGE, notes: ['implementation-notes', 'session-tracking'] }
  const b = makeDoc({ list: [PLANNER_STAGE, twoNotes] })
  const partial = [SPEC_OK(b.item), { itemId: b.item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` }]
  const partialResult = makeResult(b.item, [{ seat: 'implementer-a2', baseSeat: 'implementer', cycle: 2 }])
  const partialAudit = execLib.auditActors(b.doc, partial, undefined, { result: partialResult })
  assert.equal(partialAudit.ok, false)
  assert.deepEqual(rowOf(partialAudit).missing, ['session-tracking'])
  assert.deepEqual(rowOf(partialAudit).mismatched, [])
  // same fixture without a result: the absent required note is still missing (the reseat id itself is S1/AC3)
  assert.deepEqual(rowOf(execLib.auditActors(b.doc, partial)).missing, ['session-tracking'])

  // (c) all stages deferred in the result: skipped, even with a reseat-ledger present and nothing observed
  const c = makeDoc()
  const deferred = makeResult(c.item, [{ seat: 'implementer-a2', baseSeat: 'implementer', cycle: 2 }], {
    statuses: { planner: 'deferred', implementer: 'deferred' },
  })
  const skipAudit = execLib.auditActors(c.doc, [], undefined, { result: deferred })
  assert.equal(skipAudit.ok, true)
  assert.equal(rowOf(skipAudit).skipped, true)
  assert.deepEqual(rowOf(skipAudit).missing, [])
  assert.deepEqual(rowOf(skipAudit).mismatched, [])
})

test('S10: CLI actors --result exits 0 for a vouching ledger and 3 for a ledger that omits the seat (AC2 via CLI)', () => {
  const { doc, item } = makeDoc()
  const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` }]

  const good = cliActors(doc, observed, makeResult(item, [{ seat: 'implementer-a2', baseSeat: 'implementer', cycle: 2 }]))
  assert.equal(good.status, 0, good.stderr)
  assert.equal(JSON.parse(good.stdout).ok, true)

  const bad = cliActors(doc, observed, makeResult(item, [{ seat: 'implementer-a1', baseSeat: 'implementer', cycle: 1 }]))
  assert.equal(bad.status, 3, bad.stderr)
  assert.equal(JSON.parse(bad.stdout).ok, false)
})

// ---- Probes ----

test('P-boundary: n of 1, 9, 10 and 100 are accepted by the grammar (no result)', () => {
  for (const n of [1, 9, 10, 100]) {
    const { doc, item } = makeDoc()
    const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a${n}:${SHORT}:${RUN_ID}` }]
    const audit = execLib.auditActors(doc, observed)
    assert.equal(audit.ok, true, `a${n}`)
    assert.deepEqual(rowOf(audit).mismatched, [], `a${n}`)
  }
})

test('P-separator-casing: other separators, casing, leading space, missing tail and extra tail segments are mismatched', () => {
  const badActors = [
    `implementer_a2:${SHORT}:${RUN_ID}`,
    `implementer-a-2:${SHORT}:${RUN_ID}`,
    `Implementer-a2:${SHORT}:${RUN_ID}`,
    ` implementer-a2:${SHORT}:${RUN_ID}`,
    `implementer-a2:${SHORT}:${RUN_ID}:extra`,
    'implementer-a2',
    `implementer-a2:${SHORT}`,
  ]
  for (const actual of badActors) {
    const { doc, item } = makeDoc()
    const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: actual }]
    const audit = execLib.auditActors(doc, observed)
    assert.equal(audit.ok, false, JSON.stringify(actual))
    assert.deepEqual(
      rowOf(audit).mismatched,
      [{ key: 'implementation-notes', expected: `implementer:${SHORT}:${RUN_ID}`, actual }],
      JSON.stringify(actual),
    )
  }
})

test('P-ledger-shape: duplicate entries and unordered entries are accepted; cycle/model/tokens/pre values are ignored', () => {
  const { doc, item } = makeDoc()
  const observed = [
    { itemId: item.id, key: 'specification', actorId: `planner-a1:${SHORT}:${RUN_ID}` },
    { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` },
  ]
  const result = makeResult(item, [
    { seat: 'implementer-a3', baseSeat: 'implementer', cycle: 3 },
    { seat: 'implementer-a2', baseSeat: 'implementer', cycle: 99, model: 'zzz', tokens: -5, pre: 'not-a-sha' },
    { seat: 'implementer-a2', baseSeat: 'implementer' },
    { seat: 'planner-a1', baseSeat: 'planner' },
  ])
  const audit = execLib.auditActors(doc, observed, undefined, { result })
  assert.equal(audit.ok, true)
  assert.deepEqual(rowOf(audit).mismatched, [])
})

test('P-ledger-scope: another item row ledger does not vouch; a null reseats value counts as no ledger (grammar fallback)', () => {
  const { doc, item } = makeDoc()
  const observed = [SPEC_OK(item), { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` }]

  const foreign = makeResult(item, [{ seat: 'implementer-a1', baseSeat: 'implementer', cycle: 1 }])
  foreign.items.push({
    id: 'item-other', short: 'other001', status: 'done', reason: 'ok', stages: [], outputs: {},
    reseats: [{ seat: 'implementer-a2', baseSeat: 'implementer', cycle: 2 }],
  })
  const foreignAudit = execLib.auditActors(doc, observed, undefined, { result: foreign })
  assert.equal(foreignAudit.ok, false)
  assert.deepEqual(rowOf(foreignAudit).mismatched, [
    { key: 'implementation-notes', expected: `implementer:${SHORT}:${RUN_ID}`, actual: `implementer-a2:${SHORT}:${RUN_ID}` },
  ])

  const nullLedger = execLib.auditActors(doc, observed, undefined, { result: makeResult(item, null) })
  assert.equal(nullLedger.ok, true)
  assert.deepEqual(rowOf(nullLedger).mismatched, [])
})

test('P-both-lanes: planner and implementer reseat ids vouched by one ledger are both accepted; swapping them is mismatched', () => {
  const { doc, item } = makeDoc()
  const result = makeResult(item, [
    { seat: 'planner-a1', baseSeat: 'planner', cycle: 1 },
    { seat: 'implementer-a2', baseSeat: 'implementer', cycle: 2 },
  ])
  const ok = [
    { itemId: item.id, key: 'specification', actorId: `planner-a1:${SHORT}:${RUN_ID}` },
    { itemId: item.id, key: 'implementation-notes', actorId: `implementer-a2:${SHORT}:${RUN_ID}` },
  ]
  assert.equal(execLib.auditActors(doc, ok, undefined, { result }).ok, true)

  const swapped = [
    { itemId: item.id, key: 'specification', actorId: `implementer-a2:${SHORT}:${RUN_ID}` },
    { itemId: item.id, key: 'implementation-notes', actorId: `planner-a1:${SHORT}:${RUN_ID}` },
  ]
  const audit = execLib.auditActors(doc, swapped, undefined, { result })
  assert.equal(audit.ok, false)
  assert.equal(rowOf(audit).mismatched.length, 2)
})