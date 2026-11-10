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

import type { Board, BoardRun, CoordinatorState, DirectorState, FocusBoard, FocusState, ItemFacts } from '../types'
import type { CellStyle, FocusItem, Row } from './model.ts'
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
  bandWidth,
  blockerOf,
  closedRunIds,
  coordinatorFromMarker,
  coordinatorFromOpen,
  coordinatorToast,
  enterToast,
  fitRow,
  envelopeData,
  focusBlocked,
  focusFromGet,
  focusValue,
  focusProgress,
  footerLead,
  footerModes,
  isDirectorSkillCall,
  itemFacts,
  line3Cells,
  statusRow,
  titleRow,
  agentRows,
  assignColours,
  cellStyle,
  colourKey,
  listItems,
  markerPath,
  modesOn,
  resolveFocus,
  restData,
  restToolUrl,
  runClosedToast,
  runFromList,
  runIsClosed,
  namedRunIds,
  sameId,
  sessionRuns,
  watchRuns,
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
const watchedRuns = atom({ plugin: 'orchestration-mode', key: 'watchedRuns' } as const, [] as string[])

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
/** A refresh still running after this long is taken as hung (a read that never answers) and no longer blocks the next. */
const REFRESH_STALE_MS = 60_000
/** A forced refresh asked for while one ran: run it once that one ends. */
let refreshAgain = false
/**
 * Item id (lowercase) to the focus item it resolves to: the touched id and every ancestor its walk
 * read, so each id is walked once and a sibling stops at the first shared ancestor.
 */
const resolvedFocus = new Map<string, FocusItem>()
/** Failed `runs_read` `get` reads in a row of the coordinated run; at the cap the mode is cleared. */
/** Touched item id (lowercase) to that item as its walk first read it (line 3's facts until the board reads it). */
const touchedItems = new Map<string, FocusItem>()
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

/**
 * The focus graph: the focus item, its subtree's phase counts (itself included), the blocked work
 * under it, its container (line 1) and the touched item as it stands now (line 3).
 */
async function readFocus($: EngineInterface, current: NonNullable<FocusState>): Promise<FocusBoard> {
  const quiet = (p: Promise<unknown>): Promise<unknown> => p.catch(() => undefined)
  const touchedIsFocus = sameId(current.touched.itemId, current.itemId)
  const [item, overview, blockedData, touchedData] = await Promise.all([
    quiet(directorRead($, 'items_read', { select: 'get', itemId: current.itemId })),
    quiet(directorRead($, 'items_read', { select: 'overview', anchorId: current.itemId, limit: 200 })),
    quiet(directorRead($, 'items_read', { select: 'blocked', ancestorId: current.itemId, limit: 50 })),
    touchedIsFocus ? Promise.resolve(undefined) : quiet(directorRead($, 'items_read', { select: 'get', itemId: current.touched.itemId })),
  ])
  const self = focusFromGet(item)
  const blocked = blockedData === undefined ? undefined : focusBlocked(blockedData)
  // The focus is the container when it has children (an ancestor of the touched item always has);
  // a childless focus below depth 1 (a walk that settled early) hands the line to its parent, unless the
  // parent only groups work (a backlog bucket: the walk stopped below it on purpose); a lone leaf below
  // the root, or below a bucket, is its own container.
  const hasChildren = !touchedIsFocus || listItems(overview).length > 0
  let container: ItemFacts | null = self === null ? null : itemFacts(self)
  if (self !== null && !hasChildren && self.depth >= 2 && self.parentId !== null) {
    const parent = focusFromGet(await quiet(directorRead($, 'items_read', { select: 'get', itemId: self.parentId })))
    if (parent !== null && parent.grouping !== true) container = itemFacts(parent)
  }
  const touchedNow = touchedIsFocus ? self : focusFromGet(touchedData)
  const touched = touchedNow === null ? current.touched : itemFacts(touchedNow)
  const blockedBy = blocked === undefined ? undefined : blockerOf(blocked.blockers, touched.itemId)

  return {
    itemId: current.itemId,
    // A bucket the session touched itself counts its work, not itself (its own role is not progress).
    counts: focusProgress(overview, self?.grouping === true ? '' : self?.role),
    role: self?.role ?? '',
    hasChildren,
    blocked: blocked?.total ?? null,
    container,
    touched: blockedBy === undefined ? touched : { ...touched, blockedBy },
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
 * Reads the board (this session's open and paused runs with their frontiers, every run in director
 * mode; decisions waiting; and, once a TO call named an item, the focus graph) and stores it, which
 * redraws the band. Faults land in `board.error`
 * and keep the last read. Skipped while the band is off or hidden and no run is coordinated.
 */
async function refreshBoard($: EngineInterface, force = false): Promise<void> {
  const now = Date.now()
  if (refreshing && now - lastRefreshAt < REFRESH_STALE_MS) {
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
    const current = focusValue(await read($, focus))
    // Only this session's runs, in every mode: the board of every run is the director's to read, not the band's.
    // A run scoped to the focus is not one of them unless the session acted on it through `runs_write`.
    const mine = sessionRuns(listedAll, { coordinatorRunId: coord?.runId, watched: (await read($, watchedRuns)) ?? [] })
    const runs = await Promise.all(
      mine
        .slice(0, MAX_RUN_ROWS)
        .map(async run => withFrontier(run, await directorRead($, 'runs_read', { select: 'frontier', runId: run.id }))),
    )
    // Line 1 names the first run's container (its scope item, else its root) when no focus names one.
    const lead = runs[0]
    const containerId = lead?.scopeId ?? lead?.rootId
    if (lead !== undefined && containerId !== undefined) {
      const scopeItem = focusFromGet(await directorRead($, 'items_read', { select: 'get', itemId: containerId }).catch(() => undefined))
      runs[0] = { ...lead, container: scopeItem === null ? null : itemFacts(scopeItem) }
    }
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
    const focusBoard = current !== null ? await readFocus($, current) : null
    // Each member an agent works on keeps its colour from read to read (see assignColours).
    const working = runs.flatMap(run => (run.members ?? []).filter(m => m.status === 'working').map(m => colourKey(run.id, m.id)))
    const present = runs.flatMap(run => (run.members ?? []).map(m => colourKey(run.id, m.id)))
    await update($, board, prev => ({
      at: new Date().toISOString(),
      runs,
      focus: focusBoard,
      colours: assignColours(prev?.colours ?? {}, working, present),
    }))
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    await update($, board, prev => ({
      at: new Date().toISOString(),
      runs: prev?.runs ?? [],
      focus: prev?.focus ?? null,
      colours: prev?.colours ?? {},
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

/**
 * Records `itemId` as the touched item (line 3) and moves the focus to its top-most ancestor below
 * the project root and below any item that only groups work (lines 1 and 2).
 */
async function focusOn($: EngineInterface, itemId: string): Promise<void> {
  const key = itemId.toLowerCase()
  let target = resolvedFocus.get(key)
  let touched = touchedItems.get(key)
  if (target === undefined || touched === undefined) {
    // Walked once per id; a sibling's walk stops at the first ancestor an earlier walk resolved.
    const walk = await resolveFocus(
      itemId,
      id => directorRead($, 'items_read', { select: 'get', itemId: id }),
      undefined,
      id => resolvedFocus.get(id.toLowerCase()),
    )
    if (walk === null) return
    target = walk.item
    touched = walk.start
    for (const id of walk.walked) resolvedFocus.set(id.toLowerCase(), walk.item)
    touchedItems.set(key, walk.start)
    touchedItems.set(walk.start.id.toLowerCase(), walk.start)
  }
  const current = focusValue(await read($, focus))
  if (current !== null && sameId(current.itemId, target.id) && sameId(current.touched.itemId, touched.id)) return
  // A touch of the container the session is already in (an overview or context read of the feature
  // while a child is in hand) keeps the child on line 3; only a touch of another item moves it.
  if (current !== null && sameId(current.itemId, target.id) && sameId(touched.id, target.id)) return
  const next = { itemId: target.id, title: target.title, since: new Date().toISOString(), touched: itemFacts(touched) }
  await update($, focus, () => next)
  await refreshBoard($, true)
}

/**
 * Makes the runs a `runs_write` call opened or acted on this session's (`watchedRuns`), whoever in the
 * session made it: the session itself or one of its subagents (a background coordinator, a Workflow, a
 * seat). A run seen for the first time redraws the band.
 */
async function watchNamedRuns($: EngineInterface, tool: string, input: Record<string, unknown>, ran: ToolCallResult): Promise<void> {
  const ids = namedRunIds(tool, input, toolEnvelope(ran))
  if (ids.length === 0) return
  const before = (await read($, watchedRuns)) ?? []
  const fresh = ids.some(id => !before.some(w => sameId(w, id)))
  await update($, watchedRuns, prev => watchRuns(prev ?? [], ids))
  if (fresh) await refreshBoard($, true)
}

/** What one of this session's own TO calls says: a run opened or closed, or the item it touched. */
async function observeCall($: EngineInterface, tool: string, input: Record<string, unknown>, ran: ToolCallResult): Promise<void> {
  await watchNamedRuns($, tool, input, ran)
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
  // A fresh start for this load: a refresh or a cache left by an earlier load holds nothing here.
  refreshing = false
  refreshAgain = false
  lastRefreshAt = 0
  ticker = null
  backdropMode = null
  coordReadFailures = 0
  resolvedFocus.clear()
  touchedItems.clear()
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

  // TO calls on either server. This session's own: a `runs_write` open or close moves coordinator
  // mode, the item a write or an item read touched moves the focus, and a run it names becomes its
  // own. A subagent's call (it carries an agent id) only makes the runs it names this session's (a
  // background coordinator or a Workflow it dispatched); it never moves the mode or the focus.
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
      if (ran.deny !== undefined || ran.isError === true) return ran
      const input = e as unknown as Record<string, unknown>
      // In the background: the model gets its result now, whatever the board reads take (a slow or
      // hung REST server never holds a TO call). The call's result stands whatever the mod made of it.
      const observed = e.agentId !== undefined ? watchNamedRuns($, e.tool, input, ran) : observeCall($, e.tool, input, ran)
      void observed.catch(() => undefined)

      return ran
    },
  )

  // Anything else that moved a marker (a guard write from a subagent-free path, a manual rm), and
  // the board refresh after every turn.
  on('turn.complete', async ($, e, next) => {
    if (e.agentId === undefined) await reconcile($)

    return next(e)
  })

  // The prompt footer's mode slot (where `focus` and `memory paused` go), dedicated to the mode and
  // always drawn: `TO orchestrator` at rest, else the roster modes that are on. Styled
  // like the native `Fable 5.1 Medium` pair beside it (the label in the text colour at the model
  // name's weight, the modes dim), after any native labels. A tree is what the desktop app draws; a
  // rewrite of `modes` reaches only the terminal. Sibling cells in a centred row, not nested text, so
  // the baseline matches the neighbours. Reading the atoms here subscribes the footer, so a mode
  // change redraws it.
  on('ui.render', { component: 'SessionMode' }, async ($, e) => {
    const modes = footerModes(modeNames(await read($, director), await read($, coordinator)))
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

  // The band directly above the prompt, in every mode: 1 the title of the container, the root of the work being
  // done, with the bar on the right (one cell per member, green done, red stuck, amber needing the person, grey
  // finished, hollow not started, ⧄ cancelled, and each member an agent works on in its own colour, blinking); 2 the
  // status: what needs a look first, then what the agents do, and the members by phase in plain words on the right;
  // 3 and on, one row per agent (a marker in its member's colour, blinking, its seat and the item's title), at most
  // three, else the item this session last touched. While anything blinks, one `Client` (band.ts) draws the whole
  // band, so every blink turns on one timer; else plain Text. Every row is cut here to the frame's width (the
  // desktop app wraps a long Text whatever its `wrap` says): the right side stays whole. A short band drops rows from
  // the bottom, never the title. Not drawn with no run and no touched item, while hidden (`/to-band`) or off
  // (userConfig `band`), while a survey holds the band, and on a very narrow body.
  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    if (!bandWanted || (await read($, bandHidden))) return next(e)
    if (e.props.hasSurvey || e.props.bodyColumns < MIN_BAND_COLUMNS) return next(e)
    const current = await read($, board)
    const focused = focusValue(await read($, focus))
    const runs = current?.runs ?? []
    if (runs.length === 0 && focused === null) return next(e)
    const elements = $.ui.resolve(e)
    const { Box, Text } = elements
    // The terminal's cells are the width, so each row is cut to them here. The desktop app draws a proportional
    // font, where a cell count under-uses the frame: there every row is handed over whole and the surface cuts it
    // at the frame's edge (`truncate-end`).
    const desktop = e.surface === 'desktop'
    const width = desktop ? Number.MAX_SAFE_INTEGER : bandWidth(e.props.bodyColumns)

    // The focus graph read for this focus, if the board has caught up with it.
    const graph = focused !== null && current?.focus && sameId(current.focus.itemId, focused.itemId) ? current.focus : null
    const loading = current === null || (runs.length === 0 && graph === null && current.error === undefined)
    const shown: Board = loading ? null : { ...current, focus: graph }
    const colours = current?.colours ?? {}

    // Until the board has read the focus, the title row names it from what the walk saw.
    const fallback: ItemFacts | null =
      focused === null
        ? null
        : sameId(focused.touched.itemId, focused.itemId)
          ? focused.touched
          : { itemId: focused.itemId, title: focused.title, type: '', label: '' }
    // The title names the graph the band counts: the first run's container (its scope item, else its root) when a
    // run is shown, else the focus container. A board saved by an earlier version carries neither: the walk's facts.
    const containerFacts = runs[0]?.container ?? graph?.container ?? fallback

    // Rows 3 and on: the agents, else the item this session last touched.
    const agents = agentRows(shown, colours)
    const touched: (ItemFacts & { blockedBy?: string }) | null =
      focused === null ? null : graph?.touched && sameId(graph.touched.itemId, focused.touched.itemId) ? graph.touched : focused.touched
    const isContainer = touched !== null && containerFacts !== null && sameId(touched.itemId, containerFacts.itemId)
    const itemRow: Row | null = touched === null || isContainer ? null : { key: 'item', left: line3Cells(touched, width), right: [] }

    const ordered = [titleRow(shown, containerFacts, graph, colours), statusRow(shown, containerFacts, graph), ...(agents.length > 0 ? agents : itemRow ? [itemRow] : [])]
      .filter((row): row is Row => row !== null)
      .map(row => fitRow(row, width))
    // The border takes two rows; past that, rows go from the bottom.
    const room = Math.max(1, e.props.maxRows - 2)
    const rows = ordered.slice(0, Math.max(1, Math.min(room, ordered.length)))
    const frame = (children: unknown[]) =>
      Box({ flexDirection: 'column', paddingX: 1, borderStyle: 'round', borderColor: 'warning', children: children as never[] })
    const style = (row: Row) => ({ key: row.key, left: row.left.map(cellStyle), right: row.right.map(cellStyle) })

    // Something blinks: one Client draws the band, on one timer.
    if ('Client' in elements && rows.some(row => [...row.left, ...row.right].some(cell => cell.blink === true))) {
      return frame([elements.Client({ key: 'band', module: './band.ts', props: { rows: rows.map(style), desktop } })])
    }
    const cell = (s: CellStyle) =>
      Text({
        ...(s.c !== undefined ? { color: s.c } : {}),
        ...(s.b === true ? { bold: true } : {}),
        ...(s.d === true ? { dimColor: true } : {}),
        children: s.t,
      })

    return frame(
      rows.map(style).map(row =>
        // The right side (the bar, the counts) never shrinks; the left gives way, so a long title never squeezes the
        // bar (the desktop app wraps the title instead of cutting it, onto a second line under the bar's first).
        Box({
          key: row.key,
          flexDirection: 'row',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          gap: 1,
          overflow: 'hidden',
          children: [
            Box({ flexGrow: 1, flexShrink: 1, minWidth: 0, children: [Text({ wrap: desktop ? 'truncate-end' : 'truncate', children: row.left.map(cell) })] }),
            ...(row.right.length > 0 ? [Box({ flexShrink: 0, children: [Text({ wrap: 'truncate', children: row.right.map(cell) })] })] : []),
          ],
        }),
      ),
    )
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
