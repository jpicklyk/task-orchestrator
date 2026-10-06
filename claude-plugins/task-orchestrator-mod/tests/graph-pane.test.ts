// Tests of the /to-graph pane (T3 a47dcd6f, top-down rewrite): the pure model, layout, router, raster and
// activity modules as units, and the Pane mounted on terminal AND desktop over an in-memory `$.state`
// (the test kit has no state noun). Test names carry the task-scope's G-numbers.
import type { On } from 'claude-code'
import { expect, mock, test } from 'claude-code/testing'

import type { GateInfo, GraphEdge, GraphNode, GraphSnapshot, GraphStatus } from '../types'
import { READ_TOOLS, WHOLE_CLICK_MAX, trackToolCall } from '../src/graph-pane/index.ts'
import { WRITE_TOOLS } from '../src/graph-data/index.ts'
import { GATE_BLOCK_MS, emptyActivity, gateBlocks, pruneActivity, recordActivity, recordGateBlocks, rememberAgent, resolveId, seatOf, shortModel, touchedIds } from '../src/graph-pane/activity.ts'
import { COST, ELEMENT_BUDGET, TREE_CHAR_BUDGET, edgePlan, extrasChars, marksFit } from '../src/graph-pane/budget.ts'
import { focusOrder, focusTarget, recordFocusOrder, redirectFocus, resetFocusState } from '../src/graph-pane/focus.ts'
import { cellSize } from '../src/graph-pane/cell.ts'
import { layoutTD } from '../src/graph-pane/layout.ts'
import { cardsOf, criticalPath, isOpen, num, stepsOf } from '../src/graph-pane/model.ts'
import type { Card } from '../src/graph-pane/model.ts'
import { SCOPE_CANDIDATE_LIMIT, SCOPE_LOOKBACK_MS, activeScope, detailLines, duration, firstSpawnUuid, formatDetail, isDegraded, isSwitching, loadingTitle, owningScope, parseScopeArg, scopeHeader, summaryLine } from '../src/graph-pane/pane-model.ts'
import { raster, runs } from '../src/graph-pane/raster.ts'
import { routes } from '../src/graph-pane/route.ts'
import { phaseText, rollupLine } from '../src/graph-pane/shared.ts'
import type { GraphView } from '../src/graph-pane/shared.ts'
import { titleLines } from '../src/graph-pane/wrap.ts'
import { SVG_LIMIT, SVG_PX_LIMIT, edgeSvg } from '../src/graph-pane/svg.ts'

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

// ── pane model ──────────────────────────────────────────────────────────────────────────

test('parseScopeArg: empty, root, id, prefix and junk', () => {
  expect(parseScopeArg('')).toEqual({ kind: 'active' })
  expect(parseScopeArg(' root ')).toEqual({ kind: 'root' })
  expect(parseScopeArg('a47dcd6f')).toEqual({ kind: 'id', id: 'a47dcd6f' })
  expect(parseScopeArg('a47dcd6f-f956-44fd-b0be-0d24719d945b').kind).toBe('id')
  expect(parseScopeArg('abc').kind).toBe('invalid')
  expect(parseScopeArg('do something').kind).toBe('invalid')
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

// ── model: steps, readiness, state text ─────────────────────────────────────────────────

const ROOT_ID = 'rootr000'
const A = 'aaaa1111'
const B = 'bbbb2222'
const C = 'cccc3333'

const mk = (id: string, role: string, label: string, title = `Title ${label}`, statusLabel?: string): GraphNode => ({ ...node(id, role, 2, ROOT_ID, title, statusLabel), planLabel: label })
const root = (): GraphNode => node(ROOT_ID, 'work', 1, null, 'Root feature')
const gview = (nodes: GraphNode[], edges: GraphEdge[] = [], extra: Partial<GraphSnapshot> = {}): GraphView => ({ ...view([root(), ...nodes], edges, extra), scopeId: ROOT_ID })

/** F1: A work, B queue, C queue blocked by A. */
const f1 = (): GraphView => gview([mk(A, 'work', 'T1'), mk(B, 'queue', 'T2'), mk(C, 'queue', 'T3')], [blocks(A, C)])
/** F2: A work; B work blocked by A; C queue blocked by A and B. */
const f2 = (): GraphView => gview([mk(A, 'work', 'T1'), mk(B, 'work', 'T2'), mk(C, 'queue', 'T3')], [blocks(A, B), blocks(A, C), blocks(B, C)])
/** F3: A and B done; C queue blocked by A. */
const f3 = (): GraphView => gview([mk(A, 'terminal', 'T1'), mk(B, 'terminal', 'T2'), mk(C, 'queue', 'T3')], [blocks(A, C)])

const build = (v: GraphView, over: { showDone?: boolean; bodyColumns?: number } = {}) => {
  const model = cardsOf(v)
  const steps = stepsOf(model.cards)
  const lay = layoutTD(model.cards, steps, { bodyColumns: over.bodyColumns ?? 100, showDone: over.showDone ?? false, hasRoot: model.root !== undefined })

  return { model, steps, lay }
}

test('isOpen: a blocker is open until it reaches the role it unblocks at', () => {
  expect(isOpen('queue')).toBe(true)
  expect(isOpen('work')).toBe(true)
  expect(isOpen('terminal')).toBe(false)
  expect(isOpen('work', 'work')).toBe(false)
  expect(isOpen('queue', 'work')).toBe(true)
  expect(isOpen('review', 'review')).toBe(false)
  expect(isOpen('work', 'review')).toBe(true)
})

test('stepsOf: a 3-chain plus a fork steps by longest path', () => {
  const ns = ['a000', 'b000', 'c000', 'd000', 'e000'].map((id, i) => mk(id, 'queue', `T${i + 1}`))
  const { steps } = build(gview(ns, [blocks('a000', 'b000'), blocks('b000', 'c000'), blocks('a000', 'd000'), blocks('d000', 'c000'), blocks('a000', 'e000')]))
  expect(['a', 'b', 'c', 'd', 'e'].map(x => steps.step.get(`${x}000`))).toEqual([1, 2, 3, 2, 2])
  expect(steps.max).toBe(3)
})

test('stepsOf: a 2-cycle terminates, keeps every card and records one back edge', () => {
  const { steps, model } = build(gview([mk('a000', 'queue', 'T1'), mk('b000', 'queue', 'T2')], [blocks('a000', 'b000'), blocks('b000', 'a000')]))
  expect(steps.step.size).toBe(2)
  expect(steps.back.size).toBe(1)
  expect(steps.step.get('a000')).not.toBe(steps.step.get('b000'))
  // The back edge still counts as a dependency of its target.
  expect(model.cards.every(c => c.deps.length === 1)).toBe(true)
})

test('stepsOf: self edges, unknown ends and RELATES_TO never step', () => {
  const v = gview([mk('a000', 'queue', 'T1'), mk('b000', 'queue', 'T2')], [blocks('a000', 'a000'), blocks('a000', 'zzz0'), { from: 'a000', to: 'b000', type: 'RELATES_TO' }])
  expect(build(v).steps.max).toBe(1)
})

test('G3: the state line is open blockers, then a live worker, then ready, then the phase', () => {
  const v = gview([mk(A, 'work', 'T1'), mk(B, 'queue', 'T2'), mk(C, 'queue', 'T3')], [blocks(A, C)], {
    gates: { [A]: gate({ seats: [{ seat: 'implementer', required: 1, filled: 1 }, { seat: 'orchestrator', required: 1, filled: 0 }] }) },
  })
  const by = (m: ReturnType<typeof cardsOf>, id: string) => m.cards.find(c => c.id === id) as Card
  const plain = cardsOf(v)
  expect(by(plain, C).stateText).toBe('⊘ after T1')
  expect(by(plain, B).stateText).toBe('ready')
  expect(by(plain, B).ready).toBe(true)
  expect(by(plain, A).stateText).toBe('work · implementer ✓, orchestrator 0/1')
  const w = { at: 1, agentId: 'main', seat: 'implementer', model: 'claude-opus-5-5-20260101' }
  const live = cardsOf(v, { [B]: [w, { ...w, agentId: 'sub' }], [C]: [w] })
  expect(by(live, B).stateText).toBe('» impl · opus +1')
  expect(by(live, C).stateText).toBe('⊘ after T1')
  // External blockers: open ones by id8, terminal ones never.
  const ext = gview([mk(B, 'queue', 'T2')], [blocks('ext00000-1111', B), blocks('ext22222-3333', B)], {
    external: { 'ext00000-1111': { title: 'X', role: 'work' }, 'ext22222-3333': { title: 'Y', role: 'terminal' } },
  })
  expect(by(cardsOf(ext), B).stateText).toBe('⊘ after ext00000')
  // unblockAt work: a blocker already in work is satisfied.
  const early = gview([mk(A, 'work', 'T1'), mk(C, 'queue', 'T3')], [{ ...blocks(A, C), unblockAt: 'work' }])
  expect(by(cardsOf(early), C).ready).toBe(true)
  expect(by(cardsOf(early), C).deps).toEqual([{ id: A, open: false }])
})

test('G4: rows follow steps, and ties order T2 before T11 (the plan-number regex)', () => {
  const { lay } = build(f2())
  expect(lay.rows.map(r => r.ids)).toEqual([[A], [B], [C]])
  // Titles would put T11 first; the plan number puts T2 first.
  const t = build(gview([mk('x000', 'queue', 'T11', 'A first'), mk('y000', 'queue', 'T2', 'Z last')]))
  expect(t.lay.rows[0]?.ids).toEqual(['y000', 'x000'])
  expect(num('T12')).toBe(12)
  expect(num('abcd1234')).toBe(999)
})

test('rows: ordering is deterministic across input order and the sweeps remove the crossing', () => {
  const ns = [mk('a000', 'queue', 'T1', 'a'), mk('b000', 'queue', 'T2', 'b'), mk('x000', 'queue', 'T3', 'x'), mk('y000', 'queue', 'T4', 'y')]
  const es = [blocks('a000', 'y000'), blocks('b000', 'x000')]
  const first = build(gview(ns, es)).lay.rows.map(r => r.ids)
  expect(first).toEqual([['a000', 'b000'], ['y000', 'x000']])
  expect(build(gview(ns, [...es].reverse())).lay.rows.map(r => r.ids)).toEqual(first)
  expect(build(gview([...ns].reverse(), es)).lay.rows.map(r => r.ids)).toEqual(first)
})

// ── geometry ────────────────────────────────────────────────────────────────────────────

const rect = (lay: ReturnType<typeof build>['lay'], id: string) => lay.cards.get(id) as { left: number; top: number; width: number; height: number }

test('G1: F1 geometry in whole cells', () => {
  const { lay } = build(f1())
  expect(lay.root).toEqual({ left: 21, top: 0, width: 56, height: 3 })
  expect(rect(lay, A)).toEqual({ left: 13, top: 6, width: 34, height: 3 })
  expect(rect(lay, B)).toEqual({ left: 50, top: 6, width: 34, height: 3 })
  expect(rect(lay, C)).toEqual({ left: 32, top: 12, width: 34, height: 3 })
  expect(lay.height).toBe(15)
  expect(lay.tooWide).toBeNull()
})

test('G11 (unit): a fully done step folds into a chip that shifts later rows up', () => {
  const { lay } = build(f3())
  expect(lay.cards.has(A)).toBe(false)
  expect(lay.cards.has(B)).toBe(false)
  expect(lay.chips).toHaveLength(1)
  expect(lay.chips[0]).toMatchObject({ step: 1, done: 2, cancelled: 0, rect: { top: 6, height: 1, width: 40, left: 29 } })
  expect(rect(lay, C).top).toBe(10)
  expect(lay.doneSteps).toEqual([1])
  const shown = build(f3(), { showDone: true }).lay
  expect(shown.chips).toHaveLength(0)
  expect(rect(shown, C).top).toBe(12)
  expect(shown.cards.has(A)).toBe(true)
})

test('G17 (unit): a step wider than the pane reports its width', () => {
  const five = gview([1, 2, 3, 4, 5].map(i => mk(`n${i}00`, 'queue', `T${i}`)))
  expect(build(five, { bodyColumns: 60 }).lay.tooWide).toBe(92)
  expect(build(f1(), { bodyColumns: 60 }).lay.tooWide).toBeNull()
})

// ── edges: router, raster, svg ──────────────────────────────────────────────────────────

const cellsOf = (v: GraphView) => {
  const b = build(v)

  return raster(routes(b.lay, b.model.cards, 'cell'))
}
const at = (cells: ReturnType<typeof cellsOf>, x: number, y: number) => cells.get(`${x}_${y}`)

test('G6: terminal adjacent edge and shared containment junctions', () => {
  const cells = cellsOf(f1())
  const glyphs = (xs: number[], y: number) => xs.map(x => at(cells, x, y)?.glyph)
  expect(at(cells, 30, 9)?.glyph).toBe('╎')
  expect(at(cells, 30, 10)?.glyph).toBe('└')
  expect(glyphs([31, 40, 48], 10)).toEqual(['╌', '╌', '╌'])
  expect(at(cells, 49, 10)?.glyph).toBe('┐')
  expect(at(cells, 49, 11)).toEqual({ glyph: '▼', color: '#f59e0b', dim: false })
  // Containment root -> A and root -> B leave one centre (49) and share its tee.
  expect(at(cells, 49, 3)?.glyph).toBe('┆')
  expect(at(cells, 49, 4)?.glyph).toBe('┴')
  expect(glyphs([31, 40, 48], 4)).toEqual(['┄', '┄', '┄'])
  expect(at(cells, 30, 4)?.glyph).toBe('┌')
  expect(at(cells, 30, 5)?.glyph).toBe('┆')
  expect(glyphs([50, 60, 66], 4)).toEqual(['┄', '┄', '┄'])
  expect(at(cells, 67, 4)?.glyph).toBe('┐')
  expect(at(cells, 67, 5)?.glyph).toBe('┆')
  // No arrowhead on containment.
  expect(at(cells, 30, 5)?.glyph).not.toBe('▼')
  expect(at(cells, 67, 5)?.glyph).not.toBe('▼')
})

test('G7: a two-row edge takes a free channel and never enters a box', () => {
  const b = build(f2())
  const cells = raster(routes(b.lay, b.model.cards, 'cell'))
  const g = (x: number, y: number) => at(cells, x, y)?.glyph
  expect(g(49, 10)).toBe('├')
  for (const x of [50, 58, 65]) expect(g(x, 10)).toBe('╌')
  expect(g(66, 10)).toBe('┐')
  for (const y of [11, 12, 13, 14, 15]) expect(g(66, y)).toBe('╎')
  expect(g(66, 16)).toBe('┘')
  for (const x of [50, 58, 65]) expect(g(x, 16)).toBe('╌')
  expect(g(49, 16)).toBe('├')
  expect(g(49, 17)).toBe('▼')
  const rects = [...b.lay.cards.values()]
  for (const key of cells.keys()) {
    const [x, y] = key.split('_').map(Number) as [number, number]
    expect(rects.some(r => x >= r.left && x < r.left + r.width && y >= r.top && y < r.top + r.height)).toBe(false)
  }
})

test('G8: the desktop path of the long edge, markers and dashes by kind', () => {
  const b = build(f2())
  const svg = edgeSvg(routes(b.lay, b.model.cards, 'px'), { w: 8.4, h: 18 }, b.lay.width, b.lay.height)
  expect(svg).toContain('d="M411.6 162.0 V189.0 H558.6 V297.0 H411.6 V324.0"')
  const long = svg.split('<path ').find(p => p.includes('M411.6 162.0 V189.0 H558.6'))
  expect(long).toContain('marker-end="url(#ao)"')
  expect(long).toContain('stroke-dasharray="6 3"')
  const done = build(f3(), { showDone: true })
  const dsvg = edgeSvg(routes(done.lay, done.model.cards, 'px'), { w: 8.4, h: 18 }, done.lay.width, done.lay.height)
  const doneEdge = dsvg.split('<path ').find(p => p.includes('marker-end="url(#ad)"'))
  expect(doneEdge).toBeDefined()
  expect(doneEdge).not.toContain('stroke-dasharray')
  const contain = dsvg.split('<path ').find(p => p.includes('stroke-dasharray="2 3"'))
  expect(contain).toBeDefined()
  expect(contain).not.toContain('marker-end')
})

test('G11 (unit): a chip sources one satisfied edge per target and no containment enters it', () => {
  const b = build(f3())
  const rs = routes(b.lay, b.model.cards, 'cell')
  expect(rs.filter(r => r.kind === 'done')).toHaveLength(1)
  expect(rs.filter(r => r.kind === 'contain')).toHaveLength(0)
  expect(rs[0]?.pts[0]).toEqual([49, 7])
})

test('polish 1 (unit): titles break at the last space that fits, never mid-word, when a space exists', () => {
  // width 17 box -> 15 columns
  expect(titleLines('◉ [T6] Retrospective', 15)).toEqual(['◉ [T6]', 'Retrospective'])
  expect(titleLines('◉ [T7] Compaction pass', 14)).toEqual(['◉ [T7]', 'Compaction pa…'])
  expect(titleLines('◉ [T1] Rewrite the graph pane top down', 20)).toEqual(['◉ [T1] Rewrite the', 'graph pane top dow…'.replace('dow…', 'down')])
  // a single word longer than the line is hard-cut, and the overflow ends with an ellipsis
  expect(titleLines('x'.repeat(40), 15)).toEqual(['x'.repeat(15), `${'x'.repeat(14)}…`])
})

test('polish 4 (unit): the header count and the root box count the children only', () => {
  const v = f1()
  expect(cardsOf(v).cards).toHaveLength(3)
  expect(summaryLine(v).startsWith('3 items')).toBe(true)
  // an unscoped view (scope null) excludes the parentless root the same way
  expect(summaryLine({ ...v, scopeId: null }).startsWith('3 items')).toBe(true)
})

/** F4: step 1 = A (done) + P (work); step 2 = B (done, after A) folds into a chip; step 3 = C after B and P. */
const f4 = (): GraphView => {
  const P = 'pppp4444'

  return gview([mk(A, 'terminal', 'T1'), mk(P, 'work', 'T4'), mk(B, 'terminal', 'T2'), mk(C, 'queue', 'T3')], [blocks(A, B), blocks(B, C), blocks(P, C)])
}

test('polish 2 (unit): edges into and out of a collapsed step attach to its chip; pass-through edges avoid it', () => {
  const b = build(f4())
  const chip = b.lay.chips[0]
  expect(chip?.ids).toEqual([B])
  const rect = chip!.rect
  for (const mode of ['cell', 'px'] as const) {
    const rs = routes(b.lay, b.model.cards, mode)
    const cx = mode === 'px' ? rect.left + rect.width / 2 : rect.left + Math.floor(rect.width / 2)
    const headY = mode === 'px' ? rect.top : rect.top - 1
    // A -> chip: one edge ending on the chip's top centre
    expect(rs.filter(r => r.pts[r.pts.length - 1]?.[0] === cx && r.pts[r.pts.length - 1]?.[1] === headY)).toHaveLength(1)
    // chip -> C: one edge starting on the chip's bottom centre
    expect(rs.filter(r => r.pts[0]?.[0] === cx && r.pts[0]?.[1] === rect.top + 1)).toHaveLength(1)
    expect(rs.filter(r => r.kind !== 'contain')).toHaveLength(3)
    // P -> C crosses the chip row: none of its vertical runs enters the chip's columns
    const pass = rs.find(r => r.kind === 'open')!
    for (let i = 0; i + 1 < pass.pts.length; i++) {
      const [x1, y1] = pass.pts[i] as [number, number]
      const [x2, y2] = pass.pts[i + 1] as [number, number]
      if (x1 !== x2 || Math.min(y1, y2) > rect.top || Math.max(y1, y2) < rect.top) continue
      const inside = mode === 'px' ? x1 > rect.left - 0.5 && x1 < rect.left + rect.width + 0.5 : x1 >= rect.left && x1 <= rect.left + rect.width - 1
      expect(inside).toBe(false)
    }
  }
})

test('raster: arms build tees, crossings and style precedence', () => {
  const r = (kind: 'open' | 'done' | 'contain', pts: [number, number][]) => ({ kind, pts, head: false })
  const cells = raster([r('contain', [[0, 0], [0, 2], [4, 2], [4, 3]]), r('open', [[2, 0], [2, 1], [2, 5], [2, 6]])])
  expect(at(cells, 2, 2)?.glyph).toBe('┼')
  expect(at(cells, 0, 0)?.glyph).toBe('┆')
  expect(at(cells, 0, 2)?.glyph).toBe('└')
  expect(at(cells, 2, 3)?.glyph).toBe('╎')
  expect(at(cells, 2, 2)?.color).toBe('#f59e0b')
  const merged = runs(raster([r('done', [[0, 0], [0, 1], [5, 1], [5, 2]])]))
  expect(merged.find(m => m.y === 1 && m.n === 4)?.glyph).toBe('─')
})

// ── userConfig cell size ────────────────────────────────────────────────────────────────

test('G10 (unit): cellSize clamps and defaults', () => {
  expect(cellSize({})).toEqual({ w: 8.4, h: 18 })
  expect(cellSize(undefined)).toEqual({ w: 8.4, h: 18 })
  expect(cellSize({ cellWidthPx: 0 })).toEqual({ w: 8.4, h: 18 })
  expect(cellSize({ cellWidthPx: 100, cellHeightPx: 1 })).toEqual({ w: 32, h: 8 })
  expect(cellSize({ cellWidthPx: 'x', cellHeightPx: Number.NaN })).toEqual({ w: 8.4, h: 18 })
  expect(cellSize({ cellWidthPx: 10, cellHeightPx: 20 })).toEqual({ w: 10, h: 20 })
})

// ── activity ────────────────────────────────────────────────────────────────────────────

test('G13: touchedIds covers every call shape', () => {
  expect(touchedIds({ itemId: 'a1', actor: { id: 'orchestrator:x' } })).toEqual([{ id: 'a1', actor: 'orchestrator:x' }])
  expect(touchedIds({ transitions: [{ itemId: 't1', trigger: 'start' }, { itemId: 't2', actor: { id: 'implementer:y' } }], actor: { id: 'top' } })).toEqual([
    { id: 't1', actor: 'top' },
    { id: 't2', actor: 'implementer:y' },
  ])
  expect(touchedIds({ claims: [{ itemId: 'c1' }], notes: [{ itemId: 'n1' }, { itemId: 'c1' }], items: [{ id: 'i1' }, { itemId: 'i2' }] }).map(t => t.id)).toEqual(['c1', 'n1', 'i1', 'i2'])
  expect(touchedIds(null)).toEqual([])
  expect(touchedIds({ query: 'x' })).toEqual([])
})

test('G13: seatOf precedence, shortModel, resolveId', () => {
  expect(seatOf('s1', { s1: { seat: 'reviewer' } }, 'implementer:x')).toBe('reviewer')
  expect(seatOf('s1', {}, 'implementer:x:y')).toBe('implementer')
  expect(seatOf('s1', {})).toBe('agent')
  expect(seatOf(undefined, {})).toBe('main')
  expect(shortModel('claude-opus-5-5-20260101')).toBe('opus-5-5')
  expect(shortModel('opus')).toBe('opus')
  expect(shortModel(undefined)).toBeUndefined()
  expect(resolveId('aaaa1111', [A, B])).toBe(A)
  expect(resolveId('aaaa', [A, B])).toBe(A)
  expect(resolveId('aaa', [A, B])).toBeNull()
  expect(resolveId('zzzz', [A, B])).toBeNull()
  expect(resolveId('aaaa', ['aaaa1111', 'aaaa2222'])).toBeNull()
})

test('G13: recordActivity returns the same object inside 10s; pruneActivity expires and keeps identity when idle', () => {
  const e = { id: A, agentId: 'main', seat: 'implementer', changed: true }
  const first = recordActivity(emptyActivity(), e, 1000)
  expect(first.working[A]).toEqual([{ agentId: 'main', seat: 'implementer', at: 1000 }])
  expect(first.changed[A]).toBe(1000)
  expect(recordActivity(first, e, 5000)).toBe(first)
  const later = recordActivity(first, e, 11_000)
  expect(later).not.toBe(first)
  expect(later.working[A]?.[0]?.at).toBe(11_000)
  // A changed seat is news at once.
  expect(recordActivity(first, { ...e, seat: 'reviewer' }, 2000)).not.toBe(first)
  expect(pruneActivity(first, 1000 + 29_000)).toBe(first)
  const half = pruneActivity(first, 1000 + 31_000)
  expect(half.changed).toEqual({})
  expect(half.working[A]).toHaveLength(1)
  const gone = pruneActivity(first, 1000 + 121_000)
  expect(gone.working).toEqual({})
  expect(pruneActivity(gone, 500_000)).toBe(gone)
  const two = rememberAgent({ s1: { seat: 'a' } }, 's2', { seat: 'b', model: 'm' })
  expect(Object.keys(two)).toEqual(['s1', 's2'])
  expect(rememberAgent(two, 's2', { seat: 'b', model: 'm' })).toBe(two)
})

// ── budgets ─────────────────────────────────────────────────────────────────────────────

test('G16: edgePlan picks the richest drawing that fits', () => {
  const base = { desktop: true, cards: 10, chips: 0, cells: 0, runs: 0, svgChars: 5000, svgWidth: 800, svgHeight: 600 }
  expect(edgePlan(base)).toBe('full')
  expect(edgePlan({ ...base, svgChars: SVG_LIMIT + 1 })).toBe('omit')
  expect(edgePlan({ ...base, svgWidth: SVG_PX_LIMIT + 1 })).toBe('omit')
  expect(edgePlan({ ...base, svgHeight: SVG_PX_LIMIT + 1 })).toBe('omit')
  expect(edgePlan({ ...base, cards: 10_000 })).toBe('too-large')
  const term = { desktop: false, cards: 10, chips: 0, cells: 100, runs: 40, svgChars: 0, svgWidth: 0, svgHeight: 0 }
  expect(edgePlan(term)).toBe('full')
  expect(edgePlan({ ...term, cells: 100_000 })).toBe('runs')
  expect(edgePlan({ ...term, cells: 100_000, runs: 100_000 })).toBe('omit')
  expect(edgePlan({ ...term, cards: 10_000 })).toBe('too-large')
})

// ── detail lines ────────────────────────────────────────────────────────────────────────

test('G12 (unit): detailLines authors, phase age, summary and tolerated failures', () => {
  const ctx = {
    item: { id: 'a47dcd6f-f956', title: 'Pane', role: 'work' },
    gateStatus: { canAdvance: false, phase: 'work', missing: ['session-tracking'] },
    schema: [
      { key: 'implementation-notes', role: 'work', required: true, filled: true, seat: 'implementer' },
      { key: 'session-tracking', role: 'work', required: true, filled: false, seat: 'orchestrator' },
      { key: 'optional-note', role: 'work', required: false, filled: false },
      { key: 'spec', role: 'queue', required: true, filled: true },
    ],
  }
  const now = 10_000_000
  const item = { id: 'a47dcd6f-f956', title: 'Pane', type: 'feature-task', summary: 'Rewrite the graph', roleChangedAt: new Date(now - 125 * 60_000).toISOString() }
  const notes = { notes: [{ key: 'implementation-notes', role: 'work', actor: { id: 'implementer:x', kind: 'subagent' } }] }
  const lines = detailLines({ itemId: 'a47dcd6f-f956', ctx, item, notes, now })
  expect(lines[0]).toBe('Pane [a47dcd6f] · feature-task')
  expect(lines).toContain('phase: work · in phase 2h 5m')
  expect(lines).toContain('Rewrite the graph')
  expect(lines).toContain('✓ implementation-notes · implementer · by implementer:x')
  expect(lines).toContain('✗ session-tracking · orchestrator · missing')
  expect(lines.some(l => l.includes('optional-note') || l.includes('spec'))).toBe(false)
  expect(lines).toContain('can advance: no')
  expect(lines).toContain('claim: none')
  const degraded = detailLines({ itemId: 'a47dcd6f-f956', ctx, now, itemFailed: true, notesFailed: true })
  expect(degraded).toContain('(summary unavailable)')
  expect(degraded).toContain('(authors unavailable)')
  expect(degraded).toContain('✓ implementation-notes · implementer')
  expect(duration(26 * 3_600_000 + 5 * 60_000)).toBe('1d 2h')
  expect(duration(90_000)).toBe('1m')
})

// ── the Pane, mounted ───────────────────────────────────────────────────────────────────

type Slot = { value: unknown; version: number }
const slotKey = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`
const SLOT = (key: string) => `${PLUGIN}/${key}/`

function rig(on: On, seed: Record<string, unknown> = {}) {
  const state = new Map<string, Slot>()
  const sets: { key: string; value: unknown }[] = []
  for (const [key, value] of Object.entries(seed)) state.set(SLOT(key), { value, version: 1 })
  on('state.get', async (_$, e) => ({ value: state.get(slotKey(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slotKey(e as never)
    const cur = state.get(k)?.version ?? 0
    state.set(k, { value: (e as { value: unknown }).value, version: cur + 1 })
    sets.push({ key: (e as { key: string }).key, value: (e as { value: unknown }).value })

    return { value: { isSet: true, version: cur + 1 } } as never
  })

  return { state, sets, valueOf: (key: string) => state.get(SLOT(key))?.value }
}

const paneProps = (bodyColumns = 100) => ({ title: 'TO graph', isFocused: false, bodyColumns, placement: 'dock', scroll: { offset: 0, bodyRows: 20 }, view: {} }) as never

const asSnapshot = (v: GraphView): GraphSnapshot => v

const SURFACES = ['terminal', 'desktop'] as const

const mountAt = ($: Parameters<Parameters<typeof test>[1]>[0], surface: (typeof SURFACES)[number], bodyColumns = 100) =>
  $.ui.mount({ plugin: PLUGIN, surface, component: 'Pane', requestId: 'to-graph', props: paneProps(bodyColumns) })

type El = { type: string; key?: string; props: Record<string, unknown>; children: unknown[] }
const found = async (ui: { find: (q: { key: string }) => Promise<unknown> }, key: string): Promise<El> => (await ui.find({ key })) as El
// A Button's press handle is renumbered on every draw; the rest of the subtree is what must hold still.
const json = async (ui: { find: (q: { key: string }) => Promise<unknown> }, key: string): Promise<string> => JSON.stringify(await ui.find({ key }), (k, v) => (k === 'press' ? undefined : v))

const textsOf = (el: unknown): string[] => {
  const out: string[] = []
  const walk = (x: unknown): void => {
    if (typeof x === 'string') out.push(x)
    else if (typeof x === 'object' && x !== null && Array.isArray((x as El).children)) (x as El).children.forEach(walk)
  }
  walk(el)

  return out
}

for (const surface of SURFACES) {
  test(`G1/G9: ${surface} draws keyed boxes at the planned cells; edges are ${surface === 'terminal' ? 'per-cell boxes' : 'one sized Svg'}`, async ($, on) => {
    rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    const a = await found(ui, `card:${A}`)
    expect(a.props).toMatchObject({ position: 'absolute', top: 6, left: 13, width: 34, height: 3, backgroundColor: '#c2410c' })
    expect((await found(ui, `card:${B}`)).props).toMatchObject({ top: 6, left: 50, backgroundColor: '#0f766e' })
    expect((await found(ui, `card:${C}`)).props).toMatchObject({ top: 12, left: 32, backgroundColor: '#6b7280' })
    expect((await found(ui, `card:${ROOT_ID}`)).props).toMatchObject({ top: 0, left: 21, width: 56, height: 3 })
    expect((await found(ui, `open:${C}`)).props.label).toBe('⊘ after T1')
    expect((await found(ui, `open:${B}`)).props.label).toBe('ready')
    const svgs = await ui.findAll({ type: 'Svg' })
    const edgeBoxes = (await ui.findAll({ type: 'Box' })).filter(b => typeof b.key === 'string' && b.key.startsWith('e:'))
    if (surface === 'terminal') {
      expect(svgs).toHaveLength(0)
      expect(edgeBoxes.length).toBeGreaterThan(10)
      for (const b of edgeBoxes) expect(b.props).toMatchObject({ position: 'absolute', width: 1, height: 1 })
      const head = (await found(ui, 'e:49_11')).children[0] as El
      expect(head.children).toEqual(['▼'])
      // A (work) -> C (queue) is F1's critical path (two open cards): its arrowhead is purple.
      expect(head.props.color).toBe('#a855f7')
    } else {
      expect(edgeBoxes).toHaveLength(0)
      expect(svgs).toHaveLength(1)
      const canvas = await found(ui, 'canvas')
      expect(svgs[0]?.props.width).toBe(Number(((canvas.props.width as number) * 8.4).toFixed(1)))
      expect(svgs[0]?.props.height).toBe(Number(((canvas.props.height as number) * 18).toFixed(1)))
      expect(String(svgs[0]?.props.alt)).toBe('dependency edges')
    }
    await ui.unmount()
  })

  test(`G2: ${surface} wraps a long title at the last space and keeps [T1] on line 1`, async ($, on) => {
    const long = gview([mk(A, 'work', 'T1', 'Rewrite the dependency graph pane top down please')])
    const r = rig(on, { graphSnapshot: asSnapshot(long), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    const card = await found(ui, `card:${A}`)
    const texts = card.children.filter((c): c is El => typeof c === 'object' && (c as El).type === 'Button')
    const first = String(texts[0]?.props.label)
    expect(first.startsWith('◉ [T1] ')).toBe(true)
    expect(first.length).toBeLessThanOrEqual(32)
    expect(first.endsWith(' ')).toBe(false)
    const second = String(texts[1]?.props.label)
    expect(second.length).toBeGreaterThan(0)
    expect(`${first} ${second}`.replace('…', '')).toContain('graph pane')
    // Every line of the box is a plain Button: a click anywhere on it opens the detail.
    expect(texts).toHaveLength(3)
    for (const b of texts) expect(b.props.plain).toBe(true)
    // A word longer than half the width is hard-cut with an ellipsis on line 2.
    const hard = gview([mk(A, 'work', 'T1', 'x'.repeat(80))])
    r.state.set(SLOT('graphSnapshot'), { value: asSnapshot(hard), version: 5 })
    await ui.redraw()
    const c2 = await found(ui, `card:${A}`)
    const t2 = c2.children.filter((c): c is El => typeof c === 'object' && (c as El).type === 'Button')
    expect(String(t2[1]?.props.label).endsWith('…')).toBe(true)
    await ui.unmount()
  })

  test(`G5: ${surface} a change to one card redraws only that card's subtree; keys and edges hold`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    const keys = [`card:${ROOT_ID}`, `card:${A}`, `card:${B}`, `card:${C}`, 'edges']
    const before = Object.fromEntries(await Promise.all(keys.map(async k => [k, await json(ui, k)])))
    const keySet = async () => (await ui.findAll({ type: 'Box' })).map(b => b.key).sort()
    const keysBefore = await keySet()
    const changed = gview([mk(A, 'work', 'T1'), mk(B, 'queue', 'T2', 'A different title'), mk(C, 'queue', 'T3')], [blocks(A, C)])
    r.state.set(SLOT('graphSnapshot'), { value: asSnapshot(changed), version: 9 })
    await ui.redraw()
    const after = Object.fromEntries(await Promise.all(keys.map(async k => [k, await json(ui, k)])))
    for (const k of keys) {
      if (k === `card:${B}`) expect(after[k]).not.toBe(before[k])
      else expect(after[k]).toBe(before[k])
    }
    expect(await keySet()).toEqual(keysBefore)
    await ui.unmount()
  })

  test(`G15: ${surface} a live worker and a recent change show on their own cards only`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    const keys = [`card:${ROOT_ID}`, `card:${A}`, `card:${B}`, `card:${C}`]
    const before = Object.fromEntries(await Promise.all(keys.map(async k => [k, await json(ui, k)])))
    const act = { working: { [B]: [{ agentId: 'main', seat: 'implementer', model: 'claude-opus-5-5', at: 1 }] }, changed: { [A]: 1 } }
    r.state.set(SLOT('graphActivity'), { value: act, version: 3 })
    await ui.redraw()
    expect((await found(ui, `open:${B}`)).props.label).toBe('» impl · opus')
    const a = await found(ui, `card:${A}`)
    expect(String((a.children[0] as El).props.label).startsWith('✱ ')).toBe(true)
    for (const k of [`card:${ROOT_ID}`, `card:${C}`]) expect(await json(ui, k)).toBe(before[k])
    expect(await json(ui, `card:${B}`)).not.toBe(before[`card:${B}`])
    await ui.unmount()
  })

  test(`polish 3: ${surface} a narrow box shows the compact worker line and never breaks a word`, async ($, on) => {
    const six = gview([1, 2, 3, 4, 5, 6].map(i => mk(`n${i}000000`, 'queue', `T${i}`, i === 1 ? 'Retrospective' : i === 2 ? 'Compaction pass' : `Item ${i}`)))
    const r = rig(on, { graphSnapshot: asSnapshot(six), graphScope: ROOT_ID })
    const ui = await mountAt($, surface, 119)
    r.state.set(SLOT('graphActivity'), { value: { working: { n1000000: [{ agentId: 'main', seat: 'implementer', model: 'claude-sonnet-5-5', at: 1 }] }, changed: {} }, version: 3 })
    await ui.redraw()
    expect((await found(ui, 'card:n1000000')).props.width).toBe(17)
    expect((await found(ui, 'open:n1000000')).props.label).toBe('» impl · sonnet')
    const c = await found(ui, 'card:n2000000')
    const lines = c.children.filter((x): x is El => typeof x === 'object' && (x as El).type === 'Button').map(t => String(t.props.label))
    expect(lines.slice(0, 2).join(' ')).toContain('Compaction')
    await ui.unmount()
  })

  test(`G11: ${surface} folds done steps into a chip; Show done steps brings the cards back`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f3()), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    expect(await ui.find({ key: `card:${A}` })).toBeUndefined()
    expect(await ui.find({ key: `card:${B}` })).toBeUndefined()
    const chip = await found(ui, 'step:1')
    expect(chip.props).toMatchObject({ top: 6, height: 1, backgroundColor: '#15803d' })
    expect(textsOf(chip)).toEqual(['✓ step 1 · 2 done'])
    expect((await found(ui, `card:${C}`)).props).toMatchObject({ top: 10, backgroundColor: '#0f766e' })
    expect((await found(ui, 'done-steps')).props.label).toBe('Show done steps')
    await ui.press({ key: 'done-steps' })
    expect(r.sets).toContainEqual({ key: 'graphShowDone', value: true })
    await ui.redraw()
    expect(await ui.find({ key: `card:${A}` })).toBeDefined()
    expect(await ui.find({ key: 'step:1' })).toBeUndefined()
    expect((await found(ui, `card:${C}`)).props.top).toBe(12)
    expect((await found(ui, 'done-steps')).props.label).toBe('Hide done steps')
    await ui.unmount()
  })

  test(`G17: ${surface} says when a step is wider than the pane`, async ($, on) => {
    const five = gview([1, 2, 3, 4, 5].map(i => mk(`n${i}00`, 'queue', `T${i}`)))
    const r = rig(on, { graphSnapshot: asSnapshot(five), graphScope: ROOT_ID })
    const ui = await mountAt($, surface, 60)
    expect(await ui.find({ type: 'Text', text: 'Graph is 92 columns wide; the pane shows 60. Widen the pane or open a smaller scope.' })).toBeDefined()
    r.state.set(SLOT('graphSnapshot'), { value: asSnapshot(f1()), version: 4 })
    await ui.redraw()
    expect(await ui.find({ type: 'Text', text: 'columns wide' })).toBeUndefined()
    await ui.unmount()
  })

  test(`G19: ${surface} draws a dependency cycle without throwing`, async ($, on) => {
    const cyc = gview([mk(A, 'queue', 'T1'), mk(B, 'queue', 'T2')], [blocks(A, B), blocks(B, A)])
    rig(on, { graphSnapshot: asSnapshot(cyc), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    expect(await ui.find({ key: `card:${A}` })).toBeDefined()
    expect(await ui.find({ key: `card:${B}` })).toBeDefined()
    await ui.unmount()
  })

  test(`G18: ${surface} every fixture and the open detail resolve on this surface's element table`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID, graphDetail: { itemId: A, lines: ['Detail', 'x'] } })
    const ui = await mountAt($, surface)
    let version = 2
    for (const make of [f1, f2, f3]) {
      r.state.set(SLOT('graphSnapshot'), { value: asSnapshot(make()), version: version++ })
      await ui.redraw()
      expect(await ui.drawn()).toMatchObject({ type: 'Box' })
      expect(await ui.find({ key: 'detail-close' })).toBeDefined()
      expect(await ui.find({ key: 'detail-copy' })).toBeDefined()
    }
    await ui.unmount()
  })

  test(`pane on ${surface}: with no snapshot it draws a loading state without throwing`, async ($, on) => {
    rig(on)
    const ui = await mountAt($, surface)
    expect(await ui.find({ type: 'Text', text: 'Loading' })).toBeDefined()
    expect(await ui.find({ type: 'Svg' })).toBeUndefined()
    await ui.unmount()
  })

  test(`pane on ${surface}: an empty scope says so`, async ($, on) => {
    rig(on, { graphSnapshot: { ...asSnapshot(f1()), nodes: [], edges: [] }, graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    expect(await ui.find({ type: 'Text', text: 'No items' })).toBeDefined()
    expect(await ui.find({ key: 'legend' })).toBeUndefined()
    await ui.unmount()
  })
}

for (const [cw, ch] of [[10, 20], [8.4, 18]] as const) {
  test(`G10: cell ${cw}x${ch} sizes the Svg`, { options: { cellWidthPx: cw, cellHeightPx: ch } }, async ($, on) => {
    rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const ui = await mountAt($, 'desktop')
    const canvas = await found(ui, 'canvas')
    const svg = await ui.find({ type: 'Svg' })
    expect(svg?.props.width).toBe(Number(((canvas.props.width as number) * cw).toFixed(1)))
    expect(svg?.props.height).toBe(Number(((canvas.props.height as number) * ch).toFixed(1)))
    await ui.unmount()
  })
}

// ── click: detail, copy ─────────────────────────────────────────────────────────────────

const TO = 'mcp__mcp-task-orchestrator__'
const reply = (body: unknown) => ({ content: [{ type: 'text', text: JSON.stringify(body) }] })
const failed = (message: string) => ({ isError: true, content: [{ type: 'text', text: message }] })

function serveTo(on: On, over: { failContext?: boolean; failNotes?: boolean; failItem?: boolean } = {}) {
  const calls: { tool: string; args: Record<string, unknown> }[] = []
  const now = 10_000_000
  on('mcp.call', async (_$, e) => {
    calls.push({ tool: e.tool, args: e.args })
    if (e.tool === 'get_context') {
      if (over.failContext === true) return { value: failed('ctx boom') } as never

      return {
        value: reply({
          item: { id: A, title: 'Title T1', role: 'work' },
          gateStatus: { canAdvance: false, phase: 'work', missing: ['session-tracking'] },
          schema: [
            { key: 'implementation-notes', role: 'work', required: true, filled: true, seat: 'implementer' },
            { key: 'session-tracking', role: 'work', required: true, filled: false, seat: 'orchestrator' },
          ],
        }),
      } as never
    }
    if (e.tool === 'query_items') {
      if (over.failItem === true) return { value: failed('item boom') } as never

      return { value: reply({ id: A, title: 'Title T1', type: 'feature-task', summary: 'A summary', roleChangedAt: new Date(now - 125 * 60_000).toISOString() }) } as never
    }
    if (e.tool === 'query_notes') {
      if (over.failNotes === true) return { value: failed('notes boom') } as never

      return { value: reply({ notes: [{ key: 'implementation-notes', role: 'work', actor: { id: 'implementer:x', kind: 'subagent' } }] }) } as never
    }

    return { value: failed(`unexpected ${e.tool}`) } as never
  })

  return { calls, now }
}

const detailOf = (r: ReturnType<typeof rig>) => (r.valueOf('graphDetail') as { itemId: string; lines: string[] } | null | undefined) ?? null

for (const surface of SURFACES) {
  test(`G12: ${surface} clicking a box reads three read-only calls and shows the detail with authors`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const s = serveTo(on)
    mock.clock(on, { now: s.now })
    const ui = await mountAt($, surface)
    await ui.press({ key: `open:${A}` })
    await ui.redraw()
    const d = detailOf(r)
    expect(d?.itemId).toBe(A)
    expect(d?.lines).toContain('Title T1 [aaaa1111] · feature-task')
    expect(d?.lines).toContain('phase: work · in phase 2h 5m')
    expect(d?.lines).toContain('✓ implementation-notes · implementer · by implementer:x')
    expect(d?.lines).toContain('✗ session-tracking · orchestrator · missing')
    expect(s.calls.map(c => c.tool).sort()).toEqual(['get_context', 'query_items', 'query_notes'])
    expect(s.calls.find(c => c.tool === 'query_notes')?.args).toMatchObject({ operation: 'list', itemId: A, includeBody: false })
    expect(s.calls.find(c => c.tool === 'query_items')?.args).toMatchObject({ operation: 'get', itemId: A, includeTimestamps: true })
    expect(await ui.find({ key: 'detail-close' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'by implementer:x' })).toBeDefined()
    await ui.unmount()
  })

  test(`G12: ${surface} a failed get_context says Could not read; a failed notes read keeps the rest`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    serveTo(on, { failContext: true })
    mock.clock(on, { now: 1 })
    const ui = await mountAt($, surface)
    await ui.press({ key: `open:${A}` })
    expect(detailOf(r)?.lines[0]).toContain('Could not read aaaa1111')
    await ui.unmount()
  })

  test(`breadcrumb: ${surface} ancestors are buttons that switch scope; the root crumb goes to the whole project`, async ($, on) => {
    const trail = [{ id: 'proj0000-root', title: 'Project' }, { id: 'cont0000-feat', title: 'Features' }, { id: ROOT_ID, title: 'Root feature' }]
    const r = rig(on, { graphSnapshot: { ...asSnapshot(f1()), rootId: 'proj0000-root', trail }, graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    expect(await ui.find({ key: 'crumb:cont0000-feat' })).toBeDefined()
    // The current scope is plain text, not a button.
    expect((await ui.find({ key: `crumb:${ROOT_ID}` }))?.type).not.toBe('Button')
    await ui.press({ key: 'crumb:cont0000-feat' })
    expect(r.sets).toContainEqual({ key: 'graphScope', value: 'cont0000-feat' })
    await ui.press({ key: 'crumb:proj0000-root' })
    expect(r.sets).toContainEqual({ key: 'graphScope', value: null })
    await ui.unmount()
  })

  test(`whole-box click: ${surface} pressing a title line opens the same detail as the state line`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const s = serveTo(on)
    mock.clock(on, { now: s.now })
    const ui = await mountAt($, surface)
    await ui.press({ key: `open:${A}:1` })
    expect(detailOf(r)?.itemId).toBe(A)
    await ui.press({ key: `open:${B}:2` })
    expect(detailOf(r)?.itemId).toBe(B)
    await ui.unmount()
  })

  test(`G12: ${surface} a failed notes read adds (authors unavailable) and still draws the gate rows`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    serveTo(on, { failNotes: true })
    mock.clock(on, { now: 1 })
    const ui = await mountAt($, surface)
    await ui.press({ key: `open:${A}` })
    expect(detailOf(r)?.lines).toContain('(authors unavailable)')
    expect(detailOf(r)?.lines).toContain('✓ implementation-notes · implementer')
    await ui.unmount()
  })

  test(`G12: ${surface} Copy UUID hands the full id to ui.copy and toasts`, async ($, on) => {
    rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID, graphDetail: { itemId: 'aaaa1111-2222-3333-4444-555566667777', lines: ['x'] } })
    const copied: { text: string; surface?: string }[] = []
    on('ui.copy', async (_$, e) => {
      copied.push({ text: (e as { text: string }).text, surface: (e as { surface?: string }).surface })

      return { value: { isCopied: true } } as never
    })
    const ui = await mountAt($, surface)
    await ui.press({ key: 'detail-copy' })
    expect(copied).toHaveLength(1)
    expect(copied[0]?.text).toBe('aaaa1111-2222-3333-4444-555566667777')
    await ui.unmount()
  })

  test(`copy fallback: ${surface} when the surface refuses, the UUID goes to the host clipboard tool`, async ($, on) => {
    rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID, graphDetail: { itemId: "aaaa1111-2222-3333-4444-555566667777", lines: ["x"] } })
    on("ui.copy", async () => ({ value: { isCopied: false, reason: "remote" } }) as never)
    mock.env(on, { OS: "Windows_NT" })
    const runs: { argv: readonly string[]; stdin?: string }[] = []
    on("process.run", async (_$, e) => {
      const r = e as { argv: readonly string[]; init?: { stdin?: string }; stdin?: string }
      runs.push({ argv: r.argv, stdin: r.init?.stdin ?? r.stdin })

      return { value: { exitCode: 0, stdout: "", stderr: "" } } as never
    })
    const ui = await mountAt($, surface)
    await ui.press({ key: "detail-copy" })
    expect(runs.length).toBeGreaterThan(0)
    expect(runs[0]?.stdin).toBe("aaaa1111-2222-3333-4444-555566667777")
    await ui.unmount()
  })

  test(`G12: ${surface} a live worker is listed under the open detail`, async ($, on) => {
    rig(on, {
      graphSnapshot: asSnapshot(f1()),
      graphScope: ROOT_ID,
      graphDetail: { itemId: A, lines: ['x'] },
      graphActivity: { working: { [A]: [{ agentId: 'sub12345-abcd', seat: 'implementer', model: 'opus', at: 1 }] }, changed: {} },
    })
    const ui = await mountAt($, surface)
    expect(await ui.find({ type: 'Text', text: 'working: implementer · opus · sub12345' })).toBeDefined()
    await ui.unmount()
  })
}

// ── activity hooks ──────────────────────────────────────────────────────────────────────

const ok = { ref: 'r', result: { ok: true }, text: 'ok' }

function hookRig($: Parameters<Parameters<typeof test>[1]>[0], on: On, seed: Record<string, unknown> = {}) {
  void $
  const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID, ...seed })
  const clock = mock.clock(on, { now: 1_000_000 })
  on('mcp.call', async () => ({ value: failed('offline') }) as never)
  on('fs.read', async () => {
    throw new Error('ENOENT')
  })
  const seen: string[] = []
  on('tool.call', async (_$, e) => {
    seen.push(e.tool)

    return ok as never
  })

  return { r, clock, seen }
}

const working = (r: ReturnType<typeof rig>, id = A) => ((r.valueOf('graphActivity') as { working: Record<string, { agentId: string; seat: string }[]> } | undefined)?.working ?? {})[id]
const changedAt = (r: ReturnType<typeof rig>, id = A) => ((r.valueOf('graphActivity') as { changed: Record<string, number> } | undefined)?.changed ?? {})[id]

test('G14: a TO write on a snapshot item records the worker and the change; the result passes through', async ($, on) => {
  const { r } = hookRig($, on)
  const res = await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: A, trigger: 'start', actor: { id: 'implementer:c858:hand', kind: 'subagent' } }] } as never)
  expect(res).toMatchObject({ result: { ok: true }, text: 'ok' })
  expect(working(r)?.[0]).toMatchObject({ agentId: 'main', seat: 'implementer' })
  expect(changedAt(r)).toBe(1_000_000)
})

test('G14: a read tool sets working but not changed; an unknown id writes nothing', async ($, on) => {
  const { r } = hookRig($, on)
  await $.tool.call({ tool: `${TO}query_items`, operation: 'get', itemId: B } as never)
  expect(working(r, B)).toHaveLength(1)
  expect(changedAt(r, B)).toBeUndefined()
  const writes = r.sets.filter(s => s.key === 'graphActivity').length
  await $.tool.call({ tool: `${TO}advance_item`, itemId: 'ffffffff-0000', trigger: 'start' } as never)
  await $.tool.call({ tool: `${TO}manage_notes`, notes: [{ itemId: 'zz' }] } as never)
  expect(r.sets.filter(s => s.key === 'graphActivity')).toHaveLength(writes)
})

test('G14: a repeat call within 10s writes nothing; the entries expire on the mocked clock', async ($, on) => {
  const { r, clock } = hookRig($, on)
  await $.tool.call({ tool: `${TO}advance_item`, itemId: A, trigger: 'start' } as never)
  const writes = r.sets.filter(s => s.key === 'graphActivity').length
  await clock.advance(2_000)
  await $.tool.call({ tool: `${TO}advance_item`, itemId: A, trigger: 'start' } as never)
  expect(r.sets.filter(s => s.key === 'graphActivity')).toHaveLength(writes)
  await clock.advance(30_000)
  await clock.settle()
  expect(changedAt(r)).toBeUndefined()
  expect(working(r)).toHaveLength(1)
  await clock.advance(120_000)
  await clock.settle()
  expect(working(r)).toBeUndefined()
})

test("G14: the pane's own calls are not working; a foreign call is, and a throwing recorder never touches the result", async () => {
  const noted: { tool: string; changed: boolean }[] = []
  const io = { note: async (e: { tool: string }, changed: boolean) => void noted.push({ tool: e.tool, changed }) }
  const call = (plugin: string, result: unknown = ok) => Object.assign(async () => result, { origin: { plugin } })
  const e = { tool: `${TO}advance_item`, itemId: A }
  expect(await trackToolCall(io, e, call(PLUGIN) as never)).toBe(ok)
  expect(noted).toEqual([])
  expect(await trackToolCall(io, e, call('somebody-else') as never)).toBe(ok)
  expect(noted).toEqual([{ tool: e.tool, changed: true }])
  // A denied or errored write is still "working" but not "changed".
  const denied = { deny: 'no' }
  expect(await trackToolCall(io, e, call('somebody-else', denied) as never)).toBe(denied)
  expect(noted[1]).toEqual({ tool: e.tool, changed: false })
  const boom = { note: async () => Promise.reject(new Error('state down')) }
  expect(await trackToolCall(boom, e, call('somebody-else') as never)).toBe(ok)
})

test('G14: agent.spawn remembers the seat and model and returns the result untouched', async ($, on) => {
  const { r } = hookRig($, on)
  on('agent.spawn', async () => ({ model: 'claude-opus-5-5', agentId: 'sub-1' }) as never)
  const res = await $.agent.spawn({ subagentType: 'task-orchestrator:implementer', description: 'd', prompt: 'p' } as never)
  expect(res).toMatchObject({ model: 'claude-opus-5-5', agentId: 'sub-1' })
  expect(r.valueOf('graphAgents')).toEqual({ 'sub-1': { seat: 'implementer', model: 'claude-opus-5-5' } })
})

// ── G16/G20: the size bound ─────────────────────────────────────────────────────────────

/** `layers` rows of `width` cards, every card blocked by every card of the row above. */
const layered = (layers: number, width: number): GraphView => {
  const ns: GraphNode[] = []
  const es: GraphEdge[] = []
  for (let l = 0; l < layers; l++) {
    for (let k = 0; k < width; k++) ns.push(mk(`n${l}_${k}-xxxx`, 'queue', `T${l * width + k + 1}`, `Node ${l} ${k} title`))
    if (l > 0) for (let a = 0; a < width; a++) for (let b = 0; b < width; b++) es.push(blocks(`n${l - 1}_${a}-xxxx`, `n${l}_${b}-xxxx`))
  }

  return gview(ns, es)
}

/** A chain of `n` cards. */
const chain = (n: number): GraphView => {
  const ns = Array.from({ length: n }, (_, i) => mk(`n${String(i).padStart(3, '0')}-xxxx`, ['queue', 'work', 'review', 'terminal'][i % 4] as string, `T${i + 1}`, `Node ${i} with a fairly long title to fill the box width`))
  const es: GraphEdge[] = []
  for (let i = 1; i < ns.length; i++) es.push(blocks((ns[i - 1] as GraphNode).id, (ns[i] as GraphNode).id))

  return gview(ns, es)
}

const countNodes = (x: unknown): number => (typeof x === 'object' && x !== null ? 1 + ((x as El).children ?? []).reduce((n: number, c) => n + countNodes(c), 0) : 1)

const mountBig = async ($: Parameters<Parameters<typeof test>[1]>[0], on: On, surface: (typeof SURFACES)[number], v: GraphView) => {
  rig(on, { graphSnapshot: asSnapshot(v), graphScope: ROOT_ID, graphShowDone: true })

  return mountAt($, surface, 140)
}

const withinBudget = async (ui: Awaited<ReturnType<typeof mountAt>>) => {
  const tree = await ui.drawn()
  expect(JSON.stringify(tree).length).toBeLessThanOrEqual(TREE_CHAR_BUDGET)
  expect(countNodes(tree)).toBeLessThanOrEqual(ELEMENT_BUDGET)
}

test('G16: terminal merges edge cells into runs when per-cell boxes would not fit', async ($, on) => {
  const ui = await mountBig($, on, 'terminal', layered(4, 6))
  const boxes = (await ui.findAll({ type: 'Box' })).filter(b => typeof b.key === 'string' && b.key.startsWith('e:'))
  expect(boxes.some(b => (b.props.width as number) > 1)).toBe(true)
  expect(await ui.find({ type: 'Text', text: 'Too many edges' })).toBeUndefined()
  await withinBudget(ui)
  await ui.unmount()
})

test('G16: terminal over the run budget draws boxes only and says so', async ($, on) => {
  const ui = await mountBig($, on, 'terminal', layered(8, 8))
  expect(await ui.find({ type: 'Text', text: 'Too many edges to draw here; boxes only.' })).toBeDefined()
  expect((await ui.findAll({ type: 'Box' })).filter(b => typeof b.key === 'string' && b.key.startsWith('e:'))).toHaveLength(0)
  expect(await ui.find({ key: `card:${ROOT_ID}` })).toBeDefined()
  await withinBudget(ui)
  await ui.unmount()
})

test('G16: desktop falls back to boxes only when the Svg would pass the host px limit (30 cards in a row)', async ($, on) => {
  const row = gview(Array.from({ length: 30 }, (_, i) => mk(`n${i}_0-xxxx`, 'queue', `T${i + 1}`)))
  const ui = await mountBig($, on, 'desktop', row)
  expect(await ui.find({ type: 'Text', text: 'Too many edges to draw here; boxes only.' })).toBeDefined()
  expect(await ui.find({ type: 'Svg' })).toBeUndefined()
  expect(await ui.find({ key: `card:n0_0-xxxx` })).toBeDefined()
  await ui.unmount()
})

for (const surface of SURFACES) {
  test(`G20: ${surface} a 115-card chain draws boxes within the tree budget`, async ($, on) => {
    const ui = await mountBig($, on, surface, chain(115))
    expect(await ui.find({ key: 'card:n114-xxxx' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'Too large' })).toBeUndefined()
    await withinBudget(ui)
    await ui.unmount()
  })

  test(`G20: ${surface} the 150-item cap is refused with a notice, never an oversize tree`, async ($, on) => {
    const ui = await mountBig($, on, surface, chain(149))
    expect(await ui.find({ type: 'Text', text: 'Too large to draw (150 items). Open a smaller scope.' })).toBeDefined()
    expect(await ui.find({ key: 'canvas' })).toBeUndefined()
    await withinBudget(ui)
    await ui.unmount()
  })
}

// ── whole-click budget (T11 review B1) ──────────────────────────────────────────────────

/** `layered` with UUID-length ids, the realistic worst case for the serialised tree. */
const layeredUuid = (layers: number, width: number): GraphView => {
  const id = (l: number, k: number) => `${String(l).padStart(4, "0")}${String(k).padStart(4, "0")}-1111-2222-3333-444455556666`
  const ns: GraphNode[] = []
  const es: GraphEdge[] = []
  for (let l = 0; l < layers; l++) {
    for (let k = 0; k < width; k++) ns.push(mk(id(l, k), "queue", `T${l * width + k + 1}`, `Node ${l} ${k} with a fairly long title`))
    if (l > 0) for (let k = 0; k < width; k++) for (const a of [k, (k + 1) % width]) es.push(blocks(id(l - 1, a), id(l, k)))
  }

  return gview(ns, es)
}

test("B1: the edge plan charges whole-click cards more", () => {
  expect(COST.wholeCardChars).toBeGreaterThan(COST.cardChars)
  const at = (wholeClick: boolean) => edgePlan({ desktop: true, wholeClick, cards: 80, chips: 0, cells: 0, runs: 0, svgChars: 20000, svgWidth: 1000, svgHeight: 1000 })
  expect(at(false)).toBe("full")
  expect(at(true)).toBe("omit")
})

const TRAIL4 = [0, 1, 2, 3].map(i => ({ id: `${i}${"0".repeat(7)}-1111-2222-3333-444455556666`, title: `Ancestor ${i} with a long title` }))
const DETAIL = (itemId: string) => ({ itemId, lines: Array.from({ length: 12 }, (_, i) => `detail line ${i} with some text`) })

/** A chain of `n` cards with UUID-length ids (the real per-card cost). */
const chainUuid = (n: number): GraphView => {
  const id = (i: number) => `${String(i).padStart(8, "0")}-1111-2222-3333-444455556666`
  const ns = Array.from({ length: n }, (_, i) => mk(id(i), ["queue", "work", "review", "terminal"][i % 4] as string, `T${i + 1}`, `Node ${i} with a fairly long title to fill the box width`))
  const es: GraphEdge[] = []
  for (let i = 1; i < n; i++) es.push(blocks(id(i - 1), id(i)))

  return gview(ns, es)
}

for (const surface of SURFACES) {
  test(`B2: ${surface} a 115-card UUID chain with detail and trail draws within the tree budget`, async ($, on) => {
    const v = chainUuid(115)
    rig(on, { graphSnapshot: { ...asSnapshot(v), trail: TRAIL4 }, graphScope: ROOT_ID, graphShowDone: true, graphDetail: DETAIL((v.nodes[1] as GraphNode).id) })
    const ui = await mountAt($, surface, 200)
    expect(await ui.find({ type: "Text", text: "Too large to draw (116 items). Open a smaller scope." })).toBeUndefined()
    expect(await ui.find({ key: `card:${(v.nodes[115] as GraphNode).id}` })).toBeDefined()
    await withinBudget(ui)
    await ui.unmount()
  })

  test(`B2: ${surface} a 143-card UUID chain with detail and trail is refused, never an oversize tree`, async ($, on) => {
    const v = chainUuid(143)
    rig(on, { graphSnapshot: { ...asSnapshot(v), trail: TRAIL4 }, graphScope: ROOT_ID, graphShowDone: true, graphDetail: DETAIL((v.nodes[1] as GraphNode).id) })
    const ui = await mountAt($, surface, 200)
    expect(await ui.find({ type: "Text", text: "Too large to draw (144 items). Open a smaller scope." })).toBeDefined()
    await withinBudget(ui)
    await ui.unmount()
  })
}

test("B3: the detail and trail are charged to the edges only: they can drop the Svg but never refuse the boxes", () => {
  const base = { desktop: true, wholeClick: true, cards: 75, chips: 0, cells: 0, runs: 0, svgChars: 16000, svgWidth: 1000, svgHeight: 1000 }
  expect(edgePlan(base)).toBe("full")
  const extra = extrasChars(DETAIL("x").lines, TRAIL4.map(c => c.title))
  expect(extra).toBeGreaterThan(2000)
  expect(edgePlan({ ...base, extraChars: extra })).toBe("omit")
  expect(edgePlan({ ...base, desktop: false, wholeClick: false, cards: 120, svgChars: 0, extraChars: 50000 })).toBe("omit")
})

for (const [layers, width] of [[10, 7], [19, 4], [25, 3]] as const) {
  test(`B3: desktop ${layers}x${width} whole-click + Svg with detail and trail stays within budget across redraws`, async ($, on) => {
    const v = layeredUuid(layers, width)
    rig(on, { graphSnapshot: { ...asSnapshot(v), trail: TRAIL4 }, graphScope: ROOT_ID, graphShowDone: true, graphDetail: DETAIL((v.nodes[1] as GraphNode).id) })
    const ui = await mountAt($, "desktop", 200)
    for (let k = 0; k < 10; k++) await ui.redraw()
    await withinBudget(ui)
    await ui.unmount()
  })
}

for (const [layers, width] of [[14, 6], [17, 5], [8, 11]] as const) {
  test(`B1: desktop ${layers}x${width} whole-click cards with the detail open stay within the tree budget`, async ($, on) => {
    const v = layeredUuid(layers, width)
    const first = (v.nodes[1] as GraphNode).id
    rig(on, { graphSnapshot: { ...asSnapshot(v), trail: TRAIL4 }, graphScope: ROOT_ID, graphShowDone: true, graphDetail: DETAIL(first) })
    const ui = await mountAt($, "desktop", 200)
    // Whole-click is on: the title line is a Button.
    expect(await ui.find({ key: `open:${first}:1` })).toBeDefined()
    await withinBudget(ui)
    await ui.unmount()
  })
}

for (const [n, whole] of [[WHOLE_CLICK_MAX - 1, true], [WHOLE_CLICK_MAX, false]] as const) {
  test(`B1: whole-click boundary: ${n} cards ${whole ? "are" : "are not"} whole-click`, async ($, on) => {
    const ns = Array.from({ length: n }, (_, i) => mk(`b${String(i).padStart(3, "0")}-xxxx`, "queue", `T${i + 1}`))
    rig(on, { graphSnapshot: asSnapshot(gview(ns)), graphScope: ROOT_ID, graphShowDone: true })
    const ui = await mountAt($, "desktop", 200)
    expect((await ui.find({ key: "open:b000-xxxx:1" })) !== undefined).toBe(whole)
    await withinBudget(ui)
    await ui.unmount()
  })
}

// ── source guard, controls ──────────────────────────────────────────────────────────────

test('the pane calls only read tools', () => {
  expect(READ_TOOLS).toEqual(['query_items', 'get_context', 'query_notes'])
  for (const tool of READ_TOOLS) expect(WRITE_TOOLS.has(tool)).toBe(false)
})

test('S15: Reconnect bumps graphReconnectRequest; Whole project clears the scope', async ($, on) => {
  const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID, graphStatus: { refreshing: false, liveSource: 'poll' } })
  const ui = await mountAt($, 'terminal')
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

for (const surface of SURFACES) {
  for (const [name, status, shown] of DEGRADE_CASES) {
    test(`S14/S13: ${surface}, ${name}: Reconnect ${shown ? 'shown' : 'hidden'}, never Refresh`, async ($, on) => {
      rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID, graphStatus: status })
      const ui = await mountAt($, surface)
      expect((await ui.find({ key: 'reconnect' })) !== undefined).toBe(shown)
      expect(await ui.find({ key: 'refresh' })).toBeUndefined()
      expect(await ui.find({ key: 'scope-project' })).toBeDefined()
      expect(await ui.find({ key: 'scope-feature' })).toBeDefined()
      await ui.unmount()
    })
  }

  test(`S12: ${surface} draws the legend with a ready entry and the plan-label key when labelled`, async ($, on) => {
    rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    expect(await ui.find({ key: 'legend' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'done' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'ready' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'plan label' })).toBeDefined()
    await ui.unmount()
  })

  test(`S12: ${surface} has no plan-label key when nothing is labelled`, async ($, on) => {
    const plain = { ...asSnapshot(f1()), nodes: f1().nodes.map(n => ({ ...n, planLabel: undefined })) }
    rig(on, { graphSnapshot: plain, graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    expect(await ui.find({ type: 'Text', text: 'plan label' })).toBeUndefined()
    await ui.unmount()
  })

  for (const [scope, active, inactive] of [[ROOT_ID, 'scope-feature', 'scope-project'], [null, 'scope-project', 'scope-feature']] as const) {
    test(`S20: ${surface}, scope ${scope ?? 'null'}: ${active} is primary`, async ($, on) => {
      rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: scope })
      const ui = await mountAt($, surface)
      expect((await ui.find({ key: active }))?.props.variant).toBe('primary')
      expect((await ui.find({ key: inactive }))?.props.variant).toBeUndefined()
      await ui.unmount()
    })
  }
}

// ── project-root overview, drill-down, pending switch ───────────────────────────────────

/** The overview snapshot of the project root: three children with role roll-ups, no edges. */
const ov = (): GraphSnapshot => ({
  scopeId: ROOT_ID,
  rootId: ROOT_ID,
  overview: true,
  nodes: [
    node(ROOT_ID, 'work', 0, null, 'Root project'),
    { ...node(A, 'work', 1, ROOT_ID, 'Features'), childCounts: { work: 9, review: 4, terminal: 63 } },
    { ...node(B, 'queue', 1, ROOT_ID, 'Bugs'), childCounts: { queue: 2 } },
    node(C, 'terminal', 1, ROOT_ID, 'Done leaf'),
  ],
  edges: [],
  external: {},
  gates: {},
  takenAt: 1,
  truncated: false,
})

test('overview: rollupLine omits zero counts, calls terminal done and orders work, review, blocked, queue, done', () => {
  expect(rollupLine({ work: 9, review: 4, terminal: 63 })).toBe('9 work · 4 review · 63 done')
  expect(rollupLine({ terminal: 1, queue: 3, blocked: 2, work: 0 })).toBe('2 blocked · 3 queue · 1 done')
  expect(rollupLine({})).toBe('')
  expect(rollupLine(undefined)).toBe('')
})

test('overview: cards take the roll-up as their state line, their own role colour (no ready tint) and no deps', () => {
  const m = cardsOf(ov())
  expect(m.root?.id).toBe(ROOT_ID)
  expect(m.cards.map(c => [c.id, c.stateText, c.ready, c.deps.length])).toEqual([
    [A, '9 work · 4 review · 63 done', false, 0],
    [B, '2 queue', false, 0],
    [C, 'done', false, 0],
  ])
})

test('pending switch (unit): the wanted scope reads null as the root id; a mismatch with the loaded scope is a switch', () => {
  const snap = ov()
  expect(isSwitching(null, snap)).toBe(false)
  expect(isSwitching(ROOT_ID, snap)).toBe(false)
  expect(isSwitching(A, snap)).toBe(true)
  expect(isSwitching(A, null)).toBe(false)
  expect(isSwitching(null, { ...snap, rootId: null })).toBe(false)
  expect(loadingTitle(A, snap)).toBe('Features')
  expect(loadingTitle('ffff0000-aaaa', snap)).toBe('ffff0000')
  expect(scopeHeader(snap)).toBe('Project: Root project')
  expect(scopeHeader({ ...f1(), rootId: 'x' })).toBe('Scope: Root feature')
})

for (const surface of SURFACES) {
  test(`overview: ${surface} root scope draws the root plus ONE row of roll-up boxes, no edges`, async ($, on) => {
    rig(on, { graphSnapshot: ov(), graphScope: null })
    const ui = await mountAt($, surface)
    expect((await found(ui, `open:${A}`)).props.label).toBe('9 work · 4 review · 63 done')
    expect((await found(ui, `open:${B}`)).props.label).toBe('2 queue')
    expect((await found(ui, `open:${C}`)).props.label).toBe('done')
    const a = await found(ui, `card:${A}`)
    const b = await found(ui, `card:${B}`)
    const c = await found(ui, `card:${C}`)
    expect(a.props).toMatchObject({ backgroundColor: '#c2410c' })
    expect(b.props).toMatchObject({ backgroundColor: '#6b7280' })
    expect(c.props).toMatchObject({ backgroundColor: '#15803d' })
    expect(new Set([a.props.top, b.props.top, c.props.top]).size).toBe(1)
    expect(a.props.top).toBeGreaterThan(((await found(ui, `card:${ROOT_ID}`)).props.top as number) + 2)
    expect(await ui.find({ key: 'done-steps' })).toBeUndefined()
    expect(await ui.find({ type: 'Text', text: 'Project: Root project' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'Loading' })).toBeUndefined()
    await ui.unmount()
  })

  test(`overview: ${surface} an all-terminal child row is not folded into a done chip`, async ($, on) => {
    const done = { ...ov(), nodes: ov().nodes.map(n => (n.depth === 1 ? { ...n, role: 'terminal' } : n)) }
    rig(on, { graphSnapshot: done, graphScope: null })
    const ui = await mountAt($, surface)
    expect(await ui.find({ key: `card:${A}` })).toBeDefined()
    expect(await ui.find({ key: 'step:1' })).toBeUndefined()
    await ui.unmount()
  })

  test(`drill-down: ${surface} clicking a box then Open graph scopes to that item and closes the detail`, async ($, on) => {
    const r = rig(on, { graphSnapshot: ov(), graphScope: null })
    const s = serveTo(on)
    mock.clock(on, { now: s.now })
    const ui = await mountAt($, surface)
    await ui.press({ key: `open:${A}` })
    await ui.redraw()
    expect(await ui.find({ key: 'detail-open-graph' })).toBeDefined()
    await ui.press({ key: 'detail-open-graph' })
    await ui.redraw()
    expect(r.sets).toContainEqual({ key: 'graphScope', value: A })
    expect(detailOf(r)).toBeNull()
    await ui.unmount()
  })

  test(`drill-down: ${surface} the detail of the scope's own box has no Open graph`, async ($, on) => {
    const r = rig(on, { graphSnapshot: ov(), graphScope: null })
    const s = serveTo(on)
    mock.clock(on, { now: s.now })
    const ui = await mountAt($, surface)
    await ui.press({ key: `open:${ROOT_ID}` })
    await ui.redraw()
    expect(detailOf(r)?.itemId).toBe(ROOT_ID)
    expect(await ui.find({ key: 'detail-open-graph' })).toBeUndefined()
    await ui.unmount()
  })

  test(`pending switch: ${surface} shows Loading <title> and none of the old graph or its header`, async ($, on) => {
    rig(on, { graphSnapshot: ov(), graphScope: A })
    const ui = await mountAt($, surface)
    expect(await ui.find({ type: 'Text', text: 'Loading Features…' })).toBeDefined()
    expect(await ui.find({ key: 'canvas' })).toBeUndefined()
    expect(await ui.find({ type: 'Text', text: 'Project: Root project' })).toBeUndefined()
    expect(await ui.find({ key: 'scope-project' })).toBeDefined()
    await ui.unmount()
  })

  test(`pending switch: ${surface} an unknown scope shows its short id`, async ($, on) => {
    rig(on, { graphSnapshot: ov(), graphScope: 'ffff0000-1111-2222-3333-444444444444' })
    const ui = await mountAt($, surface)
    expect(await ui.find({ type: 'Text', text: 'Loading ffff0000…' })).toBeDefined()
    await ui.unmount()
  })

  test(`pending switch: ${surface} a snapshot that matches the scope draws normally, no Loading line`, async ($, on) => {
    rig(on, { graphSnapshot: f1(), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    expect(await ui.find({ key: 'canvas' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'Loading' })).toBeUndefined()
    await ui.unmount()
  })
}

// Overview wrap: a root overview whose one row does not fit the pane splits over several lines.
test('overview wrap: 25 edge-free children fit a 90-column pane over several lines, no containment lines', () => {
  const kids = Array.from({ length: 25 }, (_, i) => mk(`k${String(i).padStart(7, '0')}`, 'work', `T${i + 1}`))
  const model = cardsOf(gview(kids))
  const steps = stepsOf(model.cards)
  const lay = layoutTD(model.cards, steps, { bodyColumns: 92, showDone: true, hasRoot: true, wrap: true })
  expect(lay.wrapped).toBe(true)
  expect(lay.tooWide).toBeNull()
  expect(lay.rows.length).toBeGreaterThan(1)
  for (const r of lay.cards.values()) expect(r.left + r.width).toBeLessThanOrEqual(lay.cols)
  expect(routes(lay, model.cards, 'px').filter(e => e.kind === 'contain')).toHaveLength(0)
  // Without wrap the same row is too wide.
  expect(layoutTD(model.cards, steps, { bodyColumns: 92, showDone: true, hasRoot: true }).tooWide).not.toBeNull()
})

// ── T12a 13497d52: critical path, stall and gate-blocked warnings, one focus stop per box ──

const PURPLE = '#a855f7'
const critOf = (v: GraphView) => {
  const model = cardsOf(v)

  return criticalPath(model.cards, stepsOf(model.cards))
}

test('S1: F2 critical path is A, B, C along consecutive edges; the direct A->C edge is not on it', () => {
  const p = critOf(f2())
  expect(p.ids).toEqual([A, B, C])
  expect([...p.edges].sort()).toEqual([`${A}|${B}`, `${B}|${C}`])
  expect(p.edges.has(`${A}|${C}`)).toBe(false)
})

test('S1: ties go to the smaller plan number, at each step and between end cards', () => {
  // Two equal chains: T5 -> T6 and T1 -> T2; the T2 end wins.
  const v = gview([mk('p1', 'work', 'T5'), mk('q1', 'queue', 'T6'), mk('p2', 'work', 'T1'), mk('q2', 'queue', 'T2')], [blocks('p1', 'q1'), blocks('p2', 'q2')])
  expect(critOf(v).ids).toEqual(['p2', 'q2'])
  // Two equal predecessors of one card: T3 beats T9.
  const w = gview([mk('x', 'work', 'T9'), mk('y', 'work', 'T3'), mk('z', 'queue', 'T4')], [blocks('x', 'z'), blocks('y', 'z')])
  expect(critOf(w).ids).toEqual(['y', 'z'])
})

test('S2: a terminal card breaks the chain; a length-1 result gives no path', () => {
  // A (done) -> B (work) -> C (queue): the chain is B, C only.
  const v = gview([mk(A, 'terminal', 'T1'), mk(B, 'work', 'T2'), mk(C, 'queue', 'T3')], [blocks(A, B), blocks(B, C)])
  expect(critOf(v).ids).toEqual([B, C])
  // A (work) -> C (done), B alone: no open chain of two cards.
  const one = gview([mk(A, 'work', 'T1'), mk(B, 'queue', 'T2'), mk(C, 'terminal', 'T3')], [blocks(A, C)])
  expect(critOf(one)).toEqual({ ids: [], edges: new Set() })
  // A satisfied blocker (unblockAt work, blocker in work) is not an open step.
  const early = gview([mk(A, 'work', 'T1'), mk(C, 'queue', 'T3')], [{ ...blocks(A, C), unblockAt: 'work' }])
  expect(critOf(early).ids).toEqual([])
})

test('S4 (unit): a dependency cycle terminates; back edges never join the path', () => {
  const cyc = gview([mk(A, 'queue', 'T1'), mk(B, 'queue', 'T2'), mk(C, 'queue', 'T3')], [blocks(A, B), blocks(B, C), blocks(C, A)])
  const p = critOf(cyc)
  expect(p.ids.length).toBeLessThanOrEqual(3)
  const model = cardsOf(cyc)
  const back = stepsOf(model.cards).back
  for (const e of p.edges) expect(back.has(e)).toBe(false)
})

test('S4: desktop Svg strokes the two path edges purple with the ac marker; the direct edge stays amber', () => {
  const b = build(f2())
  const p = criticalPath(b.model.cards, b.steps)
  const svg = edgeSvg(routes(b.lay, b.model.cards, 'px', p.edges), { w: 8.4, h: 18 }, b.lay.width, b.lay.height)
  expect(svg.split(`stroke="${PURPLE}"`)).toHaveLength(3)
  expect(svg.split('url(#ac)')).toHaveLength(3)
  expect(svg).toContain('<marker id="ac"')
  const long = svg.split('<path ').find(x => x.includes('M411.6 162.0 V189.0 H558.6'))
  expect(long).toContain('marker-end="url(#ao)"')
  // No path, no ac marker at all.
  expect(edgeSvg(routes(b.lay, b.model.cards, 'px'), { w: 8.4, h: 18 }, b.lay.width, b.lay.height)).not.toContain('id="ac"')
})

test('S4: terminal path cells are heavy and purple, never dim; the A->C channel keeps its open style', () => {
  const b = build(f2())
  const p = criticalPath(b.model.cards, b.steps)
  const cells = raster(routes(b.lay, b.model.cards, 'cell', p.edges))
  // The A->B drop shares its cell with the A->C branch: a heavy tee, purple.
  expect(at(cells, 49, 10)).toEqual({ glyph: '┣', color: PURPLE, dim: false })
  expect(at(cells, 49, 11)).toEqual({ glyph: '▼', color: PURPLE, dim: false })
  expect(at(cells, 49, 9)).toEqual({ glyph: '┃', color: PURPLE, dim: false })
  for (const y of [11, 12, 13, 14, 15]) expect(at(cells, 66, y)).toEqual({ glyph: '╎', color: '#f59e0b', dim: false })
  const heavy = new Set(['┃', '━', '┏', '┓', '┗', '┛', '┣', '┫', '┳', '┻', '╋', '▼'])
  for (const c of cells.values()) if (c.color === PURPLE) expect(heavy.has(c.glyph)).toBe(true)
})

for (const surface of SURFACES) {
  test(`S3: ${surface} draws a 1-wide purple stripe at each path card's left/top; card subtrees are unchanged`, async ($, on) => {
    rig(on, { graphSnapshot: asSnapshot(f2()), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    for (const id of [A, B, C]) {
      const card = await found(ui, `card:${id}`)
      const stripe = await found(ui, `crit:${id}`)
      expect(stripe.props).toMatchObject({ position: 'absolute', top: card.props.top, left: card.props.left, width: 1, height: 3, backgroundColor: PURPLE })
      expect(JSON.stringify(card)).not.toContain(PURPLE)
    }
    expect(await ui.find({ key: `crit:${ROOT_ID}` })).toBeUndefined()
    const legend = await ui.find({ type: 'Text', text: '━ critical path' })
    expect(legend?.props.color).toBe(PURPLE)
    // The stripe follows its card in drawn order.
    const canvas = await found(ui, 'canvas')
    const keys = canvas.children.map(c => (c as El).props.key)
    expect(keys.indexOf(`crit:${B}`)).toBe(keys.indexOf(`card:${B}`) + 1)
    await ui.unmount()
  })

  test(`S3: ${surface} a card on the path draws exactly as off it (G5 method)`, async ($, on) => {
    // A alone (no path) and A heading F2's path sit in the same cells with the same state line.
    const r = rig(on, { graphSnapshot: asSnapshot(gview([mk(A, 'work', 'T1')])), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    const alone = await json(ui, `card:${A}`)
    expect(await ui.find({ key: `crit:${A}` })).toBeUndefined()
    r.state.set(SLOT('graphSnapshot'), { value: asSnapshot(f2()), version: 7 })
    await ui.redraw()
    expect(await ui.find({ key: `crit:${A}` })).toBeDefined()
    expect(await json(ui, `card:${A}`)).toBe(alone)
    await ui.unmount()
  })

  test(`S3: ${surface} the root overview draws no critical path`, async ($, on) => {
    const o = { ...ov(), edges: [blocks(A, B)] }
    rig(on, { graphSnapshot: o, graphScope: null })
    const ui = await mountAt($, surface)
    expect((await ui.findAll({ type: 'Box' })).filter(b => typeof b.key === 'string' && b.key.startsWith('crit:'))).toHaveLength(0)
    expect(await ui.find({ type: 'Text', text: 'critical path' })).toBeUndefined()
    await ui.unmount()
  })
}

// ── warnings ────────────────────────────────────────────────────────────────────────────

test('S10: line 3 priority is open blockers > live worker > gate-blocked > stalled > ready > phase', () => {
  const by = (m: ReturnType<typeof cardsOf>, id: string) => m.cards.find(c => c.id === id) as Card
  const v = gview([mk(A, 'work', 'T1'), mk(B, 'work', 'T2'), mk(C, 'queue', 'T3')], [blocks(A, C)], { stalled: { A: [], [A]: ['session-tracking'], [B]: ['implementation-notes', 'session-tracking'], [C]: ['x'] } })
  const w = { at: 1, agentId: 'main', seat: 'implementer' }
  const blocked = { [B]: { at: 1, missing: ['review-checklist'], target: 'review' }, [C]: { at: 1, missing: ['y'] } }
  const plain = cardsOf(v)
  expect(by(plain, A).stateText).toBe('⚠ stalled · session-tracking')
  expect(by(plain, A).warn).toBe(true)
  expect(by(plain, B).stateText).toBe('⚠ stalled · implementation-notes, session-tracking')
  // Gate-blocked beats stalled; open blockers beat both.
  const g = cardsOf(v, {}, {}, blocked)
  expect(by(g, B).stateText).toBe('✗ gate: review-checklist')
  expect(by(g, B).warn).toBe(true)
  expect(by(g, C).stateText).toBe('⊘ after T1')
  expect(by(g, C).warn).toBe(false)
  // A live worker beats both warnings: a worked card is never shown stalled.
  const live = cardsOf(v, { [A]: [w], [B]: [w] }, {}, blocked)
  expect(by(live, A).stateText).toBe('» impl')
  expect(by(live, B).stateText).toBe('» impl')
  expect(by(live, B).warn).toBe(false)
  // No warning: ready, then the phase.
  const calm = cardsOf(gview([mk(A, 'work', 'T1'), mk(B, 'queue', 'T2')]))
  expect(by(calm, B).stateText).toBe('ready')
  expect(by(calm, A).stateText).toBe('work')
  expect(by(calm, A).warn).toBe(false)
  // A gate block with no missing keys names the target role.
  expect(by(cardsOf(v, {}, {}, { [B]: { at: 1, missing: [], target: 'review' } }), B).stateText).toBe('✗ gate: review')
})

test('S10: the overview shows no warnings', () => {
  const o = { ...ov(), stalled: { [A]: ['x'] } }
  const m = cardsOf(o, {}, {}, { [B]: { at: 1, missing: ['y'] } })
  for (const c of m.cards) {
    expect(c.warn).toBe(false)
    expect(c.stateText.startsWith('⚠') || c.stateText.startsWith('✗')).toBe(false)
  }
})

for (const surface of SURFACES) {
  test(`S10: ${surface} warning lines are undimmed; other state lines stay dim; the overview draws none`, async ($, on) => {
    const v = { ...asSnapshot(gview([mk(A, 'work', 'T1'), mk(B, 'work', 'T2'), mk(C, 'queue', 'T3')])), stalled: { [A]: ['session-tracking'] } }
    const r = rig(on, { graphSnapshot: v, graphScope: ROOT_ID, graphActivity: { working: {}, changed: {}, blocked: { [B]: { at: 1, missing: ['review-checklist'] } } } })
    const ui = await mountAt($, surface)
    const a = await found(ui, `open:${A}`)
    expect(a.props.label).toBe('⚠ stalled · session-tracking')
    expect(a.props.dimColor).toBeUndefined()
    const b = await found(ui, `open:${B}`)
    expect(b.props.label).toBe('✗ gate: review-checklist')
    expect(b.props.dimColor).toBeUndefined()
    expect((await found(ui, `open:${C}`)).props.dimColor).toBe(true)
    // An activity value written before gate blocks existed (no \`blocked\`) still draws.
    r.state.set(SLOT('graphActivity'), { value: { working: {}, changed: {} }, version: 9 })
    await ui.redraw()
    expect((await found(ui, `open:${B}`)).props.label).toBe('work')
    // The overview: no warning marks.
    r.state.set(SLOT('graphSnapshot'), { value: { ...ov(), stalled: { [A]: ['x'] } }, version: 10 })
    r.state.set(SLOT('graphScope'), { value: null, version: 10 })
    r.state.set(SLOT('graphActivity'), { value: { working: {}, changed: {}, blocked: { [B]: { at: 1, missing: ['y'] } } }, version: 10 })
    await ui.redraw()
    expect(String((await found(ui, `open:${A}`)).props.label)).toBe('9 work · 4 review · 63 done')
    expect(String((await found(ui, `open:${B}`)).props.label)).toBe('2 queue')
    await ui.unmount()
  })
}

test('S9: gateBlocks reads the gate_blocked rows of an advance_item result and nothing else', () => {
  const text = JSON.stringify({
    results: [
      { itemId: A, trigger: 'start', applied: false, errorCode: 'gate_blocked', missingNotes: [{ key: 'implementation-notes', description: 'd' }, 'session-tracking'], targetRole: 'review' },
      { itemId: B, trigger: 'start', applied: true, newRole: 'work' },
      { itemId: C, trigger: 'start', applied: false, errorCode: 'dependency_blocked' },
    ],
    summary: { total: 3, succeeded: 1, failed: 2 },
  })
  expect(gateBlocks(`${TO}advance_item`, text)).toEqual([{ itemId: A, missing: ['implementation-notes', 'session-tracking'], targetRole: 'review' }])
  expect(gateBlocks(`${TO}advance_item`, 'not json')).toEqual([])
  expect(gateBlocks(`${TO}advance_item`, undefined)).toEqual([])
  expect(gateBlocks(`${TO}manage_notes`, text)).toEqual([])
  expect(gateBlocks('Bash', text)).toEqual([])
})

test('S9: trackToolCall hands the blocks to the recorder after the activity and returns the result unchanged', async () => {
  const order: string[] = []
  const gated: unknown[] = []
  const io = { note: async () => void order.push('note'), gate: async (rows: unknown) => void (order.push('gate'), gated.push(rows)) }
  const ran = { ref: 'r', result: {}, text: JSON.stringify({ results: [{ itemId: A, applied: false, errorCode: 'gate_blocked', missingNotes: ['x'] }] }) }
  const call = Object.assign(async () => ran, { origin: { plugin: 'somebody-else' } })
  expect(await trackToolCall(io, { tool: `${TO}advance_item`, itemId: A }, call as never)).toBe(ran)
  expect(order).toEqual(['note', 'gate'])
  expect(gated).toEqual([[{ itemId: A, missing: ['x'] }]])
  // A throwing recorder never touches the result.
  const boom = { note: async () => undefined, gate: async () => Promise.reject(new Error('down')) }
  expect(await trackToolCall(boom, { tool: `${TO}advance_item`, itemId: A }, call as never)).toBe(ran)
})

test('S9: recordGateBlocks keeps identity on a repeat, pruneActivity expires blocks after 10 min, a later write clears one', () => {
  const t0 = 1_000_000
  const one = recordGateBlocks(emptyActivity(), [{ id: A, missing: ['x'], target: 'review' }], t0)
  expect(one.blocked).toEqual({ [A]: { at: t0, missing: ['x'], target: 'review' } })
  expect(recordGateBlocks(one, [{ id: A, missing: ['x'], target: 'review' }], t0 + 1_000)).toBe(one)
  expect(pruneActivity(one, t0 + GATE_BLOCK_MS)).toBe(one)
  expect(pruneActivity(one, t0 + GATE_BLOCK_MS + 1).blocked).toBeUndefined()
  // A later TO write on the item clears it; a read does not.
  const read = recordActivity(one, { id: A, agentId: 'main', seat: 'main', changed: false }, t0 + 20_000)
  expect(read.blocked?.[A]).toBeDefined()
  const wrote = recordActivity(read, { id: A, agentId: 'main', seat: 'main', changed: true }, t0 + 20_001)
  expect(wrote.blocked).toBeUndefined()
  // An activity value without \`blocked\` (written before T12a) is tolerated and stays without one.
  const legacy = { working: {}, changed: {} }
  expect(pruneActivity(legacy, t0)).toBe(legacy)
  expect(recordActivity(legacy, { id: B, agentId: 'main', seat: 'main', changed: true }, t0).blocked).toBeUndefined()
})

test('S9: a gate-blocked advance_item marks its card; a later write on it clears the mark; the mark expires', async ($, on) => {
  const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
  const clock = mock.clock(on, { now: 1_000_000 })
  on('mcp.call', async () => ({ value: failed('offline') }) as never)
  on('fs.read', async () => {
    throw new Error('ENOENT')
  })
  const blockedText = JSON.stringify({ results: [{ itemId: A, trigger: 'start', applied: false, errorCode: 'gate_blocked', missingNotes: [{ key: 'implementation-notes' }], targetRole: 'review' }], summary: { total: 1, succeeded: 0, failed: 1 } })
  const appliedText = JSON.stringify({ results: [{ itemId: A, trigger: 'complete', applied: true }], summary: { total: 1, succeeded: 1, failed: 0 } })
  const triggerOf = (e: unknown) => ((e as { transitions?: { trigger?: string }[] }).transitions ?? [])[0]?.trigger
  on('tool.call', async (_$, e) => ({ ref: 'r', result: {}, text: triggerOf(e) === 'start' ? blockedText : appliedText }) as never)
  const blockedOf = () => (r.valueOf('graphActivity') as { blocked?: Record<string, unknown> } | undefined)?.blocked
  const res = await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: A, trigger: 'start' }] } as never)
  expect(res).toMatchObject({ text: blockedText })
  expect(blockedOf()?.[A]).toEqual({ at: 1_000_000, missing: ['implementation-notes'], target: 'review' })
  // A later successful write on the item clears the mark. (The clock stays put: past 300ms the mod's own
  // re-snapshot would land, and with this offline server it holds no items to attribute calls to.)
  await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: A, trigger: 'complete' }] } as never)
  expect(blockedOf()?.[A]).toBeUndefined()
  // Blocked again; ten minutes on the mocked clock expire it.
  await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: A, trigger: 'start' }] } as never)
  expect(blockedOf()?.[A]).toBeDefined()
  await clock.advance(GATE_BLOCK_MS + 1_000)
  await clock.settle()
  expect(blockedOf()?.[A]).toBeUndefined()
})

// ── keyboard: one focus stop per box ────────────────────────────────────────────────────

test('S11: focusTarget sends each box to its one stop and walks backward box by box', () => {
  const order = ['R', 'X', 'Y']
  // Forward into a box (Tab from a control or the previous box): its state line.
  expect(focusTarget(order, 'scope-project', 'open:R:1')).toBe('open:R')
  expect(focusTarget(order, 'open:R', 'open:X:1')).toBe('open:X')
  expect(focusTarget(order, undefined, 'open:Y:2')).toBe('open:Y')
  // Backward from a box's stop lands on its own line 2: go to the previous box's stop.
  expect(focusTarget(order, 'open:Y', 'open:Y:2')).toBe('open:X')
  expect(focusTarget(order, 'open:X', 'open:X:2')).toBe('open:R')
  // The first box backward: pass through (toward the controls above).
  expect(focusTarget(order, 'open:R', 'open:R:2')).toBe('open:R:2')
  // A state line, a control, an unknown box or no recorded order: pass through.
  expect(focusTarget(order, 'open:R', 'open:X')).toBe('open:X')
  expect(focusTarget(order, 'open:X', 'scope-project')).toBe('scope-project')
  expect(focusTarget(order, 'open:X', 'open:Z:1')).toBe('open:Z:1')
  expect(focusTarget(null, 'open:X', 'open:Y:1')).toBe('open:Y:1')
  expect(focusTarget(order, 'open:X', undefined)).toBeUndefined()
})

test('S11: redirectFocus rewrites the element, and remembers it only when the move was not denied', async () => {
  resetFocusState()
  recordFocusOrder(['R', 'X'])
  const seen: (string | undefined)[] = []
  const pass = async (e: { element?: string }) => (seen.push(e.element), {})
  expect(await redirectFocus({ element: 'open:X:1', origin: { kind: 'person' } }, pass)).toEqual({})
  expect(seen).toEqual(['open:X'])
  // Now on open:X, a backward move to its line 2 goes to R.
  await redirectFocus({ element: 'open:X:2' }, pass)
  expect(seen[1]).toBe('open:R')
  // A denied move is not remembered: the ring is still on open:R.
  await redirectFocus({ element: 'open:X:1' }, async () => ({ deny: 'busy' }))
  await redirectFocus({ element: 'open:R:2' }, pass)
  expect(seen[2]).toBe('open:R:2')
  resetFocusState()
})

test('S12 (unit): recordFocusOrder keeps a copy; null clears it', () => {
  resetFocusState()
  const ids = ['R', 'X']
  recordFocusOrder(ids)
  ids.push('Y')
  expect(focusOrder()).toEqual(['R', 'X'])
  recordFocusOrder(null)
  expect(focusOrder()).toBeNull()
})

for (const surface of SURFACES) {
  test(`S12: ${surface} boxes draw root first, then rows top-down, left to right; Tab over the drawn Buttons stops once per box`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    // The drawn order (the same loop records the focus order): root, row 1 (A, B) left to right, row 2 (C).
    const canvas = await found(ui, 'canvas')
    const order = canvas.children.map(c => String((c as El).props.key)).filter(k => k.startsWith('card:')).map(k => k.slice(5))
    expect(order).toEqual([ROOT_ID, A, B, C])
    // The person's Tab proposes the next drawn Button; the hook body sends it to the box's one stop.
    // (The live ui.focus dispatch into the plugin cannot be driven from a mount: see implementation-notes.)
    resetFocusState()
    recordFocusOrder(order)
    const buttons = (await ui.findAll({ type: 'Button' })).map(b => String(b.props.key))
    const pass = async (e: { element?: string }) => ({ landed: e.element })
    const walk = async (step: 1 | -1, from: string | undefined, moves: number): Promise<string[]> => {
      const stops: string[] = []
      let cur = from
      for (let k = 0; k < moves; k++) {
        const proposed = buttons[(cur === undefined ? -1 : buttons.indexOf(cur)) + step]
        if (proposed === undefined) break
        cur = ((await redirectFocus({ element: proposed }, pass)) as { landed?: string }).landed
        stops.push(cur as string)
      }

      return stops
    }
    const forward = await walk(1, undefined, 50)
    expect(forward.filter(k => k.startsWith('open:'))).toEqual([`open:${ROOT_ID}`, `open:${A}`, `open:${B}`, `open:${C}`])
    // Backward, box by box, until the first box (which passes through toward the controls above).
    expect(await walk(-1, `open:${C}`, 3)).toEqual([`open:${B}`, `open:${A}`, `open:${ROOT_ID}`])
    expect(await walk(-1, `open:${ROOT_ID}`, 1)).toEqual([`open:${ROOT_ID}:2`])
    // Too large to draw: no boxes at all.
    r.state.set(SLOT('graphSnapshot'), { value: asSnapshot(chain(149)), version: 5 })
    r.state.set(SLOT('graphShowDone'), { value: true, version: 5 })
    await ui.redraw()
    expect(await ui.find({ key: 'canvas' })).toBeUndefined()
    await ui.unmount()
    resetFocusState()
  })
}

// ── budget with stripes (B-style, real UUID ids) ────────────────────────────────────────

/** A chain of `n` open (work) cards with UUID ids: the critical path is every card. */
const openChainUuid = (n: number): GraphView => {
  const id = (i: number) => `${String(i).padStart(8, '0')}-1111-2222-3333-444455556666`
  const ns = Array.from({ length: n }, (_, i) => mk(id(i), 'work', `T${i + 1}`, `Node ${i} with a fairly long title to fill the box width`))
  const es: GraphEdge[] = []
  for (let i = 1; i < n; i++) es.push(blocks(id(i - 1), id(i)))

  return gview(ns, es)
}

test('B4: stripes are charged to the edges and never refuse a scope', () => {
  // Too many edge cells for either terminal edge drawing: the plan is boxes only or refused.
  const base = { desktop: false, wholeClick: false, chips: 0, cells: 5000, runs: 5000, svgChars: 0, svgWidth: 0, svgHeight: 0 }
  // At the refusal boundary, a stripe per card still draws the boxes (the stripes are dropped instead).
  const last = { ...base, cards: 123 }
  expect(edgePlan(last)).toBe('omit')
  expect(edgePlan({ ...last, marks: 122 })).toBe('omit')
  expect(marksFit({ ...last, marks: 122 }, 'omit')).toBe(false)
  expect(edgePlan({ ...base, cards: 124, marks: 0 })).toBe('too-large')
  expect(marksFit({ ...base, cards: 124, marks: 0 }, 'too-large')).toBe(false)
  // A plan that draws edges has room for its stripes; many stripes can drop the edges.
  const small = { ...base, desktop: true, cards: 40, svgChars: 20000, svgWidth: 1000, svgHeight: 1000 }
  expect(edgePlan(small)).toBe('full')
  expect(marksFit({ ...small, marks: 40 }, edgePlan({ ...small, marks: 40 }))).toBe(true)
  expect(edgePlan({ ...small, wholeClick: true, cards: 75, marks: 75 })).toBe('omit')
  expect(COST.markChars).toBeGreaterThanOrEqual(240)
})

for (const surface of SURFACES) {
  for (const n of [40, 60, 89, 90]) {
    test(`B4: ${surface} a ${n}-card open UUID chain (every card on the path) with detail and trail stays within budget across redraws`, async ($, on) => {
      const v = openChainUuid(n)
      rig(on, { graphSnapshot: { ...asSnapshot(v), trail: TRAIL4 }, graphScope: ROOT_ID, graphShowDone: true, graphDetail: DETAIL((v.nodes[1] as GraphNode).id) })
      const ui = await mountAt($, surface, 200)
      for (let k = 0; k < 5; k++) await ui.redraw()
      expect(await ui.find({ type: 'Text', text: 'Too large' })).toBeUndefined()
      await withinBudget(ui)
      await ui.unmount()
    })
  }

  for (const open of [false, true]) {
    test(`B4: ${surface} refusal cutoff unchanged with a full-length path, detail ${open ? 'open' : 'closed'}: 122 draw, 123 refused`, async ($, on) => {
      const r = rig(on, { graphSnapshot: { ...asSnapshot(openChainUuid(122)), trail: TRAIL4 }, graphScope: ROOT_ID, graphShowDone: true, ...(open ? { graphDetail: DETAIL('x') } : {}) })
      const ui = await mountAt($, surface, 200)
      expect(await ui.find({ type: 'Text', text: 'Too large' })).toBeUndefined()
      expect(await ui.find({ key: `card:${String(121).padStart(8, '0')}-1111-2222-3333-444455556666` })).toBeDefined()
      await withinBudget(ui)
      r.state.set(SLOT('graphSnapshot'), { value: { ...asSnapshot(openChainUuid(123)), trail: TRAIL4 }, version: 9 })
      await ui.redraw()
      expect(await ui.find({ type: 'Text', text: 'Too large to draw (124 items). Open a smaller scope.' })).toBeDefined()
      await withinBudget(ui)
      await ui.unmount()
    })
  }
}

// ── T12b 6a976f10: cross-session marks, gate toasts, budget with every decoration ─────

test('S14 (unit): cardsOf marks a card remote only when its id is in graphRemote', () => {
  const m = cardsOf(f1(), {}, {}, {}, { [A]: 1, 'not-in-scope': 2 })
  expect(m.cards.map(c => [c.id, c.remote])).toEqual([[A, true], [B, false], [C, false]])
  expect(cardsOf(f1()).cards.every(c => c.remote === false)).toBe(true)
})

for (const surface of SURFACES) {
  test(`S14: ${surface} a remote mark draws ⇄ on line 1 of its card only, after ✱; the other cards are unchanged`, async ($, on) => {
    const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
    const ui = await mountAt($, surface)
    const keys = [`card:${ROOT_ID}`, `card:${B}`, `card:${C}`]
    const before = Object.fromEntries(await Promise.all(keys.map(async k => [k, await json(ui, k)])))
    expect(JSON.stringify(await ui.find({ key: 'canvas' }))).not.toContain('⇄')
    r.state.set(SLOT('graphRemote'), { value: { [A]: 1, 'ffffffff-not-in-snapshot': 2 }, version: 3 })
    await ui.redraw()
    expect(String((await found(ui, `open:${A}:1`)).props.label).startsWith('⇄ ◉ [T1] ')).toBe(true)
    for (const k of keys) expect(await json(ui, k)).toBe(before[k])
    // The drawn tree (ui.find adds a text digest that repeats each label): exactly one mark.
    expect(JSON.stringify(await ui.drawn()).split('⇄')).toHaveLength(2)
    // A recent change too: ✱ first, then ⇄.
    r.state.set(SLOT('graphActivity'), { value: { working: {}, changed: { [A]: 1 } }, version: 4 })
    await ui.redraw()
    expect(String((await found(ui, `open:${A}:1`)).props.label).startsWith('✱ ⇄ ◉ [T1] ')).toBe(true)
    // Cleared (a local write): the mark goes.
    r.state.set(SLOT('graphRemote'), { value: {}, version: 5 })
    await ui.redraw()
    expect(JSON.stringify(await ui.find({ key: 'canvas' }))).not.toContain('⇄')
    await ui.unmount()
  })
}

const gateBlockedRig = (on: On) => {
  const r = rig(on, { graphSnapshot: asSnapshot(f1()), graphScope: ROOT_ID })
  mock.clock(on, { now: 1_000_000 })
  on('mcp.call', async () => ({ value: failed('offline') }) as never)
  on('fs.read', async () => {
    throw new Error('ENOENT')
  })
  const out = { text: JSON.stringify({ results: [{ itemId: A, trigger: 'start', applied: false, errorCode: 'gate_blocked', missingNotes: [{ key: 'implementation-notes' }], targetRole: 'review' }], summary: { total: 1, succeeded: 0, failed: 1 } }) }
  on('tool.call', async () => ({ ref: 'r', result: {}, text: out.text }) as never)
  const toasts: string[] = []
  on('ui.toast', async (_$, e) => {
    toasts.push(e.text)

    return { value: undefined } as never
  })

  return { toasts, out, r }
}

test('S8: with graphToasts on, a gate-blocked advance_item of this session toasts its label, target and missing notes', { options: { graphToasts: true } }, async ($, on) => {
  const { toasts, out } = gateBlockedRig(on)
  await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: A, trigger: 'start' }] } as never)
  expect(toasts).toEqual(['✗ Gate blocked: T1 -> review: implementation-notes'])
  // An applied row raises none.
  out.text = JSON.stringify({ results: [{ itemId: A, applied: true }] })
  await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: A, trigger: 'start' }] } as never)
  expect(toasts).toHaveLength(1)
})

test('S8: with graphToasts at its default (off), a gate-blocked advance_item raises no toast but still marks the card', async ($, on) => {
  const { toasts, r } = gateBlockedRig(on)
  await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: A, trigger: 'start' }] } as never)
  expect(toasts).toEqual([])
  expect((r.valueOf('graphActivity') as { blocked?: Record<string, unknown> }).blocked?.[A]).toBeDefined()
})

/** A chain of `n` open UUID cards with long titles: every decoration on at once is the worst case per card. */
const decoratedChain = (n: number) => {
  const id = (i: number) => `${String(i).padStart(8, '0')}-1111-2222-3333-444455556666`
  const ns = Array.from({ length: n }, (_, i) => mk(id(i), 'work', `T${i + 1}`, `Node ${i} with a really quite long title that fills both title lines of the box and then runs on further`))
  const es: GraphEdge[] = []
  for (let i = 1; i < n; i++) es.push(blocks(id(i - 1), id(i)))
  const ids = ns.map(x => x.id)
  const stalled = Object.fromEntries(ids.map(x => [x, ['implementation-notes', 'session-tracking', 'delegation-metadata']]))

  return {
    ids,
    seed: (detail: boolean) => ({
      graphSnapshot: { ...asSnapshot(gview(ns, es)), trail: TRAIL4, stalled },
      graphScope: ROOT_ID,
      graphShowDone: true,
      graphRemote: Object.fromEntries(ids.map(x => [x, 1])),
      graphActivity: { working: {}, changed: Object.fromEntries(ids.map(x => [x, 1])), blocked: Object.fromEntries(ids.filter((_, i) => i % 2 === 0).map(x => [x, { at: 1, missing: ['review-checklist', 'implementation-notes'], target: 'review' }])) },
      ...(detail ? { graphDetail: DETAIL(ids[1] as string) } : {}),
    }),
  }
}

for (const surface of SURFACES) {
  for (const [n, whole] of [[40, true], [WHOLE_CLICK_MAX, false]] as const) {
    test(`B5: ${surface} a fully decorated ${whole ? 'whole-click' : 'state-line-only'} card (✱ ⇄, warning, UUID, long title, path) stays within its per-card cost`, async ($, on) => {
      const cost = whole ? COST.wholeCardChars : COST.cardChars
      const d = decoratedChain(n)
      rig(on, d.seed(true))
      const ui = await mountAt($, surface, 200)
      expect((await ui.find({ key: `open:${d.ids[0]}:1` })) !== undefined).toBe(whole)
      // Measured in the drawn tree (what the host bounds), not ui.find's form, which adds a key and a text digest.
      const drawn = (await ui.drawn()) as El
      const byKey = new Map<string, unknown>()
      const walk = (x: unknown): void => {
        if (typeof x !== 'object' || x === null) return
        const k = (x as El).props?.key
        if (typeof k === 'string' && k.startsWith('card:')) byKey.set(k, x)
        ;((x as El).children ?? []).forEach(walk)
      }
      walk(drawn)
      const sizes = d.ids.map(x => JSON.stringify(byKey.get(`card:${x}`)).length)
      const max = Math.max(...sizes)
      expect(max).toBeLessThanOrEqual(cost)
      expect(JSON.stringify(await ui.find({ key: `card:${d.ids[n - 1]}` }))).toContain('⇄')
      await ui.unmount()
    })
  }

  for (const n of [40, 89, 90]) {
    test(`B5: ${surface} a ${n}-card fully decorated UUID chain with detail and trail stays within budget across redraws`, async ($, on) => {
      rig(on, decoratedChain(n).seed(true))
      const ui = await mountAt($, surface, 200)
      for (let k = 0; k < 5; k++) await ui.redraw()
      expect(await ui.find({ type: 'Text', text: 'Too large' })).toBeUndefined()
      await withinBudget(ui)
      await ui.unmount()
    })
  }

  for (const open of [false, true]) {
    test(`B5: ${surface} refusal cutoff unchanged with every decoration, detail ${open ? 'open' : 'closed'}: 122 draw, 123 refused`, async ($, on) => {
      const r = rig(on, decoratedChain(122).seed(open))
      const ui = await mountAt($, surface, 200)
      expect(await ui.find({ type: 'Text', text: 'Too large' })).toBeUndefined()
      expect(JSON.stringify(await ui.find({ key: `card:${String(121).padStart(8, '0')}-1111-2222-3333-444455556666` }))).toContain('⇄')
      await withinBudget(ui)
      const next = decoratedChain(123).seed(open)
      for (const [key, value] of Object.entries(next)) r.state.set(SLOT(key), { value, version: 9 })
      await ui.redraw()
      expect(await ui.find({ type: 'Text', text: 'Too large to draw (124 items). Open a smaller scope.' })).toBeDefined()
      await withinBudget(ui)
      // Toggling the detail never flips the scope: 123 stays refused, 122 stays drawn.
      r.state.set(SLOT('graphDetail'), { value: open ? null : DETAIL('x'), version: 10 })
      await ui.redraw()
      expect(await ui.find({ type: 'Text', text: 'Too large to draw (124 items). Open a smaller scope.' })).toBeDefined()
      for (const [key, value] of Object.entries(decoratedChain(122).seed(!open))) r.state.set(SLOT(key), { value, version: 11 })
      if (open) r.state.set(SLOT('graphDetail'), { value: null, version: 11 })
      await ui.redraw()
      expect(await ui.find({ type: 'Text', text: 'Too large' })).toBeUndefined()
      await withinBudget(ui)
      await ui.unmount()
    })
  }
}

// ── 1262993f: the default scope follows the latest transition (activeScope, /to-graph, This feature) ──
// Oracle: the item's summary rule and the frozen diagnosis decisions D1-D6 (test-plan S1-S17), never the code.

const WR = 'wroot000-0000-4000-8000-000000000000'
const WFEATURES = 'wfeats00-0000-4000-8000-000000000000'
const WBUGS = 'wbugs000-0000-4000-8000-000000000000'
const WF = 'wfeat000-0000-4000-8000-000000000001'
const WT = 'wtask000-0000-4000-8000-000000000002'
const WU = 'wsubt000-0000-4000-8000-000000000003'
const WT2 = 'wtask000-0000-4000-8000-000000000004'
const WF2 = 'wfeat000-0000-4000-8000-000000000005'
const WT3 = 'wtask000-0000-4000-8000-000000000006'
const WB = 'wbug0000-0000-4000-8000-000000000007'
const WA = 'wstale00-0000-4000-8000-000000000008'
const WD = 'wdirect0-0000-4000-8000-000000000009'
const WW = 'wnotype0-0000-4000-8000-00000000000a'
const WZ = 'wother00-0000-4000-8000-00000000000b'
const WO = 'wotherr0-0000-4000-8000-00000000000c'
const WNOW = 1_760_000_000_000

type WItem = { id: string; parentId: string | null; title: string; type?: string; role: string }
const WITEMS: WItem[] = [
  { id: WR, parentId: null, title: 'Project', type: 'project', role: 'work' },
  { id: WFEATURES, parentId: WR, title: 'Features', type: 'container', role: 'work' },
  { id: WBUGS, parentId: WR, title: 'Bugs', type: 'container', role: 'work' },
  { id: WF, parentId: WFEATURES, title: 'Feature F', type: 'feature-implementation', role: 'work' },
  { id: WA, parentId: WFEATURES, title: 'Stale feature A', type: 'feature-implementation', role: 'work' },
  { id: WT, parentId: WF, title: 'Task T', type: 'feature-task', role: 'work' },
  { id: WU, parentId: WF, title: 'Subtask U', type: 'feature-task', role: 'work' },
  { id: WT2, parentId: WU, title: 'Task T2', type: 'feature-task', role: 'work' },
  { id: WF2, parentId: WF, title: 'Nested feature F2', type: 'feature-implementation', role: 'work' },
  { id: WT3, parentId: WF2, title: 'Task T3', type: 'feature-task', role: 'work' },
  { id: WB, parentId: WBUGS, title: 'Bug B', type: 'bug-fix', role: 'work' },
  { id: WD, parentId: WR, title: 'Bug directly under the root', type: 'bug-fix', role: 'work' },
  { id: WW, parentId: WR, title: 'Item with no type', role: 'work' },
  { id: WO, parentId: null, title: 'Other project', type: 'project', role: 'work' },
  { id: WZ, parentId: WO, title: 'Other project task', type: 'feature-task', role: 'work' },
]

type WWorld = { items?: WItem[]; active?: string[]; transitions?: string[] | null; failResume?: boolean; failGetOf?: string }
const ancestorsOf = (items: WItem[], id: string): { id: string; title: string; depth: number }[] => {
  const out: { id: string; title: string; depth: number }[] = []
  let cur = items.find(i => i.id === id)?.parentId ?? null
  while (cur !== null) {
    const p = items.find(i => i.id === cur)
    if (p === undefined) break
    out.unshift({ id: p.id, title: p.title, depth: 0 })
    cur = p.parentId
  }
  out.forEach((a, i) => {
    a.depth = i
  })

  return out
}

/** The TO server's reads for this item: replies are parsed JSON, a failure is the string 'FAIL'. */
function worldReply(w: WWorld, tool: string, args: Record<string, unknown>): unknown {
  const items = w.items ?? WITEMS
  if (tool === 'get_context' && args.mode === 'session-resume') {
    if (w.failResume === true) return 'FAIL'
    const active = (w.active ?? []).map(id => ({ id, title: items.find(i => i.id === id)?.title ?? id, role: 'work', ancestors: ancestorsOf(items, id) }))
    const rows = w.transitions === null ? undefined : (w.transitions ?? []).map((itemId, k) => ({ itemId, fromRole: 'queue', toRole: 'work', transitionedAt: new Date(WNOW - k * 1000).toISOString() }))

    return { activeItems: active, ...(rows !== undefined ? { recentTransitions: rows } : {}) }
  }
  if (tool === 'query_items' && args.operation === 'get') {
    const id = String(args.itemId)
    if (w.failGetOf === id) return 'FAIL'
    const it = items.find(i => i.id === id)
    if (it === undefined) return 'FAIL'

    return { id: it.id, title: it.title, role: it.role, ...(it.type !== undefined ? { type: it.type } : {}), ...(args.includeAncestors === true ? { ancestors: ancestorsOf(items, id) } : {}) }
  }
  // The legacy rule: newest work-role feature-implementation under the root.
  if (tool === 'query_items' && args.operation === 'search' && args.type === 'feature-implementation') return { items: [{ id: WA, title: 'Stale feature A', type: 'feature-implementation', role: 'work' }], total: 1, returned: 1 }
  if (tool === 'query_items') return { items: [], total: 0, returned: 0, limit: 50, offset: 0 }
  if (tool === 'query_dependencies') return { dependencies: [] }

  return 'FAIL'
}

type WCall = { tool: string; args: Record<string, unknown> }
const fakeCall = (w: WWorld) => {
  const calls: WCall[] = []
  const call = async (tool: string, args: Record<string, unknown>): Promise<unknown> => {
    calls.push({ tool, args })
    const r = worldReply(w, tool, args)
    if (r === 'FAIL') throw new Error(`${tool} failed`)

    return r
  }

  return { call, calls }
}

const scopeOf = (w: WWorld) => activeScope(fakeCall(w).call, WR, WNOW)

test('S1 (unit): a task in work resolves to its feature', async () => {
  expect(await scopeOf({ active: [WT], transitions: [WT] })).toBe(WF)
})

test('S2 (unit): a bug under a container resolves to the bug itself, never the stale feature', async () => {
  expect(await scopeOf({ active: [WA, WB], transitions: [WB, WA] })).toBe(WB)
})

test('S3 (unit): the first ACTIVE transition wins over a newer inactive one; duplicates and order are taken as returned', async () => {
  // WB is not active (terminal), so the newer transitions on it are skipped; the first active one names WT.
  expect(await scopeOf({ active: [WT], transitions: [WB, WB, WT, WA] })).toBe(WF)
  // The first active in returned order wins even when a later row is also active.
  expect(await scopeOf({ active: [WT, WB], transitions: [WB, WT] })).toBe(WB)
  expect(await scopeOf({ active: [WT, WB], transitions: [WT, WB] })).toBe(WF)
})

test('S4 (unit): nothing active, the newest transition (terminal) still names its feature via the ancestors read', async () => {
  const { call, calls } = fakeCall({ active: [], transitions: [WT, WB] })
  expect(await activeScope(call, WR, WNOW)).toBe(WF)
  expect(calls.filter(c => c.tool === 'query_items' && c.args.operation === 'get' && c.args.itemId === WT && c.args.includeAncestors === true)).toHaveLength(1)
})

test('S7 (unit): a newest transition belonging to another project resolves to null', async () => {
  expect(await scopeOf({ active: [], transitions: [WZ] })).toBeNull()
})

test('S10 (unit): a feature left in queue by a gate-blocked start cascade still owns its task', async () => {
  const items = WITEMS.map(i => (i.id === WF ? { ...i, role: 'queue' } : i))
  expect(await scopeOf({ items, active: [WT], transitions: [WT] })).toBe(WF)
})

test('S11 (unit): an item directly under the root resolves to itself', async () => {
  expect(await scopeOf({ active: [WD], transitions: [WD] })).toBe(WD)
})

test('S12 (unit): non-container intermediates are skipped on the way to the feature', async () => {
  expect(await scopeOf({ active: [WT2], transitions: [WT2] })).toBe(WF)
})

test('S13 (unit): the nearest feature wins; the root-first ancestors are walked nearest-first', async () => {
  expect(await scopeOf({ active: [WT3], transitions: [WT3] })).toBe(WF2)
})

test('S14 (unit): a moved item that is itself a container resolves to null', async () => {
  expect(await scopeOf({ active: [WBUGS], transitions: [WBUGS] })).toBeNull()
})

test('S15 (unit): a node reply with no type counts as a non-container', async () => {
  expect(await scopeOf({ active: [WW], transitions: [WW] })).toBe(WW)
})

test('probe (unit): empty and absent replies are all "no candidate"; repeat calls agree', async () => {
  expect(await scopeOf({ active: [], transitions: [] })).toBeNull()
  expect(await scopeOf({ active: [], transitions: null })).toBeNull()
  expect(await scopeOf({ active: [WT], transitions: [] })).toBeNull()
  const w: WWorld = { active: [WB], transitions: [WB] }
  expect(await scopeOf(w)).toBe(WB)
  expect(await scopeOf(w)).toBe(WB)
})

test('S16 (unit): one session-resume read with the exact request shape, read tools only, bounded gets', async () => {
  expect(SCOPE_LOOKBACK_MS).toBe(14 * 24 * 60 * 60 * 1000)
  const { call, calls } = fakeCall({ active: [WT], transitions: [WT] })
  expect(await activeScope(call, WR, WNOW)).toBe(WF)
  expect(calls[0]).toEqual({ tool: 'get_context', args: { mode: 'session-resume', since: new Date(WNOW - SCOPE_LOOKBACK_MS).toISOString(), ancestorId: WR, includeAncestors: true, limit: 200 } })
  expect(calls.filter(c => c.tool === 'get_context')).toHaveLength(1)
  for (const c of calls) expect(READ_TOOLS).toContain(c.tool)
  // Chain is root > Features > F > T: at most one get per ancestor below the root.
  expect(calls.filter(c => c.tool === 'query_items').length).toBeLessThanOrEqual(3)
})

// Integration: the command and the button, with the TO server and project config under the loaded plugin.

const WCONFIG = `project:\n  rootId: ${WR}\n`

function scopeRig(on: On, w: WWorld, opts: { config?: boolean; seed?: Record<string, unknown> } = {}) {
  const r = rig(on, opts.seed ?? {})
  const calls: WCall[] = []
  const toasts: string[] = []
  on('fs.read', async (_$, e) => {
    const path = String((e as { path: string }).path).split(String.fromCharCode(92)).join('/')
    if (opts.config !== false && path.endsWith('.taskorchestrator/config.yaml')) return { value: WCONFIG } as never
    throw new Error(`ENOENT ${path}`)
  })
  mock.env(on, {})
  mock.clock(on, { now: WNOW })
  on('mcp.call', async (_$, e) => {
    calls.push({ tool: e.tool, args: e.args })
    const out = worldReply(w, e.tool, e.args)

    return { value: out === 'FAIL' ? failed(`${e.tool} boom`) : reply(out) } as never
  })
  on('session.start', async (_$, e) => ({ cwd: e.cwd }) as never)
  on('ui.open', async () => ({ value: { isPlaced: true } }) as never)
  on('ui.close', async () => ({ value: undefined }) as never)
  on('ui.status', async () => ({ value: undefined }) as never)
  on('ui.toast', async (_$, e) => {
    toasts.push(e.text)

    return { value: undefined } as never
  })
  on('command.register', async () => ({ value: undefined }) as never)

  return { r, calls, toasts }
}

type WEngine = Parameters<Parameters<typeof test>[1]>[0]
const runToGraph = async ($: WEngine) => {
  await $.session.start({ cwd: '/work/project', surface: 'terminal', isInteractive: true } as never)
  await $.command.run({ command: 'to-graph', args: '' } as never)
}
const scopeSets = (r: ReturnType<typeof rig>) => r.sets.filter(s => s.key === 'graphScope').map(s => s.value)
const paneSeed = { graphSnapshot: { ...asSnapshot(f1()), rootId: WR }, graphScope: null }
const pressFeature = async ($: WEngine) => {
  const ui = await mountAt($, 'terminal')
  await ui.press({ key: 'scope-feature' })
  await ui.unmount()
}

// The live repro: stale feature A (work) is what the legacy rule returns; bug B is what moved.
const REPRO: WWorld = { active: [WA, WB], transitions: [WB, WA] }

test('S2 (int): /to-graph with no argument scopes to the bug that moved, not the stale feature', async ($, on) => {
  const { r } = scopeRig(on, REPRO)
  await runToGraph($)
  expect(r.valueOf('graphScope')).toBe(WB)
  expect(scopeSets(r)).not.toContain(WA)
})

test('S1 (int): /to-graph resolves a task in work to its feature', async ($, on) => {
  const { r } = scopeRig(on, { active: [WT], transitions: [WT] })
  await runToGraph($)
  expect(r.valueOf('graphScope')).toBe(WF)
})

test('S4 (int): nothing active, the newest move is a terminal task: its feature', async ($, on) => {
  const { r } = scopeRig(on, { active: [], transitions: [WT] })
  await runToGraph($)
  expect(r.valueOf('graphScope')).toBe(WF)
})

test('S17 (int): the This feature button picks the bug that moved, as the command does', async ($, on) => {
  const { r } = scopeRig(on, REPRO, { seed: paneSeed })
  await pressFeature($)
  expect(scopeSets(r)).toContain(WB)
  expect(scopeSets(r)).not.toContain(WA)
})

test('S1 (int): the This feature button resolves a task in work to its feature', async ($, on) => {
  const { r } = scopeRig(on, { active: [WT], transitions: [WT] }, { seed: paneSeed })
  await pressFeature($)
  expect(scopeSets(r)).toContain(WF)
})

test('S5 (int): a failed session-resume read falls back to the legacy newest-work feature', async ($, on) => {
  const { r } = scopeRig(on, { ...REPRO, failResume: true })
  await runToGraph($)
  expect(r.valueOf('graphScope')).toBe(WA)
})

test('S6 (int): no recent transitions falls back to the legacy rule', async ($, on) => {
  const { r } = scopeRig(on, { active: [WB], transitions: [] })
  await runToGraph($)
  expect(r.valueOf('graphScope')).toBe(WA)
})

test('S7 (int): a newest transition from another project falls back to the legacy rule', async ($, on) => {
  const { r } = scopeRig(on, { active: [], transitions: [WZ] })
  await runToGraph($)
  expect(r.valueOf('graphScope')).toBe(WA)
})

test('S8 (int): a failing read during the walk falls back to the legacy rule (command)', async ($, on) => {
  const { r } = scopeRig(on, { active: [], transitions: [WT], failGetOf: WT })
  await runToGraph($)
  expect(r.valueOf('graphScope')).toBe(WA)
})

test('S8 (int): the button also falls back when the walk fails', async ($, on) => {
  const { r } = scopeRig(on, { active: [], transitions: [WT], failGetOf: WT }, { seed: paneSeed })
  await pressFeature($)
  expect(scopeSets(r)).toContain(WA)
})

test('S9 (int): with no project rootId the command makes no scope-resolution MCP call', async ($, on) => {
  const { calls } = scopeRig(on, REPRO, { config: false })
  await runToGraph($)
  expect(calls.filter(c => c.tool === 'get_context' || (c.tool === 'query_items' && c.args.operation !== 'overview'))).toEqual([])
})

// -- 8f1064d7: auto vs pinned scope; the auto scope follows this session's own TO activity --
// Oracle: the item's summary clauses (Q1-Q4, container sentence QC) and task-scope sections (TS-n) as frozen in
// test-plan S1-S17, never the code. Reuses the 1262993f world (WITEMS/worldReply/fakeCall/scopeRig fixtures).

// Real UUID-shaped (hex) ids for the paths that parse ids from text or prefixes.
const HF = 'abcdef01-0000-4000-8000-0000000000f1'
const HT = 'abcdef02-0000-4000-8000-0000000000f2'
const HA = 'abcdef03-0000-4000-8000-0000000000f3'
const HITEMS: WItem[] = [
  ...WITEMS,
  { id: HF, parentId: WFEATURES, title: 'Hex feature', type: 'feature-implementation', role: 'work' },
  { id: HT, parentId: HF, title: 'Hex task', type: 'feature-task', role: 'work' },
  { id: HA, parentId: WFEATURES, title: 'Hex stale feature', type: 'feature-implementation', role: 'work' },
]
const FW = (over: WWorld = {}): WWorld => ({ items: HITEMS, ...over })

/** worldReply that also accepts a unique id prefix on `query_items get` (the tool accepts one), answering with the full id. */
const prefixReply = (w: WWorld, tool: string, args: Record<string, unknown>): unknown => {
  if (tool === 'query_items' && args.operation === 'get') {
    const id = String(args.itemId)
    const hit = (w.items ?? WITEMS).filter(i => i.id.startsWith(id))
    if (hit.length === 1 && hit[0] !== undefined) return worldReply(w, tool, { ...args, itemId: hit[0].id })
  }

  return worldReply(w, tool, args)
}
const pfxCall = (w: WWorld) => {
  const calls: WCall[] = []
  const call = async (tool: string, args: Record<string, unknown>): Promise<unknown> => {
    calls.push({ tool, args })
    const r = prefixReply(w, tool, args)
    if (r === 'FAIL') throw new Error(`${tool} failed`)

    return r
  }

  return { call, calls }
}

/** scopeRig with a prefix-aware TO server, a passthrough tool.call and an agent.spawn answer. */
function followRig(on: On, w: WWorld, seed: Record<string, unknown>) {
  const r = rig(on, seed)
  const calls: WCall[] = []
  on('fs.read', async (_$, e) => {
    const path = String((e as { path: string }).path).split(String.fromCharCode(92)).join('/')
    if (path.endsWith('.taskorchestrator/config.yaml')) return { value: WCONFIG } as never
    throw new Error(`ENOENT ${path}`)
  })
  mock.env(on, {})
  mock.clock(on, { now: WNOW })
  on('mcp.call', async (_$, e) => {
    calls.push({ tool: e.tool, args: e.args })
    const out = prefixReply(w, e.tool, e.args)

    return { value: out === 'FAIL' ? failed(`${e.tool} boom`) : reply(out) } as never
  })
  on('session.start', async (_$, e) => ({ cwd: e.cwd }) as never)
  on('ui.open', async () => ({ value: { isPlaced: true } }) as never)
  on('ui.close', async () => ({ value: undefined }) as never)
  on('ui.status', async () => ({ value: undefined }) as never)
  on('ui.toast', async () => ({ value: undefined }) as never)
  on('command.register', async () => ({ value: undefined }) as never)
  on('tool.call', async () => ok as never)
  on('agent.spawn', async () => ({ model: 'claude-opus-5-5', agentId: 'sub-8f' }) as never)

  return { r, calls }
}

const FSNAP: GraphSnapshot = { ...asSnapshot(gview([mk(WA, 'work', 'T1', 'Stale feature A')])), rootId: WR, scopeId: WA }
const followSeed = (over: Record<string, unknown> = {}) => ({ graphSnapshot: FSNAP, graphScope: WA, graphPaneOpen: true, graphScopeMode: 'auto', ...over })
const startFollow = ($: WEngine) => $.session.start({ cwd: '/work/project', surface: 'terminal', isInteractive: true } as never)
const runToGraphArgs = async ($: WEngine, args: string) => {
  await startFollow($)
  await $.command.run({ command: 'to-graph', args } as never)
}
const touch = ($: WEngine, ...ids: string[]) =>
  $.tool.call({ tool: `${TO}advance_item`, transitions: ids.map(itemId => ({ itemId, trigger: 'start', actor: { id: 'implementer:x:r-1', kind: 'subagent' } })) } as never)
const spawn = ($: WEngine, description: string, prompt: string) => $.agent.spawn({ subagentType: 'task-orchestrator:implementer', description, prompt } as never)
const getsOf = (calls: WCall[], id: string) => calls.filter(c => c.tool === 'query_items' && c.args.operation === 'get' && c.args.itemId === id)
const anyGets = (calls: WCall[]) => calls.filter(c => c.tool === 'query_items' && c.args.operation === 'get')

// -- happy --

test('S1 (8f1064d7): auto mode, a touch outside the snapshot moves the scope to its owning feature', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed())
  await startFollow($)
  const res = await touch($, WT)
  expect(res).toMatchObject({ result: { ok: true }, text: 'ok' })
  expect(scopeSets(r)).toEqual([WF])
  expect(r.valueOf('graphScope')).toBe(WF)
})

test('S1 (8f1064d7): bare /to-graph is auto: it unpins, then the next out-of-snapshot touch follows', async ($, on) => {
  const { r } = followRig(on, FW({ active: [WA], transitions: [WA] }), followSeed({ graphScopeMode: 'pinned' }))
  await runToGraph($)
  expect(r.valueOf('graphScopeMode')).toBe('auto')
  expect(r.valueOf('graphScope')).toBe(WA)
  r.state.set(SLOT('graphSnapshot'), { value: FSNAP, version: 50 })
  r.state.set(SLOT('graphPaneOpen'), { value: true, version: 50 })
  await touch($, WT)
  expect(r.valueOf('graphScope')).toBe(WF)
})

test('S2 (8f1064d7): an 8-hex id prefix touch resolves to the full owning-feature uuid', async ($, on) => {
  const { r, calls } = followRig(on, FW(), followSeed())
  await startFollow($)
  await touch($, HT.slice(0, 8))
  expect(r.valueOf('graphScope')).toBe(HF)
  expect(getsOf(calls, HT.slice(0, 8))[0]?.args).toEqual({ operation: 'get', itemId: HT.slice(0, 8), includeAncestors: true })
})

test('S3 (8f1064d7): a bug under the Bugs container scopes to the bug itself', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed())
  await startFollow($)
  await touch($, WB)
  expect(r.valueOf('graphScope')).toBe(WB)
})

test('S4 (8f1064d7): agent.spawn naming the root then a task moves to the task feature; the spawn result is untouched', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed())
  await startFollow($)
  const res = await spawn($, 'implement', `Project ${WR} then work on ${HT} please`)
  expect(res).toMatchObject({ model: 'claude-opus-5-5', agentId: 'sub-8f' })
  expect(r.valueOf('graphScope')).toBe(HF)
  expect(scopeSets(r)).toEqual([HF])
})

test('S4 (8f1064d7): a uuid in the description wins over one in the prompt', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed())
  await startFollow($)
  await spawn($, `item ${HT}`, `other ${WB}`)
  expect(r.valueOf('graphScope')).toBe(HF)
})

test('S5 (8f1064d7): pinned shows Follow; pressing it returns to auto with the bare-command scope, and later touches follow again', async ($, on) => {
  const { r } = followRig(on, FW({ active: [WT], transitions: [WT] }), followSeed({ graphScopeMode: 'pinned' }))
  await startFollow($)
  const ui = await mountAt($, 'terminal')
  expect(await ui.find({ key: 'scope-follow' })).toBeDefined()
  await ui.press({ key: 'scope-follow' })
  expect(r.valueOf('graphScopeMode')).toBe('auto')
  expect(r.valueOf('graphScope')).toBe(WF)
  r.state.set(SLOT('graphSnapshot'), { value: FSNAP, version: 60 })
  await touch($, WB)
  expect(r.valueOf('graphScope')).toBe(WB)
  await ui.unmount()
})

test('S5 (8f1064d7): auto mode shows no Follow control', async ($, on) => {
  followRig(on, FW(), followSeed())
  await startFollow($)
  const ui = await mountAt($, 'terminal')
  expect(await ui.find({ key: 'scope-follow' })).toBeUndefined()
  await ui.unmount()
})

test('S5 (8f1064d7): Follow with no recent transitions falls back to the legacy newest-work feature, like the bare command', async ($, on) => {
  const { r } = followRig(on, FW({ active: [], transitions: [] }), followSeed({ graphScopeMode: 'pinned', graphScope: WF }))
  await startFollow($)
  const ui = await mountAt($, 'terminal')
  await ui.press({ key: 'scope-follow' })
  expect(r.valueOf('graphScopeMode')).toBe('auto')
  expect(r.valueOf('graphScope')).toBe(WA)
  await ui.unmount()
})

// -- activeScope: containers and the root are skipped when choosing the moved item --

const KIDS = Array.from({ length: 12 }, (_, i): WItem => ({ id: `wkont${String(i).padStart(3, '0')}-0000-4000-8000-000000000000`, parentId: WR, title: `Container ${i}`, type: 'container', role: 'work' }))
const KONTS = KIDS.map(k => k.id)
const KW = (over: WWorld): WWorld => ({ items: [...HITEMS, ...KIDS], ...over })

test('S6 (8f1064d7): a container ahead of the task in the active transitions is skipped', async () => {
  expect(await scopeOf({ active: [WBUGS, WT], transitions: [WBUGS, WT] })).toBe(WF)
})

test('S7 (8f1064d7): the project root ahead of the task is skipped', async () => {
  expect(await scopeOf({ active: [WR, WT], transitions: [WR, WT] })).toBe(WF)
})

test('S8 (8f1064d7): nothing active: the newest non-container transition is used for the fallback', async () => {
  expect(await scopeOf({ active: [], transitions: [WBUGS, WT] })).toBe(WF)
  expect(await scopeOf({ active: [], transitions: [WR, WBUGS, WFEATURES, WB] })).toBe(WB)
})

test('S8 (8f1064d7): a container-only history is still null', async () => {
  expect(await scopeOf({ active: [WBUGS], transitions: [WBUGS, WR] })).toBeNull()
  expect(await scopeOf({ active: [], transitions: [WBUGS, WR] })).toBeNull()
})

test('S8 (8f1064d7): at most SCOPE_CANDIDATE_LIMIT distinct ids are examined; repeats of one id count once', async () => {
  expect(SCOPE_CANDIDATE_LIMIT).toBe(10)
  const nine = KONTS.slice(0, 9)
  const ten = KONTS.slice(0, 10)
  // Nine containers then the task: the task is the 10th distinct id, still examined.
  expect(await activeScope(pfxCall(KW({ active: [...nine, HT], transitions: [...nine, HT] })).call, WR, WNOW)).toBe(HF)
  expect(await activeScope(pfxCall(KW({ active: [], transitions: [...nine, HT] })).call, WR, WNOW)).toBe(HF)
  // Ten containers then the task: the task is the 11th, never examined.
  expect(await activeScope(pfxCall(KW({ active: [...ten, HT], transitions: [...ten, HT] })).call, WR, WNOW)).toBeNull()
  expect(await activeScope(pfxCall(KW({ active: [], transitions: [...ten, HT] })).call, WR, WNOW)).toBeNull()
  // Twenty rows naming one container count as one distinct id.
  const dup = Array.from({ length: 20 }, () => WBUGS)
  expect(await activeScope(pfxCall(KW({ active: [WBUGS, HT], transitions: [...dup, HT] })).call, WR, WNOW)).toBe(HF)
})

test('S15 (8f1064d7): the container fix keeps the read bound (root > Features > F > T: at most 3 query_items) and read tools only', async () => {
  const { call, calls } = fakeCall({ active: [WBUGS, WT], transitions: [WBUGS, WT] })
  expect(await activeScope(call, WR, WNOW)).toBe(WF)
  expect(calls.filter(c => c.tool === 'get_context')).toHaveLength(1)
  for (const c of calls) expect(READ_TOOLS).toContain(c.tool)
  const single = fakeCall({ active: [WT], transitions: [WT] })
  expect(await activeScope(single.call, WR, WNOW)).toBe(WF)
  expect(single.calls.filter(c => c.tool === 'query_items').length).toBeLessThanOrEqual(3)
})

// -- pinned never moves --

const PINS: [string, (ui: Awaited<ReturnType<typeof mountAt>>) => Promise<void>, Record<string, unknown>, string | null][] = [
  ['This feature', async ui => ui.press({ key: 'scope-feature' }), {}, WF],
  ['Whole project', async ui => ui.press({ key: 'scope-project' }), {}, null],
  ['node click', async ui => ui.press({ key: `open:${WA}` }), { graphScope: ROOT_ID, graphSnapshot: { ...FSNAP, scopeId: ROOT_ID } }, ROOT_ID],
  ['Open graph', async ui => ui.press({ key: 'detail-open-graph' }), { graphScope: ROOT_ID, graphSnapshot: { ...FSNAP, scopeId: ROOT_ID }, graphDetail: { itemId: WA, lines: ['x'] } }, WA],
  ['breadcrumb', async ui => ui.press({ key: `crumb:${WFEATURES}` }), { graphSnapshot: { ...FSNAP, trail: [{ id: WR, title: 'Project' }, { id: WFEATURES, title: 'Features' }, { id: WA, title: 'Stale feature A' }] } }, WFEATURES],
]
for (const [name, act, seed, expected] of PINS) {
  test(`S9 (8f1064d7): after ${name} the scope is pinned and a later out-of-snapshot touch does not move it`, async ($, on) => {
    const { r } = followRig(on, FW({ active: [WT], transitions: [WT] }), followSeed(seed))
    await startFollow($)
    const ui = await mountAt($, 'terminal')
    await act(ui)
    expect(r.valueOf('graphScopeMode')).toBe('pinned')
    expect(r.valueOf('graphScope')).toBe(expected)
    const writes = scopeSets(r).length
    await touch($, WB)
    expect(scopeSets(r)).toHaveLength(writes)
    expect(r.valueOf('graphScope')).toBe(expected)
    await ui.unmount()
  })
}

test('S9 (8f1064d7): /to-graph <id> pins and a later out-of-snapshot touch does not move it', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed())
  await runToGraphArgs($, HA)
  expect(r.valueOf('graphScopeMode')).toBe('pinned')
  expect(r.valueOf('graphScope')).toBe(HA)
  r.state.set(SLOT('graphSnapshot'), { value: FSNAP, version: 70 })
  r.state.set(SLOT('graphPaneOpen'), { value: true, version: 70 })
  await touch($, WB)
  expect(r.valueOf('graphScope')).toBe(HA)
})

test('S9 (8f1064d7): the control for /to-graph <id>: a bare /to-graph then the same touch does move to the bug', async ($, on) => {
  const { r } = followRig(on, FW({ active: [HA], transitions: [HA] }), followSeed())
  await runToGraph($)
  expect(r.valueOf('graphScopeMode')).toBe('auto')
  r.state.set(SLOT('graphSnapshot'), { value: FSNAP, version: 70 })
  r.state.set(SLOT('graphPaneOpen'), { value: true, version: 70 })
  await touch($, WB)
  expect(r.valueOf('graphScope')).toBe(WB)
})

test('S9 (8f1064d7): /to-graph root pins the whole project (null) and a touch does not move it', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed())
  await runToGraphArgs($, 'root')
  expect(r.valueOf('graphScopeMode')).toBe('pinned')
  expect(r.valueOf('graphScope')).toBeNull()
  r.state.set(SLOT('graphSnapshot'), { value: FSNAP, version: 70 })
  r.state.set(SLOT('graphPaneOpen'), { value: true, version: 70 })
  await touch($, WB)
  expect(r.valueOf('graphScope')).toBeNull()
})

// -- guards: nothing moves, nothing throws --

test('S10 (8f1064d7): a touch of an id already in the snapshot neither moves the scope nor reads it', async ($, on) => {
  const { r, calls } = followRig(on, FW(), followSeed({ graphScope: WF }))
  await startFollow($)
  const res = await touch($, WA)
  expect(res).toMatchObject({ result: { ok: true }, text: 'ok' })
  expect(scopeSets(r)).toEqual([])
  expect(r.valueOf('graphScope')).toBe(WF)
  expect(getsOf(calls, WA)).toEqual([])
})

const NO_MOVE: [string, string, WWorld][] = [
  ['a container', WBUGS, {}],
  ['the project root', WR, {}],
  ['an item of another project', WZ, {}],
  ['an item whose read fails', WT, { failGetOf: WT }],
]
for (const [name, id, extra] of NO_MOVE) {
  test(`S11 (8f1064d7): touching ${name} leaves the scope alone and the tool result untouched`, async ($, on) => {
    const { r, calls } = followRig(on, FW(extra), followSeed())
    await startFollow($)
    const res = await touch($, id)
    expect(res).toMatchObject({ result: { ok: true }, text: 'ok' })
    // The path was reached (the id was looked up), and nothing was written.
    expect(getsOf(calls, id).length).toBeGreaterThanOrEqual(1)
    expect(scopeSets(r)).toEqual([])
    expect(r.valueOf('graphScope')).toBe(WA)
  })
}

test('S11 (8f1064d7): a failing read during a spawn follow leaves the scope alone and the spawn result untouched', async ($, on) => {
  const { r, calls } = followRig(on, FW({ failGetOf: HT }), followSeed())
  await startFollow($)
  const res = await spawn($, 'x', `work on ${HT}`)
  expect(res).toMatchObject({ model: 'claude-opus-5-5', agentId: 'sub-8f' })
  expect(getsOf(calls, HT).length).toBeGreaterThanOrEqual(1)
  expect(scopeSets(r)).toEqual([])
})

for (const [name, seed] of [['closed', { graphPaneOpen: false }], ['never opened', { graphPaneOpen: undefined }]] as const) {
  test(`S12 (8f1064d7): with the pane ${name} a touch moves nothing and reads nothing`, async ($, on) => {
    const { r, calls } = followRig(on, FW(), followSeed(seed))
    await startFollow($)
    await touch($, WT)
    expect(scopeSets(r)).toEqual([])
    expect(r.valueOf('graphScope')).toBe(WA)
    expect(anyGets(calls)).toEqual([])
  })
}

test('S12 (8f1064d7): with no snapshot yet a touch moves nothing', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed({ graphSnapshot: null }))
  await startFollow($)
  await touch($, WT)
  expect(scopeSets(r)).toEqual([])
})

// -- edges --

test('S13 (8f1064d7): the same out-of-snapshot touch twice reads that id at most once; reads are read tools', async ($, on) => {
  const { r, calls } = followRig(on, FW(), followSeed())
  await startFollow($)
  await touch($, WT)
  await touch($, WT)
  expect(getsOf(calls, WT)).toHaveLength(1)
  expect(scopeSets(r)).toEqual([WF])
  for (const c of calls) expect(READ_TOOLS).toContain(c.tool)
})

test('S13 (8f1064d7): a touch whose owning scope already is the scope writes nothing', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed({ graphScope: WF }))
  await startFollow($)
  await touch($, WT)
  expect(scopeSets(r)).toEqual([])
})

test('S14 (8f1064d7): one call naming two out-of-snapshot items makes one move, to the first by call order', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed())
  await startFollow($)
  await touch($, WT, WB)
  expect(scopeSets(r)).toEqual([WF])
})

test('S14 (8f1064d7): the same item named twice in one call is one move and one read', async ($, on) => {
  const { r, calls } = followRig(on, FW(), followSeed())
  await startFollow($)
  await touch($, WT, WT)
  expect(scopeSets(r)).toEqual([WF])
  expect(getsOf(calls, WT)).toHaveLength(1)
})

const bandProps = { hasSurvey: false, isWorking: false, maxRows: 6, bodyColumns: 100, scroll: { offset: 0, bodyRows: 6 }, view: {} } as never

test('S17 (8f1064d7): the band opening the pane after a pin and close returns to auto without moving the scope', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed({ graphSnapshot: { ...f1(), rootId: WR }, graphPaneOpen: false, graphScopeMode: 'pinned' }))
  await startFollow($)
  const ui = await $.ui.mount({ plugin: PLUGIN, surface: 'terminal', component: 'AbovePrompt', props: bandProps })
  await ui.press({ key: 'band-open' })
  expect(r.valueOf('graphScopeMode')).toBe('auto')
  expect(scopeSets(r)).toEqual([])
  r.state.set(SLOT('graphSnapshot'), { value: { ...f1(), rootId: WR }, version: 80 })
  r.state.set(SLOT('graphPaneOpen'), { value: true, version: 80 })
  await touch($, WT)
  expect(r.valueOf('graphScope')).toBe(WF)
  await ui.unmount()
})

test('S17 (8f1064d7): the band pressed while the pane is already open does not unpin', async ($, on) => {
  const { r } = followRig(on, FW(), followSeed({ graphSnapshot: { ...f1(), rootId: WR }, graphPaneOpen: true, graphScopeMode: 'pinned' }))
  await startFollow($)
  const ui = await $.ui.mount({ plugin: PLUGIN, surface: 'terminal', component: 'AbovePrompt', props: bandProps })
  await ui.press({ key: 'band-open' })
  expect(r.valueOf('graphScopeMode')).toBe('pinned')
  expect(scopeSets(r)).toEqual([])
  await ui.unmount()
})

// -- probes --

test('probe (8f1064d7): an empty itemId reads nothing; a missing actor still follows; an uppercase uuid passes through', async ($, on) => {
  const { r, calls } = followRig(on, FW(), followSeed())
  await startFollow($)
  const empty = await $.tool.call({ tool: `${TO}advance_item`, transitions: [{ itemId: '', trigger: 'start' }] } as never)
  expect(empty).toMatchObject({ result: { ok: true } })
  expect(anyGets(calls)).toEqual([])
  expect(scopeSets(r)).toEqual([])
  await $.tool.call({ tool: `${TO}advance_item`, itemId: WT, trigger: 'start' } as never)
  expect(r.valueOf('graphScope')).toBe(WF)
  // Uppercase uuid: the result passes through untouched (no oracle for case handling beyond that).
  const up = await $.tool.call({ tool: `${TO}advance_item`, itemId: HT.toUpperCase(), trigger: 'start' } as never)
  expect(up).toMatchObject({ result: { ok: true }, text: 'ok' })
})

test('probe (8f1064d7): a spawn with no uuid, or naming only the root, reads nothing and moves nothing', async ($, on) => {
  const { r, calls } = followRig(on, FW(), followSeed())
  await startFollow($)
  const a = await spawn($, 'plain words', 'no ids here')
  expect(a).toMatchObject({ agentId: 'sub-8f' })
  const b = await spawn($, 'root only', `project ${WR}`)
  expect(b).toMatchObject({ agentId: 'sub-8f' })
  expect(anyGets(calls)).toEqual([])
  expect(scopeSets(r)).toEqual([])
})

// -- pure units: owningScope and firstSpawnUuid --

test('S16 (8f1064d7): owningScope resolves the nearest feature, the item itself under a container, and null for root/container/other project', async () => {
  const own = (id: string) => owningScope(pfxCall(FW()).call, id, WR)
  expect(await own(WT)).toBe(WF)
  expect(await own(WT2)).toBe(WF)
  expect(await own(WT3)).toBe(WF2)
  expect(await own(WB)).toBe(WB)
  expect(await own(WD)).toBe(WD)
  expect(await own(WBUGS)).toBeNull()
  expect(await own(WFEATURES)).toBeNull()
  expect(await own(WR)).toBeNull()
  expect(await own(WZ)).toBeNull()
})

test('S16 (8f1064d7): owningScope reads once with the exact shape and returns the full id for a prefix', async () => {
  const { call, calls } = pfxCall(FW())
  expect(await owningScope(call, HT.slice(0, 8), WR)).toBe(HF)
  expect(calls[0]).toEqual({ tool: 'query_items', args: { operation: 'get', itemId: HT.slice(0, 8), includeAncestors: true } })
  for (const c of calls) expect(READ_TOOLS).toContain(c.tool)
  // Root > Features > F > T: one get for the item, at most one per ancestor below the root.
  expect(calls.length).toBeLessThanOrEqual(3)
})

test('S16 (8f1064d7): owningScope is null for a reply without an id', async () => {
  const call = async (): Promise<unknown> => ({ items: [] })
  expect(await owningScope(call, HT, WR)).toBeNull()
})

test('S4 (8f1064d7): firstSpawnUuid prefers the description, then the prompt, skips the root, and ignores non-strings', () => {
  expect(firstSpawnUuid(`item ${HT}`, `other ${HA}`, WR)).toBe(HT)
  expect(firstSpawnUuid('nothing', `root ${WR} then ${HT}`, WR)).toBe(HT)
  expect(firstSpawnUuid(undefined, `${HT}: do the thing`, WR)).toBe(HT)
  expect(firstSpawnUuid('nothing', `only the root ${WR}`, WR)).toBeNull()
  expect(firstSpawnUuid('no ids', 'no ids', WR)).toBeNull()
  expect(firstSpawnUuid(42, null, WR)).toBeNull()
  expect(firstSpawnUuid(undefined, undefined, WR)).toBeNull()
  // An 8-hex prefix is not a full uuid.
  expect(firstSpawnUuid('', `item ${HT.slice(0, 8)}`, WR)).toBeNull()
})

// -- 087c6077: another session's transition follows the auto scope, through the loaded plugin --
// Oracles (frozen in the item's test-plan): [RM] README scope bullet, [TS] task-scope B (eligible event, 3s trailing
// debounce, listener = followActivity), 8f1064d7 owningScope. The loaded plugin has its own module instance, so the
// live-to-pane listener is exercised end to end: SSE frames come from the test's process.spawn handler.

// A pane that is not open yet, so /to-graph opens it, counts a subscriber and starts the events stream.
const CLOSED = { graphPaneOpen: false }
const API_URL = 'http://localhost:3001'
const SSE_TIME = { timeoutMs: 30_000 } as never

/** The world's reads, plus the snapshot's subtree search (the shared world answers it with no rows). */
const subtreeReply = (w: WWorld, tool: string, args: Record<string, unknown>): unknown => {
  if (tool === 'query_items' && args.operation === 'search' && args.type === undefined && typeof args.ancestorId === 'string') {
    const items = w.items ?? WITEMS
    const inTree = (id: string | null): boolean => id !== null && (id === args.ancestorId || inTree(items.find(i => i.id === id)?.parentId ?? null))
    const rows = items.filter(i => inTree(i.id)).map(i => ({ ...i, depth: ancestorsOf(items, i.id).length, priority: 'medium' }))
    const offset = Number(args.offset ?? 0)
    const limit = Number(args.limit ?? 50)

    return { items: rows.slice(offset, offset + limit), total: rows.length, returned: Math.min(limit, Math.max(0, rows.length - offset)), limit, offset }
  }

  return prefixReply(w, tool, args)
}

/** followRig with an events stream: process.spawn yields whatever `push` queues. */
function sseFollowRig(on: On, w: WWorld, seed: Record<string, unknown>) {
  const r = rig(on, seed)
  const calls: WCall[] = []
  on('fs.read', async (_$, e) => {
    const path = String((e as { path: string }).path).split(String.fromCharCode(92)).join('/')
    if (path.endsWith('.taskorchestrator/config.yaml')) return { value: WCONFIG } as never
    throw new Error(`ENOENT ${path}`)
  })
  mock.env(on, { TASK_ORCHESTRATOR_API_URL: API_URL })
  const clock = mock.clock(on, { now: WNOW })
  on('mcp.call', async (_$, e) => {
    calls.push({ tool: e.tool, args: e.args })
    const out = subtreeReply(w, e.tool, e.args)

    return { value: out === 'FAIL' ? failed(`${e.tool} boom`) : reply(out) } as never
  })
  const queue: string[] = []
  let wake: () => void = () => undefined
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
  on('tool.call', async () => ok as never)
  on('agent.spawn', async () => ({ model: 'claude-opus-5-5', agentId: 'sub-8f' }) as never)
  const push = (t: string) => {
    queue.push(t)
    wake()
  }
  /** One item.advanced frame for this project root; `actor: null` leaves the actor out (an older server). */
  const advancedFrame = (itemId: string, over: { actor?: { id: string; parent?: string } | null; rootId?: string } = {}) => {
    const data: Record<string, unknown> = { id: 1, event: 'item.advanced', itemId, rootId: over.rootId ?? WR }
    if (over.actor !== null) data.actor = over.actor ?? { id: 'other-session:9', kind: 'orchestrator' }

    return `event: item.advanced\ndata: ${JSON.stringify(data)}\n\n`
  }
  const settleBy = async (ms: number) => {
    await clock.advance(ms)
    await clock.settle()
  }

  return { r, calls, push, advancedFrame, settleBy }
}

const FOLLOW_ACTIVE = (id: string): WWorld => FW({ active: [id], transitions: [id] })

test('S15 (087c6077): auto mode: another session\'s item.advanced for an out-of-snapshot task moves the scope to its owning feature once the debounce has passed', SSE_TIME, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await runToGraph($)
  await settleBy(1_000)
  expect(r.valueOf('graphScopeMode')).toBe('auto')
  expect(r.valueOf('graphScope')).toBe(WA)
  push(advancedFrame(WT))
  await settleBy(2_000)
  // Not yet: the trailing debounce is 3s.
  expect(r.valueOf('graphScope')).toBe(WA)
  await settleBy(1_500)
  expect(r.valueOf('graphScope')).toBe(WF)
  expect(scopeSets(r).at(-1)).toBe(WF)
})

test('S15 (087c6077): the same frame without an actor (window attribution) or for another root never moves the scope; the actor frame right after does', SSE_TIME, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await runToGraph($)
  await settleBy(1_000)
  push(advancedFrame(WB, { actor: null }))
  push(advancedFrame(WB, { rootId: WZ }))
  await settleBy(10_000)
  expect(r.valueOf('graphScope')).toBe(WA)
  // Control on the same fixture: a remote-by-actor frame for the same item follows.
  push(advancedFrame(WB))
  await settleBy(3_500)
  expect(r.valueOf('graphScope')).toBe(WB)
})

test('S15 (087c6077): a wave of frames moves the scope once, to the owning scope of the last item', SSE_TIME, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await runToGraph($)
  await settleBy(1_000)
  const before = scopeSets(r).length
  push(advancedFrame(WB))
  await settleBy(1_000)
  push(advancedFrame(WT3))
  await settleBy(1_000)
  push(advancedFrame(WT))
  await settleBy(4_000)
  expect(r.valueOf('graphScope')).toBe(WF)
  expect(scopeSets(r).slice(before)).toEqual([WF])
})

test('S16 (087c6077): pinned scope never moves for a remote advance; after an unpin (bare /to-graph) the same frame moves it', SSE_TIME, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(HA), followSeed(CLOSED))
  await runToGraphArgs($, HA)
  await settleBy(1_000)
  expect(r.valueOf('graphScopeMode')).toBe('pinned')
  expect(r.valueOf('graphScope')).toBe(HA)
  push(advancedFrame(WB))
  await settleBy(5_000)
  expect(r.valueOf('graphScopeMode')).toBe('pinned')
  expect(r.valueOf('graphScope')).toBe(HA)
  // Control on the same fixture: unpinned, the same kind of frame follows.
  await $.command.run({ command: 'to-graph', args: '' } as never)
  await settleBy(1_000)
  expect(r.valueOf('graphScopeMode')).toBe('auto')
  push(advancedFrame(WB))
  await settleBy(4_000)
  expect(r.valueOf('graphScope')).toBe(WB)
})

// The test's `$` carries no `ui.close` (the kit raises it only from a plugin's `$.ui.close`), so an inline plugin
// closes the pane on a command of its own, as live-sync.test.ts does.
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
const SSE_WITH_CLOSER = { plugins: [CLOSER], timeoutMs: 30_000 } as never

test('S16 (087c6077): after the pane is closed a remote advance moves nothing; with it open the same frame moved the scope', SSE_WITH_CLOSER, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await runToGraph($)
  await settleBy(1_000)
  push(advancedFrame(WB))
  await settleBy(4_000)
  expect(r.valueOf('graphScope')).toBe(WB)
  await $.command.run({ command: 'close-graph', args: '' } as never)
  await settleBy(1_000)
  const writes = scopeSets(r).length
  push(advancedFrame(WT))
  await settleBy(5_000)
  expect(scopeSets(r)).toHaveLength(writes)
  expect(r.valueOf('graphScope')).toBe(WB)
})

test('S17 (087c6077): an advance for an item already in the snapshot reads nothing and keeps the scope; one outside it reads the item', SSE_TIME, async ($, on) => {
  const { r, calls, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WT), followSeed(CLOSED))
  await runToGraph($)
  await settleBy(1_000)
  expect(r.valueOf('graphScope')).toBe(WF)
  const snapshotIds = (r.valueOf('graphSnapshot') as GraphSnapshot).nodes.map(n => n.id)
  expect(snapshotIds).toContain(WT)
  expect(snapshotIds).not.toContain(WB)
  // The ownership lookup is the get with includeAncestors (plan-label reads are plain gets and are not the follow's).
  const owner = (id: string) => getsOf(calls, id).filter(c => c.args.includeAncestors === true).length
  const before = owner(WT)
  push(advancedFrame(WT))
  await settleBy(5_000)
  expect(owner(WT)).toBe(before)
  expect(r.valueOf('graphScope')).toBe(WF)
  // Control on the same fixture: an item outside the snapshot is looked up and the scope moves.
  push(advancedFrame(WB))
  await settleBy(4_000)
  expect(owner(WB)).toBeGreaterThanOrEqual(1)
  expect(r.valueOf('graphScope')).toBe(WB)
})

// -- 087c6077 A1: a pane opened through the band's 'band-open' press follows other sessions too --
// Oracles: [RM] README scope bullet (another session's transitions in this project are followed in auto mode) and
// task-scope B + Amendment A1 (the Pane draw registers the one-slot listener). The rig's ui.open stub draws nothing,
// so mounting the Pane (requestId 'to-graph') stands in for the host's draw. The listener is not observable from the
// test realm, so every case runs end to end: SSE frame -> debounce -> followActivity -> graphScope.

/** Press the band's open control on a closed pane (the band alone opens it; it does not draw the Pane). */
const bandOpen = async ($: WEngine) => {
  const band = await $.ui.mount({ plugin: PLUGIN, surface: 'terminal', component: 'AbovePrompt', props: bandProps })
  await band.press({ key: 'band-open' })

  return band
}

test('A1-S1 (087c6077): a pane opened by the band press and drawn follows another session; actorless and other-root frames on the same fixture do not', SSE_TIME, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await startFollow($)
  const band = await bandOpen($)
  const pane = await mountAt($, 'terminal')
  await settleBy(1_000)
  expect(r.valueOf('graphScopeMode')).toBe('auto')
  expect(r.valueOf('graphScope')).toBe(WA)
  // Window-attributed (no actor) and other-root frames are never followed: the actor frame below is the falsifier.
  push(advancedFrame(WB, { actor: null }))
  push(advancedFrame(WB, { rootId: WZ }))
  await settleBy(10_000)
  expect(r.valueOf('graphScope')).toBe(WA)
  const before = scopeSets(r).length
  push(advancedFrame(WT))
  await settleBy(2_000)
  expect(r.valueOf('graphScope')).toBe(WA)
  await settleBy(1_500)
  expect(r.valueOf('graphScope')).toBe(WF)
  expect(scopeSets(r).slice(before)).toEqual([WF])
  await pane.unmount()
  await band.unmount()
})

test('A1-S2 (087c6077): /to-graph, close, then a band reopen follows again; the same frame did not move anything before the reopen', SSE_WITH_CLOSER, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await runToGraph($)
  await settleBy(1_000)
  push(advancedFrame(WB))
  await settleBy(4_000)
  expect(r.valueOf('graphScope')).toBe(WB)
  await $.command.run({ command: 'close-graph', args: '' } as never)
  await settleBy(1_000)
  const closedWrites = scopeSets(r).length
  push(advancedFrame(WT))
  await settleBy(5_000)
  expect(scopeSets(r)).toHaveLength(closedWrites)
  expect(r.valueOf('graphScope')).toBe(WB)
  const band = await bandOpen($)
  const pane = await mountAt($, 'terminal')
  await settleBy(1_000)
  expect(r.valueOf('graphScope')).toBe(WB)
  const reopened = scopeSets(r).length
  push(advancedFrame(WT))
  await settleBy(4_000)
  expect(r.valueOf('graphScope')).toBe(WF)
  expect(scopeSets(r).slice(reopened)).toEqual([WF])
  await pane.unmount()
  await band.unmount()
})

test('A1-S3 (087c6077): regression: a /to-graph pane that is then drawn still follows, once; a window-attributed frame first does not', SSE_TIME, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await runToGraph($)
  const pane = await mountAt($, 'terminal')
  await settleBy(1_000)
  const before = scopeSets(r).length
  push(advancedFrame(WB, { actor: null }))
  await settleBy(10_000)
  expect(scopeSets(r).slice(before)).toEqual([])
  expect(r.valueOf('graphScope')).toBe(WA)
  push(advancedFrame(WT))
  await settleBy(4_000)
  expect(r.valueOf('graphScope')).toBe(WF)
  expect(scopeSets(r).slice(before)).toEqual([WF])
  await pane.unmount()
})

test('A1-S4 (087c6077): repeated Pane draws leave one listener: a wave of frames moves the scope once, to the last item; actorless frames first move nothing', SSE_TIME, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await startFollow($)
  const band = await bandOpen($)
  // The kit allows one Pane per requestId, so repeated draws are mount/unmount/mount cycles (three draws in all).
  await (await mountAt($, 'terminal')).unmount()
  await (await mountAt($, 'terminal')).unmount()
  const pane = await mountAt($, 'terminal')
  await settleBy(1_000)
  const before = scopeSets(r).length
  push(advancedFrame(WB, { actor: null }))
  await settleBy(10_000)
  expect(scopeSets(r).slice(before)).toEqual([])
  push(advancedFrame(WB))
  await settleBy(1_000)
  push(advancedFrame(WT3))
  await settleBy(1_000)
  push(advancedFrame(WT))
  await settleBy(4_000)
  expect(r.valueOf('graphScope')).toBe(WF)
  expect(scopeSets(r).slice(before)).toEqual([WF])
  await pane.unmount()
  await band.unmount()
})

test('A1-S5 (087c6077): a band-opened pane that is closed follows nothing; while open, the same kind of frame moved the scope', SSE_WITH_CLOSER, async ($, on) => {
  const { r, push, advancedFrame, settleBy } = sseFollowRig(on, FOLLOW_ACTIVE(WA), followSeed(CLOSED))
  await startFollow($)
  await bandOpen($)
  await mountAt($, 'terminal')
  await settleBy(1_000)
  push(advancedFrame(WB))
  await settleBy(4_000)
  expect(r.valueOf('graphScope')).toBe(WB)
  await $.command.run({ command: 'close-graph', args: '' } as never)
  await settleBy(1_000)
  const writes = scopeSets(r).length
  push(advancedFrame(WT))
  await settleBy(5_000)
  expect(scopeSets(r)).toHaveLength(writes)
  expect(r.valueOf('graphScope')).toBe(WB)
})
