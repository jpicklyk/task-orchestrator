package io.github.jpicklyk.mcptask.domain.model

/**
 * Represents the status of a project.
 */
enum class ProjectStatus {
    PLANNING,
    IN_DEVELOPMENT,
    COMPLETED,
    ARCHIVED;

    companion object {
        /**
         * Converts a string to a ProjectStatus enum value.
         * @param status The string representation of the status.
         * @return The ProjectStatus enum value or null if not found.
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
}