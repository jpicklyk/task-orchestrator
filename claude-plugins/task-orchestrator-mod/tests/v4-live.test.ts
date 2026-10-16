// Live stream of the graph data layer on the 4.0 event set (item 5dbd8275, scenarios S7, S8, S9; AC4 of the
// frozen task-scope).
//
// Authored blind to the implementation. Oracles:
//  - current/docs/api-rest.md section 21: the event types and their payload fields (`runId`, `decisionId`, `class`,
//    `severity`, `rootId`), frame ids are the events seq (above 10^12), `Last-Event-ID` resume, `sync.lost` as the first
//    frame of an unreplayable resume with the cause in `reason`, and the sentinel id to resume from.
//  - task-scope AC4 and D6: which event kinds refresh, which only update the run summary, the per-generation string
//    cursor, `applyDecisionEvent` / `applyRunEvent` / `decisionGlyph` / `runSummary`.
import { expect, test } from 'claude-code/testing'

import type { GraphDecision, GraphRun } from '../types'
import type { GraphIo } from '../src/graph-data/io.ts'
import { SSE_TYPES, createSseParser, curlRequest, resetLiveState, restartLive, syncLive } from '../src/graph-data/live.ts'
import { resetRefreshState } from '../src/graph-data/refresh.ts'
import { resetRemoteState } from '../src/graph-data/remote.ts'
import { DECISION_CAP, applyDecisionEvent, applyRunEvent, decisionGlyph, runSummary } from '../src/graph-data/runs.ts'
import { shortId } from '../src/shared/to-client.ts'

const ROOT = 'a0000000-0000-4000-8000-000000000001'
const OTHER_ROOT = 'c0000000-0000-4000-8000-000000000002'
const ITEM = 'b1111111-0000-4000-8000-000000000001'
const ITEM2 = 'b2222222-0000-4000-8000-000000000002'
const RUN = 'd1111111-0000-4000-8000-000000000001'
const RUN2 = 'd2222222-0000-4000-8000-000000000002'
const CONFIG = `project:\n  rootId: ${ROOT}\n`

// --- S9: runs.ts (pure) -------------------------------------------------------------------

const raised = (over: Record<string, unknown> = {}) => ({ id: 1_000_000_000_001, event: 'decision.raised', itemId: ITEM, rootId: ROOT, decisionId: 'dec-1', class: 'deviation', severity: 'high', ...over })

test('S9 applyDecisionEvent: decision.raised with an item sets the entry under the item short id', () => {
  const map = applyDecisionEvent({}, 'decision.raised', raised())
  expect(map).toEqual({ [shortId(ITEM)]: { decisionId: 'dec-1', severity: 'high', class: 'deviation' } })
  // an upper-case UUID lands under the same lower-case short id
  expect(Object.keys(applyDecisionEvent({}, 'decision.raised', raised({ itemId: ITEM.toUpperCase() })))).toEqual([shortId(ITEM)])
})

test('S9 applyDecisionEvent: severity and class are optional members of the entry', () => {
  const map = applyDecisionEvent({}, 'decision.raised', { event: 'decision.raised', itemId: ITEM, decisionId: 'dec-2' })
  expect(map[shortId(ITEM)]).toEqual({ decisionId: 'dec-2' })
})

test('S9 applyDecisionEvent: a raise without an item changes nothing and returns the same object', () => {
  const start = { [shortId(ITEM2)]: { decisionId: 'x' } }
  expect(applyDecisionEvent(start, 'decision.raised', raised({ itemId: undefined }))).toBe(start)
  expect(applyDecisionEvent(start, 'decision.raised', undefined)).toBe(start)
  expect(applyDecisionEvent(start, 'decision.raised', null)).toBe(start)
})

test('S9 applyDecisionEvent: answered and withdrawn remove the entry of that item when the decision id matches', () => {
  for (const name of ['decision.answered', 'decision.withdrawn']) {
    const start = applyDecisionEvent({}, 'decision.raised', raised())
    const gone = applyDecisionEvent(start, name, raised({ event: name }))
    expect(gone).toEqual({})
    expect(start[shortId(ITEM)]).toBeDefined()
  }
})

test('S9 applyDecisionEvent: answered with another decision id, acknowledged, and an unknown item change nothing (same object)', () => {
  const start = applyDecisionEvent({}, 'decision.raised', raised())
  expect(applyDecisionEvent(start, 'decision.answered', raised({ decisionId: 'other-decision' }))).toBe(start)
  expect(applyDecisionEvent(start, 'decision.withdrawn', raised({ decisionId: 'other-decision' }))).toBe(start)
  expect(applyDecisionEvent(start, 'decision.acknowledged', raised())).toBe(start)
  expect(applyDecisionEvent(start, 'decision.answered', raised({ itemId: ITEM2 }))).toBe(start)
  expect(applyDecisionEvent(start, 'decision.answered', raised({ itemId: undefined }))).toBe(start)
})

test('S9 applyDecisionEvent: raising the identical decision again returns the same object; a newer decision on the item replaces it', () => {
  const start = applyDecisionEvent({}, 'decision.raised', raised())
  expect(applyDecisionEvent(start, 'decision.raised', raised())).toBe(start)
  const replaced = applyDecisionEvent(start, 'decision.raised', raised({ decisionId: 'dec-9', severity: 'low' }))
  expect(replaced[shortId(ITEM)]).toEqual({ decisionId: 'dec-9', severity: 'low', class: 'deviation' })
})

test('S9 applyDecisionEvent: the map is capped at 200 entries, the oldest key out', () => {
  expect(DECISION_CAP).toBe(200)
  const itemOf = (i: number) => `${(0x10000000 + i).toString(16)}-0000-4000-8000-000000000000`
  let map: Record<string, GraphDecision> = {}
  for (let i = 0; i < DECISION_CAP; i++) map = applyDecisionEvent(map, 'decision.raised', raised({ itemId: itemOf(i), decisionId: `d${i}` }))
  expect(Object.keys(map)).toHaveLength(200)
  map = applyDecisionEvent(map, 'decision.raised', raised({ itemId: itemOf(200), decisionId: 'd200' }))
  expect(Object.keys(map)).toHaveLength(200)
  expect(map[shortId(itemOf(0))]).toBeUndefined()
  expect(map[shortId(itemOf(1))]).toBeDefined()
  expect(map[shortId(itemOf(200))]).toEqual({ decisionId: 'd200', severity: 'high', class: 'deviation' })
})

test('S9 applyDecisionEvent: does not mutate the map it was given', () => {
  const start: Record<string, GraphDecision> = {}
  const next = applyDecisionEvent(start, 'decision.raised', raised())
  expect(start).toEqual({})
  expect(next).not.toBe(start)
})

const runEvent = (name: string, over: Record<string, unknown> = {}) => ({ id: 1_000_000_000_002, event: name, runId: RUN, rootId: ROOT, ...over })

test('S9 applyRunEvent: opened and resumed set open, paused sets paused, ended deletes', () => {
  let map: Record<string, GraphRun> = {}
  map = applyRunEvent(map, 'run.opened', runEvent('run.opened'), ROOT)
  expect(map).toEqual({ [RUN]: { status: 'open' } })
  map = applyRunEvent(map, 'run.paused', runEvent('run.paused'), ROOT)
  expect(map).toEqual({ [RUN]: { status: 'paused' } })
  map = applyRunEvent(map, 'run.resumed', runEvent('run.resumed'), ROOT)
  expect(map).toEqual({ [RUN]: { status: 'open' } })
  map = applyRunEvent(map, 'run.ended', runEvent('run.ended'), ROOT)
  expect(map).toEqual({})
})

test('S9 applyRunEvent: updated and marked change nothing, and neither do replays (same object)', () => {
  const open = applyRunEvent({}, 'run.opened', runEvent('run.opened'), ROOT)
  expect(applyRunEvent(open, 'run.updated', runEvent('run.updated'), ROOT)).toBe(open)
  expect(applyRunEvent(open, 'run.marked', runEvent('run.marked'), ROOT)).toBe(open)
  expect(applyRunEvent(open, 'run.opened', runEvent('run.opened'), ROOT)).toBe(open)
  const empty: Record<string, GraphRun> = {}
  expect(applyRunEvent(empty, 'run.ended', runEvent('run.ended'), ROOT)).toBe(empty)
})

test('S9 applyRunEvent: only events of the project root count; the comparison is on the short id', () => {
  const empty: Record<string, GraphRun> = {}
  expect(applyRunEvent(empty, 'run.opened', runEvent('run.opened', { rootId: OTHER_ROOT }), ROOT)).toBe(empty)
  expect(applyRunEvent(empty, 'run.opened', runEvent('run.opened', { rootId: undefined }), ROOT)).toBe(empty)
  expect(applyRunEvent(empty, 'run.opened', runEvent('run.opened'), null)).toBe(empty)
  expect(applyRunEvent(empty, 'run.opened', runEvent('run.opened', { rootId: ROOT.toUpperCase() }), ROOT)).toEqual({ [RUN]: { status: 'open' } })
  expect(applyRunEvent(empty, 'run.opened', runEvent('run.opened', { rootId: shortId(ROOT) }), ROOT)).toEqual({ [RUN]: { status: 'open' } })
})

test('S9 applyRunEvent: two runs are tracked separately and do not mutate the input', () => {
  const one = applyRunEvent({}, 'run.opened', runEvent('run.opened'), ROOT)
  const two = applyRunEvent(one, 'run.opened', runEvent('run.opened', { runId: RUN2 }), ROOT)
  expect(two).toEqual({ [RUN]: { status: 'open' }, [RUN2]: { status: 'open' } })
  expect(one).toEqual({ [RUN]: { status: 'open' } })
  expect(applyRunEvent(two, 'run.ended', runEvent('run.ended'), ROOT)).toEqual({ [RUN2]: { status: 'open' } })
})

test('S9 decisionGlyph: a question mark for an item with an open decision, else empty; ids compare by short id', () => {
  const map = applyDecisionEvent({}, 'decision.raised', raised())
  expect(decisionGlyph(shortId(ITEM), map)).toBe('?')
  expect(decisionGlyph(ITEM, map)).toBe('?')
  expect(decisionGlyph(ITEM.toUpperCase(), map)).toBe('?')
  expect(decisionGlyph(shortId(ITEM2), map)).toBe('')
  expect(decisionGlyph(shortId(ITEM), {})).toBe('')
})

test('S9 runSummary: empty for no runs, else open and paused counts in that order', () => {
  expect(runSummary({})).toBe('')
  expect(runSummary({ a: { status: 'open' } })).toBe('runs: 1 open')
  expect(runSummary({ a: { status: 'open' }, b: { status: 'open' }, c: { status: 'open' } })).toBe('runs: 3 open')
  expect(runSummary({ a: { status: 'paused' } })).toBe('runs: 1 paused')
  expect(runSummary({ a: { status: 'paused' }, b: { status: 'paused' } })).toBe('runs: 2 paused')
  expect(runSummary({ a: { status: 'paused' }, b: { status: 'open' }, c: { status: 'open' } })).toBe('runs: 2 open, 1 paused')
})

// --- SSE parser and curl request ----------------------------------------------------------

test('S8 createSseParser: the frame id: line is passed to onEvent as a string, in either order with event:', () => {
  const seen: [string, unknown, string | undefined][] = []
  const feed = createSseParser((name, data, id) => seen.push([name, data, id]))
  feed('id: 1000000000042\nevent: item.updated\ndata: {"a":1}\n\n')
  feed('event: item.advanced\nid: 1000000000043\ndata: {}\n\n')
  feed('event: note.upserted\r\nid: 1000000000044\r\ndata: {}\r\n\r\n')
  feed('event: item.created\ndata: {}\n\n')
  expect(seen).toEqual([
    ['item.updated', { a: 1 }, '1000000000042'],
    ['item.advanced', {}, '1000000000043'],
    ['note.upserted', {}, '1000000000044'],
    ['item.created', {}, undefined],
  ])
})

test('S8 createSseParser: an id past 2^53 stays a string, digit for digit; a split chunk keeps the id with its frame', () => {
  const seen: [string, string | undefined][] = []
  const feed = createSseParser((name, _data, id) => seen.push([name, id]))
  feed('id: 9007199254740993123\nevent: sync.lost\ndata: {"reason":"unknown_event_id"}\n\n')
  feed('id: 123')
  feed('4567\nevent: item.upd')
  feed('ated\ndata: {}\n\n')
  expect(seen).toEqual([
    ['sync.lost', '9007199254740993123'],
    ['item.updated', '1234567'],
  ])
  expect(typeof seen[0]?.[1]).toBe('string')
})

const headerOf = (argv: readonly string[]) => {
  const i = argv.findIndex((a, n) => a === '-H' && argv[n + 1]?.startsWith('Last-Event-ID'))

  return i < 0 ? undefined : argv[i + 1]
}

test('S8 curlRequest: a digit cursor becomes -H "Last-Event-ID: <id>"; anything else sends no header', () => {
  expect(headerOf(curlRequest('http://h/x', undefined, '12').argv)).toBe('Last-Event-ID: 12')
  expect(headerOf(curlRequest('http://h/x', 'tok', '1000000000042').argv)).toBe('Last-Event-ID: 1000000000042')
  expect(headerOf(curlRequest('http://h/x', undefined, '9007199254740993123').argv)).toBe('Last-Event-ID: 9007199254740993123')
  for (const bad of [undefined, '', 'abc', '12a', 'a12', '1 2', '-1', '1.5', '0x10', '12\n']) {
    expect(headerOf(curlRequest('http://h/x', undefined, bad).argv)).toBeUndefined()
  }
})

test('S8 curlRequest: the cursor header does not move the token out of the input or change the url', () => {
  const r = curlRequest('http://h/x', 'secret', '77')
  expect(r.argv.join(' ')).not.toContain('secret')
  expect(r.input).toBe('header = "Authorization: Bearer secret"\n')
  expect(r.argv).toContain('http://h/x')
})

test('S7 SSE_TYPES: the 21 domain event types, without the control events or the ownership audit event', () => {
  expect([...SSE_TYPES]).toHaveLength(21)
  for (const t of ['run.opened', 'run.updated', 'run.paused', 'run.resumed', 'run.ended', 'run.marked', 'seat.ended', 'decision.raised', 'decision.acknowledged', 'decision.answered', 'decision.withdrawn']) {
    expect(SSE_TYPES).toContain(t)
  }
  for (const t of ['sync.lost', 'auth.expired', 'note.ownership_transferred']) expect(SSE_TYPES).not.toContain(t)
  expect(new Set(SSE_TYPES).size).toBe(SSE_TYPES.length)
})

// --- a live rig: manual clock, scripted streams -------------------------------------------

declare const setTimeout: (fn: () => void, ms: number) => unknown
const flush = () => new Promise<void>(resolve => setTimeout(resolve, 0))

type Timer = { due: number; fn: () => void; every?: number; dead: boolean }
type Spawn = { argv: readonly string[]; input?: string }
/** A script for the nth spawn (1-based): frames to emit, then `end` (stream ends) or stay open. */
type Script = { frames: string[]; end: boolean }

const emptyOverview = { ok: true, data: { anchor: { id: 'a0000000', title: 'Project' }, items: [], page: { total: 0, truncated: false } } }

function rig(scripts: Script[] = []) {
  resetRefreshState()
  resetLiveState()
  resetRemoteState()
  const timers: Timer[] = []
  let now = 1_000
  const spawns: Spawn[] = []
  const reads: Record<string, unknown>[] = []
  const decisions: { value: Record<string, GraphDecision> } = { value: {} }
  const runs: { value: Record<string, GraphRun> } = { value: {} }
  const pushes: (() => void)[] = []
  const live: { queue: string[]; wake: () => void } = { queue: [], wake: () => undefined }
  const arm = (ms: number, fn: () => void, every?: number): Timer => {
    const t: Timer = { due: now + ms, fn, every, dead: false }
    timers.push(t)

    return t
  }
  const state = { scope: null as string | null, status: { refreshing: false, liveSource: 'none' } as import('../types').GraphStatus }
  const io: GraphIo = {
    callTool: async (_tool, args) => {
      reads.push(args)

      return emptyOverview.data
    },
    readFile: async path => {
      if (path === '.taskorchestrator/config.yaml') return CONFIG
      throw new Error(`ENOENT ${path}`)
    },
    now: async () => now,
    after: (ms, fn) => {
      const t = arm(ms, fn)

      return { cancel: () => void (t.dead = true) }
    },
    every: (ms, fn) => {
      const t = arm(ms, fn, ms)

      return { cancel: () => void (t.dead = true) }
    },
    sleep: (ms, signal) =>
      new Promise<void>((resolve, reject) => {
        const t = arm(ms, resolve)
        signal?.addEventListener('abort', () => {
          t.dead = true
          reject(new Error('aborted'))
        })
      }),
    spawn: request => {
      spawns.push(request)
      const script = scripts[spawns.length - 1]
      async function* gen(): AsyncGenerator<{ stream: 'stdout' | 'stderr'; text: string }, { code: number | null; signal: string | null }> {
        for (const f of script?.frames ?? []) yield { stream: 'stdout', text: f }
        if (script?.end === true) return { code: 0, signal: null }
        for (;;) {
          while (live.queue.length === 0) await new Promise<void>(resolve => (live.wake = resolve))
          yield { stream: 'stdout', text: live.queue.shift() as string }
        }
      }

      return gen()
    },
    readScope: async () => state.scope,
    setSnapshot: async () => undefined,
    updateStatus: async change => void (state.status = change(state.status)),
    updateDecisions: async step => void (decisions.value = step(decisions.value)),
    updateRuns: async step => void (runs.value = step(runs.value)),
    envApiUrl: async () => 'http://localhost:3001',
    envApiToken: async () => undefined,
    homeDir: async () => undefined,
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
  const push = (text: string) => {
    live.queue.push(text)
    live.wake()
  }
  void pushes

  return { io, advance, push, spawns, reads, decisions, runs, state, refreshes: () => reads.filter(a => a.select === 'overview').length }
}

let seq = 1_000_000_000_100
const frame = (event: string, data: Record<string, unknown> = {}, id: string | number = ++seq) => `id: ${id}\nevent: ${event}\ndata: ${JSON.stringify({ event, rootId: ROOT, ...data })}\n\n`

// --- S7: dispatch -------------------------------------------------------------------------

test('S7: item, note, dependency, scope, seat and decision events each give one debounced refresh (300ms)', async () => {
  const names = [
    'item.created', 'item.updated', 'item.deleted', 'item.advanced', 'note.upserted', 'note.deleted', 'dependency.added', 'dependency.removed',
    'scope.entered', 'scope.left', 'seat.ended', 'decision.raised', 'decision.acknowledged', 'decision.answered', 'decision.withdrawn',
  ]
  for (const name of names) {
    const r = rig()
    syncLive(r.io, 1)
    await r.advance(0)
    const base = r.refreshes()
    r.push(frame(name, { itemId: ITEM, runId: RUN, decisionId: 'dec-1' }))
    await r.advance(299)
    expect([name, r.refreshes() - base]).toEqual([name, 0])
    await r.advance(1)
    expect([name, r.refreshes() - base]).toEqual([name, 1])
    syncLive(r.io, 0)
    await r.advance(0)
  }
})

test('S7: run events update the run summary only and never refresh the snapshot', async () => {
  for (const name of ['run.opened', 'run.updated', 'run.paused', 'run.resumed', 'run.ended', 'run.marked']) {
    const r = rig()
    syncLive(r.io, 1)
    await r.advance(0)
    const base = r.refreshes()
    r.push(frame(name, { runId: RUN }))
    await r.advance(5_000)
    expect([name, r.refreshes() - base]).toEqual([name, 0])
    syncLive(r.io, 0)
    await r.advance(0)
  }
})

test('S7: run events drive graphRuns: opened, paused, resumed, ended; another root\'s runs are ignored', async () => {
  const r = rig()
  syncLive(r.io, 1)
  await r.advance(0)
  r.push(frame('run.opened', { runId: RUN }))
  await r.advance(0)
  expect(r.runs.value).toEqual({ [RUN]: { status: 'open' } })
  r.push(frame('run.paused', { runId: RUN }))
  await r.advance(0)
  expect(r.runs.value).toEqual({ [RUN]: { status: 'paused' } })
  r.push(frame('run.opened', { runId: RUN2, rootId: OTHER_ROOT }))
  await r.advance(0)
  expect(r.runs.value).toEqual({ [RUN]: { status: 'paused' } })
  r.push(frame('run.resumed', { runId: RUN }))
  await r.advance(0)
  expect(r.runs.value).toEqual({ [RUN]: { status: 'open' } })
  r.push(frame('run.ended', { runId: RUN }))
  await r.advance(0)
  expect(r.runs.value).toEqual({})
  syncLive(r.io, 0)
  await r.advance(0)
})

test('S7: decision events drive graphDecisions: raised sets, answered with the same decision removes, acknowledged keeps', async () => {
  const r = rig()
  syncLive(r.io, 1)
  await r.advance(0)
  r.push(frame('decision.raised', { itemId: ITEM, decisionId: 'dec-1', class: 'deviation', severity: 'high' }))
  await r.advance(0)
  expect(r.decisions.value).toEqual({ [shortId(ITEM)]: { decisionId: 'dec-1', severity: 'high', class: 'deviation' } })
  r.push(frame('decision.acknowledged', { itemId: ITEM, decisionId: 'dec-1' }))
  await r.advance(0)
  expect(Object.keys(r.decisions.value)).toEqual([shortId(ITEM)])
  r.push(frame('decision.answered', { itemId: ITEM, decisionId: 'dec-other' }))
  await r.advance(0)
  expect(Object.keys(r.decisions.value)).toEqual([shortId(ITEM)])
  r.push(frame('decision.answered', { itemId: ITEM, decisionId: 'dec-1' }))
  await r.advance(0)
  expect(r.decisions.value).toEqual({})
  syncLive(r.io, 0)
  await r.advance(0)
})

test('S7: sync.lost, whatever the reason, refreshes at once (no debounce); an item event waits the 300ms', async () => {
  for (const reason of ['queue_overflow', 'buffer_evicted', 'unknown_event_id']) {
    const r = rig()
    syncLive(r.io, 1)
    await r.advance(0)
    const base = r.refreshes()
    r.push(frame('sync.lost', { reason }))
    await r.advance(0)
    expect([reason, r.refreshes() - base]).toEqual([reason, 1])
    syncLive(r.io, 0)
    await r.advance(0)
  }
  const r = rig()
  syncLive(r.io, 1)
  await r.advance(0)
  const base = r.refreshes()
  r.push(frame('item.updated', { itemId: ITEM }))
  await r.advance(0)
  expect(r.refreshes() - base).toBe(0)
  syncLive(r.io, 0)
  await r.advance(0)
})

// --- S8: the Last-Event-ID cursor ---------------------------------------------------------

const frameWith = (id: string, event = 'item.updated', data = '{}') => `id: ${id}\nevent: ${event}\ndata: ${data}\n\n`

test('S8: the first connection carries no cursor; after a stream ends the reconnect carries the last id seen', async () => {
  const r = rig([{ frames: [frameWith('12')], end: true }, { frames: [], end: false }])
  syncLive(r.io, 1)
  await r.advance(0)
  expect(r.spawns).toHaveLength(1)
  expect(headerOf(r.spawns[0]!.argv)).toBeUndefined()
  await r.advance(10_000)
  expect(r.spawns).toHaveLength(2)
  expect(headerOf(r.spawns[1]!.argv)).toBe('Last-Event-ID: 12')
  syncLive(r.io, 0)
  await r.advance(0)
})

test('S8: the cursor is the latest id; a frame without an id leaves it alone', async () => {
  const r = rig([{ frames: [frameWith('12'), ': keepalive\n\n', frameWith('15'), 'event: item.updated\ndata: {}\n\n'], end: true }, { frames: [], end: false }])
  syncLive(r.io, 1)
  await r.advance(10_000)
  expect(r.spawns).toHaveLength(2)
  expect(headerOf(r.spawns[1]!.argv)).toBe('Last-Event-ID: 15')
  syncLive(r.io, 0)
  await r.advance(0)
})

test('S8: a sync.lost frame refreshes at once and its own id replaces the cursor for the next reconnect', async () => {
  const r = rig([
    { frames: [frameWith('12')], end: true },
    { frames: [frameWith('1000000000000', 'sync.lost', '{"reason":"unknown_event_id"}')], end: true },
    { frames: [], end: false },
  ])
  syncLive(r.io, 1)
  await r.advance(0)
  await r.advance(2_500)
  expect(r.spawns.length).toBeGreaterThanOrEqual(2)
  expect(headerOf(r.spawns[1]!.argv)).toBe('Last-Event-ID: 12')
  expect(r.refreshes()).toBeGreaterThanOrEqual(1)
  await r.advance(10_000)
  expect(r.spawns).toHaveLength(3)
  expect(headerOf(r.spawns[2]!.argv)).toBe('Last-Event-ID: 1000000000000')
  syncLive(r.io, 0)
  await r.advance(0)
})

test('S8: an id past 2^53 is resumed verbatim, not rounded through a number', async () => {
  const big = '9007199254740993123'
  const r = rig([{ frames: [frameWith(big)], end: true }, { frames: [], end: false }])
  syncLive(r.io, 1)
  await r.advance(10_000)
  expect(headerOf(r.spawns[1]!.argv)).toBe(`Last-Event-ID: ${big}`)
  syncLive(r.io, 0)
  await r.advance(0)
})

test('S8: ids that are not all digits never become the cursor', async () => {
  for (const bad of ['abc', '12a', '1 2', '-5', '']) {
    const r = rig([{ frames: [frameWith(bad)], end: true }, { frames: [], end: false }])
    syncLive(r.io, 1)
    await r.advance(10_000)
    expect([bad, headerOf(r.spawns[1]!.argv)]).toEqual([bad, undefined])
    syncLive(r.io, 0)
    await r.advance(0)
  }
  // a bad id after a good one leaves the good one in place
  const r = rig([{ frames: [frameWith('5'), frameWith('abc')], end: true }, { frames: [], end: false }])
  syncLive(r.io, 1)
  await r.advance(10_000)
  expect(headerOf(r.spawns[1]!.argv)).toBe('Last-Event-ID: 5')
  syncLive(r.io, 0)
  await r.advance(0)
})

test('S8: a new generation starts with no cursor (restartLive, a stop and start, resetLiveState)', async () => {
  // restartLive
  let r = rig([{ frames: [frameWith('12')], end: false }, { frames: [], end: false }])
  syncLive(r.io, 1)
  await r.advance(0)
  restartLive(r.io, 1)
  await r.advance(0)
  expect(r.spawns.length).toBeGreaterThanOrEqual(2)
  expect(headerOf(r.spawns[r.spawns.length - 1]!.argv)).toBeUndefined()
  syncLive(r.io, 0)
  await r.advance(0)
  // stop and start
  r = rig([{ frames: [frameWith('12')], end: false }, { frames: [], end: false }])
  syncLive(r.io, 1)
  await r.advance(0)
  syncLive(r.io, 0)
  await r.advance(0)
  syncLive(r.io, 1)
  await r.advance(0)
  expect(r.spawns).toHaveLength(2)
  expect(headerOf(r.spawns[1]!.argv)).toBeUndefined()
  syncLive(r.io, 0)
  await r.advance(0)
  // resetLiveState then a new start
  r = rig([{ frames: [frameWith('12')], end: false }, { frames: [], end: false }])
  syncLive(r.io, 1)
  await r.advance(0)
  resetLiveState()
  syncLive(r.io, 1)
  await r.advance(0)
  expect(headerOf(r.spawns[r.spawns.length - 1]!.argv)).toBeUndefined()
  syncLive(r.io, 0)
  await r.advance(0)
})
