package io.github.jpicklyk.mcptask.domain.model

/**
 * Filter model for status and priority fields supporting multi-value inclusion/exclusion.
 *
 * This filter enables powerful query patterns:
 * - **Inclusion** (OR logic): Match ANY of the specified values
 * - **Exclusion** (AND NOT logic): Match NONE of the specified values
 * - **Combined**: Exclusion takes precedence over inclusion
 *
 * Examples:
 * ```
 * // Match pending OR in-progress
 * StatusFilter(include = listOf(TaskStatus.PENDING, TaskStatus.IN_PROGRESS))
 *
 * // Match anything EXCEPT completed
 * StatusFilter(exclude = listOf(TaskStatus.COMPLETED))
 *
 * // Match pending OR in-progress, but NOT cancelled
 * StatusFilter(
 *     include = listOf(TaskStatus.PENDING, TaskStatus.IN_PROGRESS),
 *     exclude = listOf(TaskStatus.CANCELLED)
 * )
 * ```
 *
 * @param T The type of values to filter (TaskStatus, FeatureStatus, ProjectStatus, Priority)
 * @param include Values to include (OR logic) - match if value is in this list
 * @param exclude Values to exclude (AND NOT logic) - reject if value is in this list
 */
data class StatusFilter<T>(
    val include: List<T> = emptyList(),
    val exclude: List<T> = emptyList()
) {
    /**
     * Returns true if both include and exclude lists are empty.
     * Empty filters should be treated as "match all" (no filtering).
     */
    fun isEmpty(): Boolean = include.isEmpty() && exclude.isEmpty()

    /**
     * Tests if a value matches this filter.
     *
     * Matching logic:
     * 1. If value is in exclude list → reject (return false)
     * 2. If include list is not empty and value is NOT in include list → reject (return false)
     * 3. Otherwise → accept (return true)
     *
     * This means exclusion takes precedence over inclusion.
     *
     * @param value The value to test
     * @return true if the value matches this filter, false otherwise
     */
    fun matches(value: T): Boolean {
        // Exclusion takes precedence
        if (exclude.contains(value)) return false

        // If include list is specified, value must be in it
        if (include.isNotEmpty() && !include.contains(value)) return false

        // Otherwise, accept
        return true
    }
}

/**
 * Type alias for priority filtering.
 * Enables the same multi-value inclusion/exclusion logic as status filters.
 */
typealias PriorityFilter = StatusFilter<Priority>
