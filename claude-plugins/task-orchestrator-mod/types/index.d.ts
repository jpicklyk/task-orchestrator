// State contract of task-orchestrator-mod: every `$.state` key the plugin uses, declared once.
// Self-contained (no imports). One region per feature: a work item edits only its own region,
// exported value types above, state keys inside PluginState below.

/** The feature folders under src/, one per work item. */
export type TaskOrchestratorModFeature = 'graph-data' | 'graph-pane' | 'band' | 'retro' | 'phase-guard' | 'call-shape'

// ── graph-data (T2 32492bfa) types ──
/** One work item of a graph snapshot, keyed by its 8-hex short id. `parentId` is null for the scope's top node. */
export interface GraphNode {
  id: string
  parentId: string | null
  title: string
  role: string
  statusLabel?: string
  type?: string
  priority?: string
  depth: number
  /** Short plan label (properties.planLabel, 1-12 chars), e.g. T3. */
  planLabel?: string
  /** Overview snapshots only: the node's full-subtree descendant counts by role (zero counts left out). */
  childCounts?: Record<string, number>
}

/** A dependency, normalized so `from` blocks `to` (IS_BLOCKED_BY folded into BLOCKS with the ends swapped). */
export interface GraphEdge {
  from: string
  to: string
  type: 'BLOCKS' | 'RELATES_TO'
  /** The role at which `from` unblocks `to` (the server's effectiveUnblockRole), when it reports one. */
  unblockAt?: string
}

/** Required-note progress of one seat in the current phase. */
export interface SeatProgress {
  seat: string
  required: number
  filled: number
}

/** Gate status of a work/review node; `required`/`filled` count the current phase's required schema rows. */
export interface GateInfo {
  canAdvance: boolean
  phase: string
  missing: string[]
  required: number
  filled: number
  /** Per-seat progress of the current phase's required rows; absent when the schema has no seats. */
  seats?: SeatProgress[]
}

export interface GraphSnapshot {
  /** The subtree root the snapshot covers; null only when no scope could be resolved. */
  scopeId: string | null
  rootId: string | null
  nodes: GraphNode[]
  edges: GraphEdge[]
  /** Edge endpoints outside the node set (out-of-subtree, or cut by the node cap). */
  external: Record<string, { title: string; role: string }>
  gates: Record<string, GateInfo>
  takenAt: number
  /** True when the subtree had more items than the node cap and only the shallowest were kept. */
  truncated: boolean
  /** True for the cheap project-root snapshot: the root plus its direct children with roll-ups, no edges, gates or labels. */
  overview?: boolean
  /** The scope's path from the project root to the scope itself (root first), for the breadcrumb. */
  trail?: { id: string; title: string }[]
  /** Set when any call failed (the snapshot is then partial) or no scope could be resolved. */
  error?: string
  /**
   * Work/review items missing required notes with no role transition in the last STALL_MS, with their
   * missing note keys. Absent on the overview and when the session-resume read failed.
   */
  stalled?: Record<string, string[]>
  /** When the earliest listed-but-recently-moved item would cross STALL_MS (ms); the refresh re-snapshots then. */
  stallDueAt?: number
}

export interface GraphStatus {
  refreshing: boolean
  lastError?: string
  liveSource: 'sse' | 'poll' | 'none'
}

/** An open decision on an item, from a decision.raised SSE event (graph-data/runs.ts). */
export interface GraphDecision {
  decisionId: string
  severity?: string
  class?: string
}

/** One open or paused run of the project, from run.* SSE events (graph-data/runs.ts). */
export interface GraphRun {
  status: 'open' | 'paused'
}
// ── graph-pane (T3 a47dcd6f) types ──
/** What the /to-graph detail section shows: the read-only context summary of one node, as plain lines. */
export interface GraphDetail {
  /** The node's (short) id. */
  itemId: string
  lines: string[]
  /** The item's full UUID, from the get view, when it could be read (Copy UUID). */
  uuid?: string
}

/** One agent seen calling a TO tool about an item: `agentId` is 'main' on the main loop. */
export interface GraphWorker {
  agentId: string
  seat: string
  model?: string
  /** When this worker last touched the item (ms). */
  at: number
}

/** One gate-blocked advance of this session on an item: when, the missing note keys, the target role (when known). */
export interface GraphGateBlock {
  at: number
  missing: string[]
  target?: string
}

/** Who is working on which item, which items changed recently, and which were gate-blocked; all expire (activity.ts). */
export interface GraphActivity {
  working: Record<string, GraphWorker[]>
  changed: Record<string, number>
  /** Absent in a value written before T12a (a hot reload keeps the old value). */
  blocked?: Record<string, GraphGateBlock>
}

/** What agent.spawn told us about a subagent, keyed by its agent id. */
export interface GraphAgentInfo {
  seat: string
  model?: string
}

// ── band (T4 ec1e2f91) types ──
// ── retro (T5 0f4fe024) types ──
/**
 * What the retrospective feature remembers for the session (the in-process form of the command hooks'
 * per-session retro marker), plus the queued dispatch. The cross-session dedupe is the server's `retro`
 * run mark, so nothing here is keyed by root or survives the session.
 */
export interface RetroState {
  /** Run ids of successful closes this session recorded without evaluating them (a subagent, or no project rootId); capped. */
  closes?: string[]
  /** A successful close was recorded; the no-rootId Stop backstop reads it. */
  sawClose?: boolean
  /** Runs already nudged this session (never nudged twice). */
  nudged?: string[]
  /** The no-rootId backstop has nudged once this session. */
  backstopNudged?: boolean
  /** Runs whose `retro` mark this session won, queued for the next clear Stop; null/absent when none. */
  pendingDispatch?: string[] | null
  /** The last retrospective agent the mod spawned. */
  dispatched?: { agentId?: string; runs: string[]; at: number }
  /** Ids of agents the mod spawned for a retrospective; their TO calls never count. */
  retroAgents?: string[]
}
// ── phase-guard (T6 26246238) types ──
/** What the phase guard remembers about one subagent: the items it entered, the role it entered each in, and its SubagentStop block count. */
export interface PhaseGuardEntry {
  items: string[]
  blocks: number
  enteredRoles: Record<string, string>
}
// ── call-shape (T7 cedcfc11) types ──

declare module 'claude-code' {
  interface PluginState {
    'task-orchestrator-mod': {
      // ── graph-data (T2 32492bfa) state ──
      /** The item id in view; null means the project root. */
      graphScope: string | null
      /** 'auto' follows this session's own TO activity; 'pinned' (an explicit id, This feature, Whole project, a node click) never moves. Default 'auto'. */
      graphScopeMode: 'auto' | 'pinned'
      graphSnapshot: GraphSnapshot | null
      graphStatus: GraphStatus
      /** Consumers currently showing the graph (pane, band); the live source runs while this is above 0. */
      graphSubscribers: number
      /** Bumped to ask for a debounced re-snapshot. */
      graphRefreshRequest: number
      /** Bumped to restart the live source (SSE/poll) and re-snapshot. */
      graphReconnectRequest: number
      /** Items whose last change came from another session over SSE (not this session's echo): short id -> when (ms). Capped at 200. */
      graphRemote: Record<string, number>
      /** Open decisions seen over SSE: short item id -> decision. Capped at 200. */
      graphDecisions: Record<string, GraphDecision>
      /** The project's open and paused runs seen over SSE: run id -> status. */
      graphRuns: Record<string, GraphRun>
      // ── graph-pane (T3 a47dcd6f) state ──
      /** The /to-graph pane has counted itself once in graphSubscribers; a repeat /to-graph neither double-counts nor leaks. */
      graphPaneOpen: boolean
      /** The node whose context summary the pane shows, or null. */
      graphDetail: GraphDetail | null
      /** Live workers per item and recently-changed items; the render never reads the clock. */
      graphActivity: GraphActivity
      /** Subagent seat/model by agent id (capped), recorded at agent.spawn. */
      graphAgents: Record<string, GraphAgentInfo>
      /** The pane's single `Show done steps` toggle. */
      graphShowDone: boolean
      // ── band (T4 ec1e2f91) state ──
      /** Hide pressed (or /to-band toggled off) for this session; the status line stays on. */
      bandHidden: boolean
      /** The band has counted itself once in graphSubscribers; survives a hot reload so it is not counted twice. */
      bandSubscribed: boolean
      // ── retro (T5 0f4fe024) state ──
      /** Session-scoped retrospective bookkeeping; replaces the tmpdir retro-<key>.json marker while the mod owns retro handling. */
      retro: RetroState
      // ── phase-guard (T6 26246238) state ──
      /** Per subagent (keyed by agent id): replaces the tmpdir `phase-guard-*.json` marker files. */
      phaseGuardAgents: StateFamily<PhaseGuardEntry>
      // ── call-shape (T7 cedcfc11) state ──
    }
  }
}
