// Top-down geometry of the dependency graph, in whole character cells. Pure and deterministic:
// the root box on row 0, then one row per dependency step; rows ordered by barycenter sweeps.
import { num } from './model.ts'
import type { Card, Steps } from './model.ts'

export const GAP = 3
export const BH = 3
export const RG = 3
export const SWEEPS = 4

export interface Rect {
  left: number
  top: number
  width: number
  height: number
}

/** A fully finished step folded into one line. */
export interface Chip {
  step: number
  rect: Rect
  done: number
  cancelled: number
  /** Ids of the cards it stands for. */
  ids: string[]
}

export interface Row {
  step: number
  chip: boolean
  /** Card ids in left-to-right order (all of the step's cards, also when collapsed). */
  ids: string[]
  top: number
  height: number
}

export interface TopDown {
  root: Rect
  hasRoot: boolean
  cards: Map<string, Rect>
  chips: Chip[]
  rows: Row[]
  cols: number
  width: number
  height: number
  boxWidth: number
  /** Steps whose cards are all terminal, collapsed or not. */
  doneSteps: number[]
  /** The widest row's extent when it exceeds `cols`, else null. */
  tooWide: number | null
}

export interface LayoutOptions {
  bodyColumns?: number
  showDone: boolean
  hasRoot: boolean
}

const cmp = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0)

/** Row order per step: PROBE barycenter + 4 passes; ties by plan number, title, id. */
export function orderRows(cards: readonly Card[], steps: Steps): string[][] {
  const byId = new Map(cards.map(c => [c.id, c]))
  const rows: Card[][] = Array.from({ length: steps.max }, () => [])
  for (const c of cards) rows[(steps.step.get(c.id) ?? 1) - 1]?.push(c)
  const slot = new Map<string, number>()
  const tie = (a: Card, b: Card): number => num(a.label) - num(b.label) || cmp(a.title, b.title) || cmp(a.id, b.id)
  const mean = (xs: (number | undefined)[]): number | undefined => {
    const ok = xs.filter((x): x is number => x !== undefined)

    return ok.length > 0 ? ok.reduce((a, b) => a + b, 0) / ok.length : undefined
  }
  rows.forEach(row => {
    const key = (c: Card): number => mean(c.deps.map(d => slot.get(d.id))) ?? num(c.label) / 100
    row.sort((a, b) => key(a) - key(b) || tie(a, b))
    row.forEach((c, i) => slot.set(c.id, (i + 0.5) / row.length))
  })
  const dependents = new Map<string, string[]>()
  for (const c of cards) for (const d of c.deps) if (byId.has(d.id)) dependents.set(d.id, [...(dependents.get(d.id) ?? []), c.id])
  const reorder = (row: Card[], neighbours: (c: Card) => string[]): void => {
    const key = (c: Card): number => mean(neighbours(c).map(id => slot.get(id))) ?? slot.get(c.id) ?? 0.5
    row.sort((a, b) => key(a) - key(b) || tie(a, b))
    row.forEach((c, i) => slot.set(c.id, (i + 0.5) / row.length))
  }
  for (let pass = 0; pass < SWEEPS; pass++) {
    for (let i = rows.length - 2; i >= 0; i--) reorder(rows[i] as Card[], c => dependents.get(c.id) ?? [])
    for (let i = 1; i < rows.length; i++) reorder(rows[i] as Card[], c => c.deps.map(d => d.id))
  }

  return rows.map(r => r.map(c => c.id))
}

export function layoutTD(cards: readonly Card[], steps: Steps, opts: LayoutOptions): TopDown {
  const byId = new Map(cards.map(c => [c.id, c]))
  const cols = Math.max(60, (opts.bodyColumns ?? 120) - 2)
  const order = orderRows(cards, steps)
  const isDone = (ids: string[]): boolean => ids.length > 0 && ids.every(id => byId.get(id)?.role === 'terminal')
  const doneSteps = order.map((ids, i) => (isDone(ids) ? i + 1 : 0)).filter(s => s > 0)
  const collapsed = (i: number): boolean => !opts.showDone && doneSteps.includes(i + 1)

  const maxN = Math.max(1, ...order.filter((_, i) => !collapsed(i)).map(r => r.length))
  const BW = Math.max(16, Math.min(34, Math.floor((cols - (maxN - 1) * GAP) / maxN)))
  const rects = new Map<string, Rect>()
  const rows: Row[] = []
  const chips: Chip[] = []
  let y = BH + RG
  let rightmost = cols
  let widest = 0
  order.forEach((ids, i) => {
    const step = i + 1
    if (collapsed(i)) {
      const width = Math.min(cols, 40)
      const rect = { left: Math.floor((cols - width) / 2), top: y, width, height: 1 }
      const done = ids.filter(id => byId.get(id)?.kind === 'terminal').length
      chips.push({ step, rect, done, cancelled: ids.length - done, ids })
      rows.push({ step, chip: true, ids, top: y, height: 1 })
      y += 1 + RG

      return
    }
    const total = ids.length * BW + (ids.length - 1) * GAP
    const x0 = Math.max(0, Math.floor((cols - total) / 2))
    ids.forEach((id, j) => rects.set(id, { left: x0 + j * (BW + GAP), top: y, width: BW, height: BH }))
    rows.push({ step, chip: false, ids, top: y, height: BH })
    rightmost = Math.max(rightmost, x0 + total)
    widest = Math.max(widest, total)
    y += BH + RG
  })
  const last = rows[rows.length - 1]
  const RW = Math.min(cols, 56)

  return {
    root: { left: Math.floor((cols - RW) / 2), top: 0, width: RW, height: BH },
    hasRoot: opts.hasRoot,
    cards: rects,
    chips,
    rows,
    cols,
    width: rightmost,
    height: last !== undefined ? last.top + last.height : BH,
    boxWidth: BW,
    doneSteps,
    tooWide: widest > cols ? widest : null,
  }
}
