// State contract of task-orchestrator-mod: every `$.state` key the plugin uses, declared once.
// Self-contained (no imports). One region per feature: a work item edits only its own region,
// exported value types above, state keys inside PluginState below.

/** The feature folders under src/, one per work item. */
export type TaskOrchestratorModFeature = 'graph-data' | 'graph-pane' | 'band' | 'retro' | 'phase-guard' | 'call-shape'

// ── graph-data (T2 32492bfa) types ──
/** One work item of a graph snapshot. `parentId` is null for the scope's top node. */
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
  /** Set when any call failed (the snapshot is then partial) or no scope could be resolved. */
  error?: string
}

export interface GraphStatus {
  refreshing: boolean
  lastError?: string
  liveSource: 'sse' | 'poll' | 'none'
}
// ── graph-pane (T3 a47dcd6f) types ──
/** What the /to-graph detail section shows: the read-only get_context summary of one node, as plain lines. */
export interface GraphDetail {
  itemId: string
  lines: string[]
}

/** One agent seen calling a TO tool about an item: `agentId` is 'main' on the main loop. */
export interface GraphWorker {
  agentId: string
  seat: string
  model?: string
  /** When this worker last touched the item (ms). */
  at: number
}

/** Who is working on which item, and which items changed recently; both expire (activity.ts). */
export interface GraphActivity {
  working: Record<string, GraphWorker[]>
  changed: Record<string, number>
}

/** What agent.spawn told us about a subagent, keyed by its agent id. */
export interface GraphAgentInfo {
  seat: string
  model?: string
}

// ── band (T4 ec1e2f91) types ──
// ── retro (T5 0f4fe024) types ──
/** What the retrospective feature remembers for the session: the in-process form of the retro-<key>.json marker, plus the queued dispatch. */
export interface RetroState {
  /** A lone terminal was seen and no run boundary has surfaced it yet (the Stop backstop reads it). */
  sawTerminal?: boolean
  lastTerminalAt?: number
  /** Roots recorded by lone terminals, for the backstop nudge. */
  pendingRoots?: string[]
  /** Items that reached terminal since the last directive; the substance gate. */
  terminalCount?: number
  /** When the last directive (nudge or dispatch) was issued or acked. */
  handledAt?: number
  /** Roots already surfaced, newest last, capped. */
  rootUuids?: string[]
  /** Roots of a queued dispatch the mod spawns at the next clear Stop; null/absent when none. */
  pendingDispatch?: string[] | null
  /** The last retrospective agent the mod spawned. */
  dispatched?: { agentId?: string; roots: string[]; at: number }
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
      graphSnapshot: GraphSnapshot | null
      graphStatus: GraphStatus
      /** Consumers currently showing the graph (pane, band); the live source runs while this is above 0. */
      graphSubscribers: number
      /** Bumped to ask for a debounced re-snapshot. */
      graphRefreshRequest: number
      /** Bumped to restart the live source (SSE/poll) and re-snapshot. */
      graphReconnectRequest: number
      // ── graph-pane (T3 a47dcd6f) state ──
      /** The /to-graph pane has counted itself once in graphSubscribers; a repeat /to-graph neither double-counts nor leaks. */
      graphPaneOpen: boolean
      /** The node whose get_context summary the pane shows, or null. */
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
      /** The `itemId::key` pairs skill-enforcement already warned about this session. */
      skillWarned: string[]
      // ── call-shape (T7 cedcfc11) state ──
    }
  }
}
