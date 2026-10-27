package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.Role

/**
 * Provides trigger-to-label mappings for status labels on role transitions.
 *
 * Labels are resolved from `.taskorchestrator/config.yaml` under the `status_labels` section.
 * When no config is present, hardcoded defaults are used:
 * - start -> "in-progress"
 * - complete -> "done"
 * - block -> "blocked"
 * - cancel -> "cancelled"
 * - cascade -> "done" (the terminal cascade only; start and reopen cascades use the start and reopen labels)
 * - resume, hold, reopen -> null (no label override)
 *
 * The key a transition looks up is [statusLabelKey]; the label is then applied by the advance
 * pipeline's rule: an explicit label wins, else entering BLOCKED preserves the item's label, else the
 * label is cleared.
 */
interface StatusLabelService {
    /**
     * Returns the status label for the given trigger, or null if the trigger
     * should not set a label (e.g., resume, reopen).
     */
    fun resolveLabel(trigger: String): String?
}

/**
 * The `status_labels` key a transition looks up: `"cascade"` for the terminal cascade
 * ([Trigger.Cascade.Complete]); `"start"` and `"reopen"` for the start and reopen cascades, which move
 * a parent into WORK the way those user triggers would (bug f328cceb: they used `"cascade"` and
 * stamped the terminal `done` label on a working parent); `"complete"` for a `start` whose target is
 * TERMINAL (a start that actually completes the item, bug 100da214); else the trigger's wire name.
 */
fun statusLabelKey(
    trigger: Trigger,
    target: Role
): String =
    when {
        trigger is Trigger.Cascade.Complete -> Trigger.Cascade.WIRE
        trigger == Trigger.Cascade.Start -> Trigger.User.START.wire
        trigger == Trigger.Cascade.Reopen -> Trigger.User.REOPEN.wire
        trigger == Trigger.User.START && target == Role.TERMINAL -> Trigger.User.COMPLETE.wire
        else -> trigger.wire
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
            // resume, hold and reopen intentionally absent — null means no label override
        )

    override fun resolveLabel(trigger: String): String? = defaults[trigger]
}
