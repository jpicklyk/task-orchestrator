// Orchestration-mode mod: the band above the prompt shows the progression of the graph this session
// drives (open runs, else the focus item), in every mode; the footer names the TO modes a user sees
// in a session of their own (director, coordinator); a desktop backdrop per mode, and toasts.
// Work items 7380eb6a-7c30-4c4f-9451-2cccaa33d421 and d8999287-71c9-4b30-8b5e-cde721cdb283.
//
// Director mode comes from the marker file the plugin's `director-guard` hook writes for the session
// (`<tmp>/task-orchestrator/<session>/director.json`), never from this module's own memory of a Skill
// call, so the indicator agrees with the guard after a resume, a reload, or an `off` from any path.
// Coordinator mode comes from this session's own `runs_write` `open` (its result carries the
// coordinator token, which is never kept) until that run closes, or from a `coordinator.json` marker
// beside the director one.
//
// The board is read on the director profile (no note-body read exists there), by the mod and not by
// the model, so it costs no context and cannot breach the altitude rule. By default it goes over the
// server's REST ops route (`apiUrl`, `$.http.fetch`): a timer-originated `$.mcp.call` has no model
// request behind it, which the desktop's auto permission mode cannot evaluate. An empty `apiUrl`
// reads over the director MCP connection instead (stdio servers, which have no REST API).
//
// Validator rules (see ../README.md): matchers are literals in this file, `$` never leaves it,
// and the atoms are declared here beside their reads and writes.
import { atom, read, update } from 'claude-code'
import type { EngineInterface, On, PluginOptions, Timer, ToolCallResult } from 'claude-code'

import type { Board, BoardRun, CoordinatorState, DirectorState, FocusBoard, FocusState } from '../types'
import type { FocusItem } from './model.ts'
import {
  BACKDROP_ALT,
  BACKDROP_HEIGHT,
  BACKDROP_WIDTH,
  BAND_HIDDEN_TOAST,
  BAND_SHOWN_TOAST,
  DEFAULT_API_URL,
  DIRECTOR_SERVER,
  EXIT_TOAST,
  FOOTER_PREFIX,
  MAX_RUN_ROWS,
  MIN_BAND_COLUMNS,
  MIN_REFRESH_GAP_MS,
  REFRESH_MS,
  backdropSvg,
  boardProgress,
  closedRunIds,
  coordinatorFromMarker,
  coordinatorFromOpen,
  coordinatorToast,
  decisionCounts,
  enterToast,
  envelopeData,
  focusBlocked,
  focusDetail,
  focusFromGet,
  focusProgress,
  focusRowCell,
  footerLead,
  footerModes,
  formatTokens,
  graphTitle,
  isDirectorSkillCall,
  listItems,
  listTotal,
  markerPath,
  modeDetail,
  modesOn,
  pausedCell,
  phaseCell,
  progressBar,
  resolveFocus,
  restData,
  restToolUrl,
  runClosedToast,
  runFromList,
  runIsClosed,
  runName,
  runTotal,
  sameId,
  seatsCell,
  tmpDir,
  toolBase,
  toolEnvelope,
  touchedItemId,
  withFrontier,
} from './model.ts'

const PLUGIN = 'orchestration-mode'

const director = atom({ plugin: 'orchestration-mode', key: 'director' } as const, null as DirectorState)
const coordinator = atom({ plugin: 'orchestration-mode', key: 'coordinator' } as const, null as CoordinatorState)
const focus = atom({ plugin: 'orchestration-mode', key: 'focus' } as const, null as FocusState)
const bandHidden = atom({ plugin: 'orchestration-mode', key: 'bandHidden' } as const, false)
const board = atom({ plugin: 'orchestration-mode', key: 'board' } as const, null as Board)

const enter = (): DirectorState => ({ since: new Date().toISOString() })
const leave = (): DirectorState => null

/** Whether the desktop backdrop is wanted (userConfig `backdrop`, default true). Set once per load. */
let backdropWanted = true
/** Whether the band is drawn at all (userConfig `band`, default true); `/to-band` hides it per session. */
let bandWanted = true
/** The REST base for board reads (userConfig `apiUrl`); empty reads over the MCP connection. */
let apiUrl = DEFAULT_API_URL
/** The roster mode whose backdrop this module instance set; a reload resets it so it is set again once. */
let backdropMode: string | null = null
/** The refresh timer; a reload drops it and the next reconcile starts it again. */
let ticker: Timer | null = null
let lastRefreshAt = 0
let refreshing = false
/** A forced refresh asked for while one ran: run it once that one ends. */
let refreshAgain = false
/**
 * Item id (lowercase) to the focus item it resolves to: the touched id and every ancestor its walk
 * read, so each id is walked once and a sibling stops at the first shared ancestor.
 */
const resolvedFocus = new Map<string, FocusItem>()
/** Failed `runs_read` `get` reads in a row of the coordinated run; at the cap the mode is cleared. */
let coordReadFailures = 0
const MAX_COORD_READ_FAILURES = 3

/** The roster mode names that are on, from the two mode atoms' values. */
const modeNames = (dir: DirectorState, coord: CoordinatorState): string[] => [
  ...(dir !== null ? ['director'] : []),
  ...(coord !== null ? ['coordinator'] : []),
]

/** This session's marker `file` path. Throws when the engine cannot say where. */
async function sessionMarker($: EngineInterface, file: string): Promise<string> {
  const env = {
    TMPDIR: await $.env.get('TMPDIR'),
    TEMP: await $.env.get('TEMP'),
    TMP: await $.env.get('TMP'),
  }
  const sessionId = await $.session.id()

  return markerPath(tmpDir(env), sessionId, file)
}

/** True while this session's director marker exists. Fail-open to false on any fault. */
async function markerExists($: EngineInterface, file: string): Promise<boolean> {
  try {
    return await $.fs.exists(await sessionMarker($, file))
  } catch {
    return false
  }
}

/** A session marker's JSON (`{}` when it is not an object), or null when it is absent or unreadable. */
async function readMarker($: EngineInterface, file: string): Promise<unknown> {
  try {
    const path = await sessionMarker($, file)
    if (!(await $.fs.exists(path))) return null
    try {
      return JSON.parse(await $.fs.read(path))
    } catch {
      return {}
    }
  } catch {
    return null
  }
}

async function clearBackdrop($: EngineInterface): Promise<void> {
  backdropMode = null
  try {
    await $.ui.backdrop(undefined)
  } catch {
    // nothing stood
  }
}

/**
 * Sets the backdrop of the first roster mode that is on, or clears it when none is. `turnedOff`
 * clears it even when this module instance did not set it (a reload, then a mode off).
 */
async function syncBackdrop($: EngineInterface, turnedOff: boolean): Promise<void> {
  const first = modesOn(modeNames(await read($, director), await read($, coordinator)))[0]
  if (first === undefined) {
    if (backdropMode !== null || turnedOff) await clearBackdrop($)
    return
  }
  if (!backdropWanted || backdropMode === first.name) return
  backdropMode = first.name
  try {
    await $.ui.backdrop({ id: first.backdropId, glass: first.glass })
  } catch {
    backdropMode = null
  }
}

/** One director-profile read, unwrapped to its `data`: REST when `apiUrl` is set, else the MCP connection. */
async function directorRead($: EngineInterface, tool: string, args: Record<string, unknown>): Promise<unknown> {
  if (apiUrl === '') return envelopeData(await $.mcp.call(DIRECTOR_SERVER, tool, args))
  const response = await $.http.fetch(restToolUrl(apiUrl, tool), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(args),
  })

  return restData(response.status, response.text)
}

/** The focus graph: the item itself, its subtree's phase counts and the blocked work under it. */
async function readFocus($: EngineInterface, current: NonNullable<FocusState>): Promise<FocusBoard> {
  const quiet = (p: Promise<unknown>): Promise<unknown> => p.catch(() => undefined)
  const [item, overview, blockedData] = await Promise.all([
    quiet(directorRead($, 'items_read', { select: 'get', itemId: current.itemId })),
    quiet(directorRead($, 'items_read', { select: 'overview', anchorId: current.itemId, limit: 200 })),
    quiet(directorRead($, 'items_read', { select: 'blocked', ancestorId: current.itemId, limit: 20 })),
  ])
  const self = focusFromGet(item)
  const blocked = blockedData === undefined ? undefined : focusBlocked(blockedData)

  return {
    itemId: current.itemId,
    title: self?.title || current.title,
    label: self?.label ?? '',
    counts: focusProgress(overview, self?.role),
    blocked: blocked?.total ?? null,
    firstBlocked: blocked?.first,
  }
}

/** Leaves coordinator mode: state, toast, backdrop. */
async function clearCoordinator($: EngineInterface, label: string): Promise<void> {
  coordReadFailures = 0
  await update($, coordinator, () => null)
  $.ui.toast(runClosedToast(label))
  await syncBackdrop($, true)
}

/**
 * Reads the board (open and paused runs with their frontiers, decisions waiting, blocked work, and,
 * with no run, the focus graph) and stores it, which redraws the band. Faults land in `board.error`
 * and keep the last read. Skipped while the band is off or hidden and no run is coordinated.
 */
async function refreshBoard($: EngineInterface, force = false): Promise<void> {
  const now = Date.now()
  if (refreshing) {
    if (force) refreshAgain = true
    return
  }
  if (!force && now - lastRefreshAt < MIN_REFRESH_GAP_MS) return
  refreshing = true
  lastRefreshAt = now
  try {
    const coord = await read($, coordinator)
    const shown = bandWanted && !(await read($, bandHidden))
    if (!shown && coord === null) return
    const [open, paused] = await Promise.all([
      directorRead($, 'runs_read', { select: 'list', status: 'open', limit: 20 }),
      directorRead($, 'runs_read', { select: 'list', status: 'paused', limit: 20 }),
    ])
    const listedAll = [...listItems(open), ...listItems(paused)]
      .map(runFromList)
      .filter((r): r is BoardRun => r !== null)
    const runs = await Promise.all(
      listedAll
        .slice(0, MAX_RUN_ROWS)
        .map(async run => withFrontier(run, await directorRead($, 'runs_read', { select: 'frontier', runId: run.id }))),
    )
    // The coordinated run, gone from the open and paused lists: clear the mode once it reads closed,
    // once the server says it does not exist, or after MAX_COORD_READ_FAILURES failed reads in a row.
    if (coord !== null && coord.source === 'tool' && !listedAll.some(run => sameId(run.id, coord.runId))) {
      try {
        const closed = runIsClosed(await directorRead($, 'runs_read', { select: 'get', runId: coord.runId }))
        coordReadFailures = 0
        if (closed) await clearCoordinator($, coord.label)
      } catch (err) {
        const notFound = err instanceof Error && err.message.startsWith('not_found')
        coordReadFailures += 1
        if (notFound || coordReadFailures >= MAX_COORD_READ_FAILURES) await clearCoordinator($, coord.label)
      }
    } else {
      coordReadFailures = 0
    }
    let decisions: { open: number; overdue: number } | null = null
    let blocked: number | null = null
    try {
      decisions = decisionCounts(await directorRead($, 'runs_read', { select: 'decisions', limit: 50 }))
    } catch {
      // decisions stay unknown
    }
    try {
      blocked = listTotal(await directorRead($, 'items_read', { select: 'blocked' }))
    } catch {
      // blocked stays unknown
    }
    const current = await read($, focus)
    const focusBoard = runs.length === 0 && current !== null ? await readFocus($, current) : null
    await update($, board, () => ({ at: new Date().toISOString(), runs, decisions, blocked, focus: focusBoard }))
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    await update($, board, prev => ({
      at: new Date().toISOString(),
      runs: prev?.runs ?? [],
      decisions: prev?.decisions ?? null,
      blocked: prev?.blocked ?? null,
      focus: prev?.focus ?? null,
      error: message,
    }))
  } finally {
    refreshing = false
    if (refreshAgain) {
      refreshAgain = false
      void refreshBoard($, true)
    }
  }
}

function startTicker($: EngineInterface): void {
  if (ticker !== null) return
  try {
    ticker = $.clock.every(REFRESH_MS, () => {
      void refreshBoard($)
    })
  } catch {
    // no timer: the board still refreshes at turn end and on a focus or mode change
  }
}

/**
 * Brings the modes to the markers: director on or off from `director.json`, and a marker-sourced
 * coordinator from `coordinator.json`; then the backdrop, the ticker and a board refresh (a reload or
 * resume drops the backdrop and the ticker). Idempotent; safe from any hook.
 */
async function reconcile($: EngineInterface): Promise<void> {
  let turnedOn = false
  let turnedOff = false
  const active = await markerExists($, 'director.json')
  const current = await read($, director)
  if (active && current === null) {
    const next = await update($, director, enter)
    $.ui.toast(enterToast(next?.since ?? new Date().toISOString()))
    turnedOn = true
  } else if (!active && current !== null) {
    await update($, director, leave)
    $.ui.toast(EXIT_TOAST)
    turnedOff = true
  }
  const marker = await readMarker($, 'coordinator.json')
  const coord = await read($, coordinator)
  if (marker !== null && coord === null) {
    const next = coordinatorFromMarker(marker, new Date().toISOString())
    await update($, coordinator, () => next)
    $.ui.toast(coordinatorToast(next.label))
    turnedOn = true
  } else if (marker === null && coord !== null && coord.source === 'marker') {
    await update($, coordinator, () => null)
    $.ui.toast(runClosedToast(coord.label))
    turnedOff = true
  }
  await syncBackdrop($, turnedOff)
  startTicker($)
  await refreshBoard($, turnedOn)
}

/** Moves the focus to the top-most item below the project root of `itemId`, walked once per id. */
async function focusOn($: EngineInterface, itemId: string): Promise<void> {
  let target = resolvedFocus.get(itemId.toLowerCase())
  if (target === undefined) {
    const walk = await resolveFocus(
      itemId,
      id => directorRead($, 'items_read', { select: 'get', itemId: id }),
      undefined,
      id => resolvedFocus.get(id.toLowerCase()),
    )
    if (walk === null) return
    target = walk.item
    for (const id of walk.walked) resolvedFocus.set(id.toLowerCase(), walk.item)
  }
  const current = await read($, focus)
  if (current !== null && sameId(current.itemId, target.id)) return
  const next = { itemId: target.id, title: target.title, since: new Date().toISOString() }
  await update($, focus, () => next)
  await refreshBoard($, true)
}

/** What one of this session's own TO calls says: a run opened or closed, or the item it touched. */
async function observeCall($: EngineInterface, tool: string, input: Record<string, unknown>, ran: ToolCallResult): Promise<void> {
  if (toolBase(tool) === 'runs_write') {
    const envelope = toolEnvelope(ran)
    const opened = coordinatorFromOpen(input, envelope, new Date().toISOString())
    if (opened !== null) {
      coordReadFailures = 0
      await update($, coordinator, () => opened)
      $.ui.toast(coordinatorToast(opened.label))
      await syncBackdrop($, false)
      await refreshBoard($, true)
      return
    }
    // Only a coordinator this session's own open made: a marker-sourced one follows its marker,
    // which reconcile would read back on at turn end.
    const coord = await read($, coordinator)
    if (coord !== null && coord.source === 'tool' && closedRunIds(input, envelope).some(id => sameId(id, coord.runId))) {
      await clearCoordinator($, coord.label)
      await refreshBoard($, true)
    }
    return
  }
  const itemId = touchedItemId(tool, input)
  if (itemId !== undefined) await focusOn($, itemId)
}

export function registerOrchestrationMode(on: On, options: PluginOptions): void {
  backdropWanted = options.backdrop !== false
  bandWanted = options.band !== false
  apiUrl = typeof options.apiUrl === 'string' ? options.apiUrl.trim() : DEFAULT_API_URL

  // A resume or reload: a marker may already exist while $.state is empty or the backdrop is gone.
  // A status line pinned by an earlier version of this plugin outlives a reload (the engine keeps it
  // until the plugin clears it), so clear it: the footer label is drawn in the mode slot instead.
  on('session.start', async ($, e, next) => {
    $.ui.status(undefined)
    try {
      await $.command.register({ name: 'to-band', description: 'Show or hide the Task Orchestrator band above the prompt' })
    } catch {
      // no command this session; the band still follows userConfig `band`
    }
    await reconcile($)

    return next(e)
  })

  // `/to-band`: hide or show the band for this session. The choice lives in this session's $.state
  // only, so it survives a reload and a resume; userConfig `band` is the default for every session,
  // and a fresh session starts from it. The footer label is not affected.
  on('command.run', { command: 'to-band' }, async ($, e) => {
    if (!bandWanted) return { text: 'The band is off in the plugin settings (band: false).' }
    const hidden = await update($, bandHidden, prev => !prev)
    const text = hidden ? BAND_HIDDEN_TOAST : BAND_SHOWN_TOAST
    $.ui.toast(text)
    if (!hidden) await refreshBoard($, true)

    return { text }
  })

  // The director skill itself: after `next`, the guard hook has written or removed the marker.
  on('tool.call', { tool: 'Skill' }, async ($, e, next) => {
    const result = await next(e)
    if (isDirectorSkillCall((e as { skill?: unknown }).skill)) await reconcile($)

    return result
  })

  // This session's own TO calls on either server: a `runs_write` open or close moves coordinator
  // mode, and the item a write or an item read touched moves the focus. A subagent's call (it
  // carries an agent id) is ignored, so a seat's calls never move the session that dispatched it.
  on(
    'tool.call',
    {
      tool: [
        'mcp__mcp-task-orchestrator__items_write',
        'mcp__mcp-task-orchestrator__advance',
        'mcp__mcp-task-orchestrator__notes_write',
        'mcp__mcp-task-orchestrator__deps_write',
        'mcp__mcp-task-orchestrator__items_read',
        'mcp__mcp-task-orchestrator__runs_write',
        'mcp__mcp-task-orchestrator-director__items_read',
        'mcp__mcp-task-orchestrator-director__runs_write',
      ],
    },
    async ($, e, next) => {
      const ran = await next(e)
      if (e.agentId !== undefined || ran.deny !== undefined || ran.isError === true) return ran
      // In the background: the model gets its result now, whatever the board reads take (a slow or
      // hung REST server never holds a TO call). The call's result stands whatever the mod made of it.
      void observeCall($, e.tool, e as unknown as Record<string, unknown>, ran).catch(() => undefined)

      return ran
    },
  )

  // Anything else that moved a marker (a guard write from a subagent-free path, a manual rm), and
  // the board refresh after every turn.
  on('turn.complete', async ($, e, next) => {
    if (e.agentId === undefined) await reconcile($)

    return next(e)
  })

  // The prompt footer's mode slot (where `focus` and `memory paused` go): while a roster mode is on,
  // draw the label there as a tree, styled like the native `Fable 5.1 Medium` pair beside it (the
  // label in the text colour at the model name's weight, the modes dim), after any native labels. A
  // tree is what the desktop app draws; a rewrite of `modes` reaches only the terminal. Sibling cells
  // in a centred row, not nested text, so the baseline matches the neighbours. Reading the atoms
  // here subscribes the footer, so a mode change redraws it.
  on('ui.render', { component: 'SessionMode' }, async ($, e, next) => {
    const modes = footerModes(modeNames(await read($, director), await read($, coordinator)))
    if (modes === '') return next(e)
    const { Box, Text } = $.ui.resolve(e)
    const lead = footerLead(e.props.modes)

    return Box({
      flexDirection: 'row',
      alignItems: 'center',
      gap: 1,
      children: [
        ...(lead === '' ? [] : [Text({ dimColor: true, children: lead.trimEnd() })]),
        Text({ color: 'text', weight: 'semibold', children: FOOTER_PREFIX }),
        Text({ color: 'subtle', children: modes }),
      ],
    })
  })

  // The band directly above the prompt, in every mode: the graph's name, its bar and totals, then
  // one row per open or paused run, or, with no run, one row for the focus item. Not drawn with no
  // run and no focus, while hidden (`/to-band`) or off (userConfig `band`), while a survey holds the
  // band, and on a very narrow body.
  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    if (!bandWanted || (await read($, bandHidden))) return next(e)
    if (e.props.hasSurvey || e.props.bodyColumns < MIN_BAND_COLUMNS) return next(e)
    const current = await read($, board)
    const focused = await read($, focus)
    const allRuns = current?.runs ?? []
    if (allRuns.length === 0 && focused === null) return next(e)
    const columns = e.props.bodyColumns
    const { Box, Text } = $.ui.resolve(e)

    const titleCell = (title: string) =>
      Box({ key: 'title', flexShrink: 0, children: [Text({ color: 'warning', bold: true, children: title })] })
    const barCell = (done: number, total: number, attention: boolean) =>
      total > 0
        ? [
            Box({
              key: 'bar',
              flexShrink: 0,
              gap: 1,
              children: [
                Text({ color: attention ? 'error' : 'success', children: progressBar(done, total) }),
                Text({ dimColor: true, tabular: true, children: `${done}/${total}` }),
              ],
            }),
          ]
        : []
    const frame = (rows: ReturnType<typeof Box>[]) =>
      Box({ flexDirection: 'column', paddingX: 1, borderStyle: 'round', borderColor: 'warning', children: rows })

    if (allRuns.length === 0 && focused !== null) {
      // The focus view: the focus item's graph, read while no run is open.
      const graph = current?.focus && sameId(current.focus.itemId, focused.itemId) ? current.focus : null
      const total = graph === null ? 0 : runTotal(graph.counts)
      const rows = [
        Box({
          key: 'mode',
          flexDirection: 'row',
          gap: 2,
          children: [
            titleCell(graphTitle([], graph?.title || focused.title)),
            ...barCell(graph?.counts.terminal ?? 0, total, false),
            Text({ dimColor: true, wrap: 'truncate', children: focusDetail(current, graph) }),
          ],
        }),
        ...(graph !== null && e.props.maxRows > 3
          ? [
              Box({
                key: 'focus',
                flexDirection: 'row',
                gap: 2,
                children: [
                  Text({
                    color: (graph.blocked ?? 0) > 0 ? 'warning' : undefined,
                    dimColor: (graph.blocked ?? 0) === 0,
                    wrap: 'truncate',
                    children: focusRowCell(graph),
                  }),
                ],
              }),
            ]
          : []),
      ]

      return frame(rows)
    }

    // The runs view. The border takes two rows and the mode row one; the rest go to runs.
    const runRows = Math.max(0, Math.min(MAX_RUN_ROWS, e.props.maxRows - 3))
    const runs = allRuns.slice(0, runRows)
    const progress = boardProgress(current)
    const anyAttention = runs.some(run => run.stalled > 0 || run.coordinatorStalled)
    const rows = [
      // The mode row: the graph's name, the board-wide bar, then the totals.
      Box({
        key: 'mode',
        flexDirection: 'row',
        gap: 2,
        children: [
          titleCell(graphTitle(allRuns, undefined)),
          ...barCell(progress.done, progress.total, anyAttention),
          Text({ dimColor: true, wrap: 'truncate', children: modeDetail(current) }),
        ],
      }),
      // One row per run: name, the phase pipeline, the seats, then the chip that needs a look.
      ...runs.map(run => {
        const total = runTotal(run.counts)
        const attention = run.stalled > 0 || run.coordinatorStalled
        const chip = run.status === 'paused' ? pausedCell(run) : run.coordinatorStalled ? 'COORD STALLED' : undefined

        return Box({
          key: run.id,
          flexDirection: 'row',
          gap: 2,
          children: [
            Box({
              flexShrink: 0,
              gap: 1,
              children: [
                Text({ dimColor: true, tabular: true, children: `${run.counts.terminal}/${total}` }),
                Text({ bold: true, children: runName(run) }),
              ],
            }),
            Box({ flexShrink: 0, children: [Text({ dimColor: true, children: phaseCell(run.counts) })] }),
            Text({ color: attention ? 'error' : undefined, dimColor: !attention, wrap: 'truncate', children: seatsCell(run) }),
            ...(chip !== undefined
              ? [Box({ flexShrink: 0, children: [Text({ color: run.status === 'paused' ? 'warning' : 'error', bold: true, children: chip })] })]
              : []),
            ...(columns >= 120 ? [Box({ flexShrink: 0, children: [Text({ dimColor: true, children: `${formatTokens(run.tokens)} tok` })] })] : []),
          ],
        })
      }),
    ]

    return frame(rows)
  })

  // The desktop backdrop this plugin set for director mode: an amber gradient with a faint DIRECTOR
  // watermark. The coordinator backdrop is its glass alone.
  on('ui.render', { component: 'Backdrop' }, ($, e, next) => {
    if (e.props.plugin !== PLUGIN || e.props.id !== 'director') return next(e)
    if (e.surface !== 'desktop') return next(e)
    const { Svg } = $.ui.resolve(e)

    return Svg({
      source: backdropSvg(BACKDROP_WIDTH, BACKDROP_HEIGHT),
      alt: BACKDROP_ALT,
      width: BACKDROP_WIDTH,
      height: BACKDROP_HEIGHT,
    })
  })
}
