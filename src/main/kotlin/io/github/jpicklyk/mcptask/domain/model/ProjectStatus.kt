package io.github.jpicklyk.mcptask.domain.model

/**
 * Represents the status of a project.
 *
 * v2.0: Additional orchestration statuses added for config-driven workflows.
 * When .taskorchestrator/config.yaml exists, validation uses config instead of these enum values.
 */
enum class ProjectStatus {
    // v1.0 original statuses
    PLANNING,
    IN_DEVELOPMENT,
    COMPLETED,
    ARCHIVED,

    // v2.0 orchestration statuses
    ON_HOLD,     // Project temporarily paused
    CANCELLED;   // Project cancelled/abandoned

    companion object {
        /**
         * Converts a string to a ProjectStatus enum value (case-insensitive).
         * Supports both underscore and hyphen separators.
         * @param status The string representation of the status
         * @return The ProjectStatus enum value or null if not found
         */
        fun fromString(status: String): ProjectStatus? {
            return try {
                valueOf(status.uppercase().replace('-', '_'))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Gets the default status (PLANNING).
         */
        fun getDefault(): ProjectStatus = PLANNING
    }

    /**
     * Returns the string representation of this status in uppercase with underscores.
     */
    override fun toString(): String = name
}