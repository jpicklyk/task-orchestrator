// Status line + AbovePrompt band: in-flight item and gate progress (T4).
// Owned by work item ec1e2f91-1d16-43dc-a9c9-f86aea28a050. Read-only: it draws from T2's snapshot
// atom and makes no TO call and no write beyond its own state.
//
// The validator follows `$` only into functions of the same file, so the atoms below are this
// file's own same-literal declarations of graph-data's keys (an atom is a typed reference to a
// host-held value); only the pure steps and types come from graph-data.
import { atom, read, update } from 'claude-code'
import type { EngineInterface, On } from 'claude-code'

import type { GraphSnapshot } from '../../types'
import { addSubscriber, requestLiveSync } from '../graph-data/index.ts'
import { bandLine, bandModel } from './model.ts'

/** Below this many columns the band hides; the status line still carries the item. */
export const MIN_BAND_COLUMNS = 40

/** The /to-graph pane's id (graph-pane PANE_ID); the band's text opens it. */
const PANE_ID = 'to-graph'

const graphSnapshot = atom({ plugin: 'task-orchestrator-mod', key: 'graphSnapshot' } as const, null as GraphSnapshot | null)
const graphSubscribers = atom({ plugin: 'task-orchestrator-mod', key: 'graphSubscribers' } as const, 0)
const graphPaneOpen = atom({ plugin: 'task-orchestrator-mod', key: 'graphPaneOpen' } as const, false)
const graphScopeMode = atom({ plugin: 'task-orchestrator-mod', key: 'graphScopeMode' } as const, 'auto' as 'auto' | 'pinned')
const bandHidden = atom({ plugin: 'task-orchestrator-mod', key: 'bandHidden' } as const, false)
const bandSubscribed = atom({ plugin: 'task-orchestrator-mod', key: 'bandSubscribed' } as const, false)

const toggle = (hidden: boolean): boolean => !hidden
const hide = (): boolean => true
const yes = (): boolean => true
const auto = (): 'auto' | 'pinned' => 'auto'

/** Set once per module load, so a hot reload (which drops the registered command) registers it again. */
let commandRegistered = false

/**
 * Brings the status line to the current snapshot, subscribes the band once per session (bandSubscribed
 * lives in $.state, so a hot reload does not count twice) and registers /to-band, the way back after
 * Hide. Idempotent; safe to call from any hook.
 */
async function sync($: EngineInterface): Promise<void> {
  $.ui.status(bandModel(await read($, graphSnapshot), true).status)
  if (!(await read($, bandSubscribed))) {
    await update($, bandSubscribed, yes)
    await update($, graphSubscribers, addSubscriber)
    requestLiveSync(await read($, graphSubscribers))
  }
  if (!commandRegistered) {
    commandRegistered = true
    await $.command.register({ name: 'to-band', description: 'Show or hide the Task Orchestrator in-flight band' })
  }
}

/**
 * The band's text pressed: open the work graph pane on the current scope, and count it once as a
 * subscriber, the same steps /to-graph runs (graph-pane index.ts). Run directly, not through a hook on
 * our own state write (a plugin's own `$.state.set` does not reliably reach its own hooks).
 */
async function openPane($: EngineInterface): Promise<void> {
  await $.ui.open({ id: PANE_ID, title: 'TO graph' })
  if (!(await read($, graphPaneOpen))) {
    // The band opens the pane on the current scope without choosing one: follow this session's activity.
    await update($, graphScopeMode, auto)
    await update($, graphPaneOpen, yes)
    await update($, graphSubscribers, addSubscriber)
    requestLiveSync(await read($, graphSubscribers))
  }
}

export function registerBand(on: On): void {
  // Graph-data writes the snapshot atom; this observes the write after it lands. There is no
  // session.start hook here (graph-data owns the one unmatched session.start and the validator refuses
  // a second). A write raised from a timer callback may not reach this hook in every engine, so the
  // prompt and turn hooks below run the same sync from events that always dispatch the whole chain;
  // the status line is then at worst one prompt or turn behind.
  on('state.set', { plugin: 'task-orchestrator-mod', key: 'graphSnapshot' }, async ($, e, next) => {
    const wrote = await next(e)
    const result = wrote as { isSet?: boolean; value?: { isSet?: boolean } }
    if (result.isSet === true || result.value?.isSet === true) await sync($)

    return wrote
  })

  on('prompt.submit', async ($, e, next) => {
    await sync($)

    return next(e)
  })

  on('turn.complete', async ($, e, next) => {
    await sync($)

    return next(e)
  })

  on('command.run', { command: 'to-band' }, async $ => {
    const hidden = await update($, bandHidden, toggle)

    return { text: hidden ? 'In-flight band hidden. Run /to-band to show it again.' : 'In-flight band shown.' }
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    if (e.props.hasSurvey || e.props.bodyColumns < MIN_BAND_COLUMNS) return next(e)
    if (await read($, bandHidden)) return next(e)
    // The smart line while the graph is scoped to an item, else today's in-flight line (model.ts).
    const text = bandLine(await read($, graphSnapshot), e.props.bodyColumns)
    if (text === undefined) return next(e)

    const { Box, Button } = $.ui.resolve(e)

    return h(
      Box,
      null,
      h(Button, { key: 'band-open', label: text, plain: true, onPress: () => openPane($) }),
      h(Button, { key: 'hide', label: 'Hide', onPress: () => update($, bandHidden, hide) }),
    )
  })
}
