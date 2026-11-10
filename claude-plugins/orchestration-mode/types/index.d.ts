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
/**
 * The session's focus: the touched item (the fact) and the focus its walk reached (a cache, walked again each load).
 * `by: 'write'` marks a focus a write set; a load drops one without it (an earlier version also moved it on a read).
 */
export type FocusState = { itemId: string; title: string; since: string; touched: ItemFacts; by?: 'write' } | null

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
  /** The members the frontier lists (in flight, ready, waiting), each with the status the band draws; absent before the first frontier read. */
  members?: BoardMember[]
  /** How many terminal members a cancel left (frontier `cancelled`, counted in `counts.terminal`); absent from an older server. */
  cancelled?: number
  /** The server's coordinator liveness flag as read; the band draws `coordStalled(run)`, which applies the director rule. */
  coordinatorStalled: boolean
  /** The run's `hints.method` is `A` and `hints.phase` is `launched`: a Workflow runs it and nothing calls under the coordinator token. */
  workflowLaunched?: boolean
  /** The item the run is scoped to (`scopeId`), absent for a run over its whole root. */
  scopeId?: string
  /** The run's depth-0 root (`rootId`). */
  rootId?: string
  /** The scope item (else the root) as read, so line 1 names the run's container when the session has no focus. */
  container?: ItemFacts | null
  tokens: number
}

/**
 * What a run member is doing, as the band draws it: an agent works on it now (`working`), its agent went quiet
 * (`stuck`), it waits on a decision (`needsYou`), its last agent finished and it waits to be advanced (`finished`), or
 * nothing has started on it (`notStarted`, which includes waiting on another item).
 */
export type MemberStatus = 'working' | 'stuck' | 'needsYou' | 'finished' | 'notStarted'

/** One non-terminal run member from the frontier: its short id, status, role, and for an agent's member its seat, title and last activity. */
export type BoardMember = {
  id: string
  status: MemberStatus
  role?: string
  seat?: string
  title?: string
  lastSeenAt?: string
}

/** The focus graph on the board (`items_read` get, overview, blocked), read whenever a focus is set. */
export type FocusBoard = {
  itemId: string
  /** Phase counts of the focus subtree, the focus itself included. */
  counts: PhaseCounts
  /** The focus item's own role, for the phase pipeline a focus with no children shows. */
  role?: string
  /** True when the focus has children: line 2 then shows its subtree's bar and counts, else its own phases. */
  hasChildren?: boolean
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
  /** No longer read (it counted every session's decisions); kept so a board saved by an earlier version still loads. */
  decisions?: { open: number; overdue: number } | null
  focus?: FocusBoard | null
  /**
   * The agent colour slot of each member, keyed `<runId>:<item short id>`: kept from read to read so an item keeps its
   * colour from stage to stage, and two members worked on at once never share one while a slot is free.
   */
  colours?: Record<string, number>
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
      /** Run ids this session or one of its subagents opened or named, newest last (at most 20). */
      watchedRuns: string[]
    }
  }
}
