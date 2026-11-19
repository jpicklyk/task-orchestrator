// retro-metrics.test.mjs -- scripts/lib/retro-metrics.mjs (pure module) and `run-planner retro-metrics`
// (the CLI, spawned asynchronously against an in-test fake REST server).
//
// Scenario ids S1-S10 match the item's frozen `test-plan` note. Oracles: task-scope D3-D9 and the base
// prose rules the module replaces (session-retrospective SKILL.md at 6a6b0ccc, :112-178 and :296), the
// api-reference `report` notes semantics (api-reference.md:1893, :1914), and the grammar the
// provenance-lib formatters and layer-meters.test.mjs already pin. Every expected number is hand-summed
// in the comment above its fixture; none is read back from the code under test. This file never opens
// retro-metrics.mjs or run-planner.mjs: only their declared surface is imported or spawned.

import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { retroMetrics, defectFingerprint, normaliseMessage, parseDefects, parseBaselines, CONTRACT } from '../lib/retro-metrics.mjs'
import { formatProvenance } from '../provenance-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const RUN_PLANNER = join(HERE, '..', 'run-planner.mjs')

const TMP = mkdtempSync(join(tmpdir(), 'retro-metrics-'))
after(() => rmSync(TMP, { recursive: true, force: true }))

// -- fixtures ---------------------------------------------------------------------------------------

const ID_A = 'aaaaaaaa-1111-4111-8111-111111111111'
const ID_B = 'bbbbbbbb-2222-4222-8222-222222222222'
const ID_C = 'cccccccc-3333-4333-8333-333333333333'
const ROOT = 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273'

const METERS = (i, cw, cr, o) => ({ input: i, cacheWrite: cw, cacheRead: cr, output: o })

// A structured delegation-metadata first line, built with the public formatter so the grammar is not guessed.
function structured(over = {}) {
  return formatProvenance({
    adapter: 'claude-agent',
    run: 'r-1',
    seats: [{ seat: 'planner', model: 'opus' }, { seat: 'implementer', model: 'sonnet' }],
    model: 'sonnet',
    isolation: 'worktree:/wt',
    agents: 5,
    tokens: 1000,
    duration: 60,
    deferred: 0,
    inRunEdges: 0,
    orchestratorTurns: 3,
    ...over,
  })
}

const item = (id, over = {}) => ({ id, type: 'feature-task', runId: 'r-1', delegationMetadata: structured(), sessionTracking: null, ...over })

const isGap = (v) => v === null || v === undefined || v === 'unknown'

// six-field, five-field and three-field extra-seats rows (provenance grammar of parseExtraSeats)
const ROW6 = 'reviewer-a1:sonnet:100:medium:review:1/2/3/4'
const ROW5 = 'implementer-a1:sonnet:5000:medium:test'
const ROW3 = 'reviewer-a2:sonnet:7000'
const withExtras = (first, rows) => first + '\nextra-seats=' + rows.join(',')

const get = (res, key) => res[key]
const findRun = (res, run) => res.perRun.find((r) => r.run === run)
const layerRow = (res, run, layer) => res.perLayer.find((r) => r.run === run && r.layer === layer)

// -- S1: happy path ---------------------------------------------------------------------------------

test('S1: structured line gives seats (reviewer-a1 -> type reviewer), perRun tokens and agents, cacheRead known sum and unknown count', () => {
  // run r-1: tokens 1000, agents 5, meters cacheRead 3 -> known 3, unknown 0
  // run r-2: tokens 500, agents 2, meters unknown -> cacheRead known 0, unknown 1
  const a = item(ID_A, {
    runId: 'r-1',
    delegationMetadata: structured({
      seats: [{ seat: 'planner', model: 'opus' }, { seat: 'reviewer-a1', model: 'sonnet' }],
      efforts: [{ seat: 'planner', effort: 'high' }],
      meters: METERS(1, 2, 3, 4),
    }),
  })
  const b = item(ID_B, { runId: 'r-2', delegationMetadata: structured({ run: 'r-2', agents: 2, tokens: 500, meters: 'unknown' }) })
  const res = retroMetrics({ items: [a, b] })

  assert.equal(res.contract, 'run-wave/retro-metrics-v1')
  assert.equal(CONTRACT, 'run-wave/retro-metrics-v1')
  assert.equal(res.delegations.length, 2)
  const d = res.delegations.find((x) => x.item === ID_A)
  assert.equal(d.form, 'structured')
  const reviewer = d.seats.find((s) => s.seat === 'reviewer-a1')
  assert.equal(reviewer.type, 'reviewer')
  assert.equal(reviewer.model, 'sonnet')
  assert.equal(reviewer.effort, null, 'no effort recorded for this seat reads null')
  const planner = d.seats.find((s) => s.seat === 'planner')
  assert.equal(planner.type, 'planner')
  assert.equal(planner.effort, 'high')

  const r1 = findRun(res, 'r-1')
  assert.equal(r1.adapter, 'claude-agent')
  assert.equal(r1.agents, 5)
  assert.equal(r1.tokens, 1000)
  assert.equal(r1.duration, 60)
  assert.equal(r1.orchestratorTurns, 3)
  assert.deepEqual(r1.cacheRead, { known: 3, unknown: 0 })
  const r2 = findRun(res, 'r-2')
  assert.equal(r2.tokens, 500)
  assert.deepEqual(r2.cacheRead, { known: 0, unknown: 1 })
})

// A2/B1: orchestratorTurns is a run-level value; provenance writes the same run turns on every item line
// of a run, so the run row takes it once (max of the numeric values), never the sum.
test('A2 B1: perRun orchestratorTurns is taken once per run (max), not summed over the run\'s lines', () => {
  const line = (turns) => structured({ run: 'r-1', orchestratorTurns: turns })
  // three lines of r-1, each 3 -> 3 (a sum would read 9)
  const same = retroMetrics({ items: [
    item(ID_A, { delegationMetadata: line(3) }),
    item(ID_B, { delegationMetadata: line(3) }),
    item(ID_C, { delegationMetadata: line(3) }),
  ] })
  assert.equal(findRun(same, 'r-1').orchestratorTurns, 3)
  // lines 2 and 5 -> 5 (a sum would read 7)
  const differ = retroMetrics({ items: [
    item(ID_A, { delegationMetadata: line(2) }),
    item(ID_B, { delegationMetadata: line(5) }),
  ] })
  assert.equal(findRun(differ, 'r-1').orchestratorTurns, 5)
  // a numeric value wins over an unknown one; all unknown reads 'unknown'
  const mixed = retroMetrics({ items: [
    item(ID_A, { delegationMetadata: line('unknown') }),
    item(ID_B, { delegationMetadata: line(4) }),
  ] })
  assert.equal(findRun(mixed, 'r-1').orchestratorTurns, 4)
  const none = retroMetrics({ items: [
    item(ID_A, { delegationMetadata: line('unknown') }),
    item(ID_B, { delegationMetadata: line('unknown') }),
  ] })
  assert.equal(findRun(none, 'r-1').orchestratorTurns, 'unknown')
})

// -- S2: extra-seats rows of three shapes -----------------------------------------------------------

test('S2: six-field rows feed the seats layer sums; five- and three-field rows are flagged legacyBasis and left out; unknown tokens is a gap, not 0', () => {
  // item meters 10/20/30/40 + six-field row 1/2/3/4 -> input 11, cacheWrite 22, cacheRead 33, output 44; tokens 11+22+44 = 77
  const first = structured({ meters: METERS(10, 20, 30, 40) })
  const body = withExtras(first, [ROW6, ROW5, ROW3, 'implementer-a2:sonnet:unknown'])
  // the per-layer table exists for a run that has a layer-meters line (base prose: no such line skips the table)
  const res = retroMetrics({ items: [item(ID_A, { delegationMetadata: body })], layerLines: ['layer-meters=coordinator:1/1/1/1 run=r-1'] })

  const rows = res.delegations[0].extraSeats
  assert.deepEqual(rows.map((r) => r.fields), [6, 5, 3, 3])
  assert.deepEqual(rows.map((r) => Boolean(r.legacyBasis)), [false, true, true, true])
  assert.deepEqual(rows.map((r) => r.type), ['reviewer', 'implementer', 'reviewer', 'implementer'])
  assert.deepEqual(rows[0].meters, METERS(1, 2, 3, 4))
  assert.equal(rows[1].tokens, 5000)
  assert.equal(rows[2].tokens, 7000)
  assert.ok(isGap(rows[3].tokens) && rows[3].tokens !== 0, 'unknown tokens is a gap, not zero: ' + rows[3].tokens)

  const seats = layerRow(res, 'r-1', 'seats')
  assert.equal(seats.input, 11)
  assert.equal(seats.cacheWrite, 22)
  assert.equal(seats.cacheRead, 33)
  assert.equal(seats.output, 44)
  assert.equal(seats.tokens, 77)
  assert.equal(res.fixCycle.unknownTokenSeats, 1)
})

// -- S3: layer meters -------------------------------------------------------------------------------

test('S3: layer tokens are input + cacheWrite + output with cacheRead apart; coordinator:unknown stays unknown; an omitted layer has no row', () => {
  // coordinator 10/20/300/5 -> tokens 10+20+5 = 35 ; session 1/2/3/4 -> tokens 1+2+4 = 7
  // r-2 line has only coordinator:unknown: no session row
  const res = retroMetrics({
    items: [item(ID_A)],
    layerLines: [
      'layer-meters=coordinator:10/20/300/5,session:1/2/3/4 run=r-1 session-window=whole-session',
      'layer-meters=coordinator:unknown run=r-2',
    ],
  })
  const c = layerRow(res, 'r-1', 'coordinator')
  assert.deepEqual([c.input, c.cacheWrite, c.cacheRead, c.output], [10, 20, 300, 5])
  assert.equal(c.tokens, 35)
  const s = layerRow(res, 'r-1', 'session')
  assert.equal(s.tokens, 7)
  assert.equal(s.cacheRead, 3)

  const unk = layerRow(res, 'r-2', 'coordinator')
  assert.ok(unk, 'the coordinator layer of r-2 has a row')
  assert.ok(isGap(unk.tokens) && unk.tokens !== 0, 'unknown layer tokens: ' + unk.tokens)
  assert.equal(layerRow(res, 'r-2', 'session'), undefined, 'a layer the line omits has no row')
})

// -- S4: fix-cycle cost -----------------------------------------------------------------------------

function cycleItem(runTokens, rows, tracking) {
  return item(ID_A, { delegationMetadata: withExtras(structured({ tokens: runTokens }), rows), sessionTracking: tracking ?? null })
}

test('S4: loop = -a<n> rows with cause review|test; carry-in and breach are excluded; verdict comes from session-tracking', () => {
  // run tokens 700 + loop 200 (review) + 50 (test) + carry-in 100 + breach 40 -> runTokens 1090, loop 250
  const rows = [
    'implementer-a1:sonnet:200:medium:review:1/1/1/1',
    'implementer-a2:sonnet:50:medium:test:1/1/1/1',
    'reviewer-a1:sonnet:100:medium:carry-in:1/1/1/1',
    'implementer-a3:sonnet:40:medium:breach:1/1/1/1',
  ]
  const res = retroMetrics({ items: [cycleItem(700, rows, 'Review: approved after A2\n')] })
  assert.equal(res.fixCycle.loopTokens, 250)
  assert.equal(res.fixCycle.runTokens, 1090)
  assert.ok(Math.abs(res.fixCycle.loopShare - 250 / 1090) < 1e-3, 'loopShare ' + res.fixCycle.loopShare)
  const loop = res.fixCycle.cycles.filter((c) => c.cause === 'review' || c.cause === 'test')
  assert.deepEqual(loop.map((c) => c.cycle), [1, 2])
  assert.deepEqual(loop.map((c) => c.tokens), [200, 50])
  assert.equal(loop[1].verdict, 'approved')
  assert.equal(loop[0].item, ID_A)
  assert.deepEqual(res.fixCycle.findings, [])
})

test('S4: a carry-in row never counts toward the loop (would be 30% if it did)', () => {
  // run 700 + review 200 + carry-in 100 -> runTokens 1000; loop 200 = 20%; with carry-in it would be 300 = 30%
  const res = retroMetrics({ items: [cycleItem(700, ['implementer-a1:sonnet:200:medium:review:1/1/1/1', 'reviewer-a1:sonnet:100:medium:carry-in:1/1/1/1'])] })
  assert.equal(res.fixCycle.loopTokens, 200)
  assert.equal(res.fixCycle.runTokens, 1000)
  assert.ok(Math.abs(res.fixCycle.loopShare - 0.2) < 1e-9)
  assert.deepEqual(res.fixCycle.findings, [])
})

test('S4: the finding fires at 26% and not at exactly 25%', () => {
  // 25%: run 750 + loop 200 + 50 = runTokens 1000, loop 250
  const at25 = retroMetrics({ items: [cycleItem(750, ['implementer-a1:sonnet:200:medium:review:1/1/1/1', 'implementer-a2:sonnet:50:medium:test:1/1/1/1'])] })
  assert.equal(at25.fixCycle.runTokens, 1000)
  assert.equal(at25.fixCycle.loopTokens, 250)
  assert.deepEqual(at25.fixCycle.findings, [], 'exactly 25% is not over 25%')
  // 26%: run 740 + 210 + 50 = 1000, loop 260
  const at26 = retroMetrics({ items: [cycleItem(740, ['implementer-a1:sonnet:210:medium:review:1/1/1/1', 'implementer-a2:sonnet:50:medium:test:1/1/1/1'])] })
  assert.equal(at26.fixCycle.loopTokens, 260)
  assert.deepEqual(at26.fixCycle.findings, ['loop-share-over-25pct'])
})

test('S4 probe: a run with no extra-seats rows has loop 0 and no finding; the share is not NaN', () => {
  const res = retroMetrics({ items: [item(ID_A)] })
  assert.equal(res.fixCycle.loopTokens, 0)
  assert.equal(res.fixCycle.runTokens, 1000)
  assert.equal(res.fixCycle.loopShare, 0)
  assert.deepEqual(res.fixCycle.findings, [])
})

// -- S5: legacy and missing forms -------------------------------------------------------------------

test('S5: legacy prose reads form legacy, a missing delegation-metadata reads none, a see:<short> run counts once per run', () => {
  const legacy = item(ID_B, { runId: undefined, delegationMetadata: 'Delegated to sonnet in a worktree; 3 agents, about 40k tokens.' })
  const none = item(ID_C, { runId: undefined, delegationMetadata: null })
  const seeA = item(ID_A, { runId: 'r-9', delegationMetadata: structured({ run: 'r-9', tokens: 'see:abcd1234', duration: 'see:abcd1234' }) })
  const seeB = item('dddddddd-4444-4444-8444-444444444444', { runId: 'r-9', delegationMetadata: structured({ run: 'r-9', tokens: 'see:abcd1234', duration: 'see:abcd1234' }) })
  const res = retroMetrics({ items: [legacy, none, seeA, seeB] })
  assert.equal(res.delegations.find((d) => d.item === ID_B).form, 'legacy')
  assert.equal(res.delegations.find((d) => d.item === ID_C).form, 'none')
  assert.equal(res.perRun.filter((r) => r.run === 'r-9').length, 1, 'two items of one run: one perRun row')
  assert.notEqual(typeof findRun(res, 'r-9').tokens, 'number', 'a see: value is not summed into a number')
})

test('S5 probe: absent, null and empty delegation-metadata are three spellings of form none; duplicate run ids and CRLF bodies', () => {
  const absent = { id: ID_A, type: 'feature-task' }
  const nul = item(ID_B, { delegationMetadata: null, runId: undefined })
  const empty = item(ID_C, { delegationMetadata: '', runId: undefined })
  const res = retroMetrics({ items: [absent, nul, empty] })
  assert.deepEqual(res.delegations.map((d) => d.form), ['none', 'none', 'none'])
  assert.equal(res.perRun.length, 0)

  // two items of one run id: one perRun row (whether their numeric fields are summed or taken once is not
  // stated by the task-scope; recorded as an ambiguity in the manifest, not asserted)
  const dup = retroMetrics({ items: [item(ID_A), item(ID_B)] })
  assert.equal(dup.perRun.length, 1)

  // CRLF body: extra-seats on the second line still parses
  const crlf = retroMetrics({ items: [item(ID_A, { delegationMetadata: structured() + '\r\nextra-seats=' + ROW6 + '\r\n' })] })
  assert.equal(crlf.delegations[0].extraSeats.length, 1)
  assert.equal(crlf.delegations[0].extraSeats[0].fields, 6)
  assert.deepEqual(crlf.delegations[0].extraSeats[0].meters, METERS(1, 2, 3, 4))
})

test('S5 probe: unknown in every numeric field is a gap, never 0', () => {
  const body = structured({ agents: 'unknown', tokens: 'unknown', duration: 'unknown', meters: 'unknown' })
  const res = retroMetrics({ items: [item(ID_A, { delegationMetadata: body })] })
  const r = findRun(res, 'r-1')
  for (const k of ['agents', 'tokens', 'duration']) assert.ok(isGap(r[k]) && r[k] !== 0, k + ' is a gap: ' + r[k])
  assert.deepEqual(r.cacheRead, { known: 0, unknown: 1 })
})

// -- S6: recorded share -----------------------------------------------------------------------------

const AGENT = (label, cls, tokens) => ({ label, class: cls, model: 'sonnet', effort: 'medium', dispatches: 1, input: tokens, cacheWrite: 0, cacheRead: 0, output: 0, tokens })
const classRow = (res, cls) => {
  const rows = res.sessionCoverage.rows
  return Array.isArray(rows) ? rows.find((r) => (r.class ?? r.name) === cls) : rows[cls]
}

test('S6: recorded share counts rows whose label is recorded in a delegation-metadata note; total 0 reads pct null; a null label is listed unlabelled', () => {
  // recorded: planner:aaaaaaaa 30 + implementer:aaaaaaaa 20 = 50 (aaaaaaaa is a member, name in seats=)
  // not recorded: research:bbbbbbbb 30 (no such seat), implementer:cccccccc 15 (not a member), null label 5
  // total 100 -> 50%
  const sessionMeters = {
    contract: 'run-wave/session-meters-v1',
    sessionDir: '/s',
    agents: [
      AGENT('implementer:aaaaaaaa', 'seat', 20),
      AGENT('implementer:cccccccc', 'seat', 15),
      AGENT('planner:aaaaaaaa', 'seat', 30),
      AGENT('research:bbbbbbbb', 'research', 30),
      { ...AGENT(null, 'unlabelled', 5) },
    ],
    main: { input: 100, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 100 },
    byClass: {
      seat: { agents: 3, input: 65, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 65 },
      research: { agents: 1, input: 30, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 30 },
      unlabelled: { agents: 1, input: 5, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 5 },
    },
    lines: [],
  }
  const res = retroMetrics({ items: [item(ID_A)], sessionMeters })
  const cov = res.sessionCoverage
  assert.equal(cov.recorded, 50)
  assert.equal(cov.total, 100, 'the main transcript is in neither number')
  assert.equal(cov.pct, 50)
  assert.equal(classRow(res, 'seat').tokens, 65)
  assert.equal(classRow(res, 'research').tokens, 30)
  for (const absent of ['lane', 'red-proofs', 'retrospective', 'coordinator']) {
    assert.equal(classRow(res, absent).tokens, 0, absent + ' absent from byClass reads 0')
  }
  assert.equal(cov.main.tokens, 100)
  const nullRow = cov.unlabelledAgents.find((u) => u.label === null || u.label === '(no description)')
  assert.ok(nullRow, 'the null-label agent is listed')
  assert.equal(nullRow.tokens, 5)

  const empty = retroMetrics({ items: [item(ID_A)], sessionMeters: { contract: 'run-wave/session-meters-v1', sessionDir: '/s', agents: [], main: null, byClass: {}, lines: [] } })
  assert.equal(empty.sessionCoverage.total, 0)
  assert.equal(empty.sessionCoverage.pct, null)
})

test('S6 probe: a recorded name must be a seat of that item (extra-seats row seat also counts)', () => {
  // reviewer:aaaaaaaa is recorded only through the extra-seats row reviewer-a1? the row seat is 'reviewer-a1',
  // the label name is 'reviewer-a1' -> recorded when written with that name
  const body = withExtras(structured(), [ROW6])
  const sessionMeters = {
    contract: 'run-wave/session-meters-v1', sessionDir: '/s', main: null, lines: [],
    agents: [AGENT('reviewer-a1:aaaaaaaa', 'seat', 40), AGENT('reviewer-a9:aaaaaaaa', 'seat', 60)],
    byClass: { seat: { agents: 2, input: 100, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 100 } },
  }
  const res = retroMetrics({ items: [item(ID_A, { delegationMetadata: body })], sessionMeters })
  assert.equal(res.sessionCoverage.recorded, 40)
  assert.equal(res.sessionCoverage.total, 100)
})

// -- S9: defect fingerprint -------------------------------------------------------------------------

const fp = (normalised, component = 'server', tool = 'items_read', op = 'get', code = 'not_found') =>
  'to-bug-' + createHash('sha256').update(`${component}|${tool}|${op}|${code}|${normalised}`.toLowerCase()).digest('hex').slice(0, 8)

test('S9: normaliseMessage lowercases and replaces UUIDs/hex runs, paths and digit runs, collapses whitespace', () => {
  // hand-derived from D7
  assert.equal(
    normaliseMessage('  Item 3f2a9c1e-1111-4222-8333-444455556666  not   found at C:\\Work\\notes\\x.md after 12 tries '),
    'item <id> not found at <path> after <n> tries',
  )
  assert.equal(normaliseMessage('Bad ref deadbeef12 in /var/log/app.log line 40'), 'bad ref <id> in <path> line <n>')
  assert.equal(normaliseMessage('Plain Message'), 'plain message')
  assert.equal(normaliseMessage(''), '')
})

test('S9: fingerprint is to-bug- plus 8 hex of sha256 over component|tool|op|code|normalised; ids, paths and numbers do not change it; another code does', () => {
  const base = { component: 'server', tool: 'items_read', op: 'get', code: 'not_found', message: 'Item 3f2a9c1e-1111-4222-8333-444455556666 not found at C:\\a\\b.md after 3 tries' }
  const expected = fp('item <id> not found at <path> after <n> tries')
  assert.equal(defectFingerprint(base), expected)
  assert.match(defectFingerprint(base), /^to-bug-[0-9a-f]{8}$/)
  const other = { ...base, message: 'Item 00000000-9999-4888-8777-666655554444 not found at /tmp/q/r.md after 41 tries' }
  assert.equal(defectFingerprint(other), expected)
  assert.notEqual(defectFingerprint({ ...base, code: 'forbidden' }), expected)
  assert.notEqual(defectFingerprint({ ...base, component: 'plugin' }), expected)
  assert.notEqual(defectFingerprint({ ...base, tool: 'notes_read' }), expected)
  assert.notEqual(defectFingerprint({ ...base, op: 'list' }), expected)
  assert.notEqual(defectFingerprint({ ...base, message: 'Item gone' }), expected)
  assert.equal(defectFingerprint({ ...base, code: 'NOT_FOUND' }), expected, 'the hashed text is lowercased')
})

test('S9 probe: a Windows path with backslashes and a drive letter normalises to <path> (platform-neutral, no separator assumed)', () => {
  assert.equal(normaliseMessage('cannot open C:\\Users\\x\\a b.txt'), 'cannot open <path> b.txt')
})

// -- S9/D7: defects in session-tracking -------------------------------------------------------------

test('D7: defects parse from session-tracking; message ends at the next defect, "; " or the end of the line; component from the tool name', () => {
  const body = 'Friction: defect: items_read, get, not_found, Item 12 not found; defect: run-planner, plan, exit_1, bad thing\r\nNext line defect-free'
  const parsed = parseDefects(body)
  assert.equal(parsed.length, 2)
  assert.deepEqual([parsed[0].tool, parsed[0].op, parsed[0].code, parsed[0].message], ['items_read', 'get', 'not_found', 'Item 12 not found'])
  assert.deepEqual([parsed[1].tool, parsed[1].op, parsed[1].code, parsed[1].message], ['run-planner', 'plan', 'exit_1', 'bad thing'])

  const res = retroMetrics({ items: [item(ID_A, { sessionTracking: body })] })
  assert.equal(res.defects.length, 2)
  assert.equal(res.defects[0].item, ID_A)
  assert.equal(res.defects[0].component, 'server')
  assert.equal(res.defects[1].component, 'plugin')
  assert.equal(res.defects[0].fingerprint, fp('item <n> not found'))
  assert.equal(res.defects[1].fingerprint, fp('bad thing', 'plugin', 'run-planner', 'plan', 'exit_1'))

  assert.deepEqual(parseDefects('no defect here'), [])
  assert.deepEqual(parseDefects(''), [])
  assert.deepEqual(parseDefects(null), [])
})

// A2/O6: the marker matches on a word boundary, so "nodefect:" inside a longer word is not a defect.
test('A2 O6: defect: matches on a word boundary ("nodefect:" is not a marker; "x; defect:" is)', () => {
  assert.deepEqual(parseDefects('nodefect: a, b, c, d'), [])
  const one = parseDefects('x; defect: a, b, c, d')
  assert.equal(one.length, 1)
  assert.deepEqual([one[0].tool, one[0].op, one[0].code, one[0].message], ['a', 'b', 'c', 'd'])
})

// -- S10: baselines, self ---------------------------------------------------------------------------

const CONFIG_WITH_BASELINES = [
  'project:',
  '  rootId: ' + ROOT,
  'extensions:',
  '  claude-code:',
  '    retrospective:',
  '      mode: auto',
  '      baselines:',
  '        note-volume: { measured: 2026-11-02, review-share-pct: 31, review-checklist-avg-kb: 6.5, largest-note-kb: 37 }',
  '        work-notes: { measured: 2026-11-07, work-avg-bytes: 1127, work-avg-target-bytes: 800 }',
  '',
].join('\n')

test('S10: a config without baselines reads null; a config with them echoes both entries', () => {
  assert.equal(parseBaselines('project:\n  rootId: x\n'), null)
  assert.equal(parseBaselines('extensions:\n  claude-code:\n    retrospective:\n      mode: auto\n'), null)
  const b = parseBaselines(CONFIG_WITH_BASELINES)
  assert.equal(Number(b['note-volume']['review-share-pct']), 31)
  assert.equal(Number(b['note-volume']['largest-note-kb']), 37)
  assert.equal(Number(b['work-notes']['work-avg-bytes']), 1127)
  assert.equal(Number(b['work-notes']['work-avg-target-bytes']), 800)
  assert.equal(retroMetrics({ items: [], baselines: null }).noteVolume.baselines ?? null, null)
})

test('S10: self reads the session-meters row; analysedShare is self tokens over the run tokens; measured:false passes through', () => {
  // self row 10/20/5/20 -> tokens 10+20+20 = 50 ; runTokens 1000 -> share 0.05
  const row = { label: 'retrospective:aaaaaaaa', class: 'retrospective', model: 'sonnet', effort: 'medium', dispatches: 1, input: 10, cacheWrite: 20, cacheRead: 5, output: 20, tokens: 50 }
  const res = retroMetrics({ items: [item(ID_A)], self: { label: 'retrospective:aaaaaaaa', row, wallMs: 90000 } })
  assert.equal(res.self.label, 'retrospective:aaaaaaaa')
  assert.equal(res.self.tokens, 50)
  assert.equal(res.self.cacheRead, 5)
  assert.equal(res.self.wallMs, 90000)
  assert.ok(Math.abs(res.self.analysedShare - 0.05) < 1e-9)
  assert.equal(res.self.partial, true)

  const gap = retroMetrics({ items: [item(ID_A)], self: { measured: false, reason: 'no session dir' } })
  assert.equal(gap.self.measured, false)
  assert.equal(gap.self.reason, 'no session dir')
  const noRow = retroMetrics({ items: [item(ID_A)], self: { label: 'retrospective:aaaaaaaa', row: null } })
  assert.ok(noRow.self.measured === false || isGap(noRow.self.tokens), 'no row: self is not zero')
  assert.notEqual(noRow.self.tokens, 0)
})

test('S7 (module): a noteVolume {measured:false, reason} input is passed through', () => {
  const res = retroMetrics({ items: [item(ID_A)], noteVolume: { measured: false, reason: 'forbidden' } })
  assert.equal(res.noteVolume.measured, false)
  assert.equal(res.noteVolume.reason, 'forbidden')
})

// -- CLI harness ------------------------------------------------------------------------------------

function startFake(handler) {
  const calls = []
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      const chunks = []
      req.on('data', (c) => chunks.push(c))
      req.on('end', () => {
        let body = {}
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}') } catch { body = {} }
        const m = /^\/api\/v1\/ops\/([^/?]+)/.exec(req.url)
        const tool = m ? m[1] : null
        calls.push({ method: req.method, url: req.url, tool, body })
        const out = tool ? handler(tool, body) : { status: 404, json: { ok: false, error: { code: 'not_found', message: 'no route' } } }
        res.writeHead(out.status ?? 200, { 'content-type': 'application/json' })
        res.end(JSON.stringify(out.json))
      })
    })
    server.listen(0, '127.0.0.1', () => resolve({ server, calls, url: 'http://127.0.0.1:' + server.address().port }))
  })
}
const stopFake = (f) => new Promise((r) => f.server.close(r))

// The fake is a minimal REST ops server: envelopes {ok, data} / {ok:false, error:{code}}.
const ok = (data) => ({ json: { ok: true, data, meta: { reqId: 'x' } } })
const err = (status, code) => ({ status, json: { ok: false, error: { code, message: code + ' (fake)' }, meta: { reqId: 'x' } } })

const NOTE_BODIES = { 'delegation-metadata': structured({ run: 'r-1' }), 'session-tracking': 'plain tracking text' }

function baseHandler(over = {}) {
  return (tool, body) => {
    if (over[tool]) {
      const r = over[tool](body)
      if (r) return r
    }
    if (tool === 'items_read') {
      if (body.select === 'get') return ok({ id: body.itemId ?? ID_A, type: 'feature-task', rootId: ROOT, role: 'terminal', traits: [] })
      if (body.select === 'schema') {
        return ok({ type: 'feature-task', notes: [
          { key: 'task-scope', role: 'queue', maxLength: 12000 },
          { key: 'implementation-notes', role: 'work', maxLength: 1200 },
          { key: 'review-checklist', role: 'review', maxLength: 20000 },
        ] })
      }
      return ok({ items: [], page: { total: 0, truncated: false } })
    }
    if (tool === 'notes_read') {
      if (body.select === 'get') {
        const text = NOTE_BODIES[body.key]
        if (text === undefined) return err(404, 'not_found')
        return ok({ id: 'n1', key: body.key, role: 'work', version: 1, body: text })
      }
      return ok({ notes: [], items: [], page: { total: 0, truncated: false } })
    }
    if (tool === 'report') {
      if (body.select === 'notes' && body.groupBy === 'role') {
        // page 1: work row; page 2 (cursor p2): review row. work bytes 6900 / 6 writes, review bytes 3100 -> 31%
        if (body.cursor === 'p2') {
          return ok({ items: [{ key: { role: 'review' }, writes: 2, notes: 2, rewrites: 0, bytes: 3100, maxBytes: 2000, avgBytes: 1550 }], page: { total: 2, truncated: false }, window: { since: body.since } })
        }
        return ok({ items: [{ key: { role: 'work' }, writes: 6, notes: 5, rewrites: 1, bytes: 6900, maxBytes: 1500, avgBytes: 1150 }], page: { total: 2, truncated: true, next: 'p2' }, window: { since: body.since } })
      }
      if (body.select === 'notes' && body.groupBy === 'noteKey') {
        return ok({ items: [
          { key: { noteKey: 'review-checklist' }, writes: 2, notes: 2, rewrites: 0, bytes: 13000, maxBytes: 9000, avgBytes: 6500 },
          { key: { noteKey: 'implementation-notes' }, writes: 4, notes: 3, rewrites: 1, bytes: 4400, maxBytes: 1500, avgBytes: 1100 },
          { key: { noteKey: 'task-scope' }, writes: 1, notes: 1, rewrites: 0, bytes: 5000, maxBytes: 5000, avgBytes: 5000 },
        ], page: { total: 3, truncated: false }, window: { since: body.since } })
      }
      return ok({ items: [], page: { total: 0, truncated: false } })
    }
    return err(404, 'not_found')
  }
}

function runCli(args, { env = {}, cwd } = {}) {
  const dir = cwd ?? mkdtempSync(join(TMP, 'cwd-'))
  const full = {
    ...process.env,
    CLAUDE_CONFIG_DIR: join(dir, 'claude-config'),
    TASK_ORCHESTRATOR_CEILING: dir,
    AGENT_CONFIG_DIR: dir,
    HOME: dir,
    USERPROFILE: dir,
    ...env,
  }
  for (const [k, v] of Object.entries(full)) if (v === undefined) delete full[k]
  if (!('TASK_ORCHESTRATOR_API_URL' in env)) delete full.TASK_ORCHESTRATOR_API_URL
  delete full.TASK_ORCHESTRATOR_API_TOKEN
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [RUN_PLANNER, 'retro-metrics', ...args], { cwd: dir, env: full })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (c) => { stdout += c })
    child.stderr.on('data', (c) => { stderr += c })
    child.on('close', (status) => resolve({ status, stdout, stderr, dir }))
  })
}

const json = (r) => {
  assert.doesNotThrow(() => JSON.parse(r.stdout), 'stdout is one JSON document: ' + r.stdout.slice(0, 300) + ' | ' + r.stderr.slice(0, 300))
  return JSON.parse(r.stdout)
}
const share = (v) => (v > 1 ? v / 100 : v)
// Two days back: inside the CLI's 31-day --since floor, whenever the suite runs (A2/B2).
const SINCE_RECENT = () => new Date(Date.now() - 2 * 86400000).toISOString()

// -- S7: CLI note volume over REST ------------------------------------------------------------------

test('S7: note volume over REST /api/v1/ops/report (two role pages), review share, review-checklist avg, largest, work avg, overruns, baselines echoed', async () => {
  const fake = await startFake(baseHandler())
  const cfg = join(TMP, 'config-with.yaml')
  writeFileSync(cfg, CONFIG_WITH_BASELINES)
  try {
    const since = SINCE_RECENT()
    const r = await runCli(['--root', ROOT, '--item', ID_A, '--since', since, '--config', cfg], { env: { TASK_ORCHESTRATOR_API_URL: fake.url } })
    assert.equal(r.status, 0, r.stderr)
    const doc = json(r)
    assert.equal(doc.contract, 'run-wave/retro-metrics-v1')

    const nv = doc.noteVolume
    // review 3100 of (6900 + 3100) = 31%
    assert.ok(Math.abs(share(nv.reviewShare) - 0.31) < 0.005, 'reviewShare ' + nv.reviewShare)
    assert.equal(nv.reviewChecklistAvgBytes, 6500)
    assert.equal(nv.largest.noteKey, 'review-checklist')
    assert.equal(nv.largest.maxBytes, 9000)
    assert.equal(nv.workAvgBytes, 1150)
    // implementation-notes maxBytes 1500 > maxLength 1200; task-scope 5000 < 12000; review-checklist 9000 < 20000
    assert.deepEqual(nv.overruns, [{ noteKey: 'implementation-notes', maxBytes: 1500, maxLength: 1200 }])
    assert.equal(nv.window.since, since)
    assert.equal(Number(nv.baselines['note-volume']['review-share-pct']), 31)
    assert.equal(Number(nv.baselines['work-notes']['work-avg-bytes']), 1127)

    const reports = fake.calls.filter((c) => c.tool === 'report')
    assert.ok(reports.length >= 3, 'role page 1, role page 2, noteKey: ' + reports.length)
    for (const c of reports) {
      assert.equal(c.body.select, 'notes')
      assert.equal(c.body.since, since)
      assert.equal(c.body.rootId, ROOT)
    }
    assert.ok(reports.some((c) => c.body.groupBy === 'role' && c.body.cursor === 'p2'), 'page.next followed')
    assert.ok(reports.some((c) => c.body.groupBy === 'noteKey'))
    assert.ok(fake.calls.every((c) => c.url.startsWith('/api/v1/ops/')), 'REST only, no director path')
  } finally {
    await stopFake(fake)
  }
})

// A2/O7: report notes groups by one dimension, so a key's largest write has no type; the overrun is judged
// against the smallest cap among the types declaring the key, with capsByType listed when caps differ.
test('A2 O7: a noteKey declared by two types with different caps overruns the smaller cap, in either item order', async () => {
  const TYPE_CAPS = { 'type-a': 12000, 'type-b': 4000 }
  const typeOfItem = { [ID_A]: 'type-a', [ID_B]: 'type-b' }
  const mkHandler = () => baseHandler({
    items_read: (body) => {
      if (body.select === 'get') {
        const id = body.itemId
        const type = Object.entries(typeOfItem).find(([k]) => k === id || k.startsWith(String(id)))?.[1] ?? 'type-a'
        return ok({ id, type, rootId: ROOT, role: 'terminal', traits: [] })
      }
      if (body.select === 'schema') {
        const type = body.type ?? typeOfItem[body.itemId] ?? Object.entries(typeOfItem).find(([k]) => k.startsWith(String(body.itemId)))?.[1]
        return ok({ type, notes: [{ key: 'task-scope', role: 'queue', maxLength: TYPE_CAPS[type] }] })
      }
      return null
    },
    report: (body) => {
      if (body.groupBy === 'noteKey') {
        // task-scope largest write 5000: above type-b's 4000, below type-a's 12000
        return ok({ items: [{ key: { noteKey: 'task-scope' }, writes: 2, notes: 2, rewrites: 0, bytes: 6000, maxBytes: 5000, avgBytes: 3000 }], page: { total: 1, truncated: false } })
      }
      return null
    },
  })
  const expected = [{ noteKey: 'task-scope', maxBytes: 5000, maxLength: 4000, capsByType: { 'type-a': 12000, 'type-b': 4000 } }]
  for (const order of [[ID_A, ID_B], [ID_B, ID_A]]) {
    const fake = await startFake(mkHandler())
    try {
      const args = ['--root', ROOT, '--since', SINCE_RECENT()]
      for (const id of order) args.push('--item', id)
      const r = await runCli(args, { env: { TASK_ORCHESTRATOR_API_URL: fake.url } })
      assert.equal(r.status, 0, r.stderr)
      assert.deepEqual(json(r).noteVolume.overruns, expected, 'item order ' + order.map((i) => i.slice(0, 8)).join(','))
    } finally {
      await stopFake(fake)
    }
  }
})

test('S7 probe: page.next absent on the only page ends the read after one request per grouping', async () => {
  const fake = await startFake(baseHandler({
    report: (body) => (body.groupBy === 'role'
      ? ok({ items: [{ key: { role: 'work' }, writes: 1, notes: 1, rewrites: 0, bytes: 100, maxBytes: 100, avgBytes: 100 }, { key: { role: 'review' }, writes: 1, notes: 1, rewrites: 0, bytes: 100, maxBytes: 100, avgBytes: 100 }], page: { total: 2, truncated: false } })
      : null),
  }))
  try {
    const r = await runCli(['--root', ROOT, '--item', ID_A, '--since', SINCE_RECENT()], { env: { TASK_ORCHESTRATOR_API_URL: fake.url } })
    assert.equal(r.status, 0, r.stderr)
    assert.equal(fake.calls.filter((c) => c.tool === 'report' && c.body.groupBy === 'role').length, 1)
    assert.ok(Math.abs(share(json(r).noteVolume.reviewShare) - 0.5) < 0.005)
  } finally {
    await stopFake(fake)
  }
})

// -- S8: failures -----------------------------------------------------------------------------------

test('S8: no REST base URL is measured:false with reason "no REST API configured" and exit 0', async () => {
  const r = await runCli(['--root', ROOT, '--item', ID_A])
  assert.equal(r.status, 0, r.stderr)
  assert.deepEqual(json(r), { contract: 'run-wave/retro-metrics-v1', measured: false, reason: 'no REST API configured' })
})

test('S8: a failing report read gives noteVolume {measured:false, reason:<code>} while the other sections are present; exit 0', async () => {
  const fake = await startFake(baseHandler({ report: () => err(403, 'forbidden') }))
  try {
    const r = await runCli(['--root', ROOT, '--item', ID_A, '--since', SINCE_RECENT()], { env: { TASK_ORCHESTRATOR_API_URL: fake.url } })
    assert.equal(r.status, 0, r.stderr)
    const doc = json(r)
    assert.equal(doc.noteVolume.measured, false)
    assert.equal(doc.noteVolume.reason, 'forbidden')
    assert.equal(doc.delegations.length, 1)
    assert.equal(doc.delegations[0].form, 'structured')
    assert.equal(doc.delegations[0].item, ID_A)
    assert.equal(findRun(doc, 'r-1').tokens, 1000)
  } finally {
    await stopFake(fake)
  }
})

test('S8: an unknown flag and a value flag without a value exit 2 with an invalid-args error', async () => {
  const fake = await startFake(baseHandler())
  try {
    for (const args of [['--root', ROOT, '--bogus'], ['--root'], ['--root', ROOT, '--run']]) {
      const r = await runCli(args, { env: { TASK_ORCHESTRATOR_API_URL: fake.url } })
      assert.equal(r.status, 2, args.join(' ') + ' -> ' + r.stderr + r.stdout)
      assert.match(r.stdout + r.stderr, /invalid args/)
    }
  } finally {
    await stopFake(fake)
  }
})

// -- S10: CLI edges ---------------------------------------------------------------------------------

test('S10: a --config without baselines gives noteVolume.baselines null', async () => {
  const fake = await startFake(baseHandler())
  const cfg = join(TMP, 'config-without.yaml')
  writeFileSync(cfg, 'project:\n  rootId: ' + ROOT + '\n')
  try {
    const r = await runCli(['--root', ROOT, '--item', ID_A, '--since', SINCE_RECENT(), '--config', cfg], { env: { TASK_ORCHESTRATOR_API_URL: fake.url } })
    assert.equal(r.status, 0, r.stderr)
    assert.equal(json(r).noteVolume.baselines, null)
  } finally {
    await stopFake(fake)
  }
})

test('S10: --self-only without a session dir gives self measured:false with a reason, exit 0', async () => {
  const fake = await startFake(baseHandler())
  try {
    const r = await runCli(['--root', ROOT, '--run', 'r-1', '--self-only'], { env: { TASK_ORCHESTRATOR_API_URL: fake.url } })
    assert.equal(r.status, 0, r.stderr)
    const doc = json(r)
    assert.equal(doc.self.measured, false)
    assert.equal(typeof doc.self.reason, 'string')
    assert.ok(doc.self.reason.length > 0)
  } finally {
    await stopFake(fake)
  }
})
