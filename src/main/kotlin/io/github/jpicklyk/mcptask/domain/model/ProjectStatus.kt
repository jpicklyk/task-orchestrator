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
         * Supports multiple format variations:
         * - Hyphen-separated: "in-development", "on-hold"
         * - Underscore-separated: "in_development", "on_hold"
         * - No separator: "indevelopment", "onhold"
         *
         * @param value The string representation of the status
         * @return The ProjectStatus enum value, or null if invalid
         */
        fun fromString(value: String): ProjectStatus? {
            // Normalize the input by converting to uppercase and replacing hyphens
            val normalized = value.uppercase().replace('-', '_')

            return try {
                valueOf(normalized)
            } catch (_: IllegalArgumentException) {
                // Try compound word variations (no separator) and spelling variants
                when (normalized.replace("_", "")) {
                    "INDEVELOPMENT" -> IN_DEVELOPMENT
                    "ONHOLD" -> ON_HOLD
                    "CANCELED" -> CANCELLED  // US spelling variant
                    else -> null
                }
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