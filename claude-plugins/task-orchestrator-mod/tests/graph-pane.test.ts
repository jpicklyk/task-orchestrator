// Tests of the /to-graph pane (T3 a47dcd6f): the pure layout, collapse and renderers as units, and the
// Pane mounted on terminal AND desktop over an in-memory `$.state` (the test kit has no state noun).
import type { On } from 'claude-code'
import { expect, test } from 'claude-code/testing'

import type { GateInfo, GraphEdge, GraphNode, GraphSnapshot, GraphStatus } from '../types'
import { READ_TOOLS } from '../src/graph-pane/index.ts'
import { WRITE_TOOLS } from '../src/graph-data/index.ts'
import { collapse } from '../src/graph-pane/collapse.ts'
import { countCrossings, layout } from '../src/graph-pane/layout.ts'
import { formatDetail, isDegraded, parseScopeArg, sideLabel, sideList } from '../src/graph-pane/pane-model.ts'
import { SVG_LIMIT, esc, renderSvg } from '../src/graph-pane/render-svg.ts'
import { renderText } from '../src/graph-pane/render-text.ts'
import { dagOf, phaseText } from '../src/graph-pane/shared.ts'
import type { GraphView } from '../src/graph-pane/shared.ts'

const PLUGIN = 'task-orchestrator-mod'

const node = (id: string, role: string, depth: number, parentId: string | null, title = `T ${id}`, statusLabel?: string): GraphNode => ({
  id,
  parentId,
  title,
  role,
  depth,
  ...(statusLabel !== undefined ? { statusLabel } : {}),
})
const blocks = (from: string, to: string): GraphEdge => ({ from, to, type: 'BLOCKS' })
const gate = (over: Partial<GateInfo> = {}): GateInfo => ({ canAdvance: false, phase: 'work', missing: ['implementation-notes'], required: 3, filled: 2, ...over })
const view = (nodes: GraphNode[], edges: GraphEdge[] = [], extra: Partial<GraphSnapshot> = {}): GraphView => ({
  scopeId: nodes[0]?.id ?? null,
  rootId: 'root',
  nodes,
  edges,
  external: {},
  gates: {},
  takenAt: 1,
  truncated: false,
  ...extra,
})
const lay = (v: GraphView) => {
  const dag = dagOf(v)

  return layout(dag.nodes, dag.edges)
}

// ── layout ──────────────────────────────────────────────────────────────────────────────

test('layout: a 3-chain plus a fork layers by longest path', () => {
  const nodes = ['a', 'b', 'c', 'd', 'e'].map(id => node(id, 'queue', 1, null))
  const l = layout(nodes, [blocks('a', 'b'), blocks('b', 'c'), blocks('a', 'd'), blocks('d', 'c'), blocks('a', 'e')])
  expect(l.pos.a?.layer).toBe(0)
  expect(l.pos.b?.layer).toBe(1)
  expect(l.pos.d?.layer).toBe(1)
  expect(l.pos.e?.layer).toBe(1)
  expect(l.pos.c?.layer).toBe(2)
  expect(l.layers).toHaveLength(3)
})

test('layout: a 2-cycle terminates, keeps every node and records one back edge', () => {
  const nodes = [node('a', 'queue', 1, null), node('b', 'queue', 1, null)]
  const l = layout(nodes, [blocks('a', 'b'), blocks('b', 'a')])
  expect(Object.keys(l.pos).sort()).toEqual(['a', 'b'])
  expect(l.backEdges).toHaveLength(1)
  expect(l.pos.a?.layer).not.toBe(l.pos.b?.layer)
})

test('layout: a self edge and an edge to an unknown id are ignored; RELATES_TO does not layer', () => {
  const nodes = [node('a', 'queue', 1, null), node('b', 'queue', 1, null)]
  const l = layout(nodes, [blocks('a', 'a'), blocks('a', 'zzz'), { from: 'a', to: 'b', type: 'RELATES_TO' }])
  expect(l.layers).toHaveLength(1)
})

test('layout: deterministic across runs and across input edge order', () => {
  const nodes = ['a', 'b', 'c', 'd', 'e', 'f'].map((id, i) => node(id, 'queue', 1, i % 2 === 0 ? 'p' : 'q', `Title ${6 - i}`))
  const edges = [blocks('a', 'd'), blocks('b', 'e'), blocks('c', 'f'), blocks('a', 'e')]
  const first = layout(nodes, edges)
  expect(layout(nodes, edges)).toEqual(first)
  expect(layout(nodes, [...edges].reverse()).layers).toEqual(first.layers)
})

test('layout: the sweeps reduce a crossing to zero', () => {
  // a->y, b->x with x listed before y: the naive order crosses.
  const nodes = [node('a', 'queue', 1, null, 'a'), node('b', 'queue', 1, null, 'b'), node('x', 'queue', 1, null, 'x'), node('y', 'queue', 1, null, 'y')]
  const edges = [blocks('a', 'y'), blocks('b', 'x')]
  const l = layout(nodes, edges)
  expect(countCrossings(l, edges)).toBe(0)
})

test('layout: ties keep siblings of one parent adjacent', () => {
  const nodes = [node('s1', 'queue', 2, 'P1', 'z'), node('t1', 'queue', 2, 'P2', 'a'), node('s2', 'queue', 2, 'P1', 'b'), node('t2', 'queue', 2, 'P2', 'c')]
  const l = layout(nodes, [])
  const parents = (l.layers[0] ?? []).map(id => nodes.find(n => n.id === id)?.parentId)
  expect(parents).toEqual(['P1', 'P1', 'P2', 'P2'])
})

// ── collapse ────────────────────────────────────────────────────────────────────────────

const deep = (): GraphView => {
  const nodes = [
    node('root', 'work', 0, null, 'Root'),
    node('cont', 'work', 1, 'root', 'Container'),
    node('f1', 'work', 2, 'cont', 'Feature 1'),
    node('f2', 'queue', 2, 'cont', 'Feature 2'),
    node('t1', 'work', 3, 'f1'),
    node('t2', 'queue', 3, 'f1'),
    node('t3', 'queue', 3, 'f2'),
    node('s1', 'queue', 4, 't1'),
  ]

  return view(nodes, [blocks('t1', 't3'), blocks('t2', 't3'), blocks('s1', 't3'), blocks('t1', 't2')])
}

test('collapse: root scope keeps depth <= 2 with roll-ups and lifted, deduped edges', () => {
  const c = collapse(deep(), true)
  expect(c.nodes.map(n => n.id)).toEqual(['root', 'cont', 'f1', 'f2'])
  expect(c.rollups?.f1).toEqual({ count: 3, byRole: { work: 1, queue: 2 } })
  expect(c.rollups?.f2).toEqual({ count: 1, byRole: { queue: 1 } })
  // t1/t2/s1 -> t3 all lift to f1 -> f2 once; t1 -> t2 is a self loop and drops.
  expect(c.edges).toEqual([blocks('f1', 'f2')])
})

test('collapse: a non-root scope passes through untouched', () => {
  const v = deep()
  expect(collapse(v, false)).toBe(v)
})

// ── renderSvg ───────────────────────────────────────────────────────────────────────────

test('svg: background rect first, role glyphs and colors, tooltips with missing notes', () => {
  const nodes = [
    node('q', 'queue', 1, null, 'Q'),
    node('w', 'work', 1, null, 'W'),
    node('r', 'review', 1, null, 'R'),
    node('b', 'blocked', 1, null, 'B'),
    node('d', 'terminal', 1, null, 'D'),
    node('x', 'terminal', 1, null, 'X', 'cancelled'),
  ]
  const v = view(nodes, [blocks('w', 'r')], { gates: { w: gate({ missing: ['alpha-note', 'beta-note'] }) } })
  const svg = renderSvg(lay(v), v) as string
  expect(svg.startsWith('<svg')).toBe(true)
  expect(svg.split('\n')[1]).toMatch(/^<rect width="\d+" height="\d+" fill="#1e1e1e"\/>$/)
  for (const glyph of ['○', '◉', '⊘', '✓', '—']) expect(svg).toContain(glyph)
  for (const color of ['#6b7280', '#b45309', '#2563eb', '#dc2626', '#15803d', '#4b5563']) expect(svg).toContain(color)
  expect(svg).toContain('<title>')
  expect(svg).toContain('missing: alpha-note, beta-note')
  expect(svg).toContain('marker-end')
  expect((renderSvg(lay(v), v, { theme: 'light' }) as string).split('\n')[1]).toContain('fill="#ffffff"')
})

test('svg: < & and quotes in titles are escaped', () => {
  const v = view([node('a', 'queue', 1, null, 'A <b> & "c" \u0001')])
  const svg = renderSvg(lay(v), v) as string
  expect(svg).toContain('A &lt;b&gt; &amp; &quot;c&quot;')
  expect(svg).not.toContain('<b>')
  expect(svg).not.toContain('\u0001')
  expect(esc(`'`)).toBe('&apos;')
})

test('svg: an external blocker draws as a dashed stub labelled blocked by', () => {
  const v = view([node('a', 'queue', 1, null)], [blocks('ext00000-1111', 'a')], { external: { 'ext00000-1111': { title: 'Outside', role: 'work' } } })
  const svg = renderSvg(lay(v), v) as string
  expect(svg).toContain('blocked by ext00000')
  expect(svg).toContain('stroke-dasharray="5 3"')
})

test('svg: a 150-node fixture stays within the limit or yields null', () => {
  const nodes = Array.from({ length: 150 }, (_, i) => node(`n${String(i).padStart(3, '0')}`, ['queue', 'work', 'terminal'][i % 3] as string, 1, null, `Node number ${i} with a fairly long title for width`))
  const edges = nodes.slice(1).map((n, i) => blocks((nodes[i] as GraphNode).id, n.id))
  const v = view(nodes, edges, { gates: Object.fromEntries(nodes.map(n => [n.id, gate()])) })
  const svg = renderSvg(lay(v), v)
  if (svg !== null) expect(svg.length).toBeLessThanOrEqual(SVG_LIMIT)
  // And when it cannot fit at all, the answer is null rather than an oversize string.
  const huge = view(
    Array.from({ length: 150 }, (_, i) => node(`h${i}`, 'queue', 1, null, 'x'.repeat(2000))),
    [],
  )
  const hugeSvg = renderSvg(lay(huge), huge)
  if (hugeSvg !== null) expect(hugeSvg.length).toBeLessThanOrEqual(SVG_LIMIT)
})

test('svg: over the limit with tooltips, the lean form drops them', () => {
  const nodes = Array.from({ length: 150 }, (_, i) => node(`n${i}`, 'work', 1, null, `T${i}`))
  const missing = Array.from({ length: 40 }, (_, i) => `a-rather-long-missing-note-key-${i}`)
  const v = view(nodes, [], { gates: Object.fromEntries(nodes.map(n => [n.id, gate({ missing })])) })
  const svg = renderSvg(lay(v), v)
  expect(svg).not.toBeNull()
  expect((svg as string).length).toBeLessThanOrEqual(SVG_LIMIT)
  expect(svg).not.toContain('<title>')
})

// ── renderText ──────────────────────────────────────────────────────────────────────────

test('text: containment tree, gate suffix, and blocked-by only for live blockers', () => {
  const nodes = [
    node('feat0000', 'work', 0, null, 'Feature'),
    node('aaaa1111', 'terminal', 1, 'feat0000', 'Done task'),
    node('bbbb2222', 'work', 1, 'feat0000', 'Work task'),
    node('cccc3333', 'queue', 1, 'feat0000', 'Queued task'),
  ]
  const v = view(nodes, [blocks('aaaa1111', 'cccc3333'), blocks('bbbb2222', 'cccc3333'), blocks('ext99999-x', 'bbbb2222')], {
    gates: { bbbb2222: gate() },
    external: { 'ext99999-x': { title: 'Elsewhere', role: 'queue' } },
  })
  const lines = renderText(v)
  expect(lines[0]).toBe('◉ Feature [feat0000] work')
  expect(lines).toContain('├─ ✓ Done task [aaaa1111] done')
  expect(lines).toContain('├─ ◉ Work task [bbbb2222] work · 2/3 notes')
  expect(lines.some(l => l.endsWith('↳ blocked by ext99999 Elsewhere'))).toBe(true)
  expect(lines.some(l => l.endsWith('↳ blocked by bbbb2222 Work task'))).toBe(true)
  expect(lines.some(l => l.includes('blocked by aaaa1111'))).toBe(false)
  expect(lines.filter(l => l.includes('Queued task'))[0]).toMatch(/^└─ ○ Queued task/)
})


// ── phase text, plan labels, legend ─────────────────────────────────────────────────────

test('S9: phaseText for every kind', () => {
  const seats = (...entries: [string, number, number][]): GateInfo => gate({ seats: entries.map(([seat, required, filled]) => ({ seat, required, filled })) })
  expect(phaseText(node('a', 'queue', 1, null), undefined)).toBe('queue')
  expect(phaseText(node('a', 'blocked', 1, null), undefined)).toBe('blocked')
  expect(phaseText(node('a', 'terminal', 1, null), undefined)).toBe('done')
  expect(phaseText(node('a', 'terminal', 1, null, 't', 'cancelled'), undefined)).toBe('cancelled')
  expect(phaseText(node('a', 'work', 1, null), seats(['implementer', 1, 1], ['orchestrator', 1, 0]))).toBe('work · implementer ✓, orchestrator 0/1')
  expect(phaseText(node('a', 'work', 1, null), seats(['implementer', 2, 2]))).toBe('work · implementer ✓')
  expect(phaseText(node('a', 'review', 1, null), seats(['reviewer', 2, 1]))).toBe('review · reviewer 1/2')
  expect(phaseText(node('a', 'work', 1, null), gate())).toBe('work · 2/3 notes')
  expect(phaseText(node('a', 'work', 1, null), gate({ required: 0, filled: 0 }))).toBe('work')
  expect(phaseText(node('a', 'work', 1, null), undefined)).toBe('work')
  expect(phaseText(node('a', 'review', 1, null), undefined)).toBe('review')
})

const labelled = (): GraphView =>
  view([node('feat0000', 'work', 0, null, 'Feature'), { ...node('bbbb2222', 'work', 1, 'feat0000', 'Work task'), planLabel: 'T3' }, node('cccc3333', 'queue', 1, 'feat0000', 'Queued task')], [], {
    gates: { bbbb2222: gate({ seats: [{ seat: 'implementer', required: 1, filled: 1 }, { seat: 'orchestrator', required: 1, filled: 0 }] }) },
  })

test('S10: the text line carries [label] before the title and the phase text after the id', () => {
  const lines = renderText(labelled())
  expect(lines).toContain('├─ ◉ [T3] Work task [bbbb2222] work · implementer ✓, orchestrator 0/1')
  expect(lines).toContain('└─ ○ Queued task [cccc3333] queue')
})

test('S11: the svg badges a labelled node, draws the phase line, and escapes the label', () => {
  const v = labelled()
  const svg = renderSvg(lay(v), v) as string
  expect(svg).toContain('>T3</text>')
  expect(svg).toContain('fill-opacity="0.35"')
  expect(svg).toContain('work · implementer ✓, orchestrator 0/1')
  const plain = view([node('a', 'queue', 1, null, 'A')])
  const plainSvg = renderSvg(lay(plain), plain) as string
  expect(plainSvg).not.toContain('fill-opacity="0.35"')
  expect(plainSvg).toContain('>queue</text>')
  const evil = view([{ ...node('a', 'queue', 1, null, 'A'), planLabel: '<x>' }])
  const evilSvg = renderSvg(lay(evil), evil) as string
  expect(evilSvg).toContain('&lt;x&gt;')
  expect(evilSvg).not.toContain('<x>')
})

test('S19: sideLabel carries the label and the phase', () => {
  const v = labelled()
  const n = v.nodes[1] as GraphNode
  expect(sideLabel(n, v.gates[n.id])).toBe('◉ [T3] bbbb2222 Work task · work · implementer ✓, orchestrator 0/1')
  expect(sideLabel(node('cccc3333', 'queue', 1, null, 'Queued'))).toBe('○ cccc3333 Queued · queue')
})

// ── pane model ──────────────────────────────────────────────────────────────────────────

test('parseScopeArg: empty, root, id, prefix and junk', () => {
  expect(parseScopeArg('')).toEqual({ kind: 'active' })
  expect(parseScopeArg(' root ')).toEqual({ kind: 'root' })
  expect(parseScopeArg('a47dcd6f')).toEqual({ kind: 'id', id: 'a47dcd6f' })
  expect(parseScopeArg('a47dcd6f-f956-44fd-b0be-0d24719d945b').kind).toBe('id')
  expect(parseScopeArg('abc').kind).toBe('invalid')
  expect(parseScopeArg('do something').kind).toBe('invalid')
})

test('sideList: leaves only, in-flight first, capped at 40', () => {
  const nodes = [node('root', 'work', 0, null), ...Array.from({ length: 60 }, (_, i) => node(`n${i}`, i < 50 ? 'queue' : 'work', 1, 'root'))]
  const list = sideList(view(nodes))
  expect(list).toHaveLength(40)
  expect(list.slice(0, 10).every(n => n.role === 'work')).toBe(true)
  expect(list.some(n => n.id === 'root')).toBe(false)
})

test('formatDetail: missing notes, can-advance and a claim', () => {
  const lines = formatDetail({
    item: { id: 'a47dcd6f-f956', title: 'Pane', role: 'work' },
    gateStatus: { canAdvance: false, phase: 'work', missing: ['implementation-notes', { key: 'session-tracking' }] },
    claim: { claimedBy: 'agent-7', claimExpiresAt: '2026-10-03T16:00:00Z', isExpired: true },
  })
  expect(lines).toContain('can advance: no')
  expect(lines).toContain('missing notes: implementation-notes, session-tracking')
  expect(lines.some(l => l.startsWith('claimed by agent-7 (expired)'))).toBe(true)
  expect(formatDetail({ item: { role: 'queue' }, gateStatus: { canAdvance: true, missing: [] } })).toEqual(
    expect.arrayContaining(['can advance: yes', 'missing notes: none', 'claim: none']),
  )
  expect(formatDetail(null)).toEqual(['No detail available.'])
})

// ── the Pane, mounted ───────────────────────────────────────────────────────────────────

type Slot = { value: unknown; version: number }
const slotKey = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`

function rig(on: On, seed: Record<string, unknown> = {}) {
  const state = new Map<string, Slot>()
  const sets: { key: string; value: unknown }[] = []
  for (const [key, value] of Object.entries(seed)) state.set(`${PLUGIN}/${key}/`, { value, version: 1 })
  on('state.get', async (_$, e) => ({ value: state.get(slotKey(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slotKey(e as never)
    const cur = state.get(k)?.version ?? 0
    state.set(k, { value: (e as { value: unknown }).value, version: cur + 1 })
    sets.push({ key: (e as { key: string }).key, value: (e as { value: unknown }).value })

    return { value: { isSet: true, version: cur + 1 } } as never
  })

  return { state, sets }
}

const paneProps = () => ({ title: 'TO graph', isFocused: false, bodyColumns: 100, placement: 'dock', scroll: { offset: 0, bodyRows: 20 }, view: {} }) as never

const snapshot = (): GraphSnapshot => ({
  scopeId: 'feat0000',
  rootId: 'root',
  nodes: [node('feat0000', 'work', 1, null, 'Feature'), node('bbbb2222', 'work', 2, 'feat0000', 'Work task'), node('cccc3333', 'queue', 2, 'feat0000', 'Queued task')],
  edges: [blocks('bbbb2222', 'cccc3333')],
  external: {},
  gates: { bbbb2222: gate() },
  takenAt: 1,
  truncated: false,
})

for (const surface of ['terminal', 'desktop'] as const) {
  test(`pane on ${surface}: with a snapshot it draws the graph (${surface === 'terminal' ? 'text, no Svg' : 'one interactive Svg'}) and a node Button`, async ($, on) => {
    rig(on, { graphSnapshot: snapshot(), graphScope: 'feat0000' })
    const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'Pane', requestId: 'to-graph', props: paneProps() })
    expect(await ui.find({ key: 'refresh' })).toBeUndefined()
    expect(await ui.find({ key: 'node:bbbb2222' })).toBeDefined()
    const svg = await ui.find({ type: 'Svg' })
    if (surface === 'terminal') {
      expect(svg).toBeUndefined()
      expect(await ui.find({ type: 'Text', text: 'Work task' })).toBeDefined()
    } else {
      expect(svg).toBeDefined()
    }
    await ui.unmount()
  })

  test(`pane on ${surface}: with no snapshot it draws a loading state without throwing`, async ($, on) => {
    rig(on)
    const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'Pane', requestId: 'to-graph', props: paneProps() })
    expect(await ui.find({ type: 'Text', text: 'Loading' })).toBeDefined()
    expect(await ui.find({ type: 'Svg' })).toBeUndefined()
    await ui.unmount()
  })

  test(`pane on ${surface}: an empty scope says so`, async ($, on) => {
    rig(on, { graphSnapshot: { ...snapshot(), nodes: [], edges: [] } })
    const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'Pane', requestId: 'to-graph', props: paneProps() })
    expect(await ui.find({ type: 'Text', text: 'No items' })).toBeDefined()
    await ui.unmount()
  })
}

test('S15: Reconnect bumps graphReconnectRequest; Whole project clears the scope', async ($, on) => {
  const r = rig(on, { graphSnapshot: snapshot(), graphScope: 'feat0000' })
  const ui = await $.ui.mount({ plugin: PLUGIN, surface: 'terminal', component: 'Pane', requestId: 'to-graph', props: paneProps() })
  await ui.press({ key: 'reconnect' })
  expect(r.sets).toContainEqual({ key: 'graphReconnectRequest', value: 1 })
  await ui.press({ key: 'scope-project' })
  expect(r.sets).toContainEqual({ key: 'graphScope', value: null })
  expect(r.sets.some(x => x.key === 'graphRefreshRequest')).toBe(false)
  await ui.unmount()
})

test('S14: isDegraded is false only for sse with no error', () => {
  const st = (over: Partial<GraphStatus>): GraphStatus => ({ refreshing: false, liveSource: 'sse', ...over })
  expect(isDegraded(st({}))).toBe(false)
  expect(isDegraded(st({ refreshing: true }))).toBe(false)
  expect(isDegraded(st({ liveSource: 'poll' }))).toBe(true)
  expect(isDegraded(st({ liveSource: 'none' }))).toBe(true)
  expect(isDegraded(st({ lastError: 'boom' }))).toBe(true)
})

const DEGRADE_CASES: [string, GraphStatus, boolean][] = [
  ['sse, clean', { refreshing: false, liveSource: 'sse' }, false],
  ['poll', { refreshing: false, liveSource: 'poll' }, true],
  ['none', { refreshing: false, liveSource: 'none' }, true],
  ['sse with lastError', { refreshing: false, liveSource: 'sse', lastError: 'x' }, true],
]

for (const surface of ['terminal', 'desktop'] as const) {
  for (const [name, status, shown] of DEGRADE_CASES) {
    test(`S14/S13: ${surface}, ${name}: Reconnect ${shown ? 'shown' : 'hidden'}, never Refresh`, async ($, on) => {
      rig(on, { graphSnapshot: snapshot(), graphScope: 'feat0000', graphStatus: status })
      const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'Pane', requestId: 'to-graph', props: paneProps() })
      expect((await ui.find({ key: 'reconnect' })) !== undefined).toBe(shown)
      expect(await ui.find({ key: 'refresh' })).toBeUndefined()
      expect(await ui.find({ key: 'scope-project' })).toBeDefined()
      expect(await ui.find({ key: 'scope-feature' })).toBeDefined()
      await ui.unmount()
    })
  }

  test(`S12: ${surface} draws the legend, with no plan-label key when nothing is labelled`, async ($, on) => {
    rig(on, { graphSnapshot: snapshot(), graphScope: 'feat0000' })
    const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'Pane', requestId: 'to-graph', props: paneProps() })
    expect(await ui.find({ key: 'legend' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'done' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'plan label' })).toBeUndefined()
    await ui.unmount()
  })

  test(`S12: ${surface} adds the plan-label key when a node is labelled`, async ($, on) => {
    const snap = snapshot()
    snap.nodes[1] = { ...(snap.nodes[1] as GraphNode), planLabel: 'T3' }
    rig(on, { graphSnapshot: snap, graphScope: 'feat0000' })
    const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'Pane', requestId: 'to-graph', props: paneProps() })
    expect(await ui.find({ type: 'Text', text: 'plan label' })).toBeDefined()
    await ui.unmount()
  })
}

test('S12: an empty scope has no legend', async ($, on) => {
  rig(on, { graphSnapshot: { ...snapshot(), nodes: [], edges: [] } })
  const ui = await $.ui.mount({ plugin: PLUGIN, surface: 'terminal', component: 'Pane', requestId: 'to-graph', props: paneProps() })
  expect(await ui.find({ key: 'legend' })).toBeUndefined()
  await ui.unmount()
})

// ── source guard ────────────────────────────────────────────────────────────────────────

test('the pane calls only read tools (the source is also grepped for write tool names in verification)', () => {
  expect(READ_TOOLS.length).toBeGreaterThan(0)
  for (const tool of READ_TOOLS) expect(WRITE_TOOLS.has(tool)).toBe(false)
})

// Scope toggle: the active scope draws as the primary button, the other as the default look.
for (const surface of ['terminal', 'desktop'] as const) {
  for (const [scope, active, inactive] of [['feat0000', 'scope-feature', 'scope-project'], [null, 'scope-project', 'scope-feature']] as const) {
    test(`S20: ${surface}, scope ${scope ?? 'null'}: ${active} is primary`, async ($, on) => {
      rig(on, { graphSnapshot: snapshot(), graphScope: scope })
      const ui = await $.ui.mount({ plugin: PLUGIN, surface, component: 'Pane', requestId: 'to-graph', props: paneProps() })
      expect((await ui.find({ key: active }))?.props.variant).toBe('primary')
      expect((await ui.find({ key: inactive }))?.props.variant).toBeUndefined()
      await ui.unmount()
    })
  }
}
