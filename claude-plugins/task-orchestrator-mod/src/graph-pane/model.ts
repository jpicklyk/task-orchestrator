// Pure card model of the top-down graph: one card per non-root node with its dependencies, open
// blockers, readiness and state line; plus the cycle-broken longest-path step assignment.
import type { GraphGateBlock, GraphWorker } from '../../types'
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
  /** The state line is a warning (gate-blocked or stalled): drawn undimmed. */
  warn: boolean
  /** Touched by a TO write in the last RECENT_MS. */
  recent: boolean
  /** Its last change came from another session (graphRemote; SSE only). */
  remote: boolean
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

/**
 * The state line of a non-overview card, by priority: open blockers > live worker > gate-blocked >
 * stalled > ready > phase. A card with a live worker never reads as stalled.
 */
function stateOf(
  openBlockers: readonly string[],
  live: readonly GraphWorker[],
  block: GraphGateBlock | undefined,
  stalled: readonly string[] | undefined,
  ready: boolean,
  phase: () => string,
): { text: string; warn: boolean } {
  if (openBlockers.length > 0) return { text: `⊘ after ${openBlockers.join(', ')}`, warn: false }
  if (live.length > 0) return { text: workerText(live), warn: false }
  if (block !== undefined) return { text: `✗ gate: ${block.missing.length > 0 ? block.missing.join(', ') : (block.target ?? 'blocked')}`, warn: true }
  if (stalled !== undefined) return { text: `⚠ stalled${stalled.length > 0 ? ` · ${stalled.join(', ')}` : ''}`, warn: true }
  if (ready) return { text: 'ready', warn: false }

  return { text: phase(), warn: false }
}

export function cardsOf(
  view: GraphView,
  workers: Record<string, readonly GraphWorker[]> = {},
  changed: Record<string, number> = {},
  blocked: Record<string, GraphGateBlock> = {},
  remote: Record<string, number> = {},
): Model {
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
    // The overview shows no warnings (it reads no gates or transitions).
    const state = view.overview === true
      ? { text: rollup !== '' ? rollup : phaseText(node, undefined), warn: false }
      : stateOf(openBlockers, live, blocked[node.id], view.stalled?.[node.id], ready, () => phaseText(node, view.gates[node.id]))

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
      stateText: state.text,
      warn: state.warn,
      recent: changed[node.id] !== undefined,
      remote: remote[node.id] !== undefined,
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

/** The longest chain of open cards, as card ids in order, and its consecutive edges (`from|to`). */
export interface CriticalPath {
  ids: string[]
  edges: Set<string>
}

/**
 * The longest chain c1 -> ... -> ck of non-terminal cards where each blocks the next through an open
 * in-scope dependency (cycle back edges excluded); empty unless k >= 2. Length counts cards. Ties go to
 * the smaller plan number, then title, then id, both at each step and between end cards.
 */
export function criticalPath(cards: readonly Card[], steps: Steps): CriticalPath {
  const byId = new Map(cards.filter(c => c.role !== 'terminal').map(c => [c.id, c]))
  const better = (a: Card, b: Card): boolean => num(a.label) - num(b.label) < 0 || (num(a.label) === num(b.label) && (cmp(a.title, b.title) < 0 || (a.title === b.title && a.id < b.id)))
  // Steps are a topological order of the non-back edges: a blocker's step is below its target's.
  const order = [...byId.values()].sort((a, b) => (steps.step.get(a.id) ?? 0) - (steps.step.get(b.id) ?? 0))
  const len = new Map<string, number>()
  const prev = new Map<string, string>()
  for (const c of order) {
    let best: Card | undefined
    let bestLen = 0
    for (const d of c.deps) {
      const p = byId.get(d.id)
      if (!d.open || p === undefined || p.id === c.id || steps.back.has(`${p.id}|${c.id}`)) continue
      const l = len.get(p.id)
      if (l === undefined) continue
      if (l > bestLen || (l === bestLen && best !== undefined && better(p, best))) {
        best = p
        bestLen = l
      }
    }
    len.set(c.id, bestLen + 1)
    if (best !== undefined) prev.set(c.id, best.id)
  }
  let end: Card | undefined
  for (const c of order) {
    const l = len.get(c.id) as number
    const e = end === undefined ? 0 : (len.get(end.id) as number)
    if (end === undefined || l > e || (l === e && better(c, end))) end = c
  }
  if (end === undefined || (len.get(end.id) as number) < 2) return { ids: [], edges: new Set() }
  const ids: string[] = [end.id]
  for (let p = prev.get(end.id); p !== undefined; p = prev.get(p)) ids.unshift(p)
  const edges = new Set<string>()
  for (let i = 1; i < ids.length; i++) edges.add(`${ids[i - 1]}|${ids[i]}`)

  return { ids, edges }
}
