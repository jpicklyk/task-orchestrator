package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Ordering strategy for [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.findClaimable].
 *
 * - [PRIORITY_THEN_COMPLEXITY] — existing get_next_item ranking (default): HIGH > MEDIUM > LOW,
 *   then complexity ascending (quick wins first).
 * - [OLDEST_FIRST] — createdAt ASC; useful for FIFO queue drain (Ralph-style).
 * - [NEWEST_FIRST] — createdAt DESC; useful for recency-biased selection.
 */
enum class NextItemOrder {
    PRIORITY_THEN_COMPLEXITY,
    OLDEST_FIRST,
    NEWEST_FIRST;

    companion object {
        /**
         * Parse a nullable string to [NextItemOrder], returning null for unrecognised values.
         *
         * Recognised aliases:
         * - null or "priority" → [PRIORITY_THEN_COMPLEXITY]
         * - "oldest"          → [OLDEST_FIRST]
         * - "newest"          → [NEWEST_FIRST]
         */
        fun fromString(s: String?): NextItemOrder? =
            when (s?.lowercase()) {
                null, "priority" -> PRIORITY_THEN_COMPLEXITY
                "oldest" -> OLDEST_FIRST
                "newest" -> NEWEST_FIRST
                else -> null
            }
    }
}
