// Director-mode indicator: a band above the prompt with the run board, a status line, a footer label,
// a desktop backdrop and toasts, keyed to the task-orchestrator director marker. Owned by work item
// 7380eb6a-7c30-4c4f-9451-2cccaa33d421.
//
// Source of truth is the marker file the plugin's `director-guard` hook writes for the session
// (`<tmp>/task-orchestrator/<session>/director.json`), never this module's own memory of a Skill call,
// so the indicator agrees with the guard after a resume, a reload, or an `off` from any path.
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
import type { EngineInterface, On, PluginOptions, Timer } from 'claude-code'

import type { Board, BoardRun, DirectorState } from '../types'
import {
  BACKDROP_ALT,
  BACKDROP_HEIGHT,
  BACKDROP_WIDTH,
  BAND_TITLE,
  DEFAULT_API_URL,
  DIRECTOR_SERVER,
  EXIT_TOAST,
  FOOTER_PREFIX,
  GLASS,
  MAX_RUN_ROWS,
  MIN_BAND_COLUMNS,
  MIN_REFRESH_GAP_MS,
  MODE_LABEL,
  REFRESH_MS,
  backdropSvg,
  boardProgress,
  decisionCounts,
  enterToast,
  envelopeData,
  footerLead,
  formatTokens,
  isDirectorSkillCall,
  listItems,
  listTotal,
  markerPath,
  modeDetail,
  pausedCell,
  phaseCell,
  progressBar,
  restData,
  restToolUrl,
  runFromList,
  runName,
  runTotal,
  seatsCell,
  tmpDir,
  withFrontier,
} from './model.ts'

const PLUGIN = 'orchestration-mode'
const BACKDROP_ID = 'director'

const director = atom({ plugin: 'orchestration-mode', key: 'director' } as const, null as DirectorState)
const board = atom({ plugin: 'orchestration-mode', key: 'board' } as const, null as Board)

const enter = (): DirectorState => ({ since: new Date().toISOString() })
const leave = (): DirectorState => null

/** Whether the desktop backdrop is wanted (userConfig `backdrop`, default true). Set once per load. */
let backdropWanted = true
/** The REST base for board reads (userConfig `apiUrl`); empty reads over the MCP connection. */
let apiUrl = DEFAULT_API_URL
/** Whether this module instance has set the backdrop; a reload resets it so the backdrop is re-set once. */
let backdropSet = false
/** The refresh timer while on; a reload drops it and the next reconcile starts it again. */
let ticker: Timer | null = null
let lastRefreshAt = 0
let refreshing = false

/** True while this session's director marker exists. Fail-open to false on any fault. */
async function markerExists($: EngineInterface): Promise<boolean> {
  try {
    const env = {
      TMPDIR: await $.env.get('TMPDIR'),
      TEMP: await $.env.get('TEMP'),
      TMP: await $.env.get('TMP'),
    }
    const sessionId = await $.session.id()

    return await $.fs.exists(markerPath(tmpDir(env), sessionId))
  } catch {
    return false
  }
}

async function showBackdrop($: EngineInterface): Promise<void> {
  if (!backdropWanted || backdropSet) return
  backdropSet = true
  try {
    const drawn = await $.ui.backdrop({ id: BACKDROP_ID, glass: GLASS })
  } catch {
    backdropSet = false
  }
}

async function clearBackdrop($: EngineInterface): Promise<void> {
  backdropSet = false
  try {
    await $.ui.backdrop(undefined)
  } catch {
    // nothing stood
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
 * Reads the board (open and paused runs with their frontiers, decisions waiting, blocked work) and
 * stores it, which redraws the band. Faults land in `board.error` and keep the last runs.
 */
async function refreshBoard($: EngineInterface, force = false): Promise<void> {
  const now = Date.now()
  if (refreshing || (!force && now - lastRefreshAt < MIN_REFRESH_GAP_MS)) return
  refreshing = true
  lastRefreshAt = now
  try {
    const [open, paused] = await Promise.all([
      directorRead($, 'runs_read', { select: 'list', status: 'open', limit: 20 }),
      directorRead($, 'runs_read', { select: 'list', status: 'paused', limit: 20 }),
    ])
    const listed = [...listItems(open), ...listItems(paused)]
      .map(runFromList)
      .filter((r): r is BoardRun => r !== null)
      .slice(0, MAX_RUN_ROWS)
    const runs = await Promise.all(
      listed.map(async run => withFrontier(run, await directorRead($, 'runs_read', { select: 'frontier', runId: run.id }))),
    )
    let decisions: { open: number; overdue: number } | null = null
    let blocked: number | null = null
    try {
      decisions = decisionCounts(await directorRead($, 'runs_read', { select: 'decisions', limit: 50 }))
    } catch (err) {
    }
    try {
      blocked = listTotal(await directorRead($, 'items_read', { select: 'blocked' }))
    } catch (err) {
    }
    await update($, board, () => ({ at: new Date().toISOString(), runs, decisions, blocked }))
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    await update($, board, prev => ({
      at: new Date().toISOString(),
      runs: prev?.runs ?? [],
      decisions: prev?.decisions ?? null,
      blocked: prev?.blocked ?? null,
      error: message,
    }))
  } finally {
    refreshing = false
  }
}

function startTicker($: EngineInterface): void {
  if (ticker !== null) return
  ticker = $.clock.every(REFRESH_MS, () => {
    void refreshBoard($)
  })
}

function stopTicker(): void {
  ticker?.cancel()
  ticker = null
}

/**
 * Brings the indicator to the marker: enter (state, board, backdrop, status, toast), leave (clear all),
 * or, while already on, refresh the board and make sure the backdrop and status stand (a reload or
 * resume drops them). Idempotent; safe from any hook.
 */
async function reconcile($: EngineInterface, source: string): Promise<void> {
  const active = await markerExists($)
  const current = await read($, director)
  if (active && current === null) {
    const next = await update($, director, enter)
    await showBackdrop($)
    $.ui.toast(enterToast(next?.since ?? new Date().toISOString()))
    startTicker($)
    await refreshBoard($, true)
  } else if (!active && current !== null) {
    stopTicker()
    await update($, director, leave)
    await update($, board, () => null)
    await clearBackdrop($)
    $.ui.toast(EXIT_TOAST)
  } else if (active) {
    await showBackdrop($)
    startTicker($)
    await refreshBoard($)
  }
}

export function registerOrchestrationMode(on: On, options: PluginOptions): void {
  backdropWanted = options.backdrop !== false
  apiUrl = typeof options.apiUrl === 'string' ? options.apiUrl.trim() : DEFAULT_API_URL

  // A resume or reload: the marker may already exist while $.state is empty or the backdrop is gone.
  on('session.start', async ($, e, next) => {
    await reconcile($, 'session.start')

    return next(e)
  })

  // The director skill itself: after `next`, the guard hook has written or removed the marker.
  on('tool.call', { tool: 'Skill' }, async ($, e, next) => {
    const result = await next(e)
    if (isDirectorSkillCall((e as { skill?: unknown }).skill)) await reconcile($, 'tool.call')

    return result
  })

  // Anything else that moved the marker (a guard write from a subagent-free path, a manual rm), and
  // the board refresh after every turn.
  on('turn.complete', async ($, e, next) => {
    await reconcile($, 'turn.complete')

    return next(e)
  })

  // The prompt footer's mode slot (where `focus` and `memory paused` go): while on, draw the label
  // there as a tree, `Orchestration Mode: director`, after any native labels. A tree is what the
  // desktop app draws; a rewrite of `modes` reaches only the terminal. Reading the atom here
  // subscribes the footer, so the write in reconcile redraws it.
  on('ui.render', { component: 'SessionMode' }, async ($, e, next) => {
    if ((await read($, director)) === null) return next(e)
    const { Text } = $.ui.resolve(e)

    return Text({
      dimColor: true,
      children: [footerLead(e.props.modes), FOOTER_PREFIX, Text({ bold: true, children: MODE_LABEL })],
    })
  })

  // The band directly above the prompt: the mode row, then one row per open or paused run. Hidden
  // while a survey holds the band, and on a very narrow body.
  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    const state = await read($, director)
    if (state === null) return next(e)
    if (e.props.hasSurvey || e.props.bodyColumns < MIN_BAND_COLUMNS) return next(e)
    const current = await read($, board)
    const columns = e.props.bodyColumns
    const { Box, Text } = $.ui.resolve(e)

    // The border takes two rows and the mode row one; the rest go to runs.
    const runRows = Math.max(0, Math.min(MAX_RUN_ROWS, e.props.maxRows - 3))
    const runs = current?.runs.slice(0, runRows) ?? []

    const progress = boardProgress(current)
    const anyAttention = runs.some(run => run.stalled > 0 || run.coordinatorStalled)
    const rows = [
      // The mode row: title, the board-wide bar, then the totals.
      Box({
        key: 'mode',
        flexDirection: 'row',
        gap: 2,
        children: [
          Box({ flexShrink: 0, children: [Text({ color: 'warning', bold: true, children: BAND_TITLE })] }),
          ...(progress.total > 0
            ? [
                Box({
                  flexShrink: 0,
                  gap: 1,
                  children: [
                    Text({ color: anyAttention ? 'error' : 'success', children: progressBar(progress.done, progress.total) }),
                    Text({ dimColor: true, tabular: true, children: `${progress.done}/${progress.total}` }),
                  ],
                }),
              ]
            : []),
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

    return Box({
      flexDirection: 'column',
      paddingX: 1,
      borderStyle: 'round',
      borderColor: 'warning',
      children: rows,
    })
  })

  // The desktop backdrop this plugin set: an amber gradient with a faint DIRECTOR watermark.
  on('ui.render', { component: 'Backdrop' }, ($, e, next) => {
    if (e.props.plugin !== PLUGIN || e.props.id !== BACKDROP_ID) return next(e)
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
