// Pure card model of the top-down graph: one card per non-root node with its dependencies, open
// blockers, readiness and state line; plus the cycle-broken longest-path step assignment.
import type { GraphWorker } from '../../types'
import { glyphOf, id8, kindOf, phaseText, rollupLine } from './shared.ts'
import type { GraphView, Kind } from './shared.ts'
import { shortModel } from './activity.ts'

/** One blocker of a card inside the drawn scope; `open` while it still blocks. */
export interface Dep {
  id: string
  open: boolean
}

export interface Card {
  id: string
  label: string
  title: string
  kind: Kind
  glyph: string
  role: string
  deps: Dep[]
  /** Labels of open blockers (in-scope by label, external by id8). */
  openBlockers: string[]
  ready: boolean
  stateText: string
  /** Touched by a TO write in the last RECENT_MS. */
  recent: boolean
}

export interface Model {
  /** The scope's own node, drawn as the wide box on top; undefined when the view has none. */
  root?: { id: string; label: string; title: string; kind: Kind; glyph: string }
  cards: Card[]
}

const RANK: Record<string, number> = { queue: 0, blocked: 0, work: 1, review: 2, terminal: 3 }

/** Whether a blocker in `blockerRole` still blocks, given the role at which it unblocks (default terminal). */
export function isOpen(blockerRole: string, unblockAt?: string): boolean {
  return (RANK[blockerRole] ?? 0) < (RANK[unblockAt ?? 'terminal'] ?? 3)
}

/** `T12` -> 12; unlabelled -> 999. */
export const num = (label: string): number => Number(/^T(\d+)/.exec(label)?.[1] ?? 999)

const cmp = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0)

const SEAT_ABBR: Record<string, string> = { implementer: 'impl', reviewer: 'rev', planner: 'plan', 'test-author': 'test', orchestrator: 'orch' }

/** Live workers' state text, compact for narrow boxes: `» impl · sonnet`, plus ` +N` for more workers. */
function workerText(workers: readonly GraphWorker[]): string {
  const first = workers[0] as GraphWorker
  const model = shortModel(first.model)?.split('-')[0]
  const seat = SEAT_ABBR[first.seat] ?? first.seat

  return `» ${seat}${model !== undefined ? ` · ${model}` : ''}${workers.length > 1 ? ` +${workers.length - 1}` : ''}`
}

export function cardsOf(view: GraphView, workers: Record<string, readonly GraphWorker[]> = {}, changed: Record<string, number> = {}): Model {
  const rootNode =
    view.nodes.find(n => n.id === view.scopeId) ?? view.nodes.find(n => n.parentId === null)
  const labelOf = (n: { id: string; planLabel?: string }): string => n.planLabel ?? id8(n.id)
  const nodes = view.nodes.filter(n => n.id !== rootNode?.id)
  const byId = new Map(nodes.map(n => [n.id, n]))

  const cards: Card[] = nodes.map(node => {
    const incoming = view.edges.filter(e => e.type === 'BLOCKS' && e.to === node.id && e.from !== node.id)
    const deps: Dep[] = []
    const open: { label: string; n: number }[] = []
    for (const e of incoming) {
      const src = byId.get(e.from)
      if (src !== undefined) {
        const isO = isOpen(src.role, e.unblockAt)
        if (!deps.some(d => d.id === src.id)) deps.push({ id: src.id, open: isO })
        if (isO) open.push({ label: labelOf(src), n: num(labelOf(src)) })
        continue
      }
      const ext = view.external[e.from]
      if (ext !== undefined && e.from !== rootNode?.id && isOpen(ext.role, e.unblockAt)) open.push({ label: id8(e.from), n: 999 })
    }
    const openBlockers = [...new Set(open.sort((a, b) => a.n - b.n || cmp(a.label, b.label)).map(o => o.label))]
    // An overview draws each child in its own role colour: no ready tint, no blockers (it reads no edges).
    const ready = view.overview !== true && node.role === 'queue' && openBlockers.length === 0
    const live = workers[node.id] ?? []
    const rollup = view.overview === true ? rollupLine(node.childCounts) : ''
    const stateText = view.overview === true
      ? rollup !== '' ? rollup : phaseText(node, undefined)
      : openBlockers.length > 0 ? `⊘ after ${openBlockers.join(', ')}` : live.length > 0 ? workerText(live) : ready ? 'ready' : phaseText(node, view.gates[node.id])

    return {
      id: node.id,
      label: labelOf(node),
      title: node.title,
      kind: kindOf(node),
      glyph: glyphOf(node),
      role: node.role,
      deps,
      openBlockers,
      ready,
      stateText,
      recent: changed[node.id] !== undefined,
    }
  })

  return {
    ...(rootNode !== undefined ? { root: { id: rootNode.id, label: labelOf(rootNode), title: rootNode.title, kind: kindOf(rootNode), glyph: glyphOf(rootNode) } } : {}),
    cards,
  }
}

export interface Steps {
  /** 1-based longest-path step of each card. */
  step: Map<string, number>
  /** Dependency edges dropped from stepping to break a cycle (`from|to`); they still draw. */
  back: Set<string>
  max: number
}

/** Longest-path steps over in-scope dependencies, cycles broken by DFS back edges. Always terminates. */
export function stepsOf(cards: readonly Card[]): Steps {
  const ids = new Set(cards.map(c => c.id))
  const out = new Map<string, string[]>(cards.map(c => [c.id, []]))
  for (const c of cards) {
    for (const d of c.deps) {
      if (d.id === c.id || !ids.has(d.id)) continue
      const list = out.get(d.id) as string[]
      if (!list.includes(c.id)) list.push(c.id)
    }
  }
  const back = new Set<string>()
  const state = new Map<string, 1 | 2>()
  for (const c of cards) {
    if (state.has(c.id)) continue
    const stack: { id: string; i: number }[] = [{ id: c.id, i: 0 }]
    state.set(c.id, 1)
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
  const preds = new Map<string, string[]>(cards.map(c => [c.id, []]))
  const indeg = new Map<string, number>(cards.map(c => [c.id, 0]))
  for (const [from, tos] of out) {
    for (const to of tos) {
      if (back.has(`${from}|${to}`)) continue
      ;(preds.get(to) as string[]).push(from)
      indeg.set(to, (indeg.get(to) as number) + 1)
    }
  }
  const step = new Map<string, number>()
  const queue = cards.filter(c => indeg.get(c.id) === 0).map(c => c.id)
  for (let q = 0; q < queue.length; q++) {
    const id = queue[q] as string
    step.set(id, Math.max(0, ...(preds.get(id) as string[]).map(p => step.get(p) as number)) + 1)
    for (const to of out.get(id) as string[]) {
      if (back.has(`${id}|${to}`)) continue
      const left = (indeg.get(to) as number) - 1
      indeg.set(to, left)
      if (left === 0) queue.push(to)
    }
  }

  return { step, back, max: Math.max(0, ...step.values()) }
}
