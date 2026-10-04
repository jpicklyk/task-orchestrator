// Debounced, single-flight refresh of the graph atoms. Module state is per load: a hot reload
// starts it over, which only loses a pending debounce.
import type { GraphSnapshot, GraphStatus } from '../../types'
import type { GraphIo } from './io.ts'
import { snapshot } from './snapshot.ts'

export const DEBOUNCE_MS = 300

let timer: { cancel(): void } | null = null
let running = false
/** A refresh was requested while one ran: run again right after, after this delay. */
let rerunAfter: number | null = null
/** undefined: no explicit scope requested, read the atom. */
let pendingScope: string | null | undefined
let waiters: Array<() => void> = []

function schedule(io: GraphIo, ms: number): void {
  if (running) {
    rerunAfter = rerunAfter === null ? ms : Math.min(rerunAfter, ms)

    return
  }
  timer?.cancel()
  timer = io.after(ms, () => {
    timer = null
    void run(io)
  })
}

async function run(io: GraphIo): Promise<void> {
  running = true
  rerunAfter = null
  const batch = waiters
  waiters = []
  const requested = pendingScope
  pendingScope = undefined
  try {
    const scope = requested !== undefined ? requested : await io.readScope()
    const snap = await snapshot(io, scope)
    await io.setSnapshot(snap)
    await io.updateStatus(({ liveSource }) => ({ refreshing: false, liveSource, ...(snap.error !== undefined && { lastError: snap.error }) }))
  } catch (err) {
    const lastError = err instanceof Error ? err.message : String(err)
    try {
      await io.updateStatus(({ liveSource }) => ({ refreshing: false, liveSource, lastError }))
    } catch {
      // state itself failed; nothing more to record
    }
  } finally {
    running = false
    for (const resolve of batch) resolve()
    if (rerunAfter !== null) schedule(io, rerunAfter)
  }
}

/**
 * Re-snapshots after a quiet DEBOUNCE_MS: calls inside the window collapse into one run, and a call
 * during a run queues exactly one more. Resolves when the run it joined has finished; never rejects.
 * `scopeId` overrides the `graphScope` atom for that run (null: the project root).
 */
export function refresh(io: GraphIo, scopeId?: string | null): Promise<void> {
  if (scopeId !== undefined) pendingScope = scopeId
  const done = new Promise<void>(resolve => waiters.push(resolve))
  schedule(io, DEBOUNCE_MS)

  return done
}

/** Like refresh but without the debounce window (a `sync.lost`, a scope change). */
export function refreshNow(io: GraphIo, scopeId?: string | null): Promise<void> {
  if (scopeId !== undefined) pendingScope = scopeId
  const done = new Promise<void>(resolve => waiters.push(resolve))
  schedule(io, 0)

  return done
}

/** Drops pending timers and queues. For tests, which share this module across cases. */
export function resetRefreshState(): void {
  timer?.cancel()
  timer = null
  running = false
  rerunAfter = null
  pendingScope = undefined
  waiters = []
}

/**
 * Whether two snapshots draw the same graph: everything but `takenAt`, which differs on every run.
 * A write of an unchanged snapshot would redraw every reader (and flash the pane's Svg frame).
 */
export function sameSnapshot(a: GraphSnapshot | null, b: GraphSnapshot): boolean {
  if (a === null) return false
  const { takenAt: _a, ...restA } = a
  const { takenAt: _b, ...restB } = b

  return JSON.stringify(restA) === JSON.stringify(restB)
}

/** Whether two statuses are equal, so an unchanged status is never rewritten. */
export function sameStatus(a: GraphStatus, b: GraphStatus): boolean {
  return a.refreshing === b.refreshing && a.liveSource === b.liveSource && a.lastError === b.lastError
}
