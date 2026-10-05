// Pure helpers of the phase guard, copied as TS from the TO command hooks (decision D1: the mod cannot
// import the .mjs hooks, which pull in fs/os/path at module top). Each helper names its source; keep
// the constants and semantics equal to the originals. No `$`, no I/O.
import { readSection, scalar, inlineScalar } from '../lib/yaml-lite.mjs'

// ── execution-mode.mjs ─────────────────────────────────────────────────────────────────────

/** Source: hooks/execution-mode.mjs `phaseOwnerSeat`. */
export function phaseOwnerSeat(agentType: string | undefined): 'implementer' | 'reviewer' | null {
  const match = /(^|:)(implementer|reviewer)$/.exec(agentType ?? '')

  return match ? (match[2] as 'implementer' | 'reviewer') : null
}

/** Source: hooks/execution-mode.mjs `isTestAuthorAgentType`. */
export function isTestAuthorAgentType(agentType: string | undefined): boolean {
  return /(^|:)test-author$/.test(agentType ?? '')
}

// ── retro-lib.mjs ──────────────────────────────────────────────────────────────────────────

/** Source: hooks/retro-lib.mjs `extractResponseJson`. */
export function extractResponseJson(toolResponse: any): any {
  try {
    if (toolResponse == null) return null
    if (typeof toolResponse === 'string') return JSON.parse(toolResponse)
    if (Array.isArray(toolResponse)) {
      const block = toolResponse.find(b => b && typeof b.text === 'string')

      return block ? JSON.parse(block.text) : null
    }
    if (Array.isArray(toolResponse.content)) {
      const block = toolResponse.content.find((b: any) => b && typeof b.text === 'string')

      return block ? JSON.parse(block.text) : null
    }
    if (typeof toolResponse.text === 'string') return JSON.parse(toolResponse.text)
    if (toolResponse.results !== undefined || toolResponse.summary !== undefined) return toolResponse

    return null
  } catch {
    return null
  }
}

// ── phase-guard-record.mjs ─────────────────────────────────────────────────────────────────

/** Source: phase-guard-record.mjs MAX_ITEMS_PER_AGENT. */
export const MAX_ITEMS_PER_AGENT = 50
/** Source: phase-guard.mjs MAX_BLOCKS_PER_AGENT. */
export const MAX_BLOCKS_PER_AGENT = 2

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Source: phase-guard-record.mjs `isFullUuid`. */
export function isFullUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_RE.test(value)
}

/** Source: phase-guard-record.mjs `extractRecordableItemIds`. */
export function extractRecordableItemIds(payload: any): string[] {
  const results = Array.isArray(payload?.results) ? payload.results : []

  return results.filter((r: any) => r && isFullUuid(r.itemId) && (r.applied === true || r.errorCode === 'gate_blocked')).map((r: any) => r.itemId)
}

/** Source: phase-guard-record.mjs `buildActorMap`. */
export function buildActorMap(toolInput: any): Map<string, any> {
  const map = new Map<string, any>()
  if (!toolInput || typeof toolInput !== 'object') return map
  if (Array.isArray(toolInput.transitions)) {
    for (const t of toolInput.transitions) {
      if (t && typeof t === 'object' && typeof t.itemId === 'string') map.set(t.itemId, t.actor)
    }
  } else if (typeof toolInput.itemId === 'string') {
    map.set(toolInput.itemId, toolInput.actor)
  }

  return map
}

/** Source: phase-guard-record.mjs `actorForResult` (exact, then prefix either way, then position). */
export function actorForResult(actorMap: Map<string, any>, resultItemId: unknown, resultIndex: number, totalResults: number): any {
  if (!(actorMap instanceof Map) || typeof resultItemId !== 'string') return undefined
  if (actorMap.has(resultItemId)) return actorMap.get(resultItemId)

  const lowerResult = resultItemId.toLowerCase()
  for (const [key, actor] of actorMap) {
    if (typeof key !== 'string' || key.length === 0) continue
    const lowerKey = key.toLowerCase()
    if (lowerResult.startsWith(lowerKey) || lowerKey.startsWith(lowerResult)) return actor
  }

  if (actorMap.size === totalResults) {
    const keys = [...actorMap.keys()]
    if (resultIndex >= 0 && resultIndex < keys.length) return actorMap.get(keys[resultIndex] as string)
  }

  return undefined
}

/** Source: phase-guard-record.mjs `isWorkflowSeatActor`. */
export function isWorkflowSeatActor(actor: any): boolean {
  return !!actor && typeof actor === 'object' && typeof actor.parent === 'string' && actor.parent.startsWith('workflow:')
}

/** Source: phase-guard-record.mjs `extractEnteredRoles`. */
export function extractEnteredRoles(payload: any): Record<string, string> {
  const results = Array.isArray(payload?.results) ? payload.results : []
  const roles: Record<string, string> = {}
  for (const r of results) {
    if (!r || !isFullUuid(r.itemId)) continue
    if (r.applied === true && typeof r.newRole === 'string' && r.newRole) roles[r.itemId] = r.newRole
    else if (r.errorCode === 'gate_blocked' && typeof r.previousRole === 'string' && r.previousRole) roles[r.itemId] = r.previousRole
  }

  return roles
}

// ── phase-guard.mjs ────────────────────────────────────────────────────────────────────────

export const BLOCKING_ROLES: ReadonlySet<string> = new Set(['work', 'review'])
export const SEAT_ROLE: Record<string, string> = { implementer: 'work', reviewer: 'review' }
/** Source: phase-guard.mjs TEST_AUTHOR_OWNED_KEYS. */
export const TEST_AUTHOR_OWNED_KEYS: ReadonlySet<string> = new Set(['test-manifest'])

/** The gate in the shape phase-guard.mjs reads from REST /gate; the mod builds it from `get_context`. */
export interface Gate {
  itemId: string
  title: string
  role: string
  gateStatus: { canAdvance?: boolean; missing?: unknown[]; violations?: any[] }
  skillPointer?: string
  guidanceKey?: string
}

/** Adapts a `get_context` item-mode payload to the REST /gate shape (decision D2); null when it has no item id. */
export function gateFromContext(ctx: any): Gate | null {
  const item = ctx?.item
  if (!item || typeof item.id !== 'string') return null
  const gs = ctx.gateStatus ?? {}

  return {
    itemId: item.id,
    title: String(item.title ?? ''),
    role: String(item.role ?? ''),
    gateStatus: { canAdvance: gs.canAdvance, missing: gs.missing, violations: gs.violations },
    skillPointer: ctx.skillPointer ?? gs.skillPointer ?? undefined,
    guidanceKey: ctx.guidanceKey ?? gs.guidanceKey ?? undefined,
  }
}

/** Source: phase-guard.mjs `missingKey`. */
function missingKey(entry: any): string | undefined {
  return typeof entry === 'string' ? entry : entry?.key
}

export interface Blocker {
  gate: Gate
  missing: string[]
  hintValid: boolean
  violationBlockers: any[]
}

/** Source: phase-guard.mjs `formatViolation`. */
function formatViolation(v: any): string {
  return `${v.key} (${v.constraint})`
}

/** Source: phase-guard.mjs `buildReason`. */
export function buildReason(blockers: Blocker[]): string {
  const parts = blockers.map(({ gate, missing, hintValid, violationBlockers }) => {
    const uuid8 = gate.itemId.slice(0, 8)
    const hint = !hintValid ? '' : gate.skillPointer ? ` Invoke the ${gate.skillPointer} skill for guidance.` : gate.guidanceKey ? ` See guidance: ${gate.guidanceKey}.` : ''
    const segments: string[] = []
    if (missing.length > 0) segments.push(`required notes still missing: ${missing.join(', ')}`)
    if (violationBlockers.length > 0) {
      segments.push(`independence-attestation violations blocking this transition: ${violationBlockers.map(formatViolation).join(', ')}`)
    }

    return `Item ${uuid8} "${gate.title}" is in ${gate.role} with ${segments.join(' and ')}.${hint}`
  })

  return `${parts.join(' ')} Fill them via manage_notes(upsert) before returning. If something blocks you, say what blocks you and stop.`
}

/**
 * Source: the per-gate loop of phase-guard.mjs `main`. The blocker for one gate, or null when the gate
 * does not block (a gate that could not be read is the caller's null and never reaches here).
 */
export function blockerFor(gate: Gate, enteredRoles: Record<string, string>, agentType: string | undefined): Blocker | null {
  const seat = phaseOwnerSeat(agentType)
  const isTestAuthor = isTestAuthorAgentType(agentType)
  const enteredRole = enteredRoles[gate.itemId]
  if (enteredRole && gate.role !== enteredRole) return null
  if (!BLOCKING_ROLES.has(gate.role)) return null
  if (seat && gate.role !== SEAT_ROLE[seat]) return null
  const rawMissing = Array.isArray(gate.gateStatus?.missing) ? gate.gateStatus.missing : []
  const normalizedMissing = rawMissing.map(missingKey).filter(Boolean) as string[]
  const missing = isTestAuthor ? normalizedMissing : normalizedMissing.filter(key => !TEST_AUTHOR_OWNED_KEYS.has(key))

  const rawViolations = Array.isArray(gate.gateStatus?.violations) ? gate.gateStatus.violations : null
  const violationBlockers =
    rawViolations && rawMissing.length === 0 && gate.gateStatus?.canAdvance === false
      ? rawViolations.filter(v => v && v.waived !== true && (!seat || v.seat === seat))
      : []

  if (missing.length === 0 && violationBlockers.length === 0) return null
  const hintValid = missing.length > 0 && missing[0] === normalizedMissing[0]

  return { gate, missing, hintValid, violationBlockers }
}

// ── skill-enforcement.mjs ──────────────────────────────────────────────────────────────────

/** Source: skill-enforcement.mjs PLACEHOLDER_PATTERNS. */
export const PLACEHOLDER_PATTERNS: readonly RegExp[] = [
  /^n\/?a$/i,
  /^looks?\s+(fine|good|ok)/i,
  /^no\s+issues?\s*(found)?/i,
  /^todo$/i,
  /^placeholder$/i,
  /^tbd$/i,
  /^pending$/i,
  /^will\s+fill\s+(later|soon)/i,
]

/** Source: skill-enforcement.mjs DEFAULT_SUBSTANTIVE_LENGTH. */
export const DEFAULT_SUBSTANTIVE_LENGTH = 200

export interface SkillMaps {
  skillMap: Map<string, string>
  maxLengthMap: Map<string, number>
}

/** Source: skill-enforcement.mjs line-based config parse (note key -> skill, note key -> maxLength; last wins). */
export function parseSkillMaps(configContent: string): SkillMaps {
  const skillMap = new Map<string, string>()
  const maxLengthMap = new Map<string, number>()
  let currentKey: string | null = null

  for (const line of configContent.split('\n')) {
    const trimmed = line.trim()
    const keyMatch = trimmed.match(/^- key:\s*["']?([^"'\s]+)["']?/)
    if (keyMatch) {
      currentKey = keyMatch[1] as string
      continue
    }
    const skillMatch = trimmed.match(/^skill:\s*["']?([^"'\s]+)["']?/)
    if (skillMatch && currentKey) {
      skillMap.set(currentKey, skillMatch[1] as string)
      continue
    }
    const maxLengthMatch = trimmed.match(/^maxLength:\s*(\d+)/)
    if (maxLengthMatch && currentKey) {
      maxLengthMap.set(currentKey, Number(maxLengthMatch[1]))
      continue
    }
    if (trimmed.endsWith(':') && !trimmed.startsWith('skill:') && !trimmed.startsWith('maxLength:')) currentKey = null
  }

  return { skillMap, maxLengthMap }
}

/** Source: skill-enforcement.mjs `substantiveFloor`. */
export function substantiveFloor(maxLengthMap: Map<string, number>, key: string): number {
  const maxLength = maxLengthMap.get(key)
  if (maxLength !== undefined && maxLength < 800) return Math.min(DEFAULT_SUBSTANTIVE_LENGTH, Math.floor(maxLength / 4))

  return DEFAULT_SUBSTANTIVE_LENGTH
}

/**
 * Source: skill-enforcement.mjs note loop. The advisories for a manage_notes(upsert) input and the
 * `itemId::key` pairs that produced them; `warned` is the session's set so far.
 */
export function skillAdvisories(configContent: string, toolInput: any, warned: ReadonlySet<string>): { warnings: string[]; newlyWarned: string[] } {
  const warnings: string[] = []
  const newlyWarned: string[] = []
  if (!toolInput || toolInput.operation !== 'upsert' || !Array.isArray(toolInput.notes)) return { warnings, newlyWarned }
  const { skillMap, maxLengthMap } = parseSkillMaps(configContent)
  if (skillMap.size === 0) return { warnings, newlyWarned }

  for (const note of toolInput.notes) {
    const { key, body, bodyFromFile, itemId } = note ?? {}
    if (!key || !skillMap.has(key)) continue
    if (typeof bodyFromFile === 'string' && bodyFromFile.length > 0) continue

    const dedupKey = `${itemId}::${key}`
    if (warned.has(dedupKey) || newlyWarned.includes(dedupKey)) continue

    const skill = skillMap.get(key)
    const trimmedBody = (body || '').trim()
    const bodyLen = trimmedBody.length
    const floor = substantiveFloor(maxLengthMap, key)
    const isPlaceholder = bodyLen < floor && PLACEHOLDER_PATTERNS.some(p => p.test(trimmedBody))

    if (!body || bodyLen < floor || isPlaceholder) {
      warnings.push(
        `⊘ SKILL SUGGESTED — the note "${key}" is schema-bound to the /${skill} framework. ` +
          `If you have not already, invoke the Skill tool with skill="${skill}" and incorporate ` +
          `its structured evaluation before finalizing this note. If the Skill tool reports ` +
          `'Unknown skill', the schema's skill pointer is invalid — log that as an observation ` +
          `instead of retrying. (Shown once per item+key per session.)`,
      )
      newlyWarned.push(dedupKey)
    }
  }

  return { warnings, newlyWarned }
}

// ── enforce-actor-attribution.mjs ──────────────────────────────────────────────────────────

/** Source: enforce-actor-attribution.mjs `isActorAuthenticationEnabled`. */
export function isActorAuthenticationEnabled(configContent: string | null): boolean {
  if (!configContent) return false
  const section = readSection(configContent, 'actor_authentication')
  if (!section) return false
  const raw = section.inline !== null ? inlineScalar(section.inline, 'enabled') : scalar(section.lines, 'enabled')

  return raw !== null && raw.toLowerCase() === 'true'
}

/** Source: enforce-actor-attribution.mjs `isActorAttributionRequired`. */
export function isActorAttributionRequired(configContent: string | null): boolean {
  if (!configContent) return false
  const section = readSection(configContent, 'actor_attribution')
  if (!section) return false
  const raw = section.inline !== null ? inlineScalar(section.inline, 'required') : scalar(section.lines, 'required')

  return raw !== null && raw.toLowerCase() === 'true'
}

/** Source: enforce-actor-attribution.mjs deny text. */
export const ACTOR_DENY_REASON =
  'Actor attribution is required (actor_authentication.enabled or actor_attribution.required is set). Include an "actor" object ' +
  'with "id" (string) and "kind" (orchestrator|subagent|user|external) on every ' +
  'transition/note element (for a singular advance_item call with itemId+trigger, put it at the ' +
  'top level). For subagents, include "parent" with the dispatching agent\'s id.'

/** Source: enforce-actor-attribution.mjs `missing` computation; false when enforcement is off. */
export function actorMissing(enforced: boolean, toolName: 'advance_item' | 'manage_notes', toolInput: any): boolean {
  if (!enforced) return false
  if (toolName === 'advance_item') {
    if (Array.isArray(toolInput.transitions)) return toolInput.transitions.some((t: any) => !t.actor)
    if (typeof toolInput.itemId === 'string') return !toolInput.actor

    return false
  }
  const notes = toolInput.notes || []

  return notes.some((n: any) => !n.actor)
}

/** Source: enforce-actor-attribution.mjs candidate filter: orchestrator-kind, full-UUID itemId, at most 10. */
export function seatOwnedCandidates(toolInput: any): { itemId: string; key: string }[] {
  const notes = Array.isArray(toolInput?.notes) ? toolInput.notes : []

  return notes
    .filter((n: any) => n && n.actor && n.actor.kind === 'orchestrator' && isFullUuid(n.itemId) && typeof n.key === 'string' && n.key)
    .slice(0, 10)
    .map((n: any) => ({ itemId: n.itemId, key: n.key }))
}

/** Source: enforce-actor-attribution.mjs warning text. */
export function seatOwnedWarning(itemId: string, key: string, storedActorId: string): string {
  return (
    `Seat-owned note: item ${itemId} note "${key}" is stored under subagent actor ` +
    `"${storedActorId}". Re-upserting it as the orchestrator flips its ownership and ` +
    "self-confirms the seat's verdict. Dispatch a fresh reviewer seat for re-review, or record " +
    'your confirmation under your own key "orchestrator-confirmation" (role review, optional).'
  )
}
