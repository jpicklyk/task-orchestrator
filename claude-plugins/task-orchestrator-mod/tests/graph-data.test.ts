// Tests of the graph data layer (T2 32492bfa).
//
// The engine's test `$` has no clock/state/fs/mcp nouns, so there are two kinds of test:
//  - unit tests run the modules in the test's own realm over `fake()`, an in-memory GraphIo (TO server
//    world, fs, env, a manual clock, scripted `process.spawn`, the status/snapshot/scope state);
//  - hook tests drive the loaded plugin through `$.tool.call`, with the TO server on `mcp.call`
//    and time on mock.clock.
import type { On } from 'claude-code'
import { expect, mock, test } from 'claude-code/testing'

import { WRITE_TOOLS, WRITE_TOOL_NAME, parseToolResult, shouldRefresh } from '../src/graph-data/index.ts'
import type { GraphIo } from '../src/graph-data/io.ts'
import { invalidateLabels, resetLabelState } from '../src/graph-data/labels.ts'
import { createSseParser, curlRequest, eventsUrl, isLoopbackApiUrl, resetLiveState, resolveApiUrl, restartLive, syncLive } from '../src/graph-data/live.ts'
import { REFRESH_TIMEOUT_MS, refresh, refreshNow, resetRefreshState, sameSnapshot, sameStatus } from '../src/graph-data/refresh.ts'
import { NODE_CAP, snapshot, trailOf } from '../src/graph-data/snapshot.ts'

const ROOT = '00000000-0000-4000-8000-000000000001'
const TO = 'mcp__mcp-task-orchestrator__'
const CONFIG = `project:\n  rootId: ${ROOT}\n`

type Item = { id: string; parentId?: string; title: string; role: string; depth: number; type?: string; statusLabel?: string; properties?: unknown; childCounts?: Record<string, number> }
type Dep = { id: string; from: string; to: string; type: string; unblock?: string }
type World = {
  items: Item[]
  deps?: Dep[]
  outside?: Record<string, { title: string; role: string }>
  context?: Record<string, unknown>
  failDepsFor?: string[]
  failGetFor?: string[]
}
type Call = { tool: string; args: Record<string, unknown> }
type McpResult = { content: { type: 'text'; text: string }[]; isError?: boolean }

const text = (body: unknown): McpResult => ({ content: [{ type: 'text', text: JSON.stringify(body) }] })
const failure = (message: string): McpResult => ({ isError: true, content: [{ type: 'text', text: message }] })

/** The TO server, read tools only, answered from `world`. */
function answer(world: World, tool: string, args: Record<string, unknown>): McpResult {
  const info = (id: string) => {
    const item = world.items.find(i => i.id === id)

    return item ? { title: item.title, role: item.role } : (world.outside?.[id] ?? { title: id, role: 'queue' })
  }
  if (tool === 'query_items' && args.operation === 'overview') {
    const anchor = String(args.anchorId)
    const rows = world.items.filter(i => i.parentId === anchor)

    return text({
      anchor: { id: anchor, title: world.items.find(i => i.id === anchor)?.title ?? 'Project' },
      items: rows.map(i => ({ ...i, priority: 'medium' })),
      total: rows.length,
      truncated: false,
      offset: 0,
    })
  }
  if (tool === 'query_items' && args.operation === 'get') {
    const id = String(args.itemId)
    if (world.failGetFor?.includes(id)) return failure('get boom')
    const item = world.items.find(i => i.id === id)
    if (!item) return failure('not found')
    const { properties, ...rest } = item

    return text({ ...rest, ...(properties !== undefined && { properties }) })
  }
  if (tool === 'query_items') {
    const scope = String(args.ancestorId)
    const inTree = (i: Item): boolean => i.id === scope || (i.parentId !== undefined && inTree(world.items.find(p => p.id === i.parentId) as Item))
    const rows = world.items.filter(inTree).map(i => ({ ...i, priority: 'medium' }))
    const offset = Number(args.offset ?? 0)
    const limit = Number(args.limit ?? 50)

    return text({ items: rows.slice(offset, offset + limit), total: rows.length, returned: Math.min(limit, Math.max(0, rows.length - offset)), limit, offset })
  }
  if (tool === 'query_dependencies') {
    const id = String(args.itemId)
    if (world.failDepsFor?.includes(id)) return failure('boom')
    const rows = (world.deps ?? []).filter(d => d.from === id || d.to === id)

    return text({
      dependencies: rows.map(d => ({
        id: d.id,
        fromItemId: d.from,
        toItemId: d.to,
        type: d.type,
        ...(d.unblock && { effectiveUnblockRole: d.unblock }),
        fromItem: info(d.from),
        toItem: info(d.to),
      })),
    })
  }
  if (tool === 'get_context') return text(world.context?.[String(args.itemId)] ?? {})

  return failure(`unexpected ${tool}`)
}

/** root -> feature -> three tasks (queue, work, review) -> one subtask under the work task. */
function threeLevels(): World {
  return {
    items: [
      { id: 'root', title: 'Root', role: 'work', depth: 0 },
      { id: 'feat', parentId: 'root', title: 'Feature', role: 'work', depth: 1, type: 'feature-implementation' },
      { id: 't-queue', parentId: 'feat', title: 'Queued task', role: 'queue', depth: 2 },
      { id: 't-work', parentId: 'feat', title: 'Working task', role: 'work', depth: 2, statusLabel: 'in-progress' },
      { id: 't-review', parentId: 'feat', title: 'Reviewed task', role: 'review', depth: 2 },
      { id: 'sub', parentId: 't-work', title: 'Subtask', role: 'terminal', depth: 3 },
    ],
  }
}

declare const setTimeout: (fn: () => void, ms: number) => unknown

const flush = () => new Promise<void>(resolve => setTimeout(resolve, 0))

type GraphSnapshot = import('../types').GraphSnapshot
type GraphStatus = import('../types').GraphStatus
type Timer = { due: number; fn: () => void; every?: number; dead: boolean }
type SpawnScript = (request: { argv: readonly string[]; input?: string }) => AsyncGenerator<{ stream: 'stdout' | 'stderr'; text: string }, { code: number | null; signal: string | null }>

/** An in-memory GraphIo. Time moves only through `advance`. */
function fake(opts: { world?: World; files?: Record<string, string>; env?: Record<string, string>; spawn?: SpawnScript } = {}) {
  resetLabelState()
  const world = opts.world ?? threeLevels()
  const files = opts.files ?? { '.taskorchestrator/config.yaml': CONFIG }
  const calls: Call[] = []
  const timers: Timer[] = []
  const state = {
    scope: null as string | null,
    snapshot: null as GraphSnapshot | null,
    status: { refreshing: false, liveSource: 'none' } as GraphStatus,
  }
  let now = 1_000
  const arm = (ms: number, fn: () => void, every?: number): Timer => {
    const timer: Timer = { due: now + ms, fn, every, dead: false }
    timers.push(timer)

    return timer
  }
  const io: GraphIo = {
    callTool: async (tool, args) => {
      calls.push({ tool, args })

      return parseToolResult(tool, answer(world, tool, args))
    },
    readFile: async path => {
      const body = files[path]
      if (body === undefined) throw new Error(`ENOENT ${path}`)

      return body
    },
    now: async () => now,
    after: (ms, fn) => {
      const timer = arm(ms, fn)

      return { cancel: () => void (timer.dead = true) }
    },
    every: (ms, fn) => {
      const timer = arm(ms, fn, ms)

      return { cancel: () => void (timer.dead = true) }
    },
    sleep: (ms, signal) =>
      new Promise<void>((resolve, reject) => {
        const timer = arm(ms, resolve)
        signal?.addEventListener('abort', () => {
          timer.dead = true
          reject(new Error('aborted'))
        })
      }),
    spawn: request => {
      if (!opts.spawn) throw new Error('no spawn script')

      return opts.spawn(request)
    },
    readScope: async () => state.scope,
    setSnapshot: async value => void (state.snapshot = value),
    updateStatus: async change => void (state.status = change(state.status)),
    envApiUrl: async () => opts.env?.TASK_ORCHESTRATOR_API_URL,
    envApiToken: async () => opts.env?.TASK_ORCHESTRATOR_API_TOKEN,
    homeDir: async () => opts.env?.HOME,
  }
  const advance = async (ms: number) => {
    const target = now + ms
    await flush()
    for (;;) {
      const due = timers.filter(t => !t.dead && t.due <= target).sort((a, b) => a.due - b.due)[0]
      if (!due) break
      now = due.due
      if (due.every !== undefined) due.due += due.every
      else due.dead = true
      due.fn()
      await flush()
    }
    now = target
    await flush()
  }
  const searches = () => calls.filter(c => c.tool === 'query_items' && c.args.operation === 'search').length
  const gets = () => calls.filter(c => c.tool === 'query_items' && c.args.operation === 'get' && c.args.includeAncestors !== true).length

  return { io, calls, advance, searches, gets, state }
}

// ── snapshot ───────────────────────────────────────────────────────────────────────────

test('a 3-level tree gives the right nodes and parents', async () => {
  const s = await snapshot(fake().io, 'root')
  expect(s.error).toBe(undefined)
  expect(s.truncated).toBe(false)
  expect(s.scopeId).toBe('root')
  expect(s.rootId).toBe(ROOT)
  expect(s.nodes.map(n => [n.id, n.parentId, n.depth])).toEqual([
    ['root', null, 0],
    ['feat', 'root', 1],
    ['t-queue', 'feat', 2],
    ['t-work', 'feat', 2],
    ['t-review', 'feat', 2],
    ['sub', 't-work', 3],
  ])
  expect(s.nodes[3]).toEqual({ id: 't-work', parentId: 'feat', title: 'Working task', role: 'work', statusLabel: 'in-progress', priority: 'medium', depth: 2 })
})

test('a sub-scope keeps its top node parentless', async () => {
  const s = await snapshot(fake().io, 't-work')
  expect(s.nodes.map(n => [n.id, n.parentId])).toEqual([
    ['t-work', null],
    ['sub', 't-work'],
  ])
})

test('a null scope resolves to the project root; no rootId gives an empty snapshot with an error', async () => {
  const withRoot = fake()
  const s = await snapshot(withRoot.io, null)
  expect(s.scopeId).toBe(ROOT)
  const none = fake({ files: {} })
  const empty = await snapshot(none.io, null)
  expect(empty.nodes).toEqual([])
  expect(empty.error).toBe('no project.rootId')
  expect(none.calls).toHaveLength(0)
})

/** The project root with three children carrying role roll-ups, as the overview answers it. */
function projectWorld(): World {
  return {
    items: [
      { id: ROOT, title: 'TaskOrchestrator', role: 'work', depth: 0 },
      { id: 'cont-features', parentId: ROOT, title: 'Features', role: 'work', depth: 1, type: 'container', childCounts: { work: 9, review: 4, terminal: 63, queue: 0, blocked: 0 } },
      { id: 'cont-bugs', parentId: ROOT, title: 'Bugs', role: 'queue', depth: 1, childCounts: { queue: 2, work: 0 } },
      { id: 'leaf', parentId: ROOT, title: 'Leaf', role: 'terminal', depth: 1 },
      { id: 'deep', parentId: 'cont-features', title: 'Deep', role: 'work', depth: 2 },
    ],
    deps: [{ id: 'd1', from: 'cont-features', to: 'cont-bugs', type: 'BLOCKS' }],
  }
}

test('overview: the project root is ONE query_items overview call; children carry roll-ups, nothing else is read', async () => {
  for (const scope of [null, ROOT]) {
    const env = fake({ world: projectWorld() })
    const s = await snapshot(env.io, scope)
    expect(env.calls).toEqual([{ tool: 'query_items', args: { operation: 'overview', anchorId: ROOT, limit: 100 } }])
    expect(s.overview).toBe(true)
    expect(s.scopeId).toBe(ROOT)
    expect(s.error).toBe(undefined)
    expect(s.nodes.map(n => [n.id, n.parentId, n.depth, n.title])).toEqual([
      [ROOT, null, 0, 'TaskOrchestrator'],
      ['cont-features', ROOT, 1, 'Features'],
      ['cont-bugs', ROOT, 1, 'Bugs'],
      ['leaf', ROOT, 1, 'Leaf'],
    ])
    // zero counts are dropped; a child without childCounts has none
    expect(s.nodes[1]?.childCounts).toEqual({ work: 9, review: 4, terminal: 63 })
    expect(s.nodes[2]?.childCounts).toEqual({ queue: 2 })
    expect(s.nodes[3]?.childCounts).toBe(undefined)
    expect(s.edges).toEqual([])
    expect(s.gates).toEqual({})
  }
})

test('overview: a failed call gives an empty-children snapshot with the error; a sub-scope still walks the subtree', async () => {
  const env = fake({ world: projectWorld() })
  const original = env.io.callTool
  env.io.callTool = async (tool, args) => {
    if (args.operation === 'overview') throw new Error('overview boom')

    return original(tool, args)
  }
  const bad = await snapshot(env.io, null)
  expect(bad.error).toBe('overview boom')
  expect(bad.nodes).toEqual([])
  const sub = await snapshot(fake({ world: projectWorld() }).io, 'cont-features')
  expect(sub.overview).toBe(undefined)
  expect(sub.nodes.map(n => n.id)).toEqual(['cont-features', 'deep'])
})

test('subtrees page in 100s and 151 items truncate to the 150 shallowest', async () => {
  const items: Item[] = [{ id: 'root', title: 'Root', role: 'queue', depth: 0 }]
  for (let i = 0; i < 25; i++) items.push({ id: `a${i}`, parentId: 'root', title: `A${i}`, role: 'queue', depth: 1 })
  for (let i = 0; i < 124; i++) items.push({ id: `b${i}`, parentId: 'a0', title: `B${i}`, role: 'queue', depth: 2 })
  items.push({ id: 'deep', parentId: 'b0', title: 'Deep', role: 'queue', depth: 3 })
  expect(items).toHaveLength(NODE_CAP + 1)
  const env = fake({ world: { items } })
  const s = await snapshot(env.io, 'root')
  expect(env.calls.filter(c => c.tool === 'query_items' && c.args.operation === 'search').map(c => c.args.offset)).toEqual([0, 100])
  expect(s.truncated).toBe(true)
  expect(s.nodes).toHaveLength(NODE_CAP)
  expect(s.nodes.some(n => n.id === 'deep')).toBe(false)
})

test('120 items span two pages without truncating', async () => {
  const items: Item[] = [{ id: 'root', title: 'Root', role: 'queue', depth: 0 }]
  for (let i = 0; i < 119; i++) items.push({ id: `c${i}`, parentId: 'root', title: `C${i}`, role: 'queue', depth: 1 })
  const env = fake({ world: { items } })
  const s = await snapshot(env.io, 'root')
  expect(env.searches()).toBe(2)
  expect(s.nodes).toHaveLength(120)
  expect(s.truncated).toBe(false)
})

test('edges: IS_BLOCKED_BY is folded, outside ends go to external, duplicates collapse', async () => {
  const world = threeLevels()
  world.outside = { ext: { title: 'Elsewhere', role: 'work' } }
  world.deps = [
    { id: 'd1', from: 't-queue', to: 't-work', type: 'BLOCKS', unblock: 'terminal' },
    // t-review is blocked by t-work: the blocker is t-work
    { id: 'd2', from: 't-review', to: 't-work', type: 'IS_BLOCKED_BY' },
    // the same relationship as a BLOCKS row too: kept once
    { id: 'd3', from: 't-work', to: 't-review', type: 'BLOCKS' },
    { id: 'd4', from: 'ext', to: 't-queue', type: 'BLOCKS' },
    { id: 'd5', from: 'sub', to: 'ext', type: 'RELATES_TO' },
    // neither end in the subtree: dropped
    { id: 'd6', from: 'ext', to: 'ext2', type: 'BLOCKS' },
  ]
  const s = await snapshot(fake({ world }).io, 'root')
  expect(s.edges).toHaveLength(4)
  expect(s.edges).toContainEqual({ from: 't-queue', to: 't-work', type: 'BLOCKS', unblockAt: 'terminal' })
  expect(s.edges).toContainEqual({ from: 't-work', to: 't-review', type: 'BLOCKS' })
  expect(s.edges).toContainEqual({ from: 'ext', to: 't-queue', type: 'BLOCKS' })
  expect(s.edges).toContainEqual({ from: 'sub', to: 'ext', type: 'RELATES_TO' })
  expect(s.edges.some(e => (e.type as string) === 'IS_BLOCKED_BY')).toBe(false)
  expect(s.external).toEqual({ ext: { title: 'Elsewhere', role: 'work' } })
})

test('a dependency listed from both ends (same id) is kept once', async () => {
  const world = threeLevels()
  world.deps = [{ id: 'd1', from: 't-queue', to: 't-work', type: 'BLOCKS' }]
  const s = await snapshot(fake({ world }).io, 'root')
  // t-queue and t-work both report d1
  expect(s.edges).toEqual([{ from: 't-queue', to: 't-work', type: 'BLOCKS' }])
})

test('gates are read for work and review nodes only, with phase-row counts', async () => {
  const world = threeLevels()
  world.context = {
    'feat': { gateStatus: { canAdvance: true, phase: 'work', missing: [] }, schema: [] },
    't-work': {
      gateStatus: { canAdvance: false, phase: 'work', missing: ['session-tracking'] },
      schema: [
        { key: 'task-scope', role: 'queue', required: true, filled: true },
        { key: 'implementation-notes', role: 'work', required: true, filled: true },
        { key: 'delegation-metadata', role: 'work', required: false, filled: false },
        { key: 'session-tracking', role: 'work', required: true, filled: false },
        { key: 'extra', role: 'work', required: true, filled: false },
      ],
    },
    't-review': { gateStatus: { canAdvance: true, phase: 'review', missing: [] }, schema: [{ key: 'review-checklist', role: 'review', required: true, filled: true }] },
  }
  const env = fake({ world })
  const s = await snapshot(env.io, 'root')
  expect(env.calls.filter(c => c.tool === 'get_context').map(c => c.args.itemId).sort()).toEqual(['feat', 'root', 't-review', 't-work'])
  expect(s.gates['t-work']).toEqual({ canAdvance: false, phase: 'work', missing: ['session-tracking'], required: 3, filled: 1 })
  expect(s.gates['t-review']).toEqual({ canAdvance: true, phase: 'review', missing: [], required: 1, filled: 1 })
  expect(s.gates['t-queue']).toBe(undefined)
  expect(s.gates['sub']).toBe(undefined)
})

test('one failing query_dependencies gives a partial snapshot and no throw', async () => {
  const world = threeLevels()
  world.failDepsFor = ['t-queue']
  world.deps = [{ id: 'd1', from: 't-work', to: 't-review', type: 'BLOCKS' }]
  const s = await snapshot(fake({ world }).io, 'root')
  expect(s.nodes).toHaveLength(6)
  expect(s.edges).toEqual([{ from: 't-work', to: 't-review', type: 'BLOCKS' }])
  expect(s.error).toContain('query_dependencies: boom')
})

test('a failing hierarchy call returns an empty snapshot with the error', async () => {
  const env = fake()
  env.io.callTool = async () => {
    throw new Error('query_items: server down')
  }
  const s = await snapshot(env.io, 'root')
  expect(s.nodes).toEqual([])
  expect(s.error).toContain('server down')
})

test('snapshot never calls a write tool', async () => {
  const world = threeLevels()
  world.deps = [{ id: 'd1', from: 't-queue', to: 't-work', type: 'BLOCKS' }]
  world.context = { 't-work': { gateStatus: { canAdvance: true, phase: 'work', missing: [] }, schema: [] } }
  const env = fake({ world })
  await snapshot(env.io, 'root')
  expect(env.calls.length).toBeGreaterThan(0)
  for (const c of env.calls) expect(['query_items', 'query_dependencies', 'get_context']).toContain(c.tool)
})


// ── plan labels ────────────────────────────────────────────────────────────────────────

const withProps = (properties: unknown): World => {
  const w = threeLevels()
  ;(w.items.find(i => i.id === 't-work') as Item).properties = properties

  return w
}
const labelOf = async (properties: unknown) => {
  const s = await snapshot(fake({ world: withProps(properties) }).io, 'root')

  return { label: s.nodes.find(n => n.id === 't-work')?.planLabel, error: s.error }
}

test('S1: properties.planLabel becomes node.planLabel (properties arrive as a JSON string)', async () => {
  const r = await labelOf('{"traits":["x"],"planLabel":"T3"}')
  expect(r.label).toBe('T3')
  expect(r.error).toBe(undefined)
})

test('S2: missing, bad, wrong-typed, empty or too long labels give no label and no error', async () => {
  for (const bad of [undefined, 'not json', '{"planLabel":5}', '{"planLabel":""}', '{"planLabel":"   "}', '{"planLabel":"1234567890123"}', '[1]', null, '{"other":1}']) {
    const r = await labelOf(bad)
    expect(r.label).toBe(undefined)
    expect(r.error).toBe(undefined)
  }
  expect((await labelOf({ planLabel: 'T9' })).label).toBe('T9')
  expect((await labelOf('{"planLabel":"123456789012"}')).label).toBe('123456789012')
})

test('S3: labels are fetched once per id; a second snapshot makes no get calls', async () => {
  const env = fake({ world: withProps('{"planLabel":"T3"}') })
  await snapshot(env.io, 'root')
  expect(env.gets()).toBe(6)
  const again = await snapshot(env.io, 'root')
  expect(env.gets()).toBe(6)
  expect(again.nodes.find(n => n.id === 't-work')?.planLabel).toBe('T3')
})

test('S4: invalidateLabels makes the next snapshot get again', async () => {
  const env = fake({ world: withProps('{"planLabel":"T3"}') })
  await snapshot(env.io, 'root')
  invalidateLabels()
  await snapshot(env.io, 'root')
  expect(env.gets()).toBe(12)
})

test('S5: a failed get gives no label and no error, and is retried next snapshot', async () => {
  const world = withProps('{"planLabel":"T3"}')
  world.failGetFor = ['t-work']
  const env = fake({ world })
  const first = await snapshot(env.io, 'root')
  expect(first.nodes.find(n => n.id === 't-work')?.planLabel).toBe(undefined)
  expect(first.error).toBe(undefined)
  expect(env.gets()).toBe(6)
  world.failGetFor = []
  const second = await snapshot(env.io, 'root')
  expect(env.gets()).toBe(7)
  expect(second.nodes.find(n => n.id === 't-work')?.planLabel).toBe('T3')
})

test('S17: a snapshot only reads: query_items is search or get', async () => {
  const env = fake({ world: withProps('{"planLabel":"T3"}') })
  await snapshot(env.io, 'root')
  for (const c of env.calls.filter(x => x.tool === 'query_items')) expect(['search', 'get']).toContain(c.args.operation as string)
})

// ── seat progress ──────────────────────────────────────────────────────────────────────

test('S7: gate seats group the current phase required rows by seat in schema order; null seat is other', async () => {
  const world = threeLevels()
  world.context = {
    't-work': {
      gateStatus: { canAdvance: false, phase: 'work', missing: [] },
      schema: [
        { key: 'task-scope', role: 'queue', required: true, filled: true, seat: 'planner' },
        { key: 'implementation-notes', role: 'work', required: true, filled: true, seat: 'implementer' },
        { key: 'test-manifest', role: 'work', required: true, filled: false, seat: 'test-author' },
        { key: 'delegation-metadata', role: 'work', required: false, filled: false, seat: 'orchestrator' },
        { key: 'extra', role: 'work', required: true, filled: false, seat: 'implementer' },
        { key: 'session-tracking', role: 'work', required: true, filled: true, seat: 'orchestrator' },
        { key: 'loose', role: 'work', required: true, filled: false, seat: null },
      ],
    },
  }
  const s = await snapshot(fake({ world }).io, 'root')
  expect(s.gates['t-work']?.seats).toEqual([
    { seat: 'implementer', required: 2, filled: 1 },
    { seat: 'test-author', required: 1, filled: 0 },
    { seat: 'orchestrator', required: 1, filled: 1 },
    { seat: 'other', required: 1, filled: 0 },
  ])
})

test('S8: rows without a seat leave the seats key out', async () => {
  const world = threeLevels()
  world.context = { 't-work': { gateStatus: { canAdvance: true, phase: 'work', missing: [] }, schema: [{ key: 'a', role: 'work', required: true, filled: true }] } }
  const s = await snapshot(fake({ world }).io, 'root')
  expect('seats' in (s.gates['t-work'] as object)).toBe(false)
})

// ── refresh ────────────────────────────────────────────────────────────────────────────

test('refresh: 5 rapid calls give one snapshot 300ms after the last; atoms are written', async () => {
  resetRefreshState()
  const env = fake()
  env.state.scope = 'root'
  for (let i = 0; i < 5; i++) {
    void refresh(env.io)
    await env.advance(50)
  }
  await env.advance(249)
  expect(env.searches()).toBe(0)
  await env.advance(1)
  expect(env.searches()).toBe(1)
  expect(env.state.snapshot?.nodes).toHaveLength(6)
  expect(env.state.status).toEqual({ refreshing: false, liveSource: 'none' })
  await env.advance(10_000)
  expect(env.searches()).toBe(1)
})

test('refresh is single-flight: a call during a run queues exactly one more', async () => {
  resetRefreshState()
  const env = fake()
  env.state.scope = 'root'
  let release: () => void = () => undefined
  const gate = new Promise<void>(resolve => (release = resolve))
  const original = env.io.callTool
  let started = 0
  env.io.callTool = async (tool, args) => {
    if (tool === 'query_items' && args.operation === 'search') {
      started++
      if (started === 1) await gate
    }

    return original(tool, args)
  }
  const first = refresh(env.io)
  await env.advance(300)
  expect(started).toBe(1)
  // in flight: three more requests collapse into a single rerun
  void refresh(env.io)
  void refresh(env.io)
  void refresh(env.io)
  await env.advance(1_000)
  expect(started).toBe(1)
  release()
  await first
  await env.advance(299)
  expect(started).toBe(1)
  await env.advance(1)
  expect(started).toBe(2)
  await env.advance(5_000)
  expect(started).toBe(2)
})

test('timeout: a refresh stuck past 10s lets the waiting scope switch run; the stale result is discarded', async () => {
  resetRefreshState()
  const env = fake({ world: projectWorld() })
  let release: () => void = () => undefined
  const gate = new Promise<void>(resolve => (release = resolve))
  const original = env.io.callTool
  env.io.callTool = async (tool, args) => {
    if (tool === 'query_items' && args.operation === 'search') await gate

    return original(tool, args)
  }
  env.state.scope = 'cont-features'
  void refresh(env.io)
  await env.advance(300)
  // the switch to the project root queues behind the stuck run
  env.state.scope = null
  const switched = refreshNow(env.io)
  await env.advance(REFRESH_TIMEOUT_MS - 1_000)
  expect(env.state.snapshot).toBe(null)
  await env.advance(1_000)
  await switched
  expect(env.state.snapshot?.overview).toBe(true)
  expect(env.state.snapshot?.scopeId).toBe(ROOT)
  // the abandoned run finishing late must not overwrite the newer snapshot
  release()
  await env.advance(1_000)
  expect(env.state.snapshot?.scopeId).toBe(ROOT)
})

test('a failed snapshot lands in graphStatus.lastError', async () => {
  resetRefreshState()
  const env = fake({ files: {} })
  const done = refresh(env.io)
  await env.advance(300)
  await done
  expect(env.state.status).toEqual({ refreshing: false, liveSource: 'none', lastError: 'no project.rootId' })
})

// ── live sources ───────────────────────────────────────────────────────────────────────

test('no API URL: polls every 15s while subscribed; unsubscribe stops it', async () => {
  resetRefreshState()
  resetLiveState()
  const env = fake()
  env.state.scope = 'root'
  syncLive(env.io, 1)
  await env.advance(0)
  expect(env.state.status.liveSource).toBe('poll')
  await env.advance(14_999)
  expect(env.searches()).toBe(0)
  await env.advance(1)
  await env.advance(300)
  expect(env.searches()).toBe(1)
  await env.advance(15_000)
  expect(env.searches()).toBe(2)
  syncLive(env.io, 0)
  await env.advance(60_000)
  expect(env.searches()).toBe(2)
  expect(env.state.status.liveSource).toBe('none')
})

test('a second subscriber shares the one poll; it stops only when the count reaches 0', async () => {
  resetRefreshState()
  resetLiveState()
  const env = fake()
  env.state.scope = 'root'
  syncLive(env.io, 1)
  syncLive(env.io, 2)
  await env.advance(15_300)
  expect(env.searches()).toBe(1)
  syncLive(env.io, 1)
  await env.advance(15_300)
  expect(env.searches()).toBe(2)
  syncLive(env.io, 0)
  await env.advance(60_000)
  expect(env.searches()).toBe(2)
})

test('with zero subscribers nothing polls or streams', async () => {
  resetRefreshState()
  resetLiveState()
  const env = fake()
  await env.advance(120_000)
  expect(env.calls).toHaveLength(0)
})

test('SSE: events refresh; sync.lost refreshes at once; unsubscribe kills the stream', async () => {
  resetRefreshState()
  resetLiveState()
  const spawned: { argv: readonly string[]; input?: string }[] = []
  let closed = false
  let push: (text: string) => void = () => undefined
  const env = fake({
    env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001/', TASK_ORCHESTRATOR_API_TOKEN: 'sekrit' },
    spawn: request => {
      spawned.push(request)
      const queue: string[] = []
      let wake: () => void = () => undefined
      push = t => {
        queue.push(t)
        wake()
      }
      // A stream like the engine's: `return()` ends it at once, killing the child.
      const stream = {
        async next() {
          while (queue.length === 0 && !closed) await new Promise<void>(resolve => (wake = resolve))
          if (closed) return { done: true as const, value: undefined }

          return { done: false as const, value: { stream: 'stdout' as const, text: queue.shift() as string } }
        },
        async return() {
          closed = true
          wake()

          return { done: true as const, value: undefined }
        },
        [Symbol.asyncIterator]() {
          return stream
        },
      }

      return stream as never
    },
  })
  env.state.scope = 'root'
  syncLive(env.io, 1)
  await env.advance(0)
  expect(spawned).toHaveLength(1)
  const argvText = spawned[0]!.argv.join(' ')
  expect(argvText).toContain(`http://localhost:3001/api/v1/events?root=${ROOT}&types=item.created,item.updated`)
  expect(argvText).not.toContain('sekrit')
  expect(spawned[0]!.input).toContain('Authorization: Bearer sekrit')
  expect(env.state.status.liveSource).toBe('sse')
  push(': hello\n\nevent: item.upda')
  push('ted\ndata: {}\n\n')
  await env.advance(299)
  expect(env.searches()).toBe(0)
  await env.advance(1)
  expect(env.searches()).toBe(1)
  push('event: sync.lost\ndata: {"reason":"queue_overflow"}\n\n')
  await env.advance(0)
  expect(env.searches()).toBe(2)
  syncLive(env.io, 0)
  await env.advance(0)
  expect(closed).toBe(true)
})

test('SSE: three failed spawns back off 2s, 4s then fall back to polling', async () => {
  resetRefreshState()
  resetLiveState()
  let spawns = 0
  const env = fake({
    env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' },
    // eslint-disable-next-line require-yield
    spawn: async function* () {
      spawns++
      throw new Error('curl: not found')
    },
  })
  env.state.scope = 'root'
  syncLive(env.io, 1)
  await env.advance(0)
  expect(spawns).toBe(1)
  await env.advance(1_999)
  expect(spawns).toBe(1)
  await env.advance(1)
  expect(spawns).toBe(2)
  await env.advance(3_999)
  expect(spawns).toBe(2)
  await env.advance(1)
  expect(spawns).toBe(3)
  expect(env.state.status.liveSource).toBe('poll')
  await env.advance(15_300)
  expect(env.searches()).toBe(1)
  expect(spawns).toBe(3)
  syncLive(env.io, 0)
})

test('S16: restartLive cancels the permanent poll fallback and spawns again; with 0 subscribers it starts nothing', async () => {
  resetRefreshState()
  resetLiveState()
  let spawns = 0
  const env = fake({
    env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' },
    // eslint-disable-next-line require-yield
    spawn: async function* () {
      spawns++
      throw new Error('curl: not found')
    },
  })
  env.state.scope = 'root'
  syncLive(env.io, 1)
  await env.advance(6_000)
  expect(spawns).toBe(3)
  expect(env.state.status.liveSource).toBe('poll')
  restartLive(env.io, 1)
  await env.advance(0)
  expect(spawns).toBe(4)
  // the first poll would have fired 15s after it started (~21s in); it was cancelled
  await env.advance(15_500)
  expect(env.searches()).toBe(0)
  syncLive(env.io, 0)
  await env.advance(0)
  const before = spawns
  restartLive(env.io, 0)
  await env.advance(10_000)
  expect(spawns).toBe(before)
})

test('S18: an SSE item.updated frame invalidates the label cache; other frames do not', async () => {
  resetRefreshState()
  resetLiveState()
  let release: () => void = () => undefined
  const gate = new Promise<void>(resolve => (release = resolve))
  const env = fake({
    world: withProps('{"planLabel":"T3"}'),
    env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' },
    spawn: async function* () {
      yield { stream: 'stdout' as const, text: 'event: item.created\ndata: {}\n\n' }
      await gate
      yield { stream: 'stdout' as const, text: 'event: item.updated\ndata: {}\n\n' }
      await new Promise<void>(() => undefined)

      return { code: 0, signal: null }
    },
  })
  env.state.scope = 'root'
  syncLive(env.io, 1)
  await env.advance(300)
  expect(env.gets()).toBe(6)
  release()
  await env.advance(300)
  expect(env.gets()).toBe(12)
  syncLive(env.io, 0)
})

test('SSE: no rootId means polling, never a stream', async () => {
  resetRefreshState()
  resetLiveState()
  let spawns = 0
  const env = fake({
    files: {},
    env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' },
    // eslint-disable-next-line require-yield
    spawn: async function* () {
      spawns++
      throw new Error('should not spawn')
    },
  })
  syncLive(env.io, 1)
  await env.advance(0)
  expect(spawns).toBe(0)
  expect(env.state.status.liveSource).toBe('poll')
  syncLive(env.io, 0)
})

// ── pure helpers ───────────────────────────────────────────────────────────────────────

test('createSseParser: frames split across chunks, comments and CRLF', () => {
  const seen: string[] = []
  const feed = createSseParser(n => seen.push(n))
  feed(': keepalive\n\nevent: item.cre')
  feed('ated\r\ndata: {}\r\n\r\nevent: sync.lost\ndata: {"reason":"x"}\n\n')
  feed('data: only data\n\n')
  expect(seen).toEqual(['item.created', 'sync.lost'])
})

test('curlRequest keeps the token out of argv; eventsUrl spells the types out', () => {
  const withToken = curlRequest('http://h/x', 'tok"en')
  expect(withToken.argv.join(' ')).not.toContain('tok')
  expect(withToken.input).toBe('header = "Authorization: Bearer tok\\"en"\n')
  expect(curlRequest('http://h/x', undefined).input).toBe(undefined)
  expect(eventsUrl('http://h', 'abc')).toBe(
    'http://h/api/v1/events?root=abc&types=item.created,item.updated,item.deleted,item.advanced,note.upserted,note.deleted,dependency.added,dependency.removed',
  )
})

test('isLoopbackApiUrl accepts only bare loopback origins', () => {
  for (const ok of ['http://localhost:3001', 'http://127.0.0.1:3001/', 'http://[::1]:3001', 'https://localhost']) expect(isLoopbackApiUrl(ok)).toBe(true)
  for (const bad of ['http://evil.example', 'http://localhost:3001/x', 'http://localhost:3001/?a=1', 'http://u:p@localhost', 'ftp://localhost', 'nonsense'])
    expect(isLoopbackApiUrl(bad)).toBe(false)
})

test('resolveApiUrl: env first, then loopback-only project client.json, then the user file', async () => {
  const files: Record<string, string> = {
    '.taskorchestrator/client.json': '{"apiUrl":"http://evil.example"}',
    '/home/u/.taskorchestrator/client.json': '{"apiUrl":"http://example.test:9/"}',
  }
  expect(await resolveApiUrl(fake({ files, env: { HOME: '/home/u' } }).io)).toBe('http://example.test:9')
  files['.taskorchestrator/client.json'] = '{"apiUrl":"http://localhost:3001"}'
  expect(await resolveApiUrl(fake({ files, env: { HOME: '/home/u' } }).io)).toBe('http://localhost:3001')
  expect(await resolveApiUrl(fake({ files, env: { TASK_ORCHESTRATOR_API_URL: 'https://to.example/' } }).io)).toBe('https://to.example')
  expect(await resolveApiUrl(fake({ files: {}, env: {} }).io)).toBe(null)
})

test('shouldRefresh: write tools only, not our own calls, denies or errors', () => {
  expect(shouldRefresh(`${TO}advance_item`, 'engine', {})).toBe(true)
  expect(shouldRefresh(`${TO}claim_item`, 'engine', {})).toBe(true)
  expect(shouldRefresh(`${TO}query_items`, 'engine', {})).toBe(false)
  expect(shouldRefresh('Bash', 'engine', {})).toBe(false)
  expect(shouldRefresh(`${TO}advance_item`, 'task-orchestrator-mod', {})).toBe(false)
  expect(shouldRefresh(`${TO}advance_item`, 'engine', { deny: 'no' })).toBe(false)
  expect(shouldRefresh(`${TO}advance_item`, 'engine', { isError: true })).toBe(false)
})

test('the hook matcher and WRITE_TOOLS name the same tools', () => {
  for (const name of WRITE_TOOLS) expect(WRITE_TOOL_NAME.test(`${TO}${name}`)).toBe(true)
  for (const name of ['query_items', 'query_dependencies', 'get_context', 'get_next_item', 'manage_plan_documents'])
    expect(WRITE_TOOL_NAME.test(`${TO}${name}`)).toBe(false)
})

// ── the loaded plugin: tool.call hook ──────────────────────────────────────────────────

/** The TO server and project config on the test's `on`; returns the call log. */
function serveHooks(on: On, world: World = threeLevels()): Call[] {
  const calls: Call[] = []
  on('fs.read', async (_$, e) => {
    if (!e.path.split(String.fromCharCode(92)).join('/').endsWith('/.taskorchestrator/config.yaml')) throw new Error(`ENOENT ${e.path}`)

    return { value: CONFIG } as never
  })
  on('mcp.call', async (_$, e) => {
    calls.push({ tool: e.tool, args: e.args })

    return { value: answer(world, e.tool, e.args) } as never
  })

  return calls
}

const searchesOf = (calls: Call[]) => calls.filter(c => c.tool === 'query_items' && (c.args.operation === 'search' || c.args.operation === 'overview')).length
const getsOf = (calls: Call[]) => calls.filter(c => c.tool === 'query_items' && c.args.operation === 'get' && c.args.includeAncestors !== true).length
const ok = { ref: 'r', result: { ok: true }, text: 'ok' }

test('five rapid advance_item calls give one snapshot after 300ms; the result passes through', async ($, on) => {
  const calls = serveHooks(on)
  const clock = mock.clock(on)
  on('tool.call', async () => ok as never)
  const results: unknown[] = []
  for (let i = 0; i < 5; i++) {
    results.push(await $.tool.call({ tool: `${TO}advance_item`, itemId: 'x', trigger: 'start' } as never))
    await clock.advance(50)
  }
  for (const r of results) expect(r).toMatchObject({ result: { ok: true }, text: 'ok' })
  await clock.advance(249)
  expect(searchesOf(calls)).toBe(0)
  await clock.advance(1)
  await clock.settle()
  expect(searchesOf(calls)).toBe(1)
  await clock.advance(5_000)
  expect(searchesOf(calls)).toBe(1)
  for (const c of calls) expect(['query_items', 'query_dependencies', 'get_context']).toContain(c.tool)
})

test('O1: a refresh that reads an unchanged graph does not rewrite the snapshot or status', async ($, on) => {
  serveHooks(on)
  const clock = mock.clock(on)
  on('tool.call', async () => ok as never)
  const writes: string[] = []
  on('state.set', async (_$, e, next) => {
    if (e.plugin === 'task-orchestrator-mod' && (e.key === 'graphSnapshot' || e.key === 'graphStatus')) writes.push(e.key)

    return next(e)
  })
  for (let i = 0; i < 2; i++) {
    await $.tool.call({ tool: `${TO}advance_item`, itemId: 'x', trigger: 'start' } as never)
    await clock.advance(1_000)
    await clock.settle()
  }
  expect(writes.filter(k => k === 'graphSnapshot')).toHaveLength(1)
  expect(writes.filter(k => k === 'graphStatus').length).toBeLessThanOrEqual(1)
})

test('a read tool, a denied call and an errored call do not refresh', async ($, on) => {
  const calls = serveHooks(on)
  const clock = mock.clock(on)
  on('tool.call', async (_$, e) => {
    if (e.tool.endsWith('manage_notes')) return { deny: 'blocked' } as never
    if (e.tool.endsWith('manage_items')) return { ...ok, isError: true } as never

    return ok as never
  })
  await $.tool.call({ tool: `${TO}query_items`, operation: 'get', itemId: 'x' } as never)
  await $.tool.call({ tool: `${TO}manage_notes`, operation: 'upsert' } as never)
  await $.tool.call({ tool: `${TO}manage_items`, operation: 'create' } as never)
  await $.tool.call({ tool: 'Bash', command: 'ls' } as never)
  await clock.advance(2_000)
  expect(calls).toHaveLength(0)
})

test('each write tool in the trigger list refreshes', async ($, on) => {
  const calls = serveHooks(on)
  const clock = mock.clock(on)
  on('tool.call', async () => ok as never)
  let expected = 0
  for (const name of WRITE_TOOLS) {
    await $.tool.call({ tool: `${TO}${name}` } as never)
    await clock.advance(300)
    await clock.settle()
    expect(searchesOf(calls)).toBe(++expected)
  }
})

test('S6: a successful manage_items call re-reads plan labels on the next refresh; advance_item does not', async ($, on) => {
  // A feature scope: only the full subtree walk reads plan labels (the root overview does not).
  const world: World = { items: [{ id: 'feat', title: 'Feature', role: 'work', depth: 1, properties: '{"planLabel":"T1"}' }] }
  const calls = serveHooks(on, world)
  on('state.get', async (_$, e) => ({ value: { value: (e as { key: string }).key === 'graphScope' ? 'feat' : undefined, version: 1 } }) as never)
  const clock = mock.clock(on)
  on('tool.call', async () => ok as never)
  const settle = async () => {
    await clock.advance(300)
    await clock.settle()
  }
  await $.tool.call({ tool: `${TO}advance_item`, itemId: 'x', trigger: 'start' } as never)
  await settle()
  expect(searchesOf(calls)).toBe(1)
  const warm = getsOf(calls)
  await $.tool.call({ tool: `${TO}advance_item`, itemId: 'x', trigger: 'start' } as never)
  await settle()
  expect(searchesOf(calls)).toBe(2)
  expect(getsOf(calls)).toBe(warm)
  await $.tool.call({ tool: `${TO}manage_items`, operation: 'update' } as never)
  await settle()
  expect(searchesOf(calls)).toBe(3)
  expect(getsOf(calls)).toBe(warm + 1)
})

// No-op write guards (flash fix 26f550b0): unchanged snapshots and statuses are never rewritten.
test('sameSnapshot ignores takenAt and catches real changes', () => {
  const base = { scopeId: 's', rootId: 'r', nodes: [], edges: [], external: {}, gates: {}, takenAt: 1, truncated: false } as unknown as GraphSnapshot
  expect(sameSnapshot(base, { ...base, takenAt: 999 })).toBe(true)
  expect(sameSnapshot(null, base)).toBe(false)
  expect(sameSnapshot(base, { ...base, truncated: true })).toBe(false)
  expect(sameSnapshot(base, { ...base, gates: { x: { canAdvance: true } } } as unknown as GraphSnapshot)).toBe(false)
})

test('sameStatus compares every field', () => {
  const s = { refreshing: false, liveSource: 'sse' } as GraphStatus
  expect(sameStatus(s, { ...s })).toBe(true)
  expect(sameStatus(s, { ...s, liveSource: 'poll' })).toBe(false)
  expect(sameStatus(s, { ...s, lastError: 'x' })).toBe(false)
})

test('trailOf: ancestors root first, then the item; tolerates missing titles and bad input', () => {
  const raw = { id: 'feat', title: 'Feature X', ancestors: [{ id: 'root', title: 'Project', depth: 0 }, { id: 'cont', depth: 1 }] }
  expect(trailOf(raw, 'feat')).toEqual([{ id: 'root', title: 'Project' }, { id: 'cont', title: 'cont' }, { id: 'feat', title: 'Feature X' }])
  expect(trailOf({ id: 'solo', title: 'Solo' }, 'solo')).toEqual([{ id: 'solo', title: 'Solo' }])
  expect(trailOf(null, 'x')).toBeUndefined()
})
