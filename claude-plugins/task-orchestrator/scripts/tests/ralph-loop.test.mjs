// Fixture-loop coverage for scripts/ralph-loop.mjs (cae5d141: ralph on runs). Test author actor id:
// test-author:cae5d141:r-202610170015-cae5d141.
//
// ralph-loop.mjs has no exports, so every test here runs it as a subprocess (`--claude-bin` is the declared
// test seam) against an in-test node:http REST stub (fixtures/ralph/fake-api.mjs) and a fake `claude`
// (fixtures/ralph/fake-claude.mjs). Both fixtures are derived from current/docs/api-reference.md (envelope,
// error catalog, runs_write/advance op references), never from the driver.
//
// Oracles (task-scope = TS): D1 REST + tokens, D3 token file, D4 membership/open op, D5 budgets, D6 claim
// ownership, D7 selector + skip list, D9 timeout/crash, D10 run end, D11 usage, D12 test seam, D13 preflight;
// AC3-AC6, AC8, AC9; plan S7 (coordinator token in memory only) and S15 (budgets opt-in).
//
// Scenario ids are the item's test-plan ids: S1 (open op/budgets), S2 (lifecycle), S3 (timeout/crash/spawn),
// S6 (assign faults), S7 (token hygiene), S11 (usage), S13 (preflight). S4/S5 live in
// ralph-idle-backoff.test.mjs, S8/S10 in ralph-lib.test.mjs and ralph-prompts-render.test.mjs.

import { test, after, describe } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { startFakeApi } from './fixtures/ralph/fake-api.mjs'
import { DEFAULT_IDLE_BACKOFF_MS, HEADLESS_ITERATION_MODE, REDACTED_TOKEN } from '../ralph-lib.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const LOOP = path.resolve(HERE, '..', 'ralph-loop.mjs')
const FAKE_CLAUDE = path.join(HERE, 'fixtures', 'ralph', 'fake-claude.mjs')
const ROOT = '11111111-2222-4333-8444-555555555555'
const BEARER = 'bearer-0a1b2c3d4e5f60718293a4b5c6d7e8f9'
const A = 'aaaa1111'
const B = 'bbbb2222'

// ---- harness -----------------------------------------------------------------------------------------

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
    if (k.startsWith('TASK_ORCHESTRATOR_') || k === 'AGENT_CONFIG_DIR' || k.startsWith('FAKE_CLAUDE_')) delete env[k]
  }
  return { ...env, ...extra }
}

function runDriver({ argv, env, cwd, timeoutMs, onStart }) {
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
    if (onStart) onStart(child)
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

// One driver run against a fresh stub. `items` are the queue ids (oldest first). `plan` is the fake claude's
// per-invocation behaviour. `args` extend/override the defaults (a later duplicate string option wins).
async function scenario(o = {}) {
  const { items = [], apiOpts = {}, plan = [], args = [], env = {}, timeoutMs = 30000, onStart, useApi = true } = o
  const cwd = tmp('ralph-cwd-')
  const home = tmp('ralph-home-')
  const aux = tmp('ralph-aux-')
  const scratch = path.join(tmp('ralph-scratch-'), 'run')
  try {
    execFileSync('git', ['init', '-q'], { cwd, stdio: 'ignore' })
  } catch {
    // the worktree cleanup is disabled by --no-cleanup; a repository is only for realism
  }
  const api = useApi ? await startFakeApi({ items: items.map((id) => ({ id })), ...apiOpts }) : null
  const logFile = path.join(aux, 'claude.jsonl')
  const argv = ['--claude-bin', FAKE_CLAUDE, '--root', ROOT, '--scratch-dir', scratch, '--no-cleanup', '--ttl', '120', '--budget', '1', '--max', '1', ...args]
  const envv = baseEnv({
    TASK_ORCHESTRATOR_HOME: home,
    TASK_ORCHESTRATOR_CEILING: cwd,
    FAKE_CLAUDE_LOG: logFile,
    FAKE_CLAUDE_PLAN: JSON.stringify(plan),
    FAKE_CLAUDE_ITEM_IDS: items.join(','),
    ...(api ? { TASK_ORCHESTRATOR_API_URL: api.base, TASK_ORCHESTRATOR_API_TOKEN: BEARER } : {}),
    ...env,
  })
  const res = await runDriver({ argv, env: envv, cwd, timeoutMs, onStart: onStart ? (child) => onStart(child, { logFile }) : undefined })
  if (api) await api.stop()
  const log = readJsonl(logFile)
  return { api, res, cwd, scratch, logFile, log, starts: log.filter((r) => r.type === 'start'), finishes: log.filter((r) => r.type === 'finish') }
}

const once = (fn) => {
  let p
  return () => (p ??= fn())
}
const tail = (s) => `\n--- driver output (tail) ---\n${s.res.out.slice(-1500)}`
const opsNamed = (s, name) => s.api.trace.filter((t) => t.op === name)
const writes = (s) => s.api.trace.filter((t) => t.tool === 'runs_write' || t.tool === 'advance')

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else out.push(p)
  }
  return out
}
function filesContaining(dirs, needles) {
  const hits = []
  for (const d of dirs) {
    for (const f of walk(d)) {
      if (f.includes(`${path.sep}.git${path.sep}`)) continue
      const text = fs.readFileSync(f, 'utf8')
      if (needles.some((n) => text.includes(n))) hits.push(f)
    }
  }
  return hits
}

// The shared S2 run: A (oldest) answers claim_held{retryAfterMs: 60000} on every claim, B is claimable and
// never leaves the queue (the fake claude does not transition it), three iterations.
const s2 = once(() =>
  scenario({
    items: [A, B],
    apiOpts: { claim: ({ itemId }) => (itemId === A ? { code: 'claim_held', retryAfterMs: 60000 } : undefined) },
    args: ['--max', '3'],
    timeoutMs: 40000,
  }),
)

// ---- S2: lifecycle, AC3 / AC4 ------------------------------------------------------------------------

describe('S2 fixture loop', () => {
  test('AC3: the loop opens exactly one run first and closes it last with completed, under the coordinator token', async () => {
    const s = await s2()
    assert.equal(s.res.timedOut, false, 'driver finished within the budget (a 60s idle wait on A means the skip list was lost)' + tail(s))
    assert.equal(s.res.code, 0, 'exit code' + tail(s))
    const w = writes(s)
    const opens = opsNamed(s, 'open')
    assert.equal(opens.length, 1, 'exactly one open per loop')
    assert.equal(w[0].op, 'open', 'open is the first write')
    const open = opens[0]
    assert.equal(open.seatToken, undefined, 'open is sent with no seat token')
    assert.equal(open.rootId, ROOT)
    assert.ok(typeof open.label === 'string' && open.label.length > 0 && open.label.length <= 64, 'label within 64 chars')
    assert.ok(open.label.startsWith('ralph-'), 'label is ralph-<pid>-<ts>')
    assert.ok(open.title.startsWith('ralph drain: '), 'title is "ralph drain: <filter or any>"')
    assert.equal(open.hints.tool, 'ralph')
    for (const [k, v] of Object.entries(open.hints)) assert.equal(typeof v, 'string', `hints.${k} must be a string (hints is a map of string to string)`)
    assert.equal('items' in open, false, 'no members at open without --items')
    assert.equal('budget' in open, false, 'S15: no budget unless a budget flag is passed')
    const closes = opsNamed(s, 'close')
    assert.equal(closes.length, 1, 'exactly one close')
    assert.equal(w.at(-1).op, 'close', 'close is the last write')
    assert.equal(closes[0].reason, 'completed')
    assert.equal(closes[0].seatToken, s.api.coordinatorToken, 'close carries the coordinator token')
    assert.equal(closes[0].runId, s.api.run.runId)
    assert.equal(s.api.run.status, 'closed')
  })

  test('AC4: A answers claim_held{retryAfterMs: 60000}, is claimed once and never again; B is claimed, completed and the loop runs three iterations', async () => {
    const s = await s2()
    assert.equal(s.res.timedOut, false, tail(s))
    const claims = opsNamed(s, 'claim').filter((c) => c.opsInCall === 1)
    const aClaims = claims.filter((c) => c.itemId.toLowerCase() === A)
    const bClaims = claims.filter((c) => c.itemId.toLowerCase() === B)
    assert.equal(aClaims.length, 1, 'A is never re-claimed after its claim_held (it is on the per-loop skip list)')
    assert.ok(bClaims.length >= 2, `B is claimed on the later iterations (got ${bClaims.length})`)
    const nexts = s.api.trace.filter((t) => t.op === 'next')
    for (const n of nexts) {
      assert.equal(n.seatToken, undefined, 'reads go token-less (D1)')
      assert.equal(n.body.role, 'queue')
      assert.equal(n.body.orderBy, 'oldest')
      assert.equal(n.body.ancestorId, ROOT, 'next is scoped to the root (D7)')
      assert.ok(Number.isInteger(n.body.limit) && n.body.limit >= 1 && n.body.limit <= 200, 'limit within 1..200')
    }
    assert.ok(nexts.some((n) => n.body.limit >= 2), 'once A is skip-listed the next call asks for skip.length + 1 candidates so B is reachable')
    const bSeats = s.api.seats.filter((x) => x.itemId === B)
    assert.ok(bSeats.length >= 2)
    assert.ok(bSeats.every((x) => x.outcome === 'done'), 'every B seat ends done')
    assert.equal(s.starts.length, bSeats.length, 'one claude spawn per claimed B seat, none for the contended A')
  })

  test('AC3/D6: every claim and end_seat carries the seat token of its own assignment; update, assign and close carry the coordinator token; each seat ends exactly once, before the next assign and before close', async () => {
    const s = await s2()
    const coord = s.api.coordinatorToken
    const t = s.api.trace
    for (const op of ['update', 'assign', 'close']) {
      for (const x of opsNamed(s, op)) assert.equal(x.seatToken, coord, `${op} carries the coordinator token`)
    }
    const assigns = opsNamed(s, 'assign')
    assert.equal(assigns.length, s.api.seats.length)
    assigns.forEach((a) => {
      assert.equal(a.runId, s.api.run.runId)
      assert.equal(a.seat, 'implementer')
    })
    const seats = s.api.seats
    const ends = opsNamed(s, 'end_seat')
    assert.equal(ends.length, seats.length, 'one end_seat per assignment')
    assert.deepEqual([...new Set(ends.map((e) => e.seatToken))].sort(), seats.map((x) => x.token).sort(), 'each seat token ends exactly once')
    for (const e of ends) assert.notEqual(e.seatToken, coord, 'end_seat never uses the coordinator token')
    const closeSeq = opsNamed(s, 'close')[0].seq
    seats.forEach((seat, i) => {
      const claim = opsNamed(s, 'claim').find((c) => c.opsInCall === 1 && c.seatToken === seat.token)
      assert.ok(claim, `seat ${i} claims under its own token`)
      assert.equal(claim.itemId.toLowerCase(), seat.itemId, 'the claim names the assigned item')
      assert.equal(claim.ttlSeconds, 120, 'claim ttl is --ttl')
      const end = ends.find((e) => e.seatToken === seat.token)
      assert.ok(assigns[i].seq < claim.seq && claim.seq < end.seq && end.seq < closeSeq, `seat ${i}: assign < claim < end_seat < close`)
      if (i + 1 < assigns.length) assert.ok(end.seq < assigns[i + 1].seq, `seat ${i} ends before the next assign (one active seat at a time)`)
    })
    // membership: every assigned item was added with update{addItems} before its first assign
    const firstAssignOf = new Map()
    assigns.forEach((a) => !firstAssignOf.has(a.itemId.toLowerCase()) && firstAssignOf.set(a.itemId.toLowerCase(), a))
    for (const [item, a] of firstAssignOf) {
      const added = t.find((x) => x.op === 'update' && (x.addItems ?? []).map((i) => i.toLowerCase()).includes(item) && x.seq < a.seq)
      assert.ok(added, `${item} is added to the run before it is assigned`)
    }
  })

  test('D9: the seat of a claim_held item ends abandoned (a skip)', async () => {
    const s = await s2()
    const aSeats = s.api.seats.filter((x) => x.itemId === A)
    assert.equal(aSeats.length, 1)
    assert.equal(aSeats[0].outcome, 'abandoned')
  })

  test('every request carries the API bearer from TASK_ORCHESTRATOR_API_TOKEN (D1)', async () => {
    const s = await s2()
    assert.ok(s.api.hits.length > 0)
    for (const h of s.api.hits) assert.equal(h.headers.authorization, `Bearer ${BEARER}`)
  })
})

// ---- S7: token hygiene (AC5) -------------------------------------------------------------------------

describe('S7 token hygiene', () => {
  test('D3: each iteration is handed the token FILE path (never the value) and the file holds exactly the seat token', async () => {
    const s = await s2()
    assert.ok(s.starts.length >= 2)
    for (const st of s.starts) {
      assert.ok(st.tokenFile, 'TASK_ORCHESTRATOR_SEAT_TOKEN_FILE is set in the iteration env')
      const seat = s.api.seats.find((x) => x.token === st.token)
      assert.ok(seat, 'the file content is a token the server issued')
      assert.equal(seat.itemId, B)
      assert.equal(st.fileHasCR, false, 'no CR in the token file')
      assert.equal(st.fileLineCount, 1, 'one line')
      assert.equal(st.leaks.argv, false, 'the token value is in no argv element')
      assert.deepEqual(st.leaks.envKeys, [], 'the token value is in no env value other than via the file')
      assert.equal(st.mode, HEADLESS_ITERATION_MODE)
      assert.ok(path.resolve(st.tokenFile).startsWith(path.resolve(s.scratch) + path.sep), 'the file lives under --scratch-dir')
      assert.equal(path.basename(path.dirname(st.tokenFile)), 'tokens')
      assert.ok(path.basename(st.tokenFile).endsWith('.implementer.token'))
      // platform-only: POSIX modes are not enforced on Windows (TS D3 "modes are advisory on Windows")
      if (process.platform !== 'win32') {
        assert.equal(st.fileMode, 0o600)
        assert.equal(st.dirMode, 0o700)
      }
      const prompt = st.argv.at(-1)
      assert.ok(prompt.includes(st.tokenFile), 'the prompt names the same token-file path (${seat_token_file})')
      assert.ok(prompt.includes(s.api.run.runId), 'the prompt carries the run id (${run_id})')
      assert.ok(prompt.toLowerCase().includes(B), 'the prompt carries the item id (${item_id})')
      assert.ok(prompt.includes('120'), 'the prompt carries the ttl (${ttl})')
      assert.equal(prompt.includes('${'), false, 'no unexpanded prompt variable')
      assert.equal(prompt.includes(st.token), false, 'the token value is not in the prompt')
      assert.equal(fs.existsSync(st.tokenFile), false, 'the token file is removed after end_seat')
    }
  })

  test('AC5: the driver output holds no token value, no API bearer and neither of the words seatToken / coordinatorToken; no file under the cwd or scratch dir holds a token', async () => {
    const s = await s2()
    const secrets = [...s.api.seatTokens, s.api.coordinatorToken, BEARER]
    assert.ok(secrets.every(Boolean) && secrets.length >= 4)
    for (const sec of secrets) assert.equal(s.res.out.includes(sec), false, 'a credential value reached the driver output')
    assert.equal(s.res.out.includes('seatToken'), false)
    assert.equal(s.res.out.includes('coordinatorToken'), false)
    assert.deepEqual(filesContaining([s.cwd, s.scratch], secrets), [], 'a credential value was persisted to disk')
  })

  test('AC5: an iteration that echoes its token in its result text and on stderr is masked as [redacted-token] in the driver output', async () => {
    const s = await scenario({ items: [A], plan: ['echo'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.starts.length, 1)
    const token = s.starts[0].token
    assert.ok(token && s.api.seatTokens.includes(token))
    // fixture condition: the fake wrote the token to stdout (result text) and stderr, so an unredacted stream would contain it
    assert.equal(s.res.out.includes(token), false, 'the echoed token must not survive in the driver output')
    assert.ok(s.res.out.includes(REDACTED_TOKEN), 'the echoed token shows as the redaction marker')
    assert.deepEqual(filesContaining([s.cwd, s.scratch], [token, s.api.coordinatorToken]), [], 'the echo was not persisted')
  })

  test('AC5 probe: a token split across two stderr writes is not reassembled in the driver output', async () => {
    const s = await scenario({ items: [A], plan: ['echo-split'] })
    assert.equal(s.res.timedOut, false, tail(s))
    const token = s.starts[0].token
    assert.equal(s.res.out.includes(token), false, 'the two halves, printed back to back, must not form the token')
  })
})

// ---- S1: budgets and membership on the open op (CLI level), D4 / D5 / S15 ---------------------------------

test('S1/D5: --run-budget-tokens, --run-budget-usd and --max-attempts become budget{tokens, costUsd, attemptsPerSeat} on open, and never maxActiveSeats', async () => {
  const s = await scenario({ items: [A], args: ['--run-budget-tokens', '1000', '--run-budget-usd', '12.5', '--max-attempts', '3'] })
  assert.equal(s.res.timedOut, false, tail(s))
  const open = opsNamed(s, 'open')[0]
  assert.deepEqual(open.budget, { tokens: 1000, costUsd: 12.5, attemptsPerSeat: 3 })
})

test('S1/D5: a single --run-budget-usd sends only costUsd', async () => {
  const s = await scenario({ items: [A], args: ['--run-budget-usd', '12.5'] })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.deepEqual(opsNamed(s, 'open')[0].budget, { costUsd: 12.5 })
})

test('D4: --items lists the members on open, de-duplicated, and the picked item is one of them; an earlier non-member in the queue is never assigned', async () => {
  const X = 'cccc3333'
  const s = await scenario({ items: [X, A], args: ['--items', `${A},${A}`, '--idle-budget', '0'], timeoutMs: 20000 })
  assert.equal(s.res.timedOut, false, tail(s))
  const open = opsNamed(s, 'open')[0]
  assert.deepEqual(open.items, [A], 'duplicate --items ids collapse to one member')
  const assigned = opsNamed(s, 'assign').map((a) => a.itemId.toLowerCase())
  assert.ok(assigned.length >= 1, 'a member behind a non-member at the head of the queue is still reachable' + tail(s))
  assert.ok(assigned.every((id) => id === A), 'only members are assigned')
})

// ---- S3: timeout, spawn error, crash (AC6, D9) -------------------------------------------------------

describe('S3 timeout and crash recovery', () => {
  test('AC6: a timed-out iteration is killed, recorded iteration_timeout, its seat ends failed, and the next iteration starts', async () => {
    const s = await scenario({ items: [A], plan: ['sleep', 'terminal'], args: ['--iteration-timeout', '2', '--max', '2', '--error-budget', '3'], timeoutMs: 40000 })
    assert.equal(s.res.timedOut, false, 'the driver must kill the sleeper itself' + tail(s))
    assert.equal(s.starts.length, 2, 'the next iteration started')
    assert.equal(s.finishes.some((f) => f.idx === 0), false, 'the first iteration never finished: it was killed')
    assert.equal(s.finishes.some((f) => f.idx === 1), true)
    assert.ok(s.res.out.includes('iteration_timeout'), 'the outcome is recorded with code iteration_timeout' + tail(s))
    const seats = s.api.seats
    assert.equal(seats.length, 2)
    assert.equal(seats[0].outcome, 'failed')
    assert.equal(seats[1].outcome, 'done')
    const ends = opsNamed(s, 'end_seat')
    const assigns = opsNamed(s, 'assign')
    assert.ok(ends[0].seq < assigns[1].seq, 'the timed-out seat is ended before the next assignment')
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
    assert.equal(writes(s).at(-1).op, 'close')
  })

  test('AC6: a spawn error is recorded spawn_error, ends the seat failed and closes the run', async () => {
    const missing = path.join(tmp('ralph-nobin-'), 'no-such-claude')
    const s = await scenario({ items: [A], args: ['--claude-bin', missing, '--error-budget', '3'], timeoutMs: 30000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.starts.length, 0)
    assert.ok(s.res.out.includes('spawn_error'), tail(s))
    assert.equal(s.api.seats.length, 1)
    assert.equal(s.api.seats[0].outcome, 'failed')
    assert.equal(writes(s).at(-1).op, 'close')
    assert.equal(s.api.run.status, 'closed')
  })

  test('D9: a non-zero exit is an error outcome whose seat ends failed, and it is never resumed', async () => {
    const s = await scenario({ items: [A], plan: ['crash', 'terminal'], args: ['--max', '2', '--error-budget', '3', '--max-continuations', '2'], timeoutMs: 30000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.starts.length, 2, 'two iterations, no resume of the crashed one')
    assert.equal(s.starts.some((st) => st.argv.includes('--resume')), false)
    assert.equal(s.api.seats[0].outcome, 'failed')
    assert.equal(s.api.seats[1].outcome, 'done')
  })

  test('probe (POSIX): SIGTERM mid-iteration kills the iteration, ends its seat failed and closes the run cancelled', { skip: process.platform === 'win32' ? 'POSIX signal semantics: Windows terminates the driver without running a handler' : false }, async () => {
    const s = await scenario({
      items: [A],
      plan: ['sleep'],
      timeoutMs: 30000,
      onStart: (child, { logFile }) => {
        const iv = setInterval(() => {
          if (readJsonl(logFile).some((r) => r.type === 'start')) {
            clearInterval(iv)
            child.kill('SIGTERM')
          }
        }, 50)
      },
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.api.seats.length, 1)
    assert.equal(s.api.seats[0].outcome, 'failed')
    assert.equal(opsNamed(s, 'close')[0].reason, 'cancelled')
    assert.equal(writes(s).at(-1).op, 'close')
  })
})

// ---- iteration outcomes through the table (AC8, D7, D10) ---------------------------------------------

describe('iteration outcome codes', () => {
  test('D7: an iteration skip with code resource_unavailable and retryAfterMs 60000 skip-lists that item for the loop; the next iteration takes B', async () => {
    const s = await scenario({ items: [A, B], plan: [{ kind: 'skip', code: 'resource_unavailable', retryAfterMs: 60000 }, 'terminal'], args: ['--max', '2'], timeoutMs: 30000 })
    assert.equal(s.res.timedOut, false, tail(s))
    const assigns = opsNamed(s, 'assign').map((a) => a.itemId.toLowerCase())
    assert.deepEqual(assigns, [A, B], 'A is tried once, then B (A is skip-listed, not re-assigned)')
    assert.equal(s.api.seats[0].outcome, 'abandoned')
    assert.equal(s.api.seats[1].outcome, 'done')
  })

  test('AC8: a run_paused code from the iteration ends the loop: seat failed, run closed cancelled, no further assignment', async () => {
    const s = await scenario({ items: [A], plan: [{ kind: 'error', code: 'run_paused' }, 'terminal'], args: ['--max', '3'], timeoutMs: 30000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.starts.length, 1)
    assert.equal(opsNamed(s, 'assign').length, 1)
    assert.equal(s.api.seats[0].outcome, 'failed')
    assert.equal(opsNamed(s, 'close')[0].reason, 'cancelled')
    assert.equal(writes(s).at(-1).op, 'close')
  })

  test('AC8: a run_closed code from the iteration ends the loop with no further assignment', async () => {
    const s = await scenario({ items: [A], plan: [{ kind: 'error', code: 'run_closed' }, 'terminal'], args: ['--max', '3'], timeoutMs: 30000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.starts.length, 1)
    assert.equal(opsNamed(s, 'assign').length, 1)
  })
})

// ---- S6: coordinator-side assign faults (D10) --------------------------------------------------------

describe('S6 assign faults', () => {
  test('run_paused on assign ends the loop, is never retried, and the run is closed cancelled', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { assign: () => ({ code: 'run_paused', detail: { runId: ROOT, pauseReason: 'budget', pauseDecisionId: 'dddd4444' } }) },
      args: ['--max', '3'],
      timeoutMs: 30000,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(opsNamed(s, 'assign').length, 1, 'run_paused is not retried')
    assert.equal(opsNamed(s, 'claim').length, 0)
    assert.equal(s.starts.length, 0)
    assert.match(s.res.out, /run paused/i)
    assert.equal(opsNamed(s, 'close')[0].reason, 'cancelled')
    assert.equal(writes(s).at(-1).op, 'close')
  })

  test('run_closed on assign ends the loop without retrying', async () => {
    const s = await scenario({ items: [A], apiOpts: { assign: () => ({ code: 'run_closed', detail: { runId: ROOT, terminalReason: 'cancelled' } }) }, args: ['--max', '3'], timeoutMs: 30000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(opsNamed(s, 'assign').length, 1)
    assert.equal(s.starts.length, 0)
  })

  test('a coordinator token refused on assign (401 unauthenticated) exits 2', async () => {
    const s = await scenario({ items: [A], apiOpts: { assign: () => ({ http401: true }) }, args: ['--max', '3'], timeoutMs: 30000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 2, 'exit code' + tail(s))
    assert.equal(opsNamed(s, 'assign').length, 1)
    assert.equal(s.starts.length, 0)
  })

  test('run_at_capacity on assign waits DEFAULT_IDLE_BACKOFF_MS and re-assigns, and does not count as an idle (--idle-budget 0)', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { assign: ({ nth }) => (nth === 1 ? { code: 'run_at_capacity', detail: { runId: ROOT, active: 1, max: 1 } } : undefined) },
      args: ['--idle-budget', '0'],
      timeoutMs: 60000,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    const assignHits = s.api.hits.filter((h) => h.raw.includes('"assign"'))
    assert.equal(assignHits.length, 2, 'assign retried exactly once' + tail(s))
    const gap = assignHits[1].ts - assignHits[0].ts
    assert.ok(gap >= DEFAULT_IDLE_BACKOFF_MS - 2000, `waited about ${DEFAULT_IDLE_BACKOFF_MS} ms before re-assigning (got ${gap})`)
    assert.ok(gap < DEFAULT_IDLE_BACKOFF_MS + 15000, `and not much longer (got ${gap})`)
    assert.equal(s.starts.length, 1)
    assert.equal(s.api.seats[0].outcome, 'done')
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
  })
})

// ---- S11: usage (AC9, D11) ---------------------------------------------------------------------------

describe('S11 usage report', () => {
  const usagePlan = [{ kind: 'terminal', total_cost_usd: 1.25, usage: { input_tokens: 100, output_tokens: 50, cache_creation_input_tokens: 10, cache_read_input_tokens: 5 } }]

  test('total_cost_usd 1.25 is reported as one advance [claim, heartbeat{usage}, release] call under the seat token, before end_seat', async () => {
    const s = await scenario({ items: [A], plan: usagePlan })
    assert.equal(s.res.timedOut, false, tail(s))
    const seat = s.api.seats[0]
    const hb = opsNamed(s, 'heartbeat')
    assert.equal(hb.length, 1)
    assert.equal(hb[0].seatToken, seat.token)
    assert.equal(hb[0].usage.costUsd, 1.25)
    assert.ok(Number.isFinite(hb[0].usage.tokens) && hb[0].usage.tokens >= 150 && hb[0].usage.tokens <= 165, `tokens between input+output and the sum of all four counters (got ${hb[0].usage.tokens})`)
    const batchOps = s.api.trace.filter((t) => t.tool === 'advance' && t.opsInCall === 3).sort((x, y) => x.seq - y.seq)
    assert.deepEqual(batchOps.map((x) => x.op), ['claim', 'heartbeat', 'release'])
    for (const x of batchOps) {
      assert.equal(x.seatToken, seat.token)
      assert.equal(x.itemId.toLowerCase(), A)
    }
    assert.equal(batchOps[0].ttlSeconds, 120)
    const end = opsNamed(s, 'end_seat')[0]
    assert.ok(batchOps[2].seq < end.seq, 'usage is reported before the seat ends')
    assert.equal(end.outcome, 'done')
  })

  test('a failed usage report is logged usage-report-failed, is not retried, and never blocks end_seat', async () => {
    const s = await scenario({ items: [A], plan: usagePlan, apiOpts: { claim: ({ nthForToken }) => (nthForToken >= 2 ? { code: 'unavailable', retryAfterMs: 1000 } : undefined) } })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.ok(s.res.out.includes('usage-report-failed'), tail(s))
    assert.equal(opsNamed(s, 'claim').length, 2, 'the usage claim is not retried')
    assert.equal(opsNamed(s, 'heartbeat').length, 1)
    const ends = opsNamed(s, 'end_seat')
    assert.equal(ends.length, 1)
    assert.equal(ends[0].outcome, 'done')
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
  })

  test('no usage report when the envelope carries neither total_cost_usd nor usage', async () => {
    const s = await scenario({ items: [A], plan: ['terminal'] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(opsNamed(s, 'heartbeat').length, 0)
    assert.equal(opsNamed(s, 'release').length, 0)
  })
})

// ---- S13: preflight and usage errors (D1, D4, D13, AC1, AC10) ----------------------------------------

describe('S13 preflight', () => {
  test('no API base URL exits 64 and names TASK_ORCHESTRATOR_API_URL', async () => {
    const s = await scenario({ items: [A], useApi: false })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 64, tail(s))
    assert.ok(s.res.out.includes('TASK_ORCHESTRATOR_API_URL'))
    assert.equal(s.starts.length, 0)
  })

  test('no root id (no --root, no ancestorId in the filter, no config) exits 64 before opening a run', async () => {
    const cwd = tmp('ralph-cwd-')
    const api = await startFakeApi({ items: [{ id: A }] })
    try {
      const res = await runDriver({
        argv: ['--claude-bin', FAKE_CLAUDE, '--no-cleanup', '--scratch-dir', path.join(tmp('ralph-scratch-'), 'run'), '--max', '1'],
        env: baseEnv({ TASK_ORCHESTRATOR_API_URL: api.base, TASK_ORCHESTRATOR_HOME: tmp('ralph-home-'), TASK_ORCHESTRATOR_CEILING: cwd }),
        cwd,
        timeoutMs: 20000,
      })
      assert.equal(res.timedOut, false)
      assert.equal(res.code, 64, res.out.slice(-1500))
      assert.match(res.out, /root/i)
      assert.equal(api.trace.filter((t) => t.op === 'open').length, 0, 'no run was opened')
    } finally {
      await api.stop()
    }
  })

  test('--dry-run makes no HTTP call, spawns nothing, and prints the would-be open op', async () => {
    const s = await scenario({ items: [A], args: ['--dry-run'], timeoutMs: 20000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.equal(s.api.hits.length, 0, 'zero requests')
    assert.equal(s.starts.length, 0)
    assert.ok(s.res.out.includes('open'), 'prints the open op')
    assert.ok(s.res.out.includes(ROOT), 'prints the root id')
  })

  for (const [label, argv, message] of [
    ['--ttl 30', ['--ttl', '30'], '--ttl must be between 60 and 86400 seconds'],
    ['--ttl 86401', ['--ttl', '86401'], '--ttl must be between 60 and 86400 seconds'],
    ['--budget 0', ['--budget', '0'], '--budget must be > 0'],
    ['--iteration-timeout 0', ['--iteration-timeout', '0'], '--iteration-timeout must be at least 1 second'],
  ]) {
    test(`usage error: ${label} exits 64 with the declared message`, async () => {
      const s = await scenario({ items: [A], args: [...argv, '--dry-run'], timeoutMs: 20000 })
      assert.equal(s.res.timedOut, false, tail(s))
      assert.equal(s.res.code, 64, tail(s))
      assert.ok(s.res.out.includes(message), tail(s))
    })
  }

  test('AC1: the removed --actor option is a usage error (exit 64)', async () => {
    const s = await scenario({ items: [A], args: ['--actor', 'someone', '--dry-run'], timeoutMs: 20000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 64, tail(s))
    assert.equal(s.api.hits.length, 0)
  })

  test('AC10: --help lists the new flags and no --actor', async () => {
    const s = await scenario({ items: [A], args: ['--help'], timeoutMs: 20000 })
    assert.equal(s.res.timedOut, false, tail(s))
    for (const flag of ['--iteration-timeout', '--run-budget-tokens', '--run-budget-usd', '--max-attempts', '--root', '--items', '--scratch-dir', '--claude-bin']) {
      assert.ok(s.res.out.includes(flag), `--help names ${flag}`)
    }
    assert.equal(/\bactor\b/i.test(s.res.out), false, '--help no longer mentions an actor option')
  })
})

// ---- A2 B1: a signal landing in a pre-spawn window (update / assign / claim) -------------------------
// Oracle: task-scope Amendment A2 (B1) and the driver contract (SKILL.md / driver header): a SIGTERM at any
// point still ends the active seat (if any), closes the run `cancelled` as the last write, spawns nothing,
// leaves no token file, exits 143, and does so without needing the 30 s shutdown backstop. The fake REST
// hooks run synchronously before the answer is written, so the test kills the driver INSIDE the hook (then
// holds the answer for a moment so the signal is delivered first) and then grants: that is the deterministic
// "signal in the window". W4 (the sub-millisecond window between the claim answer and spawn, plus the
// token-file write) has no external hook and is deliberately NOT tested here: an honest untestable limit,
// covered only by the existing mid-iteration SIGTERM probe above.

const SIGNAL_SKIP = process.platform === 'win32' ? 'POSIX signal semantics: Windows terminates the driver without running a handler' : false

function killDriverAndHold(getChild) {
  getChild().kill('SIGTERM')
  const until = Date.now() + 300
  while (Date.now() < until) {
    // busy-wait on purpose: the hook must not answer before the driver has received the signal
  }
}

async function signalScenario(hookName, trigger) {
  let child
  return scenario({
    items: [A],
    apiOpts: {
      [hookName]: (ctx) => {
        if (trigger(ctx)) killDriverAndHold(() => child)
        return undefined
      },
    },
    timeoutMs: 30000,
    onStart: (c) => {
      child = c
    },
  })
}

function assertCancelledShutdown(s) {
  assert.equal(s.res.timedOut, false, tail(s))
  assert.ok(s.res.elapsedMs < 25000, `the 30 s shutdown backstop must not have fired (took ${s.res.elapsedMs} ms)` + tail(s))
  assert.equal(s.res.code, 143, 'exit code' + tail(s))
  const closes = opsNamed(s, 'close')
  assert.equal(closes.length, 1, 'exactly one close')
  assert.equal(closes[0].reason, 'cancelled')
  assert.equal(writes(s).at(-1).op, 'close', 'close is the last write')
  assert.equal(s.starts.length, 0, 'nothing was spawned')
  assert.equal(fs.existsSync(path.join(s.scratch, 'tokens')), false, 'no tokens dir is left under the scratch dir')
}

describe('A2 B1 signal windows', () => {
  test('W1: SIGTERM during update addItems -> no assign, no seat, run closed cancelled', { skip: SIGNAL_SKIP, timeout: 30000 }, async () => {
    const s = await signalScenario('update', ({ nth }) => nth === 1)
    assertCancelledShutdown(s)
    assert.equal(opsNamed(s, 'assign').length, 0)
    assert.equal(s.api.seats.length, 0)
  })

  test('W2: SIGTERM during assign -> the seat ends abandoned before close, no claim is made', { skip: SIGNAL_SKIP, timeout: 30000 }, async () => {
    const s = await signalScenario('assign', ({ nth }) => nth === 1)
    assertCancelledShutdown(s)
    assert.equal(s.api.seats.length, 1)
    assert.equal(s.api.seats[0].outcome, 'abandoned')
    assert.equal(opsNamed(s, 'claim').length, 0)
    const end = opsNamed(s, 'end_seat')[0]
    assert.ok(end, 'the seat is ended')
    assert.ok(end.seq < opsNamed(s, 'close')[0].seq, 'end_seat precedes close')
  })

  test('W3: SIGTERM during the seat claim -> the claim was made, the seat ends abandoned before close', { skip: SIGNAL_SKIP, timeout: 30000 }, async () => {
    const s = await signalScenario('claim', ({ nth }) => nth === 1)
    assertCancelledShutdown(s)
    assert.equal(s.api.seats.length, 1)
    assert.equal(s.api.seats[0].outcome, 'abandoned')
    assert.equal(opsNamed(s, 'claim').length, 1)
    const end = opsNamed(s, 'end_seat')[0]
    assert.ok(end, 'the seat is ended')
    assert.ok(end.seq < opsNamed(s, 'close')[0].seq, 'end_seat precedes close')
  })
})

// ---- A2 B2: transient / shedding driver-call errors back the loop off, never skip-list -----------------
// Oracle: task-scope Amendment A2 (B2), error catalog (unavailable = shedding, always with retryAfterMs;
// internal = transient, no retryAfterMs) and the api-reference retry guide: transient and shedding both retry
// the same request after retryAfterMs (default 30 s when absent). Failure mode without the fix: the item is
// permanently skip-listed and the loop reports drained.

describe('A2 B2 transient driver errors', () => {
  const hitsFor = (s, word) => s.api.hits.filter((h) => h.raw.includes(`"${word}"`))

  test('update addItems unavailable{retryAfterMs: 1000} backs off and retries the same update; the item is then worked', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { update: ({ nth }) => (nth === 1 ? { code: 'unavailable', retryAfterMs: 1000 } : undefined) },
      timeoutMs: 30000,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    const updates = hitsFor(s, 'update')
    assert.equal(updates.length, 2, 'the update is repeated once' + tail(s))
    assert.ok(updates[1].ts - updates[0].ts >= 1000, `waited at least retryAfterMs (got ${updates[1].ts - updates[0].ts})`)
    assert.equal(opsNamed(s, 'assign').length, 1)
    assert.equal(s.api.seats.length, 1)
    assert.equal(s.api.seats[0].outcome, 'done')
    assert.equal(s.starts.length, 1)
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
    assert.equal(writes(s).at(-1).op, 'close')
    assert.ok(s.res.out.includes('idle (update addItems: unavailable)'), tail(s))
  })

  test('seat claim unavailable{retryAfterMs: 1000} ends the seat abandoned, waits, and the same item is assigned again and worked', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { claim: ({ nth }) => (nth === 1 ? { code: 'unavailable', retryAfterMs: 1000 } : undefined) },
      timeoutMs: 30000,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.deepEqual(s.api.seats.map((x) => [x.itemId, x.outcome]), [[A, 'abandoned'], [A, 'done']])
    assert.equal(opsNamed(s, 'claim').filter((c) => c.opsInCall === 1).length, 2)
    const assigns = opsNamed(s, 'assign')
    assert.equal(assigns.length, 2)
    assert.ok(opsNamed(s, 'end_seat')[0].seq < assigns[1].seq, 'the first seat ends before the second assign')
    const assignHits = hitsFor(s, 'assign')
    assert.ok(assignHits[1].ts - assignHits[0].ts >= 1000, `waited at least retryAfterMs (got ${assignHits[1].ts - assignHits[0].ts})`)
    assert.equal(s.starts.length, 1)
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
    assert.equal(writes(s).at(-1).op, 'close')
  })

  test('seat claim internal (no retryAfterMs) waits about DEFAULT_IDLE_BACKOFF_MS, then the same item is assigned again and worked', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { claim: ({ nth }) => (nth === 1 ? { code: 'internal' } : undefined) },
      timeoutMs: 60000,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.deepEqual(s.api.seats.map((x) => [x.itemId, x.outcome]), [[A, 'abandoned'], [A, 'done']])
    assert.equal(opsNamed(s, 'claim').filter((c) => c.opsInCall === 1).length, 2)
    const assignHits = hitsFor(s, 'assign')
    const gap = assignHits[1].ts - assignHits[0].ts
    assert.ok(gap >= DEFAULT_IDLE_BACKOFF_MS - 2000, `waited about ${DEFAULT_IDLE_BACKOFF_MS} ms (got ${gap})`)
    assert.equal(s.starts.length, 1)
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
    assert.equal(writes(s).at(-1).op, 'close')
    assert.ok(s.res.out.includes('idle (claim: internal)'), tail(s))
  })

  test('an iteration skip carrying unavailable{retryAfterMs: 1000} is a timed skip: the same item is assigned again after the wait', async () => {
    const s = await scenario({
      items: [A],
      plan: [{ kind: 'skip', code: 'unavailable', retryAfterMs: 1000 }, 'terminal'],
      args: ['--max', '2'],
      timeoutMs: 30000,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.deepEqual(opsNamed(s, 'assign').map((a) => a.itemId.toLowerCase()), [A, A])
    assert.deepEqual(s.api.seats.map((x) => x.outcome), ['abandoned', 'done'])
    assert.equal(s.starts.length, 2)
  })
})

// ---- A3 B3: a persistent transient/shedding fault is bounded by --idle-budget -------------------------
// Oracle: task-scope Amendment A3 (frozen bound). A backed-off driver call is one idle; the N-th consecutive
// idle (N = --idle-budget) exits with no wait: N faulted picks, N-1 waits, reason 'idle budget exhausted',
// exit 0, close completed last, 0 iterations, 0 spawns.

describe('A3 B3 persistent transient fault is bounded by --idle-budget', () => {
  const hitsFor = (s, word) => s.api.hits.filter((h) => h.raw.includes(`"${word}"`))

  test('update addItems unavailable on every call: exactly --idle-budget picks, then idle budget exhausted', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { update: () => ({ code: 'unavailable', retryAfterMs: 1000 }) },
      args: ['--idle-budget', '3'],
      timeoutMs: 30000,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    const updates = hitsFor(s, 'update')
    assert.equal(updates.length, 3, 'exactly --idle-budget faulted picks' + tail(s))
    assert.ok(updates[1].ts - updates[0].ts >= 1000, `first wait >= retryAfterMs (got ${updates[1].ts - updates[0].ts})`)
    assert.ok(updates[2].ts - updates[1].ts >= 1000, `second wait >= retryAfterMs (got ${updates[2].ts - updates[1].ts})`)
    assert.equal(opsNamed(s, 'assign').length, 0)
    assert.equal(s.api.seats.length, 0)
    assert.equal(s.starts.length, 0)
    assert.ok(s.res.out.includes('idle budget exhausted'), tail(s))
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
    assert.equal(writes(s).at(-1).op, 'close')
  })

  test('seat claim unavailable on every call: exactly --idle-budget assigns, claims and abandoned seats, then idle budget exhausted', async () => {
    const s = await scenario({
      items: [A],
      apiOpts: { claim: () => ({ code: 'unavailable', retryAfterMs: 1000 }) },
      args: ['--idle-budget', '3'],
      timeoutMs: 30000,
    })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 0, tail(s))
    assert.deepEqual(s.api.seats.map((x) => x.outcome), ['abandoned', 'abandoned', 'abandoned'])
    assert.equal(opsNamed(s, 'claim').filter((c) => c.opsInCall === 1).length, 3)
    const assigns = opsNamed(s, 'assign')
    assert.equal(assigns.length, 3)
    const ends = opsNamed(s, 'end_seat')
    assert.ok(ends[0].seq < assigns[1].seq, 'seat 1 ends before assign 2')
    assert.ok(ends[1].seq < assigns[2].seq, 'seat 2 ends before assign 3')
    assert.equal(s.starts.length, 0)
    assert.ok(s.res.out.includes('idle budget exhausted'), tail(s))
    assert.equal(opsNamed(s, 'close')[0].reason, 'completed')
    assert.equal(writes(s).at(-1).op, 'close')
  })
})
