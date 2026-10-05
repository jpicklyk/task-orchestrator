// Live-sync control path, driven through the LOADED plugin only (bug 25207418).
//
// Authored blind to src/: every surface here is an engine surface ($.session.start, $.command.run,
// $.ui.close, $.prompt.submit, a mounted AbovePrompt band) answered by hooks on the test's `on`.
// Oracles (frozen in the item's test-plan, before the fix):
//   OA  the graphSubscribers atom doc (types/index.d.ts): the live source runs while the count is above 0.
//   OB  README "Band and status line" (the band keeps live on for the session) and "Live updates"
//       (no URL: a 15 s poll; URL: one curl stream of GET /api/v1/events for the project root).
//   OC  claude-plugins/CLAUDE.md "Mod plugin: loading and refresh": a reload re-runs register and
//       session.start and resets module variables; $.state survives.
// The kit does not route the plugin's own $.state.set writes to its own state.set hooks (mod CLAUDE.md
// rule 5/6), so every scenario here passes only through a direct request call, never a state.set hook.
import type { On } from 'claude-code'
import { expect, mock, test } from 'claude-code/testing'

import type { GraphSnapshot, GraphStatus } from '../types'

const PLUGIN = 'task-orchestrator-mod'
const ROOT = '00000000-0000-4000-8000-000000000001'
const CONFIG = `project:\n  rootId: ${ROOT}\n`
const API = 'http://localhost:3001'
/** README "Live updates": a 15 second poll. */
const POLL = 15_000
/** Slack past a poll tick for the debounced refresh it asks for to run. */
const SLACK = 1_000

type Engine = Parameters<Parameters<typeof test>[1]>[0]
type Slot = { value: unknown; version: number }
type Call = { tool: string; args: Record<string, unknown> }

const reply = (body: unknown) => ({ content: [{ type: 'text', text: JSON.stringify(body) }] })
const failed = (message: string) => ({ isError: true, content: [{ type: 'text', text: message }] })

type Item = { id: string; parentId?: string; title: string; role: string; depth: number }
/** Project root -> feature (work) -> one task in work. */
const ITEMS: Item[] = [
  { id: ROOT, title: 'Project', role: 'work', depth: 0 },
  { id: 'feat', parentId: ROOT, title: 'Feature', role: 'work', depth: 1 },
  { id: 't-work', parentId: 'feat', title: 'Working task', role: 'work', depth: 2 },
]

/** The TO server's read tools, answered from ITEMS. */
function answer(tool: string, args: Record<string, unknown>) {
  if (tool === 'query_items' && args.operation === 'overview') {
    const anchor = String(args.anchorId)
    const rows = ITEMS.filter(i => i.parentId === anchor).map(i => ({ ...i, priority: 'medium', childCounts: { work: 1 } }))

    return reply({ anchor: { id: anchor, title: 'Project' }, items: rows, total: rows.length, truncated: false, offset: 0 })
  }
  if (tool === 'query_items' && args.operation === 'get') {
    const item = ITEMS.find(i => i.id === String(args.itemId))

    return item ? reply(item) : failed('not found')
  }
  if (tool === 'query_items') {
    const scope = String(args.ancestorId)
    const inTree = (i: Item | undefined): boolean => i !== undefined && (i.id === scope || (i.parentId !== undefined && inTree(ITEMS.find(p => p.id === i.parentId))))
    const rows = ITEMS.filter(i => inTree(i)).map(i => ({ ...i, priority: 'medium' }))

    return reply({ items: rows, total: rows.length, returned: rows.length, limit: 50, offset: 0 })
  }
  if (tool === 'query_dependencies') return reply({ dependencies: [] })
  if (tool === 'get_context') return reply({})

  return failed(`unexpected ${tool}`)
}

type World = {
  state: Map<string, Slot>
  sets: { key: string; value: unknown }[]
  calls: Call[]
  spawns: { argv: readonly string[]; input?: string }[]
  opened: unknown[]
  clock: ReturnType<typeof mock.clock>
  /** Snapshot reads (search or overview), the unit a poll tick or an SSE event costs. */
  reads: () => number
  /** Every graphStatus.liveSource the plugin wrote, in order. */
  liveWrites: () => string[]
  subscriberWrites: () => unknown[]
  put: (key: string, value: unknown) => void
  valueOf: (key: string) => unknown
}

/**
 * Everything beneath the plugin, in memory: $.state, fs (config only), env, the clock, the TO server,
 * process.spawn (a curl stream that stays open), ui.open/close/status/render, command.register.
 */
function world(on: On, opts: { seed?: Record<string, unknown>; env?: Record<string, string> } = {}): World {
  const state = new Map<string, Slot>()
  const slot = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`
  const SLOT = (key: string) => `${PLUGIN}/${key}/`
  for (const [key, value] of Object.entries(opts.seed ?? {})) state.set(SLOT(key), { value, version: 1 })
  const sets: { key: string; value: unknown }[] = []
  const calls: Call[] = []
  const spawns: { argv: readonly string[]; input?: string }[] = []
  const opened: unknown[] = []

  on('state.get', async (_$, e) => ({ value: state.get(slot(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slot(e as never)
    const cur = state.get(k)?.version ?? 0
    const ifVersion = (e as { ifVersion?: number }).ifVersion
    if (ifVersion !== undefined && ifVersion !== cur) return { value: { isSet: false, version: cur } } as never
    state.set(k, { value: (e as { value: unknown }).value, version: cur + 1 })
    if ((e as { plugin: string }).plugin === PLUGIN) sets.push({ key: (e as { key: string }).key, value: (e as { value: unknown }).value })

    return { value: { isSet: true, version: cur + 1 } } as never
  })
  on('fs.read', async (_$, e) => {
    const path = String((e as { path: string }).path).split(String.fromCharCode(92)).join('/')
    if (path.endsWith('.taskorchestrator/config.yaml')) return { value: CONFIG } as never
    throw new Error(`ENOENT ${path}`)
  })
  mock.env(on, opts.env ?? {})
  const clock = mock.clock(on, { now: 1_000_000 })
  on('mcp.call', async (_$, e) => {
    calls.push({ tool: e.tool, args: e.args })

    return { value: answer(e.tool, e.args) } as never
  })
  on('process.spawn', async function* (_$, e) {
    spawns.push({ argv: [...e.argv], input: e.input })
    // curl holds the stream open: it never ends within a test's mocked minutes. A generator suspended in
    // an await cannot observe the reader's return() (measured: a finally here never ran on close), so the
    // kill itself is not asserted; S4 (sse) checks the status and that no second curl follows.
    await clock.sleep(24 * 3_600_000)

    return { code: 0, signal: null }
  } as never)
  // SessionStartResult: `{ cwd }`, echoed by core.
  on('session.start', async (_$, e) => ({ cwd: e.cwd }) as never)
  on('ui.open', async (_$, e) => {
    opened.push(e)

    return { value: { isPlaced: true } } as never
  })
  on('ui.close', async () => ({ value: undefined }) as never)
  on('ui.status', async () => ({ value: undefined }) as never)
  on('ui.toast', async () => ({ value: undefined }) as never)
  on('command.register', async () => ({ value: undefined }) as never)
  on('prompt.submit', async (_$, e) => ({ text: e.text }) as never)
  on('ui.render', async ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, { key: 'engine' }, 'engine') as never
  })

  return {
    state,
    sets,
    calls,
    spawns,
    opened,
    clock,
    reads: () => calls.filter(c => c.tool === 'query_items' && (c.args.operation === 'search' || c.args.operation === 'overview')).length,
    liveWrites: () => sets.filter(s => s.key === 'graphStatus').map(s => (s.value as GraphStatus).liveSource),
    subscriberWrites: () => sets.filter(s => s.key === 'graphSubscribers').map(s => s.value),
    put: (key, value) => state.set(SLOT(key), { value, version: (state.get(SLOT(key))?.version ?? 0) + 1 }),
    valueOf: key => state.get(SLOT(key))?.value,
  }
}

const SSE_ENV = { TASK_ORCHESTRATOR_API_URL: API }

const settle = async (w: World, ms = SLACK) => {
  await w.clock.advance(ms)
  await w.clock.settle()
}

const start = async ($: Engine, w: World) => {
  await $.session.start({ cwd: '/work/project', surface: 'terminal', isInteractive: true } as never)
  await settle(w)
}

const openGraph = async ($: Engine, w: World) => {
  await $.command.run({ command: 'to-graph', args: 'root' } as never)
  await settle(w)
}

// The test's `$` carries no `ui.close` (the kit raises it only from a plugin's `$.ui.close`), so an inline
// plugin closes the pane on a command of its own, as the pane's opener would see a close dispatched.
const CLOSER = {
  name: 'graph-closer',
  tier: 'append',
  register(on: On) {
    on('command.run', async ($, e, next) => {
      if ((e as { command: string }).command !== 'close-graph') return next(e)
      await $.ui.close({ id: 'to-graph' } as never)

      return { text: 'closed' } as never
    })
  },
} as const
const WITH_CLOSER = { plugins: [CLOSER] } as never
// An open curl stream makes every settle wait out the kit's real-time idle check (about 2 s each, measured),
// so the SSE tests get a longer budget than the 5 s default.
const SSE_TIME = { timeoutMs: 30_000 } as never
const SSE_WITH_CLOSER = { plugins: [CLOSER], timeoutMs: 30_000 } as never

const closeGraph = async ($: Engine, w: World) => {
  await $.command.run({ command: 'close-graph', args: '' } as never)
  await settle(w)
}

/** Reads over one more poll period, past the debounce. */
const readsOverOnePoll = async (w: World) => {
  const before = w.reads()
  await settle(w, POLL + SLACK)

  return w.reads() - before
}

const lastLive = (w: World) => (w.valueOf('graphStatus') as GraphStatus | undefined)?.liveSource

const node = (id: string, role: string, depth: number, parentId: string | null) => ({ id, parentId, title: `Title ${id}`, role, depth })
/** A snapshot with one leaf in work, so the band draws its press-to-open line. */
const bandSnapshot = (): GraphSnapshot =>
  ({
    scopeId: 'root',
    rootId: 'root',
    nodes: [node('root', 'work', 0, null), node('leaf', 'work', 1, 'root')],
    edges: [],
    external: {},
    gates: {},
    takenAt: 1,
    truncated: false,
  }) as GraphSnapshot

const bandProps = () => ({ hasSurvey: false, isWorking: false, maxRows: 6, bodyColumns: 100, scroll: { offset: 0, bodyRows: 6 }, view: {} }) as never

// ── harness probe: is the loaded plugin's module state fresh per test? ─────────────────────────────

// Leaves a live source running on purpose. If the loaded plugin's module variables (the live source's
// "running" flag) leaked into the next test, S1 right below would see no poll start. S1 green after this
// is the evidence that each test loads a fresh module instance, which S8 relies on to model a reload.
test('probe: a test that leaves the live source running (S1 below must still start its own)', async ($, on) => {
  const w = world(on)
  await start($, w)
  await openGraph($, w)
  expect(w.liveWrites()).toContain('poll')
})

// ── S1-S7: the direct request calls on open, close and band press (regression guards) ──────────────

test('S1: /to-graph starts the poll (no URL): liveSource poll, and a snapshot read on the next tick', async ($, on) => {
  const w = world(on)
  await start($, w)
  expect(w.liveWrites()).not.toContain('poll')
  await openGraph($, w)
  expect(w.subscriberWrites()).toEqual([1])
  expect(w.liveWrites()).toContain('poll')
  expect(lastLive(w)).toBe('poll')
  expect(await readsOverOnePoll(w)).toBeGreaterThanOrEqual(1)
})

test('S2: with an API URL and a config rootId, /to-graph spawns exactly one curl for the events stream', SSE_TIME, async ($, on) => {
  const w = world(on, { env: SSE_ENV })
  await start($, w)
  expect(w.spawns).toHaveLength(0)
  await openGraph($, w)
  expect(w.spawns).toHaveLength(1)
  expect(w.spawns[0]!.argv.join(' ')).toContain(`${API}/api/v1/events?root=${ROOT}`)
  expect(lastLive(w)).toBe('sse')
})

test('S3: /to-graph twice counts once and runs one live source', SSE_TIME, async ($, on) => {
  const w = world(on, { env: SSE_ENV })
  await start($, w)
  await openGraph($, w)
  await openGraph($, w)
  expect(w.subscriberWrites()).toEqual([1])
  expect(w.valueOf('graphSubscribers')).toBe(1)
  await settle(w, POLL)
  expect(w.spawns).toHaveLength(1)
})

test('S3 (poll): /to-graph twice gives one poll, one read per period', async ($, on) => {
  const w = world(on)
  await start($, w)
  await openGraph($, w)
  await openGraph($, w)
  expect(w.subscriberWrites()).toEqual([1])
  expect(await readsOverOnePoll(w)).toBe(1)
})

test('S4: open then close stops the poll: liveSource none and no reads over two periods', WITH_CLOSER, async ($, on) => {
  const w = world(on)
  await start($, w)
  await openGraph($, w)
  await closeGraph($, w)
  expect(w.subscriberWrites()).toEqual([1, 0])
  expect(lastLive(w)).toBe('none')
  const before = w.reads()
  await settle(w, 2 * POLL + SLACK)
  expect(w.reads()).toBe(before)
})

test('S4 (sse): open then close ends the curl and writes liveSource none', SSE_WITH_CLOSER, async ($, on) => {
  const w = world(on, { env: SSE_ENV })
  await start($, w)
  await openGraph($, w)
  expect(w.spawns).toHaveLength(1)
  await closeGraph($, w)
  expect(lastLive(w)).toBe('none')
  await settle(w, 2 * POLL)
  expect(w.spawns).toHaveLength(1)
})

test('S5: with the band subscribed, closing the pane keeps live on (no none write, ticks continue)', WITH_CLOSER, async ($, on) => {
  const w = world(on, { seed: { graphSnapshot: bandSnapshot() } })
  await start($, w)
  await $.prompt.submit({ text: 'hello' } as never)
  await settle(w)
  expect(w.subscriberWrites()).toEqual([1])
  await openGraph($, w)
  expect(w.subscriberWrites()).toEqual([1, 2])
  const liveBeforeClose = w.liveWrites().length
  await closeGraph($, w)
  expect(w.subscriberWrites()).toEqual([1, 2, 1])
  expect(w.liveWrites().slice(liveBeforeClose)).not.toContain('none')
  expect(lastLive(w)).toBe('poll')
  expect(await readsOverOnePoll(w)).toBeGreaterThanOrEqual(1)
})

for (const surface of ['terminal', 'desktop'] as const) {
  test(`S6: on ${surface}, a band press with no prompt starts live as /to-graph does`, async ($, on) => {
    const w = world(on)
    await start($, w)
    w.put('graphSnapshot', bandSnapshot())
    const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'AbovePrompt', props: bandProps() })
    await ui.press({ key: 'band-open' })
    await settle(w)
    expect(w.opened).toHaveLength(1)
    expect(w.subscriberWrites()).toEqual([1])
    expect(w.liveWrites()).toContain('poll')
    expect(lastLive(w)).toBe('poll')
    expect(await readsOverOnePoll(w)).toBeGreaterThanOrEqual(1)
    await ui.unmount()
  })
}

test('S7: closing a pane never opened writes neither the count nor the status', WITH_CLOSER, async ($, on) => {
  const w = world(on)
  await start($, w)
  const statusWrites = w.liveWrites().length
  await closeGraph($, w)
  expect(w.subscriberWrites()).toEqual([])
  expect(w.liveWrites()).toHaveLength(statusWrites)
})

test('S7: open, close, close counts 1 then 0, never below', WITH_CLOSER, async ($, on) => {
  const w = world(on)
  await start($, w)
  await openGraph($, w)
  await closeGraph($, w)
  await closeGraph($, w)
  expect(w.subscriberWrites()).toEqual([1, 0])
  expect(w.valueOf('graphSubscribers')).toBe(0)
})

// ── S8-S10: session.start after a hot reload ───────────────────────────────────────────────────────

/** What $.state holds after a reload with the pane open and the band subscribed (OC: state survives). */
const RELOADED = { graphSubscribers: 1, graphPaneOpen: true, bandSubscribed: true, graphStatus: { refreshing: false, liveSource: 'sse' } }

test('S8: after a reload with a subscriber counted, session.start alone restarts live (poll)', async ($, on) => {
  const w = world(on, { seed: { ...RELOADED } })
  await start($, w)
  expect(w.liveWrites()).toContain('poll')
  expect(lastLive(w)).toBe('poll')
  expect(await readsOverOnePoll(w)).toBeGreaterThanOrEqual(1)
  // session.start never counts a subscriber itself.
  expect(w.subscriberWrites()).toEqual([])
})

test('S8 (sse): after a reload, session.start alone spawns exactly one curl for the events stream', SSE_TIME, async ($, on) => {
  const w = world(on, { seed: { ...RELOADED }, env: SSE_ENV })
  await start($, w)
  expect(w.spawns).toHaveLength(1)
  expect(w.spawns[0]!.argv.join(' ')).toContain(`${API}/api/v1/events?root=${ROOT}`)
  expect(lastLive(w)).toBe('sse')
})

test('S8 probe (duplicate): session.start twice after a reload still runs one curl', SSE_TIME, async ($, on) => {
  const w = world(on, { seed: { ...RELOADED }, env: SSE_ENV })
  await start($, w)
  await start($, w)
  expect(w.spawns).toHaveLength(1)
})

test('S9: with no subscriber, session.start starts nothing (sse env: no spawn, no sse/poll write)', SSE_TIME, async ($, on) => {
  const w = world(on, { seed: { graphSubscribers: 0 }, env: SSE_ENV })
  await start($, w)
  await settle(w, 2 * POLL)
  expect(w.spawns).toHaveLength(0)
  expect(w.liveWrites()).not.toContain('sse')
  expect(w.liveWrites()).not.toContain('poll')
})

test('S9 (poll): with no subscriber, session.start reads once at most and never polls', async ($, on) => {
  const w = world(on, { seed: { graphSubscribers: 0 } })
  await start($, w)
  const before = w.reads()
  await settle(w, 2 * POLL + SLACK)
  expect(w.reads()).toBe(before)
  expect(w.liveWrites()).not.toContain('poll')
})

test('S10: after a reload, /to-graph with the pane already counted still runs exactly one live source', SSE_TIME, async ($, on) => {
  const w = world(on, { seed: { ...RELOADED }, env: SSE_ENV })
  await start($, w)
  await openGraph($, w)
  expect(w.subscriberWrites()).toEqual([])
  await settle(w, POLL)
  expect(w.spawns).toHaveLength(1)
  expect(lastLive(w)).toBe('sse')
})
