// Small pure helpers shared by the /to-graph model, layout and renderers (T3 a47dcd6f).
import type { GateInfo, GraphSnapshot } from '../../types'

/** What the pane draws: a snapshot (the project-root overview or a subtree). */
export type GraphView = GraphSnapshot

/** The role a node reads as: a terminal node labelled `cancelled` is its own dim kind. */
export type Kind = 'queue' | 'work' | 'review' | 'blocked' | 'terminal' | 'cancelled'

export const id8 = (id: string): string => id.slice(0, 8)

export function kindOf(node: { role: string; statusLabel?: string }): Kind {
  if (node.role === 'terminal') return node.statusLabel === 'cancelled' ? 'cancelled' : 'terminal'
  if (node.role === 'work' || node.role === 'review' || node.role === 'blocked') return node.role

  return 'queue'
}

/** Fill colour of each kind (the approved probe's palette), shared by the boxes and the legend. */
export const KIND: Record<Kind, string> = {
  queue: '#6b7280',
  work: '#c2410c',
  review: '#1d4ed8',
  blocked: '#b91c1c',
  terminal: '#15803d',
  cancelled: '#4b5563',
}

/** Fill of a queue item whose blockers are all satisfied: it can be picked up now. */
export const READY = '#0f766e'

const GLYPHS: Record<Kind, string> = { queue: '○', work: '◉', review: '◉', blocked: '⊘', terminal: '✓', cancelled: '—' }
export const glyphOf = (node: { role: string; statusLabel?: string }): string => GLYPHS[kindOf(node)]

/** `2/3 work notes` for a node with a gate entry that has required notes; '' otherwise. */
export function gateSuffix(gate: GateInfo | undefined): string {
  return gate !== undefined && gate.required > 0 ? `${gate.filled}/${gate.required} ${gate.phase} notes` : ''
}

const PHASE_WORD: Record<Kind, string> = { queue: 'queue', work: 'work', review: 'review', blocked: 'blocked', terminal: 'done', cancelled: 'cancelled' }

/** The phase line of a node: `queue`, `done`, or for work/review the seat progress, e.g. `work · implementer ✓, orchestrator 0/1`. */
export function phaseText(node: { role: string; statusLabel?: string }, gate: GateInfo | undefined): string {
  const kind = kindOf(node)
  if (kind !== 'work' && kind !== 'review') return PHASE_WORD[kind]
  if (gate !== undefined && gate.seats !== undefined && gate.seats.length > 0) {
    return `${kind} · ${gate.seats.map(s => (s.filled >= s.required ? `${s.seat} ✓` : `${s.seat} ${s.filled}/${s.required}`)).join(', ')}`
  }
  if (gate !== undefined && gate.required > 0) return `${kind} · ${gate.filled}/${gate.required} notes`

  return kind
}

/** One legend entry per kind: glyph, phase word and the kind's colour. */
export function legendItems(): { key: Kind; text: string; color: string }[] {
  return (Object.keys(GLYPHS) as Kind[]).map(key => ({ key, text: `${GLYPHS[key]} ${PHASE_WORD[key]}`, color: KIND[key] }))
}

export function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, Math.max(0, max - 1))}…`
}

const ROLLUP_ORDER: readonly [string, string][] = [
  ['work', 'work'],
  ['review', 'review'],
  ['blocked', 'blocked'],
  ['queue', 'queue'],
  ['terminal', 'done'],
]

/** The roll-up line of an overview box, e.g. `9 work · 4 review · 63 done`; zero counts are left out, '' when none. */
export function rollupLine(counts: Record<string, number> | undefined): string {
  if (counts === undefined) return ''

  return ROLLUP_ORDER.filter(([role]) => (counts[role] ?? 0) > 0)
    .map(([role, word]) => `${counts[role]} ${word}`)
    .join(' · ')
}
