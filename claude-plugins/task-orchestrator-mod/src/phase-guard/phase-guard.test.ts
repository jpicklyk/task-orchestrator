// Phase guard on the 4.0 tool surface (item 5dbd8275, scenarios S5 and S6; AC3 of the frozen task-scope).
//
// The 3.x tests of this file are kept for the behaviour that survives (record on a subagent's advance,
// the two-block cap, the seat filter, the test-manifest ownership, the independence violations), with
// their fixtures moved to the 4.0 shapes: an advance answer is an envelope in `structuredContent`
// (`{ok, data: {results}}`, current/docs/api-reference.md "Response envelope" and "advance"), a gate read is
// `items_read` select `context` whose `item.id` is the 8-hex compact id (api-reference.md "items_read").
// Tests of the deleted behaviour (actor attribution, skill enforcement) are gone with it.
import type { On } from 'claude-code'
import { expect, test } from 'claude-code/testing'

import type { PhaseGuardEntry } from '../../types'
import { checkStop, normalizeEntry, recordAdvance } from './core.ts'
import type { GuardIo } from './core.ts'
import { buildReason, phaseOwnerSeat } from './helpers.ts'

const ID = '26246238-681f-497d-ae4c-6dbafd2c1e12'
const ID2 = '11111111-2222-4333-8444-555555555555'
const TO = 'mcp__mcp-task-orchestrator__'

type World = {
  entries: Map<string, PhaseGuardEntry>
  contexts: Record<string, unknown>
  headless: boolean
  calls: { tool: string; args: Record<string, unknown> }[]
}

function world(over: Partial<World> = {}): World {
  return { entries: new Map(), contexts: {}, headless: false, calls: [], ...over }
}

function fakeIo(w: World): GuardIo {
  return {
    getEntry: async id => normalizeEntry(w.entries.get(id)),
    setEntry: async (id, entry) => void w.entries.set(id, entry),
    callTo: async (tool, args) => {
      w.calls.push({ tool, args })
      if (tool === 'items_read' && args.select === 'context') {
        const c = w.contexts[args.itemId as string]
        if (c === undefined || c instanceof Error) throw c ?? new Error('no such item')

        return c
      }
      throw new Error(`unexpected ${tool}`)
    },
    isHeadless: async () => w.headless,
  }
}

/** The data of `items_read` select `context` for one item; `item.id` is the 8-hex compact id. */
const ctx = (id: string, role: string, missing: unknown[], extra: Record<string, unknown> = {}) => ({
  item: { id: id.slice(0, 8), title: 'Phase guard', role },
  gateStatus: { canAdvance: missing.length === 0, missing, ...((extra.gateStatus as object) ?? {}) },
  ...Object.fromEntries(Object.entries(extra).filter(([k]) => k !== 'gateStatus')),
})

const entered$ = (itemId: string, newRole: string) => ({ ok: true, itemId, previousRole: 'queue', newRole, trigger: 'start' })
const gateBlocked$ = (itemId: string, role: string) => ({
  ok: false,
  error: { code: 'gate_blocked', kind: 'permanent', message: 'required notes missing', detail: { itemId, role, missing: [{ key: 'k', role }] } },
})
const failed$ = (code: string) => ({ ok: false, error: { code, kind: 'permanent', message: code } })

/** The tool_response of an `advance` call: the envelope in structuredContent. */
const advanceResponse = (results: unknown[]) => {
  const bad = results.filter(r => (r as { ok: boolean }).ok === false).length
  const summary = { ok: results.length - bad, failed: bad }
  if (bad === 0) return { structuredContent: { ok: true, data: { results, summary }, meta: { reqId: 'k7f3q9ab' } } }

  return {
    structuredContent: {
      ok: false,
      error: { code: 'partial_failure', kind: 'permanent', message: `${bad} of ${results.length} failed` },
      data: { results, summary },
      meta: { reqId: 'k7f3q9ab' },
    },
  }
}
const startOps = (...ids: string[]) => ({ ops: ids.map(itemId => ({ op: 'transition', itemId, trigger: 'start' })) })

// --- record ---------------------------------------------------------------------------

test('record: a subagent start is remembered with the entered role', async () => {
  const w = world()
  const wrote = await recordAdvance(fakeIo(w), {
    agent_id: 'a1',
    tool_input: startOps(ID),
    tool_response: advanceResponse([entered$(ID, 'work')]),
  })
  expect(wrote).toBe(true)
  expect(w.entries.get('a1')).toEqual({ items: [ID], blocks: 0, enteredRoles: { [ID]: 'work' } })
})

test('record: gate_blocked records the role from the error detail; other failures record nothing', async () => {
  const w = world()
  const io = fakeIo(w)
  await recordAdvance(io, { agent_id: 'a1', tool_input: startOps(ID), tool_response: advanceResponse([gateBlocked$(ID, 'work')]) })
  expect(w.entries.get('a1')?.enteredRoles).toEqual({ [ID]: 'work' })
  const w2 = world()
  for (const code of ['not_claim_holder', 'resource_unavailable', 'dependency_unmet', 'not_found', 'invalid_transition']) {
    expect(
      await recordAdvance(fakeIo(w2), {
        agent_id: 'a1',
        tool_input: {},
        tool_response: advanceResponse([{ ok: false, error: { code, kind: 'permanent', message: code, detail: { itemId: ID, role: 'work' } } }]),
      }),
    ).toBe(false)
  }
  expect(w2.entries.size).toBe(0)
})

test('record: ignores the main loop, headless runs and a call made under a seat token', async () => {
  const w = world()
  const resp = advanceResponse([entered$(ID, 'work')])
  expect(await recordAdvance(fakeIo(w), { tool_input: startOps(ID), tool_response: resp })).toBe(false)
  expect(await recordAdvance(fakeIo(world({ headless: true })), { agent_id: 'a', tool_input: startOps(ID), tool_response: resp })).toBe(false)
  expect(await recordAdvance(fakeIo(w), { agent_id: 'a', tool_input: { ...startOps(ID), seatToken: 'seat-token-1' }, tool_response: resp })).toBe(false)
  expect(w.entries.size).toBe(0)
  // an empty seatToken is not a seat
  expect(await recordAdvance(fakeIo(w), { agent_id: 'a', tool_input: { ...startOps(ID), seatToken: '' }, tool_response: resp })).toBe(true)
  expect(w.entries.get('a')?.items).toEqual([ID])
})

test('record: caps items at 50 and prunes roles of dropped items', async () => {
  const w = world()
  const io = fakeIo(w)
  const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
  for (let i = 0; i < 55; i++) {
    await recordAdvance(io, { agent_id: 'a', tool_input: {}, tool_response: advanceResponse([entered$(uuid(i), 'work')]) })
  }
  const entry = w.entries.get('a') as PhaseGuardEntry
  expect(entry.items).toHaveLength(50)
  expect(entry.items[0]).toBe(uuid(5))
  expect(Object.keys(entry.enteredRoles)).toHaveLength(50)
  expect(entry.enteredRoles[uuid(0)]).toBeUndefined()
})

// --- stop -----------------------------------------------------------------------------

const entered = (id = ID, role = 'work'): PhaseGuardEntry => ({ items: [id], blocks: 0, enteredRoles: { [id]: role } })
const STOP_TAIL = 'Fill them via notes_write (op "upsert") before returning. If something blocks you, say what blocks you and stop.'

test('stop: names uuid8, title, role, missing keys and the hint of the first key', async () => {
  const w = world({ contexts: { [ID]: { ...ctx(ID, 'work', ['implementation-notes', 'session-tracking']), skillPointer: 'spec-quality' } } })
  w.entries.set('a', entered())
  const reason = await checkStop(fakeIo(w), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })
  expect(reason).toBe(
    'Item 26246238 "Phase guard" is in work with required notes still missing: implementation-notes, session-tracking. ' +
      `Invoke the spec-quality skill for guidance. ${STOP_TAIL}`,
  )
  expect(w.entries.get('a')?.blocks).toBe(1)
  expect(w.calls).toEqual([{ tool: 'items_read', args: { select: 'context', itemId: ID } }])
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

test('stop: the entered role is compared per full item id, so a context answering in short ids still decides', async () => {
  // The same agent entered ID in work and ID2 in review; each context names its item by the 8-hex id.
  const w = world({ contexts: { [ID]: ctx(ID, 'work', ['k1']), [ID2]: ctx(ID2, 'work', ['k2']) } })
  w.entries.set('a', { items: [ID, ID2], blocks: 0, enteredRoles: { [ID]: 'work', [ID2]: 'review' } })
  const reason = await checkStop(fakeIo(w), { agent_id: 'a', agent_type: 'x:implementer' })
  expect(reason).toContain('26246238')
  expect(reason).toContain('k1')
  expect(reason).not.toContain('k2')
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
    const w = world({ contexts: { [ID]: { item: { id: ID.slice(0, 8), title: 'T', role: 'work' }, gateStatus: { canAdvance, missing, violations } } } })
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

test('stop: a workflow-subagent never blocks, whatever it entered', async () => {
  const w = world({ contexts: { [ID]: ctx(ID, 'work', ['implementation-notes']) } })
  w.entries.set('a', entered())
  expect(await checkStop(fakeIo(w), { agent_id: 'a', agent_type: 'workflow-subagent' })).toBeNull()
  // the same entry blocks for an ordinary implementer, so the null above is the agent type's doing
  expect(await checkStop(fakeIo(w), { agent_id: 'a', agent_type: 'task-orchestrator:implementer' })).not.toBeNull()
})

test('seat matching is segment-exact', () => {
  expect(phaseOwnerSeat('task-orchestrator:implementer-helper')).toBeNull()
  expect(phaseOwnerSeat('task-orchestrator:reviewer')).toBe('reviewer')
})

// --- hooks through the engine ---------------------------------------------------------

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
      const data = await io.callTo(e.tool, e.args)

      return { value: { content: [{ type: 'text', text: 'ok' }], structuredContent: { ok: true, data }, isError: false } } as never
    } catch (err) {
      const message = String(err)

      return {
        value: { content: [{ type: 'text', text: `not_found: ${message}` }], structuredContent: { ok: false, error: { code: 'not_found', kind: 'permanent', message } }, isError: true },
      } as never
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
    tool_name: `${TO}advance`,
    tool_input: startOps(ID),
    tool_response: advanceResponse([entered$(ID, 'work')]),
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
  expect(stop.block).toContain(STOP_TAIL)
})

test('integration: events the guard does not own pass through without the flag', async ($, on) => {
  serve(on, world())
  const seen: Record<string, unknown>[] = []
  on('classic.PostToolUse', async (_$, e) => {
    seen.push(e as unknown as Record<string, unknown>)

    return {} as never
  })
  await $.classic.PostToolUse({ tool_name: `${TO}items_read`, tool_input: {}, tool_response: {}, tool_use_id: 't2', agent_id: 'a1' } as never)
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
