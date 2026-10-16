// Pure helpers of the phase guard, copied as TS from the TO command hooks (decision D1: the mod cannot
// import the .mjs hooks, which pull in fs/os/path at module top). Each helper names its source; keep
// the constants and semantics equal to the originals. No `$`, no I/O.
import type { ToEnvelope } from '../shared/to-client.ts'

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

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * The items an `advance` envelope entered (or found already in) a phase, with the role entered, in
 * result order. Every element of `data.results` is considered: `ok: true` with a full-UUID `itemId` and a
 * string `newRole` (role = newRole), or `ok: false` with `error.code === 'gate_blocked'` and a full-UUID
 * `error.detail.itemId` (role = `error.detail.role`). A batch that partly failed still counts.
 */
export function recordableEntries(env: ToEnvelope | null): { itemId: string; role: string }[] {
  const data = env !== null && isObj(env.data) ? env.data : {}
  const results = Array.isArray(data.results) ? data.results : []
  const out: { itemId: string; role: string }[] = []
  for (const r of results) {
    if (!isObj(r)) continue
    if (r.ok === true && isFullUuid(r.itemId) && typeof r.newRole === 'string' && r.newRole) {
      out.push({ itemId: r.itemId, role: r.newRole })
    } else if (r.ok === false && isObj(r.error) && r.error.code === 'gate_blocked' && isObj(r.error.detail) && isFullUuid(r.error.detail.itemId)) {
      const role = r.error.detail.role
      out.push({ itemId: r.error.detail.itemId, role: typeof role === 'string' ? role : '' })
    }
  }

  return out
}

// ── phase-guard.mjs ────────────────────────────────────────────────────────────────────────

export const BLOCKING_ROLES: ReadonlySet<string> = new Set(['work', 'review'])
export const SEAT_ROLE: Record<string, string> = { implementer: 'work', reviewer: 'review' }
/** Source: phase-guard.mjs TEST_AUTHOR_OWNED_KEYS. */
export const TEST_AUTHOR_OWNED_KEYS: ReadonlySet<string> = new Set(['test-manifest'])

/** The gate in the shape phase-guard.mjs reads; the mod builds it from `items_read` select `context`. */
export interface Gate {
  itemId: string
  title: string
  role: string
  gateStatus: { canAdvance?: boolean; missing?: unknown[]; violations?: any[] }
  skillPointer?: string
  guidanceKey?: string
}

/**
 * Adapts an `items_read` `context` item-mode payload to the gate shape, KEYED BY `itemId` (the recorded
 * full id): the payload's `item.id` is an 8-hex short id and is never used as the key. Null when the
 * payload has no item.
 */
export function gateFromContext(ctx: any, itemId: string): Gate | null {
  const item = ctx?.item
  if (!item || typeof item !== 'object') return null
  const gs = ctx.gateStatus ?? {}

  return {
    itemId,
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

/** Source: phase-guard.mjs `buildReason`, with the 4.0 call shape in its closing instruction. */
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

  return `${parts.join(' ')} Fill them via notes_write (op "upsert") before returning. If something blocks you, say what blocks you and stop.`
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
