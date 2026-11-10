// Pure helpers for the orchestration-mode mod. No `$`, no atoms: indicator.ts feeds these values and
// draws what they return, so they are unit-testable with the plugin test kit alone.

import type { Board, BoardMember, BoardRun, CoordinatorState, FocusBoard, FocusState, ItemFacts, MemberStatus, PhaseCounts } from '../types'

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
  const hints = isObj(item.hints) ? item.hints : {}

  return {
    id: str(item.runId),
    label: str(item.label),
    title: str(item.title),
    status,
    pauseReason: typeof item.pauseReason === 'string' ? item.pauseReason : undefined,
    counts: { queue: 0, work: 0, review: 0, blocked: 0, terminal: 0 },
    ready: 0,
    inFlight: num(item.activeSeats),
    waiting: 0,
    stalled: num(item.stalledSeats),
    coordinatorStalled: item.coordinatorStalled === true,
    workflowLaunched: hints.method === 'A' && hints.phase === 'launched',
    scopeId: str(item.scopeId) || undefined,
    rootId: str(item.rootId) || undefined,
    tokens,
  }
}

/**
 * Whether the run's coordinator needs a look, by the director skill's rule (director/SKILL.md, the
 * coordinator cell): never on a paused run; idle, not stalled, while a Workflow runs the run (Method A
 * launched: nothing calls under the coordinator token until post-run) or while any in-flight seat is
 * live; else the server's flag.
 */
export function coordStalled(run: BoardRun): boolean {
  if (!run.coordinatorStalled || run.status !== 'open') return false
  if (run.workflowLaunched === true) return false

  return run.inFlight - run.stalled <= 0
}

/**
 * The runs this session's band shows, in every mode (director included: the band is the session's own
 * graph, not the board): the run this session coordinates, the runs it or one of its subagents opened
 * or named (`watched`), and the runs scoped to an item of its focus graph (`focusIds`: the focus, the
 * touched item, the ancestors its walk read). The coordinated run first, then the server's order. A
 * run of another session or another project is never drawn.
 */
export function sessionRuns(
  runs: readonly BoardRun[],
  scope: { coordinatorRunId?: string; watched: readonly string[]; focusIds: readonly string[] },
): BoardRun[] {
  const coordinated = (run: BoardRun): boolean => scope.coordinatorRunId !== undefined && sameId(run.id, scope.coordinatorRunId)
  const watched = (run: BoardRun): boolean => scope.watched.some(id => sameId(run.id, id))
  const focused = (run: BoardRun): boolean => run.scopeId !== undefined && scope.focusIds.some(id => sameId(run.scopeId ?? '', id))
  const mine = runs.filter(run => coordinated(run) || watched(run) || focused(run))

  return [...mine.filter(coordinated), ...mine.filter(run => !coordinated(run))]
}

/** The most run ids a session keeps as its own; the oldest goes first. */
export const MAX_WATCHED_RUNS = 20

/**
 * The run ids a `runs_write` call makes this session's (whoever in it made the call): the `runId` of
 * each ok `open` result element, and each op's `runId` (a run it acts on). A read never does: the
 * director reads every run's frontier to run the board, and the band is not the board.
 */
export function namedRunIds(tool: string, input: Obj, envelope: Obj | undefined): string[] {
  if (toolBase(tool) !== 'runs_write') return []
  const ids: string[] = []
  const results = writeResults(envelope)
  opsOf(input).forEach((op, i) => {
    if (typeof op.runId === 'string' && op.runId !== '') ids.push(op.runId)
    const result = results[i]
    if (op.op === 'open' && isObj(result) && result.ok === true && typeof result.runId === 'string') ids.push(result.runId)
  })
  if (typeof input.runId === 'string' && input.runId !== '') ids.push(input.runId)

  return ids
}

/** `watched` with `ids` added (each once, by id prefix either way), newest last, at most `max`. */
export function watchRuns(watched: readonly string[], ids: readonly string[], max = MAX_WATCHED_RUNS): string[] {
  const next = [...watched]
  for (const id of ids) {
    const at = next.findIndex(w => sameId(w, id))
    // Keep the longer spelling: a full UUID read later replaces the prefix a call named first.
    const keep = at < 0 ? id : (next[at] ?? '').length >= id.length ? (next[at] ?? id) : id
    if (at >= 0) next.splice(at, 1)
    next.push(keep)
  }

  return next.slice(-max)
}

/** The run row with its `runs_read` `frontier` data folded in. */
export function withFrontier(run: BoardRun, data: unknown): BoardRun {
  if (!isObj(data)) return run
  const counts = isObj(data.counts) ? data.counts : {}
  const seats = Array.isArray(data.inFlight) ? data.inFlight.filter(isObj) : []

  return {
    ...run,
    coordinatorStalled: typeof data.stalled === 'boolean' ? data.stalled : run.coordinatorStalled,
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
    cancelled: num(data.cancelled),
    members: frontierMembers(data, run.workflowLaunched === true && run.status === 'open' && seats.length === 0),
  }
}

const idOf = (o: Obj): string => str(o.id) || str(o.itemId)

/**
 * Each member the frontier lists, once, with its status: a member with a live seat is `working` (its first live seat),
 * one whose seats all stalled is `stuck`; a waiting member is `needsYou` on a decision, else `notStarted`; a ready
 * member is `finished` past the queue (its last agent finished and it waits to be advanced) and `notStarted` in the
 * queue, unless every seat of a Workflow has ended (`allDone`), when every ready member is `finished`.
 */
export function frontierMembers(data: Obj, allDone = false): BoardMember[] {
  const seats = Array.isArray(data.inFlight) ? data.inFlight.filter(isObj) : []
  const out = new Map<string, BoardMember>()
  for (const seat of seats) {
    const id = idOf(seat)
    if (id === '') continue
    const live = seat.stalled !== true
    const had = out.get(id)
    if (had !== undefined && (had.status === 'working' || !live)) continue
    out.set(id, {
      id,
      status: live ? 'working' : 'stuck',
      role: str(seat.role) || undefined,
      seat: str(seat.seat) || undefined,
      title: str(seat.title) || undefined,
      lastSeenAt: str(seat.lastSeenAt) || undefined,
    })
  }
  for (const wait of Array.isArray(data.waiting) ? data.waiting.filter(isObj) : []) {
    const id = idOf(wait)
    if (id === '' || out.has(id)) continue
    out.set(id, { id, status: wait.reason === 'awaiting_decision' ? 'needsYou' : 'notStarted' })
  }
  for (const ready of Array.isArray(data.ready) ? data.ready.filter(isObj) : []) {
    const id = idOf(ready)
    if (id === '' || out.has(id)) continue
    const role = str(ready.role)
    out.set(id, { id, status: allDone || (role !== '' && role !== 'queue') ? 'finished' : 'notStarted', role: role || undefined, title: str(ready.title) || undefined })
  }

  return [...out.values()]
}

/** The run's total member count, from its phase counts. */
export function runTotal(counts: PhaseCounts): number {
  return counts.queue + counts.work + counts.review + counts.blocked + counts.terminal
}

/** `text` cut to `max` characters with an ellipsis (never after a space). */
export function cut(text: string, max = 24): string {
  return text.length > max ? `${text.slice(0, max - 1).trimEnd()}…` : text
}

/** A run-wave label, `r-<yyyyMMddHHmm>-<first item>`: its trailing short id is what tells two waves apart. */
const WAVE_LABEL = /^r-\d{8,14}-([0-9a-f]{6,})$/i

/**
 * The run's short name: a run-wave label's trailing short id (`6e90e4d6` for `r-202611101447-6e90e4d6`), else its
 * label, else its title, else the id prefix, cut to `max`.
 */
export function runLabel(run: BoardRun, max = 24): string {
  const wave = WAVE_LABEL.exec(run.label)

  return cut(wave?.[1] ?? (run.label || run.title || run.id.slice(0, 8)), max)
}

/**
 * True when a Workflow ran the run and no seat is in flight any more: every seat it dispatched has ended, and the
 * coordinator owes the post-run step.
 */
export function seatsDone(run: BoardRun): boolean {
  return run.status === 'open' && run.workflowLaunched === true && run.inFlight === 0
}

// ---------------------------------------------------------------------------------------------
// Colours. Green is done, red is stuck or cancelled, amber is what needs the person; each member an
// agent works on takes an agent colour, which is none of those, and keeps it from read to read.
// ---------------------------------------------------------------------------------------------

/** The agent colours, in slot order: blue, violet, teal, pink. Past four members at once they repeat. */
export const AGENT_COLOURS = ['#378ADD', '#7F77DD', '#1D9E75', '#D4537E'] as const
/** What needs the person: a decision, a paused run, a wave whose agents have all finished. */
export const NEEDS_YOU = '#EF9F27'

/** The colour-slot key of a member: `<runId>:<item short id>`. */
export const colourKey = (runId: string, itemId: string): string => `${runId}:${itemId.slice(0, 8).toLowerCase()}`

/**
 * The colour slots after a read: each member in `working` keeps its earlier slot unless another working member took
 * it first, then takes the lowest free slot (slots repeat once all are taken); a member in `present` that is not
 * worked on keeps its slot for when it is again. Members gone from the run drop out.
 */
export function assignColours(prev: Record<string, number>, working: readonly string[], present: readonly string[] = []): Record<string, number> {
  const out: Record<string, number> = {}
  const taken = new Set<number>()
  const waiting: string[] = []
  for (const key of working) {
    const slot = prev[key]
    if (slot !== undefined && slot < AGENT_COLOURS.length && !taken.has(slot)) {
      out[key] = slot
      taken.add(slot)
    } else waiting.push(key)
  }
  let cycle = 0
  for (const key of waiting) {
    let slot = AGENT_COLOURS.findIndex((_, i) => !taken.has(i))
    if (slot < 0) slot = cycle++ % AGENT_COLOURS.length
    out[key] = slot
    taken.add(slot)
  }
  for (const key of present) {
    const slot = prev[key]
    if (out[key] === undefined && slot !== undefined) out[key] = slot
  }

  return out
}

/**
 * The colour slot of every member an agent works on, keyed by colourKey: its saved slot, else its place among the
 * worked-on members in run and member order (a board saved before the slots existed). The bar and the agent rows
 * both read it, so a member's cell and its marker always share a colour.
 */
export function workingSlots(board: Board, colours: Record<string, number> = {}): Map<string, number> {
  const out = new Map<string, number>()
  let order = 0
  for (const run of board?.runs ?? []) {
    for (const m of run.members ?? []) {
      if (m.status !== 'working') continue
      const key = colourKey(run.id, m.id)
      out.set(key, colours[key] ?? order)
      order += 1
    }
  }

  return out
}

/** The colour of a slot. */
export const agentColour = (slot: number | undefined): string => AGENT_COLOURS[(slot ?? 0) % AGENT_COLOURS.length] ?? AGENT_COLOURS[0]

// ---------------------------------------------------------------------------------------------
// Rows and cells: plain data the band draws, with the Text or with the `Client` (band.ts) when a
// cell blinks.
// ---------------------------------------------------------------------------------------------

/** How a band cell is drawn. */
export type Tone =
  | 'title'
  | 'dim'
  | 'text'
  | 'lit'
  | 'success'
  | 'error'
  | 'errorBold'
  | 'needsYou'
  | 'needsYouBold'
  | 'finished'
  | 'agent'

/**
 * One run of text on a band row; a separator before it is part of its text, so it goes with it. An `agent` cell
 * carries its `color`; a `blink` cell alternates with its `off` text.
 */
export type Cell = { text: string; tone: Tone; color?: string; blink?: boolean; off?: string }

/** One band row: `left` cut to the frame from its end (down to its `elastic` cell), `right` kept whole at the right edge. */
export type Row = { key: string; left: Cell[]; right: Cell[]; elastic?: number }

/** What a cell draws with, as plain data: text, the off-beat text of a blinking cell, colour, bold, dim. */
export type CellStyle = { t: string; o?: string; c?: string; b?: true; d?: true }

/** A cell's style: both renderers (the Text, the `Client`) draw exactly this. */
export function cellStyle(cell: Cell): CellStyle {
  const style = ((): Omit<CellStyle, 't' | 'o'> => {
    switch (cell.tone) {
      case 'title':
        return { c: 'warning', b: true }
      case 'text':
        return { c: 'text' }
      case 'lit':
        return { c: 'text', b: true }
      case 'success':
        return { c: 'success' }
      case 'error':
        return { c: 'error' }
      case 'errorBold':
        return { c: 'error', b: true }
      case 'needsYou':
        return { c: NEEDS_YOU }
      case 'needsYouBold':
        return { c: NEEDS_YOU, b: true }
      case 'finished':
        return { c: 'subtle' }
      case 'agent':
        return { c: cell.color ?? AGENT_COLOURS[0] }
      default:
        return { d: true }
    }
  })()

  return { t: cell.text, ...(cell.blink === true && cell.off !== undefined ? { o: cell.off } : {}), ...style }
}

/** The gap between a row's groups, and between the item row's lead and title. */
export const GAP = '   '

/** The cells a band row has in its frame: the body less the round border and the one-cell padding each side. */
export function bandWidth(bodyColumns: number): number {
  return Math.max(1, bodyColumns - 4)
}

/** The cells a run of cells takes. */
export function cellsWidth(cells: readonly Cell[]): number {
  return cells.reduce((n, cell) => n + cell.text.length, 0)
}

/**
 * Cuts cells to `width` (the desktop app wraps a long Text whatever its `wrap` says, so no row may rely on the frame):
 * drops cells from the end first, down to the `elastic` one, then ends that one with an ellipsis. Cells whose ones
 * before the elastic one are already too wide keep only the elastic cell, cut to fit.
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

/** The least a row's left side keeps before its right side gives way. */
const MIN_LEFT = 12

/**
 * A row cut to `width`: the right side stays whole and the left takes what is left, less a two-cell gap; a frame too
 * narrow for both drops the right side.
 */
export function fitRow(row: Row, width: number): Row {
  const right = cellsWidth(row.right)
  if (right > 0 && width - right - 2 >= MIN_LEFT) return { ...row, left: fitCells(row.left, width - right - 2, row.elastic ?? 0) }

  return { ...row, right: [], left: fitCells(row.left, width, row.elastic ?? 0) }
}

// ---------------------------------------------------------------------------------------------
// The bar: one cell per member (a share past 16), on the title row's right.
// ---------------------------------------------------------------------------------------------

/** What one bar cell stands for: a member's status, done, cancelled, or (a focus graph, which has no agents) in progress. */
export type BarState = 'done' | 'cancelled' | MemberStatus | 'progress'

/** One bar cell: its state, and for an agent's member its colour. */
export type BarCell = { state: BarState; colour?: string }

/** The most cells the bar takes; past this many members, the cells no agent stands on are shares. */
export const MAX_BAR_CELLS = 16

const zero = (): PhaseCounts => ({ queue: 0, work: 0, review: 0, blocked: 0, terminal: 0 })
/** The phase counts across the board's runs, else the focus subtree with the focus itself. */
export function graphCounts(board: Board): { counts: PhaseCounts; total: number; cancelled: number } {
  const runs = board?.runs ?? []
  if (runs.length > 0) {
    const counts = zero()
    for (const run of runs) for (const r of ROLES) counts[r] += run.counts[r]

    return { counts, total: runTotal(counts), cancelled: runs.reduce((n, run) => n + (run.cancelled ?? 0), 0) }
  }
  const counts = board?.focus?.counts ?? zero()

  return { counts, total: runTotal(counts), cancelled: 0 }
}

const BAR_ORDER: readonly BarState[] = ['done', 'cancelled', 'progress', 'working', 'stuck', 'needsYou', 'finished', 'notStarted']

/**
 * The bar's cells, in state order: done, cancelled, then each member an agent works on (in colour-slot order, its
 * own colour), stuck, needing the person, finished, not started. A run's members the frontier does not list (outside
 * the call scope) count as not started. A focus graph has no agents: its members in work or review are `progress`.
 */
export function barCells(board: Board, colours: Record<string, number> = {}): BarCell[] {
  const runs = board?.runs ?? []
  const cells: BarCell[] = []
  if (runs.length > 0) {
    const working: { slot: number; colour: string }[] = []
    const slots = workingSlots(board, colours)
    for (const run of runs) {
      const cancelled = Math.min(run.cancelled ?? 0, run.counts.terminal)
      for (let i = 0; i < run.counts.terminal - cancelled; i++) cells.push({ state: 'done' })
      for (let i = 0; i < cancelled; i++) cells.push({ state: 'cancelled' })
      const members = run.members ?? []
      for (const m of members) {
        if (m.status === 'working') {
          const slot = slots.get(colourKey(run.id, m.id)) ?? 0
          working.push({ slot, colour: agentColour(slot) })
        } else cells.push({ state: m.status })
      }
      const unlisted = runTotal(run.counts) - run.counts.terminal - members.length
      for (let i = 0; i < unlisted; i++) cells.push({ state: 'notStarted' })
    }
    for (const w of working.sort((a, b) => a.slot - b.slot)) cells.push({ state: 'working', colour: w.colour })
  } else {
    const counts = board?.focus?.counts ?? zero()
    const add = (state: BarState, n: number) => {
      for (let i = 0; i < n; i++) cells.push({ state })
    }
    add('done', counts.terminal)
    add('progress', counts.work + counts.review)
    add('notStarted', counts.queue + counts.blocked)
  }

  return cells.sort((a, b) => BAR_ORDER.indexOf(a.state) - BAR_ORDER.indexOf(b.state))
}

/** The states a share may stand for past `max` cells: never a member an agent stands on, a stuck one or one needing the person. */
const SHARED: readonly BarState[] = ['done', 'cancelled', 'progress', 'finished', 'notStarted']

/**
 * At most `max` cells: the members an agent stands on, the stuck ones and those needing the person keep a cell each;
 * the other states share the room left by largest remainder, each with a member keeping at least one cell.
 */
export function fitBar(cells: readonly BarCell[], max = MAX_BAR_CELLS): BarCell[] {
  if (cells.length <= max) return [...cells]
  const own = cells.filter(c => !SHARED.includes(c.state)).slice(0, max)
  const groups = SHARED.map(state => ({ state, n: cells.filter(c => c.state === state).length })).filter(g => g.n > 0)
  const room = Math.max(groups.length, max - own.length)
  const total = groups.reduce((n, g) => n + g.n, 0)
  const share = (g: { n: number }): number => (g.n / total) * room
  const given = new Map(groups.map(g => [g.state, Math.max(1, Math.floor(share(g)))]))
  let left = room - [...given.values()].reduce((a, b) => a + b, 0)
  const byRemainder = [...groups].sort((a, b) => share(b) - (given.get(b.state) ?? 0) - (share(a) - (given.get(a.state) ?? 0)))
  for (let i = 0; left > 0 && byRemainder.length > 0; i++, left--) {
    const g = byRemainder[i % byRemainder.length]
    if (g !== undefined) given.set(g.state, (given.get(g.state) ?? 0) + 1)
  }
  const byCells = [...groups].sort((a, b) => (given.get(b.state) ?? 0) - (given.get(a.state) ?? 0))
  for (let i = 0; left < 0 && i < byCells.length * room; i++) {
    const g = byCells[i % byCells.length]
    if (g !== undefined && (given.get(g.state) ?? 0) > 1) {
      given.set(g.state, (given.get(g.state) ?? 0) - 1)
      left += 1
    }
  }
  const shared = groups.flatMap(g => Array.from({ length: given.get(g.state) ?? 0 }, (): BarCell => ({ state: g.state })))

  return [...shared, ...own].sort((a, b) => BAR_ORDER.indexOf(a.state) - BAR_ORDER.indexOf(b.state))
}

/** A bar cell drawn: `▰` filled, `▱` hollow, `⧄` cancelled; an agent's cell blinks to hollow in its own colour. */
export function barCell(cell: BarCell): Cell {
  switch (cell.state) {
    case 'done':
      return { text: '▰', tone: 'success' }
    case 'cancelled':
      return { text: '⧄', tone: 'error' }
    case 'working':
      return { text: '▰', tone: 'agent', color: cell.colour, blink: true, off: '▱' }
    case 'stuck':
      return { text: '▰', tone: 'error' }
    case 'needsYou':
      return { text: '▰', tone: 'needsYou' }
    case 'finished':
      return { text: '▰', tone: 'finished' }
    case 'progress':
      return { text: '▰', tone: 'text' }
    default:
      return { text: '▱', tone: 'dim' }
  }
}

/** The bar's cells drawn, neighbours of one look joined into one cell. */
export function barRun(cells: readonly BarCell[]): Cell[] {
  const out: Cell[] = []
  for (const cell of cells.map(barCell)) {
    const last = out[out.length - 1]
    if (last !== undefined && last.tone === cell.tone && last.color === cell.color && last.blink === cell.blink) {
      out[out.length - 1] = { ...last, text: last.text + cell.text, ...(last.off !== undefined ? { off: last.off + (cell.off ?? '') } : {}) }
    } else out.push(cell)
  }

  return out
}

// ---------------------------------------------------------------------------------------------
// Words: what the agents do, and the members by phase, in plain words.
// ---------------------------------------------------------------------------------------------

const SEAT_VERBS: Record<string, string> = {
  planner: 'planning',
  implementer: 'implementing',
  reviewer: 'reviewing',
  'test-author': 'writing tests',
}

const plural = (n: number, one: string, many: string): string => `${n} ${n === 1 ? one : many}`

/** `3 agents implementing`, or `3 agents working` when they do different things; empty with none. */
export function activityText(members: readonly BoardMember[]): string {
  const working = members.filter(m => m.status === 'working')
  if (working.length === 0) return ''
  const verbs = new Set(working.map(m => SEAT_VERBS[m.seat ?? ''] ?? 'working'))
  const verb = verbs.size === 1 ? [...verbs][0] : 'working'

  return `${plural(working.length, 'agent', 'agents')} ${verb}`
}

/** `3 planned · 2 in progress · 1 in review · 1 on hold · 2 done · 1 cancelled`, the zeros left out. */
export function countsText(counts: PhaseCounts, cancelled = 0): string {
  const done = counts.terminal - Math.min(cancelled, counts.terminal)
  const parts: [number, string][] = [
    [counts.queue, 'planned'],
    [counts.work, 'in progress'],
    [counts.review, 'in review'],
    [counts.blocked, 'on hold'],
    [done, 'done'],
    [Math.min(cancelled, counts.terminal), 'cancelled'],
  ]

  return parts
    .filter(([n]) => n > 0)
    .map(([n, word]) => `${n} ${word}`)
    .join(SEP)
}

/** `quiet 14m`, from a seat's last activity to `now`; empty when either is unknown. */
export function quietFor(lastSeenAt: string | undefined, now: string): string {
  const seen = Date.parse(lastSeenAt ?? '')
  const at = Date.parse(now)
  if (Number.isNaN(seen) || Number.isNaN(at)) return ''

  return `quiet ${Math.max(0, Math.floor((at - seen) / 60_000))}m`
}

// ---------------------------------------------------------------------------------------------
// The band's rows: the title with the bar, the status, then the agents (or the item last touched).
// ---------------------------------------------------------------------------------------------

const PHASE_ROLES = ['queue', 'work', 'review', 'terminal'] as const
const PHASE_NAMES = ['queue', 'work', 'review', 'done'] as const

/**
 * An item's own phase pipeline with the current phase lit: `queue → ◉ work → review → done`. The four phases of the
 * role sequence; a blocked item lights none and adds `◉ blocked`.
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

/** True when the band draws a lone leaf: no run, and a focus with no children. */
export function isLeaf(board: Board, graph: FocusBoard | null): boolean {
  return (board?.runs ?? []).length === 0 && graph !== null && !(graph.hasChildren ?? runTotal(graph.counts) > 1)
}

/**
 * Row 1, the title: the container's title (bold amber), and on the right the bar, one cell per member, for a graph with
 * members (a run, or a focus with children). With runs and no container read, the first run's name stands in. Null
 * with neither.
 */
export function titleRow(board: Board, container: ItemFacts | null | undefined, graph: FocusBoard | null, colours: Record<string, number> = {}): Row | null {
  const runs = board?.runs ?? []
  const title = container?.title ?? ''
  const first = runs[0]
  const left: Cell[] = title !== '' ? [{ text: title, tone: 'title' }] : first !== undefined ? [{ text: `run ${runLabel(first)}`, tone: 'dim' }] : []
  if (left.length === 0) return null
  const bar = board === null || board.error !== undefined || isLeaf(board, graph) ? [] : barRun(fitBar(barCells(board, colours)))

  return { key: 'title', left, right: bar }
}

/**
 * Row 2, the status. With runs: on the left what needs a look, first (`1 agent stuck`, `Coordinator not responding`,
 * `1 decision for you`, `Run paused: run budget`, `All agents finished · advance the items`), then what the agents do
 * (`3 agents implementing`, after `2 waves` when there are several); on the right the members by phase in plain words.
 * With a focus only: the container's type and phase, then its blocked work; on the right the members by phase, or a
 * lone leaf's own phases. `loading` before the first read, the fault after a failed one.
 */
export function statusRow(board: Board, container: ItemFacts | null | undefined, graph: FocusBoard | null): Row {
  if (board === null) return { key: 'status', left: [{ text: 'loading', tone: 'dim' }], right: [] }
  if (board.error !== undefined) return { key: 'status', left: [{ text: `board unavailable: ${board.error}`, tone: 'dim' }], right: [] }
  const runs = board.runs
  const { counts, cancelled } = graphCounts(board)
  if (runs.length === 0) {
    const leaf = isLeaf(board, graph)
    const facts = container ? (leaf ? [container.type] : [container.type, container.label]).filter(c => c !== '').join(SEP) : ''
    const left: Cell[] = facts === '' ? [] : [{ text: facts, tone: 'dim' }]
    const blocked = board.focus?.blocked ?? 0
    if (blocked > 0) left.push({ text: `${left.length > 0 ? SEP : ''}${blocked} waiting on another item`, tone: 'dim' })
    if (leaf) return { key: 'status', left, right: phasePipeline(graph?.role ?? '') }

    return { key: 'status', left, right: [{ text: countsText(counts), tone: 'dim' }] }
  }
  const members = runs.flatMap(run => run.members ?? [])
  const stuck = members.filter(m => m.status === 'stuck').length
  const decisions = members.filter(m => m.status === 'needsYou').length
  const first = runs[0]
  const alerts: Cell[] = []
  if (stuck > 0) alerts.push({ text: `${plural(stuck, 'agent', 'agents')} stuck`, tone: 'errorBold' })
  if (runs.some(run => coordStalled(run))) alerts.push({ text: 'Coordinator not responding', tone: 'errorBold' })
  if (decisions > 0) alerts.push({ text: `${plural(decisions, 'decision', 'decisions')} for you`, tone: 'needsYouBold' })
  const paused = runs.find(run => run.status === 'paused')
  if (paused !== undefined) alerts.push({ text: paused.pauseReason ? `Run paused: ${paused.pauseReason.replace(/-/g, ' ')}` : 'Run paused', tone: 'needsYouBold' })
  if (first !== undefined && runs.every(run => run.status !== 'open' || seatsDone(run)) && runs.some(seatsDone)) {
    alerts.push({ text: 'All agents finished · advance the items', tone: 'needsYouBold' })
  }
  const activity = activityText(members)
  const quiet: Cell[] = [
    ...(runs.length > 1 ? [{ text: `${runs.length} waves`, tone: 'dim' as const }] : []),
    ...(activity !== '' ? [{ text: activity, tone: 'dim' as const }] : []),
  ]
  const all = [...alerts, ...quiet]
  const left = all.map((cell, i) => (i === 0 ? cell : { ...cell, text: SEP + cell.text }))
  if (left.length === 0) left.push({ text: 'no agent working', tone: 'dim' })

  return { key: 'status', left, right: [{ text: countsText(counts, cancelled), tone: 'dim' }] }
}

/** The most agent rows the band draws; past them the last row ends `+N more`. */
export const MAX_AGENT_ROWS = 3

/**
 * Rows 3 and on, the agents: one row per member an agent stands on, the stuck ones first (red marker, `quiet 14m`),
 * then those worked on in colour-slot order, their marker blinking in the member's own colour (the same as its bar
 * cell): `◉ implementer   <title>`, with the run's name after the seat when there are several runs. At most three;
 * the last ends `+N more` when there are more.
 */
export function agentRows(board: Board, colours: Record<string, number> = {}): Row[] {
  const runs = board?.runs ?? []
  const now = board?.at ?? ''
  const slots = workingSlots(board, colours)
  const agents = runs.flatMap(run =>
    (run.members ?? [])
      .filter(m => m.status === 'working' || m.status === 'stuck')
      .map(m => ({ run, m, slot: slots.get(colourKey(run.id, m.id)) ?? -1 })),
  )
  agents.sort((a, b) => (a.m.status === b.m.status ? a.slot - b.slot : a.m.status === 'stuck' ? -1 : 1))
  const shown = agents.slice(0, MAX_AGENT_ROWS)
  const more = agents.length - shown.length

  return shown.map(({ run, m, slot }, i) => {
    const stuck = m.status === 'stuck'
    const marker: Cell = stuck
      ? { text: '◉', tone: 'error' }
      : { text: '◉', tone: 'agent', color: agentColour(slot), blink: true, off: '○' }
    const lead = [m.seat ?? 'agent', ...(runs.length > 1 ? [runLabel(run)] : []), ...(stuck ? [quietFor(m.lastSeenAt, now)] : [])]
      .filter(part => part !== '')
      .join(SEP)
    const title = m.title ?? m.id
    const left: Cell[] = [
      marker,
      { text: ` ${lead}`, tone: 'dim' },
      { text: GAP + title, tone: 'text' },
      ...(i === shown.length - 1 && more > 0 ? [{ text: `${SEP}+${more} more`, tone: 'dim' as const }] : []),
    ]

    return { key: `agent-${i}`, left, right: [], elastic: 2 }
  })
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
// The focus walk: the top-most item of the work the session touched, below the project root and
// below any grouping item (a backlog bucket).
// ---------------------------------------------------------------------------------------------

/**
 * One item as the focus walk needs it, from an `items_read` `get` result's `data`. `grouping` marks an item that
 * only groups work (isGrouping).
 */
export type FocusItem = { id: string; parentId: string | null; depth: number; title: string; type: string; label: string; role: string; grouping?: boolean }

/** The types, and the tag, of an item that only groups work: the global floor's `container` and `project`. */
const GROUPING_TYPES: readonly string[] = ['container', 'project']
const GROUPING_TAG = 'container'

/**
 * True for an item that only groups work, never work itself: typed `container` or `project`, or tagged `container`.
 * The focus walk never climbs into one, so a task under a backlog bucket (`Bugs`) focuses its own feature, not the
 * bucket's whole backlog; and a grouping focus (the session touched the bucket itself) does not count itself.
 */
export function isGrouping(data: unknown): boolean {
  if (!isObj(data)) return false
  if (GROUPING_TYPES.includes(str(data.type).toLowerCase())) return true

  return Array.isArray(data.tags) && data.tags.some(t => typeof t === 'string' && t.toLowerCase() === GROUPING_TAG)
}

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
    ...(isGrouping(data) ? { grouping: true } : {}),
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
 * reached. A parent that only groups work (isGrouping) ends the walk below it. `known` answers an id
 * an earlier walk already resolved, which ends this walk there with no read (below it, when it
 * resolved to a grouping item: only a grouping item resolves to one). Null when the first read fails
 * or answers no item; a later failed hop settles on the last item read.
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
    if (cached?.grouping === true) break
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
    if (next === null || next.grouping === true) break
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
