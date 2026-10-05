import { expect, test } from 'claude-code/testing'

import { isOwnCall, rewriteCall } from './rewrite.ts'

const ROOT = 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273'
const unchanged = (tool: string, input: Record<string, unknown>, root: string | null = ROOT) => {
  const r = rewriteCall(tool, input, root)
  expect(r.changes).toEqual([])
  expect(r.input).toBe(input)
}

test('rule A: injects ancestorId on the four scoped shapes', () => {
  for (const [tool, input] of [
    ['query_items', { operation: 'search' }],
    ['get_next_item', {}],
    ['get_blocked_items', {}],
    ['get_context', { mode: 'health-check' }],
    ['get_context', {}],
    ['get_context', { mode: 'session-resume', since: 'x' }],
  ] as const) {
    const r = rewriteCall(tool, { ...input }, ROOT)
    expect(r.input.ancestorId).toBe(ROOT)
    expect(r.changes).toEqual([`${tool} +ancestorId=ce064d2c`])
  }
})

test('rule A: skip conditions leave input untouched', () => {
  unchanged('query_items', { operation: 'search', query: 'foo' })
  unchanged('query_items', { operation: 'overview' })
  unchanged('query_items', { operation: 'get', itemId: 'abcd' })
  unchanged('query_items', { operation: 'search', ancestorId: 'x' })
  unchanged('query_items', { operation: 'search', ancestorId: null })
  unchanged('query_items', { operation: 'search', parentId: 'p' })
  unchanged('query_items', { operation: 'search', depth: 0 })
  unchanged('query_items', { operation: 'search', tags: 'retrospective-trend' })
  unchanged('query_items', { operation: 'search', tags: 'container' })
  unchanged('query_items', { operation: 'search', tags: 'bug, anything' })
  unchanged('query_items', { operation: 'search', type: 'agent-observation' })
  unchanged('query_items', { operation: 'search', type: 'feature-task' })
  unchanged('get_next_item', { tags: 'improvement-proposal' })
  unchanged('get_blocked_items', { type: 'x' })
  unchanged('get_context', { mode: 'health-check', tags: 'container' })
  unchanged('query_items', { operation: 'search', ancestorId: '' })
  unchanged('get_context', { itemId: 'abcd' })
  unchanged('get_context', { mode: 'item' })
  unchanged('query_items', { operation: 'search' }, null)
  unchanged('get_blocked_items', {}, null)
  unchanged('advance_item', {})
})

test('rule A: parentId null counts as absent, so ancestorId is injected', () => {
  const r = rewriteCall('query_items', { operation: 'search', parentId: null }, ROOT)
  expect(r.input.ancestorId).toBe(ROOT)
})

test('rule B: reserved agentId/tool_use_id survive a rewrite', () => {
  const r = rewriteCall('get_next_item', { agentId: 'ag1', tool_use_id: 'tu1' }, ROOT)
  expect(r.input.agentId).toBe('ag1')
  expect(r.input.tool_use_id).toBe('tu1')
  expect(r.input.ancestorId).toBe(ROOT)
  const actor = { id: 'a', kind: 'user' }
  const n = rewriteCall('manage_notes', { operation: 'upsert', actor, agentId: 'ag1', tool_use_id: 'tu1', notes: [{ key: 'k' }] }, null)
  expect(n.input.agentId).toBe('ag1')
  expect(n.input.tool_use_id).toBe('tu1')
})

test('rule B: copies the actor only into actor-less elements, keeps top-level', () => {
  const own = { id: 'own', kind: 'user' }
  const actor = { id: 'a', kind: 'subagent' }
  const input = { operation: 'upsert', actor, requestId: 'r', notes: [{ key: 'a' }, { key: 'b', actor: own }, { key: 'c' }] }
  const r = rewriteCall('manage_notes', input, null)
  expect(r.changes).toEqual(['manage_notes actor -> notes[0,2]'])
  expect(r.input.actor).toEqual(actor)
  expect(r.input.requestId).toBe('r')
  expect(r.input.notes).toEqual([
    { key: 'a', actor },
    { key: 'b', actor: own },
    { key: 'c', actor },
  ])
  expect(input.notes[0]).toEqual({ key: 'a' })
})

test('rule B: no change for delete, missing actor, non-array notes, all-actored', () => {
  const actor = { id: 'a', kind: 'user' }
  unchanged('manage_notes', { operation: 'delete', actor, notes: [{ key: 'a' }] })
  unchanged('manage_notes', { operation: 'upsert', notes: [{ key: 'a' }] })
  unchanged('manage_notes', { operation: 'upsert', actor, notes: 'nope' })
  unchanged('manage_notes', { operation: 'upsert', actor, notes: [{ key: 'a', actor }] })
})

test('own-call predicate', () => {
  expect(isOwnCall('task-orchestrator-mod', 'task-orchestrator-mod')).toBe(true)
  expect(isOwnCall('engine', 'task-orchestrator-mod')).toBe(false)
  expect(isOwnCall(undefined, 'task-orchestrator-mod')).toBe(false)
})

const CONFIG = `project:\n  rootId: ${ROOT}\n`

test('integration: tool.call repairs input beneath to the command hooks and logs', async ($, on) => {
  const seen: Record<string, unknown>[] = []
  const logs: string[] = []
  on('fs.read', async () => ({ value: CONFIG }) as never)
  on('ui.log', async (_$, e) => {
    logs.push(e.text)

    return { value: undefined } as never
  })
  on('tool.call', async (_$, e) => {
    seen.push(e as unknown as Record<string, unknown>)

    return { ref: 'r', result: 'ok', text: 'ok' } as never
  })

  await $.tool.call({ tool: 'mcp__mcp-task-orchestrator__query_items', operation: 'search' } as never)
  await $.tool.call({
    tool: 'mcp__mcp-task-orchestrator__manage_notes',
    operation: 'upsert',
    actor: { id: 'a', kind: 'user' },
    notes: [{ itemId: 'x', key: 'k', role: 'work' }],
  } as never)

  expect(seen[0]?.ancestorId).toBe(ROOT)
  expect((seen[1]?.notes as { actor?: unknown }[])[0]?.actor).toEqual({ id: 'a', kind: 'user' })
  expect(logs.some(l => l.includes('call-shape: query_items +ancestorId=ce064d2c'))).toBe(true)
  expect(logs.some(l => l.includes('call-shape: manage_notes actor -> notes[0]'))).toBe(true)
})

test('integration: unscoped when the config is unreadable', async ($, on) => {
  const seen: Record<string, unknown>[] = []
  on('fs.read', async () => {
    throw new Error('missing')
  })
  on('tool.call', async (_$, e) => {
    seen.push(e as unknown as Record<string, unknown>)

    return { ref: 'r', result: 'ok', text: 'ok' } as never
  })

  await $.tool.call({ tool: 'mcp__mcp-task-orchestrator__query_items', operation: 'search' } as never)
  expect(seen[0]?.ancestorId).toBeUndefined()
})

test('integration: nothing logged when nothing changed; tagged listings stay unscoped', async ($, on) => {
  const seen: Record<string, unknown>[] = []
  const logs: string[] = []
  on('fs.read', async () => ({ value: CONFIG }) as never)
  on('ui.log', async (_$, e) => {
    logs.push(e.text)

    return { value: undefined } as never
  })
  on('tool.call', async (_$, e) => {
    seen.push(e as unknown as Record<string, unknown>)

    return { ref: 'r', result: 'ok', text: 'ok' } as never
  })

  await $.tool.call({ tool: 'mcp__mcp-task-orchestrator__query_items', operation: 'search', tags: 'retrospective-trend' } as never)
  await $.tool.call({ tool: 'mcp__mcp-task-orchestrator__query_items', operation: 'search', ancestorId: '' } as never)

  expect(seen[0]?.ancestorId).toBeUndefined()
  expect(seen[1]?.ancestorId).toBe('')
  expect(logs.filter(l => l.includes('call-shape'))).toEqual([])
})
