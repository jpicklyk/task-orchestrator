package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.StatusFilter
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import java.util.*

/**
 * Repository interface for Task entity operations.
 * Inherits standardized capabilities for searching, filtering, and tag management.
 */
interface TaskRepository : ProjectScopedRepository<Task, TaskStatus, Priority> {
    
    /**
     * Finds tasks by feature with additional filtering options.
     * @param featureId The feature ID to filter by
     * @param statusFilter Multi-value status filter supporting inclusion/exclusion
     * @param priorityFilter Multi-value priority filter supporting inclusion/exclusion
     * @param limit Maximum number of results
     */
    suspend fun findByFeature(
        featureId: UUID,
        statusFilter: StatusFilter<TaskStatus>? = null,
        priorityFilter: StatusFilter<Priority>? = null,
        limit: Int = 20,
    ): Result<List<Task>>

    /**
     * Advanced filtering with feature scope support.
     * Extends the base filtering to include feature-specific filtering.
     *
     * @param featureId The feature to filter within
     * @param statusFilter Multi-value status filter supporting inclusion/exclusion
     * @param priorityFilter Multi-value priority filter supporting inclusion/exclusion
     * @param tags Optional list of tags to filter by
     * @param textQuery Optional text search query
     * @param complexityMin Optional minimum complexity (1-10)
     * @param complexityMax Optional maximum complexity (1-10)
     * @param limit Maximum number of results to return
     * @return List of tasks matching the filter criteria within the feature
     */
    suspend fun findByFeatureAndFilters(
        featureId: UUID,
        statusFilter: StatusFilter<TaskStatus>? = null,
        priorityFilter: StatusFilter<Priority>? = null,
        tags: List<String>? = null,
        textQuery: String? = null,
        complexityMin: Int? = null,
        complexityMax: Int? = null,
        limit: Int = 20,
    ): Result<List<Task>>

    /**
     * Finds tasks by complexity range.
     * @param minComplexity Minimum complexity (1-10)
     * @param maxComplexity Maximum complexity (1-10)
     * @param limit Maximum number of results
     */
    suspend fun findByComplexity(
        minComplexity: Int,
        maxComplexity: Int = 10,
        limit: Int = 20,
    ): Result<List<Task>>

    /**
     * Gets the next recommended task based on priority and dependencies.
     * @param projectId Optional project scope
     * @param excludeStatuses Statuses to exclude (default: completed, cancelled)
     */
    suspend fun getNextRecommendedTask(
        projectId: UUID? = null,
        excludeStatuses: List<TaskStatus> = listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)
    ): Result<Task?>

    /**
     * Finds all tasks for a feature (synchronous for cascade detection).
     * @param featureId The feature ID
     * @return List of tasks in the feature
     */
    fun findByFeatureId(featureId: UUID): List<Task>
}