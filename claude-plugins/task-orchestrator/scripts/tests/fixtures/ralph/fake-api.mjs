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
  }

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
    return { runId: r.runId, rootId: r.rootId, label: r.label, title: r.title, status: r.status, budget: r.budget, hints: r.hints, members: r.members.size, activeSeats: activeSeats().length, version: 1 }
  }

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
        const verdict = opts.assign?.({ itemId: norm(op.itemId), nth: n })
        if (verdict?.http401) return { http401: true }
        if (verdict) return elementError(verdict.code, verdict.detail ?? { runId: st.run.runId })
        if (!st.run.members.has(norm(op.itemId))) return elementError('invalid_request', { fields: [{ field: `/ops/${idx}/itemId`, reason: 'not a member of the run' }] })
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
        const it = st.items.get(caller.seat.itemId)
        if (opts.completeOnDone && op.outcome === 'done' && it) it.role = 'terminal'
        return { ok: true, assignmentId: caller.seat.assignmentId, itemId: caller.seat.itemId, seat: caller.seat.seat, attempt: caller.seat.attempt, outcome: op.outcome, released: [] }
      }
      case 'runs_write.close': {
        if (caller.kind !== 'coordinator') return elementError('forbidden', { scope: 'principal_kind', required: 'coordinator' })
        if (st.run.status === 'closed') return elementError('run_closed', { runId: st.run.runId, terminalReason: 'completed' })
        const active = activeSeats()
        if (op.reason === 'completed' && active.length) return elementError('invalid_run_state', { runId: st.run.runId, status: 'open', allowed: ['cancelled', 'failed', 'abandoned'] })
        for (const s of active) {
          s.active = false
          s.outcome = 'ended-by-close'
        }
        st.run.status = 'closed'
        st.run.terminalReason = op.reason
        return { ok: true, ...view(), snapshot: { reason: op.reason, released: active.length } }
      }
      case 'advance.claim': {
        if (caller.kind !== 'seat') return elementError('forbidden', { scope: 'principal_kind', required: 'seat' })
        const itemId = norm(op.itemId)
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
        const now = new Date()
        return { ok: true, itemId, claimed: true, version: 1, claimedAt: now.toISOString(), expiresAt: new Date(now.getTime() + (op.ttlSeconds ?? 900) * 1000).toISOString(), originalClaimedAt: now.toISOString() }
      }
      case 'advance.heartbeat': {
        if (caller.kind !== 'seat') return elementError('forbidden', { scope: 'principal_kind', required: 'seat' })
        const now = new Date()
        return { ok: true, itemId: norm(op.itemId), claimed: true, version: 1, claimedAt: now.toISOString(), expiresAt: new Date(now.getTime() + 900000).toISOString(), originalClaimedAt: now.toISOString(), refreshed: true }
      }
      case 'advance.release':
        return { ok: true, itemId: norm(op.itemId), released: true }
      default:
        return elementError('invalid_request', { fields: [{ field: `/ops/${idx}/op`, reason: `fake does not serve ${tool}.${op.op}` }] })
    }
  }

  function handle(tool, body) {
    const caller = who(body.seatToken)
    if (caller.kind === 'invalid') return callError(401, 'unauthenticated', 'The seat token is not accepted.')
    if (tool === 'items_read') {
      st.trace.push({ seq: ++st.seq, tool, op: body.select, seatToken: body.seatToken, body })
      if (body.select === 'next') {
        const queue = [...st.items.values()].filter((i) => i.role === 'queue')
        const page = queue.slice(0, Math.min(body.limit ?? 1, 200)).map((i) => ({ id: i.id, type: 'feature-task', title: i.title ?? i.id, role: 'queue', depth: 2, version: 1 }))
        const reason = !page.length && opts.nextReason ? opts.nextReason : undefined
        return [200, { ok: true, data: listData(page, reason), meta: meta() }]
      }
      return callError(400, 'invalid_request', `fake does not serve items_read ${body.select}`)
    }
    if (tool === 'runs_read') {
      st.trace.push({ seq: ++st.seq, tool, op: body.select, seatToken: body.seatToken, body })
      if (body.select === 'list') return [200, { ok: true, data: listData([]), meta: meta() }]
      return callError(400, 'invalid_request', `fake does not serve runs_read ${body.select}`)
    }
    if (tool === 'runs_write' || tool === 'advance') {
      const ops = Array.isArray(body.ops) ? body.ops : []
      const results = []
      let unauth = false
      ops.forEach((op, idx) => {
        st.trace.push({ seq: ++st.seq, tool, op: op.op, seatToken: body.seatToken, ...op, opIndex: idx, opsInCall: ops.length })
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
    stop() {
      server.closeAllConnections?.()
      return new Promise((r) => server.close(r))
    },
  }
}
