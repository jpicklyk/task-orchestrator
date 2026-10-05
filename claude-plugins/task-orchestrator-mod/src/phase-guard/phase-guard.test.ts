import type { On } from 'claude-code'
import { expect, test } from 'claude-code/testing'

import type { PhaseGuardEntry } from '../../types'
import { actorAttribution, checkStop, normalizeEntry, recordAdvance, skillEnforcement } from './core.ts'
import type { GuardIo } from './core.ts'
import { buildReason, parseSkillMaps, phaseOwnerSeat, skillAdvisories, substantiveFloor } from './helpers.ts'

const ID = '26246238-681f-497d-ae4c-6dbafd2c1e12'
const ID2 = '11111111-2222-4333-8444-555555555555'
const TO = 'mcp__mcp-task-orchestrator__'

type World = {
  entries: Map<string, PhaseGuardEntry>
  warned: string[]
  contexts: Record<string, unknown>
  notes: Record<string, unknown>
  config: string | null
  headless: boolean
  calls: { tool: string; args: Record<string, unknown> }[]
}

function world(over: Partial<World> = {}): World {
  return { entries: new Map(), warned: [], contexts: {}, notes: {}, config: null, headless: false, calls: [], ...over }
}

function fakeIo(w: World): GuardIo {
  return {
    getEntry: async id => normalizeEntry(w.entries.get(id)),
    setEntry: async (id, entry) => void w.entries.set(id, entry),
    getWarned: async () => w.warned,
    setWarned: async pairs => void (w.warned = pairs),
    callTo: async (tool, args) => {
      w.calls.push({ tool, args })
      if (tool === 'get_context') {
        const c = w.contexts[args.itemId as string]
        if (c === undefined || c instanceof Error) throw c ?? new Error('no such item')

        return c
      }
      if (tool === 'query_notes') {
        const n = w.notes[`${args.itemId}::${(args.keys as string[])[0]}`]
        if (n instanceof Error) throw n

        return { notes: n === undefined ? [] : [n] }
      }
      throw new Error(`unexpected ${tool}`)
    },
    readConfig: async () => w.config,
    isHeadless: async () => w.headless,
  }
}

const ctx = (id: string, role: string, missing: unknown[], extra: Record<string, unknown> = {}) => ({
  item: { id, title: 'Phase guard', role },
  gateStatus: { canAdvance: missing.length === 0, missing, ...((extra.gateStatus as object) ?? {}) },
  ...extra,
  ...(extra.gateStatus ? { gateStatus: { canAdvance: missing.length === 0, missing, ...(extra.gateStatus as object) } } : {}),
})

const advanceResponse = (results: unknown[]) => ({ structuredContent: { results, summary: {} } })
const actor = { id: 'implementer:x', kind: 'subagent', parent: 'abc' }

// ── record ──────────────────────────────────────────────────────────────────────────────

test('record: a subagent start is remembered with the entered role', async () => {
  const w = world()
  const wrote = await recordAdvance(fakeIo(w), {
    agent_id: 'a1',
    tool_input: { transitions: [{ itemId: ID, trigger: 'start', actor }] },
    tool_response: advanceResponse([{ itemId: ID, applied: true, newRole: 'work' }]),
  })
  expect(wrote).toBe(true)
  expect(w.entries.get('a1')).toEqual({ items: [ID], blocks: 0, enteredRoles: { [ID]: 'work' } })
})

test('record: gate_blocked records previousRole; other failures record nothing', async () => {
  const w = world()
  const io = fakeIo(w)
  await recordAdvance(io, {
    agent_id: 'a1',
    tool_input: { itemId: ID, trigger: 'start', actor },
    tool_response: advanceResponse([{ itemId: ID, applied: false, errorCode: 'gate_blocked', previousRole: 'work', targetRole: 'review' }]),
  })
  expect(w.entries.get('a1')?.enteredRoles).toEqual({ [ID]: 'work' })
  const w2 = world()
  for (const errorCode of ['not_claim_holder', 'resource_unavailable', 'dependency_blocked', 'item_not_found']) {
    expect(
      await recordAdvance(fakeIo(w2), { agent_id: 'a1', tool_input: {}, tool_response: advanceResponse([{ itemId: ID, applied: false, errorCode }]) }),
    ).toBe(false)
  }
  expect(w2.entries.size).toBe(0)
})

test('record: ignores the main loop, headless runs and workflow-seat actors', async () => {
  const w = world()
  const resp = advanceResponse([{ itemId: ID, applied: true, newRole: 'work' }])
  expect(await recordAdvance(fakeIo(w), { tool_input: { itemId: ID, actor }, tool_response: resp })).toBe(false)
  expect(await recordAdvance(fakeIo(world({ headless: true })), { agent_id: 'a', tool_input: { itemId: ID, actor }, tool_response: resp })).toBe(false)
  const wf = { id: 's', kind: 'subagent', parent: 'workflow:r-1' }
  expect(await recordAdvance(fakeIo(w), { agent_id: 'a', tool_input: { itemId: ID, actor: wf }, tool_response: resp })).toBe(false)
  // hex-prefix input id still resolves to the workflow actor
  expect(await recordAdvance(fakeIo(w), { agent_id: 'a', tool_input: { itemId: ID.slice(0, 8), actor: wf }, tool_response: resp })).toBe(false)
  expect(w.entries.size).toBe(0)
})

test('record: caps items at 50 and prunes roles of dropped items', async () => {
  const w = world()
  const io = fakeIo(w)
  const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
  for (let i = 0; i < 55; i++) {
    await recordAdvance(io, { agent_id: 'a', tool_input: {}, tool_response: advanceResponse([{ itemId: uuid(i), applied: true, newRole: 'work' }]) })
  }
  const entry = w.entries.get('a') as PhaseGuardEntry
  expect(entry.items).toHaveLength(50)
  expect(entry.items[0]).toBe(uuid(5))
  expect(Object.keys(entry.enteredRoles)).toHaveLength(50)
  expect(entry.enteredRoles[uuid(0)]).toBeUndefined()
})

// ── stop ────────────────────────────────────────────────────────────────────────────────

const entered = (id = ID, role = 'work'): PhaseGuardEntry => ({ items: [id], blocks: 0, enteredRoles: { [id]: role } })

test('stop: names uuid8, title, role, missing keys and the hint of the first key', async () => {
  const w = world({ contexts: { [ID]: { ...ctx(ID, 'work', ['implementation-notes', 'session-tracking']), skillPointer: 'spec-quality' } } })
  w.entries.set('a', entered())
  const reason = await checkStop(fakeIo(w), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })
  expect(reason).toBe(
    'Item 26246238 "Phase guard" is in work with required notes still missing: implementation-notes, session-tracking. ' +
      'Invoke the spec-quality skill for guidance. Fill them via manage_notes(upsert) before returning. If something blocks you, say what blocks you and stop.',
  )
  expect(w.entries.get('a')?.blocks).toBe(1)
})

test('stop: blocks at most twice, then the entry is cleared', async () => {
  const w = world({ contexts: { [ID]: ctx(ID, 'work', ['implementation-notes']) } })
  w.entries.set('a', entered())
  const io = fakeIo(w)
  expect(await checkStop(io, { agent_id: 'a', agent_type: 'x:implementer' })).not.toBeNull()
  expect(await checkStop(io, { agent_id: 'a', agent_type: 'x:implementer' })).not.toBeNull()
  expect(await checkStop(io, { agent_id: 'a', agent_type: 'x:implementer' })).toBeNull()
  expect(w.entries.get('a')).toEqual({ items: [], blocks: 0, enteredRoles: {} })
})

test('stop: passing gate, unread item and unknown agent never block; a pass clears the entry', async () => {
  const w = world({ contexts: { [ID]: ctx(ID, 'work', []), [ID2]: new Error('boom') } })
  w.entries.set('a', { items: [ID, ID2], blocks: 1, enteredRoles: {} })
  expect(await checkStop(fakeIo(w), { agent_id: 'a', agent_type: 'x:implementer' })).toBeNull()
  expect(w.entries.get('a')?.items).toEqual([])
  expect(await checkStop(fakeIo(w), { agent_id: 'nobody' })).toBeNull()
  expect(await checkStop(fakeIo(w), {})).toBeNull()
})

test('stop: skips an item a later seat advanced past the entered role', async () => {
  const w = world({ contexts: { [ID]: ctx(ID, 'review', ['review-checklist']) } })
  w.entries.set('a', entered(ID, 'work'))
  expect(await checkStop(fakeIo(w), { agent_id: 'a', agent_type: 'x:implementer' })).toBeNull()
})

test('stop: seat filter - implementer owns work, reviewer owns review, unknown is role-agnostic', async () => {
  const mk = (role: string) => {
    const w = world({ contexts: { [ID]: ctx(ID, role, ['k']) } })
    w.entries.set('a', { items: [ID], blocks: 0, enteredRoles: {} })

    return w
  }
  expect(await checkStop(fakeIo(mk('review')), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })).toBeNull()
  expect(await checkStop(fakeIo(mk('work')), { agent_id: 'a', agent_type: 'reviewer' })).toBeNull()
  expect(await checkStop(fakeIo(mk('review')), { agent_id: 'a', agent_type: 'reviewer' })).not.toBeNull()
  expect(await checkStop(fakeIo(mk('review')), { agent_id: 'a', agent_type: 'general-purpose' })).not.toBeNull()
  expect(await checkStop(fakeIo(mk('queue')), { agent_id: 'a' })).toBeNull()
  expect(await checkStop(fakeIo(mk('terminal')), { agent_id: 'a' })).toBeNull()
})

test('stop: test-manifest is dropped unless the stopping agent is a test-author; hint only when first key survives', async () => {
  const mk = () => {
    const w = world({ contexts: { [ID]: { ...ctx(ID, 'work', ['test-manifest', 'implementation-notes']), guidanceKey: 'test-manifest' } } })
    w.entries.set('a', { items: [ID], blocks: 0, enteredRoles: {} })

    return w
  }
  const impl = await checkStop(fakeIo(mk()), { agent_id: 'a', agent_type: 'x:implementer' })
  expect(impl).toContain('required notes still missing: implementation-notes.')
  expect(impl).not.toContain('See guidance')
  const author = await checkStop(fakeIo(mk()), { agent_id: 'a', agent_type: 'x:test-author' })
  expect(author).toContain('missing: test-manifest, implementation-notes.')
  expect(author).toContain('See guidance: test-manifest.')
  // only test-manifest missing: nothing to name for an implementer
  const w = world({ contexts: { [ID]: ctx(ID, 'work', ['test-manifest']) } })
  w.entries.set('a', { items: [ID], blocks: 0, enteredRoles: {} })
  expect(await checkStop(fakeIo(w), { agent_id: 'a', agent_type: 'x:implementer' })).toBeNull()
})

test('stop: A2 violations block only in reject mode, own seat, not waived', async () => {
  const violations = [
    { key: 'test-manifest', constraint: 'same_actor', seat: 'implementer', waived: false },
    { key: 'review-checklist', constraint: 'same_actor', seat: 'reviewer', waived: false },
    { key: 'x', constraint: 'same_actor', seat: 'implementer', waived: true },
  ]
  const mk = (canAdvance: boolean, missing: string[]) => {
    const w = world({ contexts: { [ID]: { item: { id: ID, title: 'T', role: 'work' }, gateStatus: { canAdvance, missing, violations } } } })
    w.entries.set('a', { items: [ID], blocks: 0, enteredRoles: {} })

    return w
  }
  const reject = await checkStop(fakeIo(mk(false, [])), { agent_id: 'a', agent_type: 'x:implementer' })
  expect(reject).toContain('independence-attestation violations blocking this transition: test-manifest (same_actor).')
  expect(reject).not.toContain('review-checklist')
  expect(await checkStop(fakeIo(mk(true, [])), { agent_id: 'a', agent_type: 'x:implementer' })).toBeNull()
  const mixed = await checkStop(fakeIo(mk(false, ['k'])), { agent_id: 'a', agent_type: 'x:implementer' })
  expect(mixed).toContain('missing: k.')
  expect(mixed).not.toContain('independence')
  const reasonWithBoth = buildReason([
    { gate: { itemId: ID, title: 'T', role: 'work', gateStatus: {} }, missing: ['a'], hintValid: false, violationBlockers: [violations[0]] },
  ])
  expect(reasonWithBoth).toContain('required notes still missing: a and independence-attestation')
})

test('stop: headless runs never block', async () => {
  const w = world({ headless: true, contexts: { [ID]: ctx(ID, 'work', ['k']) } })
  w.entries.set('a', entered())
  expect(await checkStop(fakeIo(w), { agent_id: 'a' })).toBeNull()
})

test('seat matching is segment-exact', () => {
  expect(phaseOwnerSeat('task-orchestrator:implementer-helper')).toBeNull()
  expect(phaseOwnerSeat('task-orchestrator:reviewer')).toBe('reviewer')
})

// ── skill enforcement ───────────────────────────────────────────────────────────────────

const SKILL_CONFIG = `note_schemas:
  feature-task:
    notes:
      - key: implementation-notes
        role: work
        skill: spec-quality
        maxLength: 3000
      - key: tiny
        role: work
        skill: spec-quality
        maxLength: 400
      - key: review-checklist
        role: review
`
const upsert = (notes: Record<string, unknown>[]) => ({ operation: 'upsert', notes })

test('skill: warns once per (item, key); a re-upsert is silent; another item warns again', async () => {
  const w = world({ config: SKILL_CONFIG })
  const io = fakeIo(w)
  const first = await skillEnforcement(io, upsert([{ itemId: ID, key: 'implementation-notes', body: 'short' }]))
  expect(first.context).toHaveLength(1)
  expect(first.context[0]).toContain('the note "implementation-notes" is schema-bound to the /spec-quality framework')
  expect(w.warned).toEqual([`${ID}::implementation-notes`])
  expect((await skillEnforcement(io, upsert([{ itemId: ID, key: 'implementation-notes', body: 'short' }]))).context).toEqual([])
  expect((await skillEnforcement(io, upsert([{ itemId: ID2, key: 'implementation-notes', body: 'short' }]))).context).toHaveLength(1)
})

test('skill: bodyFromFile skipped, long bodies pass, floor scales below maxLength 800, never blocks', async () => {
  expect(skillAdvisories(SKILL_CONFIG, upsert([{ itemId: ID, key: 'implementation-notes', bodyFromFile: 'x.md' }]), new Set()).warnings).toEqual([])
  expect(skillAdvisories(SKILL_CONFIG, upsert([{ itemId: ID, key: 'implementation-notes', body: 'x'.repeat(200) }]), new Set()).warnings).toEqual([])
  expect(skillAdvisories(SKILL_CONFIG, upsert([{ itemId: ID, key: 'implementation-notes', body: 'x'.repeat(199) }]), new Set()).warnings).toHaveLength(1)
  const { maxLengthMap } = parseSkillMaps(SKILL_CONFIG)
  expect(substantiveFloor(maxLengthMap, 'tiny')).toBe(100)
  expect(substantiveFloor(maxLengthMap, 'implementation-notes')).toBe(200)
  expect(skillAdvisories(SKILL_CONFIG, upsert([{ itemId: ID, key: 'tiny', body: 'x'.repeat(100) }]), new Set()).warnings).toEqual([])
  expect(skillAdvisories(SKILL_CONFIG, upsert([{ itemId: ID, key: 'tiny', body: 'x'.repeat(99) }]), new Set()).warnings).toHaveLength(1)
  expect(skillAdvisories(SKILL_CONFIG, upsert([{ itemId: ID, key: 'review-checklist', body: 'x' }]), new Set()).warnings).toEqual([])
  expect(skillAdvisories(SKILL_CONFIG, { operation: 'delete', notes: [] }, new Set()).warnings).toEqual([])
})

test('skill: a placeholder-like body is flagged only when short', () => {
  const body = `Looks fine. ${'detail '.repeat(40)}`
  expect(skillAdvisories(SKILL_CONFIG, upsert([{ itemId: ID, key: 'implementation-notes', body }]), new Set()).warnings).toEqual([])
  expect(skillAdvisories(SKILL_CONFIG, upsert([{ itemId: ID, key: 'implementation-notes', body: 'n/a' }]), new Set()).warnings).toHaveLength(1)
})

test('skill: an unreadable config is not handled, so the command hook stays authoritative', async () => {
  const r = await skillEnforcement(fakeIo(world({ config: null })), upsert([{ itemId: ID, key: 'implementation-notes', body: 'x' }]))
  expect(r).toEqual({ context: [], handled: false })
})

// ── actor attribution ───────────────────────────────────────────────────────────────────

const ENFORCED = 'actor_attribution:\n  required: true\n'
const AUTH = 'actor_authentication:\n  enabled: true\n'

test('attribution: denies actor-less elements only when enforced', async () => {
  const enforced = fakeIo(world({ config: ENFORCED }))
  const off = fakeIo(world({ config: 'actor_attribution:\n  required: false\n' }))
  const batch = { transitions: [{ itemId: ID, trigger: 'start', actor }, { itemId: ID2, trigger: 'start' }] }
  expect((await actorAttribution(enforced, 'advance_item', batch)).deny).toContain('Actor attribution is required')
  expect((await actorAttribution(enforced, 'advance_item', { itemId: ID, trigger: 'start' })).deny).toBeDefined()
  expect((await actorAttribution(enforced, 'advance_item', { itemId: ID, trigger: 'start', actor })).deny).toBeUndefined()
  expect((await actorAttribution(enforced, 'manage_notes', upsert([{ itemId: ID, key: 'k' }]))).deny).toBeDefined()
  expect((await actorAttribution(fakeIo(world({ config: AUTH })), 'manage_notes', upsert([{ itemId: ID, key: 'k' }]))).deny).toBeDefined()
  expect((await actorAttribution(off, 'advance_item', batch)).deny).toBeUndefined()
  expect((await actorAttribution(enforced, 'manage_notes', { operation: 'delete', ids: [] })).deny).toBeUndefined()
})

test('attribution: warns on an orchestrator upsert over a subagent-owned note, capped at 10', async () => {
  const w = world({ config: '', notes: { [`${ID}::review-checklist`]: { key: 'review-checklist', actor: { id: 'reviewer:1', kind: 'subagent' } } } })
  const orch = { id: 'orc', kind: 'orchestrator' }
  const r = await actorAttribution(fakeIo(w), 'manage_notes', upsert([{ itemId: ID, key: 'review-checklist', actor: orch }]))
  expect(r.deny).toBeUndefined()
  expect(r.context).toHaveLength(1)
  expect(r.context[0]).toContain(`item ${ID} note "review-checklist" is stored under subagent actor "reviewer:1"`)
  expect(r.context[0]).toContain('orchestrator-confirmation')
  // not a subagent-owned note, a prefix id, a non-orchestrator actor, a failed read: silent
  const quiet = world({ config: '', notes: { [`${ID2}::k`]: { key: 'k', actor: { id: 'u', kind: 'user' } }, [`${ID}::boom`]: new Error('x') } })
  const none = await actorAttribution(
    fakeIo(quiet),
    'manage_notes',
    upsert([
      { itemId: ID2, key: 'k', actor: orch },
      { itemId: ID.slice(0, 8), key: 'review-checklist', actor: orch },
      { itemId: ID, key: 'review-checklist', actor },
      { itemId: ID, key: 'boom', actor: orch },
    ]),
  )
  expect(none.context).toEqual([])
  expect(quiet.calls.filter(c => c.tool === 'query_notes')).toHaveLength(2)
  const many = world({ config: '' })
  const notes = Array.from({ length: 14 }, (_, i) => ({ itemId: ID, key: `k${i}`, actor: orch }))
  await actorAttribution(fakeIo(many), 'manage_notes', upsert(notes))
  expect(many.calls).toHaveLength(10)
})

// ── hooks through the engine ────────────────────────────────────────────────────────────

/** In-memory `$.state`, `$.env` and `$.mcp` for the loaded plugin, answered on the test's `on`. */
function serve(on: On, w: World, env: Record<string, string> = {}): void {
  const store = new Map<string, { value: unknown; version: number }>()
  const slot = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`
  on('state.get', async (_$, e) => ({ value: store.get(slot(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slot(e as never)
    const version = (store.get(k)?.version ?? 0) + 1
    store.set(k, { value: (e as { value: unknown }).value, version })

    return { value: { isSet: true, version } } as never
  })
  on('env.get', async (_$, e) => ({ value: env[e.name] }) as never)
  on('mcp.call', async (_$, e) => {
    const io = fakeIo(w)
    try {
      return { value: { content: [{ type: 'text', text: JSON.stringify(await io.callTo(e.tool, e.args)) }], isError: false } } as never
    } catch (err) {
      return { value: { content: [{ type: 'text', text: String(err) }], isError: true } } as never
    }
  })
}

test('integration: a recorded subagent is blocked on stop with the keys named, and the flag is forwarded', async ($, on) => {
  const w = world({ contexts: { [ID]: ctx(ID, 'work', ['implementation-notes']) } })
  serve(on, w)
  const seen: Record<string, unknown>[] = []
  on('classic.PostToolUse', async (_$, e) => {
    seen.push(e as unknown as Record<string, unknown>)

    return {} as never
  })
  await $.classic.PostToolUse({
    tool_name: `${TO}advance_item`,
    tool_input: { transitions: [{ itemId: ID, trigger: 'start', actor }] },
    tool_response: advanceResponse([{ itemId: ID, applied: true, newRole: 'work' }]),
    tool_use_id: 't1',
    agent_id: 'a1',
    agent_type: 'task-orchestrator:implementer',
  } as never)
  expect(seen[0]?.to_mod_active).toBe(true)

  const stop = await $.classic.SubagentStop({
    stop_hook_active: false,
    agent_id: 'a1',
    agent_type: 'task-orchestrator:implementer',
    agent_transcript_path: '',
  } as never)
  expect(stop.block).toContain('Item 26246238 "Phase guard" is in work with required notes still missing: implementation-notes.')
})

test('integration: events the guard does not own pass through without the flag', async ($, on) => {
  serve(on, world())
  const seen: Record<string, unknown>[] = []
  on('classic.PostToolUse', async (_$, e) => {
    seen.push(e as unknown as Record<string, unknown>)

    return {} as never
  })
  await $.classic.PostToolUse({ tool_name: `${TO}query_items`, tool_input: {}, tool_response: {}, tool_use_id: 't2', agent_id: 'a1' } as never)
  await $.classic.PostToolUse({ tool_name: 'Bash', tool_input: {}, tool_response: {}, tool_use_id: 't3' } as never)
  expect(seen).toHaveLength(2)
  for (const s of seen) expect(s.to_mod_active).toBeUndefined()
})

test('integration: a subagent with nothing recorded stops freely and the flag is forwarded', async ($, on) => {
  serve(on, world())
  const seen: Record<string, unknown>[] = []
  on('classic.SubagentStop', async (_$, e) => {
    seen.push(e as unknown as Record<string, unknown>)

    return {} as never
  })
  const r = await $.classic.SubagentStop({ stop_hook_active: false, agent_id: 'zzz', agent_type: 'general-purpose', agent_transcript_path: '' } as never)
  expect(r.block).toBeUndefined()
  expect(seen[0]?.to_mod_active).toBe(true)
})
