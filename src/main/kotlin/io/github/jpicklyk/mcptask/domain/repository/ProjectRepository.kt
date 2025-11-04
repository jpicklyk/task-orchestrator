package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.FeatureCounts
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import java.util.*

/**
 * Repository interface for Project entities.
 * Projects are the top-level entities and don't have priority.
 * Inherits standardized capabilities for searching, filtering, and tag management.
 */
interface ProjectRepository : FilterableRepository<Project, ProjectStatus, Nothing> {

    /**
     * Finds projects by name (partial matching).
     * @param name The name or part of the name to search for
     * @param limit Maximum number of results
     */
    suspend fun findByName(
        name: String,
        limit: Int = 20,
    ): Result<List<Project>>

    /**
     * Gets the count of features associated with a project.
     * @param projectId The project ID
     */
    suspend fun getFeatureCount(projectId: UUID): Result<Int>

    /**
     * Gets the count of tasks directly associated with a project (not through features).
     * @param projectId The project ID
     */
    suspend fun getTaskCount(projectId: UUID): Result<Int>

    /**
     * Gets detailed feature counts for a project.
     * Used by WorkflowService for cascade event detection.
     * @param projectId The project ID
     * @return FeatureCounts with total and completed counts
     */
    fun getFeatureCountsByProjectId(projectId: UUID): FeatureCounts

    /**
     * Advanced project-specific filtering (inherits from base implementation).
     * Note: Projects don't have priority, so the priority parameter is ignored.
     * Projects don't belong to other projects, so projectId parameter is ignored.
     */
    // No need to override - inherited from FilterableRepository
}