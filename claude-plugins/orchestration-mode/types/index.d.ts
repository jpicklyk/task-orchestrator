// The plugin's $.state contract (plugin.json `types`). The engine checks every key the module names.
// `bandHidden` is the `/to-band` choice for this session only (it survives a reload and a resume);
// userConfig `band` is the default for every session, and the mod keeps nothing in `$.store`.

/** Director mode is on since this ISO instant; null while off. */
export type DirectorState = { since: string } | null

/**
 * This session is the coordinator of `runId` since this ISO instant: seen from its own `runs_write`
 * `open` (`source: 'tool'`) or from the plugin's `coordinator.json` session marker (`'marker'`).
 * Null while it coordinates no run. The coordinator token is never kept.
 */
export type CoordinatorState = { runId: string; label: string; since: string; source: 'tool' | 'marker' } | null

/** What a band line shows of one item: its id, title, type and phase label (`statusLabel`, else its role). */
export type ItemFacts = { itemId: string; title: string; type: string; label: string }

/**
 * The focus: `itemId`/`title` are the top-most item below the project root of the last item this
 * session touched, `touched` is that touched item itself, as its walk read it. Null until a TO call
 * names an item.
 */
export type FocusState = { itemId: string; title: string; since: string; touched: ItemFacts } | null

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

/** The focus graph on the board (`items_read` get, overview, blocked), read whenever a focus is set. */
export type FocusBoard = {
  itemId: string
  /** Phase counts of the focus subtree, the focus itself included. */
  counts: PhaseCounts
  /** Blocked work under the focus (`items_read` `blocked` total); null when the read failed. */
  blocked: number | null
  /** Line 1: the focus when it has children, else its parent below the project root, else the lone leaf itself. */
  container: ItemFacts | null
  /** Line 3: the touched item as last read, with its first open blocker's short id when it is blocked. */
  touched: (ItemFacts & { blockedBy?: string }) | null
}

/** The band's board: the last read, null before the first. */
export type Board = {
  at: string
  runs: BoardRun[]
  decisions: { open: number; overdue: number } | null
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
