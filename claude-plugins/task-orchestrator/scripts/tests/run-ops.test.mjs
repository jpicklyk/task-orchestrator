// Coverage for item cac1bffa (P17): the run-ops / bind-run / follow-ups CLI subcommands and the pure
// helpers closeReason / advanceFollowUps. Scenario ids S1-S3, S7-S10, S17 and the probes match the
// item's frozen `test-plan` note; the acceptance walk is task-scope AC12.
//
// BLIND test author: no production source under workflows/, scripts/*.mjs or scripts/lib/ was opened
// by any tool. Oracles are task-scope D1-D7 and AC3/AC8/AC12 plus current/docs/api-reference.md
// (runs_write open/assign/end_seat/close, advance claim/heartbeat/release, the transition result and
// cascade vocabulary). Every expected value is a literal derived from those, never from running the
// code under test. Row shapes the declarations do not give (the runs_read seats rows, the advance
// answer wrapper) are taken from api-reference.md / api-rest.md and recorded in the test-manifest.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadWaveCore } from '../lib/wave-core.mjs'
import {
  CLOSE_REASONS, closeReason, advanceFollowUps, initState, next, stageResult, resultFromState, provenance,
} from '../run-exec-lib.mjs'
import { SNAPSHOT_CONTRACT } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const CLI = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const BASELINE = join(PLUGIN_ROOT, 'scripts', 'lib', 'contract-names-baseline.json')
const NOW = '2026-09-28T12:00:00Z'

const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const ROOT = '00000000-0000-4000-8000-000000000000'
const ANCESTOR = 'cccccccc-3333-4333-8333-333333333333'
const A = 'aaaaaaaa-1111-4111-8111-111111111111'
const B = 'bbbbbbbb-2222-4222-8222-222222222222'

// ---- helpers ----

function runCli(args, { input } = {}) {
  return spawnSync(process.execPath, [CLI, ...args], {
    encoding: 'utf8',
    input: input === undefined ? undefined : typeof input === 'string' ? input : JSON.stringify(input),
  })
}
function loadFixture(name) {
  return JSON.parse(readFileSync(join(FIXTURES, name), 'utf8'))
}
function writeJson(dir, name, value) {
  const p = join(dir, name)
  writeFileSync(p, typeof value === 'string' ? value : JSON.stringify(value))
  return p
}
function hasKeyDeep(value, key) {
  if (Array.isArray(value)) return value.some((v) => hasKeyDeep(v, key))
  if (value && typeof value === 'object') {
    return Object.keys(value).includes(key) || Object.values(value).some((v) => hasKeyDeep(v, key))
  }
  return false
}
function canonPath(p) {
  return String(p).split(String.fromCharCode(92)).join('/').toLowerCase()
}

// A planned (unbound) doc over independent-two with an ancestor and, optionally, a profile runBudget.
function planDoc({ withAncestor = true, runBudget } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'run-ops-'))
  const snap = loadFixture('independent-two.json')
  assert.equal(snap.contract, SNAPSHOT_CONTRACT)
  if (withAncestor) snap.ancestorId = ANCESTOR
  if (runBudget) snap.profile.runBudget = runBudget
  const res = runCli(['plan', '--now', NOW, '--scratchpad', join(dir, 'scratchpad')], { input: snap })
  assert.equal(res.status, 0, res.stderr)
  const doc = JSON.parse(res.stdout)
  const planPath = writeJson(dir, 'plan.json', doc)
  return { dir, doc, planPath }
}
function bind(dir, planPath, serverRun = SERVER_RUN) {
  return runCli(['bind-run', '--in', planPath, '--server-run', serverRun])
}
function boundDoc(opts) {
  const p = planDoc(opts)
  const res = bind(p.dir, p.planPath)
  assert.equal(res.status, 0, res.stderr)
  const bound = JSON.parse(res.stdout)
  const boundPath = writeJson(p.dir, 'bound.json', bound)
  return { ...p, bound, boundPath }
}
function ops(planPath, phase, extra = []) {
  const res = runCli(['run-ops', '--plan', planPath, '--phase', phase, ...extra])
  assert.equal(res.status, 0, res.stderr)
  return JSON.parse(res.stdout)
}
function stageStatus(seat, status) {
  return { seat, status, reason: status, modelReported: 'x', notes: [], commits: { pre: '', post: '' }, files: [] }
}
function resultDoc(overrides = {}) {
  return {
    contract: 'implement-wave/result-v1', started: true, runId: 'r-202609281200-bbbbbbbb', serverRunId: SERVER_RUN,
    items: [
      { id: A, short: 'aaaaaaaa', status: 'done', reason: 'ok', stages: [stageStatus('planner', 'done'), stageStatus('implementer', 'stopped')], outputs: {} },
      { id: B, short: 'bbbbbbbb', status: 'deferred', reason: 'x', stages: [stageStatus('planner', 'deferred')], outputs: {} },
    ],
    refused: [], deferred: [],
    ...overrides,
  }
}
function seatRow(itemId, seat, status) {
  return {
    assignmentId: `asg-${seat}-${itemId || 'coordinator'}`, ...(itemId ? { itemId } : {}), seat, attempt: 1, status,
    assignedAt: '2026-10-16T10:00:00Z', usage: { tokens: 0, costUsd: 0 },
  }
}

// ============================================================================================
// S1 - run-ops open [D1, D4]
// ============================================================================================

test('S1: run-ops open renders one open op - rootId, both item ids, scopeId = ancestorId, label = runId, hints.plan = runId - and no seatToken key', () => {
  const { doc, planPath } = planDoc()
  const out = ops(planPath, 'open')
  assert.equal(out.tool, 'runs_write')
  assert.equal(out.ops.length, 1)
  const op = out.ops[0]
  assert.equal(op.op, 'open')
  assert.equal(op.rootId, ROOT)
  assert.deepEqual([...op.items].sort(), [A, B])
  assert.equal(op.scopeId, ANCESTOR)
  assert.equal(op.label, doc.args.runId)
  assert.equal(op.hints.plan, doc.args.runId)
  assert.equal(hasKeyDeep(out, 'seatToken'), false, 'D1: the output never carries a seatToken key')
})

test('S1 (shape): label <= 64 and title <= 200 chars, hints is a string-to-string map, members are unique (api-reference runs_write open)', () => {
  const { planPath } = planDoc()
  const op = ops(planPath, 'open').ops[0]
  assert.ok(op.label.length <= 64)
  assert.ok(op.title.length <= 200)
  for (const [k, v] of Object.entries(op.hints)) assert.equal(typeof v, 'string', `hints.${k} must be a string`)
  assert.equal(new Set(op.items).size, op.items.length)
})

test('S1 (D4): budget appears only when the profile opts in with runBudget, and carries that budget; a plan without it has no budget key', () => {
  const without = ops(planDoc().planPath, 'open').ops[0]
  assert.equal(Object.hasOwn(without, 'budget'), false, 'fixture condition: the same snapshot without runBudget has no budget')
  const budget = { tokens: 5000000, costUsd: 12.5, attemptsPerSeat: 3, maxActiveSeats: 2 }
  const withBudget = ops(planDoc({ runBudget: budget }).planPath, 'open').ops[0]
  assert.deepEqual(withBudget.budget, budget)
})

test('S1 (no ancestor): a snapshot with ancestorId null renders no scopeId', () => {
  const op = ops(planDoc({ withAncestor: false }).planPath, 'open').ops[0]
  assert.equal(Object.hasOwn(op, 'scopeId'), false)
})

test('PROBE: a plan with zero items exits 2 on run-ops open', () => {
  const { dir, doc } = planDoc()
  const empty = JSON.parse(JSON.stringify(doc))
  empty.args.items = []
  const res = runCli(['run-ops', '--plan', writeJson(dir, 'empty.json', empty), '--phase', 'open'])
  assert.equal(res.status, 2, res.stdout + res.stderr)
})

test('PROBE: run-ops with a missing or unknown --phase exits 2 naming the problem on stderr', () => {
  const { planPath } = planDoc()
  for (const extra of [[], ['--phase', 'nope']]) {
    const res = runCli(['run-ops', '--plan', planPath, ...extra])
    assert.equal(res.status, 2)
    assert.equal(JSON.parse(res.stderr).error, 'invalid args')
  }
})

// ============================================================================================
// S2 - bind-run [D4]
// ============================================================================================

test('S2: bind-run sets args.serverRunId, drops planDocSlug, changes nothing else, and the bound doc validates', () => {
  const { dir, doc, planPath } = planDoc()
  const res = bind(dir, planPath)
  assert.equal(res.status, 0, res.stderr)
  const bound = JSON.parse(res.stdout)
  assert.equal(bound.args.serverRunId, SERVER_RUN)
  assert.equal(Object.hasOwn(bound.args, 'planDocSlug'), false)
  const rest = JSON.parse(JSON.stringify(bound.args))
  delete rest.serverRunId
  assert.deepEqual(rest, doc.args, 'only serverRunId is added')
  assert.deepEqual(bound.meta, doc.meta)
  const v = runCli(['validate'], { input: bound })
  assert.equal(v.status, 0, v.stdout + v.stderr)
})

test('S2 (probe): bind-run rejects a non-UUID --server-run with exit 2 - 8-hex prefix, braces, extra char, empty', () => {
  const { dir, planPath } = planDoc()
  for (const bad of ['8f14e45f', `{${SERVER_RUN}}`, `${SERVER_RUN}0`, 'not-a-uuid', '']) {
    const res = bind(dir, planPath, bad)
    assert.equal(res.status, 2, `${JSON.stringify(bad)} -> exit ${res.status}`)
    assert.equal(JSON.parse(res.stderr).error, 'invalid args')
  }
})

test('S2 (probe): an upper-case UUID is a UUID and binds', () => {
  const { dir, planPath } = planDoc()
  const res = bind(dir, planPath, SERVER_RUN.toUpperCase())
  assert.equal(res.status, 0, res.stderr)
  assert.equal(JSON.parse(res.stdout).args.serverRunId.toLowerCase(), SERVER_RUN)
})

test('S2 (probe): run-ops assign, heartbeat and close on an unbound plan exit 2 (no run to address)', () => {
  const { dir, planPath } = planDoc()
  const usage = writeJson(dir, 'usage.json', { tokens: 1 })
  for (const extra of [['--phase', 'assign'], ['--phase', 'heartbeat', '--usage', usage], ['--phase', 'close', '--reason', 'cancelled']]) {
    const res = runCli(['run-ops', '--plan', planPath, ...extra])
    assert.equal(res.status, 2, `${extra.join(' ')} -> ${res.status} ${res.stdout}`)
  }
})

// ============================================================================================
// S3 - run-ops assign [D2]
// ============================================================================================

test('S3: run-ops assign (Method A, all items) renders exactly four assign ops - 2 items x {planner, implementer} - each carrying the server run id', () => {
  const { bound, boundPath } = boundDoc()
  const out = ops(boundPath, 'assign')
  assert.equal(out.tool, 'runs_write')
  assert.equal(out.ops.length, 4)
  for (const op of out.ops) {
    assert.equal(op.op, 'assign')
    assert.equal(op.runId, SERVER_RUN)
    assert.equal(hasKeyDeep(op, 'seatToken'), false)
  }
  const pairs = out.ops.map((o) => `${o.itemId}:${o.seat}`).sort()
  assert.deepEqual(pairs, [`${A}:implementer`, `${A}:planner`, `${B}:implementer`, `${B}:planner`])
  // token files: one per op, in op order, under <scratchDir>/tokens/<short>.<seat>.token
  assert.equal(out.tokenFiles.length, out.ops.length)
  const scratch = canonPath(bound.args.project.scratchDir)
  out.ops.forEach((op, i) => {
    const short = op.itemId.slice(0, 8)
    assert.equal(canonPath(out.tokenFiles[i]), `${scratch}/tokens/${short}.${op.seat}.token`)
  })
})

test('S3 (B): --item and --seat render exactly one assign op for that stage, with its own token file', () => {
  const { bound, boundPath } = boundDoc()
  const out = ops(boundPath, 'assign', ['--item', 'aaaaaaaa', '--seat', 'implementer'])
  assert.equal(out.ops.length, 1)
  assert.deepEqual(out.ops[0], { op: 'assign', runId: SERVER_RUN, itemId: A, seat: 'implementer' })
  assert.equal(out.tokenFiles.length, 1)
  assert.equal(canonPath(out.tokenFiles[0]), `${canonPath(bound.args.project.scratchDir)}/tokens/aaaaaaaa.implementer.token`)
})

function withExtractorDoc() {
  const { dir, bound } = boundDoc()
  const doc = JSON.parse(JSON.stringify(bound))
  doc.args.items[0].stages.push({
    seat: 'declarations-extractor', phase: 'work', inserted: true, writes: false, notes: [], dispatch: {}, output: 'declarations-v1',
  })
  return { doc, path: writeJson(dir, 'with-extractor.json', doc) }
}

test('S3 (inserted): an inserted declarations-extractor stage is not a schema seat - the bulk assign renders no op for it', () => {
  const { path } = withExtractorDoc()
  const out = ops(path, 'assign')
  assert.equal(out.ops.length, 4)
  assert.equal(out.ops.some((o) => o.seat === 'declarations-extractor'), false)
  assert.equal(out.tokenFiles.some((t) => t.includes('declarations-extractor')), false)
})

test('S3 (probe): asking to assign the inserted extractor seat singly renders no assign op for it (refusal exit 2 or empty ops - the oracle names no exit code)', () => {
  const { doc, path } = withExtractorDoc()
  const res = runCli(['run-ops', '--plan', path, '--phase', 'assign', '--item', doc.args.items[0].short, '--seat', 'declarations-extractor'])
  // JUSTIFIED DISJUNCTION (declared in test-manifest): D2 says an assign for an inserted stage would be
  // not_found kind seat, so the helper must not emit it; the item states no exit code for the refusal.
  if (res.status === 0) {
    assert.deepEqual(JSON.parse(res.stdout).ops, [])
  } else {
    assert.equal(res.status, 2)
  }
})

test('S3 (probe): --item without --seat (and --seat without --item) exits 2', () => {
  const { boundPath } = boundDoc()
  for (const extra of [['--item', 'aaaaaaaa'], ['--seat', 'planner']]) {
    const res = runCli(['run-ops', '--plan', boundPath, '--phase', 'assign', ...extra])
    assert.equal(res.status, 2, extra.join(' '))
  }
})

test('S3 (probe): an unknown item short exits 2', () => {
  const { boundPath } = boundDoc()
  const res = runCli(['run-ops', '--plan', boundPath, '--phase', 'assign', '--item', 'zzzzzzzz', '--seat', 'planner'])
  assert.equal(res.status, 2)
})

// ============================================================================================
// S7 - run-ops heartbeat [D5]
// ============================================================================================

test('S7: heartbeat renders advance ops [claim first member, heartbeat with usage, release] on the first member item', () => {
  const { dir, bound, boundPath } = boundDoc()
  const first = bound.args.items[0].id
  const usage = writeJson(dir, 'usage.json', { tokens: 1200, costUsd: 0.5 })
  const out = ops(boundPath, 'heartbeat', ['--usage', usage])
  assert.equal(out.tool, 'advance')
  assert.deepEqual(out.ops, [
    { op: 'claim', itemId: first },
    { op: 'heartbeat', itemId: first, usage: { tokens: 1200, costUsd: 0.5 } },
    { op: 'release', itemId: first },
  ])
  assert.equal(hasKeyDeep(out, 'seatToken'), false)
})

test('S7 (absent cost): costUsd is omitted from usage when the usage file lacks it, not rendered as null or 0', () => {
  const { dir, bound, boundPath } = boundDoc()
  const first = bound.args.items[0].id
  const usage = writeJson(dir, 'usage.json', { tokens: 1200 })
  const out = ops(boundPath, 'heartbeat', ['--usage', usage])
  assert.deepEqual(out.ops[1], { op: 'heartbeat', itemId: first, usage: { tokens: 1200 } })
})

// ============================================================================================
// S8 - run-ops end-seats [D3]
// ============================================================================================

test('S8: end-seats renders one end_seat per still-active member seat - done for a done stage, failed for a stopped stage - and skips the coordinator row and an ended row', () => {
  const { dir, bound, boundPath } = boundDoc()
  const seats = {
    runId: SERVER_RUN,
    seats: [
      seatRow(null, 'coordinator', 'active'),
      seatRow(A, 'planner', 'active'),
      seatRow(A, 'implementer', 'active'),
      seatRow(B, 'implementer', 'ended'),
    ],
  }
  const result = resultDoc({
    items: [
      { id: A, short: 'aaaaaaaa', status: 'done', reason: 'ok', stages: [stageStatus('planner', 'done'), stageStatus('implementer', 'stopped')], outputs: {} },
      { id: B, short: 'bbbbbbbb', status: 'done', reason: 'ok', stages: [stageStatus('planner', 'done'), stageStatus('implementer', 'done')], outputs: {} },
    ],
  })
  const out = ops(boundPath, 'end-seats', ['--seats', writeJson(dir, 'seats.json', seats), '--result', writeJson(dir, 'result.json', result)])
  assert.equal(out.tool, 'runs_write')
  assert.deepEqual(out.ops.map((o) => o.op), ['end_seat', 'end_seat'])
  assert.deepEqual(out.ops.map((o) => o.outcome), ['done', 'failed'])
  for (const op of out.ops) {
    assert.ok(op.summary === undefined || op.summary.length <= 500, 'summary is at most 500 characters')
    assert.equal(hasKeyDeep(op, 'seatToken'), false)
  }
  const scratch = canonPath(bound.args.project.scratchDir)
  assert.deepEqual(out.tokenFiles.map(canonPath), [`${scratch}/tokens/aaaaaaaa.planner.token`, `${scratch}/tokens/aaaaaaaa.implementer.token`])
})

test('S8 (abandoned): an active seat whose stage is neither done nor stopped (deferred) ends abandoned', () => {
  const { dir, boundPath } = boundDoc()
  const seats = { runId: SERVER_RUN, seats: [seatRow(B, 'planner', 'active')] }
  const out = ops(boundPath, 'end-seats', ['--seats', writeJson(dir, 'seats.json', seats), '--result', writeJson(dir, 'result.json', resultDoc())])
  assert.deepEqual(out.ops.map((o) => o.outcome), ['abandoned'])
})

test('S8 (probe): only a coordinator row renders zero ops (the run can close completed)', () => {
  const { dir, boundPath } = boundDoc()
  const seats = { runId: SERVER_RUN, seats: [seatRow(null, 'coordinator', 'active')] }
  const out = ops(boundPath, 'end-seats', ['--seats', writeJson(dir, 'seats.json', seats), '--result', writeJson(dir, 'result.json', resultDoc())])
  assert.deepEqual(out.ops, [])
})

// ============================================================================================
// S9 - closeReason [D7]
// ============================================================================================

test('S9: CLOSE_REASONS is the api-reference close reason set (budget_exhausted is server-only)', () => {
  assert.deepEqual([...CLOSE_REASONS].sort(), ['abandoned', 'cancelled', 'completed', 'failed'])
})

test('S9: closeReason - started:true completes, started:false fails', () => {
  assert.equal(closeReason({ started: true }), 'completed')
  assert.equal(closeReason({ started: false }), 'failed')
})

test('S9: closeReason - cancelled and abandoned overrides pass through, even over a started:false result', () => {
  assert.equal(closeReason({ started: true }, 'cancelled'), 'cancelled')
  assert.equal(closeReason({ started: true }, 'abandoned'), 'abandoned')
  assert.equal(closeReason({ started: false }, 'cancelled'), 'cancelled')
})

test('S9 (probe): closeReason rejects any other override - failed, completed, budget_exhausted, an unknown word', () => {
  for (const bad of ['failed', 'completed', 'budget_exhausted', 'bogus']) {
    assert.throws(() => closeReason({ started: true }, bad), undefined, `override ${JSON.stringify(bad)}`)
  }
})

test('S9 (CLI): run-ops close renders {op: close, runId, reason} per D7 - completed from a started result, failed from started:false, override cancelled', () => {
  const { dir, boundPath } = boundDoc()
  const started = writeJson(dir, 'started.json', resultDoc({ started: true }))
  const notStarted = writeJson(dir, 'not-started.json', resultDoc({ started: false }))
  assert.deepEqual(ops(boundPath, 'close', ['--result', started]).ops, [{ op: 'close', runId: SERVER_RUN, reason: 'completed' }])
  assert.deepEqual(ops(boundPath, 'close', ['--result', notStarted]).ops, [{ op: 'close', runId: SERVER_RUN, reason: 'failed' }])
  assert.deepEqual(ops(boundPath, 'close', ['--reason', 'cancelled']).ops, [{ op: 'close', runId: SERVER_RUN, reason: 'cancelled' }])
})

test('S9 (CLI probe): run-ops close --reason with anything but cancelled or abandoned exits 2', () => {
  const { boundPath } = boundDoc()
  for (const bad of ['completed', 'failed', 'bogus']) {
    const res = runCli(['run-ops', '--plan', boundPath, '--phase', 'close', '--reason', bad])
    assert.equal(res.status, 2, bad)
  }
})

// ============================================================================================
// S10 - advanceFollowUps [AC8]
// ============================================================================================

// The advance answer: one flat element per ops[] entry, each in the `transition` result shape
// (api-reference: {itemId, previousRole, newRole, trigger, cascades?, unblocked?}; cascade entries
// {itemId, previousRole, targetRole, applied, blocked?, reason?, missing?}; unblocked [{itemId, title}]).
function element(extra = {}) {
  return { ok: true, itemId: 'c1', previousRole: 'work', newRole: 'terminal', trigger: 'complete', ...extra }
}
const CASCADES = [
  { itemId: 'P', previousRole: 'work', targetRole: 'terminal', applied: false, blocked: 'seat' },
  { itemId: 'Q', previousRole: 'work', targetRole: 'terminal', applied: false, blocked: 'gate', missing: [{ key: 'k', role: 'review' }] },
  { itemId: 'R', previousRole: 'review', targetRole: 'terminal', applied: false, blocked: 'gate', reason: 'review-pending' },
  { itemId: 'S', previousRole: 'queue', targetRole: 'work', applied: true },
]

test('S10: advanceFollowUps classifies blocked: seat, blocked: gate, review-pending and unblocked, and an applied cascade is none of them', () => {
  const out = advanceFollowUps({ results: [element({ cascades: CASCADES, unblocked: [{ itemId: 'U', title: 't' }] })] })
  assert.deepEqual(out.seatBlocked, ['P'])
  assert.deepEqual(out.gateBlocked, ['Q'])
  assert.deepEqual(out.reviewPending, ['R'])
  assert.deepEqual(out.unblocked, ['U'])
})

test('S10: the answer may arrive wrapped in data (the tool envelope) - same classification', () => {
  const plain = advanceFollowUps({ results: [element({ cascades: CASCADES, unblocked: [{ itemId: 'U', title: 't' }] })] })
  const wrapped = advanceFollowUps({ ok: true, data: { results: [element({ cascades: CASCADES, unblocked: [{ itemId: 'U', title: 't' }] })] } })
  assert.deepEqual(wrapped, plain)
})

test('S10 (absent arrays): an element with no cascades and no unblocked, and an empty results list, give four empty lists', () => {
  for (const answer of [{ results: [element()] }, { results: [] }]) {
    const out = advanceFollowUps(answer)
    assert.deepEqual([out.unblocked, out.seatBlocked, out.gateBlocked, out.reviewPending], [[], [], [], []])
  }
})

test('S10 (many elements): follow-ups from every element are collected, in element order', () => {
  const out = advanceFollowUps({
    results: [
      element({ itemId: 'c1', cascades: [{ itemId: 'P1', previousRole: 'work', targetRole: 'terminal', applied: false, blocked: 'seat' }] }),
      element({ itemId: 'c2', cascades: [{ itemId: 'P2', previousRole: 'work', targetRole: 'terminal', applied: false, blocked: 'seat' }], unblocked: [{ itemId: 'U2', title: 't' }] }),
    ],
  })
  assert.deepEqual(out.seatBlocked, ['P1', 'P2'])
  assert.deepEqual(out.unblocked, ['U2'])
})

test('S10 (negative): blocked: ownership, dependency, resource and role cascades are not seat-blocked, gate-blocked or review-pending', () => {
  const others = ['ownership', 'dependency', 'resource', 'role'].map((b, i) => ({
    itemId: `O${i}`, previousRole: 'work', targetRole: 'terminal', applied: false, blocked: b,
  }))
  const out = advanceFollowUps({ results: [element({ cascades: others })] })
  assert.deepEqual([out.seatBlocked, out.gateBlocked, out.reviewPending], [[], [], []])
})

test('S10 (CLI): follow-ups --in reads an advance answer file and prints the same classification; an unreadable file exits 2', () => {
  const dir = mkdtempSync(join(tmpdir(), 'run-ops-fu-'))
  const answer = { results: [element({ cascades: CASCADES, unblocked: [{ itemId: 'U', title: 't' }] })] }
  const res = runCli(['follow-ups', '--in', writeJson(dir, 'answer.json', answer)])
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.deepEqual(out.seatBlocked, ['P'])
  assert.deepEqual(out.reviewPending, ['R'])
  const missing = runCli(['follow-ups', '--in', join(dir, 'nope.json')])
  assert.equal(missing.status, 2)
})

// ============================================================================================
// AC12 - the acceptance walk over independent-two
// ============================================================================================

function env(output, extra = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'fake', entry: { applied: true, newRole: 'work' }, output, ...extra,
  }
}
function plannerOut(mainFiles = []) {
  return {
    proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
    decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
    mainFiles, docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
  }
}
function implementerOut() {
  return {
    mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [],
    preExistingFailures: [], publicSurface: 'none', deviations: 'none',
  }
}

test('AC12 walk: open -> bind-run -> assign (four) -> stage results -> heartbeat -> end-seats -> close completed; provenance carries server-run', () => {
  const { dir, doc, planPath } = planDoc()
  // 1. open
  const opened = ops(planPath, 'open')
  assert.equal(opened.ops[0].op, 'open')
  // 2. bind
  const bindRes = bind(dir, planPath)
  assert.equal(bindRes.status, 0, bindRes.stderr)
  const bound = JSON.parse(bindRes.stdout)
  const boundPath = writeJson(dir, 'bound.json', bound)
  // 3. assign renders four ops
  assert.equal(ops(boundPath, 'assign').ops.length, 4)
  // 4. stage results (Method B drive over the bound plan)
  const core = loadWaveCore()
  let state = initState(bound, 'B')
  // each seat reports its own schema notes (a done stage with owned notes must name them)
  const scripted = (itemShort, seat) => {
    const stage = bound.args.items.find((it) => it.short === itemShort).stages.find((st) => st.seat === seat)
    return env(seat === 'planner' ? plannerOut([`src/${itemShort}.js`]) : implementerOut(), { notes: stage.notes.map((key) => ({ key })) })
  }
  for (let i = 0; i < 20; i += 1) {
    const r = next(core, bound, state)
    if (r.complete) break
    for (const d of r.dispatch) {
      const res = stageResult(core, bound, state, d.item, d.seat, JSON.stringify(scripted(d.item, d.seat)))
      assert.equal(res.status, 'done', `${d.seat}:${d.item} -> ${res.status}/${res.reason}`)
      state = res.state
    }
  }
  const result = resultFromState(core, bound, state)
  assert.equal(result.started, true)
  assert.equal(result.items.length, 2)
  assert.ok(result.items.every((it) => it.status === 'done'), JSON.stringify(result.items.map((it) => [it.short, it.status, it.reason])))
  const resultPath = writeJson(dir, 'result.json', result)
  // 5. heartbeat
  const usagePath = writeJson(dir, 'usage.json', { tokens: 1200, costUsd: 0.5 })
  assert.equal(ops(boundPath, 'heartbeat', ['--usage', usagePath]).ops[1].op, 'heartbeat')
  // 6. end-seats: the four seats are still active on the server
  const active = {
    runId: SERVER_RUN,
    seats: [
      seatRow(null, 'coordinator', 'active'),
      ...bound.args.items.flatMap((it) => it.stages.filter((s) => !s.inserted).map((s) => seatRow(it.id, s.seat, 'active'))),
    ],
  }
  const ended = ops(boundPath, 'end-seats', ['--seats', writeJson(dir, 'seats.json', active), '--result', resultPath])
  assert.equal(ended.ops.length, 4)
  assert.ok(ended.ops.every((o) => o.outcome === 'done'))
  // 7. close completed
  assert.deepEqual(ops(boundPath, 'close', ['--result', resultPath]).ops, [{ op: 'close', runId: SERVER_RUN, reason: 'completed' }])
  // provenance carries the server run id
  const lines = provenance({ core, doc: bound, result, method: 'B', turns: 1 })
  assert.equal(Object.keys(lines).length, 2)
  for (const line of Object.values(lines)) {
    assert.ok(line.includes(` server-run=${SERVER_RUN}`), line)
    assert.ok(line.split(' ').includes(`run=${doc.args.runId}`), line)
  }
})

// ============================================================================================
// S17 - the contract-names baseline [AC1]
// ============================================================================================

test('S17: the contract-names baseline lists no row owned by cac1bffa and none of this item\'s files, and adds no proseExceptions', () => {
  const baseline = JSON.parse(readFileSync(BASELINE, 'utf8'))
  assert.deepEqual(baseline.proseExceptions, [])
  const mine = ['scripts/run-exec-lib.mjs', 'scripts/run-planner-lib.mjs', 'scripts/run-planner.mjs', 'scripts/lib/wave-core.mjs', 'scripts/resolve-changelog-lib.mjs', 'scripts/resolve-changelog.mjs']
  const owned = (row) =>
    row.owner === 'cac1bffa' || row.file.startsWith('workflows/') || row.file.startsWith('skills/run-wave/') || mine.includes(row.file)
  const stale = baseline.pendingPort.filter(owned)
  assert.deepEqual(stale, [], 'every pendingPort row of this item must be deleted')
  assert.ok(Array.isArray(baseline.pendingPort), 'pendingPort stays an array')
})

// ============================================================================================
// Prose twins and the run lifecycle text (AC2, AC3, AC9, AC10, AC13, CHANGELOG)
// ============================================================================================
// The assertions read the run-wave skill text at test time and check for oracle strings taken from the
// task-scope acceptance criteria. They use assert.ok(cond, label) so a failure never echoes the file body.

const SKILL_DIR = join(PLUGIN_ROOT, 'skills', 'run-wave')
const REPO_ROOT_DIR = join(PLUGIN_ROOT, '..', '..')
function skillText(rel) {
  return readFileSync(join(SKILL_DIR, rel), 'utf8')
}
function has(text, needle) {
  return text.includes(needle)
}

test('AC3: no run-wave text persists the plan or state as a document - no docs_write, manage_plan_documents or planDocSlug in SKILL.md, method-b.md, post-run.md or snapshot.md', () => {
  for (const rel of ['SKILL.md', 'references/method-b.md', 'references/post-run.md', 'references/snapshot.md']) {
    const text = skillText(rel)
    for (const retired of ['manage_plan_documents', 'planDocSlug', 'slug:"run/', 'kind:"plan"']) {
      assert.ok(!has(text, retired), `${rel} still contains ${retired}`)
    }
    text.split(/\r?\n/).forEach((line, i) => {
      if (has(line, 'docs_write')) {
        assert.ok(has(line, 'kind:"rule"'), `${rel}:${i + 1} has docs_write without kind:"rule" (only rule seeding may use it)`)
      }
    })
  }
})

test('AC3: SKILL.md drives the run lifecycle through the helpers - open, bind-run, assign, end-seats, close - and names runs_write and runs_read', () => {
  const text = skillText('SKILL.md')
  for (const needle of ['run-ops', 'bind-run', 'end-seats', 'runs_write', 'runs_read', 'seatToken', 'heartbeat']) {
    assert.ok(has(text, needle), `SKILL.md does not mention ${needle}`)
  }
  // F7 and Resume read the run view, matching hints.plan
  assert.ok(has(text, 'hints.plan'), 'SKILL.md Resume/F7 must match hints.plan on the open runs')
})

test('AC2: SKILL.md carries the one RESULTS rule (per-element data.results[i].error; partial_failure only means look per element)', () => {
  const text = skillText('SKILL.md')
  assert.ok(has(text, 'data.results[i]'), 'per-element results')
  assert.ok(has(text, 'partial_failure'), 'partial_failure rule')
  assert.ok(/atomic/.test(text), 'atomic exception')
})

test('AC2: snapshot.md reads the item traits from the top-level traits array, not properties.traits', () => {
  const text = skillText('references/snapshot.md')
  assert.ok(has(text, 'traits'), 'traits')
  assert.ok(!has(text, 'properties.traits'), 'the 3.x properties.traits path must be gone')
})

test('AC13: the run-wave front door refuses to start on a non-null probe.serverCompat', () => {
  assert.ok(has(skillText('SKILL.md'), 'serverCompat'), 'SKILL.md must name probe.serverCompat in its start gate')
})

test('AC8: SKILL.md and post-run.md carry the parent check - the parent= pointer field on the run-wave session-tracking line and the blocked: seat re-send', () => {
  const skill = skillText('SKILL.md')
  const post = skillText('references/post-run.md')
  assert.ok(has(skill, 'parent=') || has(post, 'parent='), 'the parent= field of the pointer line')
  assert.ok(has(post, 'blocked: seat'), 'post-run must teach the blocked: seat parent re-send')
  assert.ok(has(post, 'follow-ups'), 'post-run must name the follow-ups helper')
})

test('AC10: post-run.md carries the P31 live-owed hold, the P32 changelog resolver paragraph and Step 7b with the review-pending twist', () => {
  const post = skillText('references/post-run.md')
  assert.ok(has(post, 'Live-owed'), 'P31: the **Live-owed** line')
  assert.ok(has(post, 'resolve-changelog'), 'P32: the resolver')
  assert.ok(has(post, 'Step 7b'), 'P36: Step 7b')
  assert.ok(has(post, 'review-pending'), 'P36 twist: review-pending suppression')
})

test('AC9: the repo run profile lists CHANGELOG.md among appendOnlyFiles and the resolve-changelog helper exists next to its library', () => {
  const profile = JSON.parse(readFileSync(join(REPO_ROOT_DIR, '.taskorchestrator', 'run-profile.json'), 'utf8'))
  assert.ok(Array.isArray(profile.appendOnlyFiles) && profile.appendOnlyFiles.includes('CHANGELOG.md'))
  assert.ok(readFileSync(join(PLUGIN_ROOT, 'scripts', 'resolve-changelog.mjs'), 'utf8').length > 0)
  assert.ok(readFileSync(join(PLUGIN_ROOT, 'scripts', 'resolve-changelog-lib.mjs'), 'utf8').length > 0)
})

test('CHANGELOG: the 4.0.0 Plugin section announces the coordinator move (server runs, token files) and the marketplace remove and re-add', () => {
  const log = readFileSync(join(REPO_ROOT_DIR, 'CHANGELOG.md'), 'utf8')
  const unreleased = log.slice(log.indexOf('## [4.0.0]'), log.indexOf('\n## [', log.indexOf('## [4.0.0]') + 5))
  const plugin = unreleased.slice(unreleased.indexOf('### Plugin'))
  assert.ok(plugin.length > 0, 'a 4.0.0 ### Plugin section exists')
  assert.ok(/server run/i.test(plugin), 'names server runs')
  assert.ok(/token file/i.test(plugin), 'names token files')
  assert.ok(/re-add/.test(plugin), 'names the marketplace remove and re-add')
})

// ============================================================================================
// Coordinator report (item 666c5e8f): the `report` helper and the run-ops open token file
// ============================================================================================

function reportOut(planPath, args) {
  const res = runCli(['report', '--plan', planPath, ...args])
  return res
}
const DEC = 'd3c1a2b4-0000-4000-8000-00000000d3c1'

test('REPORT S1: a closed report with 2 stopped items has the fixed lines in order, headed TO report', () => {
  const { dir, boundPath: planPath } = boundDoc()
  const result = resultDoc({
    items: [
      { id: A, short: 'aaaaaaaa', status: 'stopped', reason: 'run_paused', stages: [{ ...stageStatus('implementer', 'stopped'), decisionId: DEC }], outputs: {} },
      { id: B, short: 'bbbbbbbb', status: 'stopped', reason: 'gate_blocked', stages: [stageStatus('implementer', 'stopped')], outputs: {} },
    ],
  })
  const close = { data: { results: [{ ok: true, snapshot: { reason: 'failed', openDecisions: [DEC] } }] } }
  const res = reportOut(planPath, [
    '--event', 'closed', '--result', writeJson(dir, 'result.json', result), '--close', writeJson(dir, 'close.json', close),
  ])
  assert.equal(res.status, 0, res.stderr)
  const lines = res.stdout.trim().split('\n')
  assert.match(lines[0], /^TO report: run r-\d+-[0-9a-f]+ \(8f14e45f\) closed: /)
  assert.deepEqual(lines.map((l) => l.split(':')[0]), ['TO report', 'run', 'decisions', 'items', 'stopped', 'close', 'needs'])
  assert.equal(lines[1], 'run: status closed phase - method A')
  assert.equal(lines[2], 'decisions: d3c1a2b4 item aaaaaaaa')
  assert.equal(lines[3], 'items: done 0, stopped 2, deferred 0, held 0, excluded 0')
  assert.equal(lines[4], 'stopped: aaaaaaaa run_paused; bbbbbbbb gate_blocked')
  assert.equal(lines[5], 'close: failed, open decisions d3c1a2b4')
  assert.equal(lines[6], 'needs: nothing')
})

test('REPORT S2: a 300-char reason is cut to 120 and a stage carrying prose or a seatToken key contributes nothing', () => {
  const { dir, boundPath: planPath } = boundDoc()
  const long = 'x'.repeat(300)
  const result = resultDoc({
    items: [{
      id: A, short: 'aaaaaaaa', status: 'stopped', reason: long,
      stages: [{ ...stageStatus('implementer', 'stopped'), seatToken: 'SECRET-TOKEN-VALUE', prose: 'DIFF BODY TEXT', notes: [{ key: 'k', body: 'NOTE BODY TEXT' }] }],
      outputs: { implementer: { text: 'OUTPUT PROSE', seatToken: 'SECRET-TOKEN-VALUE' } },
    }],
  })
  const res = reportOut(planPath, ['--event', 'closed', '--result', writeJson(dir, 'result.json', result)])
  assert.equal(res.status, 0, res.stderr)
  const stopped = res.stdout.split('\n').find((l) => l.startsWith('stopped:'))
  assert.ok(stopped.length <= 'stopped: aaaaaaaa '.length + 120, `reason cut: ${stopped.length}`)
  for (const leak of ['SECRET-TOKEN-VALUE', 'DIFF BODY TEXT', 'NOTE BODY TEXT', 'OUTPUT PROSE']) {
    assert.equal(res.stdout.includes(leak), false, `${leak} must not appear`)
  }
})

test('REPORT S3: an unknown or missing --event exits 2 invalid args', () => {
  const { planPath } = planDoc()
  for (const extra of [['--event', 'nope'], []]) {
    const res = reportOut(planPath, extra)
    assert.equal(res.status, 2)
    assert.equal(JSON.parse(res.stderr).error, 'invalid args')
  }
})

test('REPORT S4: planned from a plan with excluded items says needs approve launch; a clean plan says nothing', () => {
  const clean = planDoc()
  assert.equal(reportOut(clean.planPath, ['--event', 'planned']).stdout.trim().split('\n').pop(), 'needs: nothing')
  const dirty = JSON.parse(JSON.stringify(clean.doc))
  dirty.meta.excluded = [{ id: 'cccccccc-3333-4333-8333-333333333333', reason: 'container' }]
  const res = reportOut(writeJson(clean.dir, 'dirty.json', dirty), ['--event', 'planned'])
  assert.equal(res.status, 0, res.stderr)
  assert.equal(res.stdout.trim().split('\n').pop(), 'needs: approve launch')
  assert.match(res.stdout, /excluded 1/)
})

test('REPORT S5: a paused run.json gives the pause line and puts the pause decision in decisions', () => {
  const { dir, boundPath: planPath } = boundDoc()
  const run = { data: { runId: SERVER_RUN, status: 'paused', pauseReason: 'budget', pauseDecisionId: DEC, hints: { phase: 'launched', method: 'A' } } }
  const res = reportOut(planPath, ['--event', 'paused', '--run', writeJson(dir, 'run.json', run)])
  assert.equal(res.status, 0, res.stderr)
  const lines = res.stdout.split('\n')
  assert.equal(lines[1], 'run: status paused phase launched method A')
  assert.equal(lines[2], 'decisions: d3c1a2b4 item -')
  assert.equal(lines[3], 'pause: budget decision d3c1a2b4')
})

test('REPORT S6: 12 stopped items list 10 then and 2 more; zero items gives all-zero counts and a failed close', () => {
  const { dir, boundPath: planPath } = boundDoc()
  const items = Array.from({ length: 12 }, (_, i) => ({
    id: `0000000${i.toString(16)}-0000-4000-8000-000000000000`, short: `0000000${i.toString(16)}`, status: 'stopped', reason: `r${i}`, stages: [], outputs: {},
  }))
  const res = reportOut(planPath, ['--event', 'closed', '--result', writeJson(dir, 'many.json', resultDoc({ items }))])
  const stopped = res.stdout.split('\n').find((l) => l.startsWith('stopped:'))
  assert.equal(stopped.split('; ').length, 11, '10 entries then the and-N-more entry')
  assert.ok(stopped.endsWith('and 2 more'))
  const none = { contract: 'implement-wave/result-v1', started: false, reason: 'nothing admitted', items: [], refused: [], deferred: [] }
  const z = reportOut(planPath, ['--event', 'closed', '--result', writeJson(dir, 'none.json', none)])
  assert.match(z.stdout, /items: done 0, stopped 0, deferred 0, held 0, excluded 0/)
  assert.match(z.stdout, /close: failed/)
})

test('REPORT S9: run-ops open names the coordinator token file, keeps the ops and carries no seatToken key', () => {
  const { doc, planPath } = planDoc()
  const out = ops(planPath, 'open')
  assert.equal(canonPath(out.tokenFile), canonPath(`${doc.args.project.scratchDir}/tokens/coordinator.token`))
  assert.equal(out.ops.length, 1)
  assert.equal(hasKeyDeep(out, 'seatToken'), false)
})
