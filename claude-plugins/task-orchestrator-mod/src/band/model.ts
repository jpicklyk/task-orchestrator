// Pure selection and text for the band and the status line (T4 ec1e2f91). No `$`, no atoms: the
// hooks in index.ts feed it a snapshot and draw what it returns.
import type { GateInfo, GraphNode, GraphSnapshot } from '../../types'
import { cardsOf, num } from '../graph-pane/model.ts'

export interface BandModel {
  /** The band's line; absent when hidden or nothing is in flight. */
  band?: string
  /** The compact status-line text; absent when nothing is in flight (clears the line). */
  status?: string
}

/** Columns the Hide button and its spacing take beside the band text. */
const BUTTON_COLUMNS = 10
const MIN_TITLE = 8

const ACTIVE = new Set(['work', 'review'])

/** The in-flight leaves, in the order the band picks from: work before review, deeper first, snapshot order. */
export function inFlight(snapshot: GraphSnapshot | null): GraphNode[] {
  if (!snapshot) return []
  const active = snapshot.nodes.filter(node => ACTIVE.has(node.role))
  const byId = new Map(snapshot.nodes.map(node => [node.id, node]))
  // A node is a container when an active descendant exists: mark every ancestor of every active node.
  const containers = new Set<string>()
  for (const node of active) {
    let parent = node.parentId === null ? undefined : byId.get(node.parentId)
    for (let guard = 0; parent && guard < 1000; guard += 1) {
      containers.add(parent.id)
      parent = parent.parentId === null ? undefined : byId.get(parent.parentId)
    }
  }
  const rank = (node: GraphNode): number => (node.role === 'work' ? 0 : 1)

  return active
    .map((node, index) => ({ node, index }))
    .filter(({ node }) => !containers.has(node.id))
    .sort((a, b) => rank(a.node) - rank(b.node) || b.node.depth - a.node.depth || a.index - b.index)
    .map(({ node }) => node)
}

/** `2/3 work notes`, `work` (nothing required), or the role (no gate entry); ` ✓` when it can advance. */
export function gateText(node: GraphNode, gate: GateInfo | undefined): string {
  if (!gate) return node.role
  const base = gate.required > 0 ? `${gate.filled}/${gate.required} ${gate.phase} notes` : gate.phase

  return gate.canAdvance ? `${base} ✓` : base
}

/** `title` cut to `room` characters with a trailing ellipsis. */
export function truncate(title: string, room: number): string {
  if (title.length <= room) return title
  if (room <= 1) return '…'

  return `${title.slice(0, room - 1)}…`
}

/** Most labels shown per segment of the smart line; the rest read ` +k`. */
export const SEGMENT_MAX = 3

const byPlanNumber = (a: string, b: string): number => num(a) - num(b) || (a < b ? -1 : a > b ? 1 : 0)

function segment(name: string, labels: string[]): string | undefined {
  if (labels.length === 0) return undefined
  const sorted = [...labels].sort(byPlanNumber)
  const more = sorted.length > SEGMENT_MAX ? ` +${sorted.length - SEGMENT_MAX}` : ''

  return `${name}: ${sorted.slice(0, SEGMENT_MAX).join(', ')}${more}`
}

/**
 * The band line while the graph is scoped to an item (D1 a): `◉ <id8> · <d>/<n> done · ready: … · waiting: …`.
 * n counts the scope's cards (not the scope itself), d the terminal ones (cancelled counts as done);
 * ready are queue cards with every blocker satisfied, waiting the open cards with an open blocker.
 * Undefined on the root overview, with no snapshot, or with no cards (the in-flight line then shows).
 */
export function smartLine(snapshot: GraphSnapshot | null, bodyColumns = 80): string | undefined {
  if (!snapshot || snapshot.overview === true || snapshot.scopeId === null) return undefined
  const { cards } = cardsOf(snapshot)
  if (cards.length === 0) return undefined
  const done = cards.filter(c => c.role === 'terminal').length
  const parts = [
    `◉ ${snapshot.scopeId.slice(0, 8)}`,
    `${done}/${cards.length} done`,
    segment('ready', cards.filter(c => c.ready).map(c => c.label)),
    segment('waiting', cards.filter(c => c.role !== 'terminal' && c.openBlockers.length > 0).map(c => c.label)),
  ].filter((p): p is string => p !== undefined)

  return truncate(parts.join(' · '), Math.max(MIN_TITLE, bodyColumns - BUTTON_COLUMNS))
}

/** What the band draws: the smart line when scoped to an item, else the in-flight line (undefined: nothing). */
export function bandLine(snapshot: GraphSnapshot | null, bodyColumns = 80): string | undefined {
  return smartLine(snapshot, bodyColumns) ?? bandModel(snapshot, false, bodyColumns).band
}

export function bandModel(snapshot: GraphSnapshot | null, hidden: boolean, bodyColumns = 80): BandModel {
  const list = inFlight(snapshot)
  const first = list[0]
  if (!snapshot || !first) return {}

  const id8 = first.id.slice(0, 8)
  const more = list.length > 1 ? ` (+${list.length - 1})` : ''
  const gate = gateText(first, snapshot.gates[first.id])
  const status = `TO ◉ ${id8} ${gate.replace(' notes', '')}${more}`
  if (hidden) return { status }

  const fixed = `◉ ${id8}  — ${gate}${more}`.length
  const room = Math.max(MIN_TITLE, bodyColumns - BUTTON_COLUMNS - fixed)

  return { band: `◉ ${id8} ${truncate(first.title, room)} — ${gate}${more}`, status }
}
