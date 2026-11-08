// The plugin's $.state contract (plugin.json `types`). The engine checks every key the module names.

/** Director mode is on since this ISO instant; null while off. */
export type DirectorState = { since: string } | null

/** One open or paused run on the board, from `runs_read` list and frontier (director profile). */
export type BoardRun = {
  id: string
  label: string
  title: string
  status: 'open' | 'paused'
  pauseReason?: string
  counts: { queue: number; work: number; review: number; blocked: number; terminal: number }
  ready: number
  inFlight: number
  waiting: number
  stalled: number
  coordinatorStalled: boolean
  tokens: number
}

/** The band's board: refreshed while director mode is on, null while off. */
export type Board = {
  at: string
  runs: BoardRun[]
  decisions: { open: number; overdue: number } | null
  blocked: number | null
  error?: string
} | null

declare module 'claude-code' {
  interface PluginState {
    'orchestration-mode': {
      director: DirectorState
      board: Board
    }
  }
}
