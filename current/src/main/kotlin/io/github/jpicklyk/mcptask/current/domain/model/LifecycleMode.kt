package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Controls how a work item transitions when its lifecycle state changes.
 *
 * - AUTO: The item advances and closes automatically when all work is done.
 * - MANUAL: The item requires explicit user/agent action to close.
 * - AUTO_REOPEN: The item can be automatically reopened if new work is detected.
 * - PERMANENT: The item never closes automatically; it must be manually closed or archived.
 */
enum class LifecycleMode {
    AUTO,
    MANUAL,
    AUTO_REOPEN,
    PERMANENT;

    companion object {
        /**
         * Parse a [LifecycleMode] from a string value, case-insensitively.
         * Handles hyphenated forms (e.g., "auto-reopen" → [AUTO_REOPEN]).
         *
         * @return The matching [LifecycleMode], or null if no match is found.
         */
        fun fromString(value: String): LifecycleMode? {
            val normalized = value.trim().uppercase().replace('-', '_')
            return entries.find { it.name == normalized }
        }
    }
}
