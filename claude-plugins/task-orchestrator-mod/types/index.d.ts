// State contract of task-orchestrator-mod: every `$.state` key the plugin uses, declared once.
// Self-contained (no imports). One region per feature: a work item edits only its own region,
// exported value types above, state keys inside PluginState below.

/** The feature folders under src/, one per work item. */
export type TaskOrchestratorModFeature = 'graph-data' | 'graph-pane' | 'band' | 'retro' | 'phase-guard' | 'call-shape'

// ── graph-data (T2 32492bfa) types ──
// ── band (T4 ec1e2f91) types ──
// ── retro (T5 0f4fe024) types ──
// ── phase-guard (T6 26246238) types ──
// ── call-shape (T7 cedcfc11) types ──

declare module 'claude-code' {
  interface PluginState {
    'task-orchestrator-mod': {
      // ── graph-data (T2 32492bfa) state ──
      // ── band (T4 ec1e2f91) state ──
      // ── retro (T5 0f4fe024) state ──
      // ── phase-guard (T6 26246238) state ──
      // ── call-shape (T7 cedcfc11) state ──
    }
  }
}
