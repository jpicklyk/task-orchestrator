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
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none',
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
      const out = call(doc, `implementer-a${n}`, { cause })
      assert.equal(out.ok, true, JSON.stringify(out))
      assert.equal(out.cause, cause)
      assert.equal(out.effort, effort, `${cause} a${n}`)
      assert.equal(out.ceilingReached, ceiling, `${cause} a${n}`)
      assert.equal(out.result.items[0].reseats[0].effort, effort)
      assert.equal(out.result.items[0].reseats[0].cause, cause)
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
  assert.equal(call(doc, 'implementer-a2', { cause: 'review' }).prompt.includes(line), true)
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
  writeFileSync(resultPath, JSON.stringify(makeResult(doc)))
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
