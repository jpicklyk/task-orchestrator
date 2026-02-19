package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.StatusFilter
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock implementation of TaskRepository for unit tests.
 */
class MockTaskRepository : TaskRepository {

    private val tasks = ConcurrentHashMap<UUID, Task>()
    private val taskTags = ConcurrentHashMap<UUID, List<String>>()

    // Custom behaviors for testing
    var nextGetTaskResult: ((UUID) -> Result<Task>)? = null
    var nextCreateResult: ((Task) -> Result<Task>)? = null
    var nextUpdateResult: ((Task) -> Result<Task>)? = null
    var nextDeleteResult: ((UUID) -> Result<Boolean>)? = null

    // BaseRepository methods
    override suspend fun getById(id: UUID): Result<Task> {
        val customResult = nextGetTaskResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }
        
        val task = tasks[id]
        return if (task != null) {
            Result.Success(task)
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.TASK, "Task not found"))
        }
    }

    override suspend fun create(entity: Task): Result<Task> {
        val customResult = nextCreateResult?.invoke(entity)
        if (customResult != null) {
            return customResult
        }
        
        try {
            entity.validate()
            tasks[entity.id] = entity
            taskTags[entity.id] = entity.tags
            return Result.Success(entity)
        } catch (e: IllegalArgumentException) {
            return Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to create task: ${e.message}", e))
        }
    }

    override suspend fun update(entity: Task): Result<Task> {
        val customResult = nextUpdateResult?.invoke(entity)
        if (customResult != null) {
            return customResult
        }
        
        if (!tasks.containsKey(entity.id)) {
            return Result.Error(RepositoryError.NotFound(entity.id, EntityType.TASK, "Task not found"))
        }

        try {
            entity.validate()
            tasks[entity.id] = entity
            taskTags[entity.id] = entity.tags
            return Result.Success(entity)
        } catch (e: IllegalArgumentException) {
            return Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to update task: ${e.message}", e))
        }
    }

    override suspend fun delete(id: UUID): Result<Boolean> {
        val customResult = nextDeleteResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }
        
        if (!tasks.containsKey(id)) {
            return Result.Error(RepositoryError.NotFound(id, EntityType.TASK, "Task not found"))
        }

        tasks.remove(id)
        taskTags.remove(id)
        return Result.Success(true)
    }

    // QueryableRepository methods
    override suspend fun findAll(limit: Int): Result<List<Task>> {
        val allTasks = tasks.values.toList()
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(allTasks)
    }

    override suspend fun count(): Result<Long> {
        return Result.Success(tasks.size.toLong())
    }

    // SearchableRepository methods
    override suspend fun search(query: String, limit: Int): Result<List<Task>> {
        val lowerQuery = query.lowercase()
        val searchResults = tasks.values.toList()
            .filter {
                it.title.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(searchResults)
    }

    override suspend fun countSearch(query: String): Result<Long> {
        val lowerQuery = query.lowercase()
        val count = tasks.values.count {
            it.title.lowercase().contains(lowerQuery) ||
                    it.summary.lowercase().contains(lowerQuery)
        }
        return Result.Success(count.toLong())
    }

    // TaggableRepository methods
    override suspend fun findByTag(tag: String, limit: Int): Result<List<Task>> {
        val taggedTasks = tasks.values.toList()
            .filter { task -> taskTags[task.id]?.contains(tag) == true }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(taggedTasks)
    }

    override suspend fun findByTags(
        tags: List<String>,
        matchAll: Boolean,
        limit: Int,
    ): Result<List<Task>> {
        val taggedTasks = tasks.values.toList()
            .filter { task ->
                val taskTagList = taskTags[task.id] ?: emptyList()
                if (matchAll) {
                    tags.all { tag -> taskTagList.contains(tag) }
                } else {
                    tags.any { tag -> taskTagList.contains(tag) }
                }
            }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(taggedTasks)
    }

    override suspend fun getAllTags(): Result<List<String>> {
        val allTags = taskTags.values.flatten().distinct().sorted()
        return Result.Success(allTags)
    }

    override suspend fun countByTag(tag: String): Result<Long> {
        val count = tasks.values.count { task -> taskTags[task.id]?.contains(tag) == true }
        return Result.Success(count.toLong())
    }

    // FilterableRepository methods
    override suspend fun findByFilters(
        projectId: UUID?,
        statusFilter: StatusFilter<TaskStatus>?,
        priorityFilter: StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Task>> {
        var filteredTasks = tasks.values.toList()

        // Apply filters if provided
        if (projectId != null) {
            filteredTasks = filteredTasks.filter { it.projectId == projectId }
        }

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { statusFilter.matches(it.status) }
        }

        if (priorityFilter != null && !priorityFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { priorityFilter.matches(it.priority) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredTasks = filteredTasks.filter { task ->
                val taskTagList = taskTags[task.id] ?: emptyList()
                tags.any { tag -> taskTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredTasks = filteredTasks.filter {
                it.title.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        // Sort by modifiedAt (newest first) to match SQLite implementation
        filteredTasks = filteredTasks.sortedByDescending { it.modifiedAt }

        // Apply pagination
        val paginatedTasks = filteredTasks
            .take(limit)

        return Result.Success(paginatedTasks)
    }

    override suspend fun countByFilters(
        projectId: UUID?,
        statusFilter: StatusFilter<TaskStatus>?,
        priorityFilter: StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?
    ): Result<Long> {
        var filteredTasks = tasks.values.toList()

        if (projectId != null) {
            filteredTasks = filteredTasks.filter { it.projectId == projectId }
        }

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { statusFilter.matches(it.status) }
        }

        if (priorityFilter != null && !priorityFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { priorityFilter.matches(it.priority) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredTasks = filteredTasks.filter { task ->
                val taskTagList = taskTags[task.id] ?: emptyList()
                tags.any { tag -> taskTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredTasks = filteredTasks.filter {
                it.title.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        return Result.Success(filteredTasks.size.toLong())
    }

    override suspend fun findByStatus(status: TaskStatus, limit: Int): Result<List<Task>> {
        val statusTasks = tasks.values.toList()
            .filter { it.status == status }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(statusTasks)
    }

    override suspend fun findByPriority(priority: Priority, limit: Int): Result<List<Task>> {
        val priorityTasks = tasks.values.toList()
            .filter { it.priority == priority }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(priorityTasks)
    }

    // ProjectScopedRepository methods
    override suspend fun findByProject(projectId: UUID, limit: Int): Result<List<Task>> {
        val projectTasks = tasks.values.toList()
            .filter { it.projectId == projectId }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(projectTasks)
    }

    override suspend fun findByProjectAndFilters(
        projectId: UUID,
        statusFilter: StatusFilter<TaskStatus>?,
        priorityFilter: StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Task>> {
        var filteredTasks = tasks.values.toList()
            .filter { it.projectId == projectId }

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { statusFilter.matches(it.status) }
        }

        if (priorityFilter != null && !priorityFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { priorityFilter.matches(it.priority) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredTasks = filteredTasks.filter { task ->
                val taskTagList = taskTags[task.id] ?: emptyList()
                tags.any { tag -> taskTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredTasks = filteredTasks.filter {
                it.title.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        filteredTasks = filteredTasks.sortedByDescending { it.modifiedAt }

        val paginatedTasks = filteredTasks
            .take(limit)

        return Result.Success(paginatedTasks)
    }

    override suspend fun countByProject(projectId: UUID): Result<Long> {
        val count = tasks.values.count { it.projectId == projectId }
        return Result.Success(count.toLong())
    }

    // TaskRepository-specific methods
    override suspend fun findByFeature(
        featureId: UUID,
        statusFilter: StatusFilter<TaskStatus>?,
        priorityFilter: StatusFilter<Priority>?,
        limit: Int,
    ): Result<List<Task>> {
        var filteredTasks = tasks.values.toList()
            .filter { it.featureId == featureId }

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { statusFilter.matches(it.status) }
        }

        if (priorityFilter != null && !priorityFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { priorityFilter.matches(it.priority) }
        }

        filteredTasks = filteredTasks.sortedByDescending { it.modifiedAt }

        val paginatedTasks = filteredTasks
            .take(limit)

        return Result.Success(paginatedTasks)
    }

    override suspend fun findByFeatureAndFilters(
        featureId: UUID,
        statusFilter: StatusFilter<TaskStatus>?,
        priorityFilter: StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?,
        complexityMin: Int?,
        complexityMax: Int?,
        limit: Int,
    ): Result<List<Task>> {
        var filteredTasks = tasks.values.toList()
            .filter { it.featureId == featureId }

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { statusFilter.matches(it.status) }
        }

        if (priorityFilter != null && !priorityFilter.isEmpty()) {
            filteredTasks = filteredTasks.filter { priorityFilter.matches(it.priority) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredTasks = filteredTasks.filter { task ->
                val taskTagList = taskTags[task.id] ?: emptyList()
                tags.any { tag -> taskTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredTasks = filteredTasks.filter {
                it.title.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        if (complexityMin != null) {
            filteredTasks = filteredTasks.filter { it.complexity >= complexityMin }
        }

        if (complexityMax != null) {
            filteredTasks = filteredTasks.filter { it.complexity <= complexityMax }
        }

        filteredTasks = filteredTasks.sortedByDescending { it.modifiedAt }

        val paginatedTasks = filteredTasks
            .take(limit)

        return Result.Success(paginatedTasks)
    }

    override suspend fun findByComplexity(
        minComplexity: Int,
        maxComplexity: Int,
        limit: Int,
    ): Result<List<Task>> {
        val complexityTasks = tasks.values.toList()
            .filter { it.complexity >= minComplexity && it.complexity <= maxComplexity }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(complexityTasks)
    }

    override suspend fun getNextRecommendedTask(
        projectId: UUID?,
        excludeStatuses: List<TaskStatus>
    ): Result<Task?> {
        var availableTasks = tasks.values.toList()
            .filter { !excludeStatuses.contains(it.status) }

        if (projectId != null) {
            availableTasks = availableTasks.filter { it.projectId == projectId }
        }

        // Simple recommendation: highest priority first, then newest
        val nextTask = availableTasks
            .sortedWith(compareByDescending<Task> { it.priority.ordinal }
                .thenByDescending { it.modifiedAt })
            .firstOrNull()

        return Result.Success(nextTask)
    }

    // Workflow cascade detection methods
    override fun findByFeatureId(featureId: UUID): List<Task> {
        return tasks.values.toList()
            .filter { it.featureId == featureId }
            .sortedByDescending { it.modifiedAt }
    }

    /**
     * Test helper method to add a task directly to the repository
     */
    fun addTask(task: Task) {
        tasks[task.id] = task
        taskTags[task.id] = task.tags
    }

    /**
     * Add multiple tasks directly to the repository
     */
    fun addTasks(tasksList: List<Task>) {
        tasksList.forEach { task ->
            tasks[task.id] = task
            taskTags[task.id] = task.tags
        }
    }

    /**
     * Test helper method to clear all tasks from the repository
     */
    fun clearTasks() {
        tasks.clear()
        taskTags.clear()
        nextGetTaskResult = null
        nextCreateResult = null
        nextUpdateResult = null
        nextDeleteResult = null
    }

    /**
     * Test helper method to get all tasks
     */
    fun getAllTasks(): List<Task> {
        return tasks.values.toList()
    }
}