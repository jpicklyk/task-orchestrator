package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Controls how a work item's lifecycle is managed, particularly cascade behavior
 * when children reach terminal state.
 *
 * - AUTO: default behavior — parent cascades to terminal when all children are terminal
 * - MANUAL: suppress terminal cascade — parent must be explicitly completed
 * - AUTO_REOPEN: cascade + reopen parent when a new child is created under a terminal parent
 * - PERMANENT: never auto-terminate — parent stays in its current role regardless of children
 */
enum class LifecycleMode {
    AUTO,
    MANUAL,
    AUTO_REOPEN,
    PERMANENT;

    companion object {
        /**
         * Parse a string into a [LifecycleMode], case-insensitively and treating hyphens as underscores.
         * Returns null if the value does not match any known mode.
         */
        fun fromString(value: String): LifecycleMode? {
            val normalized = value.trim().uppercase().replace('-', '_')
            return entries.firstOrNull { it.name == normalized }
        }
    }
}
