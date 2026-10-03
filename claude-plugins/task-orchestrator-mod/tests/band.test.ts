// Tests of the status line + AbovePrompt band (T4 ec1e2f91): the pure selection model as table-driven
// units, the AbovePrompt render mounted on terminal AND desktop, and the snapshot -> status/subscribe
// hooks driven through `$.state.set`. `$.state` is answered in memory on the test's `on`.
import type { On } from 'claude-code'
import { expect, test } from 'claude-code/testing'

import type { GateInfo, GraphNode, GraphSnapshot } from '../types'
import { bandModel, gateText, inFlight, truncate } from '../src/band/model.ts'

const PLUGIN = 'task-orchestrator-mod'
const ID = 'ec1e2f91-1d16-43dc-a9c9-f86aea28a050'

const node = (id: string, role: string, depth: number, parentId: string | null, title = `Title ${id}`): GraphNode => ({ id, parentId, title, role, depth })
const gate = (over: Partial<GateInfo> = {}): GateInfo => ({ canAdvance: false, phase: 'work', missing: [], required: 3, filled: 2, ...over })
const snap = (nodes: GraphNode[], gates: Record<string, GateInfo> = {}): GraphSnapshot => ({
  scopeId: 'root',
  rootId: 'root',
  nodes,
  edges: [],
  external: {},
  gates,
  takenAt: 1,
  truncated: false,
})

/** A feature container in work with one leaf work task under it. */
const oneLeaf = (): GraphSnapshot =>
  snap([node('root', 'work', 0, null), node(ID, 'work', 1, 'root', 'Status line band')], { [ID]: gate() })

// ── bandModel ───────────────────────────────────────────────────────────────────────────

test('model: a work container with work/review children picks the leaf work child', () => {
  const s = snap(
    [node('feat', 'work', 0, null), node('t-rev', 'review', 1, 'feat'), node('t-work', 'work', 1, 'feat'), node('t-queue', 'queue', 1, 'feat')],
    { 't-work': gate() },
  )
  expect(inFlight(s).map(n => n.id)).toEqual(['t-work', 't-rev'])
  expect(bandModel(s, false).band).toContain('t-work')
})

test('model: a work node holding only terminal/queue children is itself a leaf', () => {
  const s = snap([node('feat', 'work', 0, null), node('a', 'terminal', 1, 'feat'), node('b', 'queue', 1, 'feat')])
  expect(inFlight(s).map(n => n.id)).toEqual(['feat'])
})

test('model: deeper leaves first within a role, snapshot order breaks ties', () => {
  const s = snap([
    node('r', 'queue', 0, null),
    node('shallow', 'work', 1, 'r'),
    node('mid', 'queue', 1, 'r'),
    node('deep1', 'work', 2, 'mid'),
    node('deep2', 'work', 2, 'mid'),
  ])
  expect(inFlight(s).map(n => n.id)).toEqual(['deep1', 'deep2', 'shallow'])
})

test('model: two leaves show (+1) and the status carries it too', () => {
  const s = snap([node('a', 'work', 1, null), node('b', 'work', 1, null)])
  expect(bandModel(s, false).band).toMatch(/\(\+1\)$/)
  expect(bandModel(s, false).status).toMatch(/\(\+1\)$/)
})

test('model: required 3 / filled 2 reads 2/3 work notes; status is compact', () => {
  const m = bandModel(oneLeaf(), false)
  expect(m.band).toBe(`◉ ${ID.slice(0, 8)} Status line band — 2/3 work notes`)
  expect(m.status).toBe(`TO ◉ ${ID.slice(0, 8)} 2/3 work`)
})

test('model: required 0 gives the phase; no gate entry gives the role; canAdvance appends a check', () => {
  const n = node('x', 'review', 1, null)
  expect(gateText(n, gate({ required: 0, phase: 'review' }))).toBe('review')
  expect(gateText(n, undefined)).toBe('review')
  expect(gateText(n, gate({ canAdvance: true, filled: 3 }))).toBe('3/3 work notes ✓')
})

test('model: null, empty and nothing-in-flight snapshots give {}', () => {
  expect(bandModel(null, false)).toEqual({})
  expect(bandModel(snap([]), false)).toEqual({})
  expect(bandModel(snap([node('q', 'queue', 0, null), node('t', 'terminal', 1, 'q')]), false)).toEqual({})
})

test('model: hidden drops the band but keeps the status', () => {
  const m = bandModel(oneLeaf(), true)
  expect(m.band).toBeUndefined()
  expect(m.status).toBeDefined()
})

test('model: a long title is cut with an ellipsis to fit the width', () => {
  const s = snap([node('a', 'work', 0, null, 'x'.repeat(200))], { a: gate() })
  const band = bandModel(s, false, 60).band as string
  expect(band.length).toBeLessThanOrEqual(60 - 10)
  expect(band).toContain('…')
  expect(truncate('abcdef', 4)).toBe('abc…')
  expect(truncate('abc', 4)).toBe('abc')
})

// ── rendered band + hooks ───────────────────────────────────────────────────────────────

type Slot = { value: unknown; version: number }
type Rig = { state: Map<string, Slot>; sets: { key: string; value: unknown }[]; statuses: (string | undefined)[]; commands: string[] }

const slot = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`

/** In-memory `$.state` plus observers for state writes, `ui.status` and `command.register`, on the test's `on`. */
function rig(on: On, seed: Record<string, unknown> = {}): Rig {
  const r: Rig = { state: new Map(), sets: [], statuses: [], commands: [] }
  for (const [key, value] of Object.entries(seed)) r.state.set(`${PLUGIN}/${key}/`, { value, version: 1 })
  on('state.get', async (_$, e) => ({ value: r.state.get(slot(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slot(e as never)
    const cur = r.state.get(k)?.version ?? 0
    const ifVersion = (e as { ifVersion?: number }).ifVersion
    if (ifVersion !== undefined && ifVersion !== cur) return { value: { isSet: false, version: cur } } as never
    r.state.set(k, { value: (e as { value: unknown }).value, version: cur + 1 })
    r.sets.push({ key: (e as { key: string }).key, value: (e as { value: unknown }).value })

    return { value: { isSet: true, version: cur + 1 } } as never
  })
  on('ui.status', async (_$, e) => {
    r.statuses.push(e.text)

    return { value: undefined } as never
  })
  on('ui.render', async ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, { key: 'engine' }, 'engine') as never
  })
  on('command.register', async (_$, e) => {
    r.commands.push(e.name)

    return { value: undefined } as never
  })

  return r
}

const props = (over: Record<string, unknown> = {}) =>
  ({ hasSurvey: false, isWorking: false, maxRows: 6, bodyColumns: 100, scroll: { offset: 0, bodyRows: 6 }, view: {}, ...over }) as never

for (const surface of ['terminal', 'desktop'] as const) {
  test(`band on ${surface}: shows the in-flight item, yields to a survey and to narrow widths, Hide writes bandHidden`, async ($, on) => {
    const r = rig(on, { graphSnapshot: oneLeaf() })
    const mount = (over: Record<string, unknown> = {}) => $.ui.mount({ plugin: PLUGIN, surface, component: 'AbovePrompt', props: props(over) })

    const ui = await mount()
    expect((await ui.find({ type: 'Text', text: '◉' }))?.text).toBe(`◉ ${ID.slice(0, 8)} Status line band — 2/3 work notes`)
    expect(await ui.find({ key: 'hide' })).toBeDefined()
    await ui.press({ key: 'hide' })
    expect(r.sets.filter(s => s.key === 'bandHidden')).toEqual([{ key: 'bandHidden', value: true }])
    await ui.unmount()

    const survey = await mount({ hasSurvey: true })
    expect(await survey.find({ type: 'Text', text: '◉' })).toBeUndefined()
    await survey.unmount()

    const narrow = await mount({ bodyColumns: 30 })
    expect(await narrow.find({ type: 'Text', text: '◉' })).toBeUndefined()
    await narrow.unmount()
  })

  test(`band on ${surface}: no band once hidden`, async ($, on) => {
    rig(on, { graphSnapshot: oneLeaf(), bandHidden: true })
    const hidden = await $.ui.mount({ plugin: PLUGIN, surface, component: 'AbovePrompt', props: props() })
    expect(await hidden.find({ type: 'Text', text: '◉' })).toBeUndefined()
    await hidden.unmount()
  })

  test(`band on ${surface}: nothing in flight draws nothing`, async ($, on) => {
    rig(on, { graphSnapshot: snap([node('q', 'queue', 0, null)]) })
    const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'AbovePrompt', props: props() })
    expect(await ui.find({ type: 'Text', text: '◉' })).toBeUndefined()
    await ui.unmount()
  })
}

// The kit has no `state` noun on `$`, and a write graph-data raises from a timer callback does not reach the
// band's state.set hook here, so the sync path is driven by the dispatches that always run the whole
// chain (a prompt, a finished turn) over a seeded graphSnapshot.
test('a prompt syncs the status line, subscribes once and registers /to-band; a second prompt does not repeat them', async ($, on) => {
  const r = rig(on, { graphSnapshot: oneLeaf() })
  on('prompt.submit', async (_$, e) => ({ text: e.text }) as never)
  await $.prompt.submit({ text: 'hello' } as never)
  expect(r.statuses).toEqual([`TO ◉ ${ID.slice(0, 8)} 2/3 work`])
  expect(r.sets.filter(s => s.key === 'graphSubscribers')).toEqual([{ key: 'graphSubscribers', value: 1 }])
  expect(r.commands).toEqual(['to-band'])

  await $.prompt.submit({ text: 'again' } as never)
  expect(r.statuses).toHaveLength(2)
  expect(r.sets.filter(s => s.key === 'graphSubscribers')).toHaveLength(1)
  expect(r.sets.filter(s => s.key === 'bandSubscribed')).toHaveLength(1)
  expect(r.commands).toHaveLength(1)
})

test('a prompt with nothing in flight clears the status line', async ($, on) => {
  const r = rig(on, { graphSnapshot: snap([node('q', 'queue', 0, null)]) })
  on('prompt.submit', async (_$, e) => ({ text: e.text }) as never)
  await $.prompt.submit({ text: 'hello' } as never)
  expect(r.statuses).toEqual([undefined])
})

test('a hot-reloaded session already subscribed does not subscribe again', async ($, on) => {
  const r = rig(on, { graphSnapshot: oneLeaf(), bandSubscribed: true })
  on('prompt.submit', async (_$, e) => ({ text: e.text }) as never)
  await $.prompt.submit({ text: 'hello' } as never)
  expect(r.sets.filter(s => s.key === 'graphSubscribers')).toHaveLength(0)
})

test('/to-band toggles bandHidden and says which way', async ($, on) => {
  const r = rig(on)
  const first = await $.command.run({ command: 'to-band', args: '' } as never)
  expect(first.text).toMatch(/hidden/)
  expect(r.state.get(`${PLUGIN}/bandHidden/`)?.value).toBe(true)
  const second = await $.command.run({ command: 'to-band', args: '' } as never)
  expect(second.text).toMatch(/shown/)
  expect(r.state.get(`${PLUGIN}/bandHidden/`)?.value).toBe(false)
})
