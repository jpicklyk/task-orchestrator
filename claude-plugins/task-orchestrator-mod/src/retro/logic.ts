// Pure retrospective logic (T5, ported to run marks): a port of retro-lib.mjs, retro-trigger.mjs and
// retro-backstop.mjs with no `$` and no fs, so it runs under test as plain functions. retro-lib.mjs itself
// cannot be imported (it pulls in fs/os/path). Keep the two in step when either changes.
//
// A retrospective is owed per closed run on the project root that is not marked `retro`. The server-side
// mark (runs_write `mark`, first write wins) is the cross-session dedupe: only the session whose mark
// lands dispatches, so no state here is keyed by root or kept across sessions.
import type { RetroState } from '../../types'
import { envelopeOf } from '../shared/to-client.ts'
import { inlineScalar, readSection, scalar } from '../lib/yaml-lite.mjs'

/** Most recorded closes kept (retro-trigger.mjs MAX_RECORDED). */
export const MAX_RECORDED = 50
/** Closed runs looked back over (retro-lib.mjs LIST_WINDOW_MS). */
export const LIST_WINDOW_MS = 24 * 60 * 60 * 1000
const LIST_LIMIT = 20
/** Most runs whose frontier one evaluation reads (retro-lib.mjs MAX_FRONTIER_RUNS). */
export const MAX_FRONTIER_RUNS = 5

export type RetroMode = 'nudge' | 'dispatch' | 'off'
export interface RetroConfig {
  mode: RetroMode
  dispatchThreshold: number
}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

const indentOf = (line: string): number => line.length - line.trimStart().length
const isContent = (line: string): boolean => {
  const t = line.trim()

  return t !== '' && !t.startsWith('#')
}

/** Source: retro-lib.mjs `childBlock`: the direct child `key:` of a block's lines, inline or nested; null when absent. */
function childBlock(lines: readonly string[], key: string): { inline: string | null; lines: string[] } | null {
  let firstLevel = Infinity
  for (const line of lines) if (isContent(line)) firstLevel = Math.min(firstLevel, indentOf(line))
  if (firstLevel === Infinity) return null
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const re = new RegExp('^' + escaped + '\\s*:\\s*(?:\\{(.*)\\}.*|(?:#.*)?)$')
  for (let i = 0; i < lines.length; i++) {
    const line = (lines[i] as string).replace(/\r$/, '')
    if (!isContent(line) || indentOf(line) !== firstLevel) continue
    const m = line.trim().match(re)
    if (!m) continue
    if (m[1] !== undefined) return { inline: m[1], lines: [] }
    const out: string[] = []
    for (let j = i + 1; j < lines.length; j++) {
      const next = lines[j] as string
      if (isContent(next) && indentOf(next) <= firstLevel) break
      out.push(next)
    }

    return { inline: null, lines: out }
  }

  return null
}

/**
 * Source: retro-lib.mjs `parseRetrospectiveConfig`. Reads `extensions.claude-code.retrospective`
 * (`mode`, `dispatchThreshold`; block or inline form at the leaf). A top-level `retrospective:` block is
 * not read. An absent block or an invalid value takes its default; never throws.
 */
export function parseRetrospectiveConfig(text: string | null | undefined): RetroConfig {
  const result: RetroConfig = { mode: 'nudge', dispatchThreshold: 3 }
  try {
    if (!text) return result
    const ext = readSection(text, 'extensions', { blockOnly: true })
    if (!ext) return result
    const cc = childBlock(ext.lines, 'claude-code')
    if (!cc || cc.inline !== null) return result
    const section = childBlock(cc.lines, 'retrospective')
    if (!section) return result
    const read = (key: string): string | null => (section.inline !== null ? inlineScalar(section.inline, key) : scalar(section.lines, key))

    const rawMode = read('mode')
    if (rawMode !== null) {
      const val = rawMode.toLowerCase()
      if (val === 'nudge' || val === 'dispatch' || val === 'off') result.mode = val
    }
    const rawThreshold = read('dispatchThreshold')
    if (rawThreshold !== null) {
      const n = Number(rawThreshold)
      if (Number.isInteger(n) && n >= 0) result.dispatchThreshold = n
    }
  } catch {
    // defaults
  }

  return result
}

/**
 * Source: retro-trigger.mjs `successfulCloses`. The run ids of a `runs_write` call's `close` ops whose
 * result at the same index is `ok: true` (runId from the op, else from the result). Null when no close
 * succeeded (or the result has no envelope). A partly failed batch still counts its successful closes.
 */
export function successfulCloses(toolInput: unknown, result: unknown): string[] | null {
  const env = envelopeOf(result)
  if (env === null) return null
  const results = isObj(env.data) && Array.isArray(env.data.results) ? env.data.results : []
  const ops = isObj(toolInput) && Array.isArray(toolInput.ops) ? toolInput.ops : []
  const ids: string[] = []
  let any = false
  ops.forEach((op, i) => {
    if (!isObj(op) || op.op !== 'close') return
    const r = results[i]
    if (!isObj(r) || r.ok !== true) return
    any = true
    const runId = typeof op.runId === 'string' && op.runId ? op.runId : typeof r.runId === 'string' && r.runId ? r.runId : null
    if (runId !== null) ids.push(runId)
  })

  return any ? ids : null
}

function runCommands(runIds: readonly string[]): string[] {
  return runIds.map(id => `\`/session-retrospective --run ${id}\``)
}

/** Source: retro-lib.mjs `buildNudge` (same text). */
export function buildNudge(runIds: readonly string[] | undefined): string {
  const list = (runIds ?? []).filter(Boolean)
  const tail =
    'Skip if the feature still has items in progress, if background tasks or subagents are still ' +
    'running (revisit once they finish), or if this was minor housekeeping.'
  if (list.length === 0) {
    return 'Retrospective suggested — an implementation run closed. ' + `Consider running \`/session-retrospective\` to capture learnings before the session ends. ${tail}`
  }

  return `Retrospective suggested — a run closed (run(s): ${list.join(', ')}). ` + `Consider running ${runCommands(list).join(' and ')} to capture learnings before the session ends. ${tail}`
}

/** Source: retro-lib.mjs `buildDispatch` (same text). The directive a model acts on; the fallback when the mod could not spawn the agent itself. */
export function buildDispatch(runIds: readonly string[], ancestorId: string | null): string {
  const list = runIds.filter(Boolean)
  const ancestor = ancestorId || 'none configured'
  const invoke = list.length === 0 ? '`/session-retrospective`' : runCommands(list).join(' and ')

  return (
    `Retrospective dispatch (mode: dispatch). Run ${list.join(', ')} closed. ` +
    `This directive is durable — act on it at the next run boundary: if background tasks or subagents ` +
    `are still running, hold it and dispatch only after the last of them completes and its results are ` +
    `processed. If further dispatch directives arrive while holding, merge them: dispatch ONE retrospective ` +
    `covering the union of their runs. Dispatch exactly ONE background retrospective via the Agent tool: ` +
    `subagent_type "general-purpose", model "sonnet", run_in_background true, prompt: invoke ` +
    `${invoke}, project ancestorId ${ancestor}, work ONLY from durable MCP ` +
    `state (session-tracking + delegation-metadata notes, schema config, agent-observation items — no ` +
    `conversation context), write the retrospective into the process-global 'Session Retrospectives' ` +
    `container, return only the retrospective item UUID plus a 2-3 line ` +
    `headline. Do not dispatch more than one retrospective for this run; skip entirely if the feature still ` +
    `has active items.`
  )
}

/** The prompt of the agent the mod spawns: the agent half of buildDispatch. */
export function buildSpawnPrompt(runIds: readonly string[], ancestorId: string | null): string {
  const list = runIds.filter(Boolean)
  const invoke = list.length === 0 ? '/session-retrospective' : list.map(id => `/session-retrospective --run ${id}`).join(' and ')
  const scope = ancestorId ? `project ancestorId ${ancestorId}` : 'no project ancestorId (do not scope reads to a project root)'

  return (
    `Invoke ${invoke}, ${scope}. Work ONLY from durable MCP state ` +
    `(session-tracking + delegation-metadata notes, schema config, agent-observation items — no ` +
    `conversation context). Write the retrospective into the process-global 'Session Retrospectives' ` +
    `container. Return only the retrospective item UUID plus a 2-3 line ` +
    `headline. Skip entirely if the feature still has active items.`
  )
}

/** The one line the model gets when the mod has queued a dispatch (no buildDispatch text). */
export const QUEUED_NOTICE =
  'Retrospective queued — the task-orchestrator mod dispatches it at the end of the run. Do not dispatch a retrospective yourself.'

// ── state ────────────────────────────────────────────────────────────────────────────────

const union = (a: readonly string[] | undefined, b: readonly string[]): string[] => [...new Set([...(a ?? []), ...b])]

export function normalizeState(raw: unknown): RetroState {
  return raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? (raw as RetroState) : {}
}

/** Records closes this session saw but did not evaluate (a subagent, or no project rootId): the backstop reads them. */
export function recordCloses(state: RetroState, closes: readonly string[]): RetroState {
  const all = union(state.closes, closes)

  return { ...state, sawClose: true, closes: all.length > MAX_RECORDED ? all.slice(all.length - MAX_RECORDED) : all }
}

/** True while any background task is running or pending. */
export function holdsForTasks(tasks: readonly { status: string }[]): boolean {
  return tasks.some(t => t.status === 'running' || t.status === 'pending')
}

// ── run evaluation (retro-lib.mjs evaluateRuns) ──────────────────────────────────────────

/** A TO call returning the envelope `data`; rejects on an error envelope (the caller binds `$.mcp.call`). */
export type RetroCall = (tool: string, args: Record<string, unknown>) => Promise<unknown>

export interface Evaluation {
  /** Runs whose `retro` mark this evaluation wrote: dispatch them. */
  won: string[]
  /** Runs to nudge for (nudge mode, below the threshold, or a failed mark). */
  nudge: string[]
  /** The state with the nudged runs appended to `nudged` (the caller persists it). */
  state: RetroState
}

/**
 * Source: retro-lib.mjs `evaluateRuns`, over `call`. Lists the root's closed runs not yet marked `retro`
 * (closed in the last 24 h), skips runs already marked or nudged this session, reads the terminal count
 * of at most MAX_FRONTIER_RUNS of them, and for each: dispatch mode and terminal >= threshold -> try to
 * mark (first write wins); `marked: true` is won, `marked: false` (another session marked first) is silent,
 * a failed mark is nudged (never dispatched unmarked). Everything else is nudged. A failed frontier read
 * skips that run this time. Fail-open: a failed list returns nothing.
 */
export async function evaluateRuns(call: RetroCall, rootId: string, cfg: RetroConfig, state: RetroState, now: number): Promise<Evaluation> {
  const won: string[] = []
  const nudge: string[] = []
  const nudged = state.nudged ?? []
  try {
    const list = await call('runs_read', { select: 'list', rootId, unmarked: 'retro', endedSince: new Date(now - LIST_WINDOW_MS).toISOString(), limit: LIST_LIMIT })
    const views = isObj(list) && Array.isArray(list.items) ? list.items : []
    const candidates: string[] = []
    for (const view of views) {
      if (!isObj(view) || typeof view.runId !== 'string' || view.runId === '') continue
      if (isObj(view.marks) && Object.prototype.hasOwnProperty.call(view.marks, 'retro')) continue
      if (nudged.includes(view.runId) || candidates.includes(view.runId)) continue
      candidates.push(view.runId)
      if (candidates.length >= MAX_FRONTIER_RUNS) break
    }

    const frontiers = await Promise.all(candidates.map(runId => call('runs_read', { select: 'frontier', runId }).catch(() => null)))
    const toMark: string[] = []
    candidates.forEach((runId, i) => {
      const f = frontiers[i]
      const terminal = isObj(f) && isObj(f.counts) ? f.counts.terminal : undefined
      if (typeof terminal !== 'number') return
      if (cfg.mode === 'dispatch' && terminal >= cfg.dispatchThreshold) toMark.push(runId)
      else nudge.push(runId)
    })

    const marks = await Promise.all(toMark.map(runId => call('runs_write', { ops: [{ op: 'mark', runId, key: 'retro' }] }).catch(() => null)))
    toMark.forEach((runId, i) => {
      const data = marks[i]
      const r = isObj(data) && Array.isArray(data.results) ? data.results[0] : null
      if (isObj(r) && r.ok === true && r.marked === true) won.push(runId)
      else if (isObj(r) && r.ok === true && r.marked === false) return
      else nudge.push(runId)
    })
  } catch {
    // fail-open
  }

  return { won, nudge, state: nudge.length > 0 ? { ...state, nudged: union(nudged, nudge) } : state }
}
