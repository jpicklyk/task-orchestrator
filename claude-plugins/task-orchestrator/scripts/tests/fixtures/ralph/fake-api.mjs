// In-test REST stub for the ralph-loop.mjs fixture loop (cae5d141). A node:http server that answers the
// 4.0 response envelope on POST /api/v1/ops/{tool} for exactly the calls the ralph driver makes:
//   items_read select next | runs_read list | runs_write open/update/assign/end_seat/close |
//   advance claim/heartbeat/release.
//
// Oracle for every shape here: current/docs/api-reference.md "Response envelope" (write tools answer
// data:{results,summary}; a non-atomic batch with a failed element is ok:false partial_failure with the full
// data next to it, HTTP 207; a bad seat token is the call-level 401 unauthenticated with no detail), "Error
// catalog" (kind per code, detail shapes), runs_write / advance op references (token rules, assign, end_seat,
// close "completed is refused while a seat is active"), items_read select next ("empty with reason ...").
// It is NOT derived from the driver: the stub validates what a real server validates (hints are strings, a
// seat token is accepted only while its assignment is active, only the coordinator assigns/updates/closes).
//
// Everything the driver sent is kept in `trace` (flattened per op, in arrival order) with the seatToken it
// carried, so tests can assert which credential went with which call.
//
// fd1beaee fixture corrections (oracle: workflow-guide.md "Heartbeat Pattern" and "Effect of complete and cancel
// on the Claim", api-reference.md advance / runs_write / runs_read references and the Error catalog):
//   - a claim keeps its holder and expiry; a claim of a TERMINAL item is invalid_transition{fromRole: terminal}
//     (the old fake granted any claim, which hid the post-terminal usage report bug);
//   - heartbeat never takes an item: not_claim_holder unless the caller holds an unexpired claim; usage is recorded
//     per assignment (the highest reported value is kept) and summed into the run's usage;
//   - transition `complete` is the holder's move: it lands the item in terminal and clears the claim; a terminal
//     item answers invalid_transition{fromRole: terminal};
//   - runs_read get serves the run view (status, pauseReason, terminalReason, usage);
//   - a paused run refuses update/assign with run_paused; a closed run kills its coordinator and seat tokens, so
//     the next call under one is the call-level 401 unauthenticated (api-reference "Tokens");
//   - --items may carry full UUIDs while the queue answers 8-hex short ids: membership and item lookup match on a
//     dash-free hex prefix, like the real server (ItemView short ids).

import http from 'node:http'
import { randomBytes, randomUUID } from 'node:crypto'

// error-catalog kinds (api-reference.md "Error catalog"); every code not listed is permanent.
const KINDS = { claim_held: 'transient', resource_unavailable: 'transient', run_at_capacity: 'transient', internal: 'transient', unavailable: 'shedding' }
const kindOf = (code) => KINDS[code] ?? 'permanent'

const meta = () => ({ reqId: randomBytes(4).toString('hex').slice(0, 8) })

function callError(status, code, message, detail) {
  const error = { code, kind: kindOf(code), message }
  if (detail) error.detail = detail
  return [status, { ok: false, error, meta: meta() }]
}

export function elementError(code, detail, extra = {}) {
  const error = { code, kind: kindOf(code), message: `${code} (fake)`, ...extra }
  if (detail) error.detail = detail
  return { ok: false, error }
}

function batch(results) {
  const failed = results.filter((r) => !r.ok).length
  const data = { results, summary: { ok: results.length - failed, failed } }
  if (!failed) return [200, { ok: true, data, meta: meta() }]
  return [207, { ok: false, error: { code: 'partial_failure', kind: 'permanent', message: 'partial_failure (fake)' }, data, meta: meta() }]
}

function listData(items, reason) {
  const data = { items, page: { total: items.length, truncated: false } }
  if (reason) data.reason = reason
  return data
}

// opts:
//   items            [{id}] queue members in oldest-first order (short 8-hex ids, like the compact view)
//   claim(ctx)       -> undefined (grant) | {code, detail?, retryAfterMs?} (element error); ctx {itemId, nth, nthForToken}
//   update(ctx)      -> undefined (grant) | {code, detail?, retryAfterMs?} (element error); ctx {itemId, nth} (per addItems id); consulted before the duplicate check
//   assign(ctx)      -> undefined (grant) | {code, detail?} (element error) | {http401: true}; ctx {itemId, nth}
//   nextReason       reason string sent when the queue page is empty
//   completeOnDone   when true an item whose seat ends `done` leaves the queue
//   duplicateItems   Set of ids answered `duplicate` by update addItems
//   openError        {code, detail?} element error for open
//   transition(ctx)  -> undefined (grant) | {code, detail?, retryAfterMs?} (element error); ctx {itemId, nth, trigger}
//   heartbeat(ctx)   -> undefined (grant) | {code, detail?, retryAfterMs?} (element error); ctx {itemId, nth}
//   ttlUnitMs        milliseconds per claim ttl second (default 1000): a small value makes a claim lapse fast
//   pauseOn          {op, nth = 1, pauseReason = 'budget', decisionId} or an array of them: when the nth op of that
//                    name arrives the run pauses. assign is refused run_paused{runId, pauseReason, pauseDecisionId};
//                    heartbeat / transition are performed and their result carries paused:{decisionId}; claim is
//                    performed. A run_paused verdict from the assign hook pauses the run the same way.
//   resumeAfterPolls n: the n-th runs_read get answered while paused flips the run back to open
//   closeAfterPolls  n: the n-th runs_read get answered while paused closes the run (director, cancelled)
//   externalClose    {op, nth = 1, reason = 'cancelled'}: the nth op of that name finds the run already closed by a
//                    director (every token of the run is dead, so that call is the call-level 401)
//   runsGet(ctx)     -> undefined | {http, code} call-level error for runs_read get; ctx {nth}
export async function startFakeApi(opts = {}) {
  const st = {
    trace: [],
    hits: [],
    items: new Map((opts.items ?? []).map((i) => [i.id.toLowerCase(), { role: 'queue', ...i, id: i.id.toLowerCase() }])),
    run: null,
    seats: new Map(), // token -> {assignmentId, itemId, active, attempt, outcome}
    issuedSeatTokens: [],
    coordinatorToken: null,
    claimCounts: new Map(),
    updateCounts: new Map(),
    claimCountsByToken: new Map(),
    assignCount: 0,
    seq: 0,
    transitionCounts: new Map(),
    heartbeatCounts: new Map(),
    preCounts: new Map(),
    pauseFired: new Set(),
    pausedPolls: 0,
    getPolls: 0,
    externalCloseFired: false,
  }
  const ttlUnitMs = opts.ttlUnitMs ?? 1000
  const hex = (id) => String(id).toLowerCase().replace(/-/g, '')
  // a dash-free hex prefix matches in either direction (a short id names the same item as its full UUID)
  const sameId = (a, b) => {
    const x = hex(a)
    const y = hex(b)
    return x.startsWith(y) || y.startsWith(x)
  }
  const resolveItem = (id) => [...st.items.values()].find((i) => sameId(i.id, id))
  const isMember = (id) => st.run && [...st.run.members].some((m) => sameId(m, id))
  const claimLive = (item) => item && item.claim && item.claim.expiresAtMs > Date.now()

  const who = (tok) => {
    if (tok === undefined) return { kind: 'none' }
    if (st.run && st.run.status !== 'closed' && tok === st.coordinatorToken) return { kind: 'coordinator' }
    const seat = st.seats.get(tok)
    if (seat && seat.active) return { kind: 'seat', seat, token: tok }
    return { kind: 'invalid' }
  }
  const norm = (id) => String(id).toLowerCase()
  const activeSeats = () => [...st.seats.values()].filter((s) => s.active)

  function view() {
    const r = st.run
    const seats = [...st.seats.values()]
    const usage = {
      reportedTokens: seats.reduce((n, x) => n + (x.usage?.tokens ?? 0), 0),
      reportedCostUsd: seats.reduce((n, x) => n + (x.usage?.costUsd ?? 0), 0),
      observedTokens: 0,
    }
    const v = { runId: r.runId, rootId: r.rootId, label: r.label, title: r.title, status: r.status, budget: r.budget, hints: r.hints, usage, members: r.members.size, activeSeats: activeSeats().length, version: 1 }
    if (r.status === 'paused') v.pauseReason = r.pauseReason
    if (r.status === 'closed') {
      v.terminalReason = r.terminalReason
      v.closedBy = r.closedBy
    }
    return v
  }

  function closeRun(reason, by) {
    const active = activeSeats()
    for (const s of active) {
      s.active = false
      s.outcome = 'ended-by-close'
      releaseClaims(s.token)
    }
    st.run.status = 'closed'
    st.run.terminalReason = reason
    st.run.closedBy = by
    return active.length
  }

  function releaseClaims(token) {
    const released = []
    for (const it of st.items.values()) {
      if (it.claim && it.claim.holder === token) {
        it.claim = null
        released.push(it.id)
      }
    }
    return released
  }

  function pauseRun(spec) {
    st.run.status = 'paused'
    st.run.pauseReason = spec.pauseReason ?? 'budget'
    st.run.pauseDecisionId = spec.decisionId ?? spec.pauseDecisionId
    st.pausedPolls = 0
  }

  // pauseOn entries fire once, on the nth op of their name; returns the spec that fired for this op (or undefined)
  function pauseFor(opName, n) {
    const list = [].concat(opts.pauseOn ?? [])
    for (let i = 0; i < list.length; i++) {
      const spec = list[i]
      if (spec.op === opName && (spec.nth ?? 1) === n && !st.pauseFired.has(i)) {
        st.pauseFired.add(i)
        return { pauseReason: 'budget', decisionId: 'dddd4444-0000-4000-8000-000000000001', ...spec }
      }
    }
    return undefined
  }

  const pausedError = () => elementError('run_paused', { runId: st.run.runId, pauseReason: st.run.pauseReason, pauseDecisionId: st.run.pauseDecisionId })

  function doOp(tool, op, caller, idx) {
    switch (`${tool}.${op.op}`) {
      case 'runs_write.open': {
        if (opts.openError) return elementError(opts.openError.code, opts.openError.detail)
        const fields = []
        if (typeof op.rootId !== 'string' || !op.rootId) fields.push({ field: `/ops/${idx}/rootId`, reason: 'required' })
        if (op.label !== undefined && String(op.label).length > 64) fields.push({ field: `/ops/${idx}/label`, reason: 'at most 64 characters' })
        if (op.title !== undefined && String(op.title).length > 200) fields.push({ field: `/ops/${idx}/title`, reason: 'at most 200 characters' })
        for (const [k, v] of Object.entries(op.hints ?? {})) {
          if (typeof v !== 'string') fields.push({ field: `/ops/${idx}/hints/${k}`, reason: 'hints is a map of string to string', received: typeof v })
        }
        if (op.items !== undefined && !(Array.isArray(op.items) && op.items.every((x) => typeof x === 'string'))) {
          fields.push({ field: `/ops/${idx}/items`, reason: 'array of string' })
        }
        if (fields.length) return elementError('invalid_request', { fields })
        st.coordinatorToken = `coord-${randomBytes(18).toString('hex')}`
        st.run = {
          runId: randomUUID(),
          rootId: op.rootId,
          status: 'open',
          label: op.label,
          title: op.title,
          hints: op.hints ?? {},
          budget: op.budget,
          members: new Set((op.items ?? []).map(norm)),
        }
        return { ok: true, ...view(), coordinatorToken: st.coordinatorToken }
      }
      case 'runs_write.update': {
        if (caller.kind !== 'coordinator') return elementError('forbidden', { scope: 'principal_kind', required: 'coordinator' })
        if (st.run.status === 'paused') return pausedError()
        for (const id of op.addItems ?? []) {
          const nthUpdate = (st.updateCounts.get(norm(id)) ?? 0) + 1
          st.updateCounts.set(norm(id), nthUpdate)
          const verdict = opts.update?.({ itemId: norm(id), nth: nthUpdate })
          if (verdict) {
            const extra = verdict.retryAfterMs !== undefined ? { retryAfterMs: verdict.retryAfterMs } : {}
            return elementError(verdict.code, verdict.detail, extra)
          }
          if (opts.duplicateItems?.has(norm(id))) return elementError('duplicate', { kind: 'item', id })
          st.run.members.add(norm(id))
        }
        return { ok: true, ...view() }
      }
      case 'runs_write.assign': {
        if (caller.kind !== 'coordinator') return elementError('forbidden', { scope: 'principal_kind', required: 'coordinator' })
        if (st.run.status === 'closed') return elementError('run_closed', { runId: st.run.runId, terminalReason: 'completed' })
        const n = ++st.assignCount
        if (st.run.status === 'paused') return pausedError()
        const pause = pauseFor('assign', n)
        if (pause) {
          pauseRun(pause)
          return pausedError()
        }
        const verdict = opts.assign?.({ itemId: norm(op.itemId), nth: n })
        if (verdict?.http401) return { http401: true }
        if (verdict?.code === 'run_paused') {
          pauseRun({ pauseReason: verdict.detail?.pauseReason, decisionId: verdict.detail?.pauseDecisionId })
          return pausedError()
        }
        if (verdict) return elementError(verdict.code, verdict.detail ?? { runId: st.run.runId })
        if (!isMember(op.itemId)) return elementError('invalid_request', { fields: [{ field: `/ops/${idx}/itemId`, reason: 'not a member of the run' }] })
        if (typeof op.seat !== 'string' || !op.seat) return elementError('invalid_request', { fields: [{ field: `/ops/${idx}/seat`, reason: 'required' }] })
        const token = `seat-${randomBytes(18).toString('hex')}`
        const attempt = 1 + [...st.seats.values()].filter((s) => s.itemId === norm(op.itemId)).length
        st.seats.set(token, { assignmentId: randomUUID(), itemId: norm(op.itemId), seat: op.seat, active: true, attempt, outcome: null, token })
        st.issuedSeatTokens.push(token)
        return { ok: true, assignmentId: st.seats.get(token).assignmentId, seatToken: token, attempt }
      }
      case 'runs_write.end_seat': {
        if (caller.kind !== 'seat') return elementError('forbidden', { scope: 'principal_kind', required: 'seat' })
        caller.seat.active = false
        caller.seat.outcome = op.outcome
        const it = resolveItem(caller.seat.itemId)
        if (opts.completeOnDone && op.outcome === 'done' && it) {
          it.role = 'terminal'
          it.claim = null
        }
        const released = releaseClaims(caller.token)
        return { ok: true, assignmentId: caller.seat.assignmentId, itemId: caller.seat.itemId, seat: caller.seat.seat, attempt: caller.seat.attempt, outcome: op.outcome, released }
      }
      case 'runs_write.close': {
        if (caller.kind !== 'coordinator') return elementError('forbidden', { scope: 'principal_kind', required: 'coordinator' })
        if (st.run.status === 'closed') return elementError('run_closed', { runId: st.run.runId, terminalReason: 'completed' })
        const active = activeSeats()
        if (op.reason === 'completed' && active.length) return elementError('invalid_run_state', { runId: st.run.runId, status: 'open', allowed: ['cancelled', 'failed', 'abandoned'] })
        closeRun(op.reason, 'coordinator')
        return { ok: true, ...view(), snapshot: { reason: op.reason, released: active.length } }
      }
      case 'advance.claim': {
        if (caller.kind !== 'seat') return elementError('forbidden', { scope: 'principal_kind', required: 'seat' })
        const itemId = norm(op.itemId)
        const item = resolveItem(op.itemId)
        const nth = (st.claimCounts.get(itemId) ?? 0) + 1
        st.claimCounts.set(itemId, nth)
        const nthForToken = (st.claimCountsByToken.get(caller.token) ?? 0) + 1
        st.claimCountsByToken.set(caller.token, nthForToken)
        const verdict = opts.claim?.({ itemId, nth, nthForToken })
        if (verdict) {
          const detail = verdict.detail ?? { itemId, expiresAt: new Date(Date.now() + (verdict.retryAfterMs ?? 0)).toISOString(), retryAfterMs: verdict.retryAfterMs }
          const extra = verdict.retryAfterMs !== undefined ? { retryAfterMs: verdict.retryAfterMs } : {}
          return elementError(verdict.code, detail, extra)
        }
        // api-reference "claim": claiming a terminal item is invalid_transition (nothing allowed)
        if (item && item.role === 'terminal') return elementError('invalid_transition', { itemId, fromRole: 'terminal', trigger: 'claim', allowed: [] })
        if (claimLive(item) && item.claim.holder !== caller.token) {
          const wait = item.claim.expiresAtMs - Date.now()
          return elementError('claim_held', { itemId, expiresAt: new Date(item.claim.expiresAtMs).toISOString(), retryAfterMs: wait }, { retryAfterMs: wait })
        }
        const now = new Date()
        const expiresAtMs = now.getTime() + (op.ttlSeconds ?? 900) * ttlUnitMs
        if (item) item.claim = { holder: caller.token, expiresAtMs, claimedAtMs: now.getTime() }
        const pause = pauseFor('claim', nth)
        if (pause) pauseRun(pause)
        return { ok: true, itemId, claimed: true, version: 1, claimedAt: now.toISOString(), expiresAt: new Date(expiresAtMs).toISOString(), originalClaimedAt: now.toISOString() }
      }
      case 'advance.heartbeat': {
        if (caller.kind !== 'seat') return elementError('forbidden', { scope: 'principal_kind', required: 'seat' })
        const itemId = norm(op.itemId)
        const item = resolveItem(op.itemId)
        const nth = (st.heartbeatCounts.get(itemId) ?? 0) + 1
        st.heartbeatCounts.set(itemId, nth)
        const verdict = opts.heartbeat?.({ itemId, nth })
        if (verdict) {
          const extra = verdict.retryAfterMs !== undefined ? { retryAfterMs: verdict.retryAfterMs } : {}
          return elementError(verdict.code, verdict.detail, extra)
        }
        // workflow-guide "Heartbeat Pattern": a heartbeat never takes an item - not_claim_holder unless the caller holds the live claim
        if (!claimLive(item) || item.claim.holder !== caller.token) return elementError('not_claim_holder', { itemId })
        if (op.usage !== undefined) {
          const u = op.usage
          const ok = u && typeof u === 'object' && ['tokens', 'costUsd'].every((k) => u[k] === undefined || (typeof u[k] === 'number' && u[k] >= 0))
          if (!ok) return elementError('invalid_request', { fields: [{ field: `/ops/${idx}/usage`, reason: '{tokens, costUsd} of non-negative numbers' }] })
          const prev = caller.seat.usage ?? { tokens: 0, costUsd: 0 }
          caller.seat.usage = { tokens: Math.max(prev.tokens, u.tokens ?? 0), costUsd: Math.max(prev.costUsd, u.costUsd ?? 0) }
        }
        const now = new Date()
        const expiresAtMs = now.getTime() + (op.ttlSeconds ?? 900) * ttlUnitMs
        item.claim.expiresAtMs = expiresAtMs
        const claimedAt = new Date(item.claim.claimedAtMs).toISOString()
        const out = { ok: true, itemId, claimed: true, version: 1, claimedAt, expiresAt: new Date(expiresAtMs).toISOString(), originalClaimedAt: claimedAt, refreshed: true }
        const pause = pauseFor('heartbeat', nth)
        if (pause) {
          pauseRun(pause)
          out.paused = { decisionId: pause.decisionId }
        }
        return out
      }
      case 'advance.transition': {
        if (caller.kind !== 'seat') return elementError('forbidden', { scope: 'principal_kind', required: 'seat' })
        const itemId = norm(op.itemId)
        const item = resolveItem(op.itemId)
        if (!item) return elementError('not_found', { kind: 'item', id: op.itemId })
        const nth = (st.transitionCounts.get(itemId) ?? 0) + 1
        st.transitionCounts.set(itemId, nth)
        const verdict = opts.transition?.({ itemId, nth, trigger: op.trigger })
        if (verdict) {
          const extra = verdict.retryAfterMs !== undefined ? { retryAfterMs: verdict.retryAfterMs } : {}
          return elementError(verdict.code, verdict.detail, extra)
        }
        if (item.role === 'terminal') return elementError('invalid_transition', { itemId, fromRole: 'terminal', trigger: op.trigger, allowed: [] })
        if (!claimLive(item) || item.claim.holder !== caller.token) return elementError('not_claim_holder', { itemId })
        const previousRole = item.role
        if (op.trigger === 'complete') {
          // workflow-guide "Effect of complete and cancel on the Claim": the terminal transition clears the claim
          item.role = 'terminal'
          item.claim = null
        }
        const out = { ok: true, itemId, previousRole, newRole: item.role, trigger: op.trigger }
        const pause = pauseFor('transition', nth)
        if (pause) {
          pauseRun(pause)
          out.paused = { decisionId: pause.decisionId }
        }
        return out
      }
      case 'advance.release': {
        const item = resolveItem(op.itemId)
        if (item && item.claim && caller.kind === 'seat' && item.claim.holder === caller.token) item.claim = null
        return { ok: true, itemId: norm(op.itemId), released: true }
      }
      default:
        return elementError('invalid_request', { fields: [{ field: `/ops/${idx}/op`, reason: `fake does not serve ${tool}.${op.op}` }] })
    }
  }

  function handle(tool, body) {
    if ((tool === 'runs_write' || tool === 'advance') && opts.externalClose && !st.externalCloseFired && st.run && st.run.status !== 'closed') {
      for (const op of Array.isArray(body.ops) ? body.ops : []) {
        const n = (st.preCounts.get(op.op) ?? 0) + 1
        st.preCounts.set(op.op, n)
        if (op.op === opts.externalClose.op && n === (opts.externalClose.nth ?? 1)) {
          st.externalCloseFired = true
          closeRun(opts.externalClose.reason ?? 'cancelled', 'director')
        }
      }
    }
    const caller = who(body.seatToken)
    if (caller.kind === 'invalid') return callError(401, 'unauthenticated', 'The seat token is not accepted.')
    if (tool === 'items_read') {
      st.trace.push({ seq: ++st.seq, ts: Date.now(), tool, op: body.select, seatToken: body.seatToken, body })
      if (body.select === 'next') {
        const queue = [...st.items.values()].filter((i) => i.role === 'queue')
        const page = queue.slice(0, Math.min(body.limit ?? 1, 200)).map((i) => ({ id: i.id, type: 'feature-task', title: i.title ?? i.id, role: 'queue', depth: 2, version: 1 }))
        const reason = !page.length && opts.nextReason ? opts.nextReason : undefined
        return [200, { ok: true, data: listData(page, reason), meta: meta() }]
      }
      return callError(400, 'invalid_request', `fake does not serve items_read ${body.select}`)
    }
    if (tool === 'runs_read') {
      st.trace.push({ seq: ++st.seq, ts: Date.now(), tool, op: body.select, seatToken: body.seatToken, body })
      if (body.select === 'list') return [200, { ok: true, data: listData([]), meta: meta() }]
      if (body.select === 'get') {
        const nth = ++st.getPolls
        const verdict = opts.runsGet?.({ nth })
        if (verdict) return callError(verdict.http ?? 503, verdict.code, `${verdict.code} (fake)`)
        if (!st.run || !body.runId || !sameId(st.run.runId, body.runId)) return callError(404, 'not_found', 'no such run', { kind: 'run' })
        if (st.run.status === 'paused') {
          st.pausedPolls += 1
          if (opts.resumeAfterPolls && st.pausedPolls >= opts.resumeAfterPolls) st.run.status = 'open'
          else if (opts.closeAfterPolls && st.pausedPolls >= opts.closeAfterPolls) closeRun('cancelled', 'director')
        }
        return [200, { ok: true, data: view(), meta: meta() }]
      }
      return callError(400, 'invalid_request', `fake does not serve runs_read ${body.select}`)
    }
    if (tool === 'runs_write' || tool === 'advance') {
      const ops = Array.isArray(body.ops) ? body.ops : []
      const results = []
      let unauth = false
      ops.forEach((op, idx) => {
        st.trace.push({ seq: ++st.seq, ts: Date.now(), tool, op: op.op, seatToken: body.seatToken, ...op, opIndex: idx, opsInCall: ops.length })
        const r = doOp(tool, op, caller, idx)
        if (r.http401) unauth = true
        else results.push(r)
      })
      if (unauth) return callError(401, 'unauthenticated', 'The seat token is not accepted.')
      return batch(results)
    }
    return callError(400, 'invalid_request', `unknown tool ${tool}`)
  }

  const server = http.createServer((req, res) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8')
      const m = /^[/]api[/]v1[/]ops[/]([a-z_]+)$/.exec(req.url ?? '')
      let body = {}
      try {
        body = JSON.parse(raw || '{}')
      } catch {
        // an unparseable body is answered below as an empty request
      }
      st.hits.push({ method: req.method, url: req.url, headers: req.headers, raw, ts: Date.now() })
      let out
      if (req.method !== 'POST' || !m) out = callError(404, 'not_found', 'no such route', { kind: 'root' })
      else out = handle(m[1], body)
      res.writeHead(out[0], { 'Content-Type': 'application/json', 'X-Req-Id': out[1].meta ? out[1].meta.reqId : '' })
      res.end(JSON.stringify(out[1]))
    })
  })
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${server.address().port}`
  return {
    base,
    state: st,
    get trace() {
      return st.trace
    },
    get hits() {
      return st.hits
    },
    get coordinatorToken() {
      return st.coordinatorToken
    },
    get seatTokens() {
      return st.issuedSeatTokens
    },
    get seats() {
      return [...st.seats.values()]
    },
    get run() {
      return st.run
    },
    runView() {
      return view()
    },
    item(id) {
      return resolveItem(id)
    },
    stop() {
      server.closeAllConnections?.()
      return new Promise((r) => server.close(r))
    },
  }
}
