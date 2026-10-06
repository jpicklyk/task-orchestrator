// Pure model of the /to-graph pane: command arguments, scope titles and the read-only detail
// lines (T3 a47dcd6f). No `$` here.
import type { GraphSnapshot, GraphStatus } from '../../types'
import { id8, kindOf } from './shared.ts'
import type { GraphView } from './shared.ts'

export type ScopeArg = { kind: 'active' } | { kind: 'root' } | { kind: 'id'; id: string } | { kind: 'invalid'; text: string }

/** `/to-graph` arguments: nothing (the active feature), `root`, or an item id / 4+ hex prefix. */
export function parseScopeArg(args: string): ScopeArg {
  const text = args.trim()
  if (text === '') return { kind: 'active' }
  if (text.toLowerCase() === 'root') return { kind: 'root' }
  if (/^[0-9a-f]{4,}(-[0-9a-f]+)*$/i.test(text) && text.length <= 36) return { kind: 'id', id: text.toLowerCase() }

  return { kind: 'invalid', text }
}

/** Whether the live feed is not healthy: no SSE stream, or the latest refresh failed. Reconnect shows only then. */
export const isDegraded = (status: GraphStatus): boolean => status.liveSource !== 'sse' || status.lastError !== undefined

/** Title for the pane header: the scope node's title, else the project root. */
export function scopeTitle(view: GraphView): string {
  if (view.scopeId === null) return 'Whole project'
  const hit = view.nodes.find(n => n.id === view.scopeId)

  return hit?.title ?? view.nodes.find(n => n.parentId === null)?.title ?? id8(view.scopeId)
}

/** Breadcrumb for the pane header: `Project: <title>` at the root, `Feature: <title>` on a feature, else `Scope: <title>`. */
export function scopeHeader(view: GraphView): string {
  if (view.scopeId === null) return 'Whole project'
  const hit = view.nodes.find(n => n.id === view.scopeId)
  const title = scopeTitle(view)
  if (view.scopeId === view.rootId) return `Project: ${title}`

  return `${hit?.type === 'feature-implementation' ? 'Feature' : 'Scope'}: ${title}`
}

/** The scope id the pane is asked for: the atom, with null (the project root) read as the snapshot's root id. */
export const wantedScope = (scope: string | null, snap: GraphSnapshot | null): string | null => scope ?? snap?.rootId ?? null

/** Whether the loaded snapshot is for another scope than the one asked for (a switch is still loading). */
export function isSwitching(scope: string | null, snap: GraphSnapshot | null): boolean {
  const want = wantedScope(scope, snap)

  return snap !== null && want !== null && snap.scopeId !== want
}

/** What the pane says it is loading: the wanted scope's title when the loaded snapshot knows it, else its short id. */
export function loadingTitle(scope: string | null, snap: GraphSnapshot | null): string {
  const want = wantedScope(scope, snap) ?? ''

  return snap?.nodes.find(n => n.id === want)?.title ?? id8(want)
}

/** One-line counts by kind, e.g. `12 items: 3 work, 1 blocked`. */
export function summaryLine(view: GraphView): string {
  const counts: Record<string, number> = {}
  const rootNode = view.nodes.find(n => n.id === view.scopeId) ?? view.nodes.find(n => n.parentId === null)
  const children = view.nodes.filter(n => n.id !== rootNode?.id)
  for (const n of children) counts[kindOf(n)] = (counts[kindOf(n)] ?? 0) + 1
  const parts = Object.entries(counts).map(([k, c]) => `${c} ${k}`)

  return `${children.length} items${parts.length > 0 ? `: ${parts.join(', ')}` : ''}`
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

  lines.push(claimLine(ctx, item))

  return lines
}

/** The claim line of a get_context result: `claimed by <who>[ (expired)][, expires <t>]` or `claim: none`. */
function claimLine(ctx: Obj, item: Obj): string {
  const claim = isObj(ctx.claim) ? ctx.claim : isObj(item.claim) ? item.claim : isObj(ctx.claimDetail) ? ctx.claimDetail : { ...item, ...ctx }
  const claimedBy = claim.claimedBy
  if (claimedBy === undefined || claimedBy === null) return 'claim: none'
  const who = isObj(claimedBy) ? String(claimedBy.id ?? JSON.stringify(claimedBy)) : String(claimedBy)
  const expires = typeof claim.claimExpiresAt === 'string' ? `, expires ${claim.claimExpiresAt}` : ''

  return `claimed by ${who}${claim.isExpired === true ? ' (expired)' : ''}${expires}`
}

/** `Xd Yh` / `Xh Ym` / `Xm`. */
export function duration(ms: number): string {
  const m = Math.max(0, Math.floor(ms / 60000))
  if (m >= 1440) return `${Math.floor(m / 1440)}d ${Math.floor((m % 1440) / 60)}h`
  if (m >= 60) return `${Math.floor(m / 60)}h ${m % 60}m`

  return `${m}m`
}

export interface DetailInput {
  itemId: string
  /** The get_context result (item mode). */
  ctx: unknown
  /** The query_items get result, when it could be read. */
  item?: unknown
  /** The query_notes list result (bodies not needed), when it could be read. */
  notes?: unknown
  now: number
  itemFailed?: boolean
  notesFailed?: boolean
}

/**
 * The click-through detail: title and type, phase and time in it, summary, one line per required note of
 * the current phase with its author, can-advance and the claim. Read-only data in, plain lines out.
 */
export function detailLines(i: DetailInput): string[] {
  const ctx = isObj(i.ctx) ? i.ctx : {}
  const ctxItem = isObj(ctx.item) ? ctx.item : {}
  const raw = isObj(i.item) ? i.item : {}
  const item = isObj(raw.item) ? raw.item : raw
  const gate = isObj(ctx.gateStatus) ? ctx.gateStatus : {}
  const lines: string[] = []
  const title = typeof item.title === 'string' ? item.title : typeof ctxItem.title === 'string' ? ctxItem.title : i.itemId
  const type = typeof item.type === 'string' ? item.type : typeof ctxItem.type === 'string' ? ctxItem.type : undefined
  lines.push(`${title} [${id8(i.itemId)}]${type !== undefined ? ` · ${type}` : ''}`)

  const phase = typeof gate.phase === 'string' ? gate.phase : typeof ctxItem.role === 'string' ? ctxItem.role : typeof item.role === 'string' ? item.role : 'unknown'
  const changedAt = typeof item.roleChangedAt === 'string' ? Date.parse(item.roleChangedAt) : Number.NaN
  lines.push(`phase: ${phase}${Number.isFinite(changedAt) ? ` · in phase ${duration(i.now - changedAt)}` : ''}`)

  if (typeof item.summary === 'string' && item.summary.length > 0) lines.push(item.summary.length > 600 ? `${item.summary.slice(0, 599)}…` : item.summary)
  else if (i.itemFailed === true) lines.push('(summary unavailable)')

  const authors = new Map<string, string>()
  const noteList = isObj(i.notes) && Array.isArray(i.notes.notes) ? i.notes.notes : []
  for (const n of noteList) {
    if (isObj(n) && typeof n.key === 'string' && isObj(n.actor) && typeof n.actor.id === 'string') authors.set(n.key, n.actor.id)
  }
  const rows = Array.isArray(ctx.schema) ? ctx.schema.filter(isObj) : []
  for (const row of rows) {
    if (row.role !== phase || row.required !== true || typeof row.key !== 'string') continue
    const seat = typeof row.seat === 'string' && row.seat !== '' ? row.seat : 'other'
    if (row.filled !== true) lines.push(`✗ ${row.key} · ${seat} · missing`)
    else {
      const by = authors.get(row.key)
      lines.push(`✓ ${row.key} · ${seat}${by !== undefined ? ` · by ${by}` : ''}`)
    }
  }
  if (i.notesFailed === true) lines.push('(authors unavailable)')

  if (typeof gate.canAdvance === 'boolean') lines.push(`can advance: ${gate.canAdvance ? 'yes' : 'no'}`)
  lines.push(claimLine(ctx, ctxItem))

  return lines
}

/** A parsed read-only TO call, closed over `$` in index.ts so this module stays `$`-free. */
export type ToolCall = (tool: string, args: Record<string, unknown>) => Promise<unknown>

/** How far back the default-scope read looks for the latest transition under the root. */
export const SCOPE_LOOKBACK_MS = 14 * 24 * 60 * 60 * 1000
const SCOPE_TRANSITION_LIMIT = 200

const idsOf = (v: unknown): string[] => (Array.isArray(v) ? v.filter(isObj).flatMap(a => (typeof a.id === 'string' ? [a.id] : [])) : [])

/**
 * The scope `/to-graph` opens with no argument: the owning scope of the most recent transition under
 * `rootId`. One session-resume read; the first transition (newest first) whose item is still active
 * wins, else the newest transition's item if the root is among its ancestors (transitions are not
 * scoped by the server). The owning scope is the nearest feature-implementation walking up from that
 * item; a container or project stops the walk and the last non-container seen is returned (a bug
 * under a Bugs container is its own scope). Returns null when nothing usable was found; a read that
 * throws propagates, so the caller falls back to the legacy rule on either.
 */
export async function activeScope(call: ToolCall, rootId: string, now: number): Promise<string | null> {
  const resume = await call('get_context', {
    mode: 'session-resume',
    since: new Date(now - SCOPE_LOOKBACK_MS).toISOString(),
    ancestorId: rootId,
    includeAncestors: true,
    limit: SCOPE_TRANSITION_LIMIT,
  })
  if (!isObj(resume)) return null
  const moves = (Array.isArray(resume.recentTransitions) ? resume.recentTransitions : []).filter(isObj).flatMap(t => (typeof t.itemId === 'string' ? [t.itemId] : []))
  const active = new Map<string, string[]>()
  for (const a of Array.isArray(resume.activeItems) ? resume.activeItems.filter(isObj) : []) {
    if (typeof a.id === 'string') active.set(a.id, idsOf(a.ancestors))
  }
  let start: string | undefined = moves.find(id => active.has(id))
  let ancestors = start === undefined ? [] : (active.get(start) ?? [])
  if (start === undefined) {
    const newest = moves[0]
    if (newest === undefined) return null
    const item = await call('query_items', { operation: 'get', itemId: newest, includeAncestors: true })
    if (!isObj(item)) return null
    ancestors = idsOf(item.ancestors)
    if (!ancestors.includes(rootId)) return null
    start = newest
  }
  // Nearest-first chain: the item itself, then its ancestors up to (not past) the root.
  const chain = [start, ...[...ancestors].reverse()]
  let last = start
  for (const id of chain) {
    if (id === rootId) return last
    const node = await call('query_items', { operation: 'get', itemId: id })
    const type = isObj(node) && typeof node.type === 'string' ? node.type : undefined
    if (type === 'feature-implementation') return id
    if (type === 'container' || type === 'project') return last
    last = id
  }

  return last
}
