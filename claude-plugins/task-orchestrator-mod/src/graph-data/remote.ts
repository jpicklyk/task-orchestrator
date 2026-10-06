// Cross-session change marks (T12b 6a976f10, 087c6077). An SSE item event carries the writer's `actor`
// {id, kind, parent?} when the server sends one: the event is this session's own when the actor id (or its
// parent) is one this session wrote with (the local actor set), else it came from another session. Only when
// the event has no actor (an older server, redaction, an actorless write) does the old time window decide:
// an event that arrives while one of this session's TO writes is in flight, or within LOCAL_ECHO_MS after
// the last one ended, is our own echo. A remote event marks its item as changed elsewhere until a local
// write touches it again; a remote-by-actor item.advanced in this project may also move the pane (follow).
//
// The marks map is pure (same object when nothing changes, so the caller can skip the write); the
// in-flight window is module state, which a hot reload starts over (it then only misses an echo window).

/** An SSE item event this soon after a local write ended still counts as that write's echo. */
export const LOCAL_ECHO_MS = 5_000
/** Most remote marks kept; the oldest go first. */
export const REMOTE_CAP = 200
/** Most local actor ids kept; the oldest go first. */
export const LOCAL_ACTOR_CAP = 200
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
let localActors: string[] = []

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
  localActors = []
}

/** Remembers the actor ids this session writes with (before the call runs: an echo can beat its result). Oldest dropped past LOCAL_ACTOR_CAP. */
export function rememberLocalActors(ids: readonly string[]): void {
  const next = localActors.slice()
  for (const id of ids) {
    if (typeof id !== 'string' || id.length === 0 || next.includes(id)) continue
    next.push(id)
  }
  localActors = next.length > LOCAL_ACTOR_CAP ? next.slice(next.length - LOCAL_ACTOR_CAP) : next
}

/** The remembered local actor ids (a copy), oldest first. */
export function localActorIds(): string[] {
  return localActors.slice()
}

/** Marks `itemId` changed elsewhere at `now`. Same object when it is already marked; drops the oldest past REMOTE_CAP. */
export function markRemote(map: Record<string, number>, itemId: string, now: number): Record<string, number> {
  if (map[itemId] !== undefined) return map
  const entries = Object.entries(map).sort((a, b) => a[1] - b[1])
  const keep = entries.slice(Math.max(0, entries.length - (REMOTE_CAP - 1)))

  return { ...Object.fromEntries(keep), [itemId]: now }
}

/**
 * Clears the marks of the ids a local write touched: exact ids, and ids a 4+ char prefix names (a call
 * may spell a prefix). Same object when nothing is removed.
 */
export function clearRemote(map: Record<string, number>, ids: readonly string[]): Record<string, number> {
  const hit = (key: string): boolean => ids.some(id => key === id || (id.length >= 4 && key.startsWith(id)))
  const keys = Object.keys(map)
  if (!keys.some(hit)) return map

  return Object.fromEntries(keys.filter(k => !hit(k)).map(k => [k, map[k] as number]))
}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * The actor ids a TO write call's input names: its top-level `actor.id`, then the `actor.id` of every object
 * element of every top-level array field, with or without an `itemId` (a create element has none). Deduped,
 * first-seen order; anything that is not a non-empty string id is ignored.
 */
export function actorIdsOf(input: unknown): string[] {
  if (!isObj(input)) return []
  const out: string[] = []
  const add = (v: unknown): void => {
    if (isObj(v) && isObj(v.actor) && typeof v.actor.id === 'string' && v.actor.id.length > 0 && !out.includes(v.actor.id)) out.push(v.actor.id)
  }
  add(input)
  for (const value of Object.values(input)) if (Array.isArray(value)) for (const el of value) add(el)

  return out
}

/** How an SSE event was attributed: local or remote, and whether the actor or the echo window decided. */
export type Attribution = { origin: 'local' | 'remote'; by: 'actor' | 'window' }

/**
 * Attributes an SSE event's data. With a usable `actor` (an object with a non-empty string id) it is local iff
 * the actor id or its `parent` is in `actors` (exact, case-sensitive); the window is not consulted. Without one
 * the echo window decides.
 */
export function attribute(data: unknown, w: LocalWindow, actors: readonly string[], now: number): Attribution {
  const actor = isObj(data) && isObj(data.actor) ? data.actor : undefined
  if (actor !== undefined && typeof actor.id === 'string' && actor.id.length > 0) {
    const mine = actors.includes(actor.id) || (typeof actor.parent === 'string' && actors.includes(actor.parent))

    return { origin: mine ? 'local' : 'remote', by: 'actor' }
  }

  return { origin: isLocalEcho(w, now) ? 'local' : 'remote', by: 'window' }
}

/**
 * The item id a cross-session follow should move to: an `item.advanced` attributed remote by actor, whose
 * `rootId` equals the project root (case-insensitive). Undefined for anything else.
 */
export function remoteFollowCandidate(name: string, data: unknown, a: Attribution, projectRootId: string | null): string | undefined {
  if (name !== 'item.advanced' || a.origin !== 'remote' || a.by !== 'actor' || projectRootId === null) return undefined
  if (!isObj(data) || typeof data.rootId !== 'string' || data.rootId.toLowerCase() !== projectRootId.toLowerCase()) return undefined

  return eventItemId(data)
}

/** The item ids a TO write result names: each row's `itemId` and its `cascadeEvents[].itemId`. Bad JSON gives []. */
export function resultItemIds(text: unknown): string[] {
  if (typeof text !== 'string') return []
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return []
  }
  const rows = isObj(parsed) && Array.isArray(parsed.results) ? parsed.results : []
  const out: string[] = []
  for (const row of rows) {
    if (!isObj(row)) continue
    if (typeof row.itemId === 'string') out.push(row.itemId)
    if (Array.isArray(row.cascadeEvents)) for (const c of row.cascadeEvents) if (isObj(c) && typeof c.itemId === 'string') out.push(c.itemId)
  }

  return out
}

/** The item id of a parsed SSE frame's data, when it names one. */
export function eventItemId(data: unknown): string | undefined {
  return isObj(data) && typeof data.itemId === 'string' && data.itemId.length > 0 ? data.itemId : undefined
}
