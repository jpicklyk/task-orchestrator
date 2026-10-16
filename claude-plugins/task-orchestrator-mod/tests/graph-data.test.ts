// Tests of the graph data layer (T2 32492bfa), moved to the 4.0 tool surface (item 5dbd8275).
//
// The engine's test `$` has no clock/state/fs/mcp nouns, so there are two kinds of test:
//  - unit tests run the modules in the test's own realm over `fake()`, an in-memory GraphIo (TO server
//    world, fs, env, a manual clock, scripted `process.spawn`, the status/snapshot/scope state);
//  - hook tests drive the loaded plugin through `$.tool.call`, with the TO server on `mcp.call`
//    and time on mock.clock.
//
// The fake TO server answers the 4.0 reads (items_read list/get/overview/context/history, deps_read list)
// as response envelopes in `structuredContent` (current/docs/api-reference.md "Response envelope", "items_read",
// "deps_read", "List data"). Compact views carry 8-hex short ids, full views, dependency edges and history rows
// carry full UUIDs; the registry below gives every fixture name a UUID whose first 8 hex are unique.
import type { On } from 'claude-code'
import { expect, mock, test } from 'claude-code/testing'

import { WRITE_TOOLS, WRITE_TOOL_NAME, shouldRefresh } from '../src/graph-data/index.ts'
import type { GraphIo } from '../src/graph-data/io.ts'
import { invalidateLabels, resetLabelState } from '../src/graph-data/labels.ts'
import { SSE_TYPES, createSseParser, curlRequest, eventsUrl, isLoopbackApiUrl, resetLiveState, resolveApiUrl, restartLive, syncLive } from '../src/graph-data/live.ts'
import { REFRESH_TIMEOUT_MS, refresh, refreshNow, resetRefreshState, sameSnapshot, sameStatus } from '../src/graph-data/refresh.ts'
import { NODE_CAP, STALL_MS, TRANSITION_LIMIT, snapshot, stallsOf, trailOf } from '../src/graph-data/snapshot.ts'
import { MAX_TOASTS, capToasts, gateToastLines, readyIds, snapshotEvents, toastLines } from '../src/graph-data/events.ts'
import { LOCAL_ECHO_MS, REMOTE_CAP, clearRemote, isLocalEcho, localWindow, markRemote, resetRemoteState, resultItemIds, withLocalWrite } from '../src/graph-data/remote.ts'
import { parseToResult, shortId } from '../src/shared/to-client.ts'

const ROOT = 'a0000000-0000-4000-8000-000000000001'
const TO = 'mcp__mcp-task-orchestrator__'
const CONFIG = `project:\n  rootId: ${ROOT}\n`

// --- fixture ids: a name is a UUID whose first 8 hex differ from every other name's ---------

const registry: Record<string, string> = {}
let counter = 0
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
/** The full UUID of a fixture name. */
const U = (name: string): string => {
  if (UUID_RE.test(name)) return name
  if (registry[name] === undefined) {
    counter++
    registry[name] = `${(0xb0000000 + counter).toString(16)}-0000-4000-8000-${counter.toString(16).padStart(12, '0')}`
  }

  return registry[name] as string
}
/** The 8-hex short id of a fixture name. */
const S = (name: string): string => U(name).slice(0, 8)
/** Whether an id argument (a UUID or a hex prefix of one) names the fixture. */
const names = (arg: unknown, name: string): boolean => typeof arg === 'string' && arg.length >= 8 && U(name).toLowerCase().startsWith(arg.toLowerCase())

type Item = { id: string; parentId?: string; title: string; role: string; depth: number; type?: string; statusLabel?: string; properties?: unknown; childCounts?: Record<string, number> }
type Dep = { id: string; from: string; to: string; type: 'blocks' | 'relates_to'; unblock?: string }
type World = {
  items: Item[]
  deps?: Dep[]
  outside?: Record<string, { title: string; role: string }>
  /** Item gate data (items_read context with an itemId), merged over the item's own compact view. */
  context?: Record<string, unknown>
  failDepsFor?: string[]
  failGetFor?: string[]
  /** The items_read context health view's stalled rows, and the history rows (newest first is the server's job). */
  stalled?: { id: string; role?: string; missing: string[] }[]
  history?: { itemId: string; at: number }[]
  failStall?: boolean
  failHistory?: boolean
}
type Call = { tool: string; args: Record<string, unknown> }
type McpResult = { content: { type: 'text'; text: string }[]; structuredContent: unknown; isError?: boolean }

const ok$ = (data: unknown): McpResult => ({ content: [{ type: 'text', text: 'ok' }], structuredContent: { ok: true, data, meta: { reqId: 'k7f3q9ab' } } })
const fail$ = (code: string, message: string): McpResult => ({
  isError: true,
  content: [{ type: 'text', text: `${code}: ${message}` }],
  structuredContent: { ok: false, error: { code, kind: 'permanent', message }, meta: { reqId: 'k7f3q9ab' } },
})

const ID_KEYS = ['ancestorId', 'anchorId', 'itemId', 'item', 'parentId', 'rootId']
/** A call's args with every id-valued field cut to its short id, so a full UUID or its prefix compare equal. */
const canon = (args: Record<string, unknown>): Record<string, unknown> =>
  Object.fromEntries(Object.entries(args).map(([k, v]) => [k, ID_KEYS.includes(k) && typeof v === 'string' && /^[0-9a-f-]{8,}$/i.test(v) ? v.slice(0, 8).toLowerCase() : v]))

const iso = (ms: number) => new Date(ms).toISOString()

/** The TO server, read tools only, answered from `world`. */
function answer(world: World, tool: string, args: Record<string, unknown>): McpResult {
  const find = (arg: unknown) => world.items.find(i => names(arg, i.id))
  const compact = (i: Item) => ({
    id: S(i.id),
    ...(i.parentId !== undefined ? { parentId: S(i.parentId) } : {}),
    ...(i.type !== undefined ? { type: i.type } : {}),
    title: i.title,
    role: i.role,
    ...(i.statusLabel !== undefined ? { statusLabel: i.statusLabel } : {}),
    priority: 'medium',
    depth: i.depth,
    version: 1,
  })
  const ancestorsOf = (i: Item): Item[] => {
    const parent = i.parentId === undefined ? undefined : world.items.find(p => p.id === i.parentId)

    return parent === undefined ? [] : [...ancestorsOf(parent), parent]
  }
  const pageOf = <T>(rows: T[]) => {
    const offset = args.cursor === undefined ? 0 : Number(String(args.cursor).slice(2))
    const limit = Number(args.limit ?? 50)
    const next = offset + limit < rows.length ? `c:${offset + limit}` : undefined

    return { items: rows.slice(offset, offset + limit), page: { total: rows.length, truncated: next !== undefined, ...(next !== undefined ? { next } : {}) } }
  }

  if (tool === 'items_read' && args.select === 'overview') {
    const anchor = find(args.anchorId)
    const rows = world.items.filter(i => i.parentId !== undefined && names(args.anchorId, i.parentId))

    return ok$({
      anchor: { id: S(anchor?.id ?? 'root'), title: anchor?.title ?? 'Project' },
      items: rows.map(i => ({ ...compact(i), ...(i.childCounts !== undefined ? { childCounts: i.childCounts } : {}) })),
      page: { total: rows.length, truncated: false },
    })
  }
  if (tool === 'items_read' && args.select === 'get') {
    const outside = Object.keys(world.outside ?? {}).find(n => names(args.itemId, n))
    const item = find(args.itemId)
    if (item !== undefined && world.failGetFor?.includes(item.id)) return fail$('internal', 'get boom')
    if (item === undefined && outside !== undefined) return ok$({ id: U(outside), ...(world.outside as Record<string, { title: string; role: string }>)[outside] })
    if (item === undefined) return fail$('not_found', 'no such item')
    const fields = (args.fields as string[] | undefined) ?? []

    return ok$({
      id: U(item.id),
      ...(item.parentId !== undefined ? { parentId: U(item.parentId) } : {}),
      rootId: ROOT,
      ...(item.type !== undefined ? { type: item.type } : {}),
      title: item.title,
      role: item.role,
      ...(item.statusLabel !== undefined ? { statusLabel: item.statusLabel } : {}),
      priority: 'medium',
      depth: item.depth,
      version: 1,
      ...(item.properties !== undefined ? { properties: item.properties } : {}),
      ...(fields.includes('ancestors') ? { ancestors: ancestorsOf(item).map(compact) } : {}),
      ...(fields.includes('createdAt') ? { createdAt: iso(1_000), modifiedAt: iso(2_000), roleChangedAt: iso(3_000) } : {}),
    })
  }
  if (tool === 'items_read' && args.select === 'list') {
    const scope = args.ancestorId
    const inTree = (i: Item): boolean => scope === undefined || names(scope, i.id) || (i.parentId !== undefined && inTree(world.items.find(p => p.id === i.parentId) as Item))
    const rows = world.items.filter(inTree).filter(i => args.type === undefined || i.type === args.type).filter(i => args.role === undefined || i.role === args.role)
    const p = pageOf(rows)

    return ok$({ items: p.items.map(compact), page: p.page })
  }
  if (tool === 'items_read' && args.select === 'context' && args.itemId !== undefined) {
    const item = find(args.itemId)
    if (item === undefined) return fail$('not_found', 'no such item')
    const { parentId: _drop, ...view } = compact(item)

    return ok$({ item: view, ...((world.context?.[item.id] as object | undefined) ?? {}) })
  }
  if (tool === 'items_read' && args.select === 'context') {
    if (world.failStall === true) return fail$('internal', 'stall boom')
    const empty = { items: [], page: { total: 0, truncated: false } }

    return ok$({
      active: empty,
      blocked: empty,
      stalled: (world.stalled ?? []).map(r => ({ id: S(r.id), title: r.id, role: r.role ?? 'work', missing: r.missing })),
      claimSummary: { active: 0, expired: 0, unclaimed: 0 },
    })
  }
  if (tool === 'items_read' && args.select === 'history') {
    if (world.failHistory === true) return fail$('internal', 'history boom')
    const rows = [...(world.history ?? [])].sort((a, b) => b.at - a.at).map((m, i) => ({ seq: 1_000_000_000_000 + i, id: `ev${i}`, itemId: U(m.itemId), occurredAt: iso(m.at), trigger: 'start', fromRole: 'queue', toRole: 'work' }))
    const p = pageOf(rows)

    return ok$({ items: p.items, page: p.page })
  }
  if (tool === 'deps_read' && args.select === 'list') {
    const target = world.items.find(i => names(args.item, i.id))?.id ?? Object.keys(world.outside ?? {}).find(n => names(args.item, n)) ?? ''
    if (world.failDepsFor?.includes(target)) return fail$('internal', 'boom')
    const rows = (world.deps ?? []).filter(d => d.from === target || d.to === target)
    const p = pageOf(rows.map(d => ({ id: d.id, from: U(d.from), to: U(d.to), type: d.type, ...(d.unblock !== undefined ? { unblockAt: d.unblock } : {}) })))

    return ok$({ items: p.items, page: p.page })
  }

  return fail$('invalid_request', `unexpected ${tool}`)
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
      calls.push({ tool, args: canon(args) })

      return parseToResult(tool, answer(world, tool, args))
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
  const searches = () => calls.filter(c => c.tool === 'items_read' && c.args.select === 'list' && c.args.cursor === undefined).length
  const gets = () => calls.filter(c => c.tool === 'items_read' && c.args.select === 'get' && !((c.args.fields as string[] | undefined) ?? []).includes('ancestors')).length

  return { io, calls, advance, searches, gets, state }
}

// --- snapshot -----------------------------------------------------------------------------

test('a 3-level tree gives the right nodes and parents', async () => {
  const s = await snapshot(fake().io, U('root'))
  expect(s.error).toBe(undefined)
  expect(s.truncated).toBe(false)
  expect(s.scopeId).toBe(S('root'))
  expect(s.rootId).toBe(S(ROOT))
  expect(s.nodes.map(n => [n.id, n.parentId, n.depth])).toEqual([
    [S('root'), null, 0],
    [S('feat'), S('root'), 1],
    [S('t-queue'), S('feat'), 2],
    [S('t-work'), S('feat'), 2],
    [S('t-review'), S('feat'), 2],
    [S('sub'), S('t-work'), 3],
  ])
  expect(s.nodes[3]).toEqual({ id: S('t-work'), parentId: S('feat'), title: 'Working task', role: 'work', statusLabel: 'in-progress', priority: 'medium', depth: 2 })
})

test('a sub-scope keeps its top node parentless', async () => {
  const s = await snapshot(fake().io, U('t-work'))
  expect(s.nodes.map(n => [n.id, n.parentId])).toEqual([
    [S('t-work'), null],
    [S('sub'), S('t-work')],
  ])
})

test('a scope given as its 8-hex short id works the same as the UUID', async () => {
  const s = await snapshot(fake().io, S('t-work'))
  expect(s.scopeId).toBe(S('t-work'))
  expect(s.nodes.map(n => n.id)).toEqual([S('t-work'), S('sub')])
})

test('a null scope resolves to the project root; no rootId gives an empty snapshot with an error', async () => {
  const withRoot = fake()
  const s = await snapshot(withRoot.io, null)
  expect(s.scopeId).toBe(S(ROOT))
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
    deps: [{ id: 'd1', from: 'cont-features', to: 'cont-bugs', type: 'blocks' }],
  }
}

test('overview: the project root is ONE items_read overview call; children carry roll-ups, nothing else is read', async () => {
  for (const scope of [null, ROOT]) {
    const env = fake({ world: projectWorld() })
    const s = await snapshot(env.io, scope)
    expect(env.calls).toEqual([{ tool: 'items_read', args: { select: 'overview', anchorId: S(ROOT), limit: 100 } }])
    expect(s.overview).toBe(true)
    expect(s.scopeId).toBe(S(ROOT))
    expect(s.error).toBe(undefined)
    expect(s.nodes.map(n => [n.id, n.parentId, n.depth, n.title])).toEqual([
      [S(ROOT), null, 0, 'TaskOrchestrator'],
      [S('cont-features'), S(ROOT), 1, 'Features'],
      [S('cont-bugs'), S(ROOT), 1, 'Bugs'],
      [S('leaf'), S(ROOT), 1, 'Leaf'],
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
    if (args.select === 'overview') throw new Error('overview boom')

    return original(tool, args)
  }
  const bad = await snapshot(env.io, null)
  expect(bad.error).toBe('overview boom')
  expect(bad.nodes).toEqual([])
  const sub = await snapshot(fake({ world: projectWorld() }).io, U('cont-features'))
  expect(sub.overview).toBe(undefined)
  expect(sub.nodes.map(n => n.id)).toEqual([S('cont-features'), S('deep')])
})

test('subtrees page in 100s following page.next, and 151 items truncate to the 150 shallowest', async () => {
  const items: Item[] = [{ id: 'root', title: 'Root', role: 'queue', depth: 0 }]
  for (let i = 0; i < 25; i++) items.push({ id: `a${i}`, parentId: 'root', title: `A${i}`, role: 'queue', depth: 1 })
  for (let i = 0; i < 124; i++) items.push({ id: `b${i}`, parentId: 'a0', title: `B${i}`, role: 'queue', depth: 2 })
  items.push({ id: 'deep', parentId: 'b0', title: 'Deep', role: 'queue', depth: 3 })
  expect(items).toHaveLength(NODE_CAP + 1)
  const env = fake({ world: { items } })
  const s = await snapshot(env.io, U('root'))
  const lists = env.calls.filter(c => c.tool === 'items_read' && c.args.select === 'list')
  expect(lists.map(c => c.args.cursor)).toEqual([undefined, 'c:100'])
  expect(lists.every(c => c.args.limit === 100 && c.args.ancestorId === S('root'))).toBe(true)
  expect(s.truncated).toBe(true)
  expect(s.nodes).toHaveLength(NODE_CAP)
  expect(s.nodes.some(n => n.id === S('deep'))).toBe(false)
})

test('120 items span two pages without truncating', async () => {
  const items: Item[] = [{ id: 'root', title: 'Root', role: 'queue', depth: 0 }]
  for (let i = 0; i < 119; i++) items.push({ id: `c${i}`, parentId: 'root', title: `C${i}`, role: 'queue', depth: 1 })
  const env = fake({ world: { items } })
  const s = await snapshot(env.io, U('root'))
  expect(env.calls.filter(c => c.tool === 'items_read' && c.args.select === 'list')).toHaveLength(2)
  expect(s.nodes).toHaveLength(120)
  expect(s.truncated).toBe(false)
})

test('edges: blocks and relates_to map to BLOCKS and RELATES_TO, outside ends go to external, the same edge listed from both ends is kept once', async () => {
  const world = threeLevels()
  world.outside = { ext: { title: 'Elsewhere', role: 'work' } }
  world.deps = [
    { id: 'd1', from: 't-queue', to: 't-work', type: 'blocks', unblock: 'terminal' },
    { id: 'd3', from: 't-work', to: 't-review', type: 'blocks' },
    { id: 'd4', from: 'ext', to: 't-queue', type: 'blocks' },
    { id: 'd5', from: 'sub', to: 'ext', type: 'relates_to' },
    // neither end in the subtree: dropped
    { id: 'd6', from: 'ext', to: 'ext2', type: 'blocks' },
  ]
  const s = await snapshot(fake({ world }).io, U('root'))
  expect(s.edges).toHaveLength(4)
  expect(s.edges).toContainEqual({ from: S('t-queue'), to: S('t-work'), type: 'BLOCKS', unblockAt: 'terminal' })
  expect(s.edges).toContainEqual({ from: S('t-work'), to: S('t-review'), type: 'BLOCKS' })
  expect(s.edges).toContainEqual({ from: S('ext'), to: S('t-queue'), type: 'BLOCKS' })
  expect(s.edges).toContainEqual({ from: S('sub'), to: S('ext'), type: 'RELATES_TO' })
  expect(s.external).toEqual({ [S('ext')]: { title: 'Elsewhere', role: 'work' } })
})

test('a dependency listed from both ends (same id) is kept once', async () => {
  const world = threeLevels()
  world.deps = [{ id: 'd1', from: 't-queue', to: 't-work', type: 'blocks' }]
  const s = await snapshot(fake({ world }).io, U('root'))
  // t-queue and t-work both report d1
  expect(s.edges).toEqual([{ from: S('t-queue'), to: S('t-work'), type: 'BLOCKS' }])
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
  const s = await snapshot(env.io, U('root'))
  // Per-item gate reads (select context with an itemId); the stall read names an ancestorId instead.
  expect(env.calls.filter(c => c.tool === 'items_read' && c.args.select === 'context' && c.args.itemId !== undefined).map(c => c.args.itemId).sort()).toEqual([S('feat'), S('root'), S('t-review'), S('t-work')].sort())
  expect(s.gates[S('t-work')]).toEqual({ canAdvance: false, phase: 'work', missing: ['session-tracking'], required: 3, filled: 1 })
  expect(s.gates[S('t-review')]).toEqual({ canAdvance: true, phase: 'review', missing: [], required: 1, filled: 1 })
  expect(s.gates[S('t-queue')]).toBe(undefined)
  expect(s.gates[S('sub')]).toBe(undefined)
})

test('one failing deps_read gives a partial snapshot and no throw', async () => {
  const world = threeLevels()
  world.failDepsFor = ['t-queue']
  world.deps = [{ id: 'd1', from: 't-work', to: 't-review', type: 'blocks' }]
  const s = await snapshot(fake({ world }).io, U('root'))
  expect(s.nodes).toHaveLength(6)
  expect(s.edges).toEqual([{ from: S('t-work'), to: S('t-review'), type: 'BLOCKS' }])
  expect(s.error).toContain('deps_read: internal: boom')
})

test('a failing hierarchy call returns an empty snapshot with the error', async () => {
  const env = fake()
  env.io.callTool = async () => {
    throw new Error('items_read: server down')
  }
  const s = await snapshot(env.io, U('root'))
  expect(s.nodes).toEqual([])
  expect(s.error).toContain('server down')
})

test('snapshot never calls a write tool', async () => {
  const world = threeLevels()
  world.deps = [{ id: 'd1', from: 't-queue', to: 't-work', type: 'blocks' }]
  world.context = { 't-work': { gateStatus: { canAdvance: true, phase: 'work', missing: [] }, schema: [] } }
  const env = fake({ world })
  await snapshot(env.io, U('root'))
  expect(env.calls.length).toBeGreaterThan(0)
  for (const c of env.calls) expect(['items_read', 'deps_read']).toContain(c.tool)
})

// --- plan labels --------------------------------------------------------------------------

const withProps = (properties: unknown): World => {
  const w = threeLevels()
  ;(w.items.find(i => i.id === 't-work') as Item).properties = properties

  return w
}
const labelOf = async (properties: unknown) => {
  const s = await snapshot(fake({ world: withProps(properties) }).io, U('root'))

  return { label: s.nodes.find(n => n.id === S('t-work'))?.planLabel, error: s.error }
}

test('S1: properties.planLabel becomes node.planLabel (properties is an object in a 4.0 full view)', async () => {
  const r = await labelOf({ planLabel: 'T3' })
  expect(r.label).toBe('T3')
  expect(r.error).toBe(undefined)
})

test('S2: missing, wrong-typed, empty or too long labels give no label and no error', async () => {
  for (const bad of [undefined, { planLabel: 5 }, { planLabel: '' }, { planLabel: '   ' }, { planLabel: '1234567890123' }, null, { other: 1 }, 'not json', [1]]) {
    const r = await labelOf(bad)
    expect(r.label).toBe(undefined)
    expect(r.error).toBe(undefined)
  }
  expect((await labelOf({ planLabel: 'T9' })).label).toBe('T9')
  expect((await labelOf({ traits: ['x'], planLabel: '123456789012' })).label).toBe('123456789012')
})

test('S3: labels are fetched once per id; a second snapshot makes no get calls', async () => {
  const env = fake({ world: withProps({ planLabel: 'T3' }) })
  await snapshot(env.io, U('root'))
  expect(env.gets()).toBe(6)
  const again = await snapshot(env.io, U('root'))
  expect(env.gets()).toBe(6)
  expect(again.nodes.find(n => n.id === S('t-work'))?.planLabel).toBe('T3')
})

test('S4: invalidateLabels makes the next snapshot get again', async () => {
  const env = fake({ world: withProps({ planLabel: 'T3' }) })
  await snapshot(env.io, U('root'))
  invalidateLabels()
  await snapshot(env.io, U('root'))
  expect(env.gets()).toBe(12)
})

test('S5: a failed get gives no label and no error, and is retried next snapshot', async () => {
  const world = withProps({ planLabel: 'T3' })
  world.failGetFor = ['t-work']
  const env = fake({ world })
  const first = await snapshot(env.io, U('root'))
  expect(first.nodes.find(n => n.id === S('t-work'))?.planLabel).toBe(undefined)
  expect(first.error).toBe(undefined)
  expect(env.gets()).toBe(6)
  world.failGetFor = []
  const second = await snapshot(env.io, U('root'))
  expect(env.gets()).toBe(7)
  expect(second.nodes.find(n => n.id === S('t-work'))?.planLabel).toBe('T3')
})

test('S17: a snapshot only reads: items_read is list, get, context or history', async () => {
  const env = fake({ world: withProps({ planLabel: 'T3' }) })
  await snapshot(env.io, U('root'))
  for (const c of env.calls.filter(x => x.tool === 'items_read')) expect(['list', 'get', 'context', 'history']).toContain(c.args.select as string)
})

// --- seat progress ------------------------------------------------------------------------

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
  const s = await snapshot(fake({ world }).io, U('root'))
  expect(s.gates[S('t-work')]?.seats).toEqual([
    { seat: 'implementer', required: 2, filled: 1 },
    { seat: 'test-author', required: 1, filled: 0 },
    { seat: 'orchestrator', required: 1, filled: 1 },
    { seat: 'other', required: 1, filled: 0 },
  ])
})

test('S8: rows without a seat leave the seats key out', async () => {
  const world = threeLevels()
  world.context = { 't-work': { gateStatus: { canAdvance: true, phase: 'work', missing: [] }, schema: [{ key: 'a', role: 'work', required: true, filled: true }] } }
  const s = await snapshot(fake({ world }).io, U('root'))
  expect('seats' in (s.gates[S('t-work')] as object)).toBe(false)
})

// --- refresh ------------------------------------------------------------------------------

test('refresh: 5 rapid calls give one snapshot 300ms after the last; atoms are written', async () => {
  resetRefreshState()
  const env = fake()
  env.state.scope = U('root')
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
  env.state.scope = U('root')
  let release: () => void = () => undefined
  const gate = new Promise<void>(resolve => (release = resolve))
  const original = env.io.callTool
  let started = 0
  env.io.callTool = async (tool, args) => {
    if (tool === 'items_read' && args.select === 'list') {
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
    if (tool === 'items_read' && args.select === 'list') await gate

    return original(tool, args)
  }
  env.state.scope = U('cont-features')
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
  expect(env.state.snapshot?.scopeId).toBe(S(ROOT))
  // the abandoned run finishing late must not overwrite the newer snapshot
  release()
  await env.advance(1_000)
  expect(env.state.snapshot?.scopeId).toBe(S(ROOT))
})

test('a failed snapshot lands in graphStatus.lastError', async () => {
  resetRefreshState()
  const env = fake({ files: {} })
  const done = refresh(env.io)
  await env.advance(300)
  await done
  expect(env.state.status).toEqual({ refreshing: false, liveSource: 'none', lastError: 'no project.rootId' })
})

// --- live sources -------------------------------------------------------------------------

test('no API URL: polls every 15s while subscribed; unsubscribe stops it', async () => {
  resetRefreshState()
  resetLiveState()
  const env = fake()
  env.state.scope = U('root')
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
  env.state.scope = U('root')
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
  env.state.scope = U('root')
  syncLive(env.io, 1)
  await env.advance(0)
  expect(spawned).toHaveLength(1)
  const argvText = spawned[0]!.argv.join(' ')
  expect(argvText).toContain(`http://localhost:3001/api/v1/events?root=${ROOT}&types=item.created,item.updated`)
  expect(argvText).not.toContain('sekrit')
  expect(spawned[0]!.input).toContain('Authorization: Bearer sekrit')
  expect(env.state.status.liveSource).toBe('sse')
  push(': hello\n\nid: 1000000000001\nevent: item.upda')
  push('ted\ndata: {}\n\n')
  await env.advance(299)
  expect(env.searches()).toBe(0)
  await env.advance(1)
  expect(env.searches()).toBe(1)
  push('id: 1000000000002\nevent: sync.lost\ndata: {"reason":"queue_overflow"}\n\n')
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
  env.state.scope = U('root')
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
  env.state.scope = U('root')
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
    world: withProps({ planLabel: 'T3' }),
    env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' },
    spawn: async function* () {
      yield { stream: 'stdout' as const, text: 'id: 1000000000001\nevent: item.created\ndata: {}\n\n' }
      await gate
      yield { stream: 'stdout' as const, text: 'id: 1000000000002\nevent: item.updated\ndata: {}\n\n' }
      await new Promise<void>(() => undefined)

      return { code: 0, signal: null }
    },
  })
  env.state.scope = U('root')
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

// --- pure helpers -------------------------------------------------------------------------

test('createSseParser: frames split across chunks, comments and CRLF', () => {
  const seen: string[] = []
  const feed = createSseParser(n => seen.push(n))
  feed(': keepalive\n\nevent: item.cre')
  feed('ated\r\ndata: {}\r\n\r\nevent: sync.lost\ndata: {"reason":"x"}\n\n')
  feed('data: only data\n\n')
  expect(seen).toEqual(['item.created', 'sync.lost'])
})

const SSE_TYPE_LIST = [
  'item.created',
  'item.updated',
  'item.deleted',
  'item.advanced',
  'note.upserted',
  'note.deleted',
  'dependency.added',
  'dependency.removed',
  'scope.entered',
  'scope.left',
  'run.opened',
  'run.updated',
  'run.paused',
  'run.resumed',
  'run.ended',
  'run.marked',
  'seat.ended',
  'decision.raised',
  'decision.acknowledged',
  'decision.answered',
  'decision.withdrawn',
]

test('curlRequest keeps the token out of argv; eventsUrl spells the 4.0 types out', () => {
  const withToken = curlRequest('http://h/x', 'tok"en')
  expect(withToken.argv.join(' ')).not.toContain('tok')
  expect(withToken.input).toBe('header = "Authorization: Bearer tok\\"en"\n')
  expect(curlRequest('http://h/x', undefined).input).toBe(undefined)
  expect(eventsUrl('http://h', 'abc')).toBe(`http://h/api/v1/events?root=abc&types=${SSE_TYPE_LIST.join(',')}`)
  expect([...SSE_TYPES]).toEqual(SSE_TYPE_LIST)
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

// Oracle: task-scope AC7 (shouldRefresh = a graph write tool, not our own call, not denied; isError is NOT
// consulted, because a partially failed batch is isError and may have committed) and AC4 of the graph WRITE_TOOLS.
test('shouldRefresh: write tools only, not our own calls or denies; an error result still refreshes', () => {
  for (const name of ['advance', 'items_write', 'notes_write', 'deps_write', 'runs_write', 'config']) {
    expect(shouldRefresh(`${TO}${name}`, 'engine', {})).toBe(true)
  }
  for (const name of ['items_read', 'notes_read', 'deps_read', 'runs_read', 'docs_read', 'report']) {
    expect(shouldRefresh(`${TO}${name}`, 'engine', {})).toBe(false)
  }
  expect(shouldRefresh('Bash', 'engine', {})).toBe(false)
  expect(shouldRefresh(`${TO}advance`, 'task-orchestrator-mod', {})).toBe(false)
  expect(shouldRefresh(`${TO}advance`, 'engine', { deny: 'no' })).toBe(false)
  expect(shouldRefresh(`${TO}advance`, 'engine', { isError: true })).toBe(true)
  expect(shouldRefresh(`${TO}notes_write`, 'engine', { isError: true })).toBe(true)
})

test('the hook matcher and WRITE_TOOLS name the same tools', () => {
  expect([...WRITE_TOOLS].sort()).toEqual(['advance', 'config', 'deps_write', 'items_write', 'notes_write', 'runs_write'])
  for (const name of WRITE_TOOLS) expect(WRITE_TOOL_NAME.test(`${TO}${name}`)).toBe(true)
  for (const name of ['items_read', 'notes_read', 'deps_read', 'runs_read', 'docs_read', 'report']) expect(WRITE_TOOL_NAME.test(`${TO}${name}`)).toBe(false)
})

// --- the loaded plugin: tool.call hook ----------------------------------------------------

/** The TO server and project config on the test's `on`; returns the call log. */
function serveHooks(on: On, world: World = threeLevels()): Call[] {
  const calls: Call[] = []
  on('fs.read', async (_$, e) => {
    if (!e.path.split(String.fromCharCode(92)).join('/').endsWith('/.taskorchestrator/config.yaml')) throw new Error(`ENOENT ${e.path}`)

    return { value: CONFIG } as never
  })
  on('mcp.call', async (_$, e) => {
    calls.push({ tool: e.tool, args: canon(e.args) })

    return { value: answer(world, e.tool, e.args) } as never
  })

  return calls
}

const searchesOf = (calls: Call[]) => calls.filter(c => c.tool === 'items_read' && (c.args.select === 'overview' || (c.args.select === 'list' && c.args.cursor === undefined))).length
const getsOf = (calls: Call[]) => calls.filter(c => c.tool === 'items_read' && c.args.select === 'get' && !((c.args.fields as string[] | undefined) ?? []).includes('ancestors')).length
const ok = { ref: 'r', result: { ok: true }, text: 'ok' }

test('five rapid advance calls give one snapshot after 300ms; the result passes through', async ($, on) => {
  const calls = serveHooks(on)
  const clock = mock.clock(on)
  on('tool.call', async () => ok as never)
  const results: unknown[] = []
  for (let i = 0; i < 5; i++) {
    results.push(await $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId: 'abcdef12', trigger: 'start' }] } as never))
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
  for (const c of calls) expect(['items_read', 'deps_read']).toContain(c.tool)
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
    await $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId: 'abcdef12', trigger: 'start' }] } as never)
    await clock.advance(1_000)
    await clock.settle()
  }
  expect(writes.filter(k => k === 'graphSnapshot')).toHaveLength(1)
  expect(writes.filter(k => k === 'graphStatus').length).toBeLessThanOrEqual(1)
})

test('a read tool and a denied call do not refresh; an errored call (a partial failure may have written) does', async ($, on) => {
  const calls = serveHooks(on)
  const clock = mock.clock(on)
  on('tool.call', async (_$, e) => {
    if (e.tool.endsWith('notes_write')) return { deny: 'blocked' } as never
    if (e.tool.endsWith('items_write')) return { ...ok, isError: true } as never

    return ok as never
  })
  await $.tool.call({ tool: `${TO}items_read`, select: 'get', itemId: 'abcdef12' } as never)
  await $.tool.call({ tool: `${TO}notes_write`, ops: [{ op: 'upsert' }] } as never)
  await $.tool.call({ tool: 'Bash', command: 'ls' } as never)
  await clock.advance(2_000)
  await clock.settle()
  expect(calls).toHaveLength(0)
  await $.tool.call({ tool: `${TO}items_write`, ops: [{ op: 'create', title: 'T' }] } as never)
  await clock.advance(2_000)
  await clock.settle()
  expect(searchesOf(calls)).toBe(1)
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

test('S6: a successful items_write call re-reads plan labels on the next refresh; advance does not', async ($, on) => {
  // A feature scope: only the full subtree walk reads plan labels (the root overview does not).
  const world: World = { items: [{ id: 'feat', title: 'Feature', role: 'work', depth: 1, properties: { planLabel: 'T1' } }] }
  const calls = serveHooks(on, world)
  on('state.get', async (_$, e) => ({ value: { value: (e as { key: string }).key === 'graphScope' ? U('feat') : undefined, version: 1 } }) as never)
  const clock = mock.clock(on)
  on('tool.call', async () => ok as never)
  const settle = async () => {
    await clock.advance(300)
    await clock.settle()
  }
  await $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId: 'abcdef12', trigger: 'start' }] } as never)
  await settle()
  expect(searchesOf(calls)).toBe(1)
  const warm = getsOf(calls)
  await $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId: 'abcdef12', trigger: 'start' }] } as never)
  await settle()
  expect(searchesOf(calls)).toBe(2)
  expect(getsOf(calls)).toBe(warm)
  await $.tool.call({ tool: `${TO}items_write`, ops: [{ op: 'update', itemId: 'abcdef12', title: 'x' }] } as never)
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

test('trailOf: ancestors root first (short ids), then the item; tolerates missing titles and bad input', () => {
  const raw = { id: U('feat'), title: 'Feature X', ancestors: [{ id: S('root'), title: 'Project', depth: 0 }, { id: S('cont'), depth: 1 }] }
  expect(trailOf(raw, S('feat'))).toEqual([{ id: S('root'), title: 'Project' }, { id: S('cont'), title: S('cont') }, { id: S('feat'), title: 'Feature X' }])
  expect(trailOf({ id: U('solo'), title: 'Solo' }, S('solo'))).toEqual([{ id: S('solo'), title: 'Solo' }])
  expect(trailOf(null, 'x')).toBeUndefined()
})

// --- T12a 13497d52: stalled items (items_read context + history) --------------------------

const MIN = 60_000
const stallRows = (stalled: string[]) => stalled.map(id => ({ id, role: id === 't-review' ? 'review' : 'work', missing: ['implementation-notes', 'session-tracking'] }))
const stallCalls = (calls: Call[]) => calls.filter(c => c.tool === 'items_read' && ((c.args.select === 'context' && c.args.itemId === undefined) || c.args.select === 'history'))
const contextReads = (calls: Call[]) => stallCalls(calls).filter(c => c.args.select === 'context')
const historyReads = (calls: Call[]) => stallCalls(calls).filter(c => c.args.select === 'history')

test('S5: stalled = listed work/review items with no transition in the last 30 min; one scoped context read and one history read', async () => {
  const world = threeLevels()
  world.stalled = stallRows(['t-work', 't-review', 'not-in-scope'])
  world.history = [{ itemId: 't-review', at: 1_000 - 5 * MIN }]
  const env = fake({ world })
  const s = await snapshot(env.io, U('root'))
  expect(s.error).toBe(undefined)
  expect(s.stalled).toEqual({ [S('t-work')]: ['implementation-notes', 'session-tracking'] })
  // t-review moved 5 minutes ago: it crosses 30 minutes after that move.
  expect(s.stallDueAt).toBe(1_000 - 5 * MIN + STALL_MS)
  expect(contextReads(env.calls).map(c => c.args)).toEqual([{ select: 'context', ancestorId: S('root'), limit: 200 }])
  expect(historyReads(env.calls).map(c => c.args)).toEqual([{ select: 'history', since: iso(1_000 - STALL_MS), limit: TRANSITION_LIMIT }])
})

test('S5: stallsOf ignores non-work/review rows and unknown ids, and a move already 30 min old counts as stalled', () => {
  const ids = new Set([S('a'), S('b'), S('q')])
  const context = {
    stalled: [
      { id: S('a'), title: 'a', role: 'work', missing: ['x'] },
      { id: S('b'), title: 'b', role: 'review', missing: [] },
      { id: S('q'), title: 'q', role: 'queue', missing: ['y'] },
      { id: S('zz'), title: 'zz', role: 'work', missing: ['z'] },
    ],
  }
  const history = { items: [{ itemId: U('b'), occurredAt: iso(0) }] }
  expect(stallsOf(context, history, ids, STALL_MS + 1)).toEqual({ stalled: { [S('a')]: ['x'], [S('b')]: [] } })
  expect(stallsOf(context, history, ids, 10)).toEqual({ stalled: { [S('a')]: ['x'] }, dueAt: STALL_MS })
  expect(stallsOf('junk', 'junk', ids, 0)).toEqual({ stalled: {} })
})

test('S6: a full page of 200 history rows may be cut: no stalls at all', async () => {
  const world = threeLevels()
  world.stalled = stallRows(['t-work'])
  const moves = Array.from({ length: TRANSITION_LIMIT }, (_, i) => ({ itemId: `other-${i}`, at: 1_000 - MIN }))
  world.history = moves
  const s = await snapshot(fake({ world }).io, U('root'))
  expect(s.stalled).toEqual({})
  expect(s.stallDueAt).toBe(undefined)
  // One fewer is a complete list: the stall shows.
  world.history = moves.slice(1)
  expect((await snapshot(fake({ world }).io, U('root'))).stalled).toEqual({ [S('t-work')]: ['implementation-notes', 'session-tracking'] })
})

test('S7: a failed stall read is recorded in error; stalled stays unset; the rest of the snapshot is intact', async () => {
  const world = threeLevels()
  world.failHistory = true
  world.context = { 't-work': { gateStatus: { canAdvance: false, phase: 'work', missing: ['x'] }, schema: [] } }
  const s = await snapshot(fake({ world }).io, U('root'))
  expect(s.error).toContain('items_read: internal: history boom')
  expect('stalled' in s).toBe(false)
  expect(s.nodes.map(n => n.id).sort()).toEqual([S('feat'), S('root'), S('sub'), S('t-queue'), S('t-review'), S('t-work')].sort())
  expect(s.gates[S('t-work')]?.missing).toEqual(['x'])
})

test('S7: a failed stall context read is recorded the same way', async () => {
  const world = threeLevels()
  world.failStall = true
  const s = await snapshot(fake({ world }).io, U('root'))
  expect(s.error).toContain('items_read: internal: stall boom')
  expect('stalled' in s).toBe(false)
  expect(s.nodes).toHaveLength(6)
})

test('S7: the overview reads no stall data and has no stalls', async () => {
  const env = fake({ world: projectWorld() })
  const s = await snapshot(env.io, null)
  expect(s.overview).toBe(true)
  expect(stallCalls(env.calls)).toHaveLength(0)
  expect(s.stalled).toBe(undefined)
})

test('S8: a quiet period re-snapshots once, 1s after the earliest listed item crosses 30 min; none when nothing is pending', async () => {
  resetRefreshState()
  const world = threeLevels()
  // t-work moved 5 minutes before the clock's start: it crosses at 1_000 - 5 min + 30 min.
  const movedAt = 1_000 - 5 * MIN
  world.stalled = stallRows(['t-work'])
  world.history = [{ itemId: 't-work', at: movedAt }]
  const env = fake({ world })
  // The timer's refresh reads the scope atom, as a live one does.
  env.state.scope = U('root')
  void refresh(env.io, U('root'))
  await env.advance(300)
  expect(historyReads(env.calls)).toHaveLength(1)
  expect(env.state.snapshot?.stalled).toEqual({})
  // Just before crossing + 1s: nothing yet.
  await env.advance(movedAt + STALL_MS + 1_000 - 1_300 - 1)
  expect(historyReads(env.calls)).toHaveLength(1)
  // The crossing timer fires, then the refresh debounce runs: one more read, and now it is stalled.
  await env.advance(1 + 300)
  expect(historyReads(env.calls)).toHaveLength(2)
  expect(env.state.snapshot?.stalled).toEqual({ [S('t-work')]: ['implementation-notes', 'session-tracking'] })
  // Nothing pending any more: no further re-snapshot however long it stays quiet.
  await env.advance(2 * 60 * MIN)
  expect(historyReads(env.calls)).toHaveLength(2)
  resetRefreshState()
})

test('S8: no recently moved listed item, no crossing timer', async () => {
  resetRefreshState()
  const world = threeLevels()
  world.stalled = stallRows(['t-work'])
  world.history = []
  const env = fake({ world })
  void refresh(env.io, U('root'))
  await env.advance(300)
  expect(historyReads(env.calls)).toHaveLength(1)
  await env.advance(3 * 60 * MIN)
  expect(historyReads(env.calls)).toHaveLength(1)
  resetRefreshState()
})

// --- T12b 6a976f10: toasts (snapshot events) ----------------------------------------------

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
  expect(snapshotEvents(prev, { ...next, error: 'deps_read: internal: failed' })).toEqual([])
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
  const st = stateRig(on, { graphScope: U('feat') })
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
  await $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId: 'abcdef12', trigger: 'start' }] } as never)
  await clock.advance(300)
  await clock.settle()
}

test('S5/S9: graphToasts on: the first snapshot is quiet, a completion toasts once, an unchanged refresh does not', { options: { graphToasts: true } }, async ($, on) => {
  const { world, st, clock, toasts } = toastRig(on)
  await writeAndSettle($, clock)
  expect((st.valueOf('graphSnapshot') as GraphSnapshot | undefined)?.scopeId).toBe(S('feat'))
  expect(toasts).toEqual([])
  ;(world.items.find(i => i.id === 't-review') as Item).role = 'terminal'
  await writeAndSettle($, clock)
  expect(toasts).toEqual([`✓ Done: ${S('t-review')} Reviewed task`])
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
  expect((st.valueOf('graphSnapshot') as GraphSnapshot).nodes.find(n => n.id === S('t-review'))?.role).toBe('terminal')
  expect(toasts).toEqual([])
})

test('S5: graphToasts on: the first snapshot after a scope switch is quiet, both ways', { options: { graphToasts: true } }, async ($, on) => {
  const { world, st, clock, toasts } = toastRig(on)
  const item = (id: string) => world.items.find(i => i.id === id) as Item
  item('sub').role = 'work'
  await writeAndSettle($, clock)
  // Switch to t-work's subtree while sub completes: the same node, another scope, no toast.
  item('sub').role = 'terminal'
  st.put('graphScope', U('t-work'))
  await writeAndSettle($, clock)
  expect((st.valueOf('graphSnapshot') as GraphSnapshot).scopeId).toBe(S('t-work'))
  expect(toasts).toEqual([])
  // Back on feat while t-review completes: still quiet (the previous snapshot is t-work's).
  item('t-review').role = 'terminal'
  st.put('graphScope', U('feat'))
  await writeAndSettle($, clock)
  expect(toasts).toEqual([])
  // A completion within one scope does toast here.
  item('t-queue').role = 'terminal'
  await writeAndSettle($, clock)
  expect(toasts).toEqual([`✓ Done: ${S('t-queue')} Queued task`])
})

// --- T12b 6a976f10: cross-session marks ---------------------------------------------------

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
  const one = markRemote({}, 'aaaaaaaa', 1)
  expect(one).toEqual({ aaaaaaaa: 1 })
  expect(markRemote(one, 'aaaaaaaa', 2)).toBe(one)
  expect(clearRemote(one, ['bbbbbbbb'])).toBe(one)
  expect(clearRemote(one, [])).toBe(one)
  expect(clearRemote({ aaaaaaaa: 1, bbbbbbbb: 2 }, ['aaaaaaaa'])).toEqual({ bbbbbbbb: 2 })
  let map: Record<string, number> = {}
  const key = (i: number) => i.toString(16).padStart(8, '0')
  for (let i = 0; i < REMOTE_CAP; i++) map = markRemote(map, key(i), 1_000 + i)
  expect(Object.keys(map)).toHaveLength(200)
  map = markRemote(map, 'ffffffff', 5_000)
  expect(Object.keys(map)).toHaveLength(200)
  expect(map[key(0)]).toBeUndefined()
  expect(map[key(1)]).toBe(1_001)
  expect(map.ffffffff).toBe(5_000)
})

test('S12: resultItemIds reads each result row id and its cascade ids (advance cascades, items_write cascade); no envelope gives []', () => {
  const advance = {
    structuredContent: {
      ok: true,
      data: { results: [{ ok: true, itemId: U('A'), cascades: [{ itemId: U('P'), applied: true }] }, { ok: true, itemId: U('B') }], summary: { ok: 2, failed: 0 } },
    },
  }
  expect(resultItemIds(advance).map(shortId)).toEqual([S('A'), S('P'), S('B')])
  const write = { structuredContent: { ok: true, data: { results: [{ ok: true, itemId: U('C'), cascade: [{ itemId: U('Q') }] }], summary: { ok: 1, failed: 0 } } } }
  expect(resultItemIds(write).map(shortId)).toEqual([S('C'), S('Q')])
  expect(resultItemIds('nope')).toEqual([])
  expect(resultItemIds(undefined)).toEqual([])
  expect(resultItemIds({ content: [{ type: 'text', text: 'ok' }] })).toEqual([])
})

/** One SSE frame: an `id:` line (the events seq, above 10^12), the event name and its JSON data. */
let seq = 1_000_000_000_000
const frameOf = (event: string, itemId: string | undefined, extra: Record<string, unknown> = {}) => {
  seq++

  return `id: ${seq}\nevent: ${event}\ndata: ${JSON.stringify({ id: seq, event, ...(itemId !== undefined ? { itemId } : {}), ...extra })}\n\n`
}
const sid = (v: string | undefined): string | undefined => (v === undefined ? undefined : shortId(v))

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
  env.state.scope = U('root')
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
  syncLive(io, 1)
  await env.advance(0)
  // Another session advanced A: marked, under its short id.
  push(frameOf('item.advanced', U('A')))
  await env.advance(0)
  expect(Object.keys(marks)).toEqual([S('A')])
  // The same event again writes nothing.
  push(frameOf('item.advanced', U('A')))
  await env.advance(0)
  expect(writes).toBe(1)
  // During a local write, B's event is our echo.
  await withLocalWrite(async () => {
    push(frameOf('note.upserted', U('B')))
    await env.advance(0)
  }, () => io.now())
  expect(marks[S('B')]).toBeUndefined()
  // 4s after the write ended: still an echo. 6s after: another session's.
  await env.advance(4_000)
  push(frameOf('item.updated', U('C')))
  await env.advance(0)
  expect(marks[S('C')]).toBeUndefined()
  await env.advance(2_000)
  push(frameOf('item.updated', U('C')))
  await env.advance(0)
  expect(marks[S('C')]).toBeDefined()
  // A bus-level event (no itemId) marks nothing.
  push('id: 1000000009999\nevent: sync.lost\ndata: {"id":1000000009999,"event":"sync.lost","reason":"queue_overflow"}\n\n')
  await env.advance(0)
  expect(Object.keys(marks).sort()).toEqual([S('A'), S('C')].sort())
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

test('S13: a successful local write clears the marks of its items and cascades; a write with no results does not', async ($, on) => {
  serveHooks(on)
  const st = stateRig(on, { graphRemote: { [S('A')]: 1, [S('P')]: 2, [S('Q')]: 3 } })
  const clock = mock.clock(on)
  let fail = false
  const advanced = { ok: true, data: { results: [{ ok: true, itemId: U('A'), previousRole: 'work', newRole: 'terminal', cascades: [{ itemId: U('P'), applied: true }] }], summary: { ok: 1, failed: 0 } } }
  on('tool.call', async () => (fail ? { ...ok, isError: true } : { ref: 'r', result: { structuredContent: advanced }, text: 'ok' }) as never)
  fail = true
  await $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId: U('Q'), trigger: 'start' }] } as never)
  await clock.settle()
  expect(st.valueOf('graphRemote')).toEqual({ [S('A')]: 1, [S('P')]: 2, [S('Q')]: 3 })
  fail = false
  await $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId: U('A'), trigger: 'complete' }] } as never)
  await clock.settle()
  expect(st.valueOf('graphRemote')).toEqual({ [S('Q')]: 3 })
  // Nothing marked among the touched ids: no write at all.
  const writes = st.sets.filter(k => k === 'graphRemote').length
  await $.tool.call({ tool: `${TO}notes_write`, ops: [{ op: 'upsert', itemId: U('Z'), key: 'k', role: 'work', body: 'b' }] } as never)
  await clock.settle()
  expect(st.sets.filter(k => k === 'graphRemote')).toHaveLength(writes)
})

test('S13: a whole-call refusal envelope (ok:false, no data.results) clears nothing; a partial_failure with data.results clears the ids it names', async ($, on) => {
  serveHooks(on)
  const st = stateRig(on, { graphRemote: { [S('A')]: 1, [S('P')]: 2, [S('Q')]: 3 } })
  const clock = mock.clock(on)
  let reply: unknown
  on('tool.call', async () => reply as never)
  const call = (itemId: string) => $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId, trigger: 'complete' }] } as never)
  reply = { ref: 'r', result: { structuredContent: { ok: false, error: { code: 'forbidden', kind: 'permanent', message: 'refused' } } }, text: 'forbidden: refused', isError: true }
  await call(U('Q'))
  await clock.settle()
  expect(st.valueOf('graphRemote')).toEqual({ [S('A')]: 1, [S('P')]: 2, [S('Q')]: 3 })
  const partial = {
    ok: false,
    error: { code: 'partial_failure', kind: 'permanent', message: '1 of 2 failed' },
    data: { results: [{ ok: true, itemId: U('A'), previousRole: 'work', newRole: 'terminal' }, { ok: false, error: { code: 'not_found', kind: 'permanent', message: 'no such item' } }], summary: { ok: 1, failed: 1 } },
  }
  reply = { ref: 'r', result: { structuredContent: partial }, text: 'partial_failure: 1 of 2 failed', isError: true }
  await call(U('A'))
  await clock.settle()
  expect(st.valueOf('graphRemote')).toEqual({ [S('P')]: 2, [S('Q')]: 3 })
})

// -- window-only echo attribution and cross-session follow from SSE --
// Oracles: task-scope AC5 and D4 (the SSE `actor` is the transport principal, shared by every session on one
// credential, so only the echo window attributes), api-rest.md section 21 "Actor and rootId" (what the event carries).
import { setRemoteAdvanceListener, remoteAdvanceListener } from '../src/graph-data/live.ts'
import { REMOTE_FOLLOW_DEBOUNCE_MS, REMOTE_FOLLOW_QUIET_MS, attribute, remoteFollowCandidate } from '../src/graph-data/remote.ts'

const IDLE = { inFlight: 0, lastEnd: null as number | null }

// -- pure: attribute --

test('attribute: the window alone decides - local while a write is in flight and up to 5s after it ended, remote after', () => {
  const end = 100_000
  const w = { inFlight: 0, lastEnd: end }
  expect(attribute({ itemId: 'x' }, w, end + 4_999)).toEqual({ origin: 'local', by: 'window' })
  expect(attribute({ itemId: 'x' }, w, end + 6_000)).toEqual({ origin: 'remote', by: 'window' })
  expect(attribute({ itemId: 'x' }, { inFlight: 1, lastEnd: null }, end)).toEqual({ origin: 'local', by: 'window' })
  expect(attribute({ itemId: 'x' }, IDLE, end)).toEqual({ origin: 'remote', by: 'window' })
})

test('attribute: an actor on the event never changes the outcome (D4)', () => {
  const busy = { inFlight: 1, lastEnd: null as number | null }
  const actors = [{ id: 'api:shared', kind: 'external' }, { id: 'me:1' }, { id: 'sub:2', parent: 'me:1' }, {}, { id: '' }, null, 'str']
  for (const actor of actors) {
    expect(attribute({ itemId: 'x', actor }, IDLE, 1)).toEqual({ origin: 'remote', by: 'window' })
    expect(attribute({ itemId: 'x', actor }, busy, 1)).toEqual({ origin: 'local', by: 'window' })
  }
  for (const data of [undefined, null, 'x', 5, []]) expect(attribute(data, IDLE, 1)).toEqual({ origin: 'remote', by: 'window' })
})

// -- pure: remoteFollowCandidate --

const REMOTE = { origin: 'remote', by: 'window' } as const
const advanced = (over: Record<string, unknown> = {}) => ({ id: 1_000_000_000_001, event: 'item.advanced', itemId: U('X'), actor: { id: 'api:other' }, rootId: ROOT, ...over })

test('an item.advanced event attributed remote, in this project, yields its item', () => {
  expect(sid(remoteFollowCandidate('item.advanced', advanced(), REMOTE, ROOT))).toBe(S('X'))
})

test('not eligible: local origin, another event name, missing/other rootId, no project root, bad rootId text, no itemId', () => {
  expect(sid(remoteFollowCandidate('item.advanced', advanced(), REMOTE, ROOT))).toBe(S('X'))
  expect(remoteFollowCandidate('item.advanced', advanced(), { origin: 'local', by: 'window' }, ROOT)).toBeUndefined()
  for (const name of ['item.updated', 'item.created', 'item.deleted', 'note.upserted', 'dependency.added', 'run.opened', 'decision.raised']) {
    expect(remoteFollowCandidate(name, advanced({ event: name }), REMOTE, ROOT)).toBeUndefined()
  }
  const { rootId: _drop, ...noRoot } = advanced()
  expect(remoteFollowCandidate('item.advanced', noRoot, REMOTE, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: 'c0000000-0000-4000-8000-0000000000ff' }), REMOTE, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced(), REMOTE, null)).toBeUndefined()
  // Probes: whitespace around an otherwise matching rootId, a non-string rootId, an empty or absent itemId, no data.
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: ` ${ROOT} ` }), REMOTE, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: 5 }), REMOTE, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced({ itemId: '' }), REMOTE, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', advanced({ itemId: undefined }), REMOTE, ROOT)).toBeUndefined()
  expect(remoteFollowCandidate('item.advanced', undefined, REMOTE, ROOT)).toBeUndefined()
})

test('the rootId comparison is case-insensitive in both directions', () => {
  const upperRoot = 'ABCDEF01-0000-4000-8000-0000000000AA'
  expect(sid(remoteFollowCandidate('item.advanced', advanced({ rootId: upperRoot.toLowerCase() }), REMOTE, upperRoot))).toBe(S('X'))
  expect(sid(remoteFollowCandidate('item.advanced', advanced({ rootId: upperRoot }), REMOTE, upperRoot.toLowerCase()))).toBe(S('X'))
  // Control: a different root in either case stays out.
  expect(remoteFollowCandidate('item.advanced', advanced({ rootId: upperRoot }), REMOTE, ROOT)).toBeUndefined()
})

// -- live rig: frames through the spawn script --

const remoteFrame = (itemId: string, over: Record<string, unknown> = {}) => frameOf('item.advanced', itemId, { actor: { id: 'api:other', kind: 'external' }, rootId: ROOT, ...over })

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
  env.state.scope = U('root')
  let marks: Record<string, number> = {}
  const io: GraphIo = {
    ...env.io,
    updateRemote: async step => {
      marks = step(marks)
    },
  }
  const followed: string[] = []
  setRemoteAdvanceListener(id => void followed.push(shortId(id)))
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

test('R1: frames pushed INSIDE a local write are our echo whatever actor they carry; after the window they are another session\'s', async () => {
  const rig = await sseLive()
  await withLocalWrite(async () => {
    rig.push(remoteFrame(U('X')))
    await rig.env.advance(0)
    rig.push(frameOf('item.advanced', U('Y')))
    await rig.env.advance(0)
  }, () => rig.io.now())
  expect(Object.keys(rig.marks())).toEqual([])
  await rig.env.advance(6_000)
  rig.push(remoteFrame(U('X')))
  rig.push(frameOf('item.advanced', U('Y')))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks()).sort()).toEqual([S('X'), S('Y')].sort())
  await rig.done()
})

test('R2 (D4): the actor cannot tell sessions apart - a frame with the principal of an earlier local write is still marked once the window is over', async () => {
  const rig = await sseLive()
  await withLocalWrite(async () => undefined, () => rig.io.now())
  await rig.env.advance(10_000)
  rig.push(remoteFrame(U('mine'), { actor: { id: 'local', kind: 'orchestrator' } }))
  rig.push(remoteFrame(U('theirs'), { actor: { id: 'api:other' } }))
  rig.push(frameOf('item.updated', U('plain')))
  rig.push(frameOf('item.updated', U('child'), { actor: { id: 'sub:2', parent: 'local' } }))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks()).sort()).toEqual([S('mine'), S('theirs'), S('plain'), S('child')].sort())
  await rig.done()
})

test('R3: an actorless frame 6s after a write is marked; 4s after it is not (window fallback kept)', async () => {
  const rig = await sseLive()
  await withLocalWrite(async () => undefined, () => rig.io.now())
  await rig.env.advance(4_000)
  rig.push(frameOf('item.updated', U('early')))
  await rig.env.advance(0)
  expect(rig.marks()[S('early')]).toBeUndefined()
  await rig.env.advance(2_000)
  rig.push(frameOf('item.updated', U('late')))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks())).toEqual([S('late')])
  await rig.done()
})

test('probe: a duplicate frame marks once, under the short id of the UUID it names', async () => {
  const rig = await sseLive()
  rig.push(remoteFrame(U('upper')))
  rig.push(remoteFrame(U('upper')))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks())).toEqual([S('upper')])
  await rig.done()
})

test('probe: a replayed frame after sync.lost is attributed the same way', async () => {
  const rig = await sseLive()
  rig.push('id: 1000000009998\nevent: sync.lost\ndata: {"id":1000000009998,"event":"sync.lost","reason":"queue_overflow"}\n\n')
  await rig.env.advance(0)
  rig.push(remoteFrame(U('replay-theirs')))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks())).toEqual([S('replay-theirs')])
  await rig.done()
})

// -- live rig: the follow listener --

test('F1: three eligible frames 1s apart give no listener call until 3s after the last, then exactly one with the last item', async () => {
  const rig = await sseLive()
  expect(REMOTE_FOLLOW_DEBOUNCE_MS).toBe(3_000)
  rig.push(remoteFrame(U('A')))
  await rig.env.advance(1_000)
  rig.push(remoteFrame(U('B')))
  await rig.env.advance(1_000)
  rig.push(remoteFrame(U('C')))
  await rig.env.advance(2_999)
  expect(rig.followed).toEqual([])
  await rig.env.advance(1)
  expect(rig.followed).toEqual([S('C')])
  // It fires once only.
  await rig.env.advance(10_000)
  expect(rig.followed).toEqual([S('C')])
  await rig.done()
})

test('F2: frames inside a local write, of another root, without a root, or not item.advanced never reach the listener; a real remote advance does', async () => {
  const rig = await sseLive()
  await withLocalWrite(async () => {
    rig.push(remoteFrame(U('inwrite')))
    await rig.env.advance(0)
  }, () => rig.io.now())
  await rig.env.advance(31_000)
  rig.push(remoteFrame(U('otherroot'), { rootId: 'c0000000-0000-4000-8000-0000000000ff' }))
  rig.push(remoteFrame(U('noroot'), { rootId: undefined }))
  rig.push(frameOf('item.updated', U('upd'), { actor: { id: 'api:other' }, rootId: ROOT }))
  rig.push(frameOf('note.upserted', U('note'), { actor: { id: 'api:other' }, rootId: ROOT }))
  await rig.env.advance(10_000)
  expect(rig.followed).toEqual([])
  // Control: the frames outside the window did arrive (they are marked), and a real one follows.
  expect(Object.keys(rig.marks()).sort()).toEqual([S('noroot'), S('note'), S('otherroot'), S('upd')].sort())
  rig.push(remoteFrame(U('real')))
  await rig.env.advance(3_000)
  expect(rig.followed).toEqual([S('real')])
  await rig.done()
})

test('F3: an upper-case rootId on the frame still follows', async () => {
  const rig = await sseLive()
  rig.push(remoteFrame(U('U'), { rootId: ROOT.toUpperCase() }))
  await rig.env.advance(3_000)
  expect(rig.followed).toEqual([S('U')])
  await rig.done()
})

test('F4: stopping live (syncLive 0, restartLive, resetLiveState) before the debounce fires cancels the pending call; an uninterrupted one fires', async () => {
  // Control: uninterrupted.
  let rig = await sseLive()
  rig.push(remoteFrame(U('A')))
  await rig.env.advance(0)
  await rig.env.advance(3_000)
  expect(rig.followed).toEqual([S('A')])
  await rig.done()
  // syncLive 0.
  rig = await sseLive()
  rig.push(remoteFrame(U('A')))
  await rig.env.advance(1_000)
  syncLive(rig.io, 0)
  await rig.env.advance(5_000)
  expect(rig.followed).toEqual([])
  await rig.done()
  // restartLive.
  rig = await sseLive()
  rig.push(remoteFrame(U('A')))
  await rig.env.advance(1_000)
  restartLive(rig.io, 1)
  await rig.env.advance(5_000)
  expect(rig.followed).toEqual([])
  await rig.done()
  // resetLiveState.
  rig = await sseLive()
  rig.push(remoteFrame(U('A')))
  await rig.env.advance(1_000)
  resetLiveState()
  await rig.env.advance(5_000)
  expect(rig.followed).toEqual([])
  await rig.done()
})

test('F5: own activity wins: a write that ended under 30s before the fire drops it, 30s or more delivers it, one still in flight drops it', async () => {
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
    rig.push(remoteFrame(U('F')))
    await rig.env.advance(0)
    await rig.env.advance(3_000)
    expect([label, rig.followed.length]).toEqual([label, expected])
    await rig.done()
  }
  // In flight at fire time.
  const rig = await sseLive()
  let release: () => void = () => undefined
  const held = withLocalWrite(() => new Promise<void>(resolve => (release = resolve)), () => rig.io.now())
  rig.push(remoteFrame(U('F')))
  await rig.env.advance(0)
  await rig.env.advance(3_000)
  expect(rig.followed).toEqual([])
  release()
  await held
  await rig.done()
})

test('probe: a listener that throws does not kill the stream; the next frame still marks', async () => {
  const rig = await sseLive()
  setRemoteAdvanceListener(() => {
    throw new Error('listener boom')
  })
  expect(remoteAdvanceListener()).not.toBeNull()
  rig.push(remoteFrame(U('A')))
  await rig.env.advance(3_000)
  rig.push(remoteFrame(U('B')))
  await rig.env.advance(0)
  expect(Object.keys(rig.marks()).sort()).toEqual([S('A'), S('B')].sort())
  await rig.done()
})

test('setRemoteAdvanceListener stores and clears the listener', () => {
  const fn = (_id: string) => undefined
  setRemoteAdvanceListener(fn)
  expect(remoteAdvanceListener()).toBe(fn)
  setRemoteAdvanceListener(null)
  expect(remoteAdvanceListener()).toBeNull()
})

// -- loaded plugin: the tool.call hook opens the echo window before next() --

// The loaded plugin has its own module instance (a fresh one per test), so the window is observed through its
// effect: the SSE stream is the test's process.spawn handler, the marks land in $.state graphRemote.
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

test('L1: a frame arriving while a local write tool call is still running is our echo; a stranger frame well after it is marked', SSE_TIME, async ($, on) => {
  const { st, clock, push } = loadedSse(on)
  on('tool.call', async () => {
    push(frameOf('item.updated', U('echo'), { actor: { id: 'local' }, rootId: ROOT }))
    await settleBy(clock, 500)

    return ok as never
  })
  await $.session.start({ cwd: '/work/project', surface: 'terminal', isInteractive: true } as never)
  await settleBy(clock, 1_000)
  await $.tool.call({ tool: `${TO}items_write`, ops: [{ op: 'create', title: 'new' }] } as never)
  await settleBy(clock, 20_000)
  push(frameOf('item.updated', U('stranger'), { actor: { id: 'api:other' }, rootId: ROOT }))
  await settleBy(clock, 1_000)
  expect(Object.keys((st.valueOf('graphRemote') ?? {}) as object)).toEqual([S('stranger')])
})

test('L2: a read tool opens no echo window: a frame right after it is marked', SSE_TIME, async ($, on) => {
  const { st, clock, push } = loadedSse(on)
  on('tool.call', async () => ok as never)
  await $.session.start({ cwd: '/work/project', surface: 'terminal', isInteractive: true } as never)
  await settleBy(clock, 20_000)
  await $.tool.call({ tool: `${TO}items_read`, select: 'get', itemId: 'abcdef12' } as never)
  push(frameOf('item.updated', U('by-reader'), { actor: { id: 'local' }, rootId: ROOT }))
  await settleBy(clock, 1_000)
  expect(Object.keys((st.valueOf('graphRemote') ?? {}) as object)).toEqual([S('by-reader')])
})
