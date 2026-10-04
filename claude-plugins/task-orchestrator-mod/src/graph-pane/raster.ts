// Terminal rasterisation of edge routes: each cell collects the arms (N/E/S/W) of every segment through
// it, so shared corners become tees (┴ ├) instead of crossings. Arrowheads are written last and win.
import type { Route, RouteKind } from './route.ts'

export interface Cell {
  glyph: string
  color: string
  dim: boolean
}

export const EDGE_COLOR: Record<RouteKind, string> = { open: '#f59e0b', done: '#9ca3af', contain: '#6b7280' }
const RANK: Record<RouteKind, number> = { open: 3, done: 2, contain: 1 }

const N = 1
const E = 2
const S = 4
const W = 8

const VERT: Record<RouteKind, string> = { done: '│', open: '╎', contain: '┆' }
const HORZ: Record<RouteKind, string> = { done: '─', open: '╌', contain: '┄' }
const SOLID: Record<number, string> = {
  [E | S]: '┌',
  [W | S]: '┐',
  [N | E]: '└',
  [N | W]: '┘',
  [N | S | E]: '├',
  [N | S | W]: '┤',
  [E | W | S]: '┬',
  [E | W | N]: '┴',
  [N | E | S | W]: '┼',
}

function glyphOf(arms: number, kind: RouteKind): string {
  if (arms === N || arms === S || arms === (N | S)) return VERT[kind]
  if (arms === E || arms === W || arms === (E | W)) return HORZ[kind]

  return SOLID[arms] ?? '┼'
}

/** Cells keyed `x_y`. Coordinates are integers (cell mode). */
export function raster(rs: readonly Route[]): Map<string, Cell> {
  const arms = new Map<string, { m: number; kind: RouteKind }>()
  const touch = (x: number, y: number, mask: number, kind: RouteKind): void => {
    const key = `${x}_${y}`
    const cur = arms.get(key)
    if (cur === undefined) arms.set(key, { m: mask, kind })
    else arms.set(key, { m: cur.m | mask, kind: RANK[kind] > RANK[cur.kind] ? kind : cur.kind })
  }
  for (const r of rs) {
    for (let i = 0; i + 1 < r.pts.length; i++) {
      const [x1, y1] = r.pts[i] as [number, number]
      const [x2, y2] = r.pts[i + 1] as [number, number]
      if (x1 === x2 && y1 === y2) continue
      const dx = Math.sign(x2 - x1)
      const dy = Math.sign(y2 - y1)
      const toward = dx > 0 ? E : dx < 0 ? W : dy > 0 ? S : N
      const back = dx > 0 ? W : dx < 0 ? E : dy > 0 ? N : S
      const len = Math.abs(x2 - x1) + Math.abs(y2 - y1)
      for (let k = 0; k <= len; k++) touch(x1 + dx * k, y1 + dy * k, (k < len ? toward : 0) | (k > 0 ? back : 0), r.kind)
    }
  }
  const cells = new Map<string, Cell>()
  for (const [key, { m, kind }] of arms) cells.set(key, { glyph: glyphOf(m, kind), color: EDGE_COLOR[kind], dim: kind !== 'open' })
  for (const r of rs) {
    if (!r.head) continue
    const [x, y] = r.pts[r.pts.length - 1] as [number, number]
    cells.set(`${x}_${y}`, { glyph: '▼', color: EDGE_COLOR[r.kind], dim: r.kind !== 'open' })
  }

  return cells
}

/** One horizontal run of identically drawn cells; `x_y` of its first cell and the glyph repeated. */
export interface Run {
  x: number
  y: number
  n: number
  glyph: string
  color: string
  dim: boolean
}

/** Cells merged into runs along each row (the fallback that spends fewer elements). */
export function runs(cells: Map<string, Cell>): Run[] {
  const list = [...cells].map(([key, c]) => {
    const [x, y] = key.split('_').map(Number) as [number, number]

    return { x, y, c }
  })
  list.sort((a, b) => a.y - b.y || a.x - b.x)
  const out: Run[] = []
  for (const { x, y, c } of list) {
    const last = out[out.length - 1]
    if (last !== undefined && last.y === y && last.x + last.n === x && last.glyph === c.glyph && last.color === c.color && last.dim === c.dim) last.n += 1
    else out.push({ x, y, n: 1, glyph: c.glyph, color: c.color, dim: c.dim })
  }

  return out
}
