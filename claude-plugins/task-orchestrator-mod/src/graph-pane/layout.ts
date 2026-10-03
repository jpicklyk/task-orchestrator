// Layered DAG layout (T3 a47dcd6f). Pure and deterministic: longest-path layering over BLOCKS
// edges (cycles broken by DFS), then alternating barycenter sweeps to reduce crossings.
// Containment (parent/child) is grouping only and never an edge; RELATES_TO edges do not layer.
import type { GraphEdge } from '../../types'

export const NODE_W = 220
export const NODE_H = 62
export const GAP_X = 56
export const GAP_Y = 18
export const MARGIN = 20
export const SWEEPS = 4

/** What layout needs of a node: ordering keys, no rendering data. */
export interface LayoutNode {
  id: string
  parentId: string | null
  title: string
}

export interface Placed {
  x: number
  y: number
  layer: number
  order: number
}

export interface Layout {
  layers: string[][]
  pos: Record<string, Placed>
  width: number
  height: number
  /** BLOCKS edges dropped from layering to break a cycle (`from|to`). They still draw, dashed. */
  backEdges: string[]
}

const cmp = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0)

/**
 * Lays out `nodes` (the DAG nodes: leaves and external stubs) over the BLOCKS edges whose both ends
 * are in `nodes`. `from` sits in an earlier layer than `to`. Always terminates, cycle or not.
 */
export function layout(nodes: readonly LayoutNode[], edges: readonly GraphEdge[]): Layout {
  const ids = new Set(nodes.map(n => n.id))
  const info = new Map(nodes.map(n => [n.id, n]))
  const blocks = edges.filter(e => e.type === 'BLOCKS' && e.from !== e.to && ids.has(e.from) && ids.has(e.to))

  // Adjacency in input order, for a deterministic DFS.
  const out = new Map<string, string[]>(nodes.map(n => [n.id, []]))
  for (const e of blocks) {
    const list = out.get(e.from) as string[]
    if (!list.includes(e.to)) list.push(e.to)
  }

  // Cycle breaking: an edge to a node on the DFS stack is a back-edge. Iterative, so depth is no limit.
  const back = new Set<string>()
  const state = new Map<string, 1 | 2>()
  for (const n of nodes) {
    if (state.has(n.id)) continue
    const stack: { id: string; i: number }[] = [{ id: n.id, i: 0 }]
    state.set(n.id, 1)
    while (stack.length > 0) {
      const top = stack[stack.length - 1] as { id: string; i: number }
      const next = (out.get(top.id) as string[])[top.i++]
      if (next === undefined) {
        state.set(top.id, 2)
        stack.pop()
      } else if (state.get(next) === 1) back.add(`${top.id}|${next}`)
      else if (!state.has(next)) {
        state.set(next, 1)
        stack.push({ id: next, i: 0 })
      }
    }
  }

  // Longest path over the remaining DAG (Kahn order).
  const preds = new Map<string, string[]>(nodes.map(n => [n.id, []]))
  const indeg = new Map<string, number>(nodes.map(n => [n.id, 0]))
  for (const [from, tos] of out) {
    for (const to of tos) {
      if (back.has(`${from}|${to}`)) continue
      ;(preds.get(to) as string[]).push(from)
      indeg.set(to, (indeg.get(to) as number) + 1)
    }
  }
  const layerOf = new Map<string, number>()
  const queue = nodes.filter(n => indeg.get(n.id) === 0).map(n => n.id)
  for (let q = 0; q < queue.length; q++) {
    const id = queue[q] as string
    layerOf.set(id, Math.max(0, ...(preds.get(id) as string[]).map(p => (layerOf.get(p) as number) + 1)))
    for (const to of out.get(id) as string[]) {
      if (back.has(`${id}|${to}`)) continue
      const left = (indeg.get(to) as number) - 1
      indeg.set(to, left)
      if (left === 0) queue.push(to)
    }
  }

  const depth = Math.max(-1, ...layerOf.values())
  const layers: string[][] = Array.from({ length: depth + 1 }, () => [])
  for (const n of nodes) layers[layerOf.get(n.id) as number]?.push(n.id)

  // Neighbours across adjacent layers, over layering edges only.
  const up = new Map<string, string[]>(nodes.map(n => [n.id, []]))
  const down = new Map<string, string[]>(nodes.map(n => [n.id, []]))
  for (const [from, tos] of out) {
    for (const to of tos) {
      if (back.has(`${from}|${to}`)) continue
      if ((layerOf.get(to) as number) - (layerOf.get(from) as number) !== 1) continue
      ;(down.get(from) as string[]).push(to)
      ;(up.get(to) as string[]).push(from)
    }
  }

  // Start from a canonical order (parent, title, id) so siblings sit together and the input order is moot.
  for (const layer of layers) {
    layer.sort((a, b) => {
      const x = info.get(a) as LayoutNode
      const y = info.get(b) as LayoutNode

      return cmp(x.parentId ?? '', y.parentId ?? '') || cmp(x.title, y.title) || cmp(a, b)
    })
  }
  const index = new Map<string, number>()
  const reindex = (): void => layers.forEach(layer => layer.forEach((id, i) => index.set(id, i)))
  reindex()
  const sweep = (layer: string[], neighbours: Map<string, string[]>): void => {
    const keyed = layer.map(id => {
      const ns = neighbours.get(id) as string[]
      const bary = ns.length === 0 ? (index.get(id) as number) : ns.reduce((s, n) => s + (index.get(n) as number), 0) / ns.length
      const node = info.get(id) as LayoutNode

      return { id, bary, parent: node.parentId ?? '', title: node.title }
    })
    keyed.sort((a, b) => a.bary - b.bary || cmp(a.parent, b.parent) || cmp(a.title, b.title) || cmp(a.id, b.id))
    keyed.forEach((k, i) => {
      layer[i] = k.id
    })
    reindex()
  }
  for (let s = 0; s < SWEEPS; s++) {
    if (s % 2 === 0) for (let l = 1; l < layers.length; l++) sweep(layers[l] as string[], up)
    else for (let l = layers.length - 2; l >= 0; l--) sweep(layers[l] as string[], down)
  }

  const pos: Record<string, Placed> = {}
  let rows = 0
  layers.forEach((layer, l) => {
    rows = Math.max(rows, layer.length)
    layer.forEach((id, order) => {
      pos[id] = { x: MARGIN + l * (NODE_W + GAP_X), y: MARGIN + order * (NODE_H + GAP_Y), layer: l, order }
    })
  })

  return {
    layers,
    pos,
    width: MARGIN * 2 + Math.max(1, layers.length) * NODE_W + Math.max(0, layers.length - 1) * GAP_X,
    height: MARGIN * 2 + Math.max(1, rows) * NODE_H + Math.max(0, rows - 1) * GAP_Y,
    backEdges: [...back],
  }
}

/** Crossings among layering edges that join adjacent layers; the measure the sweeps reduce. */
export function countCrossings(l: Layout, edges: readonly GraphEdge[]): number {
  const adjacent = edges.filter(e => {
    const a = l.pos[e.from]
    const b = l.pos[e.to]

    return e.type === 'BLOCKS' && a !== undefined && b !== undefined && b.layer - a.layer === 1 && !l.backEdges.includes(`${e.from}|${e.to}`)
  })
  let n = 0
  for (let i = 0; i < adjacent.length; i++) {
    for (let j = i + 1; j < adjacent.length; j++) {
      const a = adjacent[i] as GraphEdge
      const b = adjacent[j] as GraphEdge
      const pa = l.pos[a.from] as Placed
      const pb = l.pos[b.from] as Placed
      if (pa.layer !== pb.layer) continue
      const d1 = (l.pos[a.to] as Placed).order - (l.pos[b.to] as Placed).order
      const d0 = pa.order - pb.order
      if (d0 * d1 < 0) n++
    }
  }

  return n
}
