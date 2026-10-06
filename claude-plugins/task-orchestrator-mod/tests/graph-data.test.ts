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
import { NODE_CAP, STALL_MS, TRANSITION_LIMIT, snapshot, stallsOf, trailOf } from '../src/graph-data/snapshot.ts'
import { MAX_TOASTS, capToasts, gateToastLines, readyIds, snapshotEvents, toastLines } from '../src/graph-data/events.ts'
import { LOCAL_ECHO_MS, REMOTE_CAP, clearRemote, isLocalEcho, localWindow, markRemote, resetRemoteState, resultItemIds, withLocalWrite } from '../src/graph-data/remote.ts'

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
  /** The get_context session-resume answer (stalls); failResume makes it an error result. */
  resume?: unknown
  failResume?: boolean
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
  if (tool === 'get_context' && args.mode === 'session-resume') return world.failResume === true ? failure('resume boom') : text(world.resume ?? {})
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
  // Per-item gate reads; the one session-resume read (stalls) names no itemId.
  expect(env.calls.filter(c => c.tool === 'get_context' && c.args.mode !== 'session-resume').map(c => c.args.itemId).sort()).toEqual(['feat', 'root', 't-review', 't-work'])
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

// ── T12a 13497d52: stalled items (session-resume) ──────────────────────────────────────

const MIN = 60_000
/** fake()'s clock starts at 1_000 ms. */
const iso = (ms: number) => new Date(ms).toISOString()
const resumeOf = (stalled: string[], moves: { itemId: string; at: number }[]) => ({
  stalledItems: stalled.map(id => ({ id, title: id, role: id === 't-review' ? 'review' : 'work', missingNotes: ['implementation-notes', 'session-tracking'] })),
  recentTransitions: moves.map(m => ({ itemId: m.itemId, fromRole: 'queue', toRole: 'work', at: iso(m.at) })),
})
const resumeCalls = (calls: Call[]) => calls.filter(c => c.tool === 'get_context' && c.args.mode === 'session-resume')

test('S5: stalled = listed work/review items with no transition in the last 30 min; one scoped session-resume read', async () => {
  const world = threeLevels()
  world.resume = resumeOf(['t-work', 't-review', 'not-in-scope'], [{ itemId: 't-review', at: 1_000 - 5 * MIN }])
  const env = fake({ world })
  const s = await snapshot(env.io, 'root')
  expect(s.error).toBe(undefined)
  expect(s.stalled).toEqual({ 't-work': ['implementation-notes', 'session-tracking'] })
  // t-review moved 5 minutes ago: it crosses 30 minutes after that move.
  expect(s.stallDueAt).toBe(1_000 - 5 * MIN + STALL_MS)
  const calls = resumeCalls(env.calls)
  expect(calls).toHaveLength(1)
  expect(calls[0]?.args).toEqual({ mode: 'session-resume', since: iso(1_000 - STALL_MS), ancestorId: 'root', limit: TRANSITION_LIMIT })
})

test('S5: stallsOf ignores non-work/review rows and unknown ids, and a move already 30 min old counts as stalled', () => {
  const ids = new Set(['a', 'b', 'q'])
  const raw = {
    stalledItems: [{ id: 'a', role: 'work', missingNotes: ['x'] }, { id: 'b', role: 'review', missingNotes: [] }, { id: 'q', role: 'queue', missingNotes: ['y'] }, { id: 'zz', role: 'work' }],
    recentTransitions: [{ itemId: 'b', at: iso(0) }],
  }
  expect(stallsOf(raw, ids, STALL_MS + 1)).toEqual({ stalled: { a: ['x'], b: [] } })
  expect(stallsOf(raw, ids, 10)).toEqual({ stalled: { a: ['x'] }, dueAt: STALL_MS })
  expect(stallsOf('junk', ids, 0)).toEqual({ stalled: {} })
})

test('S6: a full page of 200 transitions may be cut: no stalls at all', async () => {
  const world = threeLevels()
  const moves = Array.from({ length: TRANSITION_LIMIT }, (_, i) => ({ itemId: `other-${i}`, at: 1_000 - MIN }))
  world.resume = resumeOf(['t-work'], moves)
  const s = await snapshot(fake({ world }).io, 'root')
  expect(s.stalled).toEqual({})
  expect(s.stallDueAt).toBe(undefined)
  // One fewer is a complete list: the stall shows.
  world.resume = resumeOf(['t-work'], moves.slice(1))
  expect((await snapshot(fake({ world }).io, 'root')).stalled).toEqual({ 't-work': ['implementation-notes', 'session-tracking'] })
})

test('S7: a failed session-resume read is recorded in error; stalled stays unset; the rest of the snapshot is intact', async () => {
  const world = threeLevels()
  world.failResume = true
  world.context = { 't-work': { gateStatus: { canAdvance: false, phase: 'work', missing: ['x'] }, schema: [] } }
  const s = await snapshot(fake({ world }).io, 'root')
  expect(s.error).toContain('resume boom')
  expect('stalled' in s).toBe(false)
  expect(s.nodes.map(n => n.id).sort()).toEqual(['feat', 'root', 'sub', 't-queue', 't-review', 't-work'])
  expect(s.gates['t-work']?.missing).toEqual(['x'])
})

test('S7: the overview reads no session-resume and has no stalls', async () => {
  const env = fake({ world: projectWorld() })
  const s = await snapshot(env.io, null)
  expect(s.overview).toBe(true)
  expect(resumeCalls(env.calls)).toHaveLength(0)
  expect(s.stalled).toBe(undefined)
})

test('S8: a quiet period re-snapshots once, 1s after the earliest listed item crosses 30 min; none when nothing is pending', async () => {
  resetRefreshState()
  const world = threeLevels()
  // t-work moved 5 minutes before the clock's start: it crosses at 1_000 - 5 min + 30 min.
  const movedAt = 1_000 - 5 * MIN
  world.resume = resumeOf(['t-work'], [{ itemId: 't-work', at: movedAt }])
  const env = fake({ world })
  // The timer's refresh reads the scope atom, as a live one does.
  env.state.scope = 'root'
  void refresh(env.io, 'root')
  await env.advance(300)
  expect(resumeCalls(env.calls)).toHaveLength(1)
  expect(env.state.snapshot?.stalled).toEqual({})
  // Just before crossing + 1s: nothing yet.
  await env.advance(movedAt + STALL_MS + 1_000 - 1_300 - 1)
  expect(resumeCalls(env.calls)).toHaveLength(1)
  // The crossing timer fires, then the refresh debounce runs: one more read, and now it is stalled.
  await env.advance(1 + 300)
  expect(resumeCalls(env.calls)).toHaveLength(2)
  expect(env.state.snapshot?.stalled).toEqual({ 't-work': ['implementation-notes', 'session-tracking'] })
  // Nothing pending any more: no further re-snapshot however long it stays quiet.
  await env.advance(2 * 60 * MIN)
  expect(resumeCalls(env.calls)).toHaveLength(2)
  resetRefreshState()
})

test('S8: no recently moved listed item, no crossing timer', async () => {
  resetRefreshState()
  const world = threeLevels()
  world.resume = resumeOf(['t-work'], [])
  const env = fake({ world })
  void refresh(env.io, 'root')
  await env.advance(300)
  expect(resumeCalls(env.calls)).toHaveLength(1)
  await env.advance(3 * 60 * MIN)
  expect(resumeCalls(env.calls)).toHaveLength(1)
  resetRefreshState()
})

// ── T12b 6a976f10: toasts (snapshot events) ─────────────────────────────────────────────

type EvNode = import('../types').GraphNode
const LBL: Record<string, string> = { a: 'T1', b: 'T2', c: 'T3', d: 'T4' }
/** A feature scope: `roles` maps a..d to `role` or `role:statusLabel`; a BLOCKS b. */
const evSnap = (roles: Record<string, string>, extra: Partial<GraphSnapshot> = {}): GraphSnapshot => ({
  scopeId: 'feat',
  rootId: ROOT,
  nodes: [
    { id: 'feat', parentId: null, title: 'Feature', role: 'work', depth: 0 },
    ...Object.entries(roles).map(([id, r]): EvNode => {
      const [role, statusLabel] = r.split(':') as [string, string | undefined]

      return { id, parentId: 'feat', title: `Title ${id}`, role, depth: 1, planLabel: LBL[id], ...(statusLabel !== undefined ? { statusLabel } : {}) }
    }),
  ],
  edges: [{ from: 'a', to: 'b', type: 'BLOCKS' }],
  external: {},
  gates: {},
  takenAt: 1,
  truncated: false,
  ...extra,
})

test('S4: snapshotEvents: done, ready and back-to-work; a cancel raises nothing', () => {
  const prev = evSnap({ a: 'work', b: 'queue', c: 'review', d: 'work' })
  const next = evSnap({ a: 'terminal', b: 'queue', c: 'work', d: 'terminal:cancelled' })
  expect(snapshotEvents(prev, next)).toEqual([
    { kind: 'done', id: 'a', label: 'T1', title: 'Title a' },
    { kind: 'ready', id: 'b', label: 'T2', title: 'Title b' },
    { kind: 'back', id: 'c', label: 'T3', title: 'Title c' },
  ])
  // readiness is the pane's: queue with every blocker satisfied.
  expect([...readyIds(prev)]).toEqual([])
  expect([...readyIds(next)]).toEqual(['b'])
})

test('S4: snapshotEvents: no prev, another scope, an overview or a partial snapshot give nothing', () => {
  const prev = evSnap({ a: 'work', b: 'queue', c: 'review' })
  const next = evSnap({ a: 'terminal', b: 'queue', c: 'work' })
  expect(snapshotEvents(null, next)).toEqual([])
  expect(snapshotEvents({ ...prev, scopeId: 'other' }, next)).toEqual([])
  expect(snapshotEvents(prev, { ...next, scopeId: 'other' })).toEqual([])
  expect(snapshotEvents({ ...prev, overview: true }, next)).toEqual([])
  expect(snapshotEvents(prev, { ...next, overview: true })).toEqual([])
  expect(snapshotEvents(prev, { ...next, error: 'query_dependencies failed' })).toEqual([])
  // Unchanged roles, and a node new in next, raise nothing.
  expect(snapshotEvents(prev, prev)).toEqual([])
  expect(snapshotEvents(evSnap({ a: 'work' }), evSnap({ a: 'work', d: 'terminal' }))).toEqual([])
  // queue -> work is not "ready"; work -> review is not "back".
  expect(snapshotEvents(evSnap({ a: 'terminal', b: 'queue', c: 'work' }), evSnap({ a: 'terminal', b: 'work', c: 'review' }))).toEqual([])
})

test('S7: five events give three toasts plus one +2 more graph changes; titles are cut to 60', () => {
  const ev = (i: number) => ({ kind: 'done' as const, id: `i${i}`, label: `T${i}`, title: `Title ${i}` })
  expect(toastLines([1, 2, 3, 4, 5].map(ev))).toEqual(['✓ Done: T1 Title 1', '✓ Done: T2 Title 2', '✓ Done: T3 Title 3', '+2 more graph changes'])
  expect(MAX_TOASTS).toBe(3)
  expect(toastLines([1, 2, 3].map(ev))).toHaveLength(3)
  expect(capToasts([])).toEqual([])
  const long = toastLines([{ kind: 'ready', id: 'x', label: 'T9', title: 'y'.repeat(80) }])[0] as string
  expect(long).toBe(`○ Ready: T9 ${'y'.repeat(59)}…`)
  expect(toastLines([{ kind: 'back', id: 'x', label: 'T9', title: 't' }])).toEqual(['↺ Back to work: T9 t'])
})

test('S8 (unit): gate toast lines use the snapshot label, else the id8; the target and missing keys', () => {
  const snap = evSnap({ a: 'work' })
  expect(gateToastLines([{ itemId: 'a', missing: ['implementation-notes', 'session-tracking'], targetRole: 'review' }], snap)).toEqual(['✗ Gate blocked: T1 -> review: implementation-notes, session-tracking'])
  expect(gateToastLines([{ itemId: '0123456789abcdef', missing: ['x'] }], snap)).toEqual(['✗ Gate blocked: 01234567: x'])
  expect(gateToastLines([{ itemId: 'a', missing: [] }], null)).toEqual(['✗ Gate blocked: a: required notes missing'])
})

/** In-memory `$.state` on the test's `on`, seeded; answers every key. */
function stateRig(on: On, seed: Record<string, unknown> = {}) {
  const slot = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`
  const state = new Map<string, { value: unknown; version: number }>()
  for (const [key, value] of Object.entries(seed)) state.set(`task-orchestrator-mod/${key}/`, { value, version: 1 })
  const sets: string[] = []
  on('state.get', async (_$, e) => ({ value: state.get(slot(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slot(e as never)
    const cur = state.get(k)?.version ?? 0
    state.set(k, { value: (e as { value: unknown }).value, version: cur + 1 })
    sets.push((e as { key: string }).key)

    return { value: { isSet: true, version: cur + 1 } } as never
  })

  const put = (key: string, value: unknown) => state.set(`task-orchestrator-mod/${key}/`, { value, version: (state.get(`task-orchestrator-mod/${key}/`)?.version ?? 0) + 1 })

  return { sets, put, valueOf: (key: string) => state.get(`task-orchestrator-mod/${key}/`)?.value }
}

/** A feature-scoped plugin with the TO server, mocked time and toasts captured. */
function toastRig(on: On) {
  const world = threeLevels()
  serveHooks(on, world)
  const st = stateRig(on, { graphScope: 'feat' })
  const clock = mock.clock(on)
  on('tool.call', async () => ok as never)
  const toasts: string[] = []
  on('ui.toast', async (_$, e) => {
    toasts.push(e.text)

    return { value: undefined } as never
  })

  return { world, st, clock, toasts }
}

const writeAndSettle = async ($: Parameters<Parameters<typeof test>[1]>[0], clock: ReturnType<typeof mock.clock>) => {
  await $.tool.call({ tool: `${TO}advance_item`, itemId: 'x', trigger: 'start' } as never)
  await clock.advance(300)
  await clock.settle()
}

test('S5/S9: graphToasts on: the first snapshot is quiet, a completion toasts once, an unchanged refresh does not', { options: { graphToasts: true } }, async ($, on) => {
  const { world, st, clock, toasts } = toastRig(on)
  await writeAndSettle($, clock)
  expect((st.valueOf('graphSnapshot') as GraphSnapshot | undefined)?.scopeId).toBe('feat')
  expect(toasts).toEqual([])
  ;(world.items.find(i => i.id === 't-review') as Item).role = 'terminal'
  await writeAndSettle($, clock)
  expect(toasts).toEqual(['✓ Done: t-review Reviewed task'])
  // The same graph again: the no-op guard skips the write, and no toast.
  const writes = st.sets.filter(k => k === 'graphSnapshot').length
  await writeAndSettle($, clock)
  expect(st.sets.filter(k => k === 'graphSnapshot')).toHaveLength(writes)
  expect(toasts).toHaveLength(1)
})

test('S6: graphToasts left at its default (off): a completion raises no toast', async ($, on) => {
  const { world, st, clock, toasts } = toastRig(on)
  await writeAndSettle($, clock)
  ;(world.items.find(i => i.id === 't-review') as Item).role = 'terminal'
  await writeAndSettle($, clock)
  expect((st.valueOf('graphSnapshot') as GraphSnapshot).nodes.find(n => n.id === 't-review')?.role).toBe('terminal')
  expect(toasts).toEqual([])
})

test('S5: graphToasts on: the first snapshot after a scope switch is quiet, both ways', { options: { graphToasts: true } }, async ($, on) => {
  const { world, st, clock, toasts } = toastRig(on)
  const item = (id: string) => world.items.find(i => i.id === id) as Item
  item('sub').role = 'work'
  await writeAndSettle($, clock)
  // Switch to t-work's subtree while sub completes: the same node, another scope, no toast.
  item('sub').role = 'terminal'
  st.put('graphScope', 't-work')
  await writeAndSettle($, clock)
  expect((st.valueOf('graphSnapshot') as GraphSnapshot).scopeId).toBe('t-work')
  expect(toasts).toEqual([])
  // Back on feat while t-review completes: still quiet (the previous snapshot is t-work's).
  item('t-review').role = 'terminal'
  st.put('graphScope', 'feat')
  await writeAndSettle($, clock)
  expect(toasts).toEqual([])
  // A completion within one scope does toast here.
  item('t-queue').role = 'terminal'
  await writeAndSettle($, clock)
  expect(toasts).toEqual(['✓ Done: t-queue Queued task'])
})

// ── T12b 6a976f10: cross-session marks ──────────────────────────────────────────────────

test('S10: createSseParser passes the parsed data; malformed or missing data gives undefined', () => {
  const seen: [string, unknown][] = []
  const feed = createSseParser((n, d) => seen.push([n, d]))
  feed('event: item.advanced\ndata: {"id":3,"event":"item.advanced","itemId":"A","newRole":"work"}\n\n')
  feed('event: item.updated\r\ndata: {not json\r\n\r\n')
  feed('event: note.upserted\n\n')
  feed('data: {"itemId":"orphan"}\n\n')
  expect(seen).toEqual([
    ['item.advanced', { id: 3, event: 'item.advanced', itemId: 'A', newRole: 'work' }],
    ['item.updated', undefined],
    ['note.upserted', undefined],
  ])
})

test('S11: isLocalEcho: in flight, and up to 5s after the last local write ended', () => {
  const end = 100_000
  expect(LOCAL_ECHO_MS).toBe(5_000)
  expect(isLocalEcho({ inFlight: 1, lastEnd: null }, end)).toBe(true)
  expect(isLocalEcho({ inFlight: 0, lastEnd: null }, end)).toBe(false)
  expect(isLocalEcho({ inFlight: 0, lastEnd: end }, end + 4_999)).toBe(true)
  expect(isLocalEcho({ inFlight: 0, lastEnd: end }, end + 5_001)).toBe(false)
})

test('withLocalWrite: a failing clock never replaces the write\'s result or error, and still closes the window', async () => {
  resetRemoteState()
  const noClock = async (): Promise<number> => Promise.reject(new Error('clock gone'))
  expect(await withLocalWrite(async () => 'tool result', noClock)).toBe('tool result')
  expect(localWindow()).toEqual({ inFlight: 0, lastEnd: null })
  await expect(withLocalWrite(async () => Promise.reject(new Error('write failed')), noClock)).rejects.toThrow('write failed')
  expect(localWindow()).toEqual({ inFlight: 0, lastEnd: null })
  // An earlier end time survives a write whose end time cannot be read.
  await withLocalWrite(async () => 'ok', async () => 4_000)
  await withLocalWrite(async () => 'ok', noClock)
  expect(localWindow()).toEqual({ inFlight: 0, lastEnd: 4_000 })
  resetRemoteState()
})

test('S11: withLocalWrite holds the window open while the write runs and closes it at its end, even on a throw', async () => {
  resetRemoteState()
  let inside = -1
  const result = await withLocalWrite(async () => {
    inside = localWindow().inFlight

    return 'ran'
  }, async () => 7_000)
  expect(result).toBe('ran')
  expect(inside).toBe(1)
  expect(localWindow()).toEqual({ inFlight: 0, lastEnd: 7_000 })
  let thrown = ''
  try {
    await withLocalWrite(async () => Promise.reject(new Error('boom')), async () => 9_000)
  } catch (err) {
    thrown = (err as Error).message
  }
  expect(thrown).toBe('boom')
  expect(localWindow()).toEqual({ inFlight: 0, lastEnd: 9_000 })
  resetRemoteState()
})

test('S12: markRemote and clearRemote keep identity when nothing changes; the oldest go past 200', () => {
  const one = markRemote({}, 'A', 1)
  expect(one).toEqual({ A: 1 })
  expect(markRemote(one, 'A', 2)).toBe(one)
  expect(clearRemote(one, ['B'])).toBe(one)
  expect(clearRemote(one, [])).toBe(one)
  expect(clearRemote({ 'abcd1234-ffff': 1, B: 2 }, ['abcd'])).toEqual({ B: 2 })
  expect(clearRemote({ abcd: 1 }, ['abc'])).toEqual({ abcd: 1 })
  let map: Record<string, number> = {}
  for (let i = 0; i < REMOTE_CAP; i++) map = markRemote(map, `id${i}`, 1_000 + i)
  expect(Object.keys(map)).toHaveLength(200)
  map = markRemote(map, 'new', 5_000)
  expect(Object.keys(map)).toHaveLength(200)
  expect(map.id0).toBeUndefined()
  expect(map.id1).toBe(1_001)
  expect(map.new).toBe(5_000)
})

test('S12: resultItemIds reads each row id and its cascade ids; bad text gives []', () => {
  const text = JSON.stringify({ results: [{ itemId: 'A', applied: true, cascadeEvents: [{ itemId: 'P', title: 'p' }] }, { itemId: 'B', applied: false }] })
  expect(resultItemIds(text)).toEqual(['A', 'P', 'B'])
  expect(resultItemIds('nope')).toEqual([])
  expect(resultItemIds(undefined)).toEqual([])
})

test('S13: an SSE item event marks its item unless a local write is in flight or ended under 5s ago', async () => {
  resetRefreshState()
  resetLiveState()
  resetRemoteState()
  const queue: string[] = []
  let wake: () => void = () => undefined
  const push = (t: string) => {
    queue.push(t)
    wake()
  }
  const env = fake({
    env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' },
    spawn: async function* () {
      for (;;) {
        while (queue.length === 0) await new Promise<void>(resolve => (wake = resolve))
        yield { stream: 'stdout' as const, text: queue.shift() as string }
      }
    },
  })
  env.state.scope = 'root'
  let marks: Record<string, number> = {}
  let writes = 0
  const io: GraphIo = {
    ...env.io,
    updateRemote: async step => {
      const next = step(marks)
      if (next !== marks) {
        marks = next
        writes++
      }
    },
  }
  const frame = (event: string, itemId: string) => `event: ${event}\ndata: ${JSON.stringify({ id: 1, event, itemId })}\n\n`
  syncLive(io, 1)
  await env.advance(0)
  // Another session advanced A: marked.
  push(frame('item.advanced', 'A'))
  await env.advance(0)
  expect(Object.keys(marks)).toEqual(['A'])
  // The same event again writes nothing.
  push(frame('item.advanced', 'A'))
  await env.advance(0)
  expect(writes).toBe(1)
  // During a local manage_notes call, B's event is our echo.
  await withLocalWrite(async () => {
    push(frame('note.upserted', 'B'))
    await env.advance(0)
  }, () => io.now())
  expect(marks.B).toBeUndefined()
  // 4s after the write ended: still an echo. 6s after: another session's.
  await env.advance(4_000)
  push(frame('item.updated', 'C'))
  await env.advance(0)
  expect(marks.C).toBeUndefined()
  await env.advance(2_000)
  push(frame('item.updated', 'C'))
  await env.advance(0)
  expect(marks.C).toBeDefined()
  // A bus-level event (no itemId) marks nothing.
  push('event: sync.lost\ndata: {"id":9,"event":"sync.lost","reason":"queue_overflow"}\n\n')
  await env.advance(0)
  expect(Object.keys(marks).sort()).toEqual(['A', 'C'])
  syncLive(io, 0)
  await env.advance(0)
  resetRemoteState()
  resetRefreshState()
})

test('S13: polling marks nothing (no SSE, no updateRemote call)', async () => {
  resetRefreshState()
  resetLiveState()
  resetRemoteState()
  const env = fake()
  let calls = 0
  const io: GraphIo = { ...env.io, updateRemote: async () => void calls++ }
  syncLive(io, 1)
  await env.advance(0)
  expect(env.state.status.liveSource).toBe('poll')
  await env.advance(31_000)
  expect(calls).toBe(0)
  syncLive(io, 0)
  resetRefreshState()
})

test('S13: a successful local write clears the marks of its items and cascades; an errored one does not', async ($, on) => {
  serveHooks(on)
  const st = stateRig(on, { graphRemote: { A: 1, P: 2, Q: 3 } })
  const clock = mock.clock(on)
  let fail = false
  on('tool.call', async () => (fail ? { ...ok, isError: true } : { ref: 'r', result: {}, text: JSON.stringify({ results: [{ itemId: 'A', applied: true, cascadeEvents: [{ itemId: 'P' }] }] }) }) as never)
  fail = true
  await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: 'Q', trigger: 'start' }] } as never)
  await clock.settle()
  expect(st.valueOf('graphRemote')).toEqual({ A: 1, P: 2, Q: 3 })
  fail = false
  await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: 'A', trigger: 'complete' }] } as never)
  await clock.settle()
  expect(st.valueOf('graphRemote')).toEqual({ Q: 3 })
  // Nothing marked among the touched ids: no write at all.
  const writes = st.sets.filter(k => k === 'graphRemote').length
  await $.tool.call({ tool: `${TO}manage_notes`, operation: 'upsert', notes: [{ itemId: 'Z', key: 'k', role: 'work' }] } as never)
  await clock.settle()
  expect(st.sets.filter(k => k === 'graphRemote')).toHaveLength(writes)
})

// -- 087c6077: actor-based echo attribution and cross-session follow from SSE --
// Oracles (frozen in the item's test-plan, never the code): [AR] api-rest.md section 21 'Actor and rootId';
// [TS] the task-scope A/B clauses. Every 'remote' or 'local' assertion is paired with its opposite on the same fixture.
import { setRemoteAdvanceListener, remoteAdvanceListener } from '../src/graph-data/live.ts'
import { LOCAL_ACTOR_CAP, REMOTE_FOLLOW_DEBOUNCE_MS, REMOTE_FOLLOW_QUIET_MS, actorIdsOf, attribute, localActorIds, rememberLocalActors, remoteFollowCandidate } from '../src/graph-data/remote.ts'

const IDLE = { inFlight: 0, lastEnd: null as number | null }

// -- pure: actorIdsOf (S6) --

test('S6 (087c6077): actorIdsOf reads the top-level actor then every object element of every top-level array, deduped in first-seen order', () => {
  expect(actorIdsOf({ actor: { id: 'a' }, notes: [{ itemId: 'x', actor: { id: 'b' } }, { actor: { id: 'a' } }], items: [{ title: 't', actor: { id: 'c' } }] })).toEqual(['a', 'b', 'c'])
  // An element without an itemId (a create element) still counts.
  expect(actorIdsOf({ items: [{ title: 'new', actor: { id: 'creator' } }] })).toEqual(['creator'])
  expect(actorIdsOf({ transitions: [{ itemId: 'x', actor: { id: 'first' } }], claims: [{ actor: { id: 'z' } }] })).toEqual(['first', 'z'])
})

test('S6 (087c6077): actorIdsOf ignores garbage, non-string and empty ids, non-object elements, and nested non-top-level arrays', () => {
  for (const bad of [null, undefined, 'x', 5, true, [], {}, { actor: null }, { actor: 'str' }, { actor: {} }]) expect(actorIdsOf(bad)).toEqual([])
  expect(actorIdsOf({ actor: { id: '' }, notes: [{ actor: { id: 5 } }, 'str', null, 7, { actor: { id: '' } }, { actor: { id: 'z' } }] })).toEqual(['z'])
  // Only top-level arrays are scanned.
  expect(actorIdsOf({ wrapper: { inner: [{ actor: { id: 'q' } }] } })).toEqual([])
  // Control on the same shape: the same array one level up is read.
  expect(actorIdsOf({ inner: [{ actor: { id: 'q' } }] })).toEqual(['q'])
})

// -- pure: the local actor set (S7) --

test('S7 (087c6077): the local actor set caps at LOCAL_ACTOR_CAP, the oldest dropped; resetRemoteState clears it', () => {
  resetRemoteState()
  expect(LOCAL_ACTOR_CAP).toBe(200)
  rememberLocalActors(Array.from({ length: 200 }, (_, i) => `a${i}`))
  expect(localActorIds()).toHaveLength(200)
  expect(localActorIds()).toContain('a0')
  rememberLocalActors(['a200'])
  const ids = localActorIds()
  expect(ids).toHaveLength(200)
  expect(ids).not.toContain('a0')
  expect(ids).toContain('a1')
  expect(ids).toContain('a200')
  // 201 distinct ids in one call: the first goes, the 200 newest stay.
  resetRemoteState()
  rememberLocalActors(Array.from({ length: 201 }, (_, i) => `b${i}`))
  expect(localActorIds()).toHaveLength(200)
  expect(localActorIds()).not.toContain('b0')
  expect(localActorIds()).toContain('b200')
  resetRemoteState()
  expect(localActorIds()).toEqual([])
})

test('S7 (087c6077): remembering an id twice keeps it once', () => {
  resetRemoteState()
  rememberLocalActors(['me:1', 'x'])
  rememberLocalActors(['me:1'])
  expect(localActorIds().filter(id => id === 'me:1')).toHaveLength(1)
  expect(localActorIds().sort()).toEqual(['me:1', 'x'])
  resetRemoteState()
})

// -- pure: attribute (S3, S4, S5) --

test('S3 (087c6077): attribute by actor: own id or own parent is local, any other actor is remote, whatever the window says', () => {
  const busy = { inFlight: 1, lastEnd: 100 }
  expect(attribute({ actor: { id: 'me:1' } }, IDLE, ['me:1'], 50_000)).toEqual({ origin: 'local', by: 'actor' })
  expect(attribute({ actor: { id: 'sub:2', parent: 'me:1' } }, IDLE, ['me:1'], 50_000)).toEqual({ origin: 'local', by: 'actor' })
  // Opposites on the same set: a stranger, and a stranger whose parent is also a stranger.
  expect(attribute({ actor: { id: 'other:1' } }, IDLE, ['me:1'], 50_000)).toEqual({ origin: 'remote', by: 'actor' })
  expect(attribute({ actor: { id: 'sub:2', parent: 'other:1' } }, IDLE, ['me:1'], 50_000)).toEqual({ origin: 'remote', by: 'actor' })
  // The window is not consulted when an actor is present: a write in flight cannot make a stranger local.
  expect(attribute({ actor: { id: 'other:1' } }, busy, ['me:1'], 101)).toEqual({ origin: 'remote', by: 'actor' })
  // ... and an idle window cannot make our own actor remote.
  expect(attribute({ actor: { id: 'me:1' } }, IDLE, ['me:1'], 999_999)).toEqual({ origin: 'local', by: 'actor' })
  // Ids are exact: case differs, so remote.
  expect(attribute({ actor: { id: 'ME:1' } }, IDLE, ['me:1'], 1)).toEqual({ origin: 'remote', by: 'actor' })
})

test('S4 (087c6077): attribute without an actor falls back to the window: local up to LOCAL_ECHO_MS after a write ended, remote after', () => {
  const end = 100_000
  const w = { inFlight: 0, lastEnd: end }
  expect(attribute({ itemId: 'x' }, w, [], end + 4_999)).toEqual({ origin: 'local', by: 'window' })
  expect(attribute({ itemId: 'x' }, w, [], end + 6_000)).toEqual({ origin: 'remote', by: 'window' })
  expect(attribute({ itemId: 'x' }, { inFlight: 1, lastEnd: null }, [], end)).toEqual({ origin: 'local', by: 'window' })
  expect(attribute({ itemId: 'x' }, IDLE, [], end)).toEqual({ origin: 'remote', by: 'window' })
  // Our own actor ids in the set do not matter without an actor on the event.
  expect(attribute({ itemId: 'x' }, IDLE, ['me:1'], end)).toEqual({ origin: 'remote', by: 'window' })
})

test('S5 (087c6077): an empty, empty-id, non-string-id, null or non-object actor counts as absent (window rule); a real id does not', () => {
  const busy = { inFlight: 1, lastEnd: null as number | null }
  for (const actor of [{}, { id: '' }, { id: 5 }, null, 'str', { id: null }, []]) {
    expect(attribute({ itemId: 'x', actor }, IDLE, ['me:1'], 1)).toEqual({ origin: 'remote', by: 'window' })
    expect(attribute({ itemId: 'x', actor }, busy, ['me:1'], 1)).toEqual({ origin: 'local', by: 'window' })
  }
  for (const data of [undefined, null, 'x', 5, []]) expect(attribute(data, IDLE, [], 1).by).toBe('window')
  // Control: the same fixture with a real id is decided by actor, and the window is ignored.
  expect(attribute({ itemId: 'x', actor: { id: 'other:1' } }, busy, ['me:1'], 1)).toEqual({ origin: 'remote', by: 'actor' })
})

// -- pure: remoteFollowCandidate (S9, S10, S11) --

const REMOTE_ACTOR = { origin: 'remote', by: 'actor' } as const
const advanced = (over: Record<string, unknown> = {}) => ({ id: 1, event: 'item.advanced', itemId: 'X', actor: { id: 'other:1' }, rootId: ROOT, ...over })

test('S9 (087c6077): an item.advanced event attributed remote by actor, in this project, yields its itemId', () => {
  expect(remoteFollowCandidate('item.advanced', advanced(), REMOTE_ACTOR, ROOT)).toBe('X')
})

test('S10 (087c6077): not eligible: window attribution, local origin, another event name, missing/other rootId, no project root, bad rootId text, no itemId', () => {
  expect(remoteFollowCandidate('item.advanced', advanced(), REMOTE_ACTOR, ROOT)).toBe('X')
  expect(remoteFollowCandidate('item.advanced', advanced(), { origin: 'remote', by: 'window' }, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced(), { origin: 'local', by: 'actor' }, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced(), { origin: 'local', by: 'window' }, ROOT)).toBeUndefined()
  for (const name of ['item.updated', 'item.created', 'item.deleted', 'note.upserted', 'dependency.added']) expect(remoteFollowCandidate(name, advanced({ event: name }), REMOTE_ACTOR, ROOT)).toBeUndefined()
  const { rootId: _drop, ...noRoot } = advanced()
  expect(remoteFollowCandidate('item.advanced', noRoot, REMOTE_ACTOR, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: '00000000-0000-4000-8000-0000000000ff' }), REMOTE_ACTOR, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced(), REMOTE_ACTOR, null)).toBeUndefined()
  // Probes: whitespace around an otherwise matching rootId, a non-string rootId, an empty or absent itemId, no data.
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: ` ${ROOT} ` }), REMOTE_ACTOR, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: 5 }), REMOTE_ACTOR, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced({ itemId: '' }), REMOTE_ACTOR, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced({ itemId: undefined }), REMOTE_ACTOR, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', undefined, REMOTE_ACTOR, ROOT)).toBeUndefined()
})

test('S11 (087c6077): the rootId comparison is case-insensitive in both directions', () => {
  const upperRoot = 'ABCDEF01-0000-4000-8000-0000000000AA'
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: upperRoot.toLowerCase() }), REMOTE_ACTOR, upperRoot)).toBe('X')
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: upperRoot }), REMOTE_ACTOR, upperRoot.toLowerCase())).toBe('X')
  // Control: a different root in either case stays out.
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: upperRoot }), REMOTE_ACTOR, ROOT)).toBeUndefined()
})

// -- live rig: frames through the spawn script --

const frameOf = (event: string, itemId: string, extra: Record<string, unknown> = {}) => `event: ${event}\ndata: ${JSON.stringify({ id: 1, event, itemId, ...extra })}\n\n`
const remoteFrame = (itemId: string, over: Record<string, unknown> = {}) => frameOf('item.advanced', itemId, { actor: { id: 'other:1', kind: 'subagent' }, rootId: ROOT, ...over })

/** An SSE-connected live rig that records the marks and the follow-listener calls. */
async function sseLive() {
  resetRefreshState()
  resetLiveState()
  resetRemoteState()
  setRemoteAdvanceListener(null)
  const queue: string[] = []
  let wake: () => void = () => undefined
  const push = (t: string) => {
    queue.push(t)
    wake()
  }
  const env = fake({
    env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' },
    spawn: async function* () {
      for (;;) {
        while (queue.length === 0) await new Promise<void>(resolve => (wake = resolve))
        yield { stream: 'stdout' as const, text: queue.shift() as string }
      }
    },
  })
  env.state.scope = 'root'
  let marks: Record<string, number> = {}
  const io: GraphIo = {
    ...env.io,
    updateRemote: async step => {
      marks = step(marks)
    },
  }
  const followed: string[] = []
  setRemoteAdvanceListener(id => void followed.push(id))
  syncLive(io, 1)
  await env.advance(0)
  const done = async () => {
    syncLive(io, 0)
    await env.advance(0)
    setRemoteAdvanceListener(null)
    resetRemoteState()
    resetRefreshState()
    resetLiveState()
  }

  return { env, io, push, marks: () => marks, followed, done }
}

test('S1 (087c6077): an item.advanced frame with another actor, pushed INSIDE a local write, is marked remote; an actorless frame in the same window is our echo', async () => {
  const rig = await sseLive()
  await withLocalWrite(async () => {
    rig.push(remoteFrame('X'))
    await rig.env.advance(0)
    rig.push(frameOf('item.advanced', 'Y'))
    await rig.env.advance(0)
  }, () => rig.io.now())
  expect(Object.keys(rig.marks())).toEqual(['X'])
  await rig.done()
})

test('S2 (087c6077): a frame whose actor is one of ours is not marked 10s after a write; a stranger and an actorless frame at the same moment are', async () => {
  const rig = await sseLive()
  rememberLocalActors(['me:1'])
  await withLocalWrite(async () => undefined, () => rig.io.now())
  await rig.env.advance(10_000)
  rig.push(remoteFrame('mine', { actor: { id: 'me:1' } }))
  await rig.env.advance(0)
  expect(rig.marks().mine).toBeUndefined()
  rig.push(remoteFrame('theirs', { actor: { id: 'other:1' } }))
  rig.push(frameOf('item.updated', 'plain'))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks()).sort()).toEqual(['plain', 'theirs'])
  // A subagent of ours (parent is ours) is also not marked.
  rig.push(frameOf('item.updated', 'child', { actor: { id: 'sub:2', parent: 'me:1' } }))
  await rig.env.advance(0)
  expect(rig.marks().child).toBeUndefined()
  await rig.done()
})

test('S4 (087c6077): an actorless frame 6s after a write is marked; 4s after it is not (window fallback kept)', async () => {
  const rig = await sseLive()
  await withLocalWrite(async () => undefined, () => rig.io.now())
  await rig.env.advance(4_000)
  rig.push(frameOf('item.updated', 'early'))
  await rig.env.advance(0)
  expect(rig.marks().early).toBeUndefined()
  await rig.env.advance(2_000)
  rig.push(frameOf('item.updated', 'late'))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks())).toEqual(['late'])
  await rig.done()
})

test('probe (087c6077): actor ids are exact (ME:1 is remote when me:1 is ours); a duplicate frame marks once', async () => {
  const rig = await sseLive()
  rememberLocalActors(['me:1'])
  rig.push(remoteFrame('lower', { actor: { id: 'me:1' } }))
  rig.push(remoteFrame('upper', { actor: { id: 'ME:1' } }))
  rig.push(remoteFrame('upper', { actor: { id: 'ME:1' } }))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks())).toEqual(['upper'])
  await rig.done()
})

test('probe (087c6077): a replayed frame after sync.lost carrying an actor is attributed the same way', async () => {
  const rig = await sseLive()
  rememberLocalActors(['me:1'])
  rig.push('event: sync.lost\ndata: {"id":9,"event":"sync.lost","reason":"queue_overflow"}\n\n')
  await rig.env.advance(0)
  rig.push(remoteFrame('replay-theirs'))
  rig.push(remoteFrame('replay-mine', { actor: { id: 'me:1' } }))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks())).toEqual(['replay-theirs'])
  await rig.done()
})

// -- live rig: the follow listener (S12, S13, S14, S10 live) --

test('S12 (087c6077): three eligible frames 1s apart give no listener call until 3s after the last, then exactly one with the last itemId', async () => {
  const rig = await sseLive()
  expect(REMOTE_FOLLOW_DEBOUNCE_MS).toBe(3_000)
  rig.push(remoteFrame('A'))
  await rig.env.advance(1_000)
  rig.push(remoteFrame('B'))
  await rig.env.advance(1_000)
  rig.push(remoteFrame('C'))
  await rig.env.advance(2_999)
  expect(rig.followed).toEqual([])
  await rig.env.advance(1)
  expect(rig.followed).toEqual(['C'])
  // It fires once only.
  await rig.env.advance(10_000)
  expect(rig.followed).toEqual(['C'])
  await rig.done()
})

test('S10 (087c6077) live: window-remote, local, other-root, rootId-less and non-advance frames never reach the listener; a real remote advance does', async () => {
  const rig = await sseLive()
  rememberLocalActors(['me:1'])
  rig.push(frameOf('item.advanced', 'win', { rootId: ROOT }))
  rig.push(remoteFrame('mine', { actor: { id: 'me:1' } }))
  rig.push(remoteFrame('otherroot', { rootId: '00000000-0000-4000-8000-0000000000ff' }))
  rig.push(remoteFrame('noroot', { rootId: undefined }))
  rig.push(frameOf('item.updated', 'upd', { actor: { id: 'other:1' }, rootId: ROOT }))
  rig.push(frameOf('note.upserted', 'note', { actor: { id: 'other:1' }, rootId: ROOT }))
  await rig.env.advance(10_000)
  expect(rig.followed).toEqual([])
  // Control: these frames did arrive (the stream is alive: the stranger ones are marked), and a real one follows.
  expect(Object.keys(rig.marks()).sort()).toEqual(['noroot', 'note', 'otherroot', 'upd', 'win'])
  rig.push(remoteFrame('real'))
  await rig.env.advance(3_000)
  expect(rig.followed).toEqual(['real'])
  await rig.done()
})

test('S11 (087c6077) live: an upper-case rootId on the frame still follows', async () => {
  const rig = await sseLive()
  rig.push(remoteFrame('U', { rootId: ROOT.toUpperCase() }))
  await rig.env.advance(3_000)
  expect(rig.followed).toEqual(['U'])
  await rig.done()
})

test('S13 (087c6077): stopping live (syncLive 0, restartLive, resetLiveState) before the debounce fires cancels the pending call; an uninterrupted one fires', async () => {
  // Control: uninterrupted.
  let rig = await sseLive()
  rig.push(remoteFrame('A'))
  await rig.env.advance(0)
  await rig.env.advance(3_000)
  expect(rig.followed).toEqual(['A'])
  await rig.done()
  // syncLive 0.
  rig = await sseLive()
  rig.push(remoteFrame('A'))
  await rig.env.advance(1_000)
  syncLive(rig.io, 0)
  await rig.env.advance(5_000)
  expect(rig.followed).toEqual([])
  await rig.done()
  // restartLive.
  rig = await sseLive()
  rig.push(remoteFrame('A'))
  await rig.env.advance(1_000)
  restartLive(rig.io, 1)
  await rig.env.advance(5_000)
  expect(rig.followed).toEqual([])
  await rig.done()
  // resetLiveState.
  rig = await sseLive()
  rig.push(remoteFrame('A'))
  await rig.env.advance(1_000)
  resetLiveState()
  await rig.env.advance(5_000)
  expect(rig.followed).toEqual([])
  await rig.done()
})

test('S14 (087c6077): own activity wins: a write that ended under 30s before the fire drops it, 30s or more delivers it, one still in flight drops it', async () => {
  expect(REMOTE_FOLLOW_QUIET_MS).toBe(30_000)
  const cases: [string, number, number][] = [
    ['ended 10s before the fire', 7_000, 0],
    ['ended 29.999s before the fire', 26_999, 0],
    ['ended exactly 30s before the fire', 27_000, 1],
    ['ended 31s before the fire', 28_000, 1],
  ]
  for (const [label, delay, expected] of cases) {
    const rig = await sseLive()
    await withLocalWrite(async () => undefined, () => rig.io.now())
    await rig.env.advance(delay)
    rig.push(remoteFrame('F'))
    await rig.env.advance(0)
    await rig.env.advance(3_000)
    expect([label, rig.followed.length]).toEqual([label, expected])
    await rig.done()
  }
  // In flight at fire time.
  const rig = await sseLive()
  let release: () => void = () => undefined
  const held = withLocalWrite(() => new Promise<void>(resolve => (release = resolve)), () => rig.io.now())
  rig.push(remoteFrame('F'))
  await rig.env.advance(0)
  await rig.env.advance(3_000)
  expect(rig.followed).toEqual([])
  release()
  await held
  await rig.done()
})

test('probe (087c6077): a listener that throws does not kill the stream; the next frame still marks', async () => {
  const rig = await sseLive()
  setRemoteAdvanceListener(() => {
    throw new Error('listener boom')
  })
  expect(remoteAdvanceListener()).not.toBeNull()
  rig.push(remoteFrame('A'))
  await rig.env.advance(3_000)
  rig.push(remoteFrame('B'))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks()).sort()).toEqual(['A', 'B'])
  await rig.done()
})

test('087c6077: setRemoteAdvanceListener stores and clears the listener', () => {
  const fn = (_id: string) => undefined
  setRemoteAdvanceListener(fn)
  expect(remoteAdvanceListener()).toBe(fn)
  setRemoteAdvanceListener(null)
  expect(remoteAdvanceListener()).toBeNull()
})

// -- loaded plugin: the tool.call hook records actors before next() (S8) --

// The loaded plugin has its own module instance (a fresh one per test), so the actor set is observed through
// its effect: the SSE stream is the test's process.spawn handler, the marks land in $.state graphRemote.
// With an actor on the frame the window is not consulted, so a frame carrying one of our actors can only stay
// unmarked if that actor was recorded; pushed from inside the write it can only stay unmarked if it was
// recorded BEFORE next(e) (task-scope A: an echo can arrive before the call returns).
function loadedSse(on: On) {
  serveHooks(on)
  const st = stateRig(on, { graphSubscribers: 1, graphPaneOpen: true, bandSubscribed: true, graphStatus: { refreshing: false, liveSource: 'sse' } })
  mock.env(on, { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' })
  const clock = mock.clock(on, { now: 1_000_000 })
  const queue: string[] = []
  let wake: () => void = () => undefined
  const push = (t: string) => {
    queue.push(t)
    wake()
  }
  on('process.spawn', async function* () {
    for (;;) {
      while (queue.length === 0) await new Promise<void>(resolve => (wake = resolve))
      yield { stream: 'stdout' as const, text: queue.shift() as string }
    }
  } as never)
  on('session.start', async (_$, e) => ({ cwd: e.cwd }) as never)
  on('ui.open', async () => ({ value: { isPlaced: true } }) as never)
  on('ui.close', async () => ({ value: undefined }) as never)
  on('ui.status', async () => ({ value: undefined }) as never)
  on('ui.toast', async () => ({ value: undefined }) as never)
  on('command.register', async () => ({ value: undefined }) as never)

  return { st, clock, push }
}
const SSE_TIME = { timeoutMs: 30_000 } as never
const settleBy = async (clock: ReturnType<typeof mock.clock>, ms: number) => {
  await clock.advance(ms)
  await clock.settle()
}

test('S8 (087c6077): an element actor with no itemId (a create) is recorded as ours: its later frame is not marked, a stranger frame at the same moment is', SSE_TIME, async ($, on) => {
  const { st, clock, push } = loadedSse(on)
  on('tool.call', async () => ok as never)
  await $.session.start({ cwd: '/work/project', surface: 'terminal', isInteractive: true } as never)
  await settleBy(clock, 1_000)
  await $.tool.call({ tool: `${TO}manage_items`, operation: 'create', items: [{ title: 'new', actor: { id: 'creator:c' } }], actor: { id: 'orch:o', kind: 'orchestrator' } } as never)
  // Well past the 5s window: only the actor can still make these local.
  await settleBy(clock, 20_000)
  push(frameOf('item.updated', 'mine-element', { actor: { id: 'creator:c' }, rootId: ROOT }))
  push(frameOf('item.updated', 'mine-top', { actor: { id: 'orch:o' }, rootId: ROOT }))
  push(frameOf('item.updated', 'theirs', { actor: { id: 'other:1' }, rootId: ROOT }))
  await settleBy(clock, 1_000)
  expect(Object.keys((st.valueOf('graphRemote') ?? {}) as object).sort()).toEqual(['theirs'])
})

test('S8 (087c6077): the actors are recorded BEFORE next(e): a frame carrying our actor that arrives while the write is still running is not marked', SSE_TIME, async ($, on) => {
  const { st, clock, push } = loadedSse(on)
  on('tool.call', async () => {
    push(frameOf('item.updated', 'echo', { actor: { id: 'creator:c' }, rootId: ROOT }))
    push(frameOf('item.updated', 'stranger', { actor: { id: 'other:1' }, rootId: ROOT }))
    await settleBy(clock, 500)

    return ok as never
  })
  await $.session.start({ cwd: '/work/project', surface: 'terminal', isInteractive: true } as never)
  await settleBy(clock, 1_000)
  await $.tool.call({ tool: `${TO}manage_items`, operation: 'create', items: [{ title: 'new', actor: { id: 'creator:c' } }] } as never)
  await settleBy(clock, 1_000)
  expect(Object.keys((st.valueOf('graphRemote') ?? {}) as object).sort()).toEqual(['stranger'])
})

test('S8 (087c6077): a read tool records no actor: its actor is a stranger afterwards', SSE_TIME, async ($, on) => {
  const { st, clock, push } = loadedSse(on)
  on('tool.call', async () => ok as never)
  await $.session.start({ cwd: '/work/project', surface: 'terminal', isInteractive: true } as never)
  await settleBy(clock, 1_000)
  await $.tool.call({ tool: `${TO}query_items`, operation: 'get', itemId: 'x', actor: { id: 'reader:r' } } as never)
  await settleBy(clock, 20_000)
  push(frameOf('item.updated', 'by-reader', { actor: { id: 'reader:r' }, rootId: ROOT }))
  await settleBy(clock, 1_000)
  expect(Object.keys((st.valueOf('graphRemote') ?? {}) as object)).toEqual(['by-reader'])
})
