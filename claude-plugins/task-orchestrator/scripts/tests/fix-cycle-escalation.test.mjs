// Fix-cycle effort escalation and failure-loop cost tracking (item 29fe3b41). Oracle: the item's
// task-scope (AC1-AC3) and plans/model-effort-policy.md "Failed test or review: one level higher"
// and "Tracking what the failure loop costs"; levels and ceiling come from dispatch-policy.json.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { deriveStages } from '../run-planner-lib.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { formatExtraSeats, parseExtraSeats } from '../provenance-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const RUN_PLANNER = join(HERE, '..', 'run-planner.mjs')
const SHORT = 'aaaaaaaa'
const RUN_ID = 'r-test-0001'
const PRE = 'c'.repeat(40)
const DECL = 'export function f(x)'

const PLANNER_OUT = {
  proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
  decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none', sweep: [],
}
const IMPL_OUT = {
  mainFilesChanged: ['src/x.js'], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}
const DECL_OUT = { declarations: DECL, harnessPointers: 'ptr', gaps: 'none' }

function makeDoc({ implEffort = 'medium', authorEffort } = {}) {
  const list = stages.bugFixLike()
  const impl = list.find((s) => s.seat === 'implementer')
  impl.dispatch = { agent: 'task-orchestrator:implementer', model: 'sonnet', effort: implEffort }
  if (authorEffort) list.find((s) => s.seat === 'test-author').dispatch = { model: 'sonnet', effort: authorEffort }
  const item = itemFixture({ short: SHORT, stages: list })
  return { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
}

function makeResult(doc) {
  const item = doc.args.items[0]
  return {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: item.short, status: 'done', reason: 'ok',
      stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })),
      outputs: { planner: PLANNER_OUT, implementer: IMPL_OUT, 'declarations-extractor': DECL_OUT },
    }],
    refused: [], deferred: [],
  }
}

const core = loadWaveCore()
const call = (doc, seat, opts) => execLib.reseat(core, doc, makeResult(doc), SHORT, seat, { pre: PRE, ...opts })

// A ledger row as an earlier reseat left it. cause undefined omits the key (an old row); null keeps a null cause.
// No effort or step: rows written before the step field must still be counted.
function ledgerRow(seat, cause) {
  const m = /^(.+)-a(\d+)$/.exec(seat)
  const row = { seat, baseSeat: m[1], cycle: Number(m[2]), model: 'sonnet', tokens: 'unknown', pre: PRE }
  if (cause !== undefined) row.cause = cause
  return row
}
function seeded(doc, rows) {
  const result = makeResult(doc)
  result.items[0].reseats = rows
  return result
}
const callWith = (doc, rows, seat, opts) => execLib.reseat(core, doc, seeded(doc, rows), SHORT, seat, { pre: PRE, ...opts })
const rowOf = (out, seat) => out.result.items[0].reseats.find((r) => r.seat === seat)
// n-1 earlier rows of one cause for base-a1..a(n-1): the n-th failure of that seat.
const earlier = (n, cause, base = 'implementer') => Array.from({ length: n - 1 }, (_, i) => ledgerRow(`${base}-a${i + 1}`, cause))

// ---- AC1: cause is required and only review/test escalate, capped at the ceiling ----------------

test('AC1: reseat without a cause, or with an unknown one, throws invalid args', () => {
  const doc = makeDoc()
  for (const cause of [undefined, '', 'fail', 'Review']) {
    assert.throws(() => call(doc, 'implementer-a1', { cause }), (e) => /^invalid args: cause /.test(e.message), String(cause))
  }
})

test('AC1: a review or test cause escalates an implementer one level per cycle, capped at xhigh', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  for (const cause of ['review', 'test']) {
    const rows = [[1, 'high', false], [2, 'xhigh', true], [3, 'xhigh', true], [7, 'xhigh', true]]
    for (const [n, effort, ceiling] of rows) {
      // fact 10: the n-th failure of the seat is step n; the n-1 earlier rows are the same cause
      const out = callWith(doc, earlier(n, cause), `implementer-a${n}`, { cause })
      assert.equal(out.ok, true, JSON.stringify(out))
      assert.equal(out.cause, cause)
      assert.equal(out.effort, effort, `${cause} a${n}`)
      assert.equal(out.ceilingReached, ceiling, `${cause} a${n}`)
      assert.equal(rowOf(out, `implementer-a${n}`).effort, effort)
      assert.equal(rowOf(out, `implementer-a${n}`).cause, cause)
    }
  }
})

test('AC1: a test-author base escalates from its own dispatch effort', () => {
  const doc = makeDoc({ authorEffort: 'low' })
  const out = call(doc, 'test-author-a1', { cause: 'test', declarations: DECL })
  assert.equal(out.ok, true, JSON.stringify(out))
  assert.equal(out.effort, 'medium')
  assert.equal(out.ceilingReached, false)
})

test('AC1: carry-in and breach keep the base effort and never reach the ceiling', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  for (const cause of ['carry-in', 'breach']) {
    const out = call(doc, 'implementer-a3', { cause })
    assert.equal(out.effort, 'medium', cause)
    assert.equal(out.ceilingReached, false, cause)
    assert.equal(out.result.items[0].reseats[0].cause, cause)
  }
})

test('AC1: a planner base is not escalated by a review cause (only implementer and test author)', () => {
  const doc = makeDoc()
  const planner = doc.args.items[0].stages.find((s) => s.seat === 'planner')
  planner.dispatch = { model: 'opus', effort: 'high' }
  const out = call(doc, 'planner-a2', { cause: 'review' })
  assert.equal(out.effort, 'high')
  assert.equal(out.ceilingReached, false)
})

test('AC1: an effort above the ceiling is never lowered; the base effort falls back to the policy class', () => {
  const max = call(makeDoc({ implEffort: 'max' }), 'implementer-a1', { cause: 'review' })
  assert.equal(max.effort, 'max')
  assert.equal(max.ceilingReached, true)
  const doc = makeDoc()
  delete doc.args.items[0].stages.find((s) => s.seat === 'implementer').dispatch.effort
  const fallback = call(doc, 'implementer-a1', { cause: 'review' })
  assert.equal(fallback.effort, 'high', 'implementation class medium, one level up')
})

test('AC1: the stop-and-report line is added only when an escalated cycle runs at the escalation ceiling', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const line = execLib.CEILING_STOP_LINE
  assert.equal(line, 'When the checks pass, stop and report; do not start further review rounds.')
  assert.equal(call(doc, 'implementer-a1', { cause: 'review' }).prompt.includes(line), false)
  assert.equal(callWith(doc, earlier(2, 'review'), 'implementer-a2', { cause: 'review' }).prompt.includes(line), true)
  const xhighBase = makeDoc({ implEffort: 'xhigh' })
  assert.equal(call(xhighBase, 'implementer-a1', { cause: 'carry-in' }).prompt.includes(line), false, 'not escalated')
})

test('AC1: a replay keeps the first recorded effort and cause; an older row gains them', () => {
  const doc = makeDoc()
  const first = call(doc, 'implementer-a1', { cause: 'review' })
  const again = execLib.reseat(core, doc, first.result, SHORT, 'implementer-a1', { cause: 'carry-in', pre: PRE, tokens: 10 })
  const row = again.result.items[0].reseats[0]
  assert.equal(again.result.items[0].reseats.length, 1)
  assert.equal(row.cause, 'review')
  assert.equal(row.effort, 'high')
  const old = makeResult(doc)
  old.items[0].reseats = [{ seat: 'implementer-a1', baseSeat: 'implementer', cycle: 1, model: 'sonnet', tokens: 'unknown', pre: PRE }]
  const filled = execLib.reseat(core, doc, old, SHORT, 'implementer-a1', { cause: 'test', pre: PRE })
  assert.equal(filled.result.items[0].reseats[0].effort, 'high')
  assert.equal(filled.result.items[0].reseats[0].cause, 'test')
})

test('AC1: the stop-and-report line follows the policy ceiling, not a literal level', () => {
  const policy = JSON.parse(readFileSync(join(HERE, '..', '..', 'dispatch-policy.json'), 'utf8'))
  policy.escalation.ceiling = 'high'
  const dir = mkdtempSync(join(tmpdir(), 'fix-ceiling-'))
  const policyPath = join(dir, 'policy.json')
  writeFileSync(policyPath, JSON.stringify(policy))
  const line = execLib.CEILING_STOP_LINE
  const doc = makeDoc({ implEffort: 'medium' })
  const a1 = call(doc, 'implementer-a1', { cause: 'review', policyPath })
  assert.equal(a1.effort, 'high')
  assert.equal(a1.prompt.includes(line), true, 'high is the lowered ceiling')
  const a2 = call(doc, 'implementer-a2', { cause: 'review', policyPath })
  assert.equal(a2.effort, 'high')
  assert.equal(a2.prompt.includes(line), true)
})

function cli(args) {
  return spawnSync(process.execPath, [RUN_PLANNER, ...args], { encoding: 'utf8' })
}

test('AC1 (CLI): --cause is required, unknown values exit 2, and the output carries effort, cause and ceilingReached', () => {
  const doc = makeDoc()
  const dir = mkdtempSync(join(tmpdir(), 'fix-cycle-'))
  const planPath = join(dir, 'plan.json')
  const resultPath = join(dir, 'result.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(resultPath, JSON.stringify(seeded(doc, earlier(2, 'review'))))
  const base = ['reseat', '--plan', planPath, '--run', RUN_ID, '--result', resultPath, '--item', SHORT, '--seat', 'implementer-a2', '--pre', PRE]
  for (const extra of [[], ['--cause', 'oops']]) {
    const res = cli([...base, ...extra])
    assert.equal(res.status, 2, res.stderr)
    assert.equal(JSON.parse(res.stderr).error, 'invalid args')
  }
  const ok = cli([...base, '--cause', 'review'])
  assert.equal(ok.status, 0, ok.stderr)
  const out = JSON.parse(ok.stdout)
  assert.equal(out.effort, 'xhigh')
  assert.equal(out.cause, 'review')
  assert.equal(out.ceilingReached, true)
})

// ---- AC2: extra-seats five-field rows round-trip; three-field rows still parse -------------------

test('AC2: a five-field row round-trips and an old three-field row parses with effort and cause absent', () => {
  const rows = [
    { seat: 'implementer-a1', model: 'sonnet', tokens: 12345, effort: 'high', cause: 'review' },
    { seat: 'test-author-a1', model: 'sonnet', tokens: 'unknown', effort: 'medium', cause: 'test' },
  ]
  const line = formatExtraSeats(rows)
  assert.equal(line, 'extra-seats=implementer-a1:sonnet:12345:high:review,test-author-a1:sonnet:unknown:medium:test')
  assert.deepEqual(parseExtraSeats(`x\n${line}`), rows)
  assert.deepEqual(parseExtraSeats('x\nextra-seats=reviewer-a1:opus:7'), [{ seat: 'reviewer-a1', model: 'opus', tokens: 7 }])
  assert.equal(formatExtraSeats([{ seat: 'reviewer-a1', model: 'opus', tokens: 7 }]), 'extra-seats=reviewer-a1:opus:7')
  assert.equal(formatExtraSeats([{ seat: 'x-a1', model: 'opus', tokens: 1, cause: 'breach' }]), 'extra-seats=x-a1:opus:1:unknown:breach')
})

function provenanceDoc() {
  const item = itemFixture({
    short: SHORT,
    stages: [{ seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: { model: 'sonnet', effort: 'medium' }, output: 'implementer-v1' }],
    worktree: '/repo/.claude/worktrees/x',
  })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item], runId: 'r-test-0301' }), meta: { inRunEdges: [] } }
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: 'r-test-0301', planDocSlug: 'run/x',
    items: [{
      id: item.id, short: SHORT, status: 'done', reason: 'ok', outputs: {},
      stages: [{ seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] }],
      reseats: [
        { seat: 'implementer-a1', baseSeat: 'implementer', cycle: 1, model: 'sonnet', tokens: 'unknown', effort: 'high', cause: 'review', pre: PRE },
        { seat: 'implementer-a2', baseSeat: 'implementer', cycle: 2, model: 'sonnet', tokens: 'unknown', effort: 'xhigh', cause: 'review', pre: PRE },
      ],
    }],
    refused: [], deferred: [],
  }
  return { doc, result, item }
}

test('AC2: provenance carries ledger effort and cause, takes tokens and meters only from the transcript, and warns on every gap', () => {
  const { doc, result, item } = provenanceDoc()
  const warnings = []
  const meta = { [`implementer-a1:${SHORT}`]: { model: 'sonnet', usage: { input: 1, cacheWrite: 2, cacheRead: 3, output: 4, tokens: 7 } } }
  const extraSeats = [{ seat: 'reviewer-a1', model: 'opus', tokens: 99 }]
  const out = execLib.provenance({ core, doc, result, method: 'A', turns: 1, meta, extraSeats, item: SHORT, warnings })
  const line2 = out[item.id].split('\n')[1]
  assert.equal(line2, 'extra-seats=implementer-a1:sonnet:7:high:review:1/2/3/4,implementer-a2:sonnet:unknown:xhigh:review,reviewer-a1:opus:unknown')
  assert.deepEqual(warnings, [
    `provenance: ${SHORT} seat implementer has no transcript meters (a gap, not zero)`,
    `provenance: ${SHORT} seat implementer-a2 has no transcript meters (a gap, not zero)`,
    `provenance: ${SHORT} seat reviewer-a1 tokens 99 ignored; transcript meters are the only source`,
    `provenance: ${SHORT} seat reviewer-a1 has no transcript meters (a gap, not zero)`,
  ])
})

test('AC2: a flag row for a ledger seat takes the flag model, ignores its tokens and keeps the ledger effort and cause', () => {
  const { doc, result, item } = provenanceDoc()
  const out = execLib.provenance({ core, doc, result, method: 'A', turns: 1, extraSeats: [{ seat: 'implementer-a1', model: 'opus', tokens: 5 }], item: SHORT })
  assert.ok(out[item.id].split('\n')[1].startsWith('extra-seats=implementer-a1:opus:unknown:high:review,'), out[item.id])
})

// ---- AC3: the transcript's four usage meters are summed per seat ---------------------------------

function fakeIo(files) {
  return {
    walkMeta: () => Object.keys(files).filter((p) => p.endsWith('.meta.json')).map((path) => ({ path, mtimeMs: 1 })),
    readInput: (p) => (files[p] ? JSON.parse(files[p]) : null),
    readText: (p) => (p in files ? files[p] : null),
  }
}

const assistant = (id, usage, extra = {}) => JSON.stringify({ type: 'assistant', effort: 'high', message: { id, model: 'claude-sonnet-5-5', usage }, ...extra })

test('AC3: collectDispatchMeta sums input, cache write, cache read and output once per message id', () => {
  const u1 = { input_tokens: 10, cache_creation_input_tokens: 200, cache_read_input_tokens: 3000, output_tokens: 40 }
  const u1final = { input_tokens: 10, cache_creation_input_tokens: 200, cache_read_input_tokens: 3000, output_tokens: 45 }
  const u2 = { input_tokens: 5, cache_creation_input_tokens: 0, cache_read_input_tokens: 4000, output_tokens: 60 }
  const transcript = [
    JSON.stringify({ type: 'user', message: { content: 'go' } }),
    assistant('msg_1', u1),
    assistant('msg_1', u1final),
    assistant('msg_2', u2),
    'not json "assistant"',
  ].join('\n')
  const files = {
    '/t/agent-1.meta.json': JSON.stringify({ description: `implementer-a1:${SHORT} [sonnet/high]`, model: 'sonnet' }),
    '/t/agent-1.jsonl': transcript,
    '/t/agent-2.meta.json': JSON.stringify({ description: `implementer:${SHORT}`, model: 'sonnet' }),
    '/t/agent-2.jsonl': JSON.stringify({ type: 'user' }),
  }
  const meta = execLib.collectDispatchMeta({ io: fakeIo(files), roots: ['/t'], labels: [`implementer:${SHORT}`] })
  const fix = meta[`implementer-a1:${SHORT}`]
  assert.ok(fix, 'a fix-cycle label of a wanted seat is matched: ' + JSON.stringify(Object.keys(meta)))
  assert.deepEqual(fix.usage, { input: 15, cacheWrite: 200, cacheRead: 7000, output: 105, tokens: 320 })
  assert.equal(meta[`implementer:${SHORT}`].usage, undefined, 'no usage lines -> usage absent')
})

test('AC3: sumUsage treats a missing meter as zero', () => {
  assert.deepEqual(execLib.sumUsage([{ output_tokens: 3 }, null, { input_tokens: 2, cache_read_input_tokens: 'x' }]),
    { input: 2, cacheWrite: 0, cacheRead: 0, output: 3, tokens: 5 })
})

// ---- Fact 10 (item 46b7aadb): the step counts earlier review or test failures of the same base -----
// Oracle: 416134d6 interface-contract fact 10 and task-scope D4. step = 1 + earlier rows (lower cycle,
// another seat id, same base seat) whose cause is review, test or absent; effort = escalate(base, step).
// Levels from dispatch-policy.json: implementer base medium -> step 1 high, step 2 xhigh (ceiling).

test('S1: the first failure of a seat is step 1: high, ceiling not reached, step recorded', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const out = callWith(doc, [], 'implementer-a1', { cause: 'review' })
  assert.equal(out.ok, true, JSON.stringify(out))
  assert.equal(out.effort, 'high')
  assert.equal(out.ceilingReached, false)
  assert.equal(out.step, 1)
  assert.equal(rowOf(out, 'implementer-a1').step, 1)
})

test('S2: an a2 review after an a1 review is step 2: xhigh, ceiling reached, stop line', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const out = callWith(doc, [ledgerRow('implementer-a1', 'review')], 'implementer-a2', { cause: 'review' })
  assert.equal(out.effort, 'xhigh')
  assert.equal(out.ceilingReached, true)
  assert.equal(out.step, 2)
  assert.equal(out.prompt.includes(execLib.CEILING_STOP_LINE), true)
})

test('S3: an a2 review after an a1 carry-in does not count the carry-in: step 1, high, no stop line', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const out = callWith(doc, [ledgerRow('implementer-a1', 'carry-in')], 'implementer-a2', { cause: 'review' })
  assert.equal(out.effort, 'high')
  assert.equal(out.ceilingReached, false)
  assert.equal(out.step, 1)
  assert.equal(out.prompt.includes(execLib.CEILING_STOP_LINE), false)
  const afterBreach = callWith(doc, [ledgerRow('implementer-a1', 'breach')], 'implementer-a2', { cause: 'review' })
  assert.equal(afterBreach.effort, 'high', 'a breach row does not count either')
  assert.equal(afterBreach.step, 1)
})

test('S4: an earlier row with no cause (absent or null) counts as a failure', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  for (const cause of [undefined, null]) {
    const out = callWith(doc, [ledgerRow('implementer-a1', cause)], 'implementer-a2', { cause: 'review' })
    assert.equal(out.effort, 'xhigh', String(cause))
    assert.equal(out.ceilingReached, true, String(cause))
    assert.equal(out.step, 2, String(cause))
  }
})

test('S5: a test-author base (low) steps from its own earlier rows: carry-in none, test one, no row none', () => {
  const doc = makeDoc({ authorEffort: 'low' })
  const run = (rows) => callWith(doc, rows, 'test-author-a2', { cause: 'review', declarations: DECL })
  const afterCarry = run([ledgerRow('test-author-a1', 'carry-in')])
  assert.equal(afterCarry.ok, true, JSON.stringify(afterCarry))
  assert.equal(afterCarry.effort, 'medium')
  assert.equal(afterCarry.step, 1)
  const afterTest = run([ledgerRow('test-author-a1', 'test')])
  assert.equal(afterTest.effort, 'high')
  assert.equal(afterTest.step, 2)
  const alone = run([])
  assert.equal(alone.effort, 'medium')
  assert.equal(alone.step, 1)
})

test('S6: a row of another base seat is not counted', () => {
  const implDoc = makeDoc({ implEffort: 'medium' })
  const out = callWith(implDoc, [ledgerRow('test-author-a1', 'review')], 'implementer-a2', { cause: 'review' })
  assert.equal(out.effort, 'high')
  assert.equal(out.step, 1)
  const authorDoc = makeDoc({ authorEffort: 'low' })
  const reverse = callWith(authorDoc, [ledgerRow('implementer-a1', 'review')], 'test-author-a2', { cause: 'review', declarations: DECL })
  assert.equal(reverse.effort, 'medium')
  assert.equal(reverse.step, 1)
})

test('S7: a later cycle is not an earlier failure, whatever the ledger order', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const out = callWith(doc, [ledgerRow('implementer-a3', 'review')], 'implementer-a2', { cause: 'review' })
  assert.equal(out.effort, 'high')
  assert.equal(out.step, 1)
  const authorDoc = makeDoc({ authorEffort: 'low' })
  const reversed = callWith(authorDoc, [ledgerRow('test-author-a3', 'review'), ledgerRow('test-author-a1', 'review')], 'test-author-a2', { cause: 'review', declarations: DECL })
  assert.equal(reversed.effort, 'high', 'only a1 is earlier: step 2 from low')
  assert.equal(reversed.step, 2)
})

test('S8: an a3 test after an a1 test and an a2 breach counts only the test', () => {
  const rows = (base) => [ledgerRow(`${base}-a1`, 'test'), ledgerRow(`${base}-a2`, 'breach')]
  const impl = callWith(makeDoc({ implEffort: 'medium' }), rows('implementer'), 'implementer-a3', { cause: 'test' })
  assert.equal(impl.effort, 'xhigh')
  assert.equal(impl.ceilingReached, true)
  const author = callWith(makeDoc({ authorEffort: 'low' }), rows('test-author'), 'test-author-a3', { cause: 'test', declarations: DECL })
  assert.equal(author.effort, 'high', 'step 2 from low; counting the breach would give xhigh')
  assert.equal(author.step, 2)
})

test('S9: a replay returns the recorded effort and step even when an earlier row is added later', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const first = execLib.reseat(core, doc, makeResult(doc), SHORT, 'implementer-a2', { cause: 'review', pre: PRE })
  assert.equal(first.effort, 'high')
  const result = first.result
  result.items[0].reseats.unshift(ledgerRow('implementer-a1', 'review'))
  const again = execLib.reseat(core, doc, result, SHORT, 'implementer-a2', { cause: 'review', pre: PRE })
  assert.equal(again.effort, 'high')
  assert.equal(again.step, 1)
  assert.equal(again.result.items[0].reseats.filter((r) => r.seat === 'implementer-a2').length, 1)
  assert.equal(rowOf(again, 'implementer-a2').effort, 'high')
})

test('S10: a carry-in after a review keeps the base effort, and the row still records its step', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const out = callWith(doc, [ledgerRow('implementer-a1', 'review')], 'implementer-a2', { cause: 'carry-in' })
  assert.equal(out.effort, 'medium')
  assert.equal(out.ceilingReached, false)
  assert.equal(out.step, 2)
  assert.equal(rowOf(out, 'implementer-a2').step, 2)
})

test('S11: the ledger row keeps cycle as the seat suffix, gains step, and has no findings key without the flag', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const rows = [ledgerRow('implementer-a1', 'review'), ledgerRow('implementer-a2', 'carry-in')]
  const out = callWith(doc, rows, 'implementer-a3', { cause: 'review' })
  const row = rowOf(out, 'implementer-a3')
  assert.equal(row.cycle, 3)
  assert.equal(row.step, 2)
  assert.equal(row.baseSeat, 'implementer')
  assert.equal('findings' in row, false)
  assert.equal(out.result.items[0].reseats.length, 3)
})

test('S12 (CLI): implementer-a2 review after an a1 review exits 0 with xhigh and ceilingReached', () => {
  const doc = makeDoc()
  const dir = mkdtempSync(join(tmpdir(), 'fix-step-'))
  const planPath = join(dir, 'plan.json')
  const resultPath = join(dir, 'result.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(resultPath, JSON.stringify(seeded(doc, [ledgerRow('implementer-a1', 'review')])))
  const res = cli(['reseat', '--plan', planPath, '--run', RUN_ID, '--result', resultPath, '--item', SHORT, '--seat', 'implementer-a2', '--pre', PRE, '--cause', 'review'])
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.effort, 'xhigh')
  assert.equal(out.ceilingReached, true)
})

// ---- Fact 8 (noteLimits) on derived stages -------------------------------------------------------
// Oracle: fact 8 and task-scope D5: noteLimits over the stage's owned notes whose maxLength is an integer
// >= 1; emitted only when non-empty; never on the inserted extractor.

const CTX = { rulesServed: [{ key: 'declarations-extractor', rulesVersion: '1' }], noteOwners: [] }
const candidate = (over) => ({ id: 'dddddddd-1111-4111-8111-111111111111', short: 'dddddddd', title: 'D', priority: 'high', complexity: 1, type: 'scratch-x', role: 'queue', parentId: null, traits: [], hasChildren: false, source: 'ready', ...over })

function seatSchema(notes) {
  return {
    status: 'ok', type: 'scratch-x', configFingerprint: 'fp1', configSource: 'per-root',
    notes,
    seats: [
      { name: 'planner', phase: 'queue' },
      { name: 'implementer', phase: 'work', enters: true },
      { name: 'orchestrator', phase: 'work', after: ['implementer'] },
      { name: 'test-author', phase: 'work', after: ['implementer'], readsExclude: ['implementation-notes', 'session-tracking'] },
    ],
    dispatch: { work: { agent: 'task-orchestrator:implementer' } },
    dispatchBySeat: { work: { 'test-author': { agent: null, model: 'sonnet' } } },
  }
}

const FULL_NOTES = [
  { key: 'task-scope', role: 'queue', required: true, seat: 'planner', skill: 'spec-quality', maxLength: 12000 },
  { key: 'amendments', role: 'queue', required: false, seat: 'planner', maxLength: 4000 },
  { key: 'test-plan', role: 'queue', required: true, seat: 'planner', skill: 'test-author', maxLength: 3500 },
  { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer', maxLength: 3000 },
  { key: 'session-tracking', role: 'work', required: true, seat: 'orchestrator', maxLength: 2000 },
  { key: 'test-manifest', role: 'work', required: true, seat: 'test-author', skill: 'test-author', maxLength: 3000 },
]

const stageOf = (result, seat) => result.stages.find((s) => s.seat === seat)

test('S13: a seat-aware schema gives each stage the limits of the notes it owns; the extractor gets none', () => {
  const result = deriveStages(candidate(), seatSchema(FULL_NOTES), CTX)
  assert.deepEqual(stageOf(result, 'planner').noteLimits, { 'task-scope': 12000, amendments: 4000, 'test-plan': 3500 })
  assert.deepEqual(stageOf(result, 'implementer').noteLimits, { 'implementation-notes': 3000 })
  assert.deepEqual(stageOf(result, 'test-author').noteLimits, { 'test-manifest': 3000 })
  const extractor = stageOf(result, 'declarations-extractor')
  assert.ok(extractor, 'fixture condition: the extractor is inserted')
  assert.equal('noteLimits' in extractor, false)
  assert.equal(result.stages.some((s) => s.noteLimits && 'session-tracking' in s.noteLimits), false, 'an orchestrator-owned note is on no stage')
})

test('S14: only an integer maxLength of 1 or more becomes a limit', () => {
  const variants = [
    { key: 'n-one', maxLength: 1 }, { key: 'n-zero', maxLength: 0 }, { key: 'n-neg', maxLength: -1 },
    { key: 'n-frac', maxLength: 2.5 }, { key: 'n-str', maxLength: '4000' }, { key: 'n-null', maxLength: null }, { key: 'n-abs' },
  ]
  const notes = variants.map((v) => ({ role: 'work', required: true, seat: 'implementer', ...v }))
  const result = deriveStages(candidate(), seatSchema(notes), CTX)
  assert.deepEqual(stageOf(result, 'implementer').noteLimits, { 'n-one': 1 })
  const onlyBad = notes.filter((n) => n.key !== 'n-one')
  const none = deriveStages(candidate(), seatSchema(onlyBad), CTX)
  assert.equal('noteLimits' in stageOf(none, 'implementer'), false, 'empty limits are not emitted')
})

test('S15: seat-less planner and implementer stages carry limits; a schema-free item and unlimited notes carry none', () => {
  const schema = {
    status: 'ok', type: 'scratch-x', configFingerprint: 'fp1', configSource: 'per-root',
    notes: [
      { key: 'specification', role: 'queue', required: true, maxLength: 5000 },
      { key: 'session-tracking', role: 'work', required: true, maxLength: 2000 },
    ],
  }
  const result = deriveStages(candidate(), schema, CTX)
  assert.deepEqual(stageOf(result, 'planner').noteLimits, { specification: 5000 })
  assert.deepEqual(stageOf(result, 'implementer').noteLimits, { 'session-tracking': 2000 })
  const unlimited = deriveStages(candidate(), { ...schema, notes: schema.notes.map(({ maxLength, ...n }) => n) }, CTX)
  assert.equal(unlimited.stages.some((s) => 'noteLimits' in s), false)
  const snap = JSON.parse(readFileSync(join(HERE, 'fixtures', 'run-planner', 'schema-free.json'), 'utf8'))
  const free = deriveStages(snap.candidates[0], snap.schemas[snap.candidates[0].id], { rulesServed: snap.rulesServed, noteOwners: [], profile: snap.profile })
  assert.equal(free.schemaFree, true)
  assert.equal(free.stages.some((s) => 'noteLimits' in s), false)
})

// ---- Fact 8 on the reseat FIX CYCLE line ----------------------------------------------------------

const limitSentence = (key, n) => ` ${key} is limited to ${n} characters; rewrite it in full within that limit.`
const fixLine = (prompt) => prompt.split('\n').find((l) => l.startsWith('FIX CYCLE A'))
function docWith(patch) {
  const doc = makeDoc()
  for (const [seat, fields] of Object.entries(patch)) Object.assign(doc.args.items[0].stages.find((s) => s.seat === seat), fields)
  return doc
}

test('S16: the FIX CYCLE line gains one limit sentence for the base stage note it names', () => {
  const planner = docWith({ planner: { notes: ['amendments'], noteLimits: { amendments: 4000 } } })
  const p = call(planner, 'planner-a1', { cause: 'review' })
  assert.equal(p.ok, true, JSON.stringify(p))
  assert.ok(fixLine(p.prompt).endsWith(limitSentence('amendments', 4000)), fixLine(p.prompt))

  const impl = docWith({
    planner: { notes: ['amendments'], noteLimits: { amendments: 4000 } },
    implementer: { notes: ['implementation-notes'], noteLimits: { 'implementation-notes': 3000 } },
  })
  const i = call(impl, 'implementer-a1', { cause: 'review' })
  const iLine = fixLine(i.prompt)
  assert.ok(iLine.includes("ruling A1 in the item's amendments note"), iLine)
  assert.ok(iLine.endsWith(limitSentence('implementation-notes', 3000)), iLine)
  assert.equal(iLine.includes(' amendments is limited'), false, 'the planner limit is not the implementer base stage limit')

  const author = docWith({ 'test-author': { notes: ['test-manifest'], noteLimits: { 'test-manifest': 3000 } } })
  const a = call(author, 'test-author-a1', { cause: 'review', declarations: DECL })
  assert.equal(a.ok, true, JSON.stringify(a))
  assert.ok(fixLine(a.prompt).endsWith(limitSentence('test-manifest', 3000)), fixLine(a.prompt))
})

test('S17: no sentence without a limit for the named key, or for a plan without the field', () => {
  const none = docWith({ implementer: { notes: ['implementation-notes'] } })
  assert.equal(fixLine(call(none, 'implementer-a1', { cause: 'review' }).prompt).includes('is limited to'), false)
  const otherKey = docWith({ implementer: { notes: ['implementation-notes'], noteLimits: { 'unrelated-note': 500 } } })
  assert.equal(fixLine(call(otherKey, 'implementer-a1', { cause: 'review' }).prompt).includes('is limited to'), false)
  const otherStage = docWith({
    planner: { notes: ['implementation-notes'], noteLimits: { 'implementation-notes': 900 } },
    implementer: { notes: ['implementation-notes'] },
  })
  assert.equal(fixLine(call(otherStage, 'implementer-a1', { cause: 'review' }).prompt).includes('is limited to'), false, 'only the base stage limits count')
})

// ---- Fact 6 (inputNotes) -----------------------------------------------------------------------

test('S18: a queue item gives only the test-author stage inputNotes, the test-plan key', () => {
  const result = deriveStages(candidate(), seatSchema(FULL_NOTES), CTX)
  assert.deepEqual(stageOf(result, 'test-author').inputNotes, ['test-plan'])
  for (const seat of ['planner', 'implementer', 'declarations-extractor']) {
    assert.ok(stageOf(result, seat), seat)
    assert.equal('inputNotes' in stageOf(result, seat), false, seat)
  }
})

test('S19: a resumed work-role item whose queue stages are skipped still gets inputNotes', () => {
  const result = deriveStages(candidate({ role: 'work', source: 'work' }), seatSchema(FULL_NOTES), CTX)
  assert.equal(result.stages.some((s) => s.seat === 'planner'), false, 'fixture condition: the queue stage is skipped')
  assert.deepEqual(stageOf(result, 'test-author').inputNotes, ['test-plan'])
})

test('S20: no queue note with the test-author skill gives no key; two give both in schema order', () => {
  const noQueue = FULL_NOTES.filter((n) => n.key !== 'test-plan')
  const none = deriveStages(candidate(), seatSchema(noQueue), CTX)
  assert.ok(stageOf(none, 'test-author'))
  assert.equal('inputNotes' in stageOf(none, 'test-author'), false, 'the work-role test-manifest alone is not an input note')
  const two = [
    { key: 'zeta-plan', role: 'queue', required: true, seat: 'planner', skill: 'test-author' },
    { key: 'task-scope', role: 'queue', required: true, seat: 'planner', skill: 'spec-quality' },
    { key: 'plain-queue', role: 'queue', required: true, seat: 'planner' },
    { key: 'alpha-plan', role: 'queue', required: true, seat: 'planner', skill: 'test-author' },
    ...FULL_NOTES.filter((n) => n.role === 'work'),
  ]
  const both = deriveStages(candidate(), seatSchema(two), CTX)
  assert.deepEqual(stageOf(both, 'test-author').inputNotes, ['zeta-plan', 'alpha-plan'])
})

// ---- Fact 9 (--findings) --------------------------------------------------------------------------

const FINDINGS_PREFIX = (ids) => `FIX CYCLE A1: work only blocking findings ${ids} in the item's review register. Commit your owned paths`

test('S21: findings replace the ruling clause, with or without an amending planner note', () => {
  for (const patch of [{ planner: { notes: ['amendments'] } }, {}]) {
    const doc = docWith(patch)
    const out = call(doc, 'implementer-a1', { cause: 'review', findings: ['B1'] })
    assert.equal(out.ok, true, JSON.stringify(out))
    const line = fixLine(out.prompt)
    assert.ok(line.startsWith(FINDINGS_PREFIX('B1')), line)
    for (const gone of ['ruling A1', 'amendments note', '## Amendment']) assert.equal(line.includes(gone), false, gone)
  }
  const owned = docWith({ implementer: { notes: ['implementation-notes'] } })
  assert.ok(fixLine(call(owned, 'implementer-a1', { cause: 'review', findings: ['B1'] }).prompt).includes('Rewrite implementation-notes in full so it describes the final state'), 'the rest of the line stays')
})

test('S22: several findings join with a comma and a space; the row records the id array verbatim', () => {
  const doc = makeDoc()
  const out = call(doc, 'implementer-a1', { cause: 'review', findings: ['B1', 'B3'] })
  assert.ok(fixLine(out.prompt).startsWith(FINDINGS_PREFIX('B1, B3')), fixLine(out.prompt))
  assert.deepEqual(rowOf(out, 'implementer-a1').findings, ['B1', 'B3'])
  const lower = call(doc, 'implementer-a1', { cause: 'review', findings: ['b1'] })
  assert.deepEqual(rowOf(lower, 'implementer-a1').findings, ['b1'], 'ids keep their case')
})

test('S22b: a findings cycle escalates by the step rule like any review cycle', () => {
  const doc = makeDoc({ implEffort: 'medium' })
  const out = callWith(doc, [ledgerRow('implementer-a1', 'review')], 'implementer-a2', { cause: 'review', findings: ['B2'] })
  assert.equal(out.effort, 'xhigh')
  assert.equal(out.step, 2)
})

test('S23: findings with a cause other than review, or an empty list or id, throw invalid args', () => {
  const doc = makeDoc()
  for (const cause of ['test', 'carry-in', 'breach']) {
    assert.throws(() => call(doc, 'implementer-a1', { cause, findings: ['B1'] }), (e) => /^invalid args: /.test(e.message), cause)
  }
  for (const findings of [[], [''], ['B1', '']]) {
    assert.throws(() => call(doc, 'implementer-a1', { cause: 'review', findings }), (e) => /^invalid args: /.test(e.message), JSON.stringify(findings))
  }
})

test('S24 (CLI): --findings splits on commas and trims; an empty id or a non-review cause exits 2', () => {
  const doc = makeDoc()
  const dir = mkdtempSync(join(tmpdir(), 'fix-findings-'))
  const planPath = join(dir, 'plan.json')
  const resultPath = join(dir, 'result.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(resultPath, JSON.stringify(makeResult(doc)))
  const base = ['reseat', '--plan', planPath, '--run', RUN_ID, '--result', resultPath, '--item', SHORT, '--seat', 'implementer-a1', '--pre', PRE]
  const ok = cli([...base, '--cause', 'review', '--findings', ' B1 , B3'])
  assert.equal(ok.status, 0, ok.stderr)
  assert.deepEqual(JSON.parse(ok.stdout).result.items[0].reseats[0].findings, ['B1', 'B3'])
  for (const extra of [['--cause', 'review', '--findings', 'B1,'], ['--cause', 'test', '--findings', 'B1']]) {
    const res = cli([...base, ...extra])
    assert.equal(res.status, 2, res.stderr)
    assert.equal(JSON.parse(res.stderr).error, 'invalid args')
  }
})
