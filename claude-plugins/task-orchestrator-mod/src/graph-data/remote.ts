// Cross-session change marks (T12b 6a976f10, 087c6077). An SSE event that arrives while one of this
// session's TO writes is in flight, or within LOCAL_ECHO_MS after the last one ended, is our own echo;
// any other is from another session. The echo window alone decides (D4): a 4.0 event's `actor` is the
// transport principal, shared by every session on one credential (and `local` for every stdio session),
// so it cannot tell sessions apart. A remote event marks its item as changed elsewhere until a local
// write touches it again; a remote item.advanced in this project may also move the pane (follow).
//
// Marks are keyed by the item's 8-hex short id (the id every graph structure uses). The marks map is pure
// (same object when nothing changes, so the caller can skip the write); the in-flight window is module
// state, which a hot reload starts over (it then only misses an echo window).
import { envelopeOf, shortId } from '../shared/to-client.ts'

/** An SSE item event this soon after a local write ended still counts as that write's echo. */
export const LOCAL_ECHO_MS = 5_000
/** Most remote marks kept; the oldest go first. */
export const REMOTE_CAP = 200
/** Trailing debounce before the pane follows another session's transition. */
export const REMOTE_FOLLOW_DEBOUNCE_MS = 3_000
/** A follow is dropped when a local write is in flight or ended less than this long ago. */
export const REMOTE_FOLLOW_QUIET_MS = 30_000

/** This session's TO writes: how many are running, and when the last one ended (ms; null: never). */
export interface LocalWindow {
  inFlight: number
  lastEnd: number | null
}

/** Whether an event at `now` is this session's own echo under `w`. */
export function isLocalEcho(w: LocalWindow, now: number): boolean {
  return w.inFlight > 0 || (w.lastEnd !== null && now - w.lastEnd <= LOCAL_ECHO_MS)
}

let local: LocalWindow = { inFlight: 0, lastEnd: null }

/** A local TO write starts (before `next(e)` in the write-tool hook). */
export function beginLocal(): void {
  local = { inFlight: local.inFlight + 1, lastEnd: local.lastEnd }
}

/** A local TO write ended at `now` (in the hook's finally, success or not); null: no time could be read. */
export function endLocal(now: number | null): void {
  const lastEnd = now === null ? local.lastEnd : local.lastEnd === null ? now : Math.max(local.lastEnd, now)
  local = { inFlight: Math.max(0, local.inFlight - 1), lastEnd }
}

/**
 * Runs one local TO write inside the window: begun before `run`, ended at `now()` after it, even when it throws.
 * The window bookkeeping never changes the outcome: a failing `now()` (the clock goes away when the engine unloads
 * or reloads the module) still closes the window, and `run`'s result or error passes through untouched.
 */
export async function withLocalWrite<R>(run: () => Promise<R>, now: () => Promise<number>): Promise<R> {
  beginLocal()
  try {
    return await run()
  } finally {
    let at: number | null = null
    try {
      at = await now()
    } catch {
      // no clock: close the window without a new end time
    }
    endLocal(at)
  }
}

/** The current window (a copy). */
export function localWindow(): LocalWindow {
  return { ...local }
}

/** Forgets the window. For tests, which share this module across cases. */
export function resetRemoteState(): void {
  local = { inFlight: 0, lastEnd: null }
}

/** Marks `itemId` (by its short id) changed elsewhere at `now`. Same object when it is already marked; drops the oldest past REMOTE_CAP. */
export function markRemote(map: Record<string, number>, itemId: string, now: number): Record<string, number> {
  const key = shortId(itemId)
  if (map[key] !== undefined) return map
  const entries = Object.entries(map).sort((a, b) => a[1] - b[1])
  const keep = entries.slice(Math.max(0, entries.length - (REMOTE_CAP - 1)))

  return { ...Object.fromEntries(keep), [key]: now }
}

/**
 * Clears the marks of the ids a local write touched: an id of 8 or more characters by its short id, a
 * shorter (4+ char) prefix by prefix. Same object when nothing is removed.
 */
export function clearRemote(map: Record<string, number>, ids: readonly string[]): Record<string, number> {
  const wanted = ids.filter(id => typeof id === 'string' && id.length >= 4).map(id => (id.length >= 8 ? shortId(id) : id.toLowerCase()))
  const hit = (key: string): boolean => wanted.some(id => (id.length >= 8 ? key === id : key.startsWith(id)))
  const keys = Object.keys(map)
  if (!keys.some(hit)) return map

  return Object.fromEntries(keys.filter(k => !hit(k)).map(k => [k, map[k] as number]))
}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/** How an SSE event was attributed: local or remote, always by the echo window. */
export type Attribution = { origin: 'local' | 'remote'; by: 'window' }

/** Attributes an SSE event received at `now` by the echo window `w`; the event's actor is ignored (D4). */
export function attribute(_data: unknown, w: LocalWindow, now: number): Attribution {
  return { origin: isLocalEcho(w, now) ? 'local' : 'remote', by: 'window' }
}

/**
 * The item id a cross-session follow should move to: an `item.advanced` attributed remote, whose `rootId`
 * names the project root (compared by short id). Undefined for anything else.
 */
export function remoteFollowCandidate(name: string, data: unknown, a: Attribution, projectRootId: string | null): string | undefined {
  if (name !== 'item.advanced' || a.origin !== 'remote' || projectRootId === null) return undefined
  if (!isObj(data) || typeof data.rootId !== 'string' || data.rootId.length === 0 || shortId(data.rootId) !== shortId(projectRootId)) return undefined

  return eventItemId(data)
}

/**
 * The item ids a TO write result names (read from its envelope): each `data.results[i].itemId`, its
 * `cascades[].itemId` (advance) and `cascade[].itemId` (items_write). No envelope gives [].
 */
export function resultItemIds(result: unknown): string[] {
  const env = envelopeOf(result)
  const data = env !== null && isObj(env.data) ? env.data : {}
  const rows = Array.isArray(data.results) ? data.results : []
  const out: string[] = []
  for (const row of rows) {
    if (!isObj(row)) continue
    if (typeof row.itemId === 'string') out.push(row.itemId)
    for (const key of ['cascades', 'cascade']) {
      const list = row[key]
      if (Array.isArray(list)) for (const c of list) if (isObj(c) && typeof c.itemId === 'string') out.push(c.itemId)
    }
  }

  return out
}

/** The item id of a parsed SSE frame's data, when it names one. */
export function eventItemId(data: unknown): string | undefined {
  return isObj(data) && typeof data.itemId === 'string' && data.itemId.length > 0 ? data.itemId : undefined
}
