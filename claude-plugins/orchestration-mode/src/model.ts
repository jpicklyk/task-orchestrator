// Pure helpers for the director indicator. No `$`, no atoms: indicator.ts feeds these values and
// draws what they return, so they are unit-testable with the plugin test kit alone.

import type { Board, BoardRun } from '../types'

/** The footer label added while director mode is on. */
export const LABEL = 'director'

/** The skill whose Skill tool call enters (or, with args `off`, leaves) director mode. */
export const DIRECTOR_SKILL = 'task-orchestrator:director'

/** Same rule as the task-orchestrator hooks' `sanitizeKey` (hooks/lib/markers.mjs). */
export function sanitizeKey(value: string): string {
  return value.replace(/[^a-zA-Z0-9-]/g, '_')
}

/**
 * The temp directory as Node's `os.tmpdir()` picks it: `TMPDIR`, then `TEMP`, then `TMP`, else
 * `/tmp`; a trailing separator dropped. Windows sets TEMP, POSIX TMPDIR, so one order serves both.
 */
export function tmpDir(env: { TMPDIR?: string; TEMP?: string; TMP?: string }): string {
  const raw = env.TMPDIR || env.TEMP || env.TMP || '/tmp'

  return raw.length > 1 ? raw.replace(/[\\/]+$/, '') : raw
}

/**
 * Where the task-orchestrator `director-guard` hook keeps this session's marker:
 * `<tmp>/task-orchestrator/<session>/director.json` (hooks/lib/markers.mjs `sessionMarkerPath`).
 * The separator follows the temp dir, so a Windows path stays a Windows path.
 */
export function markerPath(tmp: string, sessionId: string): string {
  const sep = tmp.includes('\\') ? '\\' : '/'

  return [tmp, 'task-orchestrator', sanitizeKey(sessionId || 'unknown'), 'director.json'].join(sep)
}

/** True for a Skill tool call of the director skill, whatever its args. */
export function isDirectorSkillCall(skill: unknown): boolean {
  return typeof skill === 'string' && skill.trim() === DIRECTOR_SKILL
}

/** The footer label's fixed part; the mode's name follows it in bold. */
export const FOOTER_PREFIX = 'Orchestration Mode: '
/** The native labels that precede ours in the footer slot, joined as the engine joins them. */
export function footerLead(modes: readonly string[]): string {
  const others = modes.filter(mode => mode !== LABEL)

  return others.length > 0 ? `${others.join(' & ')} & ` : ''
}

/** `14:02` in the local clock for an ISO instant; undefined for anything else. */
export function clockTime(since: string): string | undefined {
  const at = new Date(since)
  if (Number.isNaN(at.getTime())) return undefined
  const hh = String(at.getHours()).padStart(2, '0')
  const mm = String(at.getMinutes()).padStart(2, '0')

  return `${hh}:${mm}`
}

/** `Director mode on since 14:02` for the toast; the time in the local clock, no date. */
export function enterToast(since: string): string {
  const at = clockTime(since)

  return at === undefined ? 'Director mode on' : `Director mode on since ${at}`
}

export const EXIT_TOAST = 'Director mode off'

/** The mode's name, drawn in bold after FOOTER_PREFIX in the prompt footer's mode slot while on. */
export const MODE_LABEL = 'director'

/** The backdrop's glass: a warm amber tint so the window reads as "director" at a glance. */
export const GLASS = { blur: 14, tint: '#7c4a03', opacity: 0.42 } as const

/**
 * The backdrop drawing: an amber gradient with a faint repeated DIRECTOR watermark. Sized in
 * pixels because a percentage falls back to a small default; the surface scales it to the window.
 */
export const BACKDROP_WIDTH = 1920
export const BACKDROP_HEIGHT = 1080

export function backdropSvg(width = BACKDROP_WIDTH, height = BACKDROP_HEIGHT): string {
  const rows: string[] = []
  const step = 140
  for (let y = step / 2; y < height + step; y += step) {
    const offset = Math.round(y / step) % 2 === 0 ? 0 : 260
    for (let x = -260 + offset; x < width + 260; x += 520) {
      rows.push(
        `<text x="${x}" y="${y}" font-family="ui-monospace, Consolas, monospace" font-size="56" font-weight="700" letter-spacing="14" fill="#fbbf24" fill-opacity="0.07" transform="rotate(-12 ${x} ${y})">DIRECTOR</text>`,
      )
    }
  }

  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" preserveAspectRatio="xMidYMid slice">` +
    `<defs><radialGradient id="g" cx="50%" cy="0%" r="110%"><stop offset="0" stop-color="#f59e0b" stop-opacity="0.55"/><stop offset="0.55" stop-color="#7c2d12" stop-opacity="0.75"/><stop offset="1" stop-color="#1c0a00" stop-opacity="1"/></radialGradient></defs>` +
    `<rect width="${width}" height="${height}" fill="url(#g)"/>` +
    rows.join('') +
    `<rect x="0" y="0" width="${width}" height="6" fill="#f59e0b"/>` +
    `</svg>`
  )
}

export const BACKDROP_ALT = 'Director mode: this session runs the board and delegates every read or edit'

// ---------------------------------------------------------------------------------------------
// The band: the mode row and one row per open or paused run.
// ---------------------------------------------------------------------------------------------

export const BAND_TITLE = 'DIRECTOR MODE'
/** Below this body width the band is not drawn. */
export const MIN_BAND_COLUMNS = 40
/** At most this many run rows in the band. */
export const MAX_RUN_ROWS = 3

/** The director MCP entry `/task-orchestrator:init` registers, as `/mcp` lists it. */
export const DIRECTOR_SERVER = 'mcp-task-orchestrator-director'
/** The REST base the board reads through by default (userConfig `apiUrl`; empty = the MCP connection). */
export const DEFAULT_API_URL = 'http://localhost:3003'
/** `POST <apiUrl>/api/v1/ops/director/<tool>`, the REST twin of a director-profile tool call. */
export const restToolUrl = (apiUrl: string, tool: string): string =>
  `${apiUrl.endsWith('/') ? apiUrl.slice(0, -1) : apiUrl}/api/v1/ops/director/${tool}`
/** Refresh period while on, and the shortest gap between two refreshes. */
export const REFRESH_MS = 30_000
export const MIN_REFRESH_GAP_MS = 5_000

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const num = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0)
const str = (v: unknown): string => (typeof v === 'string' ? v : '')

/** The `data` of a REST ops response body (the same `{ok, data | error}` envelope). Throws like `envelopeData`. */
export function restData(status: number, text: string): unknown {
  let payload: unknown
  try {
    payload = JSON.parse(text)
  } catch {
    throw new Error(`http ${status}: no JSON body`)
  }

  return envelopeData({ structuredContent: payload })
}

/**
 * The `data` of a 4.0 tool result envelope, read from `structuredContent` first, then the JSON of the
 * first text block. Throws `<code>: <message>` on an error envelope and on a result with no envelope.
 */
export function envelopeData(result: { structuredContent?: unknown; content?: unknown; isError?: boolean }): unknown {
  let payload: unknown = result.structuredContent
  if (!isObj(payload) && Array.isArray(result.content)) {
    for (const block of result.content) {
      if (isObj(block) && typeof block.text === 'string') {
        try {
          payload = JSON.parse(block.text)
        } catch {
          payload = undefined
        }
        break
      }
    }
  }
  if (!isObj(payload) || typeof payload.ok !== 'boolean') throw new Error('no result envelope')
  if (!payload.ok) {
    const error = isObj(payload.error) ? payload.error : {}

    throw new Error(`${str(error.code) || 'error'}: ${str(error.message)}`)
  }

  return payload.data
}

/** The items of a list result's `data`. */
export function listItems(data: unknown): Obj[] {
  if (!isObj(data) || !Array.isArray(data.items)) return []

  return data.items.filter(isObj)
}

/** `page.total` of a list result, else the items' count. */
export function listTotal(data: unknown): number {
  if (isObj(data) && isObj(data.page) && typeof data.page.total === 'number') return data.page.total

  return listItems(data).length
}

/** A run row from one `runs_read` `list` item; null for a run that is neither open nor paused. */
export function runFromList(item: Obj): BoardRun | null {
  const status = item.status
  if (status !== 'open' && status !== 'paused') return null
  const usage = isObj(item.usage) ? item.usage : {}
  const tokens = num(usage.reportedTokens) > 0 ? num(usage.reportedTokens) : num(usage.observedTokens)

  return {
    id: str(item.runId),
    label: str(item.label),
    title: str(item.title),
    status,
    pauseReason: typeof item.pauseReason === 'string' ? item.pauseReason : undefined,
    counts: { queue: 0, work: 0, review: 0, blocked: 0, terminal: 0 },
    ready: 0,
    inFlight: 0,
    waiting: 0,
    stalled: num(item.stalledSeats),
    coordinatorStalled: item.coordinatorStalled === true,
    tokens,
  }
}

/** The run row with its `runs_read` `frontier` data folded in. */
export function withFrontier(run: BoardRun, data: unknown): BoardRun {
  if (!isObj(data)) return run
  const counts = isObj(data.counts) ? data.counts : {}
  const seats = Array.isArray(data.inFlight) ? data.inFlight.filter(isObj) : []

  return {
    ...run,
    counts: {
      queue: num(counts.queue),
      work: num(counts.work),
      review: num(counts.review),
      blocked: num(counts.blocked),
      terminal: num(counts.terminal),
    },
    ready: Array.isArray(data.ready) ? data.ready.length : 0,
    inFlight: seats.length,
    waiting: Array.isArray(data.waiting) ? data.waiting.length : 0,
    stalled: seats.filter(s => s.stalled === true).length,
  }
}

/** Open and acknowledged decisions, and how many of them are overdue. */
export function decisionCounts(data: unknown): { open: number; overdue: number } {
  const waiting = listItems(data).filter(d => d.status === 'open' || d.status === 'acknowledged')

  return { open: waiting.length, overdue: waiting.filter(d => d.overdue === true).length }
}

/** The run's total member count, from its phase counts. */
export function runTotal(counts: BoardRun['counts']): number {
  return counts.queue + counts.work + counts.review + counts.blocked + counts.terminal
}

/** A bar of `width` cells, filled for done of total; all empty for a total of 0. */
export function progressBar(done: number, total: number, width = 8): string {
  const filled = total > 0 ? Math.round((Math.min(done, total) / total) * width) : 0

  return '▰'.repeat(filled) + '▱'.repeat(width - filled)
}

/** `26k`, `1.2M`, `0`. */
export function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}M`
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`

  return String(Math.max(0, Math.round(n)))
}

/** The phase pipeline cell: `q1 > w2 > r0 > done 3`, with ` · blocked 1` when any member is blocked. */
export function phaseCell(counts: BoardRun['counts']): string {
  const cell = `q${counts.queue} → w${counts.work} → r${counts.review} → ✓${counts.terminal}`

  return counts.blocked > 0 ? `${cell} · blocked ${counts.blocked}` : cell
}

/** The run's short name: the title cut to `max` characters, else the label, else the id prefix. */
export function runName(run: BoardRun, max = 24): string {
  const name = run.title || run.label || run.id.slice(0, 8)

  return name.length > max ? `${name.slice(0, max - 1)}…` : name
}

/** The seats cell: `in flight 2 · ready 1 · waiting 1`, with the stall count when any. */
export function seatsCell(run: BoardRun): string {
  const flight = run.stalled > 0 ? `in flight ${run.inFlight} (${run.stalled} stalled)` : `in flight ${run.inFlight}`

  return [flight, `ready ${run.ready}`, `waiting ${run.waiting}`].join(' · ')
}

/** The paused run's cell in place of its bar: `PAUSED run-budget`. */
export function pausedCell(run: BoardRun): string {
  return run.pauseReason ? `PAUSED ${run.pauseReason}` : 'PAUSED'
}

/** Members done over members in all, across the board's runs; zeros with no run. */
export function boardProgress(board: Board): { done: number; total: number } {
  let done = 0
  let total = 0
  for (const run of board?.runs ?? []) {
    done += run.counts.terminal
    total += runTotal(run.counts)
  }

  return { done, total }
}

/** The dim text after the mode title: the board's totals, or why there are none. */
export function modeDetail(board: Board): string {
  if (board === null) return 'board loading'
  if (board.error !== undefined) return `board unavailable: ${board.error}`
  const parts: string[] = []
  const open = board.runs.filter(r => r.status === 'open').length
  const paused = board.runs.length - open
  parts.push(paused > 0 ? `runs ${open} open, ${paused} paused` : `runs ${open} open`)
  if (board.decisions !== null) {
    const d = board.decisions
    parts.push(d.overdue > 0 ? `decisions ${d.open} (${d.overdue} overdue)` : `decisions ${d.open}`)
  }
  if (board.blocked !== null) parts.push(`blocked ${board.blocked}`)

  return parts.join(' · ')
}
