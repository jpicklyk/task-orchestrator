// The edge router: one set of polylines per mode, in cell units. 'px' keeps the half-cell offsets of the
// approved probe (the Svg draws them); 'cell' is the integer port the terminal rasterises.
import { BH, RG } from './layout.ts'
import type { Rect, TopDown } from './layout.ts'
import type { Card } from './model.ts'

export type RouteKind = 'open' | 'done' | 'contain'
export type Mode = 'px' | 'cell'

/**
 * `pts[0]` is the start; segments alternate vertical, horizontal, vertical, ... (a zero-length one
 * keeps the alternation). `head` marks an arrowhead at the last point.
 */
export interface Route {
  kind: RouteKind
  pts: [number, number][]
  head: boolean
}

interface Anchor {
  rect: Rect
  row: number
}

export function routes(lay: TopDown, cards: readonly Card[], mode: Mode): Route[] {
  const px = mode === 'px'
  const rowOf = new Map<number, number>()
  lay.rows.forEach((r, i) => rowOf.set(r.step, i))
  const chipByStep = new Map(lay.chips.map(c => [c.step, c]))
  const stepOf = new Map<string, number>()
  for (const r of lay.rows) for (const id of r.ids) stepOf.set(id, r.step)

  const anchorOf = (id: string): Anchor | null => {
    const rect = lay.cards.get(id)
    if (rect !== undefined) return { rect, row: rowOf.get(stepOf.get(id) as number) as number }
    const chip = chipByStep.get(stepOf.get(id) as number)

    return chip !== undefined ? { rect: chip.rect, row: rowOf.get(chip.step) as number } : null
  }
  const cx = (r: Rect): number => (px ? r.left + r.width / 2 : r.left + Math.floor(r.width / 2))
  // Obstacles in the rows an edge crosses.
  const blockedIn = (rowFrom: number, rowTo: number) => {
    const rects: Rect[] = []
    for (let i = rowFrom; i <= rowTo; i++) {
      const row = lay.rows[i]
      if (row === undefined) continue
      if (row.chip) rects.push(...lay.chips.filter(c => c.step === row.step).map(c => c.rect))
      else for (const id of row.ids) rects.push(lay.cards.get(id) as Rect)
    }

    return (x: number): boolean => rects.some(r => (px ? x > r.left - 0.5 && x < r.left + r.width + 0.5 : x >= r.left && x <= r.left + r.width - 1))
  }
  const step = px ? 0.5 : 1

  const route = (kind: RouteKind, s: Rect, srow: number, t: Rect, trow: number): Route => {
    const sx = cx(s)
    const tx = cx(t)
    const start = s.top + s.height
    const gap = px ? t.top - RG / 2 : t.top - 2
    const end = px ? t.top : t.top - 1
    if (trow - srow > 1) {
      const blocked = blockedIn(srow + 1, trow - 1)
      let ch = sx
      for (let off = 0; off < lay.width && blocked(ch); off += step) ch = !blocked(sx + off) ? sx + off : !blocked(sx - off) ? sx - off : ch
      const below = px ? s.top + s.height + RG / 2 : s.top + s.height + 1

      return { kind, pts: [[sx, start], [sx, below], [ch, below], [ch, gap], [tx, gap], [tx, end]], head: kind !== 'contain' }
    }

    return { kind, pts: [[sx, start], [sx, gap], [tx, gap], [tx, end]], head: kind !== 'contain' }
  }

  const out: Route[] = []
  const seen = new Map<string, Route>()
  const rootStart: Rect = { ...lay.root, height: BH }
  for (const c of cards) {
    const target = anchorOf(c.id)
    if (target === null) continue
    const toChip = !lay.cards.has(c.id)
    for (const d of c.deps) {
      const source = anchorOf(d.id)
      if (source === null || source.rect === target.rect) continue
      const fromChip = !lay.cards.has(d.id)
      const kind: RouteKind = fromChip || !d.open ? 'done' : 'open'
      // Folded endpoints attach to their chip (top centre in, bottom centre out), one edge per pair.
      const key = `${fromChip ? `step:${stepOf.get(d.id)}` : d.id}>${toChip ? `step:${stepOf.get(c.id)}` : c.id}`
      const prior = seen.get(key)
      if (prior !== undefined) {
        if (kind === 'open') prior.kind = 'open'
        continue
      }
      const r = route(kind, source.rect, source.row, target.rect, target.row)
      seen.set(key, r)
      out.push(r)
    }
    if (!toChip && c.deps.length === 0 && lay.hasRoot) out.push(route('contain', rootStart, -1, target.rect, target.row))
  }

  return out
}
