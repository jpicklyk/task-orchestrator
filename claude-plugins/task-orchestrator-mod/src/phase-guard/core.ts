// The phase guard's logic over a `GuardIo`, so it runs in the test's own realm without `$`.
// index.ts is the only file here that touches `$` (the validator follows `$` only into functions of
// the same file); it builds a GuardIo from `$` and calls these.
import type { PhaseGuardEntry } from '../../types'
import { envelopeOf } from '../shared/to-client.ts'
import { MAX_BLOCKS_PER_AGENT, MAX_ITEMS_PER_AGENT, blockerFor, buildReason, gateFromContext, recordableEntries } from './helpers.ts'
import type { Blocker } from './helpers.ts'

export interface GuardIo {
  getEntry(agentId: string): Promise<PhaseGuardEntry>
  setEntry(agentId: string, entry: PhaseGuardEntry): Promise<void>
  /** A TO read tool's envelope `data`; throws on an error envelope or none. */
  callTo(tool: string, args: Record<string, unknown>): Promise<unknown>
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

/** A non-empty top-level `seatToken`: a run seat's call, which the run itself supervises. */
function hasSeatToken(toolInput: unknown): boolean {
  const token = toolInput !== null && typeof toolInput === 'object' ? (toolInput as { seatToken?: unknown }).seatToken : undefined

  return typeof token === 'string' && token.length > 0
}

/**
 * Port of phase-guard-record.mjs `main` for an `advance` call: records, for a subagent, the items whose
 * transition entered (or found it already in) a phase, with the role entered. A call made under a seat
 * token records nothing, nor does a 3.x payload. Returns true when something was written.
 * Edge (accepted, D3): if this throws, the caller forwards without the flag and the command hook may
 * write a marker file that the mod's SubagentStop never reads, so phase-guard.mjs is suppressed for
 * that agent - a rare fail-open.
 */
export async function recordAdvance(io: GuardIo, e: PostToolUseLike): Promise<boolean> {
  if (await io.isHeadless()) return false
  if (!e.agent_id) return false
  if (hasSeatToken(e.tool_input)) return false

  const recorded = recordableEntries(envelopeOf(e.tool_response))
  if (recorded.length === 0) return false
  const newItems = recorded.map(r => r.itemId)
  const newRoles: Record<string, string> = {}
  for (const r of recorded) if (r.role) newRoles[r.itemId] = r.role

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
 * The gate of each recorded item comes from `items_read` select `context` (D2), keyed by the recorded
 * id; an item whose read fails does not block. A workflow subagent is supervised by its workflow and
 * never blocked.
 */
export async function checkStop(io: GuardIo, e: SubagentStopLike): Promise<string | null> {
  if (e.agent_type === 'workflow-subagent') return null
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
        return gateFromContext(await io.callTo('items_read', { select: 'context', itemId }), itemId)
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
