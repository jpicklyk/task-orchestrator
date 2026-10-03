// Small pure helpers shared by the /to-graph collapse, layout and renderers (T3 a47dcd6f).
import type { GateInfo, GraphEdge, GraphNode, GraphSnapshot } from '../../types'

/** Descendants rolled up onto a visible ancestor when the project root is shown collapsed. */
export interface Rollup {
  count: number
  byRole: Record<string, number>
}

/** A snapshot, optionally with roll-ups for the nodes that hide descendants (collapse's output). */
export type GraphView = GraphSnapshot & { rollups?: Record<string, Rollup> }

/** The role a node reads as: a terminal node labelled `cancelled` is its own dim kind. */
export type Kind = 'queue' | 'work' | 'review' | 'blocked' | 'terminal' | 'cancelled'

export const id8 = (id: string): string => id.slice(0, 8)

export function kindOf(node: { role: string; statusLabel?: string }): Kind {
  if (node.role === 'terminal') return node.statusLabel === 'cancelled' ? 'cancelled' : 'terminal'
  if (node.role === 'work' || node.role === 'review' || node.role === 'blocked') return node.role

  return 'queue'
}

const GLYPHS: Record<Kind, string> = { queue: '○', work: '◉', review: '◉', blocked: '⊘', terminal: '✓', cancelled: '—' }
export const glyphOf = (node: { role: string; statusLabel?: string }): string => GLYPHS[kindOf(node)]

/** `2/3 work notes` for a node with a gate entry that has required notes; '' otherwise. */
export function gateSuffix(gate: GateInfo | undefined): string {
  return gate !== undefined && gate.required > 0 ? `${gate.filled}/${gate.required} ${gate.phase} notes` : ''
}

export function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, Math.max(0, max - 1))}…`
}

/** The ids that have at least one child in `nodes`: containers (groups), not DAG nodes. */
export function containerIds(nodes: readonly GraphNode[]): Set<string> {
  const ids = new Set(nodes.map(n => n.id))
  const out = new Set<string>()
  for (const n of nodes) if (n.parentId !== null && ids.has(n.parentId)) out.add(n.parentId)

  return out
}

/** The visible nodes with no visible children: the nodes the DAG is laid out over. */
export function leafNodes(nodes: readonly GraphNode[]): GraphNode[] {
  const containers = containerIds(nodes)

  return nodes.filter(n => !containers.has(n.id))
}

/** The "roll-up" suffix for a node hiding descendants, e.g. `+12`. */
export const rollupText = (r: Rollup | undefined): string => (r !== undefined && r.count > 0 ? `+${r.count}` : '')

/** An external endpoint (outside the node set) drawn as a dashed stub. */
export interface Stub {
  id: string
  title: string
  role: string
  /** `blocked by` when it only blocks nodes here, `blocks` when it is only blocked by them, else `external`. */
  label: 'blocked by' | 'blocks' | 'external'
}

/** What the DAG is laid out over: the leaf nodes plus the external stubs, and the edges between them. */
export interface Dag {
  nodes: { id: string; parentId: string | null; title: string }[]
  edges: GraphEdge[]
  stubs: Stub[]
}

/**
 * Leaves are the DAG nodes; containers are groups. An external endpoint of an edge whose other end is
 * a leaf becomes a stub, unless it is already terminal. RELATES_TO and BLOCKS edges both carry through
 * (layout ignores the former); edges touching a container are dropped.
 */
export function dagOf(view: GraphView): Dag {
  const leaves = leafNodes(view.nodes)
  const leafIds = new Set(leaves.map(n => n.id))
  const roles = new Map<string, { out: number; inn: number }>()
  const edges: GraphEdge[] = []
  const stubIds: string[] = []
  const known = new Set(view.nodes.map(n => n.id))
  for (const e of view.edges) {
    const fromLeaf = leafIds.has(e.from)
    const toLeaf = leafIds.has(e.to)
    const fromExt = !known.has(e.from) && view.external[e.from] !== undefined && view.external[e.from]?.role !== 'terminal'
    const toExt = !known.has(e.to) && view.external[e.to] !== undefined && view.external[e.to]?.role !== 'terminal'
    if (!((fromLeaf && toLeaf) || (fromExt && toLeaf) || (fromLeaf && toExt))) continue
    edges.push(e)
    for (const [id, ext, dir] of [[e.from, fromExt, 'out'], [e.to, toExt, 'inn']] as const) {
      if (!ext) continue
      if (!roles.has(id)) {
        roles.set(id, { out: 0, inn: 0 })
        stubIds.push(id)
      }
      ;(roles.get(id) as { out: number; inn: number })[dir] += 1
    }
  }
  const stubs: Stub[] = stubIds.map(id => {
    const r = roles.get(id) as { out: number; inn: number }
    const ext = view.external[id] as { title: string; role: string }

    return { id, title: ext.title, role: ext.role, label: r.inn === 0 ? 'blocked by' : r.out === 0 ? 'blocks' : 'external' }
  })

  return {
    nodes: [...leaves.map(n => ({ id: n.id, parentId: n.parentId, title: n.title })), ...stubs.map(s => ({ id: s.id, parentId: null, title: s.title }))],
    edges,
    stubs,
  }
}
