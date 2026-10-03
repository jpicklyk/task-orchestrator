// Pure model of the /to-graph pane: command arguments, the side list, scope titles and the
// read-only get_context summary (T3 a47dcd6f). No `$` here.
import type { GraphNode } from '../../types'
import { glyphOf, id8, kindOf, truncate } from './shared.ts'
import type { GraphView } from './shared.ts'

/** Most node Buttons the side list draws. */
export const SIDE_LIST_CAP = 40

export type ScopeArg = { kind: 'active' } | { kind: 'root' } | { kind: 'id'; id: string } | { kind: 'invalid'; text: string }

/** `/to-graph` arguments: nothing (the active feature), `root`, or an item id / 4+ hex prefix. */
export function parseScopeArg(args: string): ScopeArg {
  const text = args.trim()
  if (text === '') return { kind: 'active' }
  if (text.toLowerCase() === 'root') return { kind: 'root' }
  if (/^[0-9a-f]{4,}(-[0-9a-f]+)*$/i.test(text) && text.length <= 36) return { kind: 'id', id: text.toLowerCase() }

  return { kind: 'invalid', text }
}

const ORDER: Record<string, number> = { work: 0, review: 0, blocked: 0, queue: 1, terminal: 2 }

/** The nodes the side list offers: in-flight first (work/review/blocked), then queue, then terminal, capped. */
export function sideList(view: GraphView, cap = SIDE_LIST_CAP): GraphNode[] {
  const containers = new Set(view.nodes.map(n => n.parentId).filter((p): p is string => p !== null))

  return view.nodes
    .map((node, index) => ({ node, index }))
    .filter(({ node }) => !containers.has(node.id))
    .sort((a, b) => (ORDER[a.node.role] ?? 1) - (ORDER[b.node.role] ?? 1) || a.index - b.index)
    .slice(0, cap)
    .map(e => e.node)
}

export const sideLabel = (n: GraphNode): string => `${glyphOf(n)} ${id8(n.id)} ${truncate(n.title, 32)}`

/** Title for the pane header: the scope node's title, else the project root. */
export function scopeTitle(view: GraphView): string {
  if (view.scopeId === null) return 'Project root'
  const hit = view.nodes.find(n => n.id === view.scopeId)

  return hit?.title ?? view.nodes.find(n => n.parentId === null)?.title ?? id8(view.scopeId)
}

/** One-line counts by kind, e.g. `12 items: 3 work, 1 blocked`. */
export function summaryLine(view: GraphView): string {
  const counts: Record<string, number> = {}
  for (const n of view.nodes) counts[kindOf(n)] = (counts[kindOf(n)] ?? 0) + 1
  const parts = Object.entries(counts).map(([k, c]) => `${c} ${k}`)

  return `${view.nodes.length} items${parts.length > 0 ? `: ${parts.join(', ')}` : ''}`
}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/** A get_context (item mode) result as plain lines: role, whether it can advance, missing notes, claim. */
export function formatDetail(ctx: unknown): string[] {
  if (!isObj(ctx)) return ['No detail available.']
  const item = isObj(ctx.item) ? ctx.item : {}
  const gate = isObj(ctx.gateStatus) ? ctx.gateStatus : {}
  const lines: string[] = []
  const title = typeof item.title === 'string' ? item.title : undefined
  const id = typeof item.id === 'string' ? item.id : undefined
  lines.push(`${title ?? id ?? 'item'}${id !== undefined ? ` [${id8(id)}]` : ''}`)
  lines.push(`role: ${typeof item.role === 'string' ? item.role : 'unknown'}${typeof gate.phase === 'string' ? `  phase: ${gate.phase}` : ''}`)
  if (typeof gate.canAdvance === 'boolean') lines.push(`can advance: ${gate.canAdvance ? 'yes' : 'no'}`)
  const missing = Array.isArray(gate.missing)
    ? gate.missing.map(m => (typeof m === 'string' ? m : isObj(m) && typeof m.key === 'string' ? m.key : JSON.stringify(m)))
    : []
  lines.push(missing.length > 0 ? `missing notes: ${missing.join(', ')}` : 'missing notes: none')

  const claim = isObj(ctx.claim) ? ctx.claim : isObj(item.claim) ? item.claim : isObj(ctx.claimDetail) ? ctx.claimDetail : { ...item, ...ctx }
  const claimedBy = claim.claimedBy
  if (claimedBy !== undefined && claimedBy !== null) {
    const who = isObj(claimedBy) ? String(claimedBy.id ?? JSON.stringify(claimedBy)) : String(claimedBy)
    const expires = typeof claim.claimExpiresAt === 'string' ? `, expires ${claim.claimExpiresAt}` : ''
    lines.push(`claimed by ${who}${claim.isExpired === true ? ' (expired)' : ''}${expires}`)
  } else {
    lines.push('claim: none')
  }

  return lines
}
