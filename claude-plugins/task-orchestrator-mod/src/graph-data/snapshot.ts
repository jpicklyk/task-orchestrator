// Builds a graph snapshot of a work-item subtree with read-only TO calls. Never throws: a failed
// call is recorded in `error` and the snapshot comes back partial.
import type { GateInfo, GraphEdge, GraphNode, GraphSnapshot, SeatProgress } from '../../types'
import { readSection, scalar } from '../lib/yaml-lite.mjs'
import { CONFIG_PATH } from '../shared/config.ts'
import type { GraphIo } from './io.ts'
import { fetchLabels } from './labels.ts'

/** Most nodes a snapshot keeps; past it the shallowest win and `truncated` is set. */
export const NODE_CAP = 150
const PAGE_SIZE = 100
/** Never page past this many items, whatever `total` claims. */
const FETCH_CEILING = 1000
const LANES = 6

type Obj = Record<string, unknown>

const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null
const str = (v: unknown): string | undefined => (typeof v === 'string' && v.length > 0 ? v : undefined)
const message = (err: unknown): string => (err instanceof Error ? err.message : String(err))

/** Runs `fn` over `items` with at most `limit` in flight. `fn` must not reject. */
async function mapLimit<T>(items: readonly T[], limit: number, fn: (item: T) => Promise<void>): Promise<void> {
  let next = 0
  const lane = async (): Promise<void> => {
    while (next < items.length) await fn(items[next++] as T)
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, lane))
}

/** `project.rootId` from the project config, or null when there is none. */
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
  const id = str(raw.id)
  if (id === undefined) return null
  const node: GraphNode = {
    id,
    parentId: str(raw.parentId) ?? null,
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

  return node
}

/** The inclusive subtree of `scope`, paged; `total` is what the server says exists. */
async function fetchSubtree(io: GraphIo, scope: string, errors: string[]): Promise<{ items: GraphNode[]; total: number }> {
  const items: GraphNode[] = []
  let total = Infinity
  for (let offset = 0; offset < Math.min(total, FETCH_CEILING); offset += PAGE_SIZE) {
    let page: unknown
    try {
      page = await io.callTool('query_items', { operation: 'search', ancestorId: scope, limit: PAGE_SIZE, offset })
    } catch (err) {
      errors.push(message(err))
      break
    }
    const rows = isObj(page) && Array.isArray(page.items) ? page.items : []
    for (const row of rows) {
      const node = toNode(row)
      if (node) items.push(node)
    }
    if (isObj(page) && typeof page.total === 'number') total = page.total
    else total = items.length
    if (rows.length === 0) break
  }

  return { items, total: Number.isFinite(total) ? total : items.length }
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

function addEdges(raw: unknown, ids: ReadonlySet<string>, edges: Map<string, GraphEdge>, seenDeps: Set<string>, external: GraphSnapshot['external']): void {
  const deps = isObj(raw) && Array.isArray(raw.dependencies) ? raw.dependencies : []
  for (const dep of deps) {
    if (!isObj(dep)) continue
    const depId = str(dep.id)
    if (depId !== undefined) {
      if (seenDeps.has(depId)) continue
      seenDeps.add(depId)
    }
    const fromItemId = str(dep.fromItemId)
    const toItemId = str(dep.toItemId)
    if (fromItemId === undefined || toItemId === undefined) continue
    if (!ids.has(fromItemId) && !ids.has(toItemId)) continue

    for (const [id, info] of [[fromItemId, dep.fromItem], [toItemId, dep.toItem]] as const) {
      if (ids.has(id) || external[id] || !isObj(info)) continue
      external[id] = { title: typeof info.title === 'string' ? info.title : id, role: str(info.role) ?? 'queue' }
    }

    const kind = str(dep.type)
    let edge: GraphEdge
    if (kind === 'IS_BLOCKED_BY') edge = { from: toItemId, to: fromItemId, type: 'BLOCKS' }
    else if (kind === 'RELATES_TO') edge = { from: fromItemId, to: toItemId, type: 'RELATES_TO' }
    else edge = { from: fromItemId, to: toItemId, type: 'BLOCKS' }
    const unblockAt = str(dep.effectiveUnblockRole)
    if (unblockAt !== undefined) edge.unblockAt = unblockAt

    // The same relationship can arrive as BLOCKS from one end and IS_BLOCKED_BY from the other.
    const key = edge.type === 'RELATES_TO' ? `RELATES_TO|${[edge.from, edge.to].sort().join('|')}` : `BLOCKS|${edge.from}|${edge.to}`
    if (!edges.has(key)) edges.set(key, edge)
  }
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
 * The graph of `scopeId`'s subtree (null: the project root from config.yaml). Read-only: only
 * `query_items`, `query_dependencies` and `get_context` are ever called.
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
  const scope = scopeId ?? rootId
  const result: GraphSnapshot = { scopeId: scope, rootId, nodes: [], edges: [], external: {}, gates: {}, takenAt, truncated: false }
  if (scope === null) return { ...result, error: 'no project.rootId' }

  try {
    const { items, total } = await fetchSubtree(io, scope, errors)
    const capped = capNodes(items)
    // Truncated when the server holds more than the cap, even if paging stopped early.
    result.truncated = capped.truncated || total > NODE_CAP
    const ids = new Set(capped.nodes.map(node => node.id))
    result.nodes = capped.nodes.map(node => (node.parentId !== null && !ids.has(node.parentId) ? { ...node, parentId: null } : node))

    const edges = new Map<string, GraphEdge>()
    const seenDeps = new Set<string>()
    await mapLimit(result.nodes, LANES, async node => {
      try {
        const raw = await io.callTool('query_dependencies', { operation: 'get', itemId: node.id, direction: 'all', includeItemInfo: true })
        addEdges(raw, ids, edges, seenDeps, result.external)
      } catch (err) {
        errors.push(message(err))
      }
    })
    result.edges = [...edges.values()]
    result.nodes = await fetchLabels(io, result.nodes, (ids, fn) => mapLimit(ids, LANES, fn))

    const active = result.nodes.filter(node => node.role === 'work' || node.role === 'review')
    await mapLimit(active, LANES, async node => {
      try {
        const gate = toGate(await io.callTool('get_context', { itemId: node.id }), node.role)
        if (gate) result.gates[node.id] = gate
      } catch (err) {
        errors.push(message(err))
      }
    })
  } catch (err) {
    errors.push(message(err))
  }

  if (errors.length > 0) {
    const shown = errors.slice(0, 3).join('; ')
    result.error = errors.length > 3 ? `${shown} (+${errors.length - 3} more)` : shown
  }

  return result
}
