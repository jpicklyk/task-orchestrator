// Call-shape rewrite for the 4.0 tool surface (item 5dbd8275, scenarios S2 to S4; AC2 of the frozen task-scope).
//
// Oracles: the task-scope AC2 rule set (R1, R2, W1 to W4) and the 4.0 call shapes of
// current/docs/api-reference.md ("items_read" selects and filters; "advance" call shape: `ops` array of flat
// objects naming their `op`, optional top-level `atomic`, `seatToken`; the `actor` of an element is the
// call's principal, so a caller-supplied one is dropped).
import { expect, test } from 'claude-code/testing'

import { isOwnCall, rewriteCall } from './rewrite.ts'

const ROOT = 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273'
const OTHER_ROOT = '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d'
const TO = 'mcp__mcp-task-orchestrator__'
const ACTOR = { id: 'implementer:x', kind: 'subagent', parent: 'workflow:r-1' }

/** The tool names of the 4.0 catalog (claude-plugins/task-orchestrator/hooks/lib/tool-catalog.json). */
const CATALOG_TOOLS = ['advance', 'config', 'deps_read', 'deps_write', 'docs_read', 'docs_write', 'items_read', 'items_write', 'notes_read', 'notes_write', 'report', 'runs_read', 'runs_write']

const unchanged = (tool: string, input: Record<string, unknown>, root: string | null = ROOT) => {
  const r = rewriteCall(tool, input, root)
  expect(r.changes).toEqual([])
  expect(r.input).toBe(input)
}

// --- S2: R1 injection and R2 opt-out -----------------------------------------------------

test('S2 R1: injects ancestorId on items_read list, next, blocked and context without an itemId', () => {
  for (const input of [{ select: 'list' }, { select: 'next' }, { select: 'blocked' }, { select: 'context' }, { select: 'list', depth: 1 }, { select: 'list', parentId: null }]) {
    const r = rewriteCall('items_read', { ...input }, ROOT)
    expect(r.input.ancestorId).toBe(ROOT)
    expect(r.input.select).toBe(input.select)
    expect(r.changes).toHaveLength(1)
    expect(r.changes[0]).toContain('items_read')
  }
})

test('S2 R1: injects the root it is given, not a fixed one', () => {
  expect(rewriteCall('items_read', { select: 'list' }, OTHER_ROOT).input.ancestorId).toBe(OTHER_ROOT)
})

test('S2 R1: does not mutate the input it was given', () => {
  const input = { select: 'list', limit: 20 }
  const r = rewriteCall('items_read', input, ROOT)
  expect(r.input).not.toBe(input)
  expect(input).toEqual({ select: 'list', limit: 20 })
  expect(r.input.limit).toBe(20)
})

test('S2 R1: every skip condition leaves the input untouched', () => {
  // other selects
  unchanged('items_read', { select: 'get', itemId: 'abcdef12' })
  unchanged('items_read', { select: 'search', query: 'foo' })
  unchanged('items_read', { select: 'overview' })
  unchanged('items_read', { select: 'history' })
  unchanged('items_read', { select: 'schema', type: 'feature-task' })
  // context with an item
  unchanged('items_read', { select: 'context', itemId: 'abcdef12' })
  // ancestorId already given
  unchanged('items_read', { select: 'list', ancestorId: 'abcdef12' })
  unchanged('items_read', { select: 'next', ancestorId: ROOT })
  // a narrower or global question
  unchanged('items_read', { select: 'list', parentId: 'abcdef12' })
  unchanged('items_read', { select: 'list', depth: 0 })
  unchanged('items_read', { select: 'list', tags: ['retrospective-trend'] })
  unchanged('items_read', { select: 'next', tags: ['improvement-proposal'] })
  unchanged('items_read', { select: 'list', type: 'agent-observation' })
  unchanged('items_read', { select: 'blocked', type: 'feature-task' })
  unchanged('items_read', { select: 'list', traits: ['needs-test-author'] })
  // no project root known
  unchanged('items_read', { select: 'list' }, null)
  unchanged('items_read', { select: 'blocked' }, null)
})

test('S2 R1: applies to items_read only', () => {
  unchanged('runs_read', { select: 'list' })
  unchanged('notes_read', { select: 'list', itemId: 'abcdef12' })
  unchanged('deps_read', { select: 'list', item: 'abcdef12' })
  unchanged('docs_read', { select: 'list' })
})

test('S2 R2: an empty ancestorId is removed and nothing is injected (the 4.0 opt-out)', () => {
  for (const input of [{ select: 'list', ancestorId: '' }, { select: 'next', ancestorId: '', limit: 5 }, { select: 'context', ancestorId: '' }]) {
    const r = rewriteCall('items_read', { ...input }, ROOT)
    expect('ancestorId' in r.input).toBe(false)
    expect(r.input.select).toBe(input.select)
    expect(r.changes).toHaveLength(1)
  }
  const kept = rewriteCall('items_read', { select: 'next', ancestorId: '', limit: 5 }, ROOT)
  expect(kept.input.limit).toBe(5)
})

test('S2 R2: the opt-out is not undone by the injection in the same call', () => {
  const r = rewriteCall('items_read', { select: 'list', ancestorId: '' }, ROOT)
  expect(r.input.ancestorId).toBeUndefined()
})

test('S2: the retired 3.x scoped tools get no rewrite any more', () => {
  const retired = ['query', 'items'].join('_')
  const retiredNext = ['get', 'next', 'item'].join('_')
  unchanged(retired, { operation: 'search' })
  unchanged(retiredNext, {})
})

// --- S3: W1 to W4 ------------------------------------------------------------------------

test('S3 W1+W3: a flat notes_write op becomes ops[0] and loses the actor', () => {
  const input = { op: 'upsert', itemId: 'abcdef12', key: 'test-manifest', role: 'work', body: 'b', actor: ACTOR }
  const r = rewriteCall('notes_write', input, null)
  expect(r.input).toEqual({ ops: [{ op: 'upsert', itemId: 'abcdef12', key: 'test-manifest', role: 'work', body: 'b' }] })
  expect('actor' in r.input).toBe(false)
  expect(r.changes).toHaveLength(2)
  expect(input.actor).toEqual(ACTOR)
  expect('ops' in input).toBe(false)
})

test('S3 W1: atomic and seatToken stay at the top level, everything else moves into the element', () => {
  const r = rewriteCall('advance', { op: 'transition', itemId: 'abcdef12', trigger: 'start', atomic: true, seatToken: 'tok-1' }, ROOT)
  expect(r.input).toEqual({ ops: [{ op: 'transition', itemId: 'abcdef12', trigger: 'start' }], atomic: true, seatToken: 'tok-1' })
  expect(r.changes).toHaveLength(1)
})

test('S3 W1: an empty seatToken is still a top-level key, not an element field', () => {
  const r = rewriteCall('advance', { op: 'transition', itemId: 'abcdef12', trigger: 'start', seatToken: '' }, ROOT)
  expect(r.input.seatToken).toBe('')
  expect((r.input.ops as Record<string, unknown>[])[0]).toEqual({ op: 'transition', itemId: 'abcdef12', trigger: 'start' })
})

test('S3 W1: a call that already has ops is not wrapped again', () => {
  const input = { op: 'upsert', ops: [{ op: 'upsert', itemId: 'a', key: 'k', role: 'work', body: 'b' }] }
  const r = rewriteCall('notes_write', input, null)
  expect(r.input.ops).toEqual([{ op: 'upsert', itemId: 'a', key: 'k', role: 'work', body: 'b' }])
})

test('S3 W2: advance transitions[] becomes ops[] with op transition, keeping an element op it already has', () => {
  const r = rewriteCall(
    'advance',
    {
      transitions: [
        { itemId: 'aaaaaaaa', trigger: 'start', actor: ACTOR },
        { itemId: 'bbbbbbbb', trigger: 'complete', op: 'transition' },
      ],
      atomic: true,
    },
    ROOT,
  )
  expect(r.input).toEqual({
    ops: [
      { op: 'transition', itemId: 'aaaaaaaa', trigger: 'start' },
      { op: 'transition', itemId: 'bbbbbbbb', trigger: 'complete' },
    ],
    atomic: true,
  })
  expect('transitions' in r.input).toBe(false)
  expect(r.changes.length).toBeGreaterThanOrEqual(1)
})

test('S3 W2: notes_write notes[] becomes ops[] with op upsert, keeping an op an element already has', () => {
  const r = rewriteCall('notes_write', { notes: [{ itemId: 'aaaaaaaa', key: 'k1', role: 'work', body: 'one' }, { op: 'delete', itemId: 'bbbbbbbb', key: 'k2' }] }, null)
  expect(r.input).toEqual({
    ops: [
      { op: 'upsert', itemId: 'aaaaaaaa', key: 'k1', role: 'work', body: 'one' },
      { op: 'delete', itemId: 'bbbbbbbb', key: 'k2' },
    ],
  })
  expect('notes' in r.input).toBe(false)
  expect(r.changes).toHaveLength(1)
})

test('S3 W2: transitions[] on another tool is not converted', () => {
  unchanged('notes_write', { transitions: [{ itemId: 'a', trigger: 'start' }] }, null)
})

test('S3 W2: notes[] is converted only when ops is absent', () => {
  unchanged('notes_write', { ops: [{ op: 'upsert', itemId: 'a', key: 'k', role: 'work', body: 'b' }], notes: [{ itemId: 'x', key: 'y' }] }, null)
})

test('S3 W3: an actor is dropped on every TO tool, a call without one is left alone', () => {
  for (const tool of CATALOG_TOOLS) {
    const r = rewriteCall(tool, { actor: ACTOR, marker: tool }, ROOT)
    expect('actor' in r.input).toBe(false)
    expect(r.input.marker).toBe(tool)
    expect(r.changes).toHaveLength(1)
    unchanged(tool, { marker: tool })
  }
})

test('S3 W3: the top-level actor and the actor of every ops element are dropped, nothing else in them', () => {
  const r = rewriteCall(
    'notes_write',
    {
      actor: ACTOR,
      ops: [
        { op: 'upsert', itemId: 'a', key: 'k1', role: 'work', body: 'x', actor: ACTOR },
        { op: 'upsert', itemId: 'b', key: 'k2', role: 'work', body: 'y' },
        { op: 'upsert', itemId: 'c', key: 'k3', role: 'work', body: 'z', actor: { id: 'own', kind: 'user' } },
      ],
    },
    null,
  )
  expect(r.input).toEqual({
    ops: [
      { op: 'upsert', itemId: 'a', key: 'k1', role: 'work', body: 'x' },
      { op: 'upsert', itemId: 'b', key: 'k2', role: 'work', body: 'y' },
      { op: 'upsert', itemId: 'c', key: 'k3', role: 'work', body: 'z' },
    ],
  })
  expect(r.changes).toHaveLength(1)
})

test('S3 W3: an actor inside an element is dropped even with no top-level actor', () => {
  const r = rewriteCall('advance', { ops: [{ op: 'transition', itemId: 'a', trigger: 'start', actor: ACTOR }] }, ROOT)
  expect(r.input).toEqual({ ops: [{ op: 'transition', itemId: 'a', trigger: 'start' }] })
})

test('S3 W3: the reserved routing fields survive the rewrite', () => {
  const r = rewriteCall('advance', { ops: [{ op: 'transition', itemId: 'a', trigger: 'start' }], actor: ACTOR, agentId: 'ag1', tool_use_id: 'tu1', seatToken: 'tok' }, ROOT)
  expect(r.input.agentId).toBe('ag1')
  expect(r.input.tool_use_id).toBe('tu1')
  expect(r.input.seatToken).toBe('tok')
})

test('S3 W4: traits inside properties move to the op, other properties stay', () => {
  const r = rewriteCall(
    'items_write',
    { ops: [{ op: 'create', title: 'T', type: 'feature-task', properties: { traits: ['needs-test-author', 'delegated'], planLabel: 'T3' } }] },
    ROOT,
  )
  expect(r.input).toEqual({
    ops: [{ op: 'create', title: 'T', type: 'feature-task', traits: ['needs-test-author', 'delegated'], properties: { planLabel: 'T3' } }],
  })
  expect(r.changes).toHaveLength(1)
})

test('S3 W4: properties holding only traits is deleted once emptied', () => {
  const r = rewriteCall('items_write', { ops: [{ op: 'update', itemId: 'abcdef12', properties: { traits: ['delegated'] } }] }, ROOT)
  expect(r.input).toEqual({ ops: [{ op: 'update', itemId: 'abcdef12', traits: ['delegated'] }] })
  const el = (r.input.ops as Record<string, unknown>[])[0]!
  expect('properties' in el).toBe(false)
})

test('S3 W4: an op that already has traits keeps them and the properties traits are dropped', () => {
  const r = rewriteCall('items_write', { ops: [{ op: 'create', title: 'T', traits: ['kept'], properties: { traits: ['dropped'], planLabel: 'T1' } }] }, ROOT)
  expect(r.input).toEqual({ ops: [{ op: 'create', title: 'T', traits: ['kept'], properties: { planLabel: 'T1' } }] })
  const only = rewriteCall('items_write', { ops: [{ op: 'update', itemId: 'abcdef12', traits: ['kept'], properties: { traits: ['dropped'] } }] }, ROOT)
  expect(only.input).toEqual({ ops: [{ op: 'update', itemId: 'abcdef12', traits: ['kept'] }] })
})

test('S3 W4: every create and update element is rewritten on its own', () => {
  const r = rewriteCall(
    'items_write',
    {
      ops: [
        { op: 'create', title: 'A', properties: { traits: ['one'] } },
        { op: 'update', itemId: 'abcdef12', title: 'B' },
        { op: 'update', itemId: 'fedcba98', properties: { traits: ['two'], planLabel: 'T2' } },
      ],
    },
    ROOT,
  )
  expect(r.input.ops).toEqual([
    { op: 'create', title: 'A', traits: ['one'] },
    { op: 'update', itemId: 'abcdef12', title: 'B' },
    { op: 'update', itemId: 'fedcba98', traits: ['two'], properties: { planLabel: 'T2' } },
  ])
})

test('S3 W4: properties without traits, and other op kinds, are not touched', () => {
  unchanged('items_write', { ops: [{ op: 'create', title: 'T', properties: { planLabel: 'T3' } }] })
  unchanged('items_write', { ops: [{ op: 'delete', itemId: 'abcdef12' }] })
  unchanged('items_write', { ops: [{ op: 'update', itemId: 'abcdef12', title: 'x' }] })
})

test('S3 W1+W4: a flat items_write create has its traits lifted after being wrapped', () => {
  const r = rewriteCall('items_write', { op: 'create', title: 'T', properties: { traits: ['delegated'] } }, ROOT)
  expect(r.input).toEqual({ ops: [{ op: 'create', title: 'T', traits: ['delegated'] }] })
})

// --- S4: already-4.0 input and own calls ------------------------------------------------

test('S4: input already in 4.0 shape comes back as the same object with no changes', () => {
  unchanged('items_read', { select: 'list', ancestorId: ROOT, limit: 100 })
  unchanged('items_read', { select: 'get', itemId: 'abcdef12', fields: ['ancestors'] })
  unchanged('notes_write', { ops: [{ op: 'upsert', itemId: 'a', key: 'k', role: 'work', body: 'b' }] }, null)
  unchanged('advance', { ops: [{ op: 'transition', itemId: 'a', trigger: 'start' }], atomic: true, seatToken: 'tok' })
  unchanged('items_write', { ops: [{ op: 'create', title: 'T', traits: ['a'], properties: { planLabel: 'T1' } }] })
  unchanged('runs_write', { ops: [{ op: 'mark', runId: 'abcdef12', key: 'retro' }] })
  unchanged('deps_write', { ops: [{ op: 'add', from: 'abcdef12', to: 'fedcba98', type: 'blocks' }] })
  unchanged('config', { ops: [{ op: 'get' }] })
  unchanged('deps_read', { select: 'list', item: 'abcdef12', direction: 'all' })
})

test('S4: replay - rewriting the output again changes nothing', () => {
  for (const [tool, input] of [
    ['notes_write', { op: 'upsert', itemId: 'a', key: 'k', role: 'work', body: 'b', actor: ACTOR }],
    ['advance', { transitions: [{ itemId: 'a', trigger: 'start', actor: ACTOR }] }],
    ['items_write', { ops: [{ op: 'create', title: 'T', properties: { traits: ['x'] } }] }],
    ['items_read', { select: 'list' }],
  ] as const) {
    const first = rewriteCall(tool, { ...input }, ROOT)
    expect(first.changes.length).toBeGreaterThan(0)
    const second = rewriteCall(tool, first.input, ROOT)
    expect(second.changes).toEqual([])
    expect(second.input).toBe(first.input)
  }
})

test('S4: own-call predicate', () => {
  expect(isOwnCall('task-orchestrator-mod', 'task-orchestrator-mod')).toBe(true)
  expect(isOwnCall('engine', 'task-orchestrator-mod')).toBe(false)
  expect(isOwnCall(undefined, 'task-orchestrator-mod')).toBe(false)
})

// --- integration through the loaded plugin ----------------------------------------------

const CONFIG = `project:\n  rootId: ${ROOT}\n`

test('integration: tool.call repairs input beneath to the command hooks and logs one line per rule', async ($, on) => {
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

  await $.tool.call({ tool: `${TO}items_read`, select: 'list' } as never)
  await $.tool.call({ tool: `${TO}notes_write`, op: 'upsert', itemId: 'abcdef12', key: 'k', role: 'work', body: 'b', actor: ACTOR } as never)

  expect(seen[0]?.ancestorId).toBe(ROOT)
  expect(seen[1]?.ops).toEqual([{ op: 'upsert', itemId: 'abcdef12', key: 'k', role: 'work', body: 'b' }])
  expect(seen[1]?.actor).toBeUndefined()
  expect(seen[1]?.op).toBeUndefined()
  expect(logs.some(l => l.includes('call-shape: items_read R1'))).toBe(true)
  expect(logs.some(l => l.includes('call-shape: notes_write W1'))).toBe(true)
  expect(logs.some(l => l.includes('call-shape: notes_write W3'))).toBe(true)
})

test('integration: an advance call with transitions[] reaches the command hooks as ops[]', async ($, on) => {
  const seen: Record<string, unknown>[] = []
  on('fs.read', async () => ({ value: CONFIG }) as never)
  on('ui.log', async () => ({ value: undefined }) as never)
  on('tool.call', async (_$, e) => {
    seen.push(e as unknown as Record<string, unknown>)

    return { ref: 'r', result: 'ok', text: 'ok' } as never
  })

  await $.tool.call({ tool: `${TO}advance`, transitions: [{ itemId: 'abcdef12', trigger: 'start', actor: ACTOR }] } as never)

  expect(seen[0]?.ops).toEqual([{ op: 'transition', itemId: 'abcdef12', trigger: 'start' }])
  expect(seen[0]?.transitions).toBeUndefined()
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

  await $.tool.call({ tool: `${TO}items_read`, select: 'list' } as never)
  expect(seen[0]?.ancestorId).toBeUndefined()
})

test('integration: nothing is logged when nothing changed, and tagged listings stay unscoped', async ($, on) => {
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

  await $.tool.call({ tool: `${TO}items_read`, select: 'list', tags: ['retrospective-trend'] } as never)
  await $.tool.call({ tool: `${TO}items_read`, select: 'get', itemId: 'abcdef12' } as never)
  await $.tool.call({ tool: `${TO}notes_write`, ops: [{ op: 'upsert', itemId: 'a', key: 'k', role: 'work', body: 'b' }] } as never)

  expect(seen[0]?.ancestorId).toBeUndefined()
  expect(logs.filter(l => l.includes('call-shape'))).toEqual([])
})

test('integration: an empty ancestorId reaches the command hooks removed, and is not re-scoped', async ($, on) => {
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

  await $.tool.call({ tool: `${TO}items_read`, select: 'list', ancestorId: '' } as never)

  expect(seen[0]?.ancestorId).toBeUndefined()
  expect(logs.some(l => l.includes('call-shape: items_read R2'))).toBe(true)
  expect(logs.some(l => l.includes('call-shape: items_read R1'))).toBe(false)
})
