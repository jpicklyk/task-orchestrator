package io.github.jpicklyk.mcptask.current.application.service

/**
 * Provides trigger-to-label mappings for status labels on role transitions.
 *
 * Labels are resolved from `.taskorchestrator/config.yaml` under the `status_labels` section.
 * When no config is present, hardcoded defaults are used:
 * - start -> "in-progress"
 * - complete -> "done"
 * - block -> "blocked"
 * - cancel -> "cancelled"
 * - cascade -> "done"
 * - resume -> null (preserves pre-block label)
 * - reopen -> null (clears label)
 *
 * Label precedence in AdvanceItemTool:
 * 1. resolution.statusLabel (hardcoded cancel/reopen) — if non-null, use it
 * 2. Config-driven label from resolveLabel(trigger) — if resolution was null
 * 3. For resume: existing applyTransition logic preserves pre-block label
 */
interface StatusLabelService {
    /**
     * Returns the status label for the given trigger, or null if the trigger
     * should not set a label (e.g., resume, reopen).
     */
    fun resolveLabel(trigger: String): String?
}

/**
 * No-op implementation that returns hardcoded defaults.
 * Used when no config file is present or as a fallback.
 */
object NoOpStatusLabelService : StatusLabelService {
    private val defaults =
        mapOf(
            "start" to "in-progress",
            "complete" to "done",
            "block" to "blocked",
            "cancel" to "cancelled",
            "cascade" to "done"
            // resume and reopen intentionally absent — null means no label override
        )

    override fun resolveLabel(trigger: String): String? = defaults[trigger]
}
