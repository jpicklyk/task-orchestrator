package io.github.jpicklyk.mcptask.current.domain.model

/**
 * The 5 fixed roles in Current's workflow model.
 * Roles are the canonical state — statuses are optional display labels.
 * Ordering: QUEUE < WORK < REVIEW < TERMINAL. BLOCKED is orthogonal.
 */
enum class Role {
    QUEUE, WORK, REVIEW, BLOCKED, TERMINAL;

    companion object {
        /** Parse from string, case-insensitive. Returns null if not recognized. */
        fun fromString(value: String): Role? = entries.find { it.name.equals(value, ignoreCase = true) }

        /** All valid role names in lowercase for validation */
        val VALID_NAMES: Set<String> = entries.map { it.name.lowercase() }.toSet()

        /** The sequential progression roles (excludes BLOCKED which is orthogonal) */
        val PROGRESSION: List<Role> = listOf(QUEUE, WORK, REVIEW, TERMINAL)

        /**
         * Whether [current] is at or beyond [threshold] in the progression sequence.
         * BLOCKED never satisfies a sequential threshold.
         * Used for unblockAt dependency gating.
         */
        fun isAtOrBeyond(current: Role, threshold: Role): Boolean {
            if (current == BLOCKED) return false
            val currentIndex = PROGRESSION.indexOf(current)
            val thresholdIndex = PROGRESSION.indexOf(threshold)
            if (currentIndex < 0 || thresholdIndex < 0) return false
            return currentIndex >= thresholdIndex
        }
    }
}
