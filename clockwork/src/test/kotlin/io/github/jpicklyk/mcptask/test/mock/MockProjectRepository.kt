package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.FeatureCounts
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.model.StatusFilter
import io.github.jpicklyk.mcptask.domain.repository.ProjectRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock implementation of ProjectRepository for unit tests.
 */
class MockProjectRepository : ProjectRepository {

    private val projects = ConcurrentHashMap<UUID, Project>()
    private val projectTags = ConcurrentHashMap<UUID, List<String>>()
    private val featureCounts = ConcurrentHashMap<UUID, Int>()
    private val taskCounts = ConcurrentHashMap<UUID, Int>()

    // Custom behaviors for testing
    var nextGetProjectResult: ((UUID) -> Result<Project>)? = null
    var nextCreateResult: ((Project) -> Result<Project>)? = null
    var nextUpdateResult: ((Project) -> Result<Project>)? = null
    var nextDeleteResult: ((UUID) -> Result<Boolean>)? = null

    // BaseRepository methods
    override suspend fun getById(id: UUID): Result<Project> {
        val customResult = nextGetProjectResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }
        
        val project = projects[id]
        return if (project != null) {
            Result.Success(project)
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.PROJECT, "Project not found"))
        }
    }

    override suspend fun create(entity: Project): Result<Project> {
        val customResult = nextCreateResult?.invoke(entity)
        if (customResult != null) {
            return customResult
        }

        try {
            entity.validate()
            projects[entity.id] = entity
            projectTags[entity.id] = entity.tags
            return Result.Success(entity)
        } catch (e: IllegalArgumentException) {
            return Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to create project: ${e.message}", e))
        }
    }

    override suspend fun update(entity: Project): Result<Project> {
        val customResult = nextUpdateResult?.invoke(entity)
        if (customResult != null) {
            return customResult
        }

        if (!projects.containsKey(entity.id)) {
            return Result.Error(RepositoryError.NotFound(entity.id, EntityType.PROJECT, "Project not found"))
        }

        try {
            entity.validate()
            projects[entity.id] = entity
            projectTags[entity.id] = entity.tags
            return Result.Success(entity)
        } catch (e: IllegalArgumentException) {
            return Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to update project: ${e.message}", e))
        }
    }

    override suspend fun delete(id: UUID): Result<Boolean> {
        val customResult = nextDeleteResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }

        if (!projects.containsKey(id)) {
            return Result.Error(RepositoryError.NotFound(id, EntityType.PROJECT, "Project not found"))
        }

        projects.remove(id)
        projectTags.remove(id)
        featureCounts.remove(id)
        taskCounts.remove(id)
        return Result.Success(true)
    }

    // QueryableRepository methods
    override suspend fun findAll(limit: Int): Result<List<Project>> {
        val allProjects = projects.values.toList()
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(allProjects)
    }

    override suspend fun count(): Result<Long> {
        return Result.Success(projects.size.toLong())
    }

    // SearchableRepository methods
    override suspend fun search(query: String, limit: Int): Result<List<Project>> {
        val lowerQuery = query.lowercase()
        val searchResults = projects.values.toList()
            .filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(searchResults)
    }

    override suspend fun countSearch(query: String): Result<Long> {
        val lowerQuery = query.lowercase()
        val count = projects.values.count {
            it.name.lowercase().contains(lowerQuery) ||
                    it.summary.lowercase().contains(lowerQuery)
        }
        return Result.Success(count.toLong())
    }

    // TaggableRepository methods
    override suspend fun findByTag(tag: String, limit: Int): Result<List<Project>> {
        val taggedProjects = projects.values.toList()
            .filter { project -> projectTags[project.id]?.contains(tag) == true }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(taggedProjects)
    }

    override suspend fun findByTags(
        tags: List<String>,
        matchAll: Boolean,
        limit: Int,
    ): Result<List<Project>> {
        val taggedProjects = projects.values.toList()
            .filter { project ->
                val projectTagList = projectTags[project.id] ?: emptyList()
                if (matchAll) {
                    tags.all { tag -> projectTagList.contains(tag) }
                } else {
                    tags.any { tag -> projectTagList.contains(tag) }
                }
            }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(taggedProjects)
    }

    override suspend fun getAllTags(): Result<List<String>> {
        val allTags = projectTags.values.flatten().distinct().sorted()
        return Result.Success(allTags)
    }

    override suspend fun countByTag(tag: String): Result<Long> {
        val count = projects.values.count { project -> projectTags[project.id]?.contains(tag) == true }
        return Result.Success(count.toLong())
    }

    // FilterableRepository methods
    override suspend fun findByFilters(
        projectId: UUID?,
        statusFilter: StatusFilter<ProjectStatus>?,
        priorityFilter: StatusFilter<Nothing>?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Project>> {
        var filteredProjects = projects.values.toList()

        // Note: projectId is ignored for projects since they don't belong to other projects
        // Note: priority is ignored for projects since they don't have a priority

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredProjects = filteredProjects.filter { statusFilter.matches(it.status) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredProjects = filteredProjects.filter { project ->
                val projectTagList = projectTags[project.id] ?: emptyList()
                tags.any { tag -> projectTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredProjects = filteredProjects.filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        // Sort by modifiedAt (newest first) to match SQLite implementation
        filteredProjects = filteredProjects.sortedByDescending { it.modifiedAt }

        // Apply pagination
        val paginatedProjects = filteredProjects
            .take(limit)

        return Result.Success(paginatedProjects)
    }

    override suspend fun countByFilters(
        projectId: UUID?,
        statusFilter: StatusFilter<ProjectStatus>?,
        priorityFilter: StatusFilter<Nothing>?,
        tags: List<String>?,
        textQuery: String?
    ): Result<Long> {
        var filteredProjects = projects.values.toList()

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredProjects = filteredProjects.filter { statusFilter.matches(it.status) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredProjects = filteredProjects.filter { project ->
                val projectTagList = projectTags[project.id] ?: emptyList()
                tags.any { tag -> projectTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredProjects = filteredProjects.filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        return Result.Success(filteredProjects.size.toLong())
    }

    override suspend fun findByStatus(status: ProjectStatus, limit: Int): Result<List<Project>> {
        val statusProjects = projects.values.toList()
            .filter { it.status == status }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(statusProjects)
    }

    override suspend fun findByPriority(priority: Nothing, limit: Int): Result<List<Project>> {
        // Projects don't have a priority, so return all projects
        return findAll(limit)
    }

    // ProjectRepository-specific methods
    override suspend fun findByName(name: String, limit: Int): Result<List<Project>> {
        val lowerName = name.lowercase()
        val nameProjects = projects.values.toList()
            .filter { it.name.lowercase().contains(lowerName) }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(nameProjects)
    }

    override suspend fun getFeatureCount(projectId: UUID): Result<Int> {
        return Result.Success(featureCounts[projectId] ?: 0)
    }

    override suspend fun getTaskCount(projectId: UUID): Result<Int> {
        return Result.Success(taskCounts[projectId] ?: 0)
    }

    //======================================
    // Workflow cascade detection
    //======================================

    override fun getFeatureCountsByProjectId(projectId: UUID): FeatureCounts {
        return FeatureCounts(
            total = featureCounts[projectId] ?: 0,
            completed = featureCounts[projectId] ?: 0  // Assume all completed for simple mock case
        )
    }

    /**
     * Test helper method to add a project directly to the repository
     */
    fun addProject(project: Project) {
        projects[project.id] = project
        projectTags[project.id] = project.tags
    }

    /**
     * Add multiple projects directly to the repository
     */
    fun addProjects(projectsList: List<Project>) {
        projectsList.forEach { project ->
            projects[project.id] = project
            projectTags[project.id] = project.tags
        }
    }

    /**
     * Set feature count for a project (for testing)
     */
    fun setFeatureCount(projectId: UUID, count: Int) {
        featureCounts[projectId] = count
    }

    /**
     * Set a task count for a project (for testing)
     */
    fun setTaskCount(projectId: UUID, count: Int) {
        taskCounts[projectId] = count
    }

    /**
     * Test helper method to clear all projects from the repository
     */
    fun clearProjects() {
        projects.clear()
        projectTags.clear()
        featureCounts.clear()
        taskCounts.clear()
        nextGetProjectResult = null
        nextCreateResult = null
        nextUpdateResult = null
        nextDeleteResult = null
    }
}