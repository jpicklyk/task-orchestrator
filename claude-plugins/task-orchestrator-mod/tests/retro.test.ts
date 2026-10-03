// Tests of the retrospective feature (T5 0f4fe024): the pure logic as table-driven units, and the
// loaded plugin driven through `$.tool.call`, `$.classic.PostToolUse` and `$.classic.Stop` with
// `$.state`, `$.env`, `$.fs`, `$.clock` and `$.agent` answered on the test's `on`.
import type { On } from 'claude-code'
import { expect, test } from 'claude-code/testing'

import type { RetroState } from '../types'
import { RETRO_OWNED_FLAG } from '../src/retro/index.ts'
import {
  ackState,
  applyCall,
  backstopRoots,
  buildDispatch,
  buildNudge,
  buildSpawnPrompt,
  classifyCall,
  extractResponseJson,
  holdsForTasks,
  parseRetrospectiveConfig,
  QUEUED_NOTICE,
} from '../src/retro/logic.ts'
import type { RetroConfig } from '../src/retro/logic.ts'

const TO = 'mcp__mcp-task-orchestrator__'
const ROOT = 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273'
const A = 'aaaaaaaa-0000-4000-8000-000000000001'
const B = 'bbbbbbbb-0000-4000-8000-000000000002'
const C = 'cccccccc-0000-4000-8000-000000000003'
const MIN = 60_000

const cfg = (over: Partial<RetroConfig> = {}): RetroConfig => ({ mode: 'dispatch', dispatchThreshold: 3, cooldownMinutes: 30, ...over })

// ── config ──────────────────────────────────────────────────────────────────────────────

test('config: defaults, block form, inline form and invalid values', () => {
  expect(parseRetrospectiveConfig(null)).toEqual({ mode: 'nudge', dispatchThreshold: 3, cooldownMinutes: 30 })
  expect(parseRetrospectiveConfig('retrospective:\n  mode: Dispatch\n  dispatchThreshold: 5\n  cooldownMinutes: 10\n')).toEqual({
    mode: 'dispatch',
    dispatchThreshold: 5,
    cooldownMinutes: 10,
  })
  expect(parseRetrospectiveConfig('retrospective: { mode: off }').mode).toBe('off')
  expect(parseRetrospectiveConfig('retrospective:\n  mode: bogus\n  dispatchThreshold: -1\n  cooldownMinutes: 0\n')).toEqual({
    mode: 'nudge',
    dispatchThreshold: 3,
    cooldownMinutes: 30,
  })
})

// ── classification parity with retro-trigger.mjs (AC1) ──────────────────────────────────

const adv = (results: unknown[]) => ({ results, summary: {} })

test('classify: table of advance_item and complete_tree shapes', () => {
  const cascade = (parent: string, applied = true) => ({ itemId: 'x', newRole: 'work', cascadeEvents: [{ itemId: parent, targetRole: 'terminal', applied }] })
  const cases: [string, Record<string, unknown>, Record<string, unknown> | null, unknown][] = [
    ['advance_item', {}, adv([cascade(A)]), { kind: 'PARENT_COMPLETION', roots: [A], count: 1 }],
    ['advance_item', {}, adv([cascade(A, false)]), null],
    ['advance_item', {}, adv([{ itemId: B, newRole: 'terminal' }, cascade(A)]), { kind: 'PARENT_COMPLETION', roots: [A], count: 2 }],
    ['advance_item', {}, adv([{ itemId: B, newRole: 'terminal' }]), { kind: 'LONE_TERMINAL', roots: [B], count: 1 }],
    ['advance_item', {}, adv([{ itemId: B, newRole: 'terminal', unblockedItems: [{ itemId: C }] }, { itemId: A, newRole: 'terminal' }]), { kind: 'LONE_TERMINAL', roots: [A], count: 1 }],
    ['advance_item', {}, adv([{ itemId: B, newRole: 'terminal', unblockedItems: [{ itemId: C }] }]), null],
    ['advance_item', {}, adv([{ itemId: B, newRole: 'work' }]), null],
    ['advance_item', {}, null, null],
    ['complete_tree', { rootId: A }, { summary: { completed: 4 } }, { kind: 'PARENT_COMPLETION', roots: [A], count: 4 }],
    ['complete_tree', { itemIds: [A, B] }, { summary: { completed: 2 } }, { kind: 'PARENT_COMPLETION', roots: [A, B], count: 2 }],
    ['complete_tree', {}, { summary: { completed: 3 } }, { kind: 'UNROOTED', count: 3 }],
    ['complete_tree', { rootId: A }, { summary: { completed: 0 } }, null],
    ['complete_tree', { rootId: A }, { summary: {} }, null],
    ['query_items', {}, adv([{ itemId: B, newRole: 'terminal' }]), null],
  ]
  for (const [tool, input, resp, want] of cases) expect(classifyCall(tool, input, resp)).toEqual(want)
})

test('extractResponseJson accepts every encoding', () => {
  const payload = { results: [], summary: {} }
  const text = JSON.stringify(payload)
  expect(extractResponseJson(text)).toEqual(payload)
  expect(extractResponseJson([{ type: 'text', text }])).toEqual(payload)
  expect(extractResponseJson({ content: [{ type: 'text', text }] })).toEqual(payload)
  expect(extractResponseJson({ type: 'text', text })).toEqual(payload)
  expect(extractResponseJson({ structuredContent: payload })).toEqual(payload)
  expect(extractResponseJson(payload)).toEqual(payload)
  expect(extractResponseJson('nope')).toBeNull()
  expect(extractResponseJson(null)).toBeNull()
  expect(extractResponseJson({ other: 1 })).toBeNull()
})

// ── reducers (AC2, AC3) ─────────────────────────────────────────────────────────────────

const parent = (roots: string[], count: number) => ({ kind: 'PARENT_COMPLETION' as const, roots, count })

test('apply: nudge mode nudges; dispatch below threshold nudges; at threshold queues', () => {
  expect(applyCall({}, parent([A], 5), cfg({ mode: 'nudge' }), 1000, false).action).toBe('nudge')
  expect(applyCall({}, parent([A], 2), cfg(), 1000, false).action).toBe('nudge')
  const q = applyCall({}, parent([A], 3), cfg(), 1000, false)
  expect(q.action).toBe('queue')
  expect(q.state.pendingDispatch).toEqual([A])
  expect(q.state.handledAt).toBe(1000)
  expect(q.state.terminalCount).toBe(0)
})

test('apply: lone terminals accumulate toward the substance gate', () => {
  let state: RetroState = {}
  state = applyCall(state, { kind: 'LONE_TERMINAL', roots: [B], count: 1 }, cfg(), 10, false).state
  state = applyCall(state, { kind: 'LONE_TERMINAL', roots: [C], count: 1 }, cfg(), 20, false).state
  expect(state).toMatchObject({ sawTerminal: true, terminalCount: 2, pendingRoots: [B, C], lastTerminalAt: 20 })
  const out = applyCall(state, parent([A], 1), cfg(), 30, false)
  expect(out.action).toBe('queue')
  expect(out.state).toMatchObject({ sawTerminal: false, pendingRoots: [], terminalCount: 0 })
})

test('apply: an unrooted complete_tree counts toward substance only', () => {
  const out = applyCall({ terminalCount: 1 }, { kind: 'UNROOTED', count: 2 }, cfg(), 5, false)
  expect(out.action).toBe('none')
  expect(out.state).toMatchObject({ terminalCount: 3, sawTerminal: true })
  expect(out.state.pendingRoots).toBeUndefined()
})

test('apply: cooldown suppresses a repeat of the same roots; a new root bypasses it; expiry re-arms', () => {
  const T0 = 1_000
  const first = applyCall({}, parent([A], 3), cfg(), T0, false).state
  expect(applyCall(first, parent([A], 3), cfg(), T0 + 5 * MIN, false).action).toBe('none')
  const fresh = applyCall(first, parent([B], 3), cfg(), T0 + 5 * MIN, false)
  expect(fresh.action).toBe('queue')
  expect(fresh.state.pendingDispatch).toEqual([A, B])
  expect(fresh.state.rootUuids).toEqual([A, B])
  expect(applyCall(first, parent([A], 3), cfg(), T0 + 31 * MIN, false).action).toBe('queue')
})

test('apply: the rootUuids list keeps only the newest 50', () => {
  const many = Array.from({ length: 60 }, (_, i) => `r${i}`)
  const out = applyCall({}, parent(many, 1), cfg({ mode: 'nudge' }), 1, false)
  expect(out.state.rootUuids).toHaveLength(50)
  expect(out.state.rootUuids?.[49]).toBe('r59')
})

test('apply: a subagent queues at threshold in dispatch mode, otherwise records like a lone terminal', () => {
  const q = applyCall({}, parent([A], 3), cfg(), 1, true)
  expect(q.action).toBe('queue')
  const below = applyCall({}, parent([A], 2), cfg(), 1, true)
  expect(below.action).toBe('none')
  expect(below.state).toMatchObject({ sawTerminal: true, pendingRoots: [A], terminalCount: 2 })
  expect(below.state.handledAt).toBeUndefined()
  const nudgeMode = applyCall({}, parent([A], 9), cfg({ mode: 'nudge' }), 1, true)
  expect(nudgeMode.action).toBe('none')
  expect(nudgeMode.state.sawTerminal).toBe(true)
})

test('backstop: nudges only when sawTerminal and past cooldown; no roots means no UUID', () => {
  expect(backstopRoots({}, cfg(), 0)).toBeNull()
  expect(backstopRoots({ sawTerminal: true, handledAt: 1 }, cfg(), 10 * MIN)).toBeNull()
  expect(backstopRoots({ sawTerminal: true, handledAt: 1 }, cfg(), 31 * MIN)).toEqual([])
  expect(backstopRoots({ sawTerminal: true, pendingRoots: [B] }, cfg(), 5)).toEqual([B])
  expect(buildNudge([])).not.toMatch(/[0-9a-f]{8}-/)
  expect(buildNudge([B])).toContain(`/session-retrospective ${B}`)
})

test('ack stamps handledAt, clears the recorded run and (optionally) the queued dispatch', () => {
  const s: RetroState = { sawTerminal: true, pendingRoots: [A], terminalCount: 4, pendingDispatch: [A] }
  expect(ackState(s, 9, true)).toMatchObject({ handledAt: 9, sawTerminal: false, pendingRoots: [], terminalCount: 0, pendingDispatch: null })
  expect(ackState(s, 9, false).pendingDispatch).toEqual([A])
})

test('texts: dispatch fallback and spawn prompt carry the roots and scope', () => {
  expect(buildDispatch([A, B], ROOT)).toContain(`/session-retrospective ${A}, ${B}, project ancestorId ${ROOT}`)
  expect(buildDispatch([A], null)).toContain('none configured')
  expect(buildSpawnPrompt([A], ROOT)).toContain(`project ancestorId ${ROOT}`)
  expect(buildSpawnPrompt([A], null)).toContain('no project ancestorId')
  expect(QUEUED_NOTICE).not.toContain('subagent_type')
  expect(holdsForTasks([{ status: 'completed' }, { status: 'pending' }])).toBe(true)
  expect(holdsForTasks([{ status: 'completed' }, { status: 'failed' }])).toBe(false)
})

// ── the loaded plugin ───────────────────────────────────────────────────────────────────

type Agent = { id: string; status: string; parentId?: string; description?: string; type?: string }
type Rig = {
  state: Map<string, { value: unknown; version: number }>
  spawns: { prompt: string; subagentType?: string; model?: string; description?: string }[]
  seen: Record<string, unknown>[]
  now: { t: number }
  agents: Agent[]
  spawnMode: { deny?: string; throws?: boolean }
  /** What the base `tool.call` answers. */
  reply: () => unknown
  /** What the base `classic.Stop` answers, and the events it received. */
  stopReply: Record<string, unknown>
  stops: Record<string, unknown>[]
  post: Record<string, unknown>[]
}

const retroState = (rig: Rig): RetroState => (rig.state.get('task-orchestrator-mod/retro/')?.value ?? {}) as RetroState

/** In-memory `$.state`, `$.env`, `$.fs`, `$.clock` and `$.agent` on the test's `on`. */
function rig(on: On, opts: { config?: string | null; env?: Record<string, string>; agents?: Agent[] } = {}): Rig {
  const r: Rig = { state: new Map(), spawns: [], seen: [], now: { t: 1_000_000 }, agents: opts.agents ?? [], spawnMode: {}, reply: () => ({ ref: 'r', result: 'ok', text: 'ok' }), stopReply: {}, stops: [], post: [] }
  const config = opts.config === undefined ? `project:\n  rootId: ${ROOT}\n\nretrospective:\n  mode: dispatch\n  dispatchThreshold: 3\n` : opts.config
  const slot = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`
  on('state.get', async (_$, e) => ({ value: r.state.get(slot(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slot(e as never)
    const cur = r.state.get(k)?.version ?? 0
    const ifVersion = (e as { ifVersion?: number }).ifVersion
    if (ifVersion !== undefined && ifVersion !== cur) return { value: { isSet: false, version: cur } } as never
    r.state.set(k, { value: (e as { value: unknown }).value, version: cur + 1 })

    return { value: { isSet: true, version: cur + 1 } } as never
  })
  on('env.get', async (_$, e) => ({ value: opts.env?.[e.name] }) as never)
  on('fs.read', async () => {
    if (config === null) throw new Error('ENOENT')

    return { value: config } as never
  })
  on('tool.call', async () => r.reply() as never)
  on('classic.Stop', async (_$, e) => {
    r.stops.push(e as never)

    return r.stopReply as never
  })
  on('classic.PostToolUse', async (_$, e) => {
    r.post.push(e as never)

    return {} as never
  })
  on('clock.now', async () => ({ value: r.now.t }) as never)
  on('agent.list', async () => ({ value: r.agents }) as never)
  on('agent.spawn', async (_$, e) => {
    if (r.spawnMode.throws) throw new Error('boom')
    if (r.spawnMode.deny) return { deny: r.spawnMode.deny } as never
    r.spawns.push({ prompt: e.prompt, subagentType: (e as { subagent_type?: string }).subagent_type ?? e.subagentType, model: e.model, description: e.description })

    return { model: 'sonnet', agentId: `retro-${r.spawns.length}` } as never
  })

  return r
}

const advanceText = (results: unknown[]) => ({ ref: 'r', result: {}, text: JSON.stringify({ results, summary: {} }) })
const cascadeOf = (parentId: string, n = 1) => [
  ...Array.from({ length: n - 1 }, (_, i) => ({ itemId: `child-${i}`, newRole: 'terminal' })),
  { itemId: 'last', newRole: 'work', cascadeEvents: [{ itemId: parentId, targetRole: 'terminal', applied: true }] },
]
const advanceCall = { tool: `${TO}advance_item`, transitions: [] } as never

test('integration: dispatch mode at threshold queues, adds only the one-line notice, and spawns nothing yet', async ($, on) => {
  const g = rig(on)
  g.reply = () => advanceText(cascadeOf(A, 3))
  const r = await $.tool.call(advanceCall)
  expect(r.context).toEqual([QUEUED_NOTICE])
  expect(JSON.stringify(r.context)).not.toContain('Retrospective dispatch')
  expect(retroState(g).pendingDispatch).toEqual([A])
  expect(g.spawns).toHaveLength(0)
})

test('integration: below threshold in dispatch mode, and nudge mode, add a nudge', async ($, on) => {
  const g = rig(on)
  g.reply = () => advanceText(cascadeOf(A, 2))
  const r = await $.tool.call(advanceCall)
  expect(r.context?.[0]).toContain('Retrospective suggested')
  expect(retroState(g).pendingDispatch).toBeUndefined()
})

test('integration: mode off has no effect and touches no state', async ($, on) => {
  const g = rig(on, { config: 'retrospective:\n  mode: off\n' })
  g.reply = () => advanceText(cascadeOf(A, 5))
  const r1 = await $.tool.call(advanceCall)
  expect(r1.context).toBeUndefined()
  expect(g.state.size).toBe(0)
})

test('integration: no cwd config leaves everything to the command hooks', async ($, on) => {
  const g = rig(on, { config: null })
  g.reply = () => advanceText(cascadeOf(A, 5))
  const r = await $.tool.call(advanceCall)
  expect(r.context).toBeUndefined()
  expect(g.state.size).toBe(0)
  const e = { tool_name: `${TO}complete_tree`, tool_input: {}, tool_response: {}, tool_use_id: 't' }
  await $.classic.PostToolUse(e as never)
  await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g.post[0]).toMatchObject(e)
  expect(g.post[0]?.[RETRO_OWNED_FLAG]).toBeUndefined()
  expect(g.state.size).toBe(0)
})

test('integration: a headless iteration is not owned', async ($, on) => {
  const g = rig(on, { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } })
  g.reply = () => advanceText(cascadeOf(A, 5))
  const r = await $.tool.call(advanceCall)
  expect(r.context).toBeUndefined()
  expect(g.state.size).toBe(0)
})

test('integration: owned events reach next carrying to_mod_retro (AC6)', async ($, on) => {
  const g = rig(on)
  await $.classic.PostToolUse({ tool_name: `${TO}complete_tree`, tool_input: {}, tool_response: {}, tool_use_id: 't' } as never)
  await $.classic.PostToolUse({ tool_name: `${TO}query_items`, tool_input: {}, tool_response: {}, tool_use_id: 't2' } as never)
  await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g.post[0]?.[RETRO_OWNED_FLAG]).toBe(true)
  expect(g.post[1]?.[RETRO_OWNED_FLAG]).toBeUndefined()
  expect(g.stops[0]?.[RETRO_OWNED_FLAG]).toBe(true)
  expect(g.spawns).toHaveLength(0)
})

test('integration: a clear Stop spawns exactly one retrospective with the expected args (AC4)', async ($, on) => {
  const g = rig(on)
  g.reply = () => advanceText(cascadeOf(A, 3))
  await $.tool.call(advanceCall)
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toBeUndefined()
  expect(g.spawns).toHaveLength(1)
  expect(g.spawns[0]).toMatchObject({ subagentType: 'general-purpose', model: 'sonnet', description: 'Session retrospective' })
  expect(g.spawns[0]?.prompt).toContain(`/session-retrospective ${A}`)
  expect(g.spawns[0]?.prompt).toContain(`project ancestorId ${ROOT}`)
  expect(retroState(g)).toMatchObject({ pendingDispatch: null, dispatched: { roots: [A] } })
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(g.spawns).toHaveLength(1)
})

test('integration: a running background task holds the dispatch; the next clear Stop spawns once with the union', async ($, on) => {
  const g = rig(on)
  let results = cascadeOf(A, 3)
  g.reply = () => advanceText(results)
  await $.tool.call(advanceCall)
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [{ id: 'b1', type: 'shell', status: 'running', description: 'x' }] } as never)
  expect(g.spawns).toHaveLength(0)
  expect(retroState(g).pendingDispatch).toEqual([A])
  results = cascadeOf(B, 3)
  await $.tool.call(advanceCall)
  expect(retroState(g).pendingDispatch).toEqual([A, B])
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [{ id: 'b1', type: 'subagent', status: 'completed', description: 'x' }] } as never)
  expect(g.spawns).toHaveLength(1)
  expect(g.spawns[0]?.prompt).toContain(`${A}, ${B}`)
})

test('integration: without background_tasks the hold falls back to agent.list', async ($, on) => {
  const g = rig(on, { agents: [{ id: 'w1', status: 'running' }] })
  g.reply = () => advanceText(cascadeOf(A, 3))
  await $.tool.call(advanceCall)
  await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g.spawns).toHaveLength(0)
  g.agents = [{ id: 'w1', status: 'completed' }]
  await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g.spawns).toHaveLength(1)
})

test('integration: two racing Stops spawn once', async ($, on) => {
  const g = rig(on)
  g.reply = () => advanceText(cascadeOf(A, 3))
  await $.tool.call(advanceCall)
  await Promise.all([$.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never), $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)])
  expect(g.spawns).toHaveLength(1)
})

type SpawnFailure = { deny?: string; throws?: boolean }
async function failedSpawn($: Parameters<Parameters<typeof test>[1]>[0], on: On, mode: SpawnFailure): Promise<void> {
  const g = rig(on)
  g.spawnMode = mode
  g.reply = () => advanceText(cascadeOf(A, 3))
  await $.tool.call(advanceCall)
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toContain('Retrospective dispatch')
  expect(r.block).toContain(`/session-retrospective ${A}`)
  expect(retroState(g).pendingDispatch).toBeNull()
}

test('integration: a denied spawn falls back to a Stop block carrying the directive', async ($, on) => failedSpawn($, on, { deny: 'no agents' }))
test('integration: a throwing spawn falls back to a Stop block carrying the directive', async ($, on) => failedSpawn($, on, { throws: true }))

test('integration: stop_hook_active returns without spawning or nudging', async ($, on) => {
  const g = rig(on)
  g.reply = () => advanceText(cascadeOf(A, 3))
  await $.tool.call(advanceCall)
  const r = await $.classic.Stop({ stop_hook_active: true, background_tasks: [] } as never)
  expect(r.block).toBeUndefined()
  expect(g.spawns).toHaveLength(0)
  expect(retroState(g).pendingDispatch).toEqual([A])
})

test('integration: the backstop nudges a lone terminal once, never spawns, and keeps an existing block (AC5)', async ($, on) => {
  const g = rig(on)
  g.reply = () => advanceText([{ itemId: B, newRole: 'terminal' }])
  await $.tool.call(advanceCall)
  expect(retroState(g)).toMatchObject({ sawTerminal: true, pendingRoots: [B] })
  g.stopReply = { block: 'other plugin' }
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toContain('other plugin')
  expect(r.block).toContain(`/session-retrospective ${B}`)
  expect(g.spawns).toHaveLength(0)
  const again = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(again.block).toBe('other plugin')
})

test('integration: the backstop with no recorded roots carries no UUID', async ($, on) => {
  // an unrooted complete_tree supplies substance but no root
  const g2 = rig(on)
  g2.reply = () => ({ ref: 'r', result: {}, text: JSON.stringify({ summary: { completed: 2 } }) })
  await $.tool.call({ tool: `${TO}complete_tree` } as never)
  const r = await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g2.spawns).toHaveLength(0)
  expect(r.block).toContain('`/session-retrospective`')
  expect(r.block).not.toMatch(/[0-9a-f]{8}-/)
})

test('integration: calls from the retro agent and its descendants are ignored (AC7)', async ($, on) => {
  const g = rig(on, { agents: [{ id: 'retro-1', status: 'running' }, { id: 'kid', status: 'running', parentId: 'retro-1' }] })
  g.state.set('task-orchestrator-mod/retro/', { value: { retroAgents: ['retro-1'] }, version: 1 })
  g.reply = () => advanceText(cascadeOf(C, 5))
  const before = JSON.stringify(retroState(g))
  for (const agentId of ['retro-1', 'kid']) {
    const r = await $.tool.call({ ...(advanceCall as object), agentId } as never)
    expect(r.context).toBeUndefined()
  }
  expect(JSON.stringify(retroState(g))).toBe(before)
  const own = await $.tool.call({ ...(advanceCall as object), agentId: 'someone-else' } as never)
  expect(own.context).toEqual([QUEUED_NOTICE])
})

test('integration: a retro-ack Bash call acks the mod state', async ($, on) => {
  const g = rig(on)
  g.reply = () => advanceText([{ itemId: B, newRole: 'terminal' }])
  await $.tool.call(advanceCall)
  expect(retroState(g).sawTerminal).toBe(true)
  g.now.t += 1000
  g.reply = () => ({ ref: 'r', result: 'ok', text: 'ok' })
  await $.tool.call({ tool: 'Bash', command: 'node ${CLAUDE_PLUGIN_ROOT}/hooks/retro-ack.mjs' } as never)
  expect(retroState(g)).toMatchObject({ sawTerminal: false, pendingRoots: [], terminalCount: 0, handledAt: g.now.t })
  const r = await $.classic.Stop({ stop_hook_active: false } as never)
  expect(r.block).toBeUndefined()
})

test('integration: a subagent loop queues a dispatch at threshold in dispatch mode', async ($, on) => {
  const g = rig(on)
  g.reply = () => advanceText(cascadeOf(A, 3))
  const r = await $.tool.call({ ...(advanceCall as object), agentId: 'impl-1' } as never)
  expect(r.context).toEqual([QUEUED_NOTICE])
  expect(retroState(g).pendingDispatch).toEqual([A])
})
