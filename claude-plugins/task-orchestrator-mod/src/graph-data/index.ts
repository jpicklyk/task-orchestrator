// Graph snapshot data layer + live invalidation (T2).
// Owned by work item 32492bfa-93d5-4e72-93cc-e56ecf8b68c8. Read-only: the data layer renders
// nothing and never calls a TO write tool. T3 (pane) and T4 (band) import from this file.
//
// This is the only file here that touches `$`: the plugin validator follows `$` only into functions
// declared in the same file, so `graphIo` turns `$` into plain functions (GraphIo) and every other
// module takes that. Consumers control the layer through the atoms (see the atoms below), not by calling
// helpers with their own `$`.
import { atom, read, update } from 'claude-code'
import type { EngineInterface, On } from 'claude-code'

import { PLUGIN, TO_SERVER, toToolName } from '../shared/constants.ts'
import type { GraphSnapshot, GraphStatus } from '../../types'
import type { GraphIo } from './io.ts'
import { invalidateLabels } from './labels.ts'
import { restartLive, syncLive } from './live.ts'
import { refresh, refreshNow } from './refresh.ts'

export type { GateInfo, GraphEdge, GraphNode, GraphSnapshot, GraphStatus } from '../../types'
// ── Atoms: the control and data surface for T3 (pane) and T4 (band) ──
// Consumers cannot call helpers that take `$` (the validator follows `$` only into functions of the
// same file), so they use the library's `update($, atom, step)` / `read($, atom)` on these:
//   - show a scope:         update($, graphScope, scopeTo(id))            // null = the project root
//   - start/stop live:      update($, graphSubscribers, addSubscriber)    // on pane/band open
//                           update($, graphSubscribers, removeSubscriber) // on close
//   - ask for a refresh:    update($, graphRefreshRequest, bump)
// The hooks below react to writes of those three keys. Plugin and key are literals so
// `claude plugin validate` can list them.

/** The item id in view; null means the project root. Writing it re-snapshots at once. */
export const graphScope = atom({ plugin: 'task-orchestrator-mod', key: 'graphScope' } as const, null as string | null)

/** The latest snapshot, or null before the first one lands. Written by graph-data only. */
export const graphSnapshot = atom({ plugin: 'task-orchestrator-mod', key: 'graphSnapshot' } as const, null as GraphSnapshot | null)

/** Whether a refresh is running, the last error, and which live source keeps the snapshot current. */
export const graphStatus = atom(
  { plugin: 'task-orchestrator-mod', key: 'graphStatus' } as const,
  { refreshing: false, liveSource: 'none' } as GraphStatus,
)

/** Consumers showing the graph. The SSE stream or the poll runs only while this is above 0. */
export const graphSubscribers = atom({ plugin: 'task-orchestrator-mod', key: 'graphSubscribers' } as const, 0)

/** Bump it to request a debounced re-snapshot. */
export const graphRefreshRequest = atom({ plugin: 'task-orchestrator-mod', key: 'graphRefreshRequest' } as const, 0)

/** Bump it to restart the live source (SSE/poll) and re-snapshot. */
export const graphReconnectRequest = atom({ plugin: 'task-orchestrator-mod', key: 'graphReconnectRequest' } as const, 0)

/** Steps for `update($, atom, step)`. */
export const addSubscriber = (n: number): number => n + 1
export const removeSubscriber = (n: number): number => Math.max(0, n - 1)
export const bump = (n: number): number => n + 1
export const scopeTo =
  (id: string | null) =>
  (): string | null =>
    id

export type { GraphIo } from './io.ts'
export { snapshot } from './snapshot.ts'
export { invalidateLabels } from './labels.ts'

/** TO tools whose calls can change the graph; a successful call re-snapshots. */
export const WRITE_TOOLS: ReadonlySet<string> = new Set([
  'advance_item',
  'manage_notes',
  'create_work_tree',
  'manage_items',
  'manage_dependencies',
  'complete_tree',
  'claim_item',
])

/** The engine's spelling of those tools (`mcp__<server>__<tool>`), for the hook matcher. */
export const WRITE_TOOL_NAME = /^mcp__.*task-orchestrator.*__(advance_item|manage_notes|create_work_tree|manage_items|manage_dependencies|complete_tree|claim_item)$/

/**
 * Whether a finished tool call should refresh the graph: a TO write tool, not raised by this plugin's
 * own `$` call (its reads re-enter `tool.call`), and neither denied nor errored.
 */
export function shouldRefresh(tool: string, originPlugin: string, result: { deny?: unknown; isError?: unknown }): boolean {
  const name = toToolName(tool)

  return name !== null && WRITE_TOOLS.has(name) && originPlugin !== PLUGIN && result.deny === undefined && result.isError !== true
}

/** The parsed JSON text of a TO tool result; throws on an MCP error result or bad JSON. */
export function parseToolResult(tool: string, result: { content: readonly unknown[]; isError?: boolean }): unknown {
  let text = ''
  for (const block of result.content) {
    const b = block as { type?: string; text?: string }
    if (b.type === 'text' && typeof b.text === 'string') {
      text = b.text
      break
    }
  }
  if (result.isError) throw new Error(`${tool}: ${text || 'error result'}`)

  return JSON.parse(text)
}

function graphIo($: EngineInterface): GraphIo {
  return {
    callTool: async (tool, args) => parseToolResult(tool, await $.mcp.call(TO_SERVER, tool, args)),
    readFile: path => $.fs.read(path),
    now: () => $.clock.now(),
    after: (ms, fn) => $.clock.after(ms, fn),
    every: (ms, fn) => $.clock.every(ms, fn),
    sleep: (ms, signal) => $.clock.sleep(ms, { signal }),
    spawn: request => $.process.spawn(request),
    readScope: () => read($, graphScope),
    setSnapshot: value => update($, graphSnapshot, () => value),
    updateStatus: change => update($, graphStatus, change),
    envApiUrl: () => $.env.get('TASK_ORCHESTRATOR_API_URL'),
    envApiToken: () => $.env.get('TASK_ORCHESTRATOR_API_TOKEN'),
    homeDir: async () => (await $.env.get('TASK_ORCHESTRATOR_HOME')) || (await $.env.get('HOME')) || (await $.env.get('USERPROFILE')),
  }
}

export function registerGraphData(on: On): void {
  // Matched (the validator refuses a second unmatched `tool.call`): same names as WRITE_TOOLS.
  on('tool.call', { tool: WRITE_TOOL_NAME }, async ($, e, next) => {
    if (next.origin.plugin === PLUGIN) return next(e)
    const ran = await next(e)
    // Debounced and never awaited: the model's call is not slowed by the snapshot.
    if (shouldRefresh(e.tool, next.origin.plugin, ran)) {
      if (toToolName(e.tool) === 'manage_items') invalidateLabels()
      void refresh(graphIo($))
    }

    return ran
  })

  on('session.start', async ($, e, next) => {
    const started = await next(e)
    void refresh(graphIo($))

    return started
  })

  // Consumer controls: they write these atoms, and the layer reacts once the write has landed.
  on('state.set', { plugin: PLUGIN, key: 'graphScope' }, async ($, e, next) => {
    const wrote = await next(e)
    if (wrote.value?.isSet) void refreshNow(graphIo($))

    return wrote
  })

  on('state.set', { plugin: PLUGIN, key: 'graphRefreshRequest' }, async ($, e, next) => {
    const wrote = await next(e)
    if (wrote.value?.isSet) void refresh(graphIo($))

    return wrote
  })

  on('state.set', { plugin: PLUGIN, key: 'graphReconnectRequest' }, async ($, e, next) => {
    const wrote = await next(e)
    if (wrote.value?.isSet) {
      invalidateLabels()
      restartLive(graphIo($), await read($, graphSubscribers))
      void refreshNow(graphIo($))
    }

    return wrote
  })

  on('state.set', { plugin: PLUGIN, key: 'graphSubscribers' }, async ($, e, next) => {
    const wrote = await next(e)
    if (wrote.value?.isSet) syncLive(graphIo($), typeof e.value === 'number' ? e.value : 0)

    return wrote
  })
}
