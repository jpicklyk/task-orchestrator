package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Controls how a work item's lifecycle is managed after reaching terminal state.
 *
 * - AUTO: item lifecycle is managed automatically by the system
 * - MANUAL: transitions must be triggered explicitly by the user or agent
 * - AUTO_REOPEN: item is automatically reopened when conditions are met
 * - PERMANENT: item stays in its terminal state permanently; cannot be reopened
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
