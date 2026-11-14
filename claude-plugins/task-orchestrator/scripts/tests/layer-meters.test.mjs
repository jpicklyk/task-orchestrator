// layer-meters.test.mjs -- `run-planner provenance` layer flags (--coordinator, --session-transcript,
// --session-since, --session-until): one extra `layer-meters` member in the stdout JSON map, carrying
// the transcript meters of the coordinator and of the launching session.
//
// Every test runs the CLI over temp-dir fixtures with an explicit --session-dir/--projects-dir, so the
// real home is never scanned. Oracles: the frozen task-scope design (D1-D6) and the documented meters
// measure in lib/meters.mjs (one count per message.id, last line of an id wins; tokens = input +
// cacheWrite + output, cacheRead its own meter). Expected numbers are hand-summed in the fixtures
// below, never read back from the code under test. S1's golden was captured by running the same
// fixture against base commit 86c7d5c6 in a scratch worktree.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, utimesSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { planFixture, itemFixture } from './workflow-harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const RUN_PLANNER = join(HERE, '..', 'run-planner.mjs')

const RUN_ID = 'r-test-0201'
const COORD = 'coordinator:r-test-0201'

const COORD_WARNING = 'provenance: layer coordinator has no transcript meters (a gap, not zero)'
const SESSION_WARNING = 'provenance: layer session has no transcript meters (a gap, not zero)'
const NO_TS_WARNING = 'provenance: session transcript lines carry no timestamps; session meters cover the whole session'

// Captured at base 86c7d5c6 (scratch worktree) for the fixture below, no layer flag.
const GOLDEN_STDOUT = '{"item-aaaaaaaa":"adapter=claude-workflow run=r-test-0201 seats=implementer:sonnet model=sonnet isolation=worktree:/repo/.claude/worktrees/x-aaaaaaaa agents=1 tokens=unknown duration=unknown deferred=0 in-run-edges=0 orchestrator-turns=1 model-source=self-report meters=unknown seat-meters=implementer:unknown","item-bbbbbbbb":"adapter=claude-workflow run=r-test-0201 seats=implementer:sonnet model=sonnet isolation=worktree:/repo/.claude/worktrees/x-bbbbbbbb agents=1 tokens=unknown duration=unknown deferred=0 in-run-edges=0 orchestrator-turns=1 model-source=self-report meters=unknown seat-meters=implementer:unknown"}\n'
const GOLDEN_STDERR = '{"warning":"provenance: aaaaaaaa seat implementer has no transcript meters (a gap, not zero)"}\n{"warning":"provenance: bbbbbbbb seat implementer has no transcript meters (a gap, not zero)"}\n'

// ── fixtures ────────────────────────────────────────────────────────────────────────────────────

function assistant(id, u, ts) {
  const message = { role: 'assistant', model: 'claude-opus-5-5', usage: u }
  if (id !== null) message.id = id
  const line = { type: 'assistant', message }
  if (ts !== undefined) line.timestamp = ts
  return JSON.stringify(line)
}

const usage = (input, cacheWrite, cacheRead, output) => ({
  input_tokens: input,
  cache_creation_input_tokens: cacheWrite,
  cache_read_input_tokens: cacheRead,
  output_tokens: output,
})

function mkRun(prefix = 'layer-meters-') {
  const dir = mkdtempSync(join(tmpdir(), prefix))
  const mk = (short) => itemFixture({
    short,
    stages: [{ seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: { model: 'sonnet' }, output: 'implementer-v1' }],
    worktree: `/repo/.claude/worktrees/x-${short}`,
  })
  const a = mk('aaaaaaaa')
  const b = mk('bbbbbbbb')
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [a, b], runId: RUN_ID }), meta: { inRunEdges: [] } }
  const row = (it) => ({
    id: it.id, short: it.short, status: 'done', reason: 'ok', outputs: {},
    stages: [{ seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] }],
  })
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: RUN_ID, planDocSlug: `run/${RUN_ID}`,
    items: [row(a), row(b)], refused: [], deferred: [],
  }
  const planPath = join(dir, 'plan.json')
  const resultPath = join(dir, 'result.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(resultPath, JSON.stringify(result))
  const session = join(dir, 'session')
  mkdirSync(join(session, 'subagents'), { recursive: true })
  return { dir, session, planPath, resultPath, a, b }
}

function writeAgent(session, n, description, text, mtimeSec) {
  const meta = join(session, 'subagents', `agent-${n}.meta.json`)
  writeFileSync(meta, JSON.stringify({ description, model: 'opus' }))
  writeFileSync(join(session, 'subagents', `agent-${n}.jsonl`), text)
  if (mtimeSec !== undefined) utimesSync(meta, new Date(mtimeSec * 1000), new Date(mtimeSec * 1000))
}

// Coordinator transcript: m1 appears on two lines (the later one is final), m2 once.
// last-wins m1 = 10/20/300/4 ; m2 = 1/2/30/5 ; sum = 11/22/330/9.
const COORD_TEXT = [
  JSON.stringify({ type: 'user', message: { role: 'user', content: 'go' } }),
  assistant('m1', usage(10, 20, 300, 1)),
  assistant('m1', usage(10, 20, 300, 4)),
  assistant('m2', usage(1, 2, 30, 5)),
  '',
].join('\n')
const COORD_ENTRY = 'coordinator:11/22/330/9'

const T1 = '2026-11-14T10:00:00.000Z'
const T2 = '2026-11-14T11:00:00.000Z'
const T3 = '2026-11-14T12:00:00.000Z'
// Session transcript with timestamps: m1 100/200/3000/40 @T1 ; m2 1/2/30/4 @T2 ; m3 5/6/70/8 @T3.
// whole = 106/208/3100/52 ; m2+m3 = 6/8/100/12 ; m2 alone = 1/2/30/4 ; m1+m2 = 101/202/3030/44.
const SESSION_TEXT = [
  JSON.stringify({ type: 'user', timestamp: T1, message: { role: 'user', content: 'go' } }),
  assistant('m1', usage(100, 200, 3000, 40), T1),
  assistant('m2', usage(1, 2, 30, 4), T2),
  assistant('m3', usage(5, 6, 70, 8), T3),
  '',
].join('\n')
const SESSION_WHOLE = 'session:106/208/3100/52'

function writeSession(run, text, name = 'session.jsonl') {
  const p = join(run.dir, name)
  writeFileSync(p, text)
  return p
}

function cli(run, extra) {
  return spawnSync(
    process.execPath,
    [RUN_PLANNER, 'provenance', '--plan', run.planPath, '--result', run.resultPath, '--method', 'A', '--turns', '1', ...extra],
    { encoding: 'utf8' }
  )
}

function warnings(res) {
  return res.stderr
    .split('\n')
    .filter((l) => l.trim())
    .map((l) => {
      try { return JSON.parse(l).warning } catch { return undefined }
    })
    .filter((w) => typeof w === 'string')
}

function layerLine(res) {
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.ok(Object.hasOwn(out, 'layer-meters'), `no layer-meters member in ${res.stdout}`)
  return out['layer-meters']
}

// ── S1 / S11: byte identity and untouched item lines ────────────────────────────────────────────

test('S1: with no layer flag stdout, stderr and exit code equal the base-commit golden', () => {
  const run = mkRun()
  const res = cli(run, [])
  assert.equal(res.status, 0, res.stderr)
  assert.equal(res.stdout, GOLDEN_STDOUT)
  assert.equal(res.stderr, GOLDEN_STDERR)
})

test('S11: with layer flags the item members are byte-equal to the golden and layer-meters is appended last', () => {
  const run = mkRun()
  writeAgent(run.session, 1, COORD, COORD_TEXT)
  const sess = writeSession(run, SESSION_TEXT)
  const res = cli(run, ['--coordinator', COORD, '--session-dir', run.session, '--session-transcript', sess])
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  const golden = JSON.parse(GOLDEN_STDOUT)
  assert.deepEqual(Object.keys(out), [...Object.keys(golden), 'layer-meters'])
  for (const k of Object.keys(golden)) assert.equal(out[k], golden[k], `item line ${k} unchanged`)
})

// ── S2 / S3 / S13: the line grammar ─────────────────────────────────────────────────────────────

test('S2: --coordinator and --session-transcript give both entries, coordinator first, four meters each, whole-session', () => {
  const run = mkRun()
  writeAgent(run.session, 1, COORD, COORD_TEXT)
  const sess = writeSession(run, SESSION_TEXT)
  const res = cli(run, ['--coordinator', COORD, '--session-dir', run.session, '--session-transcript', sess])
  assert.equal(layerLine(res), `layer-meters=${COORD_ENTRY},${SESSION_WHOLE} run=${RUN_ID} session-window=whole-session`)
  assert.deepEqual(warnings(res).filter((w) => /layer|timestamps/.test(w)), [])
})

test('S3: cacheRead is its own third field and never enters another meter', () => {
  const run = mkRun()
  const sess = writeSession(run, [assistant('m1', { cache_read_input_tokens: 999 })].join('\n'))
  const res = cli(run, ['--session-transcript', sess])
  assert.equal(layerLine(res), `layer-meters=session:0/0/999/0 run=${RUN_ID} session-window=whole-session`)
  const run2 = mkRun()
  const sess2 = writeSession(run2, [assistant('m1', { input_tokens: 7, output_tokens: 3 })].join('\n'))
  assert.equal(layerLine(cli(run2, ['--session-transcript', sess2])), `layer-meters=session:7/0/0/3 run=${RUN_ID} session-window=whole-session`)
})

test('S13: only --session-transcript gives a session-only entry list', () => {
  const run = mkRun()
  const sess = writeSession(run, SESSION_TEXT)
  const res = cli(run, ['--session-transcript', sess])
  assert.equal(layerLine(res), `layer-meters=${SESSION_WHOLE} run=${RUN_ID} session-window=whole-session`)
})

test('only --coordinator gives a coordinator-only entry list with no session-window token', () => {
  const run = mkRun()
  writeAgent(run.session, 1, COORD, COORD_TEXT)
  const res = cli(run, ['--coordinator', COORD, '--session-dir', run.session])
  assert.equal(layerLine(res), `layer-meters=${COORD_ENTRY} run=${RUN_ID}`)
})

test('--projects-dir is an alternative root for the coordinator meta walk', () => {
  const run = mkRun()
  const projects = join(run.dir, 'projects')
  const nested = join(projects, 'proj-x', 'sess-1')
  mkdirSync(join(nested, 'subagents'), { recursive: true })
  writeAgent(nested, 1, COORD, COORD_TEXT)
  const res = cli(run, ['--coordinator', COORD, '--projects-dir', projects])
  assert.equal(layerLine(res), `layer-meters=${COORD_ENTRY} run=${RUN_ID}`)
})

test('a session transcript path containing spaces is read', () => {
  const run = mkRun()
  const spaced = join(run.dir, 'a folder with spaces')
  mkdirSync(spaced)
  const sess = writeSession(run, SESSION_TEXT, join('a folder with spaces', 'my session.jsonl'))
  assert.ok(sess.includes(' '))
  assert.equal(layerLine(cli(run, ['--session-transcript', sess])), `layer-meters=${SESSION_WHOLE} run=${RUN_ID} session-window=whole-session`)
})

// ── S4: the run window ──────────────────────────────────────────────────────────────────────────

test('S4: since (ISO) and until (epoch ms) keep lines inside the window, bounds inclusive, window token run', () => {
  const run = mkRun()
  const sess = writeSession(run, SESSION_TEXT)
  const res = cli(run, ['--session-transcript', sess, '--session-since', T2, '--session-until', String(Date.parse(T3))])
  assert.equal(layerLine(res), `layer-meters=session:6/8/100/12 run=${RUN_ID} session-window=run`)
  assert.deepEqual(warnings(res).filter((w) => /layer|timestamps/.test(w)), [])
})

test('S4: epoch-ms since and ISO until, and a bound exactly equal to one line timestamp includes that line', () => {
  const run = mkRun()
  const sess = writeSession(run, SESSION_TEXT)
  const both = cli(run, ['--session-transcript', sess, '--session-since', String(Date.parse(T2)), '--session-until', T2])
  assert.equal(layerLine(both), `layer-meters=session:1/2/30/4 run=${RUN_ID} session-window=run`)
  const untilOnly = cli(run, ['--session-transcript', sess, '--session-until', T2])
  assert.equal(layerLine(untilOnly), `layer-meters=session:101/202/3030/44 run=${RUN_ID} session-window=run`)
  const sinceOnly = cli(run, ['--session-transcript', sess, '--session-since', T2])
  assert.equal(layerLine(sinceOnly), `layer-meters=session:6/8/100/12 run=${RUN_ID} session-window=run`)
})

test('S4: a one-millisecond shift of a bound past a line timestamp drops that line', () => {
  const run = mkRun()
  const sess = writeSession(run, SESSION_TEXT)
  const afterT2 = cli(run, ['--session-transcript', sess, '--session-since', String(Date.parse(T2) + 1)])
  assert.equal(layerLine(afterT2), `layer-meters=session:5/6/70/8 run=${RUN_ID} session-window=run`)
  const beforeT2 = cli(run, ['--session-transcript', sess, '--session-until', String(Date.parse(T2) - 1)])
  assert.equal(layerLine(beforeT2), `layer-meters=session:100/200/3000/40 run=${RUN_ID} session-window=run`)
})

test('the window does not apply to the coordinator entry', () => {
  const run = mkRun()
  writeAgent(run.session, 1, COORD, COORD_TEXT)
  const sess = writeSession(run, SESSION_TEXT)
  const res = cli(run, ['--coordinator', COORD, '--session-dir', run.session, '--session-transcript', sess, '--session-since', T3])
  assert.equal(layerLine(res), `layer-meters=${COORD_ENTRY},session:5/6/70/8 run=${RUN_ID} session-window=run`)
})

// ── S5 / S6 / S7: gaps are unknown, never zero ──────────────────────────────────────────────────

test('S5: a coordinator label with no meta file reads coordinator:unknown with its warning, exit 0, session still measured', () => {
  const run = mkRun()
  const sess = writeSession(run, SESSION_TEXT)
  const res = cli(run, ['--coordinator', 'coordinator:nobody', '--session-dir', run.session, '--session-transcript', sess])
  assert.equal(layerLine(res), `layer-meters=coordinator:unknown,${SESSION_WHOLE} run=${RUN_ID} session-window=whole-session`)
  const w = warnings(res)
  assert.ok(w.includes(COORD_WARNING), JSON.stringify(w))
  assert.ok(!w.includes(SESSION_WARNING), JSON.stringify(w))
})

test('S6: a missing session transcript reads session:unknown with its warning and no session-window token, exit 0', () => {
  const run = mkRun()
  writeAgent(run.session, 1, COORD, COORD_TEXT)
  const res = cli(run, ['--coordinator', COORD, '--session-dir', run.session, '--session-transcript', join(run.dir, 'no-such.jsonl')])
  assert.equal(layerLine(res), `layer-meters=${COORD_ENTRY},session:unknown run=${RUN_ID}`)
  const w = warnings(res)
  assert.ok(w.includes(SESSION_WARNING), JSON.stringify(w))
  assert.ok(!w.includes(COORD_WARNING), JSON.stringify(w))
})

test('S7: an empty transcript, a transcript with no assistant usage line, and a window holding no usage line all read session:unknown, never zeros', () => {
  const userOnly = JSON.stringify({ type: 'user', timestamp: T1, message: { role: 'user', content: 'go' } })
  const assistantNoUsage = JSON.stringify({ type: 'assistant', timestamp: T1, message: { id: 'm1', role: 'assistant', content: [] } })
  const cases = [
    ['empty', '', []],
    ['user only', userOnly, []],
    ['assistant without usage', assistantNoUsage, []],
    ['window before every line', SESSION_TEXT, ['--session-until', '2026-11-14T09:00:00.000Z']],
    ['window after every line', SESSION_TEXT, ['--session-since', '2026-11-14T13:00:00.000Z']],
  ]
  for (const [name, text, flags] of cases) {
    const run = mkRun()
    const sess = writeSession(run, text)
    const res = cli(run, ['--session-transcript', sess, ...flags])
    assert.equal(layerLine(res), `layer-meters=session:unknown run=${RUN_ID}`, name)
    assert.ok(warnings(res).includes(SESSION_WARNING), name)
  }
})

test('both layers missing: both entries read unknown, both warnings, exit 0', () => {
  const run = mkRun()
  const res = cli(run, ['--coordinator', 'coordinator:nobody', '--session-dir', run.session, '--session-transcript', join(run.dir, 'gone.jsonl')])
  assert.equal(layerLine(res), `layer-meters=coordinator:unknown,session:unknown run=${RUN_ID}`)
  const w = warnings(res)
  assert.ok(w.includes(COORD_WARNING) && w.includes(SESSION_WARNING), JSON.stringify(w))
})

// ── S8: misuse exits 2 ──────────────────────────────────────────────────────────────────────────

// Oracle (amendment A1): exit 2, empty stdout, one stderr line that parses as JSON with
// error === 'invalid args' and a string detail; the detail names the flag at fault. The error
// wording is not a contract, so only the tokens the caller needs are asserted.
function usageError(res, tokens) {
  assert.equal(res.status, 2, res.stderr)
  assert.equal(res.stdout, '', 'a usage error prints nothing on stdout')
  const lines = res.stderr.split('\n').filter((l) => l.trim())
  assert.equal(lines.length, 1, res.stderr)
  const env = JSON.parse(lines[0])
  assert.equal(env.error, 'invalid args', res.stderr)
  assert.equal(typeof env.detail, 'string', res.stderr)
  for (const t of tokens) assert.ok(env.detail.includes(t), `detail ${JSON.stringify(env.detail)} lacks ${t}`)
}

test('S8: a bad --session-since or --session-until value exits 2 naming the flag and value', () => {
  const run = mkRun()
  const sess = writeSession(run, SESSION_TEXT)
  for (const [flag, value] of [['--session-since', 'yesterday'], ['--session-until', 'not-a-time']]) {
    const res = cli(run, ['--session-transcript', sess, flag, value])
    usageError(res, [flag, value])
  }
})

test('S8: a window flag without --session-transcript exits 2 naming --session-transcript', () => {
  const run = mkRun()
  for (const flag of ['--session-since', '--session-until']) {
    const res = cli(run, [flag, T2])
    usageError(res, ['--session-transcript'])
  }
})

test('S8: any layer flag together with --item exits 2 naming --item', () => {
  const run = mkRun()
  const sess = writeSession(run, SESSION_TEXT)
  for (const flags of [
    ['--coordinator', COORD, '--session-dir', run.session],
    ['--session-transcript', sess],
    ['--session-transcript', sess, '--session-since', T2],
  ]) {
    const res = cli(run, ['--item', 'aaaaaaaa', ...flags])
    usageError(res, ['--item'])
  }
})

test('--item alone still works and carries no layer-meters member', () => {
  const run = mkRun()
  const res = cli(run, ['--item', 'aaaaaaaa'])
  assert.equal(res.status, 0, res.stderr)
  assert.deepEqual(Object.keys(JSON.parse(res.stdout)), ['item-aaaaaaaa'])
})

// ── S9 / S10 / S12: counting and timestamps ─────────────────────────────────────────────────────

test('S9: one message id on three lines counts once with the last line winning; id-less lines each count alone', () => {
  const run = mkRun()
  const text = [
    assistant('m1', usage(1, 1, 1, 1)),
    assistant('m1', usage(2, 2, 2, 2)),
    assistant('m1', usage(10, 20, 30, 40)),
    assistant(null, usage(1, 0, 0, 0)),
    assistant(null, usage(1, 0, 0, 0)),
  ].join('\n')
  const sess = writeSession(run, text)
  // m1 final 10/20/30/40 + two id-less lines 1/0/0/0 each = 12/20/30/40
  assert.equal(layerLine(cli(run, ['--session-transcript', sess])), `layer-meters=session:12/20/30/40 run=${RUN_ID} session-window=whole-session`)
})

test('S9: a repeated id on the coordinator transcript counts once as well', () => {
  const run = mkRun()
  writeAgent(run.session, 1, COORD, [assistant('x', usage(5, 5, 5, 5)), assistant('x', usage(5, 5, 5, 5)), assistant('x', usage(5, 5, 5, 5))].join('\n'))
  assert.equal(layerLine(cli(run, ['--coordinator', COORD, '--session-dir', run.session])), `layer-meters=coordinator:5/5/5/5 run=${RUN_ID}`)
})

test('S10: a window flag with no timestamped usage line measures the whole transcript, says whole-session, and warns', () => {
  const run = mkRun()
  const text = [assistant('m1', usage(1, 2, 3, 4)), assistant('m2', usage(10, 20, 30, 40))].join('\n')
  const sess = writeSession(run, text)
  const res = cli(run, ['--session-transcript', sess, '--session-since', T1, '--session-until', T3])
  assert.equal(layerLine(res), `layer-meters=session:11/22/33/44 run=${RUN_ID} session-window=whole-session`)
  assert.ok(warnings(res).includes(NO_TS_WARNING), JSON.stringify(warnings(res)))
})

test('S10: no window flag and no timestamps measures the whole transcript without the no-timestamps warning', () => {
  const run = mkRun()
  const sess = writeSession(run, [assistant('m1', usage(1, 2, 3, 4))].join('\n'))
  const res = cli(run, ['--session-transcript', sess])
  assert.equal(layerLine(res), `layer-meters=session:1/2/3/4 run=${RUN_ID} session-window=whole-session`)
  assert.ok(!warnings(res).includes(NO_TS_WARNING), JSON.stringify(warnings(res)))
})

test('S14: with a window, a usage line without a timestamp is not measured, and no no-timestamps warning is given', () => {
  const run = mkRun()
  const text = [assistant('w1', usage(1, 2, 30, 4), T2), assistant('w2', usage(100, 200, 3000, 400))].join('\n')
  const sess = writeSession(run, text)
  const res = cli(run, ['--session-transcript', sess, '--session-since', T1])
  // only w1 (timestamped, inside the window) counts: 1/2/30/4 ; w2 has no timestamp and is outside the window rule
  assert.equal(layerLine(res), `layer-meters=session:1/2/30/4 run=${RUN_ID} session-window=run`)
  assert.ok(!warnings(res).includes(NO_TS_WARNING), JSON.stringify(warnings(res)))
})

test('S12: malformed JSON lines and non-assistant lines are skipped', () => {
  const run = mkRun()
  const text = [
    '{not json',
    'garbage "assistant"',
    JSON.stringify({ type: 'user', message: { id: 'u1', usage: usage(500, 500, 500, 500) } }),
    JSON.stringify({ type: 'attachment', attachment: {} }),
    assistant('m1', usage(1, 2, 3, 4)),
    '',
  ].join('\n')
  const sess = writeSession(run, text)
  assert.equal(layerLine(cli(run, ['--session-transcript', sess])), `layer-meters=session:1/2/3/4 run=${RUN_ID} session-window=whole-session`)
})

// ── probes: coordinator label matching ──────────────────────────────────────────────────────────

test('probe: a coordinator label that is only a prefix of a meta description, or only the fix-cycle variant, does not match', () => {
  const run = mkRun()
  writeAgent(run.session, 1, `${COORD}-extra`, COORD_TEXT)
  writeAgent(run.session, 2, 'coordinator-a1:r-test-0201', COORD_TEXT)
  writeAgent(run.session, 3, `${COORD}-a1`, COORD_TEXT)
  const res = cli(run, ['--coordinator', COORD, '--session-dir', run.session])
  assert.equal(layerLine(res), `layer-meters=coordinator:unknown run=${RUN_ID}`)
  assert.ok(warnings(res).includes(COORD_WARNING))
})

test('probe: a display tag on the meta description is stripped for the exact match', () => {
  const run = mkRun()
  writeAgent(run.session, 1, `${COORD} [opus/high]`, COORD_TEXT)
  assert.equal(layerLine(cli(run, ['--coordinator', COORD, '--session-dir', run.session])), `layer-meters=${COORD_ENTRY} run=${RUN_ID}`)
})

test('probe: two same-label coordinator meta files in one session are summed', () => {
  // 11/22/330/9 (COORD_TEXT) + 1000/1000/1000/1000, summed by hand, whichever file is newer.
  const SUMMED = `layer-meters=coordinator:1011/1022/1330/1009 run=${RUN_ID}`
  const run = mkRun()
  writeAgent(run.session, 1, COORD, [assistant('o', usage(1000, 1000, 1000, 1000))].join('\n'), 1_700_000_000)
  writeAgent(run.session, 2, COORD, COORD_TEXT, 1_800_000_000)
  assert.equal(layerLine(cli(run, ['--coordinator', COORD, '--session-dir', run.session])), SUMMED)
  const run2 = mkRun()
  writeAgent(run2.session, 1, COORD, COORD_TEXT, 1_700_000_000)
  writeAgent(run2.session, 2, COORD, [assistant('o', usage(1000, 1000, 1000, 1000))].join('\n'), 1_800_000_000)
  assert.equal(layerLine(cli(run2, ['--coordinator', COORD, '--session-dir', run2.session])), SUMMED)
})

// ── probes: odd meter values ────────────────────────────────────────────────────────────────────

test('probe: a string meter counts 0 and a negative numeric meter is summed as is', () => {
  const run = mkRun()
  const text = [
    assistant('m1', { input_tokens: '5', cache_creation_input_tokens: 2, cache_read_input_tokens: 3, output_tokens: 4 }),
    assistant('m2', { input_tokens: -3, cache_creation_input_tokens: 0, cache_read_input_tokens: 0, output_tokens: 1 }),
  ].join('\n')
  const sess = writeSession(run, text)
  // m1 input '5' -> 0 : 0/2/3/4 ; m2 : -3/0/0/1 ; sum -3/2/3/5
  assert.equal(layerLine(cli(run, ['--session-transcript', sess])), `layer-meters=session:-3/2/3/5 run=${RUN_ID} session-window=whole-session`)
})
