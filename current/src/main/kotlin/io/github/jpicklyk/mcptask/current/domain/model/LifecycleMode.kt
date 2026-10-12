package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Controls how a work item's lifecycle is managed, particularly cascade behavior
 * when children reach terminal state.
 *
 * - AUTO: default behavior — parent cascades to terminal when all children are terminal
 * - MANUAL: suppress terminal cascade — parent must be explicitly completed
 * - PERMANENT: never auto-terminate — parent stays in its current role regardless of children
 * - NONE: lifecycle-free: an item pinned to such a schema has no role at all (stored as a NULL `role` column with
 *   pin lifecycle `none`); no trigger, gate or cascade applies to it. Never carried by
 *   [io.github.jpicklyk.mcptask.current.domain.lifecycle.SchemaFacts] (see `SchemaFacts.of`).
 */
enum class LifecycleMode {
    AUTO,
    MANUAL,
    PERMANENT,
    NONE;

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
