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
import { addSubscriber, bump, removeSubscriber, requestLiveSync, requestReconnect, requestRefresh, scopeTo, shouldRefresh } from '../graph-data/index.ts'
import { WORKING_TTL_MS, RECENT_MS, pruneActivity, recordActivity, rememberAgent, resolveId, seatOf, touchedIds } from './activity.ts'
import { edgePlan } from './budget.ts'
import { cellSize } from './cell.ts'
import { layoutTD } from './layout.ts'
import { cut, titleLines } from './wrap.ts'
import type { TopDown } from './layout.ts'
import { cardsOf, stepsOf } from './model.ts'
import type { Card, Model } from './model.ts'
import { detailLines, isDegraded, isSwitching, loadingTitle, parseScopeArg, scopeHeader, summaryLine } from './pane-model.ts'
import { raster, runs } from './raster.ts'
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
const graphSubscribers = atom({ plugin: 'task-orchestrator-mod', key: 'graphSubscribers' } as const, 0)
const graphReconnectRequest = atom({ plugin: 'task-orchestrator-mod', key: 'graphReconnectRequest' } as const, 0)
const graphPaneOpen = atom({ plugin: 'task-orchestrator-mod', key: 'graphPaneOpen' } as const, false)
const graphDetail = atom({ plugin: 'task-orchestrator-mod', key: 'graphDetail' } as const, null as GraphDetail | null)
const graphActivity = atom({ plugin: 'task-orchestrator-mod', key: 'graphActivity' } as const, { working: {}, changed: {} } as GraphActivity)
const graphAgents = atom({ plugin: 'task-orchestrator-mod', key: 'graphAgents' } as const, {} as Record<string, GraphAgentInfo>)
const graphShowDone = atom({ plugin: 'task-orchestrator-mod', key: 'graphShowDone' } as const, false)

/** The engine's spelling of every TO tool (`mcp__<server>__<tool>`); reads count as "working on it". */
const TO_TOOL = /^mcp__.*task-orchestrator.*__[a-z_]+$/

const yes = (): boolean => true
const no = (): boolean => false

/** Set once per module load, so a hot reload (which drops the registered command) registers it again. */
let commandRegistered = false

/** The most recently modified feature-implementation in work under the project root, or null. */
async function activeFeature($: EngineInterface): Promise<string | null> {
  try {
    const rootId = parseProjectRootId(await $.fs.read(CONFIG_PATH))
    if (rootId === null) return null
    const found = parseToResult<{ items?: { id?: unknown }[] }>(
      'query_items',
      await $.mcp.call(TO_SERVER, 'query_items', {
        operation: 'search',
        ancestorId: rootId,
        type: 'feature-implementation',
        role: 'work',
        sortBy: 'modifiedAt',
        sortOrder: 'desc',
        limit: 1,
      }),
    )
    const id = found.items?.[0]?.id

    return typeof id === 'string' ? id : null
  } catch {
    return null
  }
}

const message = (err: unknown): string => (err instanceof Error ? err.message : String(err))

/** Reads the three read-only calls for one node into the detail atom. Each failure is tolerated. In-pane, read-only. */
async function openDetail($: EngineInterface, itemId: string): Promise<void> {
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

/** What the tool.call hook needs of the outside world: record one call's activity. */
export interface ActivityIo {
  note: (e: { tool: string; agentId?: string }, changed: boolean) => Promise<void>
}

const activityIo = ($: EngineInterface): ActivityIo => ({ note: (e, changed) => noteActivity($, e, changed) })

/** The tool.call hook body: skips the pane's own calls, always returns the call's result untouched. */
export async function trackToolCall<E extends { tool: string; agentId?: string }, R extends { deny?: unknown; isError?: unknown }>(
  io: ActivityIo,
  e: E,
  next: ((e: E) => Promise<R>) & { origin: { plugin: string } },
): Promise<R> {
  if (next.origin.plugin === PLUGIN) return next(e)
  const ran = await next(e)
  try {
    await io.note(e, shouldRefresh(e.tool, next.origin.plugin, ran))
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
  recent: boolean
}

/** One state-filled box: a pure function of its spec (an id-derived key, nothing view-wide). */
function boxOf(ui: Ui, s: BoxSpec, open: (id: string) => void): unknown {
  const { Box, Text, Button } = ui
  const n = s.rect.width - 2
  const [first, second] = titleLines(s.line1, n)
  const tone = s.recent ? { color: '#fde68a', underline: true } : { color: '#ffffff' }

  return h(
    Box,
    { key: s.key, position: 'absolute', top: s.rect.top, left: s.rect.left, width: s.rect.width, height: s.rect.height, backgroundColor: s.fill, flexDirection: 'column', paddingX: 1 },
    h(Text, { ...tone, bold: true, wrap: 'truncate-end' }, first),
    h(Text, { ...tone, wrap: 'truncate-end' }, second),
    h(Button, { key: `open:${s.id}`, label: cut(s.line3, n), plain: true, dimColor: true, onPress: () => open(s.id) }),
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
}

/** The graph canvas (or a one-line notice when the tree would not fit). */
function canvasOf(ui: Ui, i: CanvasInput): unknown[] {
  const { Box, Text, Svg } = ui
  const { model, lay } = i
  const rs = routes(lay, model.cards, i.desktop ? 'px' : 'cell')
  let edges: unknown = null
  let plan: ReturnType<typeof edgePlan>
  if (i.desktop) {
    const svg = edgeSvg(rs, i.cell, lay.width, lay.height)
    plan = edgePlan({ desktop: true, cards: lay.cards.size + (model.root !== undefined ? 1 : 0), chips: lay.chips.length, cells: 0, runs: 0, svgChars: svg.length, svgWidth: px(lay.width, i.cell.w), svgHeight: px(lay.height, i.cell.h) })
    if (plan === 'full' && Svg !== undefined) {
      edges = h(Box, { key: 'edges', position: 'absolute', top: 0, left: 0 }, h(Svg, { source: svg, alt: 'dependency edges', width: px(lay.width, i.cell.w), height: px(lay.height, i.cell.h) }))
    }
  } else {
    const cells = raster(rs)
    const merged = runs(cells)
    plan = edgePlan({ desktop: false, cards: lay.cards.size + (model.root !== undefined ? 1 : 0), chips: lay.chips.length, cells: cells.size, runs: merged.length, svgChars: 0, svgWidth: 0, svgHeight: 0 })
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
  if (plan === 'too-large') return [h(Text, { key: 'too-large' }, `Too large to draw (${i.count} items). Open a smaller scope.`)]

  const byId = new Map(model.cards.map(c => [c.id, c]))
  const boxes: unknown[] = []
  const root = model.root
  if (root !== undefined) {
    const words = root.kind === 'terminal' ? 'done' : root.kind
    boxes.push(
      boxOf(ui, { key: `card:${root.id}`, id: root.id, rect: lay.root, fill: KIND[root.kind], line1: `${root.glyph} [${root.label}] ${root.title}`, line3: i.overview ? `${words} · ${model.cards.length} children` : `${words} · ${model.cards.length} items · ${i.steps} steps`, recent: false }, i.open),
    )
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
      boxes.push(boxOf(ui, { key: `card:${id}`, id, rect, fill: card.ready ? READY : KIND[card.kind], line1: `${card.glyph} [${card.label}] ${card.title}`, line3: card.stateText, recent: card.recent }, i.open))
    }
  }

  const omitted = plan === 'omit' ? [h(Text, { key: 'edges-omitted', dimColor: true }, 'Too many edges to draw here; boxes only.')] : []

  return [...omitted, h(Box, { key: 'canvas', position: 'relative', width: lay.width, height: lay.height }, ...(edges !== null ? [edges] : []), ...boxes)]
}

export function registerGraphPane(on: On, options: PluginOptions = {}): void {
  const cell = cellSize(options as { cellWidthPx?: unknown; cellHeightPx?: unknown })

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
    await update($, graphScope, scopeTo(scope))
    requestRefresh()
    await $.ui.open({ id: PANE_ID, title: 'TO graph' })
    // Counted once per open pane: a repeat /to-graph neither double-counts nor leaks a subscriber.
    if (!(await read($, graphPaneOpen))) {
      await update($, graphPaneOpen, yes)
      await update($, graphSubscribers, addSubscriber)
      requestLiveSync(await read($, graphSubscribers))
    }

    return { text: scope === null ? 'Opened the work graph for the project root.' : `Opened the work graph for ${scope.slice(0, 8)}.` }
  })

  on('ui.close', { id: PANE_ID }, async ($, e, next) => {
    const closed = await next(e)
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

    return spawned
  })

  // Who is working on which item, and what changed a moment ago. Matched (the mod's other tool.call hooks
  // are matched too); the pane's own reads are skipped; the result is always returned as it came.
  on('tool.call', { tool: TO_TOOL }, ($, e, next) => trackToolCall(activityIo($), e, next))

  on('ui.render', { component: 'Pane', requestId: PANE_ID }, async ($, e) => {
    const ui = $.ui.resolve(e)
    const { Box, Text, Button, Svg } = ui
    const snap = await read($, graphSnapshot)
    const status = await read($, graphStatus)
    const scope = await read($, graphScope)
    const detail = await read($, graphDetail)
    const activity = await read($, graphActivity)
    const showDone = await read($, graphShowDone)
    const open = (id: string): Promise<void> => openDetail($, id)

    const view: GraphView | null = snap
    const switching = isSwitching(scope, snap)
    const model = view === null ? null : cardsOf(view, activity.working, activity.changed)
    const steps = model === null ? null : stepsOf(model.cards)
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
            await update($, graphScope, scopeTo(feature))
            requestRefresh()
          }
        },
      }),
      h(Button, { key: 'scope-project', label: 'Whole project', ...(scope === null ? { variant: 'primary' } : {}), onPress: async () => {
        await update($, graphScope, scopeTo(null))
        requestRefresh()
      } }),
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

    const header = [scopeHeader(view), summaryLine(view), `live: ${status.liveSource}${status.refreshing ? ' · refreshing' : ''}`].join(' · ')
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
          ...(view.nodes.some(n => n.planLabel !== undefined) ? [h(Text, { key: 'legend-label', dimColor: true }, '   Tn = plan label')] : []),
        ),
      )
      if (lay.tooWide !== null) {
        body.push(h(Text, { key: 'too-wide', dimColor: true }, `Graph is ${lay.tooWide} columns wide; the pane shows ${lay.cols}. Widen the pane or open a smaller scope.`))
      }
      body.push(...canvasOf(ui, { model, lay, desktop: e.surface !== 'terminal' && Svg !== undefined, cell, steps: steps.max, open, count: view.nodes.length, overview: view.overview === true }))
    }

    if (detail !== null) {
      // The detail is its own bordered panel: title line bold, then facts, then a row of actions.
      const facts = detail.lines.map((line, i) => h(Text, { key: `detail-${i}`, ...(i === 0 ? { bold: true } : {}) }, line))
      const working = (activity.working[detail.itemId] ?? []).map(w =>
        h(Text, { key: `working-${w.agentId}` }, `working: ${w.seat}${w.model !== undefined ? ` · ${w.model}` : ''} · ${w.agentId === 'main' ? 'main loop' : id8(w.agentId)}`),
      )
      const actions = [
        h(Button, {
          key: 'detail-copy',
          label: 'Copy UUID',
          onPress: async press => {
            try {
              const copied = await $.ui.copy({ text: detail.itemId, surface: press.surface })
              $.ui.toast(copied.isCopied ? `Copied ${id8(detail.itemId)}` : 'Copy failed: the surface refused')
            } catch (err) {
              $.ui.toast(`Copy failed: ${message(err)}`)
            }
          },
        }),
        ...(detail.itemId !== view.scopeId
          ? [
              h(Button, {
                key: 'detail-open-graph',
                label: 'Open graph',
                onPress: async () => {
                  await update($, graphDetail, () => null)
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
      h(Box, { key: 'header', marginBottom: 1 }, h(Text, { bold: true }, header)),
      controls,
      ...body,
    )
  })
}
