// What changed between two snapshots of the same scope, as toast lines (T12b 6a976f10). Pure: the
// hooks module (index.ts) calls it from graphIo.setSnapshot after the no-op guard, with the previous
// snapshot it already read, and shows the lines only when the `graphToasts` option is on.
import type { GraphNode, GraphSnapshot } from '../../types'
import { isOpen } from '../graph-pane/model.ts'
import type { GateBlockRow } from '../graph-pane/activity.ts'

/** At most this many toasts per snapshot; the rest fold into one `+N more graph changes`. */
export const MAX_TOASTS = 3
/** Titles in a toast are cut to this many characters. */
export const TOAST_TITLE_MAX = 60

export type GraphEventKind = 'done' | 'ready' | 'back'

export interface GraphEvent {
  kind: GraphEventKind
  id: string
  label: string
  title: string
}

const labelOf = (n: { id: string; planLabel?: string }): string => n.planLabel ?? n.id.slice(0, 8)

/** Queue nodes whose every in-scope or external BLOCKS blocker is satisfied (the pane's `ready`). */
export function readyIds(s: GraphSnapshot): Set<string> {
  const byId = new Map(s.nodes.map(n => [n.id, n]))
  const out = new Set<string>()
  for (const n of s.nodes) {
    if (n.role !== 'queue') continue
    const blocked = s.edges.some(e => {
      if (e.type !== 'BLOCKS' || e.to !== n.id || e.from === n.id) return false
      const role = byId.get(e.from)?.role ?? s.external[e.from]?.role

      return role !== undefined && isOpen(role, e.unblockAt)
    })
    if (!blocked) out.add(n.id)
  }

  return out
}

/**
 * The transitions from `prev` to `next` worth a toast, in `next`'s node order: a node reaching terminal
 * (not cancelled), a queue node becoming ready, a review node sent back to work. None unless both are
 * snapshots of the same scope, neither is the overview and neither is partial (an `error` can make
 * blockers vanish and fake a `ready`). Nodes new in `next` raise nothing.
 */
export function snapshotEvents(prev: GraphSnapshot | null, next: GraphSnapshot): GraphEvent[] {
  if (prev === null || prev.scopeId === null || prev.scopeId !== next.scopeId) return []
  if (prev.overview === true || next.overview === true) return []
  if (prev.error !== undefined || next.error !== undefined) return []
  const before = new Map(prev.nodes.map(n => [n.id, n]))
  const wasReady = readyIds(prev)
  const isReady = readyIds(next)
  const out: GraphEvent[] = []
  const push = (kind: GraphEventKind, n: GraphNode): void => void out.push({ kind, id: n.id, label: labelOf(n), title: n.title })
  for (const n of next.nodes) {
    const p = before.get(n.id)
    if (p === undefined) continue
    if (p.role !== 'terminal' && n.role === 'terminal') {
      if (n.statusLabel !== 'cancelled') push('done', n)
    } else if (p.role === 'queue' && n.role === 'queue' && !wasReady.has(n.id) && isReady.has(n.id)) {
      push('ready', n)
    } else if (p.role === 'review' && n.role === 'work') {
      push('back', n)
    }
  }

  return out
}

const cutTitle = (t: string): string => (t.length <= TOAST_TITLE_MAX ? t : `${t.slice(0, TOAST_TITLE_MAX - 1)}…`)

const PREFIX: Record<GraphEventKind, string> = { done: '✓ Done', ready: '○ Ready', back: '↺ Back to work' }

/** At most MAX_TOASTS lines, then one `+N more graph changes` line for the rest. */
export function capToasts(lines: readonly string[]): string[] {
  if (lines.length <= MAX_TOASTS) return [...lines]

  return [...lines.slice(0, MAX_TOASTS), `+${lines.length - MAX_TOASTS} more graph changes`]
}

/** One line per event (`✓ Done: T2 Title`), capped by capToasts. */
export function toastLines(events: readonly GraphEvent[]): string[] {
  return capToasts(events.map(e => `${PREFIX[e.kind]}: ${e.label} ${cutTitle(e.title)}`))
}

/**
 * `✗ Gate blocked: <label|id8> -> <target>: <missing keys>` per gate-blocked row, capped. The label comes
 * from the snapshot when the item is in it (exact id or a unique 4+ char prefix), else the id's first 8.
 */
export function gateToastLines(rows: readonly GateBlockRow[], snap: GraphSnapshot | null): string[] {
  const nodes = snap?.nodes ?? []
  const lines = rows.map(r => {
    const exact = nodes.find(n => n.id === r.itemId)
    const prefixed = r.itemId.length >= 4 ? nodes.filter(n => n.id.startsWith(r.itemId)) : []
    const node = exact ?? (prefixed.length === 1 ? prefixed[0] : undefined)
    const label = node !== undefined ? labelOf(node) : r.itemId.slice(0, 8)
    const target = r.targetRole !== undefined ? ` -> ${r.targetRole}` : ''

    return `✗ Gate blocked: ${label}${target}: ${r.missing.length > 0 ? r.missing.join(', ') : 'required notes missing'}`
  })

  return capToasts(lines)
}
