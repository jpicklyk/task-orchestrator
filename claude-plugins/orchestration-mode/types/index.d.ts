// The plugin's $.state contract (plugin.json `types`). The engine checks every key the module names.
// `$.store` holds one key beside these, `bandHidden` (boolean), the `/to-band` choice kept across
// reloads and resumes; the `bandHidden` atom below mirrors it so the band redraws on a toggle.

/** Director mode is on since this ISO instant; null while off. */
export type DirectorState = { since: string } | null

/**
 * This session is the coordinator of `runId` since this ISO instant: seen from its own `runs_write`
 * `open` (`source: 'tool'`) or from the plugin's `coordinator.json` session marker (`'marker'`).
 * Null while it coordinates no run. The coordinator token is never kept.
 */
export type CoordinatorState = { runId: string; label: string; since: string; source: 'tool' | 'marker' } | null

/** The focus item: the top-most item below the project root of the last item this session touched. */
export type FocusState = { itemId: string; title: string; since: string } | null

/** Members per phase. */
export type PhaseCounts = { queue: number; work: number; review: number; blocked: number; terminal: number }

/** One open or paused run on the board, from `runs_read` list and frontier (director profile). */
export type BoardRun = {
  id: string
  label: string
  title: string
  status: 'open' | 'paused'
  pauseReason?: string
  counts: PhaseCounts
  ready: number
  inFlight: number
  waiting: number
  stalled: number
  coordinatorStalled: boolean
  tokens: number
}

/** The focus graph on the board, read while no run is open (`items_read` get, overview, blocked). */
export type FocusBoard = {
  itemId: string
  title: string
  /** The focus item's own phase label (`statusLabel`, else its role). */
  label: string
  counts: PhaseCounts
  /** Blocked work under the focus (`items_read` `blocked` total); null when the read failed. */
  blocked: number | null
  firstBlocked?: { id: string; blocker?: string }
}

/** The band's board: the last read, null before the first. */
export type Board = {
  at: string
  runs: BoardRun[]
  decisions: { open: number; overdue: number } | null
  blocked: number | null
  focus?: FocusBoard | null
  error?: string
} | null

declare module 'claude-code' {
  interface PluginState {
    'orchestration-mode': {
      director: DirectorState
      coordinator: CoordinatorState
      focus: FocusState
      bandHidden: boolean
      board: Board
    }
  }
}
