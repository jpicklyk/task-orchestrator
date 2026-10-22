// Builds a graph snapshot of a work-item subtree with read-only TO calls (items_read, deps_read). Never
// throws: a failed call is recorded in `error` (`<tool>: <code>: <message>`) and the snapshot comes back
// partial.
//
// Every id in a snapshot is the item's 8-hex short id (D1): compact views (list, overview, ancestors, the
// context item) only ever carry short ids, while deps_read, history and get carry full UUIDs, so each id is
// normalized with shortId before it is used as a key. First-8 collisions are accepted (random v4 ids).
import type { GateInfo, GraphEdge, GraphNode, GraphSnapshot, SeatProgress } from '../../types'
import { readSection, scalar } from '../lib/yaml-lite.mjs'
import { CONFIG_PATH } from '../shared/config.ts'
import { shortId } from '../shared/to-client.ts'
import type { GraphIo } from './io.ts'
import { fetchLabels } from './labels.ts'

/** Most nodes a snapshot keeps; past it the shallowest win and `truncated` is set. */
export const NODE_CAP = 150
const PAGE_SIZE = 100
/** Never page past this many items, whatever `total` claims. */
const FETCH_CEILING = 1000
const LANES = 6
/** Direct children of the project root the overview asks for in its one call. */
const OVERVIEW_LIMIT = 100
/** Edges asked for per deps_read page. */
const DEPS_PAGE = 200
/** A work/review item missing required notes with no role transition for this long reads as stalled. */
export const STALL_MS = 30 * 60_000
/** Most rows one health-context or history read returns; a full history page may be cut, so it reports no stalls. */
export const TRANSITION_LIMIT = 200

type Obj = Record<string, unknown>

const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null
const str = (v: unknown): string | undefined => (typeof v === 'string' && v.length > 0 ? v : undefined)
const sid = (v: unknown): string | undefined => {
  const s = str(v)

  return s === undefined ? undefined : shortId(s)
}
const message = (err: unknown): string => (err instanceof Error ? err.message : String(err))
const itemsOf = (data: unknown): unknown[] => (isObj(data) && Array.isArray(data.items) ? data.items : [])
const pageOf = (data: unknown): Obj => (isObj(data) && isObj(data.page) ? data.page : {})

/** Runs `fn` over `items` with at most `limit` in flight. `fn` must not reject. */
async function mapLimit<T>(items: readonly T[], limit: number, fn: (item: T) => Promise<void>): Promise<void> {
  let next = 0
  const lane = async (): Promise<void> => {
    while (next < items.length) await fn(items[next++] as T)
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, lane))
}

/** `project.rootId` from the project config (as written there), or null when there is none. */
export async function readRootId(io: GraphIo): Promise<string | null> {
  try {
    const cfg = await io.readFile(CONFIG_PATH)
    const section = readSection(cfg, 'project', { blockOnly: true })
    const rootId = section ? scalar(section.lines, 'rootId') : null

    return typeof rootId === 'string' && rootId.length > 0 ? rootId : null
  } catch {
    return null
  }
}

function toNode(raw: unknown): GraphNode | null {
  if (!isObj(raw)) return null
  const id = sid(raw.id)
  if (id === undefined) return null
  const node: GraphNode = {
    id,
    parentId: sid(raw.parentId) ?? null,
    title: typeof raw.title === 'string' ? raw.title : id,
    role: str(raw.role) ?? 'queue',
    depth: typeof raw.depth === 'number' ? raw.depth : 0,
  }
  const statusLabel = str(raw.statusLabel)
  if (statusLabel !== undefined) node.statusLabel = statusLabel
  const type = str(raw.type)
  if (type !== undefined) node.type = type
  const priority = str(raw.priority)
  if (priority !== undefined) node.priority = priority
  if (isObj(raw.childCounts)) {
    const counts: Record<string, number> = {}
    for (const [role, n] of Object.entries(raw.childCounts)) if (typeof n === 'number' && n > 0) counts[role] = n
    node.childCounts = counts
  }

  return node
}

/** The inclusive subtree of `scope`, paged by cursor until `page.next` is absent or FETCH_CEILING items; `total` is `page.total`. */
async function fetchSubtree(io: GraphIo, scope: string, errors: string[]): Promise<{ items: GraphNode[]; total: number }> {
  const items: GraphNode[] = []
  let total: number | undefined
  let cursor: string | undefined
  while (items.length < FETCH_CEILING) {
    let data: unknown
    try {
      data = await io.callTool('items_read', { select: 'list', ancestorId: scope, limit: PAGE_SIZE, ...(cursor !== undefined ? { cursor } : {}) })
    } catch (err) {
      errors.push(message(err))
      break
    }
    for (const row of itemsOf(data)) {
      const node = toNode(row)
      if (node) items.push(node)
    }
    const page = pageOf(data)
    if (typeof page.total === 'number') total = page.total
    cursor = str(page.next)
    if (cursor === undefined) break
  }

  return { items, total: total ?? items.length }
}

/** Keeps the NODE_CAP shallowest nodes, in the order the server returned them. */
function capNodes(items: GraphNode[]): { nodes: GraphNode[]; truncated: boolean } {
  if (items.length <= NODE_CAP) return { nodes: items, truncated: false }
  const keep = new Set(
    items
      .map((node, index) => ({ node, index }))
      .sort((a, b) => a.node.depth - b.node.depth || a.index - b.index)
      .slice(0, NODE_CAP)
      .map(entry => entry.node.id),
  )

  return { nodes: items.filter(node => keep.has(node.id)), truncated: true }
}

/**
 * Folds one deps_read list page into `edges` (normalized so `from` blocks `to`), keeping only edges with
 * at least one end in `ids`; ends outside `ids` are collected in `externalIds`.
 */
function addEdges(rows: readonly unknown[], ids: ReadonlySet<string>, edges: Map<string, GraphEdge>, seenDeps: Set<string>, externalIds: Set<string>): void {
  for (const dep of rows) {
    if (!isObj(dep)) continue
    const depId = str(dep.id)
    if (depId !== undefined) {
      if (seenDeps.has(depId)) continue
      seenDeps.add(depId)
    }
    const from = sid(dep.from)
    const to = sid(dep.to)
    if (from === undefined || to === undefined) continue
    if (!ids.has(from) && !ids.has(to)) continue
    for (const end of [from, to]) if (!ids.has(end)) externalIds.add(end)

    const edge: GraphEdge = { from, to, type: str(dep.type) === 'relates_to' ? 'RELATES_TO' : 'BLOCKS' }
    const unblockAt = str(dep.unblockAt)
    if (unblockAt !== undefined) edge.unblockAt = unblockAt

    const key = edge.type === 'RELATES_TO' ? `RELATES_TO|${[edge.from, edge.to].sort().join('|')}` : `BLOCKS|${edge.from}|${edge.to}`
    if (!edges.has(key)) edges.set(key, edge)
  }
}

/** Every edge touching `id`, following `page.next`. A failure ends the walk and is recorded. */
async function fetchEdges(io: GraphIo, id: string, errors: string[]): Promise<unknown[]> {
  const rows: unknown[] = []
  let cursor: string | undefined
  for (let pages = 0; pages < FETCH_CEILING / DEPS_PAGE; pages++) {
    let data: unknown
    try {
      data = await io.callTool('deps_read', { select: 'list', item: id, direction: 'all', limit: DEPS_PAGE, ...(cursor !== undefined ? { cursor } : {}) })
    } catch (err) {
      errors.push(message(err))
      break
    }
    rows.push(...itemsOf(data))
    cursor = str(pageOf(data).next)
    if (cursor === undefined) break
  }

  return rows
}

function toGate(raw: unknown, nodeRole: string): GateInfo | null {
  if (!isObj(raw) || !isObj(raw.gateStatus)) return null
  const gate = raw.gateStatus
  const phase = str(gate.phase) ?? nodeRole
  const rows = Array.isArray(raw.schema) ? raw.schema.filter(isObj) : []
  const current = rows.filter(row => row.role === phase && row.required === true)
  const missing = Array.isArray(gate.missing)
    ? gate.missing.map(m => (typeof m === 'string' ? m : isObj(m) && typeof m.key === 'string' ? m.key : JSON.stringify(m)))
    : []

  const out: GateInfo = {
    canAdvance: gate.canAdvance === true,
    phase,
    missing,
    required: current.length,
    filled: current.filter(row => row.filled === true).length,
  }
  const seats = seatsOf(current)
  if (seats !== null) out.seats = seats

  return out
}

/** Required current-phase rows grouped by seat in schema order (no seat: `other`); null when no row names a seat. */
function seatsOf(current: Obj[]): SeatProgress[] | null {
  if (!current.some(row => typeof row.seat === 'string')) return null
  const bySeat = new Map<string, SeatProgress>()
  for (const row of current) {
    const seat = typeof row.seat === 'string' && row.seat !== '' ? row.seat : 'other'
    const entry = bySeat.get(seat) ?? { seat, required: 0, filled: 0 }
    entry.required += 1
    if (row.filled === true) entry.filled += 1
    bySeat.set(seat, entry)
  }

  return [...bySeat.values()]
}

/**
 * The cheap project-root snapshot: ONE `items_read` `overview` call anchored on the root. The anchor's direct
 * children come back with full-subtree role counts; there are no dependency, label or gate reads.
 */
async function overviewSnapshot(io: GraphIo, result: GraphSnapshot, rootId: string): Promise<GraphSnapshot> {
  const out: GraphSnapshot = { ...result, overview: true }
  const root = shortId(rootId)
  try {
    const data = await io.callTool('items_read', { select: 'overview', anchorId: rootId, limit: OVERVIEW_LIMIT })
    const anchor = isObj(data) && isObj(data.anchor) ? data.anchor : {}
    const children: GraphNode[] = []
    for (const row of itemsOf(data)) {
      const child = toNode(row)
      if (child) children.push({ ...child, parentId: root, depth: 1 })
    }
    const title = typeof anchor.title === 'string' ? anchor.title : root
    // The overview does not report the anchor's own role; a project anchor is drawn as in progress.
    out.nodes = [{ id: root, parentId: null, title, role: 'work', depth: 0 }, ...children]
    out.truncated = pageOf(data).truncated === true
    out.trail = [{ id: root, title }]
  } catch (err) {
    out.error = message(err)
  }

  return out
}

/**
 * Stalls from ONE health-mode `context` read (`data.stalled[]`: `{id, role, missing[]}`) and ONE `history`
 * read since now - STALL_MS (`data.items[]`, newest first: `{itemId, occurredAt}`): items of `ids` (short
 * ids) in work or review that the context lists as missing required notes with no transition since. A full
 * page of history may be cut, so it gives no stalls. `dueAt`: when the earliest listed item that did move
 * recently would cross STALL_MS. A move already STALL_MS old counts as crossed (stalled), so the re-snapshot
 * timer never re-arms on an old transition. Bad input gives no stalls.
 */
export function stallsOf(context: unknown, history: unknown, ids: ReadonlySet<string>, now: number): { stalled: Record<string, string[]>; dueAt?: number } {
  const listed = isObj(context) && Array.isArray(context.stalled) ? context.stalled.filter(isObj) : []
  const moves = itemsOf(history).filter(isObj)
  if (moves.length >= TRANSITION_LIMIT) return { stalled: {} }
  const lastMove = new Map<string, number>()
  for (const t of moves) {
    const id = sid(t.itemId)
    const at = typeof t.occurredAt === 'number' ? t.occurredAt : typeof t.occurredAt === 'string' ? Date.parse(t.occurredAt) : NaN
    if (id === undefined) continue
    lastMove.set(id, Math.max(lastMove.get(id) ?? -Infinity, Number.isFinite(at) ? at : -Infinity))
  }
  const stalled: Record<string, string[]> = {}
  let dueAt: number | undefined
  for (const item of listed) {
    const id = sid(item.id)
    if (id === undefined || !ids.has(id) || (item.role !== 'work' && item.role !== 'review')) continue
    const moved = lastMove.get(id)
    if (moved === undefined || (Number.isFinite(moved) && moved + STALL_MS <= now)) {
      stalled[id] = Array.isArray(item.missing)
        ? item.missing.flatMap(k => (typeof k === 'string' ? [k] : isObj(k) && typeof k.key === 'string' ? [k.key] : []))
        : []
    } else if (Number.isFinite(moved)) {
      dueAt = Math.min(dueAt ?? Infinity, moved + STALL_MS)
    }
  }

  return dueAt !== undefined ? { stalled, dueAt } : { stalled }
}

/** The breadcrumb from an `items_read` `get` with `fields: ['ancestors']`: ancestors root first, then the item (short ids). */
export function trailOf(raw: unknown, scope: string): { id: string; title: string }[] | undefined {
  if (!isObj(raw)) return undefined
  const ancestors = Array.isArray(raw.ancestors) ? raw.ancestors : []
  const trail: { id: string; title: string }[] = []
  for (const a of ancestors) {
    const id = isObj(a) ? sid(a.id) : undefined
    if (id !== undefined) trail.push({ id, title: typeof (a as Obj).title === 'string' ? ((a as Obj).title as string) : id })
  }
  const self = shortId(scope)
  trail.push({ id: self, title: typeof raw.title === 'string' ? raw.title : self })

  return trail
}

/**
 * The graph of `scopeId`'s subtree (null: the project root from config.yaml). Read-only: only `items_read`
 * and `deps_read` are ever called. The project root (null, or its own id by short id) gets the one-call
 * overview instead of the subtree walk.
 */
export async function snapshot(io: GraphIo, scopeId: string | null): Promise<GraphSnapshot> {
  const errors: string[] = []
  const takenAt = await io.now().catch(() => 0)
  let rootId: string | null = null
  try {
    rootId = await readRootId(io)
  } catch {
    rootId = null
  }
  const scopeFull = scopeId ?? rootId
  const scope = scopeFull === null ? null : shortId(scopeFull)
  const result: GraphSnapshot = { scopeId: scope, rootId: rootId === null ? null : shortId(rootId), nodes: [], edges: [], external: {}, gates: {}, takenAt, truncated: false }
  if (scope === null || scopeFull === null) return { ...result, error: 'no project.rootId' }
  if (rootId !== null && scope === shortId(rootId)) return overviewSnapshot(io, result, rootId)

  try {
    const { items, total } = await fetchSubtree(io, scopeFull, errors)
    const capped = capNodes(items)
    // Truncated when the server holds more than the cap, even if paging stopped early.
    result.truncated = capped.truncated || total > NODE_CAP
    const ids = new Set(capped.nodes.map(node => node.id))
    result.nodes = capped.nodes.map(node => (node.parentId !== null && !ids.has(node.parentId) ? { ...node, parentId: null } : node))

    // The breadcrumb: the scope's ancestors (root first) and the scope itself, in one read.
    try {
      result.trail = trailOf(await io.callTool('items_read', { select: 'get', itemId: scopeFull, fields: ['ancestors'] }), scope)
    } catch {
      // no trail: the pane falls back to the plain header
    }

    const edges = new Map<string, GraphEdge>()
    const seenDeps = new Set<string>()
    const externalIds = new Set<string>()
    await mapLimit(result.nodes, LANES, async node => {
      addEdges(await fetchEdges(io, node.id, errors), ids, edges, seenDeps, externalIds)
    })
    result.edges = [...edges.values()]

    // External edge ends: one get each (a failed read leaves no entry).
    await mapLimit([...externalIds], LANES, async id => {
      try {
        const raw = await io.callTool('items_read', { select: 'get', itemId: id })
        if (isObj(raw)) result.external[id] = { title: typeof raw.title === 'string' ? raw.title : id, role: str(raw.role) ?? 'queue' }
      } catch {
        // no entry: the edge end draws as unknown
      }
    })

    result.nodes = await fetchLabels(io, result.nodes, (ids, fn) => mapLimit(ids, LANES, fn))

    const active = result.nodes.filter(node => node.role === 'work' || node.role === 'review')
    await mapLimit(active, LANES, async node => {
      try {
        const gate = toGate(await io.callTool('items_read', { select: 'context', itemId: node.id }), node.role)
        if (gate) result.gates[node.id] = gate
      } catch (err) {
        errors.push(message(err))
      }
    })

    // Stalls: ONE health-context read for the scope plus ONE history read. A failure leaves `stalled` unset.
    try {
      const context = await io.callTool('items_read', { select: 'context', ancestorId: scopeFull, limit: TRANSITION_LIMIT })
      const history = await io.callTool('items_read', { select: 'history', since: new Date(takenAt - STALL_MS).toISOString(), limit: TRANSITION_LIMIT })
      const st = stallsOf(context, history, ids, takenAt)
      result.stalled = st.stalled
      if (st.dueAt !== undefined) result.stallDueAt = st.dueAt
    } catch (err) {
      errors.push(message(err))
    }
  } catch (err) {
    errors.push(message(err))
  }

  if (errors.length > 0) {
    const shown = errors.slice(0, 3).join('; ')
    result.error = errors.length > 3 ? `${shown} (+${errors.length - 3} more)` : shown
  }

  return result
}
