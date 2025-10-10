package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.database.schema.EntityTagsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.FeaturesTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TaskTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.*

/**
 * SQLite implementation of the TaskRepository interface.
 * Extends the unified base class to inherit common functionality.
 */
class SQLiteTaskRepository(
    databaseManager: io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
) :
    SQLiteBusinessEntityRepository<Task, TaskStatus, Priority>(databaseManager),
    TaskRepository {

    //======================================
    // Base Class Implementation
    //======================================

    override val entityType: EntityType = EntityType.TASK
    override val table: Table = TaskTable
    override val idColumn: Column<EntityID<UUID>> = TaskTable.id
    override val searchVectorColumn: Column<String?> = TaskTable.searchVector

    override fun mapRowToEntity(row: ResultRow): Task {
        return Task(
            id = row[TaskTable.id].value,
            featureId = row[TaskTable.featureId],
            projectId = row[TaskTable.projectId],
            title = row[TaskTable.title],
            summary = row[TaskTable.summary],
            status = row[TaskTable.status],
            priority = row[TaskTable.priority],
            complexity = row[TaskTable.complexity],
            createdAt = row[TaskTable.createdAt],
            modifiedAt = row[TaskTable.modifiedAt],
            tags = emptyList(), // Tags are loaded separately
            version = row[TaskTable.version]
        )
    }

    override fun addTagsToEntity(entity: Task, tags: List<String>): Task {
        return entity.copy(tags = tags)
    }

    override fun getSearchableText(entity: Task): String {
        return buildString {
            append(entity.title)
            append(" ").append(entity.summary)
            entity.tags.forEach { tag -> append(" ").append(tag) }
        }
    }

    override fun validateEntity(entity: Task) {
        entity.validate()

        // Validate feature-project relationship consistency
        if (entity.featureId != null && entity.projectId != null) {
            // Feature must be in the same project as the task
            val featureQuery = transaction {
                FeaturesTable.selectAll().where { FeaturesTable.id eq entity.featureId }.singleOrNull()
            }
            if (featureQuery != null) {
                val featureProjectId = featureQuery[FeaturesTable.projectId]
                if (featureProjectId != null && featureProjectId != entity.projectId) {
                    throw IllegalArgumentException("Task and its feature must belong to the same project")
                }
            }
        }
    }

    override fun insertEntity(entity: Task) {
        TaskTable.insert {
            it[id] = entity.id
            it[featureId] = entity.featureId
            it[projectId] = entity.projectId
            it[title] = entity.title
            it[summary] = entity.summary
            it[status] = entity.status
            it[priority] = entity.priority
            it[complexity] = entity.complexity
            it[createdAt] = entity.createdAt
            it[modifiedAt] = entity.modifiedAt
            it[version] = entity.version
        }
    }

    override fun updateEntityInternal(entity: Task): Int {
        // Optimistic locking: only update if version matches
        return TaskTable.update({
            (TaskTable.id eq entity.id) and (TaskTable.version eq entity.version)
        }) {
            it[featureId] = entity.featureId
            it[projectId] = entity.projectId
            it[title] = entity.title
            it[summary] = entity.summary
            it[status] = entity.status
            it[priority] = entity.priority
            it[complexity] = entity.complexity
            it[modifiedAt] = entity.modifiedAt
            it[version] = entity.version + 1
        }
    }

    override fun getEntityId(entity: Task): UUID = entity.id
    override fun getModifiedAtColumn(): Column<Instant> = TaskTable.modifiedAt
    override fun getEntityTags(entity: Task): List<String> = entity.tags
    override fun getStatusColumn(): Column<TaskStatus>? = TaskTable.status
    override fun getPriorityColumn(): Column<Priority>? = TaskTable.priority
    override fun getEntityStatus(entity: Task): TaskStatus? = entity.status
    override fun getEntityPriority(entity: Task): Priority? = entity.priority

    //======================================
    // TaskRepository Interface Implementation
    //======================================

    override suspend fun findByFeature(
        featureId: UUID,
        status: TaskStatus?,
        priority: Priority?,
        limit: Int
    ): Result<List<Task>> = withContext(Dispatchers.IO) {
        try {
            val tasks = transaction {
                // Start with a base query filtering by feature
                var query = TaskTable.selectAll().where { TaskTable.featureId eq featureId }

                // Apply additional filters
                if (status != null) {
                    query = query.andWhere { TaskTable.status eq status }
                }

                if (priority != null) {
                    query = query.andWhere { TaskTable.priority eq priority }
                }

                // Apply ordering and pagination
                query = query.orderBy(TaskTable.modifiedAt, SortOrder.DESC)
                query = query.limit(limit)

                // Execute the query and map results
                val tasks = query.map { row -> mapRowToEntity(row) }.toList()

                // Load tasks with tags
                loadEntitiesWithTags(tasks)
            }

            Result.Success(tasks)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to find tasks by feature: ${e.message}", e))
        }
    }

    override suspend fun findByFeatureAndFilters(
        featureId: UUID,
        status: TaskStatus?,
        priority: Priority?,
        tags: List<String>?,
        textQuery: String?,
        complexityMin: Int?,
        complexityMax: Int?,
        limit: Int,
    ): Result<List<Task>> = withContext(Dispatchers.IO) {
        try {
            val tasks = transaction {
                // Start with base query filtering by feature
                var query = TaskTable.selectAll().where { TaskTable.featureId eq featureId }

                // Apply status filter
                if (status != null) {
                    query = query.andWhere { TaskTable.status eq status }
                }

                // Apply priority filter
                if (priority != null) {
                    query = query.andWhere { TaskTable.priority eq priority }
                }

                // Apply complexity filters
                if (complexityMin != null) {
                    query = query.andWhere { TaskTable.complexity greaterEq complexityMin }
                }
                if (complexityMax != null) {
                    query = query.andWhere { TaskTable.complexity lessEq complexityMax }
                }

                // Apply text search
                if (!textQuery.isNullOrBlank()) {
                    val searchTerm = "%${textQuery.lowercase()}%"
                    query = query.andWhere { TaskTable.searchVector like searchTerm }
                }

                // Apply tag filtering if specified
                if (!tags.isNullOrEmpty()) {
                    // Use entity IDs from tag filtering
                    val tagEntityIds = EntityTagsTable
                        .selectAll().where {
                            (EntityTagsTable.entityType eq entityType.name) and (EntityTagsTable.tag inList tags)
                        }
                        .map { it[EntityTagsTable.entityId] }
                        .distinct()

                    if (tagEntityIds.isEmpty()) {
                        return@transaction emptyList()
                    }
                    query = query.andWhere { TaskTable.id inList tagEntityIds }
                }

                // Apply ordering and pagination
                query = query.orderBy(TaskTable.modifiedAt, SortOrder.DESC)
                query = query.limit(limit)

                // Execute the query and map results
                val tasks = query.map { row -> mapRowToEntity(row) }.toList()

                // Load tasks with tags
                loadEntitiesWithTags(tasks)
            }

            Result.Success(tasks)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to find tasks by feature and filters: ${e.message}", e))
        }
    }

    override suspend fun findByComplexity(
        minComplexity: Int,
        maxComplexity: Int,
        limit: Int,
    ): Result<List<Task>> = withContext(Dispatchers.IO) {
        try {
            val tasks = transaction {
                val query = TaskTable.selectAll()
                    .where { (TaskTable.complexity greaterEq minComplexity) and (TaskTable.complexity lessEq maxComplexity) }
                    .orderBy(TaskTable.modifiedAt, SortOrder.DESC)
                    .limit(limit)

                val tasks = query.map { row -> mapRowToEntity(row) }.toList()
                loadEntitiesWithTags(tasks)
            }

            Result.Success(tasks)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to find tasks by complexity: ${e.message}", e))
        }
    }

    override suspend fun getNextRecommendedTask(
        projectId: UUID?,
        excludeStatuses: List<TaskStatus>
    ): Result<Task?> = withContext(Dispatchers.IO) {
        try {
            val task = transaction {
                // Build query for next recommended task
                var query = TaskTable.selectAll()

                // Exclude certain statuses
                if (excludeStatuses.isNotEmpty()) {
                    query = query.where { TaskTable.status notInList excludeStatuses }
                }

                // Filter by project if specified
                if (projectId != null) {
                    query = query.andWhere { TaskTable.projectId eq projectId }
                }

                // Order by priority (HIGH first), then by creation date
                query = query.orderBy(TaskTable.priority, SortOrder.DESC)
                    .orderBy(TaskTable.createdAt, SortOrder.ASC)

                // Get the first result
                val taskRow = query.firstOrNull() ?: return@transaction null
                val task = mapRowToEntity(taskRow)

                // Load task with tags
                loadEntitiesWithTags(listOf(task)).firstOrNull()
            }

            Result.Success(task)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to get next recommended task: ${e.message}", e))
        }
    }

    //======================================
    // Project-scoped methods
    //======================================

    override suspend fun findByProject(projectId: UUID, limit: Int): Result<List<Task>> =
        withContext(Dispatchers.IO) {
            try {
                val tasks = transaction {
                    val tasks = TaskTable.selectAll()
                        .where { TaskTable.projectId eq projectId }
                        .orderBy(TaskTable.modifiedAt, SortOrder.DESC)
                        .limit(limit)
                        .map { row -> mapRowToEntity(row) }
                        .toList()

                    // Load tasks with tags
                    loadEntitiesWithTags(tasks)
                }

                Result.Success(tasks)
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to find tasks by project: ${e.message}", e))
            }
        }

    override suspend fun findByProjectAndFilters(
        projectId: UUID,
        status: TaskStatus?,
        priority: Priority?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Task>> = withContext(Dispatchers.IO) {
        try {
            val tasks = transaction {
                // Start with project filter
                var query = TaskTable.selectAll().where { TaskTable.projectId eq projectId }

                // Apply additional filters
                if (status != null) {
                    query = query.andWhere { TaskTable.status eq status }
                }

                if (priority != null) {
                    query = query.andWhere { TaskTable.priority eq priority }
                }

                if (!textQuery.isNullOrBlank()) {
                    val searchTerm = "%${textQuery.lowercase()}%"
                    query = query.andWhere { TaskTable.searchVector like searchTerm }
                }

                // Apply tag filtering if specified
                if (!tags.isNullOrEmpty()) {
                    // Use entity IDs from tag filtering
                    val tagEntityIds = EntityTagsTable
                        .selectAll().where {
                            (EntityTagsTable.entityType eq entityType.name) and (EntityTagsTable.tag inList tags)
                        }
                        .map { it[EntityTagsTable.entityId] }
                        .distinct()

                    if (tagEntityIds.isEmpty()) {
                        return@transaction emptyList()
                    }
                    query = query.andWhere { TaskTable.id inList tagEntityIds }
                }

                // Apply ordering and pagination
                query = query.orderBy(TaskTable.modifiedAt, SortOrder.DESC)
                query = query.limit(limit)

                // Execute the query and map results
                val tasks = query.map { row -> mapRowToEntity(row) }.toList()

                // Load tasks with tags
                loadEntitiesWithTags(tasks)
            }

            Result.Success(tasks)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to find tasks by project and filters: ${e.message}", e))
        }
    }

    override suspend fun countByProject(projectId: UUID): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                TaskTable.selectAll().where { TaskTable.projectId eq projectId }.count()
            }
            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to count tasks by project: ${e.message}", e))
        }
    }

    //======================================
    // Override base class to handle projectId filtering
    //======================================

    override suspend fun findByFilters(
        projectId: UUID?,
        status: TaskStatus?,
        priority: Priority?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Task>> = withContext(Dispatchers.IO) {
        try {
            val tasks = transaction {
                // Start with a base query
                var query = TaskTable.selectAll()

                // Apply project filter: tasks belong to project if they have direct projectId 
                // OR if they belong to a feature that belongs to the project
                if (projectId != null) {
                    // Get feature IDs that belong to this project
                    val projectFeatureIds = FeaturesTable
                        .selectAll().where { FeaturesTable.projectId eq projectId }
                        .map { it[FeaturesTable.id].value }

                    // Include tasks that either:
                    // 1. Have direct projectId relationship, OR
                    // 2. Have featureId in features that belong to the project
                    query = query.where {
                        (TaskTable.projectId eq projectId) or (TaskTable.featureId inList projectFeatureIds)
                    }
                }

                // Apply status filter
                if (status != null) {
                    query = query.andWhere { TaskTable.status eq status }
                }

                // Apply priority filter
                if (priority != null) {
                    query = query.andWhere { TaskTable.priority eq priority }
                }

                // Apply text search filter
                if (!textQuery.isNullOrBlank()) {
                    // Split the query into individual words and create LIKE conditions for each
                    val searchTerms = textQuery.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    searchTerms.forEach { term ->
                        // Use case-insensitive LIKE by using LOWER() function on both sides
                        query = query.andWhere {
                            TaskTable.searchVector.lowerCase() like "%${term.lowercase()}%"
                        }
                    }
                }

                // Apply tag filtering if specified
                val entityIds = if (!tags.isNullOrEmpty()) {
                    // Get entity IDs that have any of the specified tags
                    EntityTagsTable
                        .selectAll().where {
                            (EntityTagsTable.entityType eq entityType.name) and (EntityTagsTable.tag inList tags)
                        }
                        .map { it[EntityTagsTable.entityId] }
                        .distinct()
                } else {
                    null
                }

                if (entityIds != null) {
                    if (entityIds.isEmpty()) {
                        return@transaction emptyList()
                    }
                    query = query.andWhere { TaskTable.id inList entityIds }
                }

                // Apply ordering and pagination
                query = query.orderBy(TaskTable.modifiedAt, SortOrder.DESC)
                query = query.limit(limit)

                // Execute the query and map results
                val tasks = query.map { row -> mapRowToEntity(row) }.toList()

                // Load tasks with tags
                loadEntitiesWithTags(tasks)
            }

            Result.Success(tasks)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find tasks by filters: ${e.message}",
                    e
                )
            )
        }
    }

}
