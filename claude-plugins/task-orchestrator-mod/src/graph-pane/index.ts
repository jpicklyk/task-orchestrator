// /to-graph pane: a top-down dependency graph of keyed, state-filled boxes (T3 a47dcd6f, rewritten).
// Read-only: the pane makes only query_items, get_context and query_notes calls and no TO write tool, ever.
//
// This is the only file here that touches `$` (the plugin validator follows `$` only into functions
// of the same file). Control is atom-driven, so the atoms below are this file's own same-literal
// declarations of graph-data's keys; only the pure steps come from the other modules.
import { atom, read, update } from 'claude-code'
import type { EngineInterface, On, PluginOptions } from 'claude-code'

import type { GraphActivity, GraphAgentInfo, GraphDetail, GraphSnapshot, GraphStatus } from '../../types'
import { CONFIG_PATH, parseProjectRootId } from '../shared/config.ts'
import { PLUGIN, TO_SERVER } from '../shared/constants.ts'
import { parseToResult } from '../shared/to-client.ts'
import { addSubscriber, bump, removeSubscriber, requestLiveSync, requestReconnect, requestRefresh, scopeTo, setRemoteAdvanceListener, shouldRefresh } from '../graph-data/index.ts'
import { GATE_BLOCK_MS, WORKING_TTL_MS, RECENT_MS, gateBlocks, pruneActivity, recordActivity, recordGateBlocks, rememberAgent, resolveId, seatOf, touchedIds } from './activity.ts'
import type { GateBlockRow } from './activity.ts'
import { edgePlan, extrasChars, marksFit } from './budget.ts'
import { cellSize } from './cell.ts'
import { layoutTD } from './layout.ts'
import { cut, titleLines } from './wrap.ts'
import type { TopDown } from './layout.ts'
import { cardsOf, criticalPath, stepsOf } from './model.ts'
import type { Card, CriticalPath, Model } from './model.ts'
import { recordFocusOrder, redirectFocus } from './focus.ts'
import { gateToastLines } from '../graph-data/events.ts'
import { activeScope, detailLines, firstSpawnUuid, isDegraded, isSwitching, loadingTitle, owningScope, parseScopeArg, scopeHeader, summaryLine } from './pane-model.ts'
import type { ToolCall } from './pane-model.ts'
import { CRIT_COLOR, raster, runs } from './raster.ts'
import { routes } from './route.ts'
import { KIND, READY, id8, legendItems } from './shared.ts'
import type { GraphView } from './shared.ts'
import { edgeSvg, px } from './svg.ts'

export const PANE_ID = 'to-graph'

/** Every TO tool this pane calls; none is a write tool. */
export const READ_TOOLS: readonly string[] = ['query_items', 'get_context', 'query_notes']

const graphSnapshot = atom({ plugin: 'task-orchestrator-mod', key: 'graphSnapshot' } as const, null as GraphSnapshot | null)
const graphStatus = atom({ plugin: 'task-orchestrator-mod', key: 'graphStatus' } as const, { refreshing: false, liveSource: 'none' } as GraphStatus)
const graphScope = atom({ plugin: 'task-orchestrator-mod', key: 'graphScope' } as const, null as string | null)
const graphScopeMode = atom({ plugin: 'task-orchestrator-mod', key: 'graphScopeMode' } as const, 'auto' as 'auto' | 'pinned')
const graphSubscribers = atom({ plugin: 'task-orchestrator-mod', key: 'graphSubscribers' } as const, 0)
const graphReconnectRequest = atom({ plugin: 'task-orchestrator-mod', key: 'graphReconnectRequest' } as const, 0)
const graphPaneOpen = atom({ plugin: 'task-orchestrator-mod', key: 'graphPaneOpen' } as const, false)
const graphDetail = atom({ plugin: 'task-orchestrator-mod', key: 'graphDetail' } as const, null as GraphDetail | null)
const graphActivity = atom({ plugin: 'task-orchestrator-mod', key: 'graphActivity' } as const, { working: {}, changed: {} } as GraphActivity)
const graphAgents = atom({ plugin: 'task-orchestrator-mod', key: 'graphAgents' } as const, {} as Record<string, GraphAgentInfo>)
const graphShowDone = atom({ plugin: 'task-orchestrator-mod', key: 'graphShowDone' } as const, false)
const graphRemote = atom({ plugin: 'task-orchestrator-mod', key: 'graphRemote' } as const, {} as Record<string, number>)

/** The engine's spelling of every TO tool (`mcp__<server>__<tool>`); reads count as "working on it". */
const TO_TOOL = /^mcp__.*task-orchestrator.*__[a-z_]+$/

const yes = (): boolean => true
const no = (): boolean => false
const pinned = (): 'auto' | 'pinned' => 'pinned'
const auto = (): 'auto' | 'pinned' => 'auto'

/** Items already resolved to an owning scope (null: none). Capped; a repeat touch of an id reads nothing. */
const SCOPE_MEMO_MAX = 200
const scopeMemo = new Map<string, string | null>()

/** Set once per module load, so a hot reload (which drops the registered command) registers it again. */
let commandRegistered = false

/**
 * Copies `text` with the host's clipboard tool (`clip` on Windows, `pbcopy` on macOS, `wl-copy` or
 * `xclip` on Linux). Used when `$.ui.copy` cannot reach the surface, as on the desktop app today.
 */
async function hostCopy($: EngineInterface, text: string): Promise<boolean> {
  const windows = (await $.env.get('OS')) === 'Windows_NT'
  const tools: string[][] = windows ? [['clip']] : [['pbcopy'], ['wl-copy'], ['xclip', '-selection', 'clipboard']]
  for (const argv of tools) {
    try {
      const ran = await $.process.run(argv, { stdin: text, timeoutMs: 3000 })
      if (ran.exitCode === 0) return true
    } catch {
      // that tool is not installed here; try the next
    }
  }

  return false
}

/**
 * The scope `/to-graph` opens with no argument: the owning scope of the latest transition under the
 * project root (pane-model `activeScope`), else the most recently modified feature-implementation in
 * work, else null. A failed or empty scope read falls back to the legacy query; no root id makes no call.
 */
async function activeFeature($: EngineInterface): Promise<string | null> {
  try {
    const rootId = parseProjectRootId(await $.fs.read(CONFIG_PATH))
    if (rootId === null) return null
    const call = async (tool: string, args: Record<string, unknown>): Promise<unknown> => parseToResult<unknown>(tool, await $.mcp.call(TO_SERVER, tool, args))
    try {
      const scope = await activeScope(call, rootId, await $.clock.now())
      if (scope !== null) return scope
    } catch {
      // fall through to the legacy rule
    }
    const found = (await call('query_items', {
      operation: 'search',
      ancestorId: rootId,
      type: 'feature-implementation',
      role: 'work',
      sortBy: 'modifiedAt',
      sortOrder: 'desc',
      limit: 1,
    })) as { items?: { id?: unknown }[] }
    const id = found.items?.[0]?.id

    return typeof id === 'string' ? id : null
  } catch {
    return null
  }
}

const message = (err: unknown): string => (err instanceof Error ? err.message : String(err))

/** Reads the three read-only calls for one node into the detail atom. Each failure is tolerated. In-pane, read-only. */
async function openDetail($: EngineInterface, itemId: string): Promise<void> {
  // Clicking a node is a choice of focus: stop following this session's activity.
  await update($, graphScopeMode, pinned)
  const call = async (tool: string, args: Record<string, unknown>): Promise<unknown> => parseToResult<unknown>(tool, await $.mcp.call(TO_SERVER, tool, args))
  const [ctx, item, notes] = await Promise.allSettled([
    call('get_context', { itemId }),
    call('query_items', { operation: 'get', itemId, includeTimestamps: true }),
    call('query_notes', { operation: 'list', itemId, includeBody: false }),
  ])
  const now = await $.clock.now()
  const lines =
    ctx.status === 'rejected'
      ? [`Could not read ${id8(itemId)}: ${message(ctx.reason)}`]
      : detailLines({
          itemId,
          ctx: ctx.value,
          now,
          ...(item.status === 'fulfilled' ? { item: item.value } : { itemFailed: true }),
          ...(notes.status === 'fulfilled' ? { notes: notes.value } : { notesFailed: true }),
        })
  await update($, graphDetail, () => ({ itemId, lines }))
}

/**
 * Auto mode only, pane open: re-scopes the pane to the owning scope of the first candidate id the
 * snapshot cannot place (one attempt per call). Never throws; the caller's result is untouched.
 */
async function followActivity($: EngineInterface, pick: (rootId: string) => string[]): Promise<void> {
  try {
    if ((await read($, graphScopeMode)) !== 'auto' || !(await read($, graphPaneOpen))) return
    const snap = await read($, graphSnapshot)
    if (snap === null) return
    const rootId = parseProjectRootId(await $.fs.read(CONFIG_PATH))
    if (rootId === null) return
    const known = snap.nodes.map(n => n.id)
    const target = pick(rootId).find(c => resolveId(c, known) === null)
    if (target === undefined) return
    const key = `${rootId}:${target}`
    let scope: string | null
    if (scopeMemo.has(key)) scope = scopeMemo.get(key) ?? null
    else {
      const call: ToolCall = async (tool, args) => parseToResult<unknown>(tool, await $.mcp.call(TO_SERVER, tool, args))
      try {
        scope = await owningScope(call, target, rootId)
      } catch {
        scope = null
      }
      if (scopeMemo.size >= SCOPE_MEMO_MAX) scopeMemo.delete(scopeMemo.keys().next().value as string)
      scopeMemo.set(key, scope)
    }
    if (scope === null || scope === (await read($, graphScope))) return
    await update($, graphDetail, () => null)
    await update($, graphScope, scopeTo(scope))
    requestRefresh()
  } catch {
    // following is best effort: the model's call is never affected
  }
}

/** Seat and expiry bookkeeping of one TO tool call: who is working on which snapshot item, what changed. */
async function noteActivity(
  $: EngineInterface,
  e: { tool: string; agentId?: string },
  changed: boolean,
): Promise<void> {
  const snap = await read($, graphSnapshot)
  if (snap === null) return
  const known = snap.nodes.map(n => n.id)
  const agents = await read($, graphAgents)
  const hits: { id: string; seat: string; model?: string }[] = []
  for (const t of touchedIds(e)) {
    const id = resolveId(t.id, known)
    if (id === null) continue
    const model = e.agentId !== undefined ? agents[e.agentId]?.model : undefined
    hits.push({ id, seat: seatOf(e.agentId, agents, t.actor), ...(model !== undefined ? { model } : {}) })
  }
  if (hits.length === 0) return
  const now = await $.clock.now()
  const fold = (cur: GraphActivity): GraphActivity => hits.reduce((acc, h) => recordActivity(acc, { ...h, agentId: e.agentId ?? 'main', changed }, now), cur)
  const before = await read($, graphActivity)
  if (fold(before) === before) return
  await update($, graphActivity, fold)
  // Entries expire even when nothing else redraws the pane (and a hot reload drops these timers: every
  // record call prunes first, so stale entries clear on the next write too).
  const prune = async (): Promise<void> => {
    const t = await $.clock.now()
    const cur = await read($, graphActivity)
    if (pruneActivity(cur, t) !== cur) await update($, graphActivity, c => pruneActivity(c, t))
  }
  if (changed) $.clock.after(RECENT_MS + 500, () => void prune())
  $.clock.after(WORKING_TTL_MS + 500, () => void prune())
}

/** Marks this session's gate-blocked advance_item rows on their snapshot items; they expire after GATE_BLOCK_MS. */
async function noteGateBlocks($: EngineInterface, rows: readonly GateBlockRow[]): Promise<void> {
  const snap = await read($, graphSnapshot)
  if (snap === null) return
  const known = snap.nodes.map(n => n.id)
  const hits: { id: string; missing: string[]; target?: string }[] = []
  for (const r of rows) {
    const id = resolveId(r.itemId, known)
    if (id !== null) hits.push({ id, missing: r.missing, ...(r.targetRole !== undefined ? { target: r.targetRole } : {}) })
  }
  if (hits.length === 0) return
  const now = await $.clock.now()
  const before = await read($, graphActivity)
  if (recordGateBlocks(before, hits, now) === before) return
  await update($, graphActivity, cur => recordGateBlocks(cur, hits, now))
  $.clock.after(GATE_BLOCK_MS + 500, async () => {
    const t = await $.clock.now()
    const cur = await read($, graphActivity)
    if (pruneActivity(cur, t) !== cur) await update($, graphActivity, c => pruneActivity(c, t))
  })
}

/** With the `graphToasts` option on: one toast per gate-blocked row of this session (capped; events.ts). */
async function toastGateBlocks($: EngineInterface, rows: readonly GateBlockRow[]): Promise<void> {
  for (const line of gateToastLines(rows, await read($, graphSnapshot))) $.ui.toast(line)
}

/** What the tool.call hook needs of the outside world: record one call's activity and its gate blocks. */
export interface ActivityIo {
  note: (e: { tool: string; agentId?: string }, changed: boolean) => Promise<void>
  gate: (rows: readonly GateBlockRow[]) => Promise<void>
}

/** The toast goes first: a block on an item outside the snapshot is still worth one, and marking it may throw. */
const activityIo = ($: EngineInterface, toasts: boolean): ActivityIo => ({
  note: async (e, changed) => {
    await noteActivity($, e, changed)
    await followActivity($, () => touchedIds(e).map(t => t.id))
  },
  gate: async rows => {
    if (toasts) await toastGateBlocks($, rows)
    await noteGateBlocks($, rows)
  },
})

/**
 * The tool.call hook body: skips the pane's own calls, always returns the call's result untouched.
 * The gate blocks are recorded after the call's activity, so the call's own write does not clear them.
 */
export async function trackToolCall<E extends { tool: string; agentId?: string }, R extends { deny?: unknown; isError?: unknown; text?: unknown }>(
  io: ActivityIo,
  e: E,
  next: ((e: E) => Promise<R>) & { origin: { plugin: string } },
): Promise<R> {
  if (next.origin.plugin === PLUGIN) return next(e)
  const ran = await next(e)
  try {
    await io.note(e, shouldRefresh(e.tool, next.origin.plugin, ran))
    const blocks = ran.deny === undefined ? gateBlocks(e.tool, ran.text) : []
    if (blocks.length > 0) await io.gate(blocks)
  } catch {
    // bookkeeping only: the model's call is never affected
  }

  return ran
}

/** Registers /to-graph once per module load. Idempotent. */
async function ensureCommand($: EngineInterface): Promise<void> {
  if (commandRegistered) return
  commandRegistered = true
  try {
    await $.command.register({ name: 'to-graph', description: 'Open the Task Orchestrator work graph (active feature, an item id, or root)' })
  } catch {
    commandRegistered = false
  }
}

type Ui = ReturnType<EngineInterface['ui']['resolve']>

interface BoxSpec {
  key: string
  id: string
  rect: { left: number; top: number; width: number; height: number }
  fill: string
  line1: string
  line3: string
  /** The state line is a warning: drawn undimmed. */
  warn: boolean
  recent: boolean
  /** Last changed by another session: `⇄ ` after the `✱ ` on line 1. */
  remote: boolean
}

/**
 * Below this many boxes every line of a box is clickable; from it on only the state line. 90 whole-click
 * cards (COST.wholeCardChars) leave room for step chips in the tree budget; canvasOf also falls back to
 * one Button per box whenever whole-click boxes alone would not fit.
 */
export const WHOLE_CLICK_MAX = 90

/** One state-filled box: a pure function of its spec (an id-derived key, nothing view-wide). */
function boxOf(ui: Ui, s: BoxSpec, open: (id: string) => void, wholeClick = true): unknown {
  const { Box, Text, Button } = ui
  const n = s.rect.width - 2
  // A recently changed item is marked in its title (a Button label takes no colour); a change made by
  // another session adds `⇄ ` after it.
  const [first, second] = titleLines(`${s.recent ? '✱ ' : ''}${s.remote ? '⇄ ' : ''}${s.line1}`, n)
  const press = () => open(s.id)
  const dim = s.warn ? {} : { dimColor: true }

  const frame = { key: s.key, position: 'absolute', top: s.rect.top, left: s.rect.left, width: s.rect.width, height: s.rect.height, backgroundColor: s.fill, flexDirection: 'column', paddingX: 1 }
  if (!wholeClick) {
    return h(
      Box,
      frame,
      h(Text, { color: '#ffffff', bold: true, wrap: 'truncate-end' }, first),
      h(Text, { color: '#ffffff', wrap: 'truncate-end' }, second),
      h(Button, { key: `open:${s.id}`, label: cut(s.line3, n) || ' ', plain: true, ...dim, onPress: press }),
    )
  }

  // Box takes no onPress and a Button label is a single string, so every line of the box is a plain
  // Button opening the same detail: a click anywhere on the box opens it.
  return h(
    Box,
    frame,
    h(Button, { key: `open:${s.id}:1`, label: first || ' ', plain: true, onPress: press }),
    h(Button, { key: `open:${s.id}:2`, label: second || ' ', plain: true, onPress: press }),
    h(Button, { key: `open:${s.id}`, label: cut(s.line3, n) || ' ', plain: true, ...dim, onPress: press }),
  )
}

interface CanvasInput {
  model: Model
  lay: TopDown
  desktop: boolean
  cell: { w: number; h: number }
  steps: number
  open: (id: string) => void
  count: number
  /** The project-root overview: the root plus one row of children, no steps. */
  overview: boolean
  /** What the detail panel and breadcrumb add (charged to the edge drawing only). */
  extraChars: number
  /** The critical path (empty on the overview). */
  crit: CriticalPath
}

/** The graph canvas (or a one-line notice when the tree would not fit). */
function canvasOf(ui: Ui, i: CanvasInput): unknown[] {
  const { Box, Text, Svg } = ui
  const { model, lay } = i
  const rs = routes(lay, model.cards, i.desktop ? 'px' : 'cell', i.crit.edges)
  const critIds = new Set(i.crit.ids.filter(id => lay.cards.has(id)))
  const marks = critIds.size
  let edges: unknown = null
  let plan: ReturnType<typeof edgePlan>
  const cardCount = lay.cards.size + (model.root !== undefined ? 1 : 0)
  // Whole-click costs more per card: fall back to one Button per card rather than refuse the boxes.
  const wholeClick = model.cards.length < WHOLE_CLICK_MAX && edgePlan({ desktop: i.desktop, wholeClick: true, cards: cardCount, chips: lay.chips.length, cells: 0, runs: 0, svgChars: 0, svgWidth: 0, svgHeight: 0 }) !== 'too-large'
  if (i.desktop) {
    const svg = edgeSvg(rs, i.cell, lay.width, lay.height)
    plan = edgePlan({ desktop: true, wholeClick, extraChars: i.extraChars, marks, cards: lay.cards.size + (model.root !== undefined ? 1 : 0), chips: lay.chips.length, cells: 0, runs: 0, svgChars: svg.length, svgWidth: px(lay.width, i.cell.w), svgHeight: px(lay.height, i.cell.h) })
    if (plan === 'full' && Svg !== undefined) {
      edges = h(Box, { key: 'edges', position: 'absolute', top: 0, left: 0 }, h(Svg, { source: svg, alt: 'dependency edges', width: px(lay.width, i.cell.w), height: px(lay.height, i.cell.h) }))
    }
  } else {
    const cells = raster(rs)
    const merged = runs(cells)
    plan = edgePlan({ desktop: false, wholeClick, extraChars: i.extraChars, marks, cards: lay.cards.size + (model.root !== undefined ? 1 : 0), chips: lay.chips.length, cells: cells.size, runs: merged.length, svgChars: 0, svgWidth: 0, svgHeight: 0 })
    if (plan === 'full') {
      edges = h(
        Box,
        { key: 'edges', position: 'absolute', top: 0, left: 0, width: lay.width, height: lay.height },
        ...[...cells].map(([k, c]) => {
          const [x, y] = k.split('_').map(Number) as [number, number]

          return h(Box, { key: `e:${k}`, position: 'absolute', top: y, left: x, width: 1, height: 1 }, h(Text, { color: c.color, dimColor: c.dim }, c.glyph))
        }),
      )
    } else if (plan === 'runs') {
      edges = h(
        Box,
        { key: 'edges', position: 'absolute', top: 0, left: 0, width: lay.width, height: lay.height },
        ...merged.map(r => h(Box, { key: `e:${r.x}_${r.y}`, position: 'absolute', top: r.y, left: r.x, width: r.n, height: 1 }, h(Text, { color: r.color, dimColor: r.dim }, r.glyph.repeat(r.n)))),
      )
    }
  }
  if (plan === 'too-large') {
    recordFocusOrder(null)

    return [h(Text, { key: 'too-large' }, `Too large to draw (${i.count} items). Open a smaller scope.`)]
  }
  // Stripes go with the edges; with boxes only, when they still fit (they never refuse the scope).
  const stripes = marks > 0 && marksFit({ desktop: i.desktop, wholeClick, extraChars: i.extraChars, marks, cards: cardCount, chips: lay.chips.length, cells: 0, runs: 0, svgChars: 0, svgWidth: 0, svgHeight: 0 }, plan)
  /** Box ids in drawn order, for the one-stop-per-box focus. */
  const order: string[] = []

  const byId = new Map(model.cards.map(c => [c.id, c]))
  const boxes: unknown[] = []
  const root = model.root
  if (root !== undefined) {
    const words = root.kind === 'terminal' ? 'done' : root.kind
    boxes.push(
      boxOf(ui, { key: `card:${root.id}`, id: root.id, rect: lay.root, fill: KIND[root.kind], line1: `${root.glyph} [${root.label}] ${root.title}`, line3: i.overview ? `${words} · ${model.cards.length} children` : `${words} · ${model.cards.length} items · ${i.steps} steps`, warn: false, recent: false, remote: false }, i.open, wholeClick),
    )
    order.push(root.id)
  }
  for (const row of lay.rows) {
    if (row.chip) {
      const chip = lay.chips.find(c => c.step === row.step)
      if (chip === undefined) continue
      boxes.push(
        h(
          Box,
          { key: `step:${chip.step}`, position: 'absolute', top: chip.rect.top, left: chip.rect.left, width: chip.rect.width, height: 1, backgroundColor: KIND.terminal },
          h(Text, { color: '#ffffff', wrap: 'truncate-end' }, `✓ step ${chip.step} · ${chip.done} done${chip.cancelled > 0 ? `, ${chip.cancelled} cancelled` : ''}`),
        ),
      )
      continue
    }
    for (const id of row.ids) {
      const card = byId.get(id) as Card
      const rect = lay.cards.get(id)
      if (rect === undefined) continue
      boxes.push(boxOf(ui, { key: `card:${id}`, id, rect, fill: card.ready ? READY : KIND[card.kind], line1: `${card.glyph} [${card.label}] ${card.title}`, line3: card.stateText, warn: card.warn, recent: card.recent, remote: card.remote }, i.open, wholeClick))
      order.push(id)
      // A sibling stripe on the card's left column: the card's own subtree is untouched (G5 keys hold).
      if (stripes && critIds.has(id)) {
        boxes.push(h(Box, { key: `crit:${id}`, position: 'absolute', top: rect.top, left: rect.left, width: 1, height: rect.height, backgroundColor: CRIT_COLOR }))
      }
    }
  }
  recordFocusOrder(order)

  const omitted = plan === 'omit' ? [h(Text, { key: 'edges-omitted', dimColor: true }, 'Too many edges to draw here; boxes only.')] : []

  return [...omitted, h(Box, { key: 'canvas', position: 'relative', width: lay.width, height: lay.height }, ...(edges !== null ? [edges] : []), ...boxes)]
}

export function registerGraphPane(on: On, options: PluginOptions = {}): void {
  const cell = cellSize(options as { cellWidthPx?: unknown; cellHeightPx?: unknown })
  const toasts = options.graphToasts === true

  // Every unmatched hook of the session events is already taken (graph-data owns session.start, band
  // owns prompt.submit/turn.complete, and the validator refuses a second one), so the command is
  // registered from two matched hooks: the first snapshot landing (graph-data refreshes at session
  // start) and the first AbovePrompt draw, whichever comes first. Idempotent.
  on('state.set', { plugin: 'task-orchestrator-mod', key: 'graphSnapshot' }, async ($, e, next) => {
    const wrote = await next(e)
    await ensureCommand($)

    return wrote
  })

  // The terminal redraws the prompt area at once and often, so this is the reliable early trigger.
  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    await ensureCommand($)

    return next(e)
  })

  on('command.run', { command: 'to-graph' }, async ($, e) => {
    const arg = parseScopeArg(e.args)
    if (arg.kind === 'invalid') return { text: `Unknown argument "${arg.text}". Use /to-graph, /to-graph root, or /to-graph <item id>.` }
    const scope = arg.kind === 'id' ? arg.id : arg.kind === 'root' ? null : await activeFeature($)

    await update($, graphDetail, () => null)
    await update($, graphScopeMode, arg.kind === 'active' ? auto : pinned)
    await update($, graphScope, scopeTo(scope))
    requestRefresh()
    await $.ui.open({ id: PANE_ID, title: 'TO graph' })
    // Counted once per open pane: a repeat /to-graph neither double-counts nor leaks a subscriber.
    // Another session's transition in this project (graph-data/live.ts, debounced and quiet-gated): a $-free listener over this pane's own follow.
    setRemoteAdvanceListener(itemId => void followActivity($, () => [itemId]))
    if (!(await read($, graphPaneOpen))) {
      await update($, graphPaneOpen, yes)
      await update($, graphSubscribers, addSubscriber)
      requestLiveSync(await read($, graphSubscribers))
    }

    return { text: scope === null ? 'Opened the work graph for the project root.' : `Opened the work graph for ${scope.slice(0, 8)}.` }
  })

  on('ui.close', { id: PANE_ID }, async ($, e, next) => {
    const closed = await next(e)
    setRemoteAdvanceListener(null)
    if (await read($, graphPaneOpen)) {
      await update($, graphPaneOpen, no)
      await update($, graphSubscribers, removeSubscriber)
      requestLiveSync(await read($, graphSubscribers))
    }

    return closed
  })

  // Remembers each spawned subagent's seat and model, so its TO calls can be attributed. Unmatched, and
  // the only agent.spawn hook of the mod; the result is returned untouched.
  on('agent.spawn', async ($, e, next) => {
    const spawned = await next(e)
    if (spawned.agentId !== undefined) {
      const agentId = spawned.agentId
      const info: GraphAgentInfo = { seat: e.subagentType.replace(/^[^:]+:/, ''), ...(spawned.model !== undefined ? { model: spawned.model } : {}) }
      try {
        await update($, graphAgents, cur => rememberAgent(cur, agentId, info))
      } catch {
        // bookkeeping only: the spawn is never affected
      }
    }
    await followActivity($, rootId => {
      const id = firstSpawnUuid(e.description, e.prompt, rootId)

      return id === null ? [] : [id]
    })

    return spawned
  })

  // Who is working on which item, and what changed a moment ago. Matched (the mod's other tool.call hooks
  // are matched too); the pane's own reads are skipped; the result is always returned as it came.
  on('tool.call', { tool: TO_TOOL }, ($, e, next) => trackToolCall(activityIo($, toasts), e, next))

  // One focus stop per box: a whole-click box's title lines hand the ring to its state line (focus.ts).
  // Matcher spelled as a literal (an imported constant stays unresolved and never matches).
  on('ui.focus', { requestId: 'to-graph' }, async ($, e, next) => redirectFocus(e, next))

  on('ui.render', { component: 'Pane', requestId: PANE_ID }, async ($, e) => {
    // Cross-session follow: (re)set the one-slot listener on every draw so a pane opened from the band or redrawn after a hot reload still follows (a draw after close is harmless: followActivity checks graphPaneOpen).
    setRemoteAdvanceListener(itemId => void followActivity($, () => [itemId]))
    const ui = $.ui.resolve(e)
    const { Box, Text, Button, Svg } = ui
    const snap = await read($, graphSnapshot)
    const status = await read($, graphStatus)
    const scope = await read($, graphScope)
    const mode = await read($, graphScopeMode)
    const detail = await read($, graphDetail)
    const activity = await read($, graphActivity)
    const showDone = await read($, graphShowDone)
    const remote = (await read($, graphRemote)) ?? {}
    const open = (id: string): Promise<void> => openDetail($, id)

    const view: GraphView | null = snap
    const switching = isSwitching(scope, snap)
    const model = view === null ? null : cardsOf(view, activity.working, activity.changed, activity.blocked ?? {}, remote)
    const steps = model === null ? null : stepsOf(model.cards)
    const crit: CriticalPath = model === null || steps === null || view?.overview === true ? { ids: [], edges: new Set() } : criticalPath(model.cards, steps)
    const lay = model === null || steps === null ? null : layoutTD(model.cards, steps, { bodyColumns: (e.props as { bodyColumns?: number }).bodyColumns, showDone: showDone || view?.overview === true, hasRoot: model.root !== undefined, wrap: view?.overview === true })

    // Refresh is automatic (SSE or poll); Reconnect restarts the live source and shows only while degraded.
    const controls = h(
      Box,
      { key: 'controls', flexDirection: 'row', columnGap: 1, marginBottom: 1 },
      // Two-state scope toggle; the active scope draws as the primary button.
      h(Button, {
        key: 'scope-feature',
        label: 'This feature',
        ...(scope !== null ? { variant: 'primary' } : {}),
        onPress: async () => {
          const feature = await activeFeature($)
          if (feature === null) $.ui.toast('No active feature in this project.')
          else {
            await update($, graphScopeMode, pinned)
            await update($, graphScope, scopeTo(feature))
            requestRefresh()
          }
        },
      }),
      h(Button, { key: 'scope-project', label: 'Whole project', ...(scope === null ? { variant: 'primary' } : {}), onPress: async () => {
        await update($, graphScopeMode, pinned)
        await update($, graphScope, scopeTo(null))
        requestRefresh()
      } }),
      // Shown only while pinned: back to following this session's own TO activity (the bare /to-graph result).
      ...(mode === 'pinned'
        ? [h(Button, { key: 'scope-follow', label: 'Follow', onPress: async () => {
            await update($, graphScopeMode, auto)
            await update($, graphScope, scopeTo(await activeFeature($)))
            requestRefresh()
          } })]
        : []),
      ...(lay !== null && view?.overview !== true && lay.doneSteps.length > 0
        ? [h(Button, { key: 'done-steps', label: showDone ? 'Hide done steps' : 'Show done steps', onPress: () => update($, graphShowDone, v => !v) })]
        : []),
      ...(isDegraded(status) ? [h(Button, { key: 'reconnect', label: 'Reconnect', onPress: async () => {
        await update($, graphReconnectRequest, bump)
        requestReconnect(await read($, graphSubscribers))
      } })] : []),
    )

    if (view === null || model === null || steps === null || lay === null) {
      return h(Box, { flexDirection: 'column' }, controls, h(Text, { key: 'loading' }, status.lastError ?? 'Loading the work graph…'))
    }

    // A scope switch is still loading: say so, and draw nothing of the old scope (the header would claim it).
    if (switching) return h(Box, { flexDirection: 'column' }, controls, h(Text, { key: 'switching' }, `Loading ${loadingTitle(scope, snap)}…`))

    // With a breadcrumb the scope is named there; the header keeps the counts and the live source.
    const trail = view.trail ?? []
    const header = [...(trail.length > 0 ? [] : [scopeHeader(view)]), summaryLine(view), `live: ${status.liveSource}${status.refreshing ? ' · refreshing' : ''}`].join(' · ')
    const crumbs = trail.flatMap((c, i) => {
      const last = i === trail.length - 1
      const sep = i > 0 ? [h(Text, { key: `crumb-sep:${c.id}`, dimColor: true }, ' › ')] : []
      if (last) return [...sep, h(Text, { key: `crumb:${c.id}`, bold: true }, cut(c.title, 40))]
      const target = c.id === view.rootId ? null : c.id

      return [
        ...sep,
        h(Button, {
          key: `crumb:${c.id}`,
          label: cut(c.title, 28),
          plain: true,
          onPress: async () => {
            await update($, graphDetail, () => null)
            await update($, graphScopeMode, pinned)
            await update($, graphScope, scopeTo(target))
            requestRefresh()
          },
        }),
      ]
    })
    const body: unknown[] = []
    if (view.truncated) body.push(h(Text, { key: 'truncated', dimColor: true }, view.overview === true ? 'More children than shown.' : 'Large subtree: showing only the shallowest 150 items.'))
    if (view.error !== undefined) body.push(h(Text, { key: 'error', color: 'red' }, view.error))

    if (view.nodes.length === 0) {
      body.push(h(Text, { key: 'empty' }, 'No items in this scope.'))
    } else {
      body.push(
        h(
          Box,
          { key: 'legend', flexDirection: 'row', flexWrap: 'wrap', rowGap: 1, marginBottom: 1 },
          ...legendItems().map(item => h(Box, { key: `legend-${item.key}`, backgroundColor: item.color, paddingX: 1, marginRight: 1 }, h(Text, { color: '#ffffff' }, item.text))),
          h(Box, { key: 'legend-ready', backgroundColor: READY, paddingX: 1, marginRight: 1 }, h(Text, { color: '#ffffff' }, '○ ready')),
          h(Text, { key: 'legend-edges', dimColor: true }, '┄ contains   ╌ open blocker   ─ satisfied'),
          ...(crit.ids.length > 0 ? [h(Text, { key: 'legend-crit', color: CRIT_COLOR }, '   ━ critical path')] : []),
          ...(view.nodes.some(n => n.planLabel !== undefined) ? [h(Text, { key: 'legend-label', dimColor: true }, '   Tn = plan label')] : []),
        ),
      )
      if (lay.tooWide !== null) {
        body.push(h(Text, { key: 'too-wide', dimColor: true }, `Graph is ${lay.tooWide} columns wide; the pane shows ${lay.cols}. Widen the pane or open a smaller scope.`))
      }
      body.push(...canvasOf(ui, { model, lay, desktop: e.surface !== 'terminal' && Svg !== undefined, cell, steps: steps.max, open, count: view.nodes.length, overview: view.overview === true, extraChars: extrasChars(detail?.lines ?? null, trail.map(c => c.title)), crit }))
    }

    if (detail !== null) {
      // The detail is its own bordered panel: title line bold, then facts, then a row of actions.
      const facts = [
        ...detail.lines.map((line, i) => h(Text, { key: `detail-${i}`, ...(i === 0 ? { bold: true } : {}) }, line)),
        h(Text, { key: 'detail-uuid', dimColor: true }, `uuid: ${detail.itemId}`),
      ]
      const working = (activity.working[detail.itemId] ?? []).map(w =>
        h(Text, { key: `working-${w.agentId}` }, `working: ${w.seat}${w.model !== undefined ? ` · ${w.model}` : ''} · ${w.agentId === 'main' ? 'main loop' : id8(w.agentId)}`),
      )
      const actions = [
        h(Button, {
          key: 'detail-copy',
          label: 'Copy UUID',
          onPress: async press => {
            let copied = false
            try {
              const res = (await $.ui.copy({ text: detail.itemId, surface: press.surface })) as { isCopied?: boolean; value?: { isCopied?: boolean } }
              copied = res.isCopied === true || res.value?.isCopied === true
            } catch {
              copied = false
            }
            // $.ui.copy has no path on a remote surface (the desktop app) yet: fall back to the OS clipboard tool.
            if (!copied) copied = await hostCopy($, detail.itemId)
            $.ui.toast(copied ? `Copied ${detail.itemId}` : `Copy unavailable here. UUID: ${detail.itemId}`)
          },
        }),
        ...(detail.itemId !== view.scopeId
          ? [
              h(Button, {
                key: 'detail-open-graph',
                label: 'Open graph',
                onPress: async () => {
                  await update($, graphDetail, () => null)
                  await update($, graphScopeMode, pinned)
                  await update($, graphScope, scopeTo(detail.itemId))
                  requestRefresh()
                },
              }),
            ]
          : []),
        h(Button, { key: 'detail-close', label: 'Close detail', onPress: () => update($, graphDetail, () => null) }),
      ]
      body.push(
        h(
          Box,
          { key: 'detail', flexDirection: 'column', borderStyle: 'round', borderColor: '#4b5563', paddingX: 1, marginTop: 1 },
          ...facts,
          ...working,
          h(Box, { key: 'detail-actions', flexDirection: 'row', columnGap: 1, marginTop: 1 }, ...actions),
        ),
      )
    }

    return h(
      Box,
      { flexDirection: 'column', paddingX: 1 },
      ...(crumbs.length > 0 ? [h(Box, { key: 'trail', flexDirection: 'row', flexWrap: 'wrap' }, ...crumbs)] : []),
      h(Box, { key: 'header', marginBottom: 1 }, h(Text, { bold: crumbs.length === 0, dimColor: crumbs.length > 0 }, header)),
      controls,
      ...body,
    )
  })
}
