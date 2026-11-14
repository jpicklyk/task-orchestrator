// session-meters.test.mjs -- `run-planner session-meters` (one session folder metered by label class),
// the sums collectDispatchMeta now takes over a session's same-label transcripts, classifyAgentLabel,
// sessionFolderOf and windowTranscript's dropped count.
//
// Oracles are the frozen task-scope decisions D1-D12 and the interface contract on the parent feature,
// plus the documented measure in lib/meters.mjs (one count per message.id, the last line of an id wins;
// tokens = input + cacheWrite + output, cacheRead its own meter). Every expected number is hand-summed in
// the comment above its fixture, never read back from the code under test. Scenario ids S1..S16 follow the
// frozen test-plan. S13's provenance byte-identity half is held by layer-meters.test.mjs S1 (golden
// captured at the base commit), unchanged by this item.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, utimesSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { classifyAgentLabel, collectDispatchMeta, sessionFolderOf, windowTranscript } from '../run-exec-lib.mjs'
import { transcriptMeters } from '../lib/meters.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const RUN_PLANNER = join(HERE, '..', 'run-planner.mjs')

const T1 = '2026-11-14T10:00:00.000Z'
const T2 = '2026-11-14T11:00:00.000Z'
const T3 = '2026-11-14T12:00:00.000Z'
const MS = (t) => Date.parse(t)

// -- fixtures ---------------------------------------------------------------------------------------

const usage = (input, cacheWrite, cacheRead, output) => ({
  input_tokens: input,
  cache_creation_input_tokens: cacheWrite,
  cache_read_input_tokens: cacheRead,
  output_tokens: output,
})

function line(id, u, { ts, effort = 'high', model = 'claude-opus-5-5' } = {}) {
  const message = { role: 'assistant', model, usage: u }
  if (id !== null) message.id = id
  const l = { type: 'assistant', effort, message }
  if (ts !== undefined) l.timestamp = ts
  return JSON.stringify(l)
}

const text = (...lines) => lines.join('\n') + '\n'

// <root>/<folder> is the session folder, <root>/<folder>.jsonl the main transcript.
function mkSession(folder = 's') {
  const root = mkdtempSync(join(tmpdir(), 'session-meters-'))
  const s = join(root, folder)
  mkdirSync(join(s, 'subagents'), { recursive: true })
  return { root, s, main: `${s}.jsonl` }
}

// put(sess, n, description, body): agent-<n> under subagents (or sub, e.g. 'workflows/wf_x').
// description undefined -> no meta file; description === null -> meta without a description.
function put(sess, n, description, body, { model = 'opus', mtimeSec, sub = '' } = {}) {
  const dir = sub ? join(sess.s, 'subagents', sub) : join(sess.s, 'subagents')
  mkdirSync(dir, { recursive: true })
  if (description !== undefined) {
    const meta = description === null ? { model } : { description, model }
    writeFileSync(join(dir, `agent-${n}.meta.json`), JSON.stringify(meta))
  }
  const jsonl = join(dir, `agent-${n}.jsonl`)
  writeFileSync(jsonl, body)
  if (mtimeSec !== undefined) utimesSync(jsonl, new Date(mtimeSec * 1000), new Date(mtimeSec * 1000))
}

function cli(args) {
  return spawnSync(process.execPath, [RUN_PLANNER, 'session-meters', ...args], { encoding: 'utf8' })
}

function warningsOf(res) {
  return res.stderr
    .split('\n')
    .filter((l) => l.trim())
    .map((l) => {
      try { return JSON.parse(l).warning } catch { return undefined }
    })
    .filter((w) => typeof w === 'string')
}

function ok(args) {
  const res = cli(args)
  assert.equal(res.status, 0, res.stderr)
  return { res, doc: JSON.parse(res.stdout), warnings: warningsOf(res) }
}

const METER_ORDER = ['input', 'cacheWrite', 'cacheRead', 'output', 'tokens']
const ROW_ORDER = ['label', 'class', 'model', 'effort', 'dispatches', ...METER_ORDER]
const CLASS_ORDER = ['agents', ...METER_ORDER]

// A session with five rows' worth of seat, lane and coordinator transcripts plus a main transcript.
//  implementer:aaaaaaaa        1/1/1/1     tokens 1+1+1 = 3          (opus, high)
//  reviewer:aaaaaaaa           2/2/2/2 + 3/3/3/3 = 5/5/5/5  tokens 5+5+5 = 15, dispatches 2
//                              newer transcript is sonnet / medium
//  reviewer.review-quality:cccccccc  7/7/7/7  tokens 21
//  coordinator:bbbbbbbb        10/10/10/10  tokens 30
//  main s.jsonl                100/200/300/400  tokens 100+200+400 = 700
function mkMixed() {
  const sess = mkSession()
  put(sess, 1, 'implementer:aaaaaaaa [opus/high]', text(line('i1', usage(1, 1, 1, 1))))
  put(sess, 2, 'reviewer:aaaaaaaa', text(line('r1', usage(2, 2, 2, 2), { effort: 'low' })), { mtimeSec: 1_700_000_000 })
  put(sess, 3, 'reviewer:aaaaaaaa [sonnet/medium]', text(line('r2', usage(3, 3, 3, 3), { effort: 'medium', model: 'claude-sonnet-5-5' })), { model: 'sonnet', mtimeSec: 1_800_000_000 })
  put(sess, 4, 'reviewer.review-quality:cccccccc', text(line('l1', usage(7, 7, 7, 7))))
  put(sess, 5, 'coordinator:bbbbbbbb', text(line('c1', usage(10, 10, 10, 10))))
  writeFileSync(sess.main, text(line('mm', usage(100, 200, 300, 400))))
  return sess
}

// -- S1: one labelled agent ------------------------------------------------------------------------

test('S1: one labelled agent is one row; m1 repeated on two lines counts once (last line wins), keys in contract order', () => {
  // m1 final 10/20/30/40, m2 5/6/7/8 -> 15/26/37/48 ; tokens 15+26+48 = 89
  const sess = mkSession()
  put(sess, 1, 'implementer:aaaaaaaa [sonnet/high]', text(
    line('m1', usage(1, 2, 3, 4)),
    line('m1', usage(10, 20, 30, 40)),
    line('m2', usage(5, 6, 7, 8)),
  ), { model: 'sonnet' })
  const { doc, warnings } = ok(['--session-dir', sess.s])
  assert.deepEqual(Object.keys(doc), ['contract', 'sessionDir', 'agents', 'main', 'byClass', 'lines'])
  assert.equal(doc.contract, 'run-wave/session-meters-v1')
  assert.equal(doc.sessionDir, sess.s)
  assert.equal(doc.agents.length, 1)
  assert.deepEqual(Object.keys(doc.agents[0]), ROW_ORDER)
  assert.deepEqual(doc.agents[0], {
    label: 'implementer:aaaaaaaa', class: 'seat', model: 'sonnet', effort: 'high', dispatches: 1,
    input: 15, cacheWrite: 26, cacheRead: 37, output: 48, tokens: 89,
  })
  assert.equal(doc.main, null, 'no <dir>.jsonl')
  assert.deepEqual(doc.lines, [], 'no --label, no lines')
  assert.deepEqual(warnings, [], 'no bound, no window warnings')
})

// -- S2: one label, two transcripts ----------------------------------------------------------------

test('S2: two transcripts of one label (one display-tagged) are one row, dispatches 2, meters summed; model and effort come from the newer', () => {
  // both carry message id m1 and both count: 1/1/1/1 + 2/3/4/5 = 3/4/5/6 ; tokens 3+4+6 = 13
  const sess = mkSession()
  put(sess, 1, 'implementer:aaaaaaaa', text(line('m1', usage(1, 1, 1, 1), { effort: 'low' })), { model: 'opus', mtimeSec: 1_700_000_000 })
  put(sess, 2, 'implementer:aaaaaaaa [sonnet/high]', text(line('m1', usage(2, 3, 4, 5), { effort: 'high', model: 'claude-sonnet-5-5' })), { model: 'sonnet', mtimeSec: 1_800_000_000 })
  const { doc } = ok(['--session-dir', sess.s])
  assert.equal(doc.agents.length, 1)
  assert.deepEqual(doc.agents[0], {
    label: 'implementer:aaaaaaaa', class: 'seat', model: 'sonnet', effort: 'high', dispatches: 2,
    input: 3, cacheWrite: 4, cacheRead: 5, output: 6, tokens: 13,
  })
})

// -- S3: classification ----------------------------------------------------------------------------

test('S3: classifyAgentLabel maps each label shape to its class, case-sensitive and whole-label', () => {
  for (const l of ['implementer:ab12cd34', 'implementer-a1:ab12cd34', 'reviewer:ab12cd34', 'planner-a12:ab12cd34']) {
    assert.equal(classifyAgentLabel(l), 'seat', l)
  }
  assert.equal(classifyAgentLabel('reviewer.review-quality:ab12cd34'), 'lane')
  assert.equal(classifyAgentLabel('red-proofs:ab12cd34'), 'red-proofs')
  assert.equal(classifyAgentLabel('retrospective:1b056448'), 'retrospective')
  assert.equal(classifyAgentLabel('coordinator:ab12cd34'), 'coordinator')
  assert.equal(classifyAgentLabel('research:x'), 'research')
  for (const l of ['Implementer:AB12CD34', 'reviewer:ab12cd345', 'reviewer:ab12cd3', 'coordinator:r-test-01', 'red-proofs-a1:ab12cd34', 'free text', 'research:', null, '']) {
    assert.equal(classifyAgentLabel(l), 'unlabelled', String(l))
  }
})

// -- S4: byClass and main --------------------------------------------------------------------------

test('S4: byClass agents is the sum of dispatches and its meters the row sums; main is the meters of <dir>.jsonl', () => {
  const sess = mkMixed()
  const { doc } = ok(['--session-dir', sess.s])
  // rows ordered by label: '.' sorts before ':'
  assert.deepEqual(doc.agents.map((a) => a.label), [
    'coordinator:bbbbbbbb', 'implementer:aaaaaaaa', 'reviewer.review-quality:cccccccc', 'reviewer:aaaaaaaa',
  ])
  assert.deepEqual(doc.agents.map((a) => a.class), ['coordinator', 'seat', 'lane', 'seat'])
  assert.deepEqual(doc.agents.map((a) => a.dispatches), [1, 1, 1, 2])
  assert.deepEqual(Object.keys(doc.byClass).sort(), ['coordinator', 'lane', 'seat'])
  for (const k of Object.keys(doc.byClass)) assert.deepEqual(Object.keys(doc.byClass[k]), CLASS_ORDER, k)
  // seat: implementer 1/1/1/1 (3) + reviewer 5/5/5/5 (15), 1 + 2 dispatches -> 6/6/6/6, tokens 18
  assert.deepEqual(doc.byClass.seat, { agents: 3, input: 6, cacheWrite: 6, cacheRead: 6, output: 6, tokens: 18 })
  assert.deepEqual(doc.byClass.lane, { agents: 1, input: 7, cacheWrite: 7, cacheRead: 7, output: 7, tokens: 21 })
  assert.deepEqual(doc.byClass.coordinator, { agents: 1, input: 10, cacheWrite: 10, cacheRead: 10, output: 10, tokens: 30 })
  assert.deepEqual(doc.main, { input: 100, cacheWrite: 200, cacheRead: 300, output: 400, tokens: 700 })
})

// -- S5: --label lines -----------------------------------------------------------------------------

test('S5: --label is repeatable, lines follow request order in the agent-meters format, matching is exact', () => {
  const sess = mkMixed()
  const { doc, warnings } = ok(['--session-dir', sess.s, '--label', 'reviewer:aaaaaaaa', '--label', 'implementer:aaaaaaaa'])
  assert.deepEqual(doc.lines, [
    'agent-meters=reviewer:aaaaaaaa:sonnet:medium:5/5/5/5 dispatches=2',
    'agent-meters=implementer:aaaaaaaa:opus:high:1/1/1/1 dispatches=1',
  ])
  assert.deepEqual(warnings, [])
  // exact only: a shorter short id, a longer one, a fix-cycle spelling and a lane spelling are not this label
  const miss = ok(['--session-dir', sess.s, '--label', 'implementer:aaaaaaa', '--label', 'implementer:aaaaaaaaa', '--label', 'implementer-a1:aaaaaaaa', '--label', 'reviewer:cccccccc'])
  assert.deepEqual(miss.doc.lines, [
    'agent-meters=implementer:aaaaaaa:unknown',
    'agent-meters=implementer:aaaaaaaaa:unknown',
    'agent-meters=implementer-a1:aaaaaaaa:unknown',
    'agent-meters=reviewer:cccccccc:unknown',
  ])
})

// -- S6: a missing label is a gap ------------------------------------------------------------------

test('S6: a --label with no transcript prints <label>:unknown and one warning, exit 0, never zeros', () => {
  const sess = mkMixed()
  const { res, doc, warnings } = ok(['--session-dir', sess.s, '--label', 'planner:dddddddd'])
  assert.equal(res.status, 0)
  assert.deepEqual(doc.lines, ['agent-meters=planner:dddddddd:unknown'])
  assert.deepEqual(warnings, ['session-meters: no transcript carries label planner:dddddddd (a gap, not zero)'])
  assert.equal(doc.agents.some((a) => a.label === 'planner:dddddddd'), false, 'no invented row')
})

// -- S7: invalid arguments -------------------------------------------------------------------------

test('S7: invalid arguments exit 2 with an empty stdout and one invalid-args envelope that names the flag', () => {
  const sess = mkSession()
  const notDir = join(sess.root, 'plain.txt')
  writeFileSync(notDir, 'x')
  const cases = [
    { args: [], detail: '--session-dir is required' },
    { args: ['--session-dir', notDir], detail: `--session-dir ${notDir} is not a directory` },
    { args: ['--session-dir', sess.s, '--since', 'abc'], detail: '--since must be an epoch-milliseconds integer' },
    { args: ['--session-dir', sess.s, '--until', '1.5'], detail: '--until must be an epoch-milliseconds integer' },
    { args: ['--session-dir', sess.s, '--label'], detail: '--label needs a value' },
    { args: ['--session-dir', sess.s, '--label', '--since', '1'], detail: '--label needs a value' },
  ]
  for (const c of cases) {
    const res = cli(c.args)
    assert.equal(res.status, 2, `${c.args.join(' ')}: ${res.stdout}${res.stderr}`)
    assert.equal(res.stdout, '', c.detail)
    const err = res.stderr.split('\n').filter((l) => l.trim())
    assert.equal(err.length, 1, `one stderr line for ${c.detail}`)
    const parsed = JSON.parse(err[0])
    assert.equal(parsed.error, 'invalid args')
    assert.equal(parsed.detail, c.detail)
  }
})

// -- S8: unlabelled transcripts are never dropped --------------------------------------------------

test('S8: no meta, a meta without description and a tag-only description are three null-label rows; one free text twice is one row; all unlabelled', () => {
  const sess = mkSession()
  put(sess, 1, undefined, text(line('a', usage(1, 0, 0, 0))))
  put(sess, 2, null, text(line('b', usage(2, 0, 0, 0))))
  put(sess, 3, ' [opus/high]', text(line('c', usage(3, 0, 0, 0))))
  put(sess, 4, 'research notes', text(line('d', usage(4, 0, 0, 0))))
  put(sess, 5, 'research notes [opus/high]', text(line('e', usage(5, 0, 0, 0))))
  const { doc } = ok(['--session-dir', sess.s])
  // labelled rows first (the free text: 4 + 5 = 9, dispatches 2), then null rows by path: agent-1, agent-2, agent-3
  assert.deepEqual(doc.agents.map((a) => a.label), ['research notes', null, null, null])
  assert.deepEqual(doc.agents.map((a) => a.class), ['unlabelled', 'unlabelled', 'unlabelled', 'unlabelled'])
  assert.deepEqual(doc.agents.map((a) => a.dispatches), [2, 1, 1, 1])
  assert.deepEqual(doc.agents.map((a) => a.input), [9, 1, 2, 3])
  assert.deepEqual(doc.agents.map((a) => a.tokens), [9, 1, 2, 3])
  assert.deepEqual(doc.byClass, { unlabelled: { agents: 5, input: 15, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 15 } })
})

// -- S9: nested workflow folders -------------------------------------------------------------------

test('S9: a transcript under subagents/workflows/wf_x is found and metered', () => {
  const sess = mkSession()
  put(sess, 1, 'implementer:aaaaaaaa', text(line('a', usage(1, 0, 0, 0))))
  put(sess, 9, 'planner:aaaaaaaa', text(line('p', usage(6, 0, 0, 0))), { sub: join('workflows', 'wf_x') })
  const { doc } = ok(['--session-dir', sess.s, '--label', 'planner:aaaaaaaa'])
  assert.deepEqual(doc.agents.map((a) => [a.label, a.input]), [['implementer:aaaaaaaa', 1], ['planner:aaaaaaaa', 6]])
  assert.deepEqual(doc.lines, ['agent-meters=planner:aaaaaaaa:opus:high:6/0/0/0 dispatches=1'])
})

// -- S10: --since / --until -----------------------------------------------------------------------

test('S10: --since and --until bound agent and main lines by timestamp, both ends inclusive', () => {
  // agent lines T1 1, T2 2, T3 4 ; main lines T1 10, T2 20, T3 40 (input only)
  const body = (a, b, c) => text(line('x1', usage(a, 0, 0, 0), { ts: T1 }), line('x2', usage(b, 0, 0, 0), { ts: T2 }), line('x3', usage(c, 0, 0, 0), { ts: T3 }))
  const sess = mkSession()
  put(sess, 1, 'implementer:aaaaaaaa', body(1, 2, 4))
  writeFileSync(sess.main, body(10, 20, 40))
  const inputs = (args) => {
    const { doc, warnings } = ok(['--session-dir', sess.s, ...args])
    assert.deepEqual(warnings, [], args.join(' '))
    return [doc.agents[0].input, doc.main.input]
  }
  assert.deepEqual(inputs([]), [7, 70])
  assert.deepEqual(inputs(['--since', String(MS(T2))]), [6, 60], 'T2 itself is in')
  assert.deepEqual(inputs(['--until', String(MS(T2))]), [3, 30], 'T2 itself is in')
  assert.deepEqual(inputs(['--since', String(MS(T2)), '--until', String(MS(T2))]), [2, 20], 'a one-point window')
  assert.deepEqual(inputs(['--since', String(MS(T2) + 1), '--until', String(MS(T3) - 1)]), [0, 0], 'between the lines')
})

// -- S11: main absent vs empty ---------------------------------------------------------------------

test('S11: no <dir>.jsonl is main null; a <dir>.jsonl with no usage is all-zero meters', () => {
  const none = mkSession()
  put(none, 1, 'implementer:aaaaaaaa', text(line('a', usage(1, 0, 0, 0))))
  assert.equal(ok(['--session-dir', none.s]).doc.main, null)
  const empty = mkSession()
  put(empty, 1, 'implementer:aaaaaaaa', text(line('a', usage(1, 0, 0, 0))))
  writeFileSync(empty.main, text(JSON.stringify({ type: 'user', message: { role: 'user', content: 'go' } })))
  assert.deepEqual(ok(['--session-dir', empty.s]).doc.main, { input: 0, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 0 })
})

// -- S12 / S14: collectDispatchMeta over a session ------------------------------------------------

function fakeIo({ files, transcripts, walk }) {
  return {
    walkMeta: (root) => walk[root] || [],
    readInput: (p) => (Object.hasOwn(files, p) ? files[p] : null),
    readText: (p) => (Object.hasOwn(transcripts, p) ? transcripts[p] : null),
  }
}

const jl = (path) => path.replace(/\.meta\.json$/, '.jsonl')

test('S12: same-label transcripts of one session folder are summed, dispatches counts them, the newest gives the transcript; another session folder adds nothing', () => {
  const A = '/r/s/subagents/agent-1.meta.json'
  const B = '/r/s/subagents/agent-2.meta.json'
  const C = '/r/t/subagents/agent-3.meta.json'
  const label = 'planner-a1:ab12cd34'
  const mk = (mtimes) => fakeIo({
    walk: { '/r': [{ path: A, mtimeMs: mtimes[0] }, { path: B, mtimeMs: mtimes[1] }, { path: C, mtimeMs: mtimes[2] }] },
    files: {
      [A]: { description: label, model: 'opus' },
      [B]: { description: `${label} [opus/high]`, model: 'opus' },
      [C]: { description: label, model: 'opus' },
    },
    transcripts: {
      [jl(A)]: text(line('m1', usage(1, 1, 1, 1))),
      [jl(B)]: text(line('m1', usage(2, 2, 2, 2))),
      [jl(C)]: text(line('m1', usage(100, 100, 100, 100))),
    },
  })
  // newest in /r/s: A + B = 3/3/3/3 ; tokens 3+3+3 = 9 ; C (older, other session) is not added
  const a = collectDispatchMeta({ io: mk([100, 200, 150]), roots: ['/r'], labels: [label] })[label]
  assert.equal(a.dispatches, 2)
  assert.equal(a.transcript, jl(B), 'the newest entry gives the transcript')
  assert.equal(a.mtimeMs, 200)
  assert.deepEqual(a.usage, { input: 3, cacheWrite: 3, cacheRead: 3, output: 3, tokens: 9 })
  // newest in /r/t: only C counts
  const c = collectDispatchMeta({ io: mk([100, 200, 300]), roots: ['/r'], labels: [label] })[label]
  assert.equal(c.dispatches, 1)
  assert.equal(c.transcript, jl(C))
  assert.deepEqual(c.usage, { input: 100, cacheWrite: 100, cacheRead: 100, output: 100, tokens: 300 })
  // --since drops A before the sum: B alone
  const since = collectDispatchMeta({ io: mk([100, 200, 150]), roots: ['/r'], labels: [label], since: 150 })[label]
  assert.equal(since.dispatches, 1)
  assert.deepEqual(since.usage, { input: 2, cacheWrite: 2, cacheRead: 2, output: 2, tokens: 6 })
})

test('S13: with one transcript per label a record carries the old keys in their old order and gains dispatches: 1 as its last key', () => {
  const A = '/r/s/subagents/agent-1.meta.json'
  const io = fakeIo({
    walk: { '/r': [{ path: A, mtimeMs: 100 }] },
    files: { [A]: { description: 'implementer:ab12cd34', model: 'opus' } },
    transcripts: { [jl(A)]: text(line('m1', usage(1, 2, 3, 4))) },
  })
  const rec = collectDispatchMeta({ io, roots: ['/r'], labels: ['implementer:ab12cd34'] })['implementer:ab12cd34']
  assert.deepEqual(Object.keys(rec), ['label', 'model', 'modelId', 'transcript', 'mtimeMs', 'effort', 'effortSource', 'usage', 'dispatches'])
  assert.equal(rec.dispatches, 1)
  assert.deepEqual(rec.usage, { input: 1, cacheWrite: 2, cacheRead: 3, output: 4, tokens: 7 })
})

test('S14: a lane label is matched when its seat is wanted and recorded under its own key; other seats, other shorts and non-lanes are not', () => {
  const metas = {
    '/r/s/subagents/agent-1.meta.json': 'reviewer.review-quality:ab12cd34',
    '/r/s/subagents/agent-2.meta.json': 'reviewer-a1.security-review:ab12cd34',
    '/r/s/subagents/agent-3.meta.json': 'planner.review-quality:ab12cd34',
    '/r/s/subagents/agent-4.meta.json': 'reviewer.review-quality:ab12cd35',
    '/r/s/subagents/agent-5.meta.json': 'reviewer.review-quality:ab12cd34 extra',
  }
  const paths = Object.keys(metas)
  const io = fakeIo({
    walk: { '/r': paths.map((path, i) => ({ path, mtimeMs: 100 + i })) },
    files: Object.fromEntries(paths.map((p) => [p, { description: metas[p], model: 'opus' }])),
    transcripts: Object.fromEntries(paths.map((p) => [jl(p), text(line('m', usage(1, 1, 1, 1)))])),
  })
  const meta = collectDispatchMeta({ io, roots: ['/r'], labels: ['reviewer:ab12cd34'] })
  assert.deepEqual(Object.keys(meta).sort(), ['reviewer-a1.security-review:ab12cd34', 'reviewer.review-quality:ab12cd34'])
  assert.equal(meta['reviewer.review-quality:ab12cd34'].label, 'reviewer.review-quality:ab12cd34')
  assert.equal(meta['reviewer.review-quality:ab12cd34'].dispatches, 1)
})

// -- S15: windowed lines without a timestamp -------------------------------------------------------

test('S15: under --since a usage line without a timestamp is not counted and one warning says how many; a transcript with no timestamps counts whole and is named once', () => {
  const sess = mkSession()
  // agent 1: T2 line 3 counts, the untimestamped 100 does not
  put(sess, 1, 'implementer:aaaaaaaa', text(line('t', usage(3, 0, 0, 0), { ts: T2 }), line('u', usage(100, 0, 0, 0))))
  const a = ok(['--session-dir', sess.s, '--since', String(MS(T2))])
  assert.equal(a.doc.agents[0].input, 3)
  assert.deepEqual(a.warnings, ['session-meters: 1 usage line(s) carry no timestamp and were not counted'])
  // agent 2: no timestamp anywhere -> whole, counted 7
  put(sess, 2, 'reviewer:aaaaaaaa', text(line('w', usage(7, 0, 0, 0))))
  const b = ok(['--session-dir', sess.s, '--since', String(MS(T2))])
  assert.deepEqual(b.doc.agents.map((r) => [r.label, r.input]), [['implementer:aaaaaaaa', 3], ['reviewer:aaaaaaaa', 7]])
  assert.deepEqual(b.warnings.sort(), [
    'session-meters: 1 transcript(s) carry no timestamps; counted whole',
    'session-meters: 1 usage line(s) carry no timestamp and were not counted',
  ])
  // no bound: both whole, neither warning
  const c = ok(['--session-dir', sess.s])
  assert.deepEqual(c.warnings, [])
  assert.deepEqual(c.doc.agents.map((r) => r.input), [103, 7])
})

test('windowTranscript reports dropped usage lines only when it windowed', () => {
  const stamped = text(line('a', usage(1, 0, 0, 0), { ts: T1 }), line('b', usage(2, 0, 0, 0), { ts: T2 }), line('c', usage(100, 0, 0, 0)))
  const w = windowTranscript(stamped, MS(T2), MS(T3))
  assert.equal(w.windowed, true)
  assert.equal(w.dropped, 1)
  assert.equal(transcriptMeters(w.text).input, 2)
  const plain = text(line('x', usage(5, 0, 0, 0)), line('y', usage(6, 0, 0, 0)))
  const p = windowTranscript(plain, MS(T2), MS(T3))
  // whether `windowed` is true for a transcript with no timestamps is not frozen (D12 fixes only dropped)
  assert.equal(p.dropped, 0)
  assert.equal(transcriptMeters(p.text).input, 11)
})

// -- S16: session folder ---------------------------------------------------------------------------

test('S16: sessionFolderOf takes the segments before the last subagents segment, else the directory; backslashes are slashes', () => {
  assert.equal(sessionFolderOf('/x/s/subagents/agent-1.meta.json'), '/x/s')
  assert.equal(sessionFolderOf('/x/s/subagents/workflows/wf_1/agent-2.meta.json'), '/x/s')
  assert.equal(sessionFolderOf('\\x\\s\\subagents\\workflows\\wf_1\\agent-2.meta.json'), '/x/s')
  assert.equal(sessionFolderOf('C:\\x\\s\\subagents\\agent-2.meta.json'), 'C:/x/s')
  assert.equal(sessionFolderOf('/s/agent-a.meta.json'), '/s')
  assert.equal(sessionFolderOf('/a/subagents/b/subagents/agent-1.meta.json'), '/a/subagents/b', 'the LAST subagents segment')
  assert.equal(sessionFolderOf('/s/subagentsX/agent-1.meta.json'), '/s/subagentsX', 'only a whole segment counts')
})

// -- probes ----------------------------------------------------------------------------------------

test('probe: an empty transcript keeps its zero row; since > until is an empty window, not an error', () => {
  const sess = mkSession()
  put(sess, 1, undefined, '')
  put(sess, 2, 'implementer:aaaaaaaa', text(line('a', usage(5, 0, 0, 0), { ts: T2 })))
  const { doc } = ok(['--session-dir', sess.s])
  assert.deepEqual(doc.agents.map((r) => [r.label, r.dispatches, r.tokens]), [['implementer:aaaaaaaa', 1, 5], [null, 1, 0]])
  const empty = ok(['--session-dir', sess.s, '--since', String(MS(T3)), '--until', String(MS(T1))])
  assert.equal(empty.doc.agents.find((r) => r.label === 'implementer:aaaaaaaa').input, 0)
})

test('probe: a trailing slash and spaces in --session-dir still find the folder and its main transcript', () => {
  const sess = mkSession('with space')
  put(sess, 1, 'implementer:aaaaaaaa', text(line('a', usage(1, 0, 0, 0))))
  writeFileSync(sess.main, text(line('mm', usage(9, 0, 0, 0))))
  // A trailing backslash is a path separator only on Windows; on POSIX it is part of the name.
  const forms = process.platform === 'win32' ? [sess.s, `${sess.s}/`, `${sess.s}\\`] : [sess.s, `${sess.s}/`]
  for (const dir of forms) {
    const { doc } = ok(['--session-dir', dir])
    assert.equal(doc.agents.length, 1, dir)
    assert.equal(doc.main.input, 9, `main found for ${dir}`)
  }
})
