package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.TaskCounts
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.schema.EntityTagsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.FeaturesTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.ProjectsTable
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
 * SQLite implementation of the FeatureRepository interface.
 * Extends the unified base class to inherit common functionality.
 */
class SQLiteFeatureRepository(
    databaseManager: io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
) :
    SQLiteBusinessEntityRepository<Feature, FeatureStatus, Priority>(databaseManager),
    FeatureRepository {

    //======================================
    // Base Class Implementation
    //======================================

    override val entityType: EntityType = EntityType.FEATURE
    override val table: Table = FeaturesTable
    override val idColumn: Column<EntityID<UUID>> = FeaturesTable.id
    override val searchVectorColumn: Column<String?> = FeaturesTable.searchVector

    override fun mapRowToEntity(row: ResultRow): Feature {
        return Feature(
            id = row[FeaturesTable.id].value,
            projectId = row[FeaturesTable.projectId],
            name = row[FeaturesTable.name],
            description = row[FeaturesTable.description],
            summary = row[FeaturesTable.summary],
            status = row[FeaturesTable.status],
            priority = row[FeaturesTable.priority],
            createdAt = row[FeaturesTable.createdAt],
            modifiedAt = row[FeaturesTable.modifiedAt],
            tags = emptyList(), // Tags are loaded separately
            version = row[FeaturesTable.version]
        )
    }

    override fun addTagsToEntity(entity: Feature, tags: List<String>): Feature {
        return entity.copy(tags = tags)
    }

    override fun getSearchableText(entity: Feature): String {
        return buildString {
            append(entity.name)
            append(" ").append(entity.summary)
            entity.description?.let { append(" ").append(it) }
            entity.tags.forEach { tag -> append(" ").append(tag) }
        }
    }

    override fun validateEntity(entity: Feature) {
        entity.validate()

        // If projectId is not null, verify that the project exists
        if (entity.projectId != null) {
            val projectExists = transaction {
                ProjectsTable.selectAll().where { ProjectsTable.id eq entity.projectId }.count() > 0
            }

            if (!projectExists) {
                throw IllegalArgumentException("Referenced project with id ${entity.projectId} does not exist")
            }
        }
    }

    override fun insertEntity(entity: Feature) {
        FeaturesTable.insert {
            it[id] = entity.id
            it[projectId] = entity.projectId
            it[name] = entity.name
            it[description] = entity.description
            it[summary] = entity.summary
            it[status] = entity.status
            it[priority] = entity.priority
            it[createdAt] = entity.createdAt
            it[modifiedAt] = entity.modifiedAt
            it[version] = entity.version
        }
    }

    override fun updateEntityInternal(entity: Feature): Int {
        // Optimistic locking: only update if version matches
        return FeaturesTable.update({
            (FeaturesTable.id eq entity.id) and (FeaturesTable.version eq entity.version)
        }) {
            it[projectId] = entity.projectId
            it[name] = entity.name
            it[description] = entity.description
            it[summary] = entity.summary
            it[status] = entity.status
            it[priority] = entity.priority
            it[modifiedAt] = entity.modifiedAt
            it[version] = entity.version + 1
        }
    }

    override fun getEntityId(entity: Feature): UUID = entity.id
    override fun getModifiedAtColumn(): Column<Instant> = FeaturesTable.modifiedAt
    override fun getEntityTags(entity: Feature): List<String> = entity.tags
    override fun getStatusColumn(): Column<FeatureStatus>? = FeaturesTable.status
    override fun getPriorityColumn(): Column<Priority>? = FeaturesTable.priority
    override fun getEntityStatus(entity: Feature): FeatureStatus? = entity.status
    override fun getEntityPriority(entity: Feature): Priority? = entity.priority
    override fun incrementEntityVersion(entity: Feature): Feature = entity.copy(version = entity.version + 1)

    //======================================
    // FeatureRepository Interface Implementation
    //======================================

    override suspend fun findByName(
        name: String,
        limit: Int,
    ): Result<List<Feature>> = withContext(Dispatchers.IO) {
        try {
            val features = transaction {
                val searchPattern = "%${name.lowercase()}%"
                val features = FeaturesTable.selectAll()
                    .where { FeaturesTable.searchVector like searchPattern }
                    .orderBy(FeaturesTable.modifiedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { row -> mapRowToEntity(row) }
                    .toList()

                // Load features with tags
                loadEntitiesWithTags(features)
            }

            Result.Success(features)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to find features by name: ${e.message}", e))
        }
    }

    override suspend fun getTaskCount(featureId: UUID): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                // Check if the feature exists first
                val featureExists = FeaturesTable.selectAll().where { FeaturesTable.id eq featureId }.count() > 0
                if (!featureExists) {
                    return@transaction null
                }

                TaskTable.selectAll().where { TaskTable.featureId eq featureId }.count().toInt()
            }

            if (count != null) {
                Result.Success(count)
            } else {
                Result.Error(
                    RepositoryError.NotFound(
                        featureId,
                        EntityType.FEATURE,
                        "Feature not found with ID: $featureId"
                    )
                )
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to get task count: ${e.message}", e))
        }
    }

    //======================================
    // FilterableRepository Override for Project Filtering
    //======================================

    override suspend fun findByFilters(
        projectId: UUID?,
        statusFilter: io.github.jpicklyk.mcptask.domain.model.StatusFilter<FeatureStatus>?,
        priorityFilter: io.github.jpicklyk.mcptask.domain.model.StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Feature>> = withContext(Dispatchers.IO) {
        try {
            val features = transaction {
                // Start with a base query
                var query = FeaturesTable.selectAll()

                // Apply project filter
                if (projectId != null) {
                    query = query.where { FeaturesTable.projectId eq projectId }
                }

                // Apply status filter using multi-value logic
                if (statusFilter != null && !statusFilter.isEmpty()) {
                    // Include: status IN (...)
                    if (statusFilter.include.isNotEmpty()) {
                        query = query.andWhere { FeaturesTable.status inList statusFilter.include }
                    }
                    // Exclude: status NOT IN (...)
                    if (statusFilter.exclude.isNotEmpty()) {
                        query = query.andWhere { FeaturesTable.status notInList statusFilter.exclude }
                    }
                }

                // Apply priority filter using multi-value logic
                if (priorityFilter != null && !priorityFilter.isEmpty()) {
                    // Include: priority IN (...)
                    if (priorityFilter.include.isNotEmpty()) {
                        query = query.andWhere { FeaturesTable.priority inList priorityFilter.include }
                    }
                    // Exclude: priority NOT IN (...)
                    if (priorityFilter.exclude.isNotEmpty()) {
                        query = query.andWhere { FeaturesTable.priority notInList priorityFilter.exclude }
                    }
                }

                // Apply text search filter
                if (!textQuery.isNullOrBlank()) {
                    // Split the query into individual words and create LIKE conditions for each
                    val searchTerms = textQuery.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    searchTerms.forEach { term ->
                        // Use case-insensitive LIKE by using LOWER() function on both sides
                        query = query.andWhere {
                            FeaturesTable.searchVector.lowerCase() like "%${term.lowercase()}%"
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
                    query = query.andWhere { FeaturesTable.id inList entityIds }
                }

                // Apply ordering and pagination
                query = query.orderBy(FeaturesTable.modifiedAt, SortOrder.DESC)
                query = query.limit(limit)

                // Execute the query and map results
                val features = query.map { row -> mapRowToEntity(row) }.toList()

                // Load features with tags
                loadEntitiesWithTags(features)
            }

            Result.Success(features)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find features by filters: ${e.message}",
                    e
                )
            )
        }
    }

    //======================================
    // Delegate to Base Class Methods
    //======================================

    // All the base interface methods are implemented by the base class
    // Note: These are delegated automatically as the base class implements them

    // Project-scoped methods
    override suspend fun findByProject(projectId: UUID, limit: Int): Result<List<Feature>> =
        withContext(Dispatchers.IO) {
            try {
                val features = transaction {
                    val features = FeaturesTable.selectAll()
                        .where { FeaturesTable.projectId eq projectId }
                        .orderBy(FeaturesTable.modifiedAt, SortOrder.DESC)
                        .limit(limit)
                        .map { row -> mapRowToEntity(row) }
                        .toList()

                    // Load features with tags
                    loadEntitiesWithTags(features)
                }

                Result.Success(features)
        } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to find features by project: ${e.message}", e))
        }
    }

    override suspend fun findByProjectAndFilters(
        projectId: UUID,
        statusFilter: io.github.jpicklyk.mcptask.domain.model.StatusFilter<FeatureStatus>?,
        priorityFilter: io.github.jpicklyk.mcptask.domain.model.StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Feature>> = withContext(Dispatchers.IO) {
        try {
            val features = transaction {
                // Start with project filter
                var query = FeaturesTable.selectAll().where { FeaturesTable.projectId eq projectId }

                // Apply status filter using multi-value logic
                if (statusFilter != null && !statusFilter.isEmpty()) {
                    // Include: status IN (...)
                    if (statusFilter.include.isNotEmpty()) {
                        query = query.andWhere { FeaturesTable.status inList statusFilter.include }
                    }
                    // Exclude: status NOT IN (...)
                    if (statusFilter.exclude.isNotEmpty()) {
                        query = query.andWhere { FeaturesTable.status notInList statusFilter.exclude }
                    }
                }

                // Apply priority filter using multi-value logic
                if (priorityFilter != null && !priorityFilter.isEmpty()) {
                    // Include: priority IN (...)
                    if (priorityFilter.include.isNotEmpty()) {
                        query = query.andWhere { FeaturesTable.priority inList priorityFilter.include }
                    }
                    // Exclude: priority NOT IN (...)
                    if (priorityFilter.exclude.isNotEmpty()) {
                        query = query.andWhere { FeaturesTable.priority notInList priorityFilter.exclude }
                    }
                }

                if (!textQuery.isNullOrBlank()) {
                    val searchTerm = "%${textQuery.lowercase()}%"
                    query = query.andWhere { FeaturesTable.searchVector like searchTerm }
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
                    query = query.andWhere { FeaturesTable.id inList tagEntityIds }
                }

                // Apply ordering and pagination
                query = query.orderBy(FeaturesTable.modifiedAt, SortOrder.DESC)
                query = query.limit(limit)

                // Execute the query and map results
                val features = query.map { row -> mapRowToEntity(row) }.toList()

                // Load features with tags
                loadEntitiesWithTags(features)
            }

            Result.Success(features)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find features by project and filters: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun countByProject(projectId: UUID): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                FeaturesTable.selectAll().where { FeaturesTable.projectId eq projectId }.count()
            }
            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to count features by project: ${e.message}", e))
        }
    }

    //======================================
    // Workflow cascade detection
    //======================================

    override fun getTaskCountsByFeatureId(featureId: UUID): TaskCounts {
        return transaction {
            val tasks = TaskTable.selectAll().where { TaskTable.featureId eq featureId }

            val total = tasks.count().toInt()
            val pending = tasks.count { it[TaskTable.status] == TaskStatus.PENDING }.toInt()
            val inProgress = tasks.count { it[TaskTable.status] == TaskStatus.IN_PROGRESS }.toInt()
            val completed = tasks.count { it[TaskTable.status] == TaskStatus.COMPLETED }.toInt()
            val cancelled = tasks.count { it[TaskTable.status] == TaskStatus.CANCELLED }.toInt()
            val testing = tasks.count { it[TaskTable.status] == TaskStatus.TESTING }.toInt()
            val blocked = tasks.count { it[TaskTable.status] == TaskStatus.BLOCKED }.toInt()

            TaskCounts(
                total = total,
                pending = pending,
                inProgress = inProgress,
                completed = completed,
                cancelled = cancelled,
                testing = testing,
                blocked = blocked
            )
        }
    }
}
