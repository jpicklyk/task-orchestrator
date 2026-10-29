// Regression coverage for the six ralph driver bugs of fd1beaee (usage handshake, pause-wait, closed-run probe,
// id matching, claim heartbeat, failed-item skip). Test author actor id: test-author:fd1beaee:r-202610290051-ef84a1eb.
//
// Written blind: only the frozen test-plan / diagnosis notes, the verbatim declarations, the existing tests and
// fixtures, and the public docs were read; no ralph-*.mjs source. Oracles (never the driver):
//   WG  = current/docs/workflow-guide.md  "Heartbeat Pattern" (a heartbeat never takes an item), "Effect of complete
//         and cancel on the Claim" (the terminal transition clears the claim)
//   AR  = current/docs/api-reference.md   advance heartbeat ("usage needs a seat token"), runs_write "Tokens"
//         (a closed run's tokens are unauthenticated, a director close still works), runs_read get, `run_paused`
//         and `invalid_transition` error details, items_read next (8-hex short ids)
//   FIX = the frozen fix F1-F6 of the diagnosis note (the behaviours the item must deliver)
//
// Scenario ids are the item's test-plan ids S1-S15. The drivers run as subprocesses (`--claude-bin` seam) against
// fixtures/ralph/fake-api.mjs and fake-claude.mjs, both corrected here to enforce WG/AR (a claim keeps its holder
// and expiry, a heartbeat needs the held claim, complete clears the claim, a paused run refuses assign/update, a
// closed run kills its tokens). RALPH_PAUSE_POLL_MS is the driver's declared poll-interval seam.

import '../../hooks/tests/hermetic-claude-config.mjs'
import { test, after, describe } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { startFakeApi } from './fixtures/ralph/fake-api.mjs'
import { addSkip, buildHeartbeatUsageOp, decideSelection, hasItemId, isSkipped, loopEffect, OUTCOME_TABLE, sameItemId, worktreeNameForOutcome } from '../ralph-lib.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const LOOP = path.resolve(HERE, '..', 'ralph-loop.mjs')
const RENDER = path.resolve(HERE, '..', 'render-ralph-prompts.mjs')
const FAKE_CLAUDE = path.join(HERE, 'fixtures', 'ralph', 'fake-claude.mjs')
const ROOT = '11111111-2222-4333-8444-555555555555'
const BEARER = 'bearer-0a1b2c3d4e5f60718293a4b5c6d7e8f9'
const A = 'aaaa1111'
const B = 'bbbb2222'
const SHORT = '943f729d'
const FULL = '943f729d-0490-4baa-97d7-5b06ab9890f4'
const DECISION = 'dddd4444-0000-4000-8000-000000000001'
const FAST_POLL = { RALPH_PAUSE_POLL_MS: '100' }

// ---- harness (the same shape as ralph-loop.test.mjs) -------------------------------------------------

const tmpDirs = []
function tmp(prefix) {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  tmpDirs.push(d)
  return d
}
after(() => {
  for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true })
})

function baseEnv(extra) {
  const env = { ...process.env }
  for (const k of Object.keys(env)) {
    if (k.startsWith('TASK_ORCHESTRATOR_') || k === 'AGENT_CONFIG_DIR' || k.startsWith('FAKE_CLAUDE_') || k === 'RALPH_PAUSE_POLL_MS') delete env[k]
  }
  return { ...env, ...extra }
}

function runDriver({ argv, env, cwd, timeoutMs }) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [LOOP, ...argv], { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    let timedOut = false
    const t0 = Date.now()
    child.stdout.on('data', (d) => (stdout += d))
    child.stderr.on('data', (d) => (stderr += d))
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGKILL')
    }, timeoutMs)
    child.on('close', (code, signal) => {
      clearTimeout(timer)
      resolve({ code, signal, stdout, stderr, out: stdout + '\n' + stderr, timedOut, elapsedMs: Date.now() - t0 })
    })
  })
}

function readJsonl(file) {
  if (!fs.existsSync(file)) return []
  return fs
    .readFileSync(file, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l))
}

// `items` are queue ids (oldest first, 8-hex like the compact view); `plan` is the fake claude's per-invocation behaviour.
async function scenario(o = {}) {
  const { items = [], apiOpts = {}, plan = [], args = [], env = {}, timeoutMs = 30000 } = o
  const cwd = tmp('ralph-fix-cwd-')
  const home = tmp('ralph-fix-home-')
  const aux = tmp('ralph-fix-aux-')
  const scratch = path.join(tmp('ralph-fix-scratch-'), 'run')
  try {
    execFileSync('git', ['init', '-q'], { cwd, stdio: 'ignore' })
  } catch {
    // a repository is only for realism: --no-cleanup disables the worktree cleanup
  }
  const api = await startFakeApi({ items: items.map((id) => ({ id })), ...apiOpts })
  const logFile = path.join(aux, 'claude.jsonl')
  const argv = ['--claude-bin', FAKE_CLAUDE, '--root', ROOT, '--scratch-dir', scratch, '--no-cleanup', '--ttl', '120', '--budget', '1', '--max', '1', ...args]
  const envv = baseEnv({
    TASK_ORCHESTRATOR_HOME: home,
    TASK_ORCHESTRATOR_CEILING: cwd,
    FAKE_CLAUDE_LOG: logFile,
    FAKE_CLAUDE_PLAN: JSON.stringify(plan),
    FAKE_CLAUDE_ITEM_IDS: items.join(','),
    FAKE_CLAUDE_API_URL: api.base,
    TASK_ORCHESTRATOR_API_URL: api.base,
    TASK_ORCHESTRATOR_API_TOKEN: BEARER,
    ...env,
  })
  const res = await runDriver({ argv, env: envv, cwd, timeoutMs })
  await api.stop()
  const log = readJsonl(logFile)
  return { api, res, cwd, scratch, logFile, log, starts: log.filter((r) => r.type === 'start') }
}

const tail = (s) => `\n--- driver output (tail) ---\n${s.res.out.slice(-1800)}`
const opsNamed = (s, name) => s.api.trace.filter((t) => t.op === name)
const writes = (s) => s.api.trace.filter((t) => t.tool === 'runs_write' || t.tool === 'advance')
const polls = (s) => s.api.trace.filter((t) => t.tool === 'runs_read' && t.op === 'get')
const idOf = (x) => String(x).toLowerCase().replace(/-/g, '')
const sameHex = (a, b) => idOf(a).startsWith(idOf(b)) || idOf(b).startsWith(idOf(a))
const assignedIds = (s) => opsNamed(s, 'assign').map((a) => a.itemId.toLowerCase())
const countOf = (text, needle) => text.split(needle).length - 1
const COST = { total_cost_usd: 1.25, usage: { input_tokens: 100, output_tokens: 50, cache_creation_input_tokens: 10, cache_read_input_tokens: 5 } }

// ---- S1: usage lands through the ready-to-complete handshake (FIX F1; WG:1112, AR heartbeat) ------------------

describe('S1 usage handshake', () => {
  test('NEW: ready-to-complete with cost 1.25 -> heartbeat{usage} then transition complete under the seat token, before end_seat; the run counts the cost and the seat ends done', async () => {
    const s = await scenario({ items: [A], plan: [{ kind: 'ready', ...COST }] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    const seat = s.api.seats[0]
    const hb = opsNamed(s, 'heartbeat').filter((h) => h.usage !== undefined)
    assert.equal(hb.length, 1, 'one usage heartbeat' + tail(s))
    assert.equal(hb[0].seatToken, seat.token, 'usage is only recorded under a seat token (AR)')
    assert.equal(hb[0].usage.costUsd, 1.25)
    const tr = opsNamed(s, 'transition')
    assert.equal(tr.length, 1)
    assert.equal(tr[0].trigger, 'complete')
    assert.equal(tr[0].seatToken, seat.token)
    const end = opsNamed(s, 'end_seat')[0]
    assert.ok(hb[0].seq < tr[0].seq, 'the usage is reported while the claim is still held, i.e. before the terminal move')
    assert.ok(tr[0].seq < end.seq, 'complete precedes end_seat')
    assert.equal(end.outcome, 'done')
    assert.equal(s.api.item(A).role, 'terminal')
    assert.equal(s.api.runView().usage.reportedCostUsd, 1.25, 'the fixture only records usage under a held claim: a post-terminal report would leave 0')
  })

  test('NEW: usage of two items adds up in the run (the cost budget counts successes)', async () => {
    const s = await scenario({ items: [A, B], plan: [{ kind: 'ready', ...COST }, { kind: 'ready', ...COST, total_cost_usd: 0.5 }], args: ['--max', '2'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.deepEqual(s.api.seats.map((x) => [x.itemId, x.outcome]), [[A, 'done'], [B, 'done']])
    assert.equal(s.api.runView().usage.reportedCostUsd, 1.75)
  })

  test('PROBE (ready on an already-terminal item): the iteration already completed the item; the driver complete is invalid_transition{fromRole: terminal} and still ends the seat done, with no re-assign', async () => {
    const s = await scenario({ items: [A, B], plan: [{ kind: 'ready', completeFirst: true }], args: ['--max', '2'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.ok(s.log.some((r) => r.type === 'complete-sent' && r.ok === true), 'fixture condition: the iteration really completed the item itself')
    assert.equal(s.api.seats[0].outcome, 'done', 'a terminal item is a terminal outcome, not an error' + tail(s))
    assert.equal(assignedIds(s)[0], A)
    assert.equal(assignedIds(s).filter((id) => id === A).length, 1, 'A is terminal: never assigned twice')
  })
})

// ---- S10: the legacy terminal status sends no usage ops (FIX F1) --------------------------------------------

test('S10 EXISTING: a legacy terminal iteration (it completed the item itself) with usage in the envelope -> no claim, heartbeat or release after the transition, usage-unreported is printed, the seat ends done', async () => {
  const s = await scenario({ items: [A], plan: [{ kind: 'terminal-api', ...COST }] })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.ok(s.log.some((r) => r.type === 'complete-sent' && r.ok === true), 'fixture condition: the child completed the item through the API')
  assert.equal(opsNamed(s, 'claim').length, 1, 'only the seat claim before the spawn; a post-terminal claim would be invalid_transition (WG:1112)')
  assert.equal(opsNamed(s, 'heartbeat').length, 0)
  assert.equal(opsNamed(s, 'release').length, 0)
  assert.ok(s.res.out.includes('usage-unreported'), tail(s))
  assert.equal(s.api.seats[0].outcome, 'done')
  assert.equal(s.api.runView().usage.reportedCostUsd, 0)
})

// ---- S2: full-UUID / hex-id member matching (FIX F4; AR items_read next answers 8-hex short ids) -------------

describe('S2 --items id forms', () => {
  const forms = [
    ['full UUID', FULL],
    ['upper-case full UUID', FULL.toUpperCase()],
    ['hyphenless 32-hex', FULL.replace(/-/g, '')],
    ['8-hex short id', SHORT],
  ]
  for (const [label, form] of forms) {
    test(`EXISTING: --items ${label} matches the queue's short id: the item is worked and the loop ends queue drained, with no idle and no addItems for it`, async () => {
      const s = await scenario({ items: [SHORT], plan: ['terminal'], apiOpts: { completeOnDone: true }, args: ['--items', form, '--max', '3'] })
      assert.equal(s.res.timedOut, false, tail(s))
      assert.equal(s.res.code, 0, tail(s))
      const open = opsNamed(s, 'open')[0]
      assert.equal(open.items.length, 1)
      assert.ok(sameHex(open.items[0], form), `the member is the item that was asked for (${open.items[0]})`)
      const assigns = assignedIds(s)
      assert.equal(assigns.length, 1, 'the member is assigned once' + tail(s))
      assert.ok(sameHex(assigns[0], SHORT), 'and it is that item')
      assert.equal(s.api.seats[0].outcome, 'done')
      const added = s.api.trace.filter((t) => t.op === 'update' && (t.addItems ?? []).length > 0)
      assert.deepEqual(added, [], 'the member is already in the run: no addItems (an unmatched id was added as if it were new)')
      assert.match(s.res.out, /queue drained/i, tail(s))
      assert.doesNotMatch(s.res.out, /idle \(|idle budget exhausted/, 'a member that was matched never idles' + tail(s))
    })
  }

  test('EXISTING: two different 8-hex members stay different (a prefix match must not merge them)', async () => {
    const s = await scenario({ items: [A, B], plan: ['terminal', 'terminal'], apiOpts: { completeOnDone: true }, args: ['--items', `${A},${B}`, '--max', '3'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.deepEqual(assignedIds(s), [A, B])
  })
})

describe('S3 / F4 id helpers', () => {
  test('S3 EXISTING: worktreeNameForOutcome accepts an 8-hex id and a hyphenless 32-hex id, and names the worktree from the first 8 hex', () => {
    assert.equal(worktreeNameForOutcome(SHORT, 3, 'tmp'), 'ralph-943f729d-3')
    assert.equal(worktreeNameForOutcome(FULL, 3, 'tmp'), 'ralph-943f729d-3')
    assert.equal(worktreeNameForOutcome(FULL.replace(/-/g, ''), 3, 'tmp'), 'ralph-943f729d-3')
  })

  test('S3 PROBE (case): an upper-case id names the same worktree', () => {
    assert.equal(worktreeNameForOutcome(FULL.toUpperCase(), 3, 'tmp').toLowerCase(), 'ralph-943f729d-3')
    assert.equal(worktreeNameForOutcome(SHORT.toUpperCase(), 3, 'tmp').toLowerCase(), 'ralph-943f729d-3')
  })

  test('S3 EXISTING: strings that are neither a UUID nor 8-32 hex keep the temp name', () => {
    for (const bad of ['zzzzzzzz', 'g43f729d', '943f729', FULL.replace(/-/g, '') + 'a', '943f729d;id', '', 'ralph']) {
      assert.equal(worktreeNameForOutcome(bad, 1, 'tmp'), 'tmp', JSON.stringify(bad))
    }
  })

  test('F4 NEW: sameItemId treats a full UUID, its hyphenless form, its upper-case form and its 8-hex prefix as one item, and two different ids as two', () => {
    for (const other of [FULL, FULL.toUpperCase(), FULL.replace(/-/g, ''), SHORT, SHORT.toUpperCase()]) {
      assert.equal(sameItemId(FULL, other), true, other)
      assert.equal(sameItemId(other, FULL), true, `${other} (reversed)`)
    }
    assert.equal(sameItemId(A, B), false)
    assert.equal(sameItemId(FULL, 'aaaa1111-0490-4baa-97d7-5b06ab9890f4'), false)
    assert.equal(sameItemId(SHORT, 'aaaa1111'), false)
  })

  test('F4 NEW: hasItemId finds an item by any id form and is false for a list without it', () => {
    assert.equal(hasItemId([FULL], SHORT), true)
    assert.equal(hasItemId([SHORT], FULL), true)
    assert.equal(hasItemId([A, SHORT], FULL.toUpperCase()), true)
    assert.equal(hasItemId([A, B], SHORT), false)
    assert.equal(hasItemId([], SHORT), false)
  })

  test('F4 NEW: decideSelection with a members set holding a full UUID picks the short-id candidate', () => {
    class Members extends Set {
      includes(x) {
        return this.has(x)
      }
    }
    const cand = (id) => ({ id, type: 'feature-task', title: id, role: 'queue', depth: 2, version: 1 })
    const r = decideSelection({ candidates: [cand(A), cand(SHORT)], skipList: [], members: new Members([FULL]), now: 1_700_000_000_000 })
    const picked = typeof r.pick === 'string' ? r.pick : r.pick?.id
    assert.equal(picked, SHORT, 'the non-member A is passed over, the member is found through its short id')
  })
})

// ---- S4: the claim is kept alive during a long iteration (FIX F5; WG:1092-1108) ------------------------------

test('S4 NEW: a long iteration is heartbeated (ttlSeconds 60, no usage) between the claim and the complete, so the held claim never lapses and complete succeeds', async () => {
  const s = await scenario({
    items: [A],
    plan: [{ kind: 'ready', sleepMs: 4000 }],
    apiOpts: { ttlUnitMs: 50 }, // --ttl 60 means a 3 s lease here; the child needs 4 s
    args: ['--ttl', '60', '--heartbeat-interval', '1'],
    timeoutMs: 40000,
  })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.equal(s.res.code, 0, tail(s))
  const seat = s.api.seats[0]
  const claim = opsNamed(s, 'claim').find((c) => c.opsInCall === 1)
  const tr = opsNamed(s, 'transition')[0]
  assert.ok(claim && tr, 'a claim and a complete were sent')
  const hbs = opsNamed(s, 'heartbeat').filter((h) => h.seq > claim.seq && h.seq < tr.seq)
  assert.ok(hbs.length >= 2, `at least two keep-alive heartbeats between claim and complete (got ${hbs.length})` + tail(s))
  for (const h of hbs) {
    assert.equal(h.seatToken, seat.token)
    assert.equal(h.itemId.toLowerCase(), A)
    assert.equal(h.ttlSeconds, 60)
    assert.equal(h.usage, undefined, 'a keep-alive carries no usage')
  }
  for (let i = 1; i < hbs.length; i++) {
    const gap = hbs[i].ts - hbs[i - 1].ts
    assert.ok(gap >= 700 && gap <= 3000, `the timer fires about every --heartbeat-interval second (gap ${gap} ms)`)
  }
  assert.equal(tr.trigger, 'complete')
  assert.equal(s.api.seats[0].outcome, 'done', 'the lease would have lapsed after 3 s without the heartbeats: complete is the holder-only move' + tail(s))
  assert.equal(s.api.item(A).role, 'terminal')
  assert.equal(hbs.every((h) => h.seq < opsNamed(s, 'end_seat')[0].seq), true)
})

describe('S14 --heartbeat-interval validation', () => {
  for (const bad of ['0', '120', '121', 'abc']) {
    test(`EXISTING: --heartbeat-interval ${bad} with --ttl 120 is a usage error (exit 64)`, async () => {
      const s = await scenario({ items: [A], args: ['--heartbeat-interval', bad, '--dry-run'], timeoutMs: 20000 })
      assert.equal(s.res.timedOut, false, tail(s))
      assert.equal(s.res.code, 64, tail(s))
      assert.ok(s.res.out.includes('heartbeat-interval'), 'the message names the flag' + tail(s))
      assert.equal(s.api.hits.length, 0)
    })
  }
  for (const ok of ['1', '119']) {
    test(`EXISTING: --heartbeat-interval ${ok} with --ttl 120 is accepted (the 1..ttl-1 boundary)`, async () => {
      const s = await scenario({ items: [A], args: ['--heartbeat-interval', ok, '--dry-run'], timeoutMs: 20000 })
      assert.equal(s.res.timedOut, false, tail(s))
      assert.equal(s.res.code, 0, tail(s))
    })
  }
})

// ---- S5 / S12 / S13: a pause waits, it never closes the run (FIX F2; AR runs_write "Tokens" / answer) ---------

describe('S5 pause-wait', () => {
  test('EXISTING: run_paused{budget, decisionId} on assign -> no close while paused, runs_read get is polled token-less, the pause is printed once, the item is worked after the resume and the run closes completed', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { pauseOn: { op: 'assign', nth: 1, pauseReason: 'budget', decisionId: DECISION }, resumeAfterPolls: 2, completeOnDone: true },
      args: ['--max', '3'],
      env: FAST_POLL,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    const assigns = opsNamed(s, 'assign')
    assert.equal(assigns.length, 2, 'refused once while paused, then assigned after the resume')
    const ps = polls(s)
    assert.equal(ps.length, 2, 'polled until the second answer showed the run open' + tail(s))
    for (const p of ps) {
      assert.equal(p.seatToken, undefined, 'the poll is token-less')
      assert.ok(p.seq > assigns[0].seq && p.seq < assigns[1].seq)
    }
    assert.equal(opsNamed(s, 'close').length, 1)
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
    assert.equal(writes(s).at(-1).op, 'close')
    assert.equal(s.api.seats.length, 1)
    assert.equal(s.api.seats[0].outcome, 'done')
    assert.equal(countOf(s.res.out, DECISION), 1, 'the pause decision is named exactly once' + tail(s))
    assert.match(s.res.out, /budget/)
  })

  test('EXISTING: the wait is not an idle: --idle-budget 1 and several polls still end with the item worked, not idle budget exhausted', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { pauseOn: { op: 'assign', nth: 1 }, resumeAfterPolls: 4, completeOnDone: true },
      args: ['--max', '3', '--idle-budget', '1'],
      env: FAST_POLL,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.equal(polls(s).length, 4)
    assert.equal(s.api.seats.at(-1).outcome, 'done')
    assert.doesNotMatch(s.res.out, /idle budget exhausted/)
  })

  test('PROBE (two consecutive pauses): each pause is waited out and named once; the third assign works', async () => {
    const d1 = 'dddd4444-0000-4000-8000-0000000000b1'
    const d2 = 'dddd4444-0000-4000-8000-0000000000b2'
    const s = await scenario({
      items: [A],
      apiOpts: { pauseOn: [{ op: 'assign', nth: 1, decisionId: d1 }, { op: 'assign', nth: 2, decisionId: d2, pauseReason: 'attempts' }], resumeAfterPolls: 1, completeOnDone: true },
      args: ['--max', '3'],
      env: FAST_POLL,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.equal(opsNamed(s, 'assign').length, 3)
    assert.equal(polls(s).length, 2)
    assert.equal(countOf(s.res.out, d1), 1, tail(s))
    assert.equal(countOf(s.res.out, d2), 1, tail(s))
    assert.equal(opsNamed(s, 'close').length, 1)
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
  })

  test('PROBE (transient read error while paused): an unavailable answer to the poll is retried and the wait continues to the resume', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { pauseOn: { op: 'assign', nth: 1 }, resumeAfterPolls: 1, runsGet: ({ nth }) => (nth === 1 ? { http: 503, code: 'unavailable' } : undefined), completeOnDone: true },
      args: ['--max', '3'],
      env: FAST_POLL,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.ok(polls(s).length >= 2, 'the failed poll was repeated')
    assert.equal(s.api.seats.at(-1).outcome, 'done')
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
  })

  test('PROBE (permanent read error while paused): a forbidden answer to the poll ends the driver with exit 2 and never closes the paused run', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { pauseOn: { op: 'assign', nth: 1 }, runsGet: () => ({ http: 403, code: 'forbidden' }) },
      args: ['--max', '3'],
      env: FAST_POLL,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 2, tail(s))
    assert.equal(opsNamed(s, 'assign').length, 1)
    assert.equal(s.starts.length, 0)
  })
})

describe('S6 pause then close elsewhere', () => {
  test('EXISTING: a paused run that a director closes cancelled -> exit 0, no close op from the driver, the output names cancelled, nothing more is assigned (AR:2233-2235)', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { pauseOn: { op: 'assign', nth: 1 }, closeAfterPolls: 2 },
      args: ['--max', '3'],
      env: FAST_POLL,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.equal(s.api.run.status, 'closed')
    assert.equal(s.api.run.terminalReason, 'cancelled')
    assert.equal(opsNamed(s, 'close').length, 0, 'the run is already closed: the driver sends no close' + tail(s))
    assert.match(s.res.out, /cancelled/)
    assert.equal(opsNamed(s, 'assign').length, 1)
    assert.equal(s.starts.length, 0)
  })
})

describe('S12 run_paused reported by the iteration', () => {
  test('EXISTING: an iteration run_paused code fails the seat, then waits: no close until the run resumes, then the item is worked', async () => {
    const s = await scenario({
      items: [A],
      plan: [{ kind: 'error', code: 'run_paused' }, 'terminal'],
      apiOpts: { pauseOn: { op: 'claim', nth: 1 }, resumeAfterPolls: 2, completeOnDone: true },
      args: ['--max', '3'],
      env: FAST_POLL,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.deepEqual(s.api.seats.map((x) => x.outcome), ['failed', 'done'])
    const closes = opsNamed(s, 'close')
    assert.equal(closes.length, 1)
    assert.equal(closes[0].reason, 'completed')
    const ps = polls(s)
    assert.ok(ps.length >= 2)
    assert.ok(closes[0].seq > ps.at(-1).seq, 'no close until the run resumed')
  })

  test('EXISTING: when the director closes the paused run instead, the driver exits 0 without closing it', async () => {
    const s = await scenario({
      items: [A],
      plan: [{ kind: 'error', code: 'run_paused' }, 'terminal'],
      apiOpts: { pauseOn: { op: 'claim', nth: 1 }, closeAfterPolls: 1 },
      args: ['--max', '3'],
      env: FAST_POLL,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.equal(s.api.seats[0].outcome, 'failed')
    assert.equal(opsNamed(s, 'close').length, 0)
    assert.equal(s.starts.length, 1, 'nothing is started after the close')
  })
})

test('S13 NEW: a heartbeat answered paused:{decisionId} during ready-to-complete -> complete is still sent, then the driver waits for the resume before the next assign', async () => {
  const s = await scenario({
    items: [A, B],
    plan: [{ kind: 'ready', ...COST }, { kind: 'ready' }],
    apiOpts: { pauseOn: { op: 'heartbeat', nth: 1, decisionId: DECISION }, resumeAfterPolls: 2 },
    args: ['--max', '2'],
    env: FAST_POLL,
  })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.equal(s.res.code, 0, tail(s))
  const tr = opsNamed(s, 'transition')
  assert.ok(tr.length >= 1 && tr[0].itemId.toLowerCase() === A && tr[0].trigger === 'complete', 'complete is still sent after the call that paused the run' + tail(s))
  assert.ok(tr[0].seq > opsNamed(s, 'heartbeat')[0].seq)
  assert.equal(s.api.item(A).role, 'terminal')
  const assigns = opsNamed(s, 'assign')
  assert.equal(assigns.length, 2)
  const ps = polls(s)
  assert.equal(ps.length, 2, tail(s))
  assert.ok(ps.every((p) => p.seq > opsNamed(s, 'end_seat')[0].seq && p.seq < assigns[1].seq), 'the wait sits between the first seat end and the second assign')
  assert.deepEqual(s.api.seats.map((x) => [x.itemId, x.outcome]), [[A, 'done'], [B, 'done']])
  assert.equal(opsNamed(s, 'close').length, 1)
  assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
  assert.equal(countOf(s.res.out, DECISION), 1, tail(s))
})

// ---- S7: a run closed elsewhere is a clean exit, not an auth error (FIX F3; AR runs_write "Tokens") ---------

describe('S7 closed-run probe', () => {
  const assertClosedElsewhere = (s) => {
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, 'a director close is not an authentication failure' + tail(s))
    assert.equal(s.api.run.status, 'closed')
    assert.equal(opsNamed(s, 'close').length, 0, 'the driver does not try to close a run that is already closed' + tail(s))
    assert.ok(polls(s).length >= 1, 'a token-less runs_read get probed the run')
    assert.ok(polls(s).every((p) => p.seatToken === undefined))
    assert.match(s.res.out, /run closed elsewhere \(cancelled\)/, tail(s))
  }

  test('EXISTING: assign answered 401 because a director closed the run -> probe says closed, exit 0, no close op', async () => {
    const s = await scenario({ items: [A], apiOpts: { externalClose: { op: 'assign', nth: 1, reason: 'cancelled' } }, args: ['--max', '3'] })
    assertClosedElsewhere(s)
    assert.equal(s.starts.length, 0)
  })

  test('EXISTING: the seat claim answered 401 because the run was closed -> probe says closed, exit 0, no close op, nothing spawned', async () => {
    const s = await scenario({ items: [A], apiOpts: { externalClose: { op: 'claim', nth: 1, reason: 'cancelled' } }, args: ['--max', '3'] })
    assertClosedElsewhere(s)
    assert.equal(s.starts.length, 0)
  })

  test('NEW: a keep-alive heartbeat answered 401 mid-iteration -> the timer stops, and after the iteration the probe says closed: exit 0, no close op', async () => {
    const s = await scenario({
      items: [A],
      plan: [{ kind: 'terminal', sleepMs: 3000 }],
      apiOpts: { externalClose: { op: 'heartbeat', nth: 1, reason: 'cancelled' } },
      args: ['--heartbeat-interval', '1', '--max', '3'],
      timeoutMs: 40000,
    })
    assertClosedElsewhere(s)
    assert.equal(s.api.hits.filter((h) => h.raw.includes('"heartbeat"')).length, 1, 'the timer stops after the unauthenticated heartbeat' + tail(s))
  })

  test('NEW: a run_closed element error on assign marks the run closed: the driver sends no close at the end', async () => {
    const s = await scenario({ items: [A], apiOpts: { assign: () => ({ code: 'run_closed', detail: { runId: ROOT, terminalReason: 'cancelled' } }) }, args: ['--max', '3'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.equal(opsNamed(s, 'assign').length, 1)
    assert.equal(opsNamed(s, 'close').length, 0, 'closing a closed run is run_closed again: skip it' + tail(s))
  })

  test('EXISTING: a refused coordinator token on an OPEN run is still an authentication failure: the probe says open, exit 2', async () => {
    const s = await scenario({ items: [A], apiOpts: { assign: () => ({ http401: true }) }, args: ['--max', '3'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 2, tail(s))
    assert.ok(polls(s).length >= 1, 'the run was probed before the verdict' + tail(s))
    assert.equal(s.starts.length, 0)
  })
})

// ---- S8 / S9: a failed item is skip-listed (FIX F6) ---------------------------------------------------------

describe('S8 failed items are not re-picked', () => {
  test('EXISTING: an error outcome on A skip-lists A: the second iteration takes B (not A again)', async () => {
    const s = await scenario({ items: [A, B], plan: [{ kind: 'error', code: 'some_failure' }, 'terminal'], args: ['--max', '2'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.deepEqual(assignedIds(s), [A, B])
    assert.deepEqual(s.api.seats.map((x) => x.outcome), ['failed', 'done'])
  })

  test('EXISTING: a gate-blocked outcome on A skip-lists A: the second iteration takes B', async () => {
    const s = await scenario({ items: [A, B], plan: [{ kind: 'gate' }, 'terminal'], args: ['--max', '2'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.deepEqual(assignedIds(s), [A, B])
    assert.deepEqual(s.api.seats.map((x) => x.outcome), ['abandoned', 'done'])
  })

  test('EXISTING: a failed lone item is skip-listed for good, so the loop ends drained instead of re-picking it until a breaker trips', async () => {
    const s = await scenario({ items: [A], plan: [{ kind: 'error', code: 'some_failure' }, 'terminal', 'terminal'], args: ['--max', '5', '--error-budget', '5'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(assignedIds(s).filter((id) => id === A).length, 1, 'A is assigned exactly once' + tail(s))
  })

  test('F6 NEW: addSkip for gate-blocked and error never expires, whatever retryAfterMs says', () => {
    for (const code of ['gate-blocked', 'error']) {
      const list = addSkip([], { itemId: A, code, retryAfterMs: 1000, now: 1_700_000_000_000 })
      assert.equal(list[0].until, Infinity, code)
      assert.equal(isSkipped(list, A, 1_700_000_000_000 + 1e12), true, code)
    }
  })
})

describe('S9 complete refused by a gate', () => {
  for (const code of ['gate_blocked', 'seat_forbidden', 'note_owned_by_other']) {
    test(`NEW: ready-to-complete whose complete answers ${code} is a gate-blocked outcome: seat abandoned, A not re-assigned, not retried`, async () => {
      const s = await scenario({
        items: [A, B],
        plan: ['ready', 'terminal'],
        apiOpts: { transition: ({ itemId }) => (itemId === A ? { code, detail: { itemId: A, missing: [{ key: 'review-checklist', role: 'review' }] } } : undefined) },
        args: ['--max', '2'],
      })
      assert.equal(s.res.timedOut, false, tail(s))
      assert.deepEqual(assignedIds(s), [A, B], 'A is skip-listed after the gate' + tail(s))
      assert.equal(s.api.seats[0].outcome, 'abandoned')
      assert.equal(opsNamed(s, 'transition').filter((t) => t.itemId.toLowerCase() === A).length, 1, 'a permanent refusal is not retried')
      assert.ok(s.res.out.includes(code), 'the code is reported' + tail(s))
      assert.equal(s.api.item(A).role, 'queue')
    })
  }

  test('NEW: any other permanent code on complete takes that code table status (error): seat failed, A skip-listed', async () => {
    const s = await scenario({
      items: [A, B],
      plan: ['ready', 'terminal'],
      apiOpts: { transition: ({ itemId }) => (itemId === A ? { code: 'not_claim_holder', detail: { itemId: A } } : undefined) },
      args: ['--max', '2'],
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.deepEqual(assignedIds(s), [A, B])
    assert.equal(s.api.seats[0].outcome, 'failed')
  })
})

// ---- S11: transient errors on complete are retried, bounded (FIX F1) ---------------------------------------

describe('S11 complete retry', () => {
  test('NEW: complete answered unavailable{retryAfterMs: 200} once is retried after the wait and lands the item in terminal', async () => {
    const s = await scenario({ items: [A], plan: ['ready'], apiOpts: { transition: ({ nth }) => (nth === 1 ? { code: 'unavailable', retryAfterMs: 200 } : undefined) } })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    const tr = opsNamed(s, 'transition')
    assert.equal(tr.length, 2, 'one retry' + tail(s))
    assert.ok(tr[1].ts - tr[0].ts >= 180, `waited about retryAfterMs (got ${tr[1].ts - tr[0].ts})`)
    assert.equal(s.api.item(A).role, 'terminal')
    assert.equal(s.api.seats[0].outcome, 'done')
  })

  test('NEW: a transient code without retryAfterMs waits about 1000 ms before the retry', async () => {
    const s = await scenario({ items: [A], plan: ['ready'], apiOpts: { transition: ({ nth }) => (nth === 1 ? { code: 'internal' } : undefined) } })
    assert.equal(s.res.timedOut, false, tail(s))
    const tr = opsNamed(s, 'transition')
    assert.equal(tr.length, 2)
    const gap = tr[1].ts - tr[0].ts
    assert.ok(gap >= 900 && gap < 8000, `waited about 1000 ms (got ${gap})`)
    assert.equal(s.api.item(A).role, 'terminal')
  })

  test('NEW: a persistent unavailable is tried at most 3 times', async () => {
    const s = await scenario({ items: [A], plan: ['ready'], apiOpts: { transition: () => ({ code: 'unavailable', retryAfterMs: 100 }) } })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(opsNamed(s, 'transition').length, 3, 'exactly 3 attempts' + tail(s))
    assert.equal(s.api.item(A).role, 'queue')
    assert.equal(opsNamed(s, 'end_seat').length, 1, 'the seat is still ended')
  })
})

// ---- S15: the outcome table and its generated prompts ---------------------------------------------------------

describe('S15 outcome table', () => {
  const row = (code) => OUTCOME_TABLE.filter((r) => r.code === code)

  test('NEW: the ready-to-complete row is an iteration outcome that continues the loop', () => {
    assert.equal(row('ready-to-complete').length, 1)
    const r = row('ready-to-complete')[0]
    assert.equal(r.kind, 'outcome')
    assert.equal(r.by, 'iteration')
    assert.equal(r.status, 'ready-to-complete')
    assert.equal(r.loop, 'continue')
  })

  test('EXISTING: run_paused is a driver pause-wait row (status error) and run_closed stays an exit row', () => {
    assert.equal(row('run_paused').length, 1)
    assert.equal(row('run_paused')[0].by, 'driver')
    assert.equal(row('run_paused')[0].status, 'error')
    assert.equal(row('run_paused')[0].loop, 'pause-wait')
    assert.equal(row('run_closed')[0].loop, 'exit')
  })

  test('EXISTING: loopEffect for run_paused is a pause that asks for no close; run_closed still exits', () => {
    const e = loopEffect({ code: 'run_paused', status: 'error', pauseReason: 'budget' })
    assert.equal(e.effect, 'pause')
    assert.equal('closeReason' in e, false)
    assert.equal(loopEffect({ code: 'run_closed', status: 'error' }).effect, 'exit')
  })

  test('NEW: buildHeartbeatUsageOp builds heartbeat{itemId, ttlSeconds, usage} from the final envelope', () => {
    const op = buildHeartbeatUsageOp({ itemId: A, ttlSeconds: 120, envelope: COST })
    assert.equal(op.op, 'heartbeat')
    assert.equal(op.itemId, A)
    assert.equal(op.ttlSeconds, 120)
    assert.equal(op.usage.costUsd, 1.25)
    assert.ok(Number.isInteger(op.usage.tokens) && op.usage.tokens >= 150 && op.usage.tokens <= 165, `tokens ${op.usage.tokens}`)
  })

  test('EXISTING: render-ralph-prompts --check finds the generated prompt tables up to date (exit 0)', () => {
    const out = execFileSync(process.execPath, [RENDER, '--check'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    assert.equal(typeof out, 'string')
  })
})

// ---- Coordinator report (item 666c5e8f): the driver prints TO report blocks for a relaying agent ----------

test('REPORT NEW: a pause prints a TO report paused block naming the decision, the end prints a closed block after the summary, and no token value is on stdout', async () => {
  const s = await scenario({
    items: [A, B],
    plan: [{ kind: 'ready', ...COST }, { kind: 'ready' }],
    apiOpts: { pauseOn: { op: 'heartbeat', nth: 1, decisionId: DECISION }, resumeAfterPolls: 2 },
    args: ['--max', '2'],
    env: FAST_POLL,
  })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.equal(s.res.code, 0, tail(s))
  const out = s.res.out
  const blocks = out.split('TO report: ').slice(1)
  assert.equal(blocks.length, 2, 'one paused block and one closed block' + tail(s))
  assert.match(blocks[0], /^run ralph-\d+-\d+ \([0-9a-f]{8}\) paused: run paused/)
  assert.match(blocks[0], /\npause: \S+ decision [0-9a-f]{8}\n/)
  assert.ok(blocks[0].includes(`decision ${idOf(DECISION).slice(0, 8)}`))
  assert.match(blocks[0], /\nrun: status paused phase paused method ralph\n/)
  assert.match(blocks[1], / closed: /)
  assert.match(blocks[1], /\nitems: done 2, stopped 0, deferred 0, held 0, excluded 0\n/)
  assert.match(blocks[1], /\nclose: completed, open decisions none\n/)
  assert.ok(out.indexOf('Final Summary') < out.indexOf(' closed: '), 'the closed block follows the summary')
  for (const t of [BEARER, ...s.api.seats.map((x) => x.token), s.api.run.coordinatorToken].filter(Boolean)) {
    assert.equal(out.includes(t), false, 'no token value on stdout')
  }
})
