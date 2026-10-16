// Open decisions per item and open runs of the project, fed only from the SSE stream (decision.* and
// run.* events). `runs_read decisions` is director-only and a run's frontier is per run, so there is no
// snapshot read behind these: the maps hold what this session has seen since its live source started.
// Pure: same object when nothing changes, so the caller can skip the write.
import type { GraphDecision, GraphRun } from '../../types'
import { shortId } from '../shared/to-client.ts'

/** Most items with an open decision kept; the oldest key goes first. */
export const DECISION_CAP = 200

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const str = (v: unknown): string | undefined => (typeof v === 'string' && v.length > 0 ? v : undefined)

/**
 * Applies one decision.* event. `decision.raised` with an `itemId` sets `map[shortId(itemId)]`;
 * `decision.answered` / `decision.withdrawn` delete that item's entry when its decisionId matches;
 * `decision.acknowledged` and events without an itemId change nothing.
 */
export function applyDecisionEvent(map: Record<string, GraphDecision>, name: string, data: unknown): Record<string, GraphDecision> {
  if (!isObj(data)) return map
  const itemId = str(data.itemId)
  const decisionId = str(data.decisionId)
  if (itemId === undefined || decisionId === undefined) return map
  const key = shortId(itemId)

  if (name === 'decision.raised') {
    const severity = str(data.severity)
    const cls = str(data.class)
    const cur = map[key]
    if (cur !== undefined && cur.decisionId === decisionId && cur.severity === severity && cur.class === cls) return map
    const entry: GraphDecision = { decisionId, ...(severity !== undefined ? { severity } : {}), ...(cls !== undefined ? { class: cls } : {}) }
    if (cur !== undefined) return { ...map, [key]: entry }
    const keys = Object.keys(map)
    const keep = keys.length >= DECISION_CAP ? keys.slice(keys.length - (DECISION_CAP - 1)) : keys

    return { ...Object.fromEntries(keep.map(k => [k, map[k] as GraphDecision])), [key]: entry }
  }

  if (name === 'decision.answered' || name === 'decision.withdrawn') {
    if (map[key]?.decisionId !== decisionId) return map
    const { [key]: _closed, ...rest } = map

    return rest
  }

  return map
}

/**
 * Applies one run.* event of the project root (`shortId(data.rootId) === shortId(projectRootId)`; any
 * other root changes nothing): run.opened / run.resumed -> open, run.paused -> paused, run.ended ->
 * removed; run.updated and run.marked change nothing.
 */
export function applyRunEvent(map: Record<string, GraphRun>, name: string, data: unknown, projectRootId: string | null): Record<string, GraphRun> {
  if (!isObj(data) || projectRootId === null) return map
  const rootId = str(data.rootId)
  const runId = str(data.runId)
  if (rootId === undefined || runId === undefined || shortId(rootId) !== shortId(projectRootId)) return map

  const status: GraphRun['status'] | null = name === 'run.opened' || name === 'run.resumed' ? 'open' : name === 'run.paused' ? 'paused' : null
  if (status !== null) return map[runId]?.status === status ? map : { ...map, [runId]: { status } }
  if (name === 'run.ended') {
    if (map[runId] === undefined) return map
    const { [runId]: _ended, ...rest } = map

    return rest
  }

  return map
}

/** `?` when the item has an open decision, else ''. */
export function decisionGlyph(id: string, map: Record<string, GraphDecision>): string {
  return map[shortId(id)] !== undefined ? '?' : ''
}

/** `runs: <n> open`, `runs: <m> paused` or `runs: <n> open, <m> paused`; '' when there is none. */
export function runSummary(map: Record<string, GraphRun>): string {
  const runs = Object.values(map)
  const open = runs.filter(r => r.status === 'open').length
  const paused = runs.filter(r => r.status === 'paused').length
  if (open > 0 && paused > 0) return `runs: ${open} open, ${paused} paused`
  if (open > 0) return `runs: ${open} open`
  if (paused > 0) return `runs: ${paused} paused`

  return ''
}
