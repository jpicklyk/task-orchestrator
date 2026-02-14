package io.github.jpicklyk.mcptask.domain.model

/**
 * Semantic role classification for workflow statuses.
 * Ordering is hardcoded and immutable — config only controls status->role mapping.
 *
 * Progression: QUEUE(0) -> WORK(1) -> REVIEW(2) -> TERMINAL(3)
 * BLOCKED(-1) is lateral — never satisfies progression checks.
 */
enum class StatusRole(val ordinal_: Int) {
    QUEUE(0),
    WORK(1),
    REVIEW(2),
    TERMINAL(3),
    BLOCKED(-1);

    companion object {
        /**
         * Parse a role string to StatusRole enum.
         * Returns null for unrecognized values.
         */
        fun fromString(role: String): StatusRole? {
            return entries.find { it.name.equals(role, ignoreCase = true) }
        }

        /**
         * Check if [currentRole] is at or beyond [threshold] in the progression order.
         *
         * Rules:
         * - BLOCKED never satisfies any progression check (returns false) unless threshold is also BLOCKED
         * - TERMINAL is always at or beyond any non-BLOCKED threshold
         * - For sequential roles: current.ordinal_ >= threshold.ordinal_
         */
        fun isRoleAtOrBeyond(currentRole: StatusRole, threshold: StatusRole): Boolean {
            // BLOCKED only satisfies BLOCKED threshold
            if (threshold == BLOCKED) return currentRole == BLOCKED
            // BLOCKED never satisfies sequential thresholds
            if (currentRole == BLOCKED) return false
            // Sequential comparison
            return currentRole.ordinal_ >= threshold.ordinal_
        }

        /**
         * Convenience overload that accepts nullable string role names.
         * Returns false if either role string is null or unrecognized.
         */
        fun isRoleAtOrBeyond(currentRoleStr: String?, thresholdStr: String?): Boolean {
            val current = currentRoleStr?.let { fromString(it) } ?: return false
            val threshold = thresholdStr?.let { fromString(it) } ?: return false
            return isRoleAtOrBeyond(current, threshold)
        }

        /** All valid role names as lowercase strings, for validation. */
        val VALID_ROLE_NAMES: Set<String> = entries.map { it.name.lowercase() }.toSet()
    }
}
