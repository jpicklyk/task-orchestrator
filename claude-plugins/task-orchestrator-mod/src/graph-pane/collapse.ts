// Collapses a project-root snapshot to root -> containers -> features, rolling the hidden
// descendants up onto the visible ancestor and lifting edges to it (T3 a47dcd6f). Pure.
import type { GraphEdge, GraphNode } from '../../types'
import type { GraphView, Rollup } from './shared.ts'

/** How many levels below the scope's own depth stay visible when the scope is the project root. */
export const COLLAPSED_LEVELS = 2

/**
 * `scopeIsRoot` false passes the view through. True keeps nodes with depth <= scopeDepth + 2, where
 * scopeDepth is the shallowest depth in the snapshot; each hidden node counts toward its nearest
 * visible ancestor's roll-up, and an edge with a hidden end is lifted to that end's visible ancestor
 * (self-loops dropped, duplicates merged).
 */
export function collapse(view: GraphView, scopeIsRoot: boolean): GraphView {
  if (!scopeIsRoot || view.nodes.length === 0) return view
  const scopeDepth = Math.min(...view.nodes.map(n => n.depth))
  const limit = scopeDepth + COLLAPSED_LEVELS
  const byId = new Map<string, GraphNode>(view.nodes.map(n => [n.id, n]))
  const visible = new Set(view.nodes.filter(n => n.depth <= limit).map(n => n.id))

  // The nearest visible ancestor-or-self of an id, or null (external ids and orphans stay as they are).
  const lift = (id: string): string | null => {
    let cur = byId.get(id)
    let hops = 0
    while (cur !== undefined && !visible.has(cur.id) && hops++ <= view.nodes.length) {
      cur = cur.parentId === null ? undefined : byId.get(cur.parentId)
    }

    return cur?.id ?? null
  }

  const rollups: Record<string, Rollup> = {}
  for (const n of view.nodes) {
    if (visible.has(n.id)) continue
    const anchor = lift(n.id)
    if (anchor === null) continue
    const r = (rollups[anchor] ??= { count: 0, byRole: {} })
    r.count += 1
    r.byRole[n.role] = (r.byRole[n.role] ?? 0) + 1
  }

  const edges = new Map<string, GraphEdge>()
  for (const e of view.edges) {
    const from = byId.has(e.from) ? lift(e.from) : e.from
    const to = byId.has(e.to) ? lift(e.to) : e.to
    if (from === null || to === null || from === to) continue
    const key = `${e.type}|${from}|${to}`
    if (!edges.has(key)) edges.set(key, { ...e, from, to })
  }

  return {
    ...view,
    nodes: view.nodes.filter(n => visible.has(n.id)),
    edges: [...edges.values()],
    gates: Object.fromEntries(Object.entries(view.gates).filter(([id]) => visible.has(id))),
    rollups,
  }
}
