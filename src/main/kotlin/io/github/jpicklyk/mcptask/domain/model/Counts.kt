package io.github.jpicklyk.mcptask.domain.model

/**
 * Aggregated task counts by status for a feature.
 * Used by WorkflowService for cascade event detection.
 */
data class TaskCounts(
    val total: Int,
    val pending: Int,
    val inProgress: Int,
    val completed: Int,
    val cancelled: Int,
    val testing: Int,
    val blocked: Int
)

/**
 * Aggregated feature counts for a project.
 * Used by WorkflowService for cascade event detection.
 */
data class FeatureCounts(
    val total: Int,
    val completed: Int
)
