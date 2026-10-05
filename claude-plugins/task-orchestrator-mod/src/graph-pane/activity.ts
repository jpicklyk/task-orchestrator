// Who is working on which item, and which items changed a moment ago. Pure: index.ts owns the
// atoms and the hooks, and the render never reads the clock (prune-on-write plus timers expire entries).
import type { GraphActivity, GraphAgentInfo, GraphGateBlock, GraphWorker } from '../../types'
import { toToolName } from '../shared/constants.ts'

/** A worker stays on an item this long after its last call about it. */
export const WORKING_TTL_MS = 120_000
/** An item reads as recently changed this long after a TO write touched it. */
export const RECENT_MS = 30_000
/** A repeat call from the same worker inside this window writes nothing. */
export const ACTIVITY_REFRESH_MS = 10_000
/** Most workers kept per item, and most agents remembered. */
export const MAX_WORKERS = 8
export const MAX_AGENTS = 50

export const emptyActivity = (): GraphActivity => ({ working: {}, changed: {} })

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const text = (v: unknown): string | undefined => (typeof v === 'string' && v.length > 0 ? v : undefined)

/** An item id a TO call named, with the actor id on that element (or the call). */
export interface Touch {
  id: string
  actor?: string
}

const actorId = (v: unknown): string | undefined => (isObj(v) ? text(v.id) : undefined)

/**
 * The ids a TO tool call names: top-level `itemId`, and `itemId`/`id` of each element of
 * transitions, claims, notes and items. Unresolved (may be prefixes). Deduplicated, call order.
 */
export function touchedIds(input: unknown): Touch[] {
  if (!isObj(input)) return []
  const top = actorId(input.actor)
  const out = new Map<string, Touch>()
  const add = (id: string | undefined, actor: string | undefined): void => {
    if (id === undefined || out.has(id)) return
    out.set(id, actor !== undefined ? { id, actor } : { id })
  }
  add(text(input.itemId), top)
  for (const key of ['transitions', 'claims', 'notes', 'items']) {
    const list = input[key]
    if (!Array.isArray(list)) continue
    for (const el of list) {
      if (isObj(el)) add(text(el.itemId) ?? text(el.id), actorId(el.actor) ?? top)
    }
  }

  return [...out.values()]
}

/** Prefers the seat agent.spawn recorded, then the actor id's prefix before `:`, then `agent` / `main`. */
export function seatOf(agentId: string | undefined, agents: Record<string, GraphAgentInfo>, actor?: string): string {
  const known = agentId !== undefined ? agents[agentId]?.seat : undefined
  if (known !== undefined && known.length > 0) return known
  const prefix = actor !== undefined ? actor.split(':')[0] : undefined
  if (prefix !== undefined && prefix.length > 0) return prefix

  return agentId !== undefined ? 'agent' : 'main'
}

/** `claude-opus-5-5-20260101` -> `opus-5-5`; an alias such as `opus` stays as it is. */
export function shortModel(model: string | undefined): string | undefined {
  if (model === undefined || model.length === 0) return undefined
  const short = model.replace(/^claude-/, '').replace(/-\d{8}$/, '').replace(/\[.*\]$/, '')

  return short.length > 0 ? short : model
}

/** Resolves a call's id against the snapshot's ids: exact, or a prefix (>= 4 chars) that matches exactly one. */
export function resolveId(raw: string, known: readonly string[]): string | null {
  if (known.includes(raw)) return raw
  if (raw.length < 4) return null
  const hits = known.filter(id => id.startsWith(raw))

  return hits.length === 1 ? (hits[0] as string) : null
}

/** A gate-blocked advance_item marks its item this long (or until a later TO write on it). */
export const GATE_BLOCK_MS = 600_000

/** One gate-blocked transition of an advance_item result. */
export interface GateBlockRow {
  itemId: string
  targetRole?: string
  missing: string[]
}

/**
 * The gate-blocked rows of an advance_item result as the model reads it (`ran.text`, the JSON text):
 * rows with `applied: false` and `errorCode: 'gate_blocked'`. Any other tool, or text that is not
 * that JSON, gives []. `missingNotes` entries may be `{ key }` objects or plain strings.
 */
export function gateBlocks(tool: string, text: unknown): GateBlockRow[] {
  if (toToolName(tool) !== 'advance_item' || typeof text !== 'string') return []
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return []
  }
  const rows = isObj(parsed) && Array.isArray(parsed.results) ? parsed.results : []
  const out: GateBlockRow[] = []
  for (const row of rows) {
    if (!isObj(row) || row.applied !== false || row.errorCode !== 'gate_blocked') continue
    const itemId = typeof row.itemId === 'string' ? row.itemId : undefined
    if (itemId === undefined) continue
    const missing = Array.isArray(row.missingNotes)
      ? row.missingNotes.map(m => (typeof m === 'string' ? m : isObj(m) && typeof m.key === 'string' ? m.key : undefined)).filter((k): k is string => k !== undefined)
      : []
    const target = typeof row.targetRole === 'string' ? row.targetRole : undefined
    out.push({ itemId, missing, ...(target !== undefined ? { targetRole: target } : {}) })
  }

  return out
}

/** Drops workers silent for > WORKING_TTL_MS, changes older than RECENT_MS and gate blocks older than GATE_BLOCK_MS. Same object when nothing expired. */
export function pruneActivity(act: GraphActivity, now: number): GraphActivity {
  let dirty = false
  const working: Record<string, GraphWorker[]> = {}
  for (const [id, list] of Object.entries(act.working)) {
    const live = list.filter(w => now - w.at <= WORKING_TTL_MS)
    if (live.length !== list.length) dirty = true
    if (live.length > 0) working[id] = live
  }
  const changed: Record<string, number> = {}
  for (const [id, at] of Object.entries(act.changed)) {
    if (now - at <= RECENT_MS) changed[id] = at
    else dirty = true
  }
  // A value written before gate blocks existed has no `blocked`: tolerated, and left without one.
  const blocked: Record<string, GraphGateBlock> = {}
  for (const [id, b] of Object.entries(act.blocked ?? {})) {
    if (now - b.at <= GATE_BLOCK_MS) blocked[id] = b
    else dirty = true
  }

  return dirty ? withBlocked({ working, changed }, blocked) : act
}

const withBlocked = (act: GraphActivity, blocked: Record<string, GraphGateBlock>): GraphActivity =>
  Object.keys(blocked).length > 0 ? { ...act, blocked } : { working: act.working, changed: act.changed }

/**
 * Records this session's gate-blocked rows on their items (prunes first). Returns the SAME object when
 * every row is already recorded with the same keys and target under ACTIVITY_REFRESH_MS ago.
 */
export function recordGateBlocks(act: GraphActivity, rows: readonly { id: string; missing: string[]; target?: string }[], now: number): GraphActivity {
  const base = pruneActivity(act, now)
  const blocked = { ...(base.blocked ?? {}) }
  let dirty = false
  for (const r of rows) {
    const cur = blocked[r.id]
    if (cur !== undefined && cur.target === r.target && cur.missing.join('\u0000') === r.missing.join('\u0000') && now - cur.at < ACTIVITY_REFRESH_MS) continue
    blocked[r.id] = { at: now, missing: [...r.missing], ...(r.target !== undefined ? { target: r.target } : {}) }
    dirty = true
  }

  return dirty ? withBlocked(base, blocked) : base
}

/**
 * Records one call: the worker on the item and, when `changed`, the change time. Prunes first. Returns
 * the SAME object when the call adds nothing new (same worker, same seat and model, last seen under
 * ACTIVITY_REFRESH_MS ago), so the caller can skip the write.
 */
export function recordActivity(
  act: GraphActivity,
  entry: { id: string; agentId: string; seat: string; model?: string; changed: boolean },
  now: number,
): GraphActivity {
  const base = pruneActivity(act, now)
  const list = base.working[entry.id] ?? []
  const mine = list.find(w => w.agentId === entry.agentId)
  const workerStale = mine === undefined || mine.seat !== entry.seat || mine.model !== entry.model || now - mine.at >= ACTIVITY_REFRESH_MS
  const lastChange = base.changed[entry.id]
  const changeStale = entry.changed && (lastChange === undefined || now - lastChange >= ACTIVITY_REFRESH_MS)
  // A later TO write on a gate-blocked item clears its mark (the hook records a call's own blocks after this).
  const unblock = entry.changed && base.blocked?.[entry.id] !== undefined
  if (!workerStale && !changeStale && !unblock) return base

  const worker: GraphWorker = { agentId: entry.agentId, seat: entry.seat, at: now, ...(entry.model !== undefined ? { model: entry.model } : {}) }
  const next = workerStale ? [...list.filter(w => w.agentId !== entry.agentId), worker].slice(-MAX_WORKERS) : list
  const { [entry.id]: _cleared, ...restBlocked } = base.blocked ?? {}

  return withBlocked(
    {
      working: workerStale ? { ...base.working, [entry.id]: next } : base.working,
      changed: changeStale ? { ...base.changed, [entry.id]: now } : base.changed,
    },
    unblock ? restBlocked : (base.blocked ?? {}),
  )
}

/** Remembers a spawned agent, oldest dropped past MAX_AGENTS. Same object when unchanged. */
export function rememberAgent(agents: Record<string, GraphAgentInfo>, agentId: string, info: GraphAgentInfo): Record<string, GraphAgentInfo> {
  const cur = agents[agentId]
  if (cur !== undefined && cur.seat === info.seat && cur.model === info.model) return agents
  const { [agentId]: _drop, ...rest } = agents
  const entries = Object.entries({ ...rest, [agentId]: info })

  return Object.fromEntries(entries.slice(-MAX_AGENTS))
}
