// Pure retrospective logic (T5): a port of retro-lib.mjs, retro-trigger.mjs and retro-backstop.mjs
// with no `$` and no fs, so it runs under test as plain functions. retro-lib.mjs itself cannot be
// imported (it pulls in fs/os/path). Keep the two in step when either changes.
import type { RetroState } from '../../types'
import { inlineScalar, readSection, scalar } from '../lib/yaml-lite.mjs'

export const MAX_ROOT_UUIDS = 50

export type RetroMode = 'nudge' | 'dispatch' | 'off'
export interface RetroConfig {
  mode: RetroMode
  dispatchThreshold: number
  cooldownMinutes: number
}

/** The `retrospective:` block (block or inline form); an absent or invalid key takes its default. */
export function parseRetrospectiveConfig(text: string | null | undefined): RetroConfig {
  const result: RetroConfig = { mode: 'nudge', dispatchThreshold: 3, cooldownMinutes: 30 }
  if (!text) return result
  const section = readSection(text, 'retrospective')
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
  const rawCooldown = read('cooldownMinutes')
  if (rawCooldown !== null) {
    const n = Number(rawCooldown)
    if (Number.isFinite(n) && n > 0) result.cooldownMinutes = n
  }

  return result
}

/** The tool's JSON payload from any plausible encoding of its result; null when it has none. */
export function extractResponseJson(toolResponse: unknown): Record<string, unknown> | null {
  try {
    if (toolResponse == null) return null
    const asObject = (v: unknown): Record<string, unknown> | null => (v !== null && typeof v === 'object' ? (v as Record<string, unknown>) : null)
    const fromBlocks = (blocks: unknown[]): Record<string, unknown> | null => {
      const block = blocks.find(b => b !== null && typeof b === 'object' && typeof (b as { text?: unknown }).text === 'string') as { text: string } | undefined

      return block ? asObject(JSON.parse(block.text)) : null
    }
    if (typeof toolResponse === 'string') return asObject(JSON.parse(toolResponse))
    if (Array.isArray(toolResponse)) return fromBlocks(toolResponse)
    const obj = toolResponse as Record<string, unknown>
    if (asObject(obj.structuredContent)) return asObject(obj.structuredContent)
    if (Array.isArray(obj.content)) return fromBlocks(obj.content)
    if (typeof obj.text === 'string') return asObject(JSON.parse(obj.text))
    if (obj.results !== undefined || obj.summary !== undefined) return obj

    return null
  } catch {
    return null
  }
}

export function buildNudge(roots: readonly string[] | undefined): string {
  const list = (roots ?? []).filter(Boolean)
  const tail =
    'to capture learnings before the session ends. Skip if the feature still has items in progress, if background tasks or subagents are still running (revisit once they finish), or if this was minor housekeeping.'
  if (list.length === 0) {
    return `Retrospective suggested — an implementation run reached terminal. Consider running \`/session-retrospective\` ${tail}`
  }

  return `Retrospective suggested — an implementation run reached terminal (root(s): ${list.join(', ')}). Consider running \`/session-retrospective ${list[0]}\` ${tail}`
}

/** The directive a model acts on; used only as the fallback when the mod could not spawn the agent itself. */
export function buildDispatch(roots: readonly string[], ancestorId: string | null): string {
  const joined = roots.join(', ')

  return (
    `Retrospective dispatch (mode: dispatch). Run ${joined} reached terminal. ` +
    `This directive is durable — act on it at the next run boundary: if background tasks or subagents ` +
    `are still running, hold it and dispatch only after the last of them completes and its results are ` +
    `processed. If further dispatch directives arrive while holding, merge them: dispatch ONE retrospective ` +
    `covering the union of their roots. Dispatch exactly ONE background retrospective via the Agent tool: ` +
    `subagent_type "general-purpose", model "sonnet", run_in_background true, prompt: invoke ` +
    `/session-retrospective ${joined}, project ancestorId ${ancestorId ?? 'none configured'}, work ONLY from durable MCP ` +
    `state (session-tracking + delegation-metadata notes, schema config, agent-observation items — no ` +
    `conversation context), write the retrospective into the process-global 'Session Retrospectives' ` +
    `container at depth 0 OUTSIDE any project root, return only the retrospective item UUID plus a 2-3 line ` +
    `headline. Do not dispatch more than one retrospective for this run; skip entirely if the feature still ` +
    `has active items.`
  )
}

/** The prompt of the agent the mod spawns: the agent half of buildDispatch. */
export function buildSpawnPrompt(roots: readonly string[], ancestorId: string | null): string {
  const joined = roots.join(', ')
  const scope = ancestorId ? `project ancestorId ${ancestorId}` : 'no project ancestorId (do not scope reads to a project root)'

  return (
    `Invoke /session-retrospective ${joined}, ${scope}. Work ONLY from durable MCP state ` +
    `(session-tracking + delegation-metadata notes, schema config, agent-observation items — no ` +
    `conversation context). Write the retrospective into the process-global 'Session Retrospectives' ` +
    `container at depth 0 OUTSIDE any project root. Return only the retrospective item UUID plus a 2-3 line ` +
    `headline. Skip entirely if the feature still has active items.`
  )
}

/** The one line the model gets when the mod has queued a dispatch (no buildDispatch text). */
export const QUEUED_NOTICE =
  'Retrospective queued — the task-orchestrator mod dispatches it at the end of the run. Do not dispatch a retrospective yourself.'

// ── call classification (retro-trigger.mjs) ─────────────────────────────────────────────

export type CallKind = 'PARENT_COMPLETION' | 'LONE_TERMINAL'
export type Classified =
  | { kind: CallKind; roots: string[]; count: number }
  /** complete_tree completed items but named no root: counts toward substance only. */
  | { kind: 'UNROOTED'; count: number }
  | null

/** Classifies an advance_item / complete_tree call exactly as retro-trigger.mjs does. */
export function classifyCall(toolName: string, toolInput: Record<string, unknown> | undefined, resp: Record<string, unknown> | null): Classified {
  if (resp === null) return null
  const input = toolInput ?? {}

  if (toolName === 'complete_tree') {
    const completed = (resp.summary as { completed?: unknown } | undefined)?.completed
    if (typeof completed !== 'number' || completed <= 0) return null
    const roots = input.rootId ? [String(input.rootId)] : Array.isArray(input.itemIds) ? (input.itemIds as unknown[]).map(String) : []
    if (roots.length === 0) return { kind: 'UNROOTED', count: completed }

    return { kind: 'PARENT_COMPLETION', roots, count: completed }
  }

  if (toolName === 'advance_item') {
    const results = (Array.isArray(resp.results) ? resp.results : []) as Record<string, unknown>[]
    const cascade: string[] = []
    for (const r of results) {
      const events = (Array.isArray(r.cascadeEvents) ? r.cascadeEvents : []) as Record<string, unknown>[]
      for (const ev of events) if (ev.targetRole === 'terminal' && ev.applied === true) cascade.push(String(ev.itemId))
    }
    const lone = results.filter(r => r.newRole === 'terminal' && !(Array.isArray(r.unblockedItems) && r.unblockedItems.length > 0)).map(r => String(r.itemId))
    const direct = results.filter(r => r.newRole === 'terminal').map(r => String(r.itemId))

    if (cascade.length > 0) return { kind: 'PARENT_COMPLETION', roots: [...new Set(cascade)], count: new Set([...cascade, ...direct]).size }
    if (lone.length > 0) return { kind: 'LONE_TERMINAL', roots: lone, count: lone.length }
  }

  return null
}

// ── state reducers ───────────────────────────────────────────────────────────────────────

const cap = (ids: string[]): string[] => (ids.length > MAX_ROOT_UUIDS ? ids.slice(ids.length - MAX_ROOT_UUIDS) : ids)
const union = (a: readonly string[] | undefined, b: readonly string[]): string[] => [...new Set([...(a ?? []), ...b])]

export function normalizeState(raw: unknown): RetroState {
  return raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? (raw as RetroState) : {}
}

/** Record a terminal that is not (yet) a run boundary: waits for a parent completion or the Stop backstop. */
export function recordTerminal(state: RetroState, roots: readonly string[], count: number, now: number): RetroState {
  return {
    ...state,
    sawTerminal: true,
    lastTerminalAt: now,
    pendingRoots: union(state.pendingRoots, roots),
    terminalCount: (state.terminalCount ?? 0) + count,
  }
}

export type Outcome = { state: RetroState; action: 'none' | 'nudge' | 'queue'; roots: string[] }

/**
 * Reduces one classified terminal call into the state and the action to take.
 * `inSubagent` is true when the call came from a subagent loop.
 */
export function applyCall(state: RetroState, c: NonNullable<Classified>, cfg: RetroConfig, now: number, inSubagent: boolean): Outcome {
  if (c.kind === 'UNROOTED') {
    return { state: { ...state, sawTerminal: true, lastTerminalAt: now, terminalCount: (state.terminalCount ?? 0) + c.count }, action: 'none', roots: [] }
  }
  if (c.kind === 'LONE_TERMINAL') return { state: recordTerminal(state, c.roots, c.roots.length, now), action: 'none', roots: [] }

  const substance = (state.terminalCount ?? 0) + c.count
  const dispatching = cfg.mode === 'dispatch' && substance >= cfg.dispatchThreshold
  // A subagent only ever queues a dispatch; anything else is recorded for the main loop's Stop.
  if (inSubagent && !dispatching) return { state: recordTerminal(state, c.roots, c.count, now), action: 'none', roots: [] }

  const existing = state.rootUuids ?? []
  const newRun = !state.handledAt || now - state.handledAt > cfg.cooldownMinutes * 60_000 || c.roots.some(r => !existing.includes(r))
  if (!newRun) return { state, action: 'none', roots: [] }

  const base: RetroState = {
    ...state,
    handledAt: now,
    rootUuids: cap(union(existing, c.roots)),
    sawTerminal: false,
    pendingRoots: [],
    terminalCount: 0,
  }
  if (dispatching) return { state: { ...base, pendingDispatch: union(state.pendingDispatch, c.roots) }, action: 'queue', roots: c.roots }

  return { state: base, action: 'nudge', roots: c.roots }
}

/** retro-ack.mjs on the mod's state: the run is handled. */
export function ackState(state: RetroState, now: number, clearPending: boolean): RetroState {
  const next: RetroState = { ...state, handledAt: now, sawTerminal: false, pendingRoots: [], terminalCount: 0 }
  if (clearPending) next.pendingDispatch = null

  return next
}

/** True while any background task is running or pending. */
export function holdsForTasks(tasks: readonly { status: string }[]): boolean {
  return tasks.some(t => t.status === 'running' || t.status === 'pending')
}

/** The backstop decision for a Stop with nothing to dispatch: the roots to nudge for, or null. */
export function backstopRoots(state: RetroState, cfg: RetroConfig, now: number): string[] | null {
  if (!state.sawTerminal) return null
  if (state.handledAt && now - state.handledAt <= cfg.cooldownMinutes * 60_000) return null

  return state.pendingRoots ? [...state.pendingRoots] : []
}

/** The state after the backstop nudged. */
export function afterBackstop(state: RetroState, now: number): RetroState {
  return { ...state, handledAt: now, sawTerminal: false, pendingRoots: [], terminalCount: 0 }
}
