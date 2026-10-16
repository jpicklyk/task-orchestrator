// Phase guard, the 4.0-only surface (item 5dbd8275, scenarios S5 and S6; AC3 of the frozen task-scope).
//
// Oracles: current/docs/api-reference.md "Response envelope" (a non-atomic batch with failures is
// `ok:false`, error code `partial_failure`, next to the full `data: {results, summary}`), "advance" (result
// elements `{ok:true, itemId, previousRole, newRole, trigger,...}`; a `gate_blocked` element carries
// `error.detail.missing[]`; a seat token is the top-level `seatToken`), "items_read" (select `context` with
// an `itemId` answers the gate state; its `item` is the compact view whose `id` is the 8-hex short id).
// Task-scope AC3 supplies what the mod records: `error.detail.itemId` and `error.detail.role` of a
// gate_blocked element, a full UUID for every recorded id, and the marker-keyed gate read.
import type { On } from 'claude-code'
import { expect, test } from 'claude-code/testing'

import type { PhaseGuardEntry } from '../types'
import * as core from '../src/phase-guard/core.ts'
import { checkStop, normalizeEntry, recordAdvance } from '../src/phase-guard/core.ts'
import type { GuardIo } from '../src/phase-guard/core.ts'
import * as helpers from '../src/phase-guard/helpers.ts'
import * as guardIndex from '../src/phase-guard/index.ts'

const U1 = '26246238-681f-497d-ae4c-6dbafd2c1e12'
const U2 = '11111111-2222-4333-8444-555555555555'
const U3 = '77777777-8888-4999-8aaa-bbbbbbbbbbbb'
const TO = 'mcp__mcp-task-orchestrator__'

type Call = { tool: string; args: Record<string, unknown> }
type World = { entries: Map<string, PhaseGuardEntry>; contexts: Record<string, unknown>; calls: Call[] }

const world = (contexts: Record<string, unknown> = {}): World => ({ entries: new Map(), contexts, calls: [] })

const io = (w: World): GuardIo => ({
  getEntry: async id => normalizeEntry(w.entries.get(id)),
  setEntry: async (id, entry) => void w.entries.set(id, entry),
  callTo: async (tool, args) => {
    w.calls.push({ tool, args })
    const c = w.contexts[args.itemId as string]
    if (tool !== 'items_read' || args.select !== 'context' || c === undefined) throw new Error('not_found: no such item')

    return c
  },
  isHeadless: async () => false,
})

const ok$ = (itemId: string, newRole: string) => ({ ok: true, itemId, previousRole: 'queue', newRole, trigger: 'start' })
const blocked$ = (itemId: string, role: string) => ({
  ok: false,
  error: { code: 'gate_blocked', kind: 'permanent', message: 'required notes missing', detail: { itemId, role, missing: [{ key: 'implementation-notes', role }] } },
})
const unmet$ = { ok: false, error: { code: 'dependency_unmet', kind: 'permanent', message: 'blocked by a dependency' } }

const envelope = (results: unknown[], ok = true) =>
  ok
    ? { ok: true, data: { results, summary: { ok: results.length, failed: 0 } }, meta: { reqId: 'k7f3q9ab' } }
    : {
        ok: false,
        error: { code: 'partial_failure', kind: 'permanent', message: 'some elements failed' },
        data: { results, summary: { ok: 1, failed: results.length - 1 } },
        meta: { reqId: 'k7f3q9ab' },
      }
const response = (results: unknown[], ok = true) => ({ structuredContent: envelope(results, ok) })

const ctx = (id: string, role: string, missing: string[], itemId: string = id.slice(0, 8)) => ({
  item: { id: itemId, title: 'Phase guard', role },
  gateStatus: { canAdvance: missing.length === 0, missing },
})

// --- S5: what is recorded ---------------------------------------------------------------

test('S5: a partially failed batch records the started item and the gate-blocked one with the blocked role', async () => {
  const w = world()
  const wrote = await recordAdvance(io(w), {
    agent_id: 'a1',
    tool_input: { ops: [{ op: 'transition', itemId: U1, trigger: 'start' }, { op: 'transition', itemId: U2, trigger: 'start' }, { op: 'transition', itemId: U3, trigger: 'start' }] },
    tool_response: response([ok$(U1, 'work'), blocked$(U2, 'review'), unmet$], false),
  })
  expect(wrote).toBe(true)
  const entry = w.entries.get('a1') as PhaseGuardEntry
  expect(entry.items).toEqual([U1, U2])
  expect(entry.enteredRoles).toEqual({ [U1]: 'work', [U2]: 'review' })
})

test('S5: every element of data.results is considered, in order', async () => {
  const w = world()
  await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: response([ok$(U3, 'work'), ok$(U1, 'review'), ok$(U2, 'work')]) })
  expect((w.entries.get('a1') as PhaseGuardEntry).items).toEqual([U3, U1, U2])
})

test('S5: the envelope may arrive as JSON text in a content block', async () => {
  const w = world()
  const wrote = await recordAdvance(io(w), {
    agent_id: 'a1',
    tool_input: {},
    tool_response: { content: [{ type: 'text', text: JSON.stringify(envelope([ok$(U1, 'work')])) }] },
  })
  expect(wrote).toBe(true)
  expect((w.entries.get('a1') as PhaseGuardEntry).enteredRoles).toEqual({ [U1]: 'work' })
})

test('S5: the 3.x answer shape (no ok, applied/newRole results) records nothing', async () => {
  const w = world()
  const legacy = { structuredContent: { results: [{ itemId: U1, applied: true, newRole: 'work' }], summary: {} } }
  expect(await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: legacy })).toBe(false)
  const text = { content: [{ type: 'text', text: JSON.stringify({ success: true, data: { results: [{ itemId: U1, applied: true, newRole: 'work' }] } }) }] }
  expect(await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: text })).toBe(false)
  expect(await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: { content: [{ type: 'text', text: 'ok' }] } })).toBe(false)
  expect(await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: undefined })).toBe(false)
  expect(w.entries.size).toBe(0)
})

test('S5: an element without a full UUID, or without a string newRole, is not recordable', async () => {
  const w = world()
  const results = [
    ok$(U1.slice(0, 8), 'work'),
    { ok: true, itemId: U2, previousRole: 'queue', trigger: 'start' },
    { ok: true, itemId: U3, previousRole: 'queue', newRole: 7, trigger: 'start' },
    blocked$(U2.slice(0, 8), 'work'),
  ]
  expect(await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: response(results, false) })).toBe(false)
  expect(w.entries.size).toBe(0)
})

test('S5: failures other than gate_blocked record nothing, even with an itemId and role in the detail', async () => {
  const w = world()
  for (const code of ['not_claim_holder', 'resource_unavailable', 'dependency_unmet', 'not_found', 'invalid_transition', 'forbidden']) {
    const el = { ok: false, error: { code, kind: 'permanent', message: code, detail: { itemId: U1, role: 'work' } } }
    expect(await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: response([el], false) })).toBe(false)
  }
  expect(w.entries.size).toBe(0)
})

test('S5: a call under a seat token records nothing; an empty or absent token records', async () => {
  const w = world()
  const resp = response([ok$(U1, 'work')])
  expect(await recordAdvance(io(w), { agent_id: 'a', tool_input: { ops: [], seatToken: 'x' }, tool_response: resp })).toBe(false)
  expect(w.entries.size).toBe(0)
  expect(await recordAdvance(io(w), { agent_id: 'a', tool_input: { ops: [], seatToken: '' }, tool_response: resp })).toBe(true)
  const w2 = world()
  expect(await recordAdvance(io(w2), { agent_id: 'a', tool_input: { ops: [] }, tool_response: resp })).toBe(true)
})

test('S5: the same item entered twice is listed once and keeps the latest role', async () => {
  const w = world()
  await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: response([ok$(U1, 'work')]) })
  await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: response([ok$(U1, 'review')]) })
  const entry = w.entries.get('a1') as PhaseGuardEntry
  expect(entry.items).toEqual([U1])
  expect(entry.enteredRoles).toEqual({ [U1]: 'review' })
})

test('S5: two agents keep separate entries', async () => {
  const w = world()
  await recordAdvance(io(w), { agent_id: 'a1', tool_input: {}, tool_response: response([ok$(U1, 'work')]) })
  await recordAdvance(io(w), { agent_id: 'a2', tool_input: {}, tool_response: response([ok$(U2, 'work')]) })
  expect((w.entries.get('a1') as PhaseGuardEntry).items).toEqual([U1])
  expect((w.entries.get('a2') as PhaseGuardEntry).items).toEqual([U2])
})

// --- S6: the stop gate read -------------------------------------------------------------

const TAIL = 'Fill them via notes_write (op "upsert") before returning. If something blocks you, say what blocks you and stop.'

test('S6: each marker item is read with items_read context keyed by the marker id', async () => {
  const w = world({ [U1]: ctx(U1, 'work', ['implementation-notes']), [U2]: ctx(U2, 'work', ['session-tracking']) })
  w.entries.set('a', { items: [U1, U2], blocks: 0, enteredRoles: { [U1]: 'work', [U2]: 'work' } })
  const reason = await checkStop(io(w), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })
  expect(w.calls).toEqual([
    { tool: 'items_read', args: { select: 'context', itemId: U1 } },
    { tool: 'items_read', args: { select: 'context', itemId: U2 } },
  ])
  expect(reason).toContain('26246238')
  expect(reason).toContain('11111111')
  expect(reason?.endsWith(TAIL)).toBe(true)
})

test('S6: the gate is the marker item, not whatever id the context names', async () => {
  // A context whose item.id is a different short id: the block still names the marker id.
  const w = world({ [U1]: ctx(U1, 'work', ['implementation-notes'], 'deadbeef') })
  w.entries.set('a', { items: [U1], blocks: 0, enteredRoles: { [U1]: 'work' } })
  const reason = await checkStop(io(w), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })
  expect(reason).toContain('Item 26246238 ')
  expect(reason).not.toContain('deadbeef')
})

test('S6: an item moved to review since the agent entered work does not block (compared by the marker id)', async () => {
  const moved = world({ [U1]: ctx(U1, 'review', ['review-checklist']) })
  moved.entries.set('a', { items: [U1], blocks: 0, enteredRoles: { [U1]: 'work' } })
  expect(await checkStop(io(moved), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })).toBeNull()
  const still = world({ [U1]: ctx(U1, 'work', ['implementation-notes']) })
  still.entries.set('a', { items: [U1], blocks: 0, enteredRoles: { [U1]: 'work' } })
  expect(await checkStop(io(still), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })).not.toBeNull()
})

test('S6: a workflow-subagent is never blocked and reads nothing', async () => {
  const w = world({ [U1]: ctx(U1, 'work', ['implementation-notes']) })
  w.entries.set('a', { items: [U1], blocks: 0, enteredRoles: { [U1]: 'work' } })
  expect(await checkStop(io(w), { agent_id: 'a', agent_type: 'workflow-subagent' })).toBeNull()
  expect(w.calls).toEqual([])
})

test('S6: an unreadable context never blocks', async () => {
  const w = world({})
  w.entries.set('a', { items: [U1], blocks: 0, enteredRoles: { [U1]: 'work' } })
  expect(await checkStop(io(w), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })).toBeNull()
})

// --- AC3: the deleted surface -----------------------------------------------------------

test('AC3: the actor and skill helpers are gone from the modules', () => {
  for (const name of ['actorAttribution', 'skillEnforcement']) expect(name in core).toBe(false)
  for (const name of [
    'isActorAuthenticationEnabled',
    'isActorAttributionRequired',
    'ACTOR_DENY_REASON',
    'actorMissing',
    'seatOwnedCandidates',
    'seatOwnedWarning',
    'buildActorMap',
    'actorForResult',
    'isWorkflowSeatActor',
    'parseSkillMaps',
    'substantiveFloor',
    'skillAdvisories',
    'PLACEHOLDER_PATTERNS',
    'DEFAULT_SUBSTANTIVE_LENGTH',
    'extractResponseJson',
  ]) {
    expect(name in helpers).toBe(false)
  }
})

test('AC3: the phase guard exports only checkStop, recordAdvance and its registration', () => {
  expect(Object.keys(guardIndex).sort()).toEqual(['checkStop', 'recordAdvance', 'registerPhaseGuard'])
})

// --- through the engine ------------------------------------------------------------------

/** In-memory `$.state` and `$.env`, and `$.mcp` answering 4.0 envelopes in structuredContent. */
function serve(on: On, w: World): Call[] {
  const store = new Map<string, { value: unknown; version: number }>()
  const mcp: Call[] = []
  const slot = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`
  on('state.get', async (_$, e) => ({ value: store.get(slot(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slot(e as never)
    const version = (store.get(k)?.version ?? 0) + 1
    store.set(k, { value: (e as { value: unknown }).value, version })

    return { value: { isSet: true, version } } as never
  })
  on('env.get', async () => ({ value: undefined }) as never)
  on('mcp.call', async (_$, e) => {
    mcp.push({ tool: e.tool, args: e.args })
    const c = w.contexts[e.args.itemId as string]
    if (c === undefined) {
      const error = { code: 'not_found', kind: 'permanent', message: 'no such item' }

      return { value: { content: [{ type: 'text', text: 'not_found: no such item' }], structuredContent: { ok: false, error }, isError: true } } as never
    }

    return { value: { content: [{ type: 'text', text: 'ok' }], structuredContent: { ok: true, data: c }, isError: false } } as never
  })

  return mcp
}

test('integration: a partially failed advance is recorded and the stop reads both items through mcp.call envelopes', async ($, on) => {
  const w = world({ [U1]: ctx(U1, 'work', ['implementation-notes']), [U2]: ctx(U2, 'review', ['review-checklist']) })
  const mcp = serve(on, w)
  on('classic.PostToolUse', async () => ({}) as never)
  await $.classic.PostToolUse({
    tool_name: `${TO}advance`,
    tool_input: { ops: [{ op: 'transition', itemId: U1, trigger: 'start' }, { op: 'transition', itemId: U2, trigger: 'start' }, { op: 'transition', itemId: U3, trigger: 'start' }] },
    tool_response: response([ok$(U1, 'work'), blocked$(U2, 'review'), unmet$], false),
    tool_use_id: 't1',
    agent_id: 'a1',
    agent_type: 'general-purpose',
  } as never)

  const stop = await $.classic.SubagentStop({ stop_hook_active: false, agent_id: 'a1', agent_type: 'general-purpose', agent_transcript_path: '' } as never)
  expect(mcp.map(c => c.args.itemId)).toEqual([U1, U2])
  expect(mcp.every(c => c.tool === 'items_read' && c.args.select === 'context')).toBe(true)
  expect(stop.block).toContain('implementation-notes')
  expect(stop.block).toContain('review-checklist')
  expect(stop.block).toContain(TAIL)
})

test('integration: a workflow-subagent that entered an item is let through', async ($, on) => {
  const w = world({ [U1]: ctx(U1, 'work', ['implementation-notes']) })
  const mcp = serve(on, w)
  on('classic.SubagentStop', async () => ({}) as never)
  on('classic.PostToolUse', async () => ({}) as never)
  await $.classic.PostToolUse({
    tool_name: `${TO}advance`,
    tool_input: {},
    tool_response: response([ok$(U1, 'work')]),
    tool_use_id: 't1',
    agent_id: 'a1',
    agent_type: 'workflow-subagent',
  } as never)
  const stop = await $.classic.SubagentStop({ stop_hook_active: false, agent_id: 'a1', agent_type: 'workflow-subagent', agent_transcript_path: '' } as never)
  expect(stop.block).toBeUndefined()
  expect(mcp).toEqual([])
})
