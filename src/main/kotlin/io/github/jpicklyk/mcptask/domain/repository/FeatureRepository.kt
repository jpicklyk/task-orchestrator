package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.TaskCounts
import java.util.*

/**
 * Repository interface for Feature entity operations.
 * Inherits standardized capabilities for searching, filtering, and tag management.
 */
interface FeatureRepository : ProjectScopedRepository<Feature, FeatureStatus, Priority> {

    /**
     * Finds features by name (partial matching).
     * @param name The name or partial name to search for
     * @param limit Maximum number of results
     */
    suspend fun findByName(
        name: String,
        limit: Int = 20,
    ): Result<List<Feature>>

    /**
     * Gets task count for a specific feature.
     * @param featureId The feature ID
     */
    suspend fun getTaskCount(featureId: UUID): Result<Int>

    /**
     * Gets detailed task counts by status for a feature.
     * Used by WorkflowService for cascade event detection.
     * @param featureId The feature ID
     * @return TaskCounts with breakdown by status
     */
    fun getTaskCountsByFeatureId(featureId: UUID): TaskCounts
}