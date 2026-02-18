package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.StatusFilter
import java.util.*

/**
 * Hierarchical Repository Architecture for MCP Task Orchestrator
 * ============================================================
 *
 * This file defines a hierarchical structure of repository interfaces that ensures
 * consistent capabilities across all business entities while allowing for
 * entity-specific extensions.
 *
 * Architecture Overview:
 * ----------------------
 * BaseRepository<T>
 * ??? QueryableRepository<T> (adds findAll, count)
 * ???? SearchableRepository<T> (adds search, countSearch)
 * ????? TaggableRepository<T> (adds findByTag, getAllTags)
 * ?????? FilterableRepository<T,Status,Priority> (adds findByFilters, findByStatus)
 * ??????? ProjectScopedRepository<T,Status,Priority> (adds findByProject)
 *
 * Implementation Notes:
 * --------------------
 * - All business entities (Project, Feature, Task) inherit from this hierarchy
 * - Template and Section entities use specialized interfaces as they serve different purposes
 * - The unified tag system (EntityTagsTable) provides consistent tag management
 * - Search vectors enable efficient full-text search across all entities
 * - This design eliminates feature drift between repositories
 *
 * Entity Mapping:
 * ---------------
 * - ProjectRepository: FilterableRepository<Project, ProjectStatus, Nothing>
 * - FeatureRepository: ProjectScopedRepository<Feature, FeatureStatus, Priority>
 * - TaskRepository: ProjectScopedRepository<Task, TaskStatus, Priority>
 */

/**
 * Base repository interface with standard CRUD operations.
 */
interface BaseRepository<T> {
    /**
     * Retrieves an entity by its ID.
     */
    suspend fun getById(id: UUID): Result<T>

    /**
     * Creates a new entity.
     */
    suspend fun create(entity: T): Result<T>

    /**
     * Updates an existing entity.
     */
    suspend fun update(entity: T): Result<T>

    /**
     * Deletes an entity by its ID.
     */
    suspend fun delete(id: UUID): Result<Boolean>
}

/**
 * Repository interface for entities that support basic querying and pagination.
 */
interface QueryableRepository<T> : BaseRepository<T> {
    /**
     * Finds all entities with pagination.
     */
    suspend fun findAll(limit: Int = 20): Result<List<T>>

    /**
     * Counts total number of entities.
     */
    suspend fun count(): Result<Long>
}

/**
 * Repository interface for entities that support text-based searching.
 */
interface SearchableRepository<T> : QueryableRepository<T> {
    /**
     * Searches entities by text query with pagination.
     * Should search across relevant text fields (title, description, etc.)
     */
    suspend fun search(
        query: String,
        limit: Int = 20,
    ): Result<List<T>>

    /**
     * Counts entities matching the search query.
     */
    suspend fun countSearch(query: String): Result<Long>
}

/**
 * Repository interface for entities that support tags.
 */
interface TaggableRepository<T> : SearchableRepository<T> {
    /**
     * Finds entities by tag with pagination.
     */
    suspend fun findByTag(
        tag: String,
        limit: Int = 20,
    ): Result<List<T>>

    /**
     * Finds entities containing any of the specified tags.
     */
    suspend fun findByTags(
        tags: List<String>,
        matchAll: Boolean = false,
        limit: Int = 20,
    ): Result<List<T>>

    /**
     * Gets all unique tags used by entities.
     */
    suspend fun getAllTags(): Result<List<String>>

    /**
     * Counts entities with the specified tag.
     */
    suspend fun countByTag(tag: String): Result<Long>
}

/**
 * Repository interface for entities that support status and priority filtering.
 */
interface FilterableRepository<T, TStatus, TPriority> : TaggableRepository<T> {
    /**
     * Advanced filtering with multiple criteria.
     *
     * @param projectId Optional project scope filter
     * @param statusFilter Multi-value status filter supporting inclusion/exclusion
     * @param priorityFilter Multi-value priority filter supporting inclusion/exclusion
     * @param tags Optional list of tags to filter by
     * @param textQuery Optional text search query
     * @param limit Maximum number of results to return
     * @return List of entities matching the filter criteria
     */
    suspend fun findByFilters(
        projectId: UUID? = null,
        statusFilter: StatusFilter<TStatus>? = null,
        priorityFilter: StatusFilter<TPriority>? = null,
        tags: List<String>? = null,
        textQuery: String? = null,
        limit: Int = 20,
    ): Result<List<T>>

    /**
     * Counts entities matching the filter criteria.
     *
     * @param projectId Optional project scope filter
     * @param statusFilter Multi-value status filter supporting inclusion/exclusion
     * @param priorityFilter Multi-value priority filter supporting inclusion/exclusion
     * @param tags Optional list of tags to filter by
     * @param textQuery Optional text search query
     * @return Count of entities matching the filter criteria
     */
    suspend fun countByFilters(
        projectId: UUID? = null,
        statusFilter: StatusFilter<TStatus>? = null,
        priorityFilter: StatusFilter<TPriority>? = null,
        tags: List<String>? = null,
        textQuery: String? = null
    ): Result<Long>

    /**
     * Finds entities by status with pagination.
     */
    suspend fun findByStatus(
        status: TStatus,
        limit: Int = 20,
    ): Result<List<T>>

    /**
     * Finds entities by priority with pagination.
     */
    suspend fun findByPriority(
        priority: TPriority,
        limit: Int = 20,
    ): Result<List<T>>
}

/**
 * Repository interface for entities that belong to a project.
 */
interface ProjectScopedRepository<T, TStatus, TPriority> : FilterableRepository<T, TStatus, TPriority> {
    /**
     * Finds entities within a specific project.
     */
    suspend fun findByProject(
        projectId: UUID,
        limit: Int = 20,
    ): Result<List<T>>

    /**
     * Advanced filtering within a project scope.
     *
     * @param projectId The project to filter within
     * @param statusFilter Multi-value status filter supporting inclusion/exclusion
     * @param priorityFilter Multi-value priority filter supporting inclusion/exclusion
     * @param tags Optional list of tags to filter by
     * @param textQuery Optional text search query
     * @param limit Maximum number of results to return
     * @return List of entities matching the filter criteria within the project
     */
    suspend fun findByProjectAndFilters(
        projectId: UUID,
        statusFilter: StatusFilter<TStatus>? = null,
        priorityFilter: StatusFilter<TPriority>? = null,
        tags: List<String>? = null,
        textQuery: String? = null,
        limit: Int = 20,
    ): Result<List<T>>

    /**
     * Counts entities within a project.
     */
    suspend fun countByProject(projectId: UUID): Result<Long>
}