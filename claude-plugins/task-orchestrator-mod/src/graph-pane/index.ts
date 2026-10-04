// /to-graph pane: layered DAG layout + SVG and text renderers (T3).
// Owned by work item a47dcd6f-f956-44fd-b0be-0d24719d945b. Read-only: the pane makes only
// query_items and get_context calls and no TO write tool, ever.
//
// This is the only file here that touches `$` (the plugin validator follows `$` only into functions
// of the same file). Control is atom-driven, so the atoms below are this file's own same-literal
// declarations of graph-data's keys; only the pure steps come from graph-data.
import { atom, read, update } from 'claude-code'
import type { EngineInterface, On } from 'claude-code'

import type { GraphDetail, GraphSnapshot, GraphStatus } from '../../types'
import { CONFIG_PATH, parseProjectRootId } from '../shared/config.ts'
import { TO_SERVER } from '../shared/constants.ts'
import { parseToResult } from '../shared/to-client.ts'
import { addSubscriber, bump, removeSubscriber, scopeTo } from '../graph-data/index.ts'
import { collapse } from './collapse.ts'
import { layout } from './layout.ts'
import { formatDetail, isDegraded, parseScopeArg, scopeTitle, sideLabel, sideList, summaryLine } from './pane-model.ts'
import { renderSvg } from './render-svg.ts'
import type { Theme } from './render-svg.ts'
import { renderText } from './render-text.ts'
import { dagOf, legendItems } from './shared.ts'
import type { GraphView } from './shared.ts'

export const PANE_ID = 'to-graph'

/** Every TO tool this pane calls; none is a write tool. */
export const READ_TOOLS: readonly string[] = ['query_items', 'get_context']

const graphSnapshot = atom({ plugin: 'task-orchestrator-mod', key: 'graphSnapshot' } as const, null as GraphSnapshot | null)
const graphStatus = atom({ plugin: 'task-orchestrator-mod', key: 'graphStatus' } as const, { refreshing: false, liveSource: 'none' } as GraphStatus)
const graphScope = atom({ plugin: 'task-orchestrator-mod', key: 'graphScope' } as const, null as string | null)
const graphSubscribers = atom({ plugin: 'task-orchestrator-mod', key: 'graphSubscribers' } as const, 0)
const graphReconnectRequest = atom({ plugin: 'task-orchestrator-mod', key: 'graphReconnectRequest' } as const, 0)
const graphPaneOpen = atom({ plugin: 'task-orchestrator-mod', key: 'graphPaneOpen' } as const, false)
const graphDetail = atom({ plugin: 'task-orchestrator-mod', key: 'graphDetail' } as const, null as GraphDetail | null)

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

/** Reads get_context for one node into the detail atom. In-pane, read-only. */
async function openDetail($: EngineInterface, itemId: string): Promise<void> {
  let lines: string[]
  try {
    lines = formatDetail(parseToResult<unknown>('get_context', await $.mcp.call(TO_SERVER, 'get_context', { itemId })))
  } catch (err) {
    lines = [`Could not read ${itemId.slice(0, 8)}: ${err instanceof Error ? err.message : String(err)}`]
  }
  await update($, graphDetail, () => ({ itemId, lines }))
}

/** `dark` unless the host's theme row says light. */
async function themeOf($: EngineInterface): Promise<Theme> {
  try {
    const row = (await $.config.list()).find(r => r.key === 'theme')

    return typeof row?.value === 'string' && row.value.toLowerCase().includes('light') ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
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

export function registerGraphPane(on: On): void {
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
    await $.ui.open({ id: PANE_ID, title: 'TO graph' })
    // Counted once per open pane: a repeat /to-graph neither double-counts nor leaks a subscriber.
    if (!(await read($, graphPaneOpen))) {
      await update($, graphPaneOpen, yes)
      await update($, graphSubscribers, addSubscriber)
    }

    return { text: scope === null ? 'Opened the work graph for the project root.' : `Opened the work graph for ${scope.slice(0, 8)}.` }
  })

  on('ui.close', { id: PANE_ID }, async ($, e, next) => {
    const closed = await next(e)
    if (await read($, graphPaneOpen)) {
      await update($, graphPaneOpen, no)
      await update($, graphSubscribers, removeSubscriber)
    }

    return closed
  })

  on('ui.render', { component: 'Pane', requestId: PANE_ID }, async ($, e) => {
    const { Box, Text, Button, Svg } = $.ui.resolve(e)
    const snap = await read($, graphSnapshot)
    const status = await read($, graphStatus)
    const scope = await read($, graphScope)
    const detail = await read($, graphDetail)

    // Refresh is automatic (SSE or poll); Reconnect restarts the live source and shows only while degraded.
    const controls = h(
      Box,
      { key: 'controls' },
      // Two-state scope toggle; the active scope draws as the primary button.
      h(Button, {
        key: 'scope-feature',
        label: 'This feature',
        ...(scope !== null ? { variant: 'primary' } : {}),
        onPress: async () => {
          const feature = await activeFeature($)
          if (feature === null) $.ui.toast('No active feature in this project.')
          else await update($, graphScope, scopeTo(feature))
        },
      }),
      h(Button, { key: 'scope-project', label: 'Whole project', ...(scope === null ? { variant: 'primary' } : {}), onPress: () => update($, graphScope, scopeTo(null)) }),
      ...(isDegraded(status) ? [h(Button, { key: 'reconnect', label: 'Reconnect', onPress: () => update($, graphReconnectRequest, bump) })] : []),
    )

    if (snap === null) {
      return h(Box, { flexDirection: 'column' }, controls, h(Text, { key: 'loading' }, status.lastError ?? 'Loading the work graph…'))
    }

    const view: GraphView = collapse(snap, scope === null)
    const header = [scope === null ? scopeTitle(view) : `Feature: ${scopeTitle(view)}`, summaryLine(view), `live: ${status.liveSource}${status.refreshing ? ' · refreshing' : ''}`].join(' · ')
    const body: unknown[] = []
    if (view.truncated) body.push(h(Text, { key: 'truncated', dimColor: true }, 'Large subtree: showing only the shallowest 150 items.'))
    if (view.error !== undefined) body.push(h(Text, { key: 'error', color: 'red' }, view.error))

    if (view.nodes.length === 0) {
      body.push(h(Text, { key: 'empty' }, 'No items in this scope.'))
    } else {
      body.push(
        h(
          Box,
          { key: 'legend', flexDirection: 'row' },
          ...legendItems().map(item => h(Text, { key: `legend-${item.key}`, color: item.color }, `${item.text}  `)),
          ...(view.nodes.some(n => n.planLabel !== undefined) ? [h(Text, { key: 'legend-label', dimColor: true }, 'Tn = plan label')] : []),
        ),
      )
      const lines = renderText(view)
      const dag = dagOf(view)
      const placed = layout(dag.nodes, dag.edges)
      const svg = e.surface === 'terminal' ? null : renderSvg(placed, view, { theme: await themeOf($) })
      if (svg !== null) {
        // Explicit size: without it the desktop fits the markup into a short default box, scaling the
        // graph down and letterboxing the rest of the slot in white.
        body.push(h(Svg, { key: 'graph', source: svg, alt: `Work graph of ${scopeTitle(view)}: ${summaryLine(view)}`, isInteractive: true, width: placed.width, height: placed.height }))
      } else {
        if (e.surface !== 'terminal') body.push(h(Text, { key: 'fallback', dimColor: true }, 'Too large to draw; showing the text tree.'))
        lines.forEach((line, i) => body.push(h(Text, { key: `line-${i}` }, line)))
      }
      for (const node of sideList(view)) {
        body.push(h(Button, { key: `node:${node.id}`, label: sideLabel(node, view.gates[node.id]), onPress: () => openDetail($, node.id) }))
      }
    }

    if (detail !== null) {
      detail.lines.forEach((line, i) => body.push(h(Text, { key: `detail-${i}` }, line)))
      body.push(h(Button, { key: 'detail-close', label: 'Close detail', onPress: () => update($, graphDetail, () => null) }))
    }

    return h(Box, { flexDirection: 'column' }, h(Text, { key: 'header', bold: true }, header), controls, ...body)
  })
}
