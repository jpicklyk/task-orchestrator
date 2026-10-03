// The phase guard's logic over a `GuardIo`, so it runs in the test's own realm without `$`.
// index.ts is the only file here that touches `$` (the validator follows `$` only into functions of
// the same file); it builds a GuardIo from `$` and calls these.
import type { PhaseGuardEntry } from '../../types'
import {
  ACTOR_DENY_REASON,
  MAX_BLOCKS_PER_AGENT,
  MAX_ITEMS_PER_AGENT,
  actorForResult,
  actorMissing,
  blockerFor,
  buildActorMap,
  buildReason,
  extractEnteredRoles,
  extractRecordableItemIds,
  extractResponseJson,
  gateFromContext,
  isActorAttributionRequired,
  isActorAuthenticationEnabled,
  isWorkflowSeatActor,
  seatOwnedCandidates,
  seatOwnedWarning,
  skillAdvisories,
} from './helpers.ts'
import type { Blocker } from './helpers.ts'

export interface GuardIo {
  getEntry(agentId: string): Promise<PhaseGuardEntry>
  setEntry(agentId: string, entry: PhaseGuardEntry): Promise<void>
  getWarned(): Promise<string[]>
  setWarned(pairs: string[]): Promise<void>
  /** A TO read tool's parsed JSON result; throws on an error result. */
  callTo(tool: string, args: Record<string, unknown>): Promise<unknown>
  /** The raw project config text, or null when absent or unreadable. */
  readConfig(): Promise<string | null>
  /** True in a headless ralph iteration (TASK_ORCHESTRATOR_MODE=headless-iteration). */
  isHeadless(): Promise<boolean>
}

export const EMPTY_ENTRY: PhaseGuardEntry = { items: [], blocks: 0, enteredRoles: {} }

/** Normalizes whatever state held into a PhaseGuardEntry (an old or malformed value degrades to empty). */
export function normalizeEntry(value: unknown): PhaseGuardEntry {
  const v = value as Partial<PhaseGuardEntry> | null | undefined
  if (!v || typeof v !== 'object') return { items: [], blocks: 0, enteredRoles: {} }
  const roles = v.enteredRoles && typeof v.enteredRoles === 'object' && !Array.isArray(v.enteredRoles) ? v.enteredRoles : {}

  return { items: Array.isArray(v.items) ? v.items : [], blocks: Number.isInteger(v.blocks) ? (v.blocks as number) : 0, enteredRoles: roles }
}

export interface PostToolUseLike {
  agent_id?: string
  tool_input?: unknown
  tool_response?: unknown
}

/**
 * Port of phase-guard-record.mjs `main`: records, for a subagent, the items whose advance_item call
 * entered (or found it already in) a phase. Returns true when something was written.
 * Edge (accepted, D3): if this throws, the caller forwards without the flag and the command hook may
 * write a marker file that the mod's SubagentStop never reads, so phase-guard.mjs is suppressed for
 * that agent - a rare fail-open.
 */
export async function recordAdvance(io: GuardIo, e: PostToolUseLike): Promise<boolean> {
  if (await io.isHeadless()) return false
  if (!e.agent_id) return false

  const toolResponse = e.tool_response as { structuredContent?: unknown } | undefined
  const structured = toolResponse?.structuredContent ?? toolResponse
  const payload = extractResponseJson(structured)

  const actorMap = buildActorMap(e.tool_input)
  const rawResults: any[] = Array.isArray(payload?.results) ? payload.results : []
  const nonWorkflow = rawResults.filter((r, i) => !isWorkflowSeatActor(actorForResult(actorMap, r?.itemId, i, rawResults.length)))
  const filtered = { ...payload, results: nonWorkflow }

  const newItems = extractRecordableItemIds(filtered)
  if (newItems.length === 0) return false
  const newRoles = extractEnteredRoles(filtered)

  const entry = await io.getEntry(e.agent_id)
  const merged = [...new Set([...entry.items, ...newItems])]
  const items = merged.length > MAX_ITEMS_PER_AGENT ? merged.slice(merged.length - MAX_ITEMS_PER_AGENT) : merged
  const mergedRoles = { ...entry.enteredRoles, ...newRoles }
  const enteredRoles: Record<string, string> = {}
  for (const id of items) {
    if (mergedRoles[id]) enteredRoles[id] = mergedRoles[id] as string
  }
  await io.setEntry(e.agent_id, { items, blocks: entry.blocks, enteredRoles })

  return true
}

export interface SubagentStopLike {
  agent_id?: string
  agent_type?: string
}

/**
 * Port of phase-guard.mjs `main`: the block reason for a stopping subagent, or null to let it stop.
 * The gate of each recorded item comes from get_context (D2); an item whose read fails does not block.
 */
export async function checkStop(io: GuardIo, e: SubagentStopLike): Promise<string | null> {
  if (await io.isHeadless()) return null
  if (!e.agent_id) return null

  const entry = await io.getEntry(e.agent_id)
  if (entry.items.length === 0) return null

  if (entry.blocks >= MAX_BLOCKS_PER_AGENT) {
    await io.setEntry(e.agent_id, { items: [], blocks: 0, enteredRoles: {} })

    return null
  }

  const gates = await Promise.all(
    entry.items.map(async itemId => {
      try {
        return gateFromContext(await io.callTo('get_context', { itemId }))
      } catch {
        return null
      }
    }),
  )

  const blockers: Blocker[] = []
  for (const gate of gates) {
    if (!gate) continue
    const blocker = blockerFor(gate, entry.enteredRoles, e.agent_type)
    if (blocker) blockers.push(blocker)
  }

  if (blockers.length === 0) {
    await io.setEntry(e.agent_id, { items: [], blocks: 0, enteredRoles: {} })

    return null
  }

  await io.setEntry(e.agent_id, { items: entry.items, blocks: entry.blocks + 1, enteredRoles: entry.enteredRoles })

  return buildReason(blockers)
}

/** Result of the PreToolUse advisories for one call. */
export interface PreToolVerdict {
  /** Set when the call must be refused. */
  deny?: string
  /** Advisory texts for the model, possibly empty. */
  context: string[]
  /** False when the config could not be read, so the command hook must stay authoritative. */
  handled: boolean
}

/** Port of skill-enforcement.mjs for a manage_notes call. Advisory only. */
export async function skillEnforcement(io: GuardIo, toolInput: any): Promise<PreToolVerdict> {
  if (!toolInput || toolInput.operation !== 'upsert' || !Array.isArray(toolInput.notes)) return { context: [], handled: true }
  const config = await io.readConfig()
  if (config === null) return { context: [], handled: false }
  const warned = new Set(await io.getWarned())
  const { warnings, newlyWarned } = skillAdvisories(config, toolInput, warned)
  if (warnings.length === 0) return { context: [], handled: true }
  await io.setWarned([...warned, ...newlyWarned])

  return { context: [warnings.join('\n\n')], handled: true }
}

/**
 * Port of enforce-actor-attribution.mjs: deny an actor-less write when enforcement is on, and warn
 * (never deny) when an orchestrator re-upserts a note stored under a subagent actor.
 */
export async function actorAttribution(io: GuardIo, toolName: 'advance_item' | 'manage_notes', toolInput: any): Promise<PreToolVerdict> {
  const input = toolInput ?? {}
  const isUpsert = toolName === 'manage_notes' && input.operation === 'upsert'
  if (toolName === 'manage_notes' && !isUpsert) return { context: [], handled: true }

  const config = await io.readConfig()
  if (config === null) return { context: [], handled: false }
  const enforced = isActorAuthenticationEnabled(config) || isActorAttributionRequired(config)

  if (actorMissing(enforced, toolName, input)) return { deny: ACTOR_DENY_REASON, context: [], handled: true }
  if (!isUpsert) return { context: [], handled: true }

  const found = await Promise.all(
    seatOwnedCandidates(input).map(async c => {
      try {
        const listed = (await io.callTo('query_notes', { operation: 'list', itemId: c.itemId, keys: [c.key], includeBody: false })) as any
        const stored = Array.isArray(listed?.notes) ? listed.notes.find((n: any) => n?.key === c.key) : undefined
        if (stored?.actor?.kind === 'subagent') return seatOwnedWarning(c.itemId, c.key, String(stored.actor.id))
      } catch {
        // best-effort: stay silent
      }

      return null
    }),
  )

  return { context: found.filter((w): w is string => w !== null), handled: true }
}
