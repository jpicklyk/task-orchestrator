// Pure helpers for the orchestration-mode mod. No `$`, no atoms: indicator.ts feeds these values and
// draws what they return, so they are unit-testable with the plugin test kit alone.

import type { Board, BoardRun, CoordinatorState, FocusBoard, FocusState, ItemFacts, PhaseCounts } from '../types'

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
 * Where the task-orchestrator hooks keep one of this session's markers:
 * `<tmp>/task-orchestrator/<session>/<file>` (hooks/lib/markers.mjs `sessionMarkerPath`), the
 * director guard's `director.json` by default. The separator follows the temp dir, so a Windows
 * path stays a Windows path.
 */
export function markerPath(tmp: string, sessionId: string, file = 'director.json'): string {
  const sep = tmp.includes('\\') ? '\\' : '/'

  return [tmp, 'task-orchestrator', sanitizeKey(sessionId || 'unknown'), file].join(sep)
}

/** True for a Skill tool call of the director skill, whatever its args. */
export function isDirectorSkillCall(skill: unknown): boolean {
  return typeof skill === 'string' && skill.trim() === DIRECTOR_SKILL
}

/** The footer label, drawn like the model name beside it; the modes follow it, dim like the effort. */
export const FOOTER_PREFIX = 'TO'

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

/** The director backdrop's glass: a warm amber tint so the window reads as "director" at a glance. */
export const GLASS = { blur: 14, tint: '#7c4a03', opacity: 0.42 } as const
/** The coordinator backdrop's glass: a cool blue tint. */
export const COORDINATOR_GLASS = { blur: 14, tint: '#0b3a6e', opacity: 0.42 } as const

// ---------------------------------------------------------------------------------------------
// The roster: the TO modes a user sees in a session of their own. Seats, ralph and workflow
// coordinators never run in a visible session, so they are never labelled. One entry per mode,
// in footer order; a third mode is one more entry.
// ---------------------------------------------------------------------------------------------

export type ModeName = 'director' | 'coordinator'

export type ModeEntry = {
  /** The name drawn in the footer after FOOTER_PREFIX. */
  name: ModeName
  /** The session marker file the task-orchestrator plugin writes for the mode. */
  marker: string
  /** The backdrop id and glass set while this is the first roster mode on. */
  backdropId: string
  glass: { readonly blur: number; readonly tint: string; readonly opacity: number }
}

export const MODES: readonly ModeEntry[] = [
  { name: 'director', marker: 'director.json', backdropId: 'director', glass: GLASS },
  { name: 'coordinator', marker: 'coordinator.json', backdropId: 'coordinator', glass: COORDINATOR_GLASS },
]

/** The roster modes that are on, in roster order, whatever order `on` names them in. */
export function modesOn(on: readonly string[]): ModeEntry[] {
  return MODES.filter(mode => on.includes(mode.name))
}

/** The mode a session is in when no roster mode is on, named in the footer at rest. */
export const REST_MODE = 'orchestrator'

/** The footer's mode text: the roster modes that are on joined with ` & `; `orchestrator` when none is. */
export function footerModes(on: readonly string[]): string {
  const names = modesOn(on).map(mode => mode.name)

  return names.length > 0 ? names.join(' & ') : REST_MODE
}

/** The native labels that precede ours in the footer slot, joined as the engine joins them. */
export function footerLead(modes: readonly string[]): string {
  const others = modes.filter(mode => !MODES.some(m => m.name === mode))

  return others.length > 0 ? `${others.join(' & ')} & ` : ''
}

/** `Coordinator of run wave-8` when this session opens a run. */
export function coordinatorToast(label: string): string {
  return label ? `Coordinator of run ${label}` : 'Coordinator of a run'
}

/** `Run wave-8 closed` when the coordinated run closes. */
export function runClosedToast(label: string): string {
  return label ? `Run ${label} closed` : 'Run closed'
}

export const BAND_HIDDEN_TOAST = 'Band hidden'
export const BAND_SHOWN_TOAST = 'Band shown'
/** The slash command that hides or shows the band for this session (`/to-band`). */
export const BAND_COMMAND = 'to-band'

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
// The band: the graph this session drives. The mode row, then one row per open or paused run, or,
// with no run, one row for the focus item.
// ---------------------------------------------------------------------------------------------

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
/** Refresh period, and the shortest gap between two refreshes. */
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

/** The `{ok, data | error}` envelope of a tool result: `structuredContent`, else the JSON of the first text block. */
function envelopeOf(result: { structuredContent?: unknown; content?: unknown }): Obj | undefined {
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

  return isObj(payload) && typeof payload.ok === 'boolean' ? payload : undefined
}

/**
 * The `data` of a 4.0 tool result envelope, read from `structuredContent` first, then the JSON of the
 * first text block. Throws `<code>: <message>` on an error envelope and on a result with no envelope.
 */
export function envelopeData(result: { structuredContent?: unknown; content?: unknown; isError?: boolean }): unknown {
  const payload = envelopeOf(result)
  if (payload === undefined) throw new Error('no result envelope')
  if (!payload.ok) {
    const error = isObj(payload.error) ? payload.error : {}

    throw new Error(`${str(error.code) || 'error'}: ${str(error.message)}`)
  }

  return payload.data
}

/**
 * The envelope of a TO tool call as a `tool.call` hook sees it after `next`: the tool's `result`
 * (an envelope itself, an MCP result with `structuredContent` or `content`, or a content array),
 * else the JSON of `text` (what the model read). Undefined when none of them holds one.
 */
export function toolEnvelope(ran: { result?: unknown; text?: unknown }): Obj | undefined {
  const result = ran.result
  if (isObj(result) && typeof result.ok === 'boolean') return result
  if (isObj(result)) {
    const inner = envelopeOf(result)
    if (inner !== undefined) return inner
  }
  if (Array.isArray(result)) {
    const inner = envelopeOf({ content: result })
    if (inner !== undefined) return inner
  }
  if (typeof ran.text === 'string') return envelopeOf({ content: [{ text: ran.text }] })

  return undefined
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
export function runTotal(counts: PhaseCounts): number {
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

/** The phase pipeline cell: `q1 → w2 → r0 → ✓3`, with ` · blocked 1` when any member is blocked. */
export function phaseCell(counts: PhaseCounts): string {
  const cell = `q${counts.queue} → w${counts.work} → r${counts.review} → ✓${counts.terminal}`

  return counts.blocked > 0 ? `${cell} · blocked ${counts.blocked}` : cell
}

/** `text` cut to `max` characters with an ellipsis (never after a space). */
export function cut(text: string, max = 24): string {
  return text.length > max ? `${text.slice(0, max - 1).trimEnd()}…` : text
}

/** The run's short name on the container line: its label, else its title, else the id prefix, cut to `max`. */
export function runLabel(run: BoardRun, max = 24): string {
  return cut(run.label || run.title || run.id.slice(0, 8), max)
}

/** The seats cell: `in flight 2 · ready 1 · waiting 1`, with the stall count when any. */
export function seatsCell(run: BoardRun): string {
  const flight = run.stalled > 0 ? `in flight ${run.inFlight} (${run.stalled} stalled)` : `in flight ${run.inFlight}`

  return [flight, `ready ${run.ready}`, `waiting ${run.waiting}`].join(' · ')
}

/** The paused run's chip: `PAUSED run-budget`. */
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

/** The decisions cell: `decisions 2 (1 overdue)`. */
function decisionsCell(d: { open: number; overdue: number }): string {
  return d.overdue > 0 ? `decisions ${d.open} (${d.overdue} overdue)` : `decisions ${d.open}`
}

// ---------------------------------------------------------------------------------------------
// The band's three lines: the container, the progress, the work item this session touched.
// ---------------------------------------------------------------------------------------------

const zero = (): PhaseCounts => ({ queue: 0, work: 0, review: 0, blocked: 0, terminal: 0 })

/**
 * Line 2's numbers: the phase counts across every member (the runs' members, else the focus subtree
 * with the focus itself), done over total, the blocked count, and whether a seat or a coordinator is
 * stalled (the bar turns red).
 */
export function graphCounts(board: Board): { counts: PhaseCounts; done: number; total: number; blocked: number; attention: boolean } {
  const runs = board?.runs ?? []
  if (runs.length > 0) {
    const counts = zero()
    for (const run of runs) for (const r of ROLES) counts[r] += run.counts[r]

    return {
      counts,
      done: counts.terminal,
      total: runTotal(counts),
      blocked: counts.blocked,
      attention: runs.some(run => run.stalled > 0 || run.coordinatorStalled),
    }
  }
  const counts = board?.focus?.counts ?? zero()

  return { counts, done: counts.terminal, total: runTotal(counts), blocked: board?.focus?.blocked ?? counts.blocked, attention: false }
}

/** How a band cell is drawn: the container title, dim facts, plain text, the lit phase, or a colour. */
export type Tone = 'title' | 'dim' | 'text' | 'lit' | 'success' | 'error' | 'errorBold' | 'warningBold'

/** One run of text on a band line; a separator before it is part of its text, so it goes with it. */
export type Cell = { text: string; tone: Tone }

/** The gap between the groups of line 2 (facts, bar, counts) and between line 3's lead and title. */
export const GAP = '   '

/** The cells a band line has in its frame: the body less the round border and the one-cell padding each side. */
export function bandWidth(bodyColumns: number): number {
  return Math.max(1, bodyColumns - 4)
}

/** The cells a line of `cells` takes. */
export function cellsWidth(cells: readonly Cell[]): number {
  return cells.reduce((n, cell) => n + cell.text.length, 0)
}

/**
 * Cuts a line to `width` itself (the desktop app wraps a long Text whatever its `wrap` says, so no
 * line may rely on the frame): drops cells from the end first, down to the `elastic` one, then ends
 * that one with an ellipsis. A line whose cells before the elastic one are already too wide keeps
 * only the elastic cell, cut to fit.
 */
export function fitCells(cells: readonly Cell[], width: number, elastic = 0): Cell[] {
  const out = cells.filter(cell => cell.text !== '')
  while (cellsWidth(out) > width && out.length > elastic + 1) out.pop()
  const over = cellsWidth(out) - width
  const target = out[elastic]
  if (over <= 0 || target === undefined) return out
  const room = target.text.length - over
  if (room >= 2) {
    out[elastic] = { ...target, text: cut(target.text, room) }

    return out
  }

  return [{ ...target, text: cut(target.text.replace(/^[\s·]+/, ''), Math.max(1, width)) }]
}

const PHASE_ROLES = ['queue', 'work', 'review', 'terminal'] as const
const PHASE_NAMES = ['queue', 'work', 'review', 'done'] as const

/**
 * An item's own phase pipeline with the current phase lit: `queue → ◉ work → review → done`. The
 * four phases of the role sequence (the `get` the band reads cannot tell an item without a review
 * phase); a blocked item lights none and adds `◉ blocked`.
 */
export function phasePipeline(role: string, prefix = ''): Cell[] {
  const at = PHASE_ROLES.findIndex(r => r === role)
  if (at < 0) {
    const cells: Cell[] = [{ text: prefix + PHASE_NAMES.join(' → '), tone: 'dim' }]
    if (role === 'blocked') cells.push({ text: `${SEP}◉ blocked`, tone: 'lit' })

    return cells
  }
  const before = PHASE_NAMES.slice(0, at)
  const after = PHASE_NAMES.slice(at + 1)

  const cells: Cell[] = [
    ...(before.length > 0 || prefix !== '' ? [{ text: prefix + before.map(name => `${name} → `).join(''), tone: 'dim' as const }] : []),
    { text: `◉ ${PHASE_NAMES[at]}`, tone: 'lit' },
    ...(after.length > 0 ? [{ text: after.map(name => ` → ${name}`).join(''), tone: 'dim' as const }] : []),
  ]

  return cells.filter(cell => cell.text !== '')
}

/** `2 runs open`, or `3 runs, 2 open, 1 paused`. */
function runsCell(runs: readonly BoardRun[]): string {
  const first = runs[0]
  if (first === undefined) return ''
  if (runs.length === 1) return `run ${runLabel(first)} ${first.status}`
  const open = runs.filter(run => run.status === 'open').length
  const paused = runs.length - open

  return paused > 0 ? `${runs.length} runs, ${open} open, ${paused} paused` : `${runs.length} runs open`
}

/**
 * Line 1, the container: the root of the work alone, in full (bold amber), then, with a run open, the
 * run (`run wave-8 open`, or `2 runs open`), the first run's chip (`PAUSED <reason>`, `COORD STALLED`),
 * its seats and, at 120 columns or more, its tokens. Unfitted; null with no container and no run.
 */
export function line1Cells(container: ItemFacts | null | undefined, runs: readonly BoardRun[], columns: number): Cell[] | null {
  const first = runs[0]
  const title = container?.title ?? ''
  if (title === '' && first === undefined) return null
  const cells: Cell[] = title === '' ? [] : [{ text: title, tone: 'title' }]
  if (first !== undefined) {
    const sep = (): string => (cells.length > 0 ? SEP : '')
    cells.push({ text: sep() + runsCell(runs), tone: 'dim' })
    if (first.status === 'paused') cells.push({ text: SEP + pausedCell(first), tone: 'warningBold' })
    else if (first.coordinatorStalled) cells.push({ text: `${SEP}COORD STALLED`, tone: 'errorBold' })
    for (const part of seatsCell(first).split(SEP)) cells.push({ text: SEP + part, tone: 'dim' })
    if (columns >= 120) cells.push({ text: `${SEP}${formatTokens(first.tokens)} tok`, tone: 'dim' })
  }

  return cells
}

/**
 * Line 2, the progress: the container's dim type and phase, then, for a graph with members (a run, or
 * a focus with children), the bar, `done/total` and the counts pipeline; for a focus with no children,
 * its type and its own phase pipeline. Then only the non-zero `blocked N` and `decisions N (k overdue)`.
 * `loading` before the first read, the fault after a failed one. Unfitted.
 */
export function line2Cells(board: Board, container: ItemFacts | null | undefined, graph: FocusBoard | null): Cell[] {
  const runs = board?.runs ?? []
  const leaf = runs.length === 0 && graph !== null && !(graph.hasChildren ?? runTotal(graph.counts) > 1)
  const facts = container ? (leaf ? [container.type] : [container.type, container.label]).filter(cell => cell !== '').join(SEP) : ''
  const cells: Cell[] = facts === '' ? [] : [{ text: facts, tone: 'dim' }]
  const gap = (): string => (cells.length > 0 ? GAP : '')
  if (board === null) return [...cells, { text: `${gap()}loading`, tone: 'dim' }]
  if (board.error !== undefined) return [...cells, { text: `${gap()}board unavailable: ${board.error}`, tone: 'dim' }]
  const { counts, done, total, blocked, attention } = graphCounts(board)
  if (leaf) cells.push(...phasePipeline(graph?.role ?? '', cells.length > 0 ? SEP : ''))
  else {
    if (total > 0) {
      cells.push({ text: gap() + progressBar(done, total), tone: attention ? 'error' : 'success' })
      cells.push({ text: ` ${done}/${total}`, tone: 'dim' })
    }
    cells.push({ text: gap() + phaseCell({ ...counts, blocked: 0 }), tone: 'dim' })
  }
  if (blocked > 0) cells.push({ text: `${SEP}blocked ${blocked}`, tone: 'dim' })
  if (board.decisions !== null && board.decisions.open > 0) cells.push({ text: SEP + decisionsCell(board.decisions), tone: 'dim' })

  return cells
}

/** The shortest a line 3 title is cut to before its `blocked by` cell is dropped instead. */
const MIN_ITEM_TITLE = 12

/**
 * Line 3, the current item, fitted to `width`: `d8999287 · plugin-change · in-progress`, the title,
 * then `blocked by <id>` in red. The title gives way first (an ellipsis), down to a dozen cells; past
 * that the blocker cell goes and the title takes the room.
 */
export function line3Cells(item: ItemFacts & { blockedBy?: string }, width: number): Cell[] {
  const lead: Cell = { text: itemLead(item), tone: 'dim' }
  const title: Cell = { text: GAP + item.title, tone: 'text' }
  if (item.blockedBy !== undefined) {
    const blocker: Cell = { text: SEP + blockedByCell(item.blockedBy), tone: 'error' }
    const room = width - lead.text.length - blocker.text.length
    if (title.text.length <= room) return [lead, title, blocker]
    if (room >= GAP.length + MIN_ITEM_TITLE) return [lead, { ...title, text: cut(title.text, room) }, blocker]
  }

  return fitCells([lead, title], width, 1)
}

/** Line 3's dim lead before the title: `d8999287 · plugin-change · in-progress`. */
export function itemLead(item: ItemFacts): string {
  return [item.itemId.slice(0, 8), item.type, item.label].filter(cell => cell !== '').join(' · ')
}

/** Line 3's blocker cell: `blocked by 95c660e2`. */
export function blockedByCell(blocker: string): string {
  return `blocked by ${blocker.slice(0, 8)}`
}

/** One separator between band cells. */
export const SEP = ' · '

// ---------------------------------------------------------------------------------------------
// The focus walk: the top-most item below the project root of the item this session touched.
// ---------------------------------------------------------------------------------------------

/** One item as the focus walk needs it, from an `items_read` `get` result's `data`. */
export type FocusItem = { id: string; parentId: string | null; depth: number; title: string; type: string; label: string; role: string }

/** The focus walk's view of an `items_read` `get` result; null for anything without an id. */
export function focusFromGet(data: unknown): FocusItem | null {
  if (!isObj(data) || typeof data.id !== 'string' || data.id === '') return null
  const role = str(data.role)

  return {
    id: data.id,
    parentId: typeof data.parentId === 'string' && data.parentId !== '' ? data.parentId : null,
    depth: typeof data.depth === 'number' ? data.depth : 0,
    title: str(data.title),
    type: str(data.type),
    label: str(data.statusLabel) || role,
    role,
  }
}

/** The facts a band line shows of an item. */
export function itemFacts(item: FocusItem): ItemFacts {
  return { itemId: item.id, title: item.title, type: item.type, label: item.label }
}

/** The most `parentId` hops the focus walk takes before it settles on where it got to. */
export const MAX_FOCUS_HOPS = 6

/**
 * The ancestor-walk rule: the next id to read on the way up, or null when `item` is the focus
 * (depth 0 or 1, the top-most item below the project root, or an item with no parent).
 */
export function focusParent(item: FocusItem): string | null {
  return item.depth <= 1 ? null : item.parentId
}

/**
 * A focus walk's result: the focus item, the touched item itself (the walk's first read), and every
 * id the walk read on the way (the start id too).
 */
export type FocusWalk = { item: FocusItem; start: FocusItem; walked: string[] }

/**
 * Walks from `startId` up `parentId` to the focus item (focusParent), reading each hop through `get`
 * (an `items_read` `get` returning its `data`), at most `maxHops` hops; past that, the item it
 * reached. `known` answers an id an earlier walk already resolved, which ends this walk there with
 * no read. Null when the first read fails or answers no item; a later failed hop settles on the
 * last item read.
 */
export async function resolveFocus(
  startId: string,
  get: (itemId: string) => Promise<unknown>,
  maxHops = MAX_FOCUS_HOPS,
  known: (itemId: string) => FocusItem | undefined = () => undefined,
): Promise<FocusWalk | null> {
  const walked = [startId]
  let item: FocusItem | null
  try {
    item = focusFromGet(await get(startId))
  } catch {
    return null
  }
  if (item === null) return null
  const start = item
  walked.push(item.id)
  for (let hop = 0; hop < maxHops; hop++) {
    const parent = focusParent(item)
    if (parent === null) break
    const cached = known(parent)
    if (cached !== undefined) {
      walked.push(parent)
      return { item: cached, start, walked }
    }
    let next: FocusItem | null
    try {
      next = focusFromGet(await get(parent))
    } catch {
      break
    }
    if (next === null) break
    item = next
    walked.push(next.id)
  }

  return { item, start, walked }
}

/** True when two item or run ids name the same thing: one is a case-insensitive prefix of the other. */
/**
 * The focus as this version reads it: a value written before `touched` existed (the atom survives a
 * reload and a resume) gets the focus itself as its touched item, and a value with no item is null.
 */
export function focusValue(value: unknown): NonNullable<FocusState> | null {
  if (value === null || typeof value !== 'object') return null
  const v = value as { itemId?: unknown; title?: unknown; since?: unknown; touched?: unknown }
  if (typeof v.itemId !== 'string' || v.itemId === '') return null
  const title = typeof v.title === 'string' ? v.title : v.itemId
  const since = typeof v.since === 'string' ? v.since : ''
  const touched = v.touched as Partial<ItemFacts> | undefined
  const facts: ItemFacts =
    touched !== undefined && touched !== null && typeof touched.itemId === 'string'
      ? { itemId: touched.itemId, title: typeof touched.title === 'string' ? touched.title : touched.itemId, type: typeof touched.type === 'string' ? touched.type : '', label: typeof touched.label === 'string' ? touched.label : '' }
      : { itemId: v.itemId, title, type: '', label: '' }

  return { itemId: v.itemId, title, since, touched: facts }
}

export function sameId(a: string, b: string): boolean {
  if (a === '' || b === '') return false
  const x = a.toLowerCase()
  const y = b.toLowerCase()

  return x.startsWith(y) || y.startsWith(x)
}

const ROLES = ['queue', 'work', 'review', 'blocked', 'terminal'] as const

/**
 * The focus graph's phase counts from an `items_read` `overview` `{anchorId}` result: the focus
 * itself in its own role (`selfRole`), then each child in its role plus its subtree's `childCounts`.
 */
export function focusProgress(overview: unknown, selfRole = ''): PhaseCounts {
  const counts = zero()
  const self = ROLES.find(r => r === selfRole)
  if (self !== undefined) counts[self] += 1
  for (const child of listItems(overview)) {
    const role = ROLES.find(r => r === child.role)
    if (role !== undefined) counts[role] += 1
    const below = isObj(child.childCounts) ? child.childCounts : {}
    for (const r of ROLES) counts[r] += num(below[r])
  }

  return counts
}

/**
 * The blocked work under the focus, from an `items_read` `blocked` `{ancestorId}` result: the total,
 * and the first unsatisfied blocker (8-character id) of each listed item, by the listed item's id.
 */
export function focusBlocked(data: unknown): { total: number; blockers: Record<string, string> } {
  const blockers: Record<string, string> = {}
  for (const item of listItems(data)) {
    if (typeof item.id !== 'string') continue
    const all = Array.isArray(item.blockers) ? item.blockers.filter(isObj) : []
    const open = all.find(b => b.satisfied !== true) ?? all[0]
    if (open !== undefined && typeof open.id === 'string') blockers[item.id.toLowerCase()] = open.id.slice(0, 8)
  }

  return { total: listTotal(data), blockers }
}

/** The blocker `focusBlocked` found for `itemId`, matched by id prefix either way. */
export function blockerOf(blockers: Record<string, string>, itemId: string): string | undefined {
  for (const [id, blocker] of Object.entries(blockers)) if (sameId(id, itemId)) return blocker

  return undefined
}

// ---------------------------------------------------------------------------------------------
// What the session's own TO calls say: the item it touched, and a run it opened or closed.
// ---------------------------------------------------------------------------------------------

/** The TO tools a write or an item-scoped read of names an item through. */
const ITEM_TOOLS = ['items_write', 'advance', 'notes_write', 'deps_write'] as const

/** `runs_write` for `mcp__mcp-task-orchestrator__runs_write` and its director twin. */
export function toolBase(tool: string): string {
  const at = tool.lastIndexOf('__')

  return at < 0 ? tool : tool.slice(at + 2)
}

const firstId = (o: Obj): string | undefined => {
  for (const key of ['itemId', 'id', 'from', 'to', 'parentId']) {
    const v = o[key]
    if (typeof v === 'string' && v !== '') return v
  }

  return undefined
}

/**
 * The item a TO call touched: `items_read` names it by its top-level `itemId`; the writes
 * (`items_write`, `advance`, `notes_write`, `deps_write`) by the first op's `itemId`, `id`, `from`,
 * `to` or, for a create, `parentId`, else a top-level one. Undefined for any other call.
 */
export function touchedItemId(tool: string, input: Obj): string | undefined {
  const base = toolBase(tool)
  if (base === 'items_read') return typeof input.itemId === 'string' && input.itemId !== '' ? input.itemId : undefined
  if (!(ITEM_TOOLS as readonly string[]).includes(base)) return undefined
  const ops = Array.isArray(input.ops) ? input.ops.filter(isObj) : []
  for (const op of ops) {
    const id = firstId(op)
    if (id !== undefined) return id
  }

  return firstId(input)
}

/** The `results[i]` of a write envelope (`data: {results, summary}`), whether or not the batch failed. */
function writeResults(envelope: Obj | undefined): unknown[] {
  if (envelope === undefined || !isObj(envelope.data) || !Array.isArray(envelope.data.results)) return []

  return envelope.data.results
}

const opsOf = (input: Obj): Obj[] => (Array.isArray(input.ops) ? input.ops.map(op => (isObj(op) ? op : {})) : [])

/**
 * The run this session became the coordinator of: the first `open` op of a `runs_write` call whose
 * result element is ok and carries a `coordinatorToken` (the token itself is never kept). Null when
 * the call opened no run.
 */
export function coordinatorFromOpen(input: Obj, envelope: Obj | undefined, since: string): CoordinatorState {
  const ops = opsOf(input)
  const results = writeResults(envelope)
  for (let i = 0; i < ops.length; i++) {
    const op = ops[i]
    const result = results[i]
    if (op?.op !== 'open' || !isObj(result) || result.ok !== true) continue
    if (typeof result.coordinatorToken !== 'string' || typeof result.runId !== 'string') continue
    const label = str(result.label) || str(op.label) || str(result.title) || str(op.title) || result.runId.slice(0, 8)

    return { runId: result.runId, label, since, source: 'tool' }
  }

  return null
}

/** True when a `runs_read` `get` result's run is closed. */
export function runIsClosed(data: unknown): boolean {
  return isObj(data) && data.status === 'closed'
}

/** The run ids a `runs_write` call closed: each `close` op whose result element is ok. */
export function closedRunIds(input: Obj, envelope: Obj | undefined): string[] {
  const ops = opsOf(input)
  const results = writeResults(envelope)
  const closed: string[] = []
  ops.forEach((op, i) => {
    const result = results[i]
    if (op.op === 'close' && typeof op.runId === 'string' && isObj(result) && result.ok === true) closed.push(op.runId)
  })

  return closed
}

/** The coordinator state a `coordinator.json` marker stands for: its runId and label when it names them. */
export function coordinatorFromMarker(marker: unknown, since: string): NonNullable<CoordinatorState> {
  const m = isObj(marker) ? marker : {}
  const runId = str(m.runId)

  return { runId, label: str(m.label) || str(m.title) || runId.slice(0, 8), since: str(m.since) || since, source: 'marker' }
}
