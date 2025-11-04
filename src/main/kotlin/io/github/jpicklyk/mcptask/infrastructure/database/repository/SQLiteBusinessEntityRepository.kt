package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.FilterableRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.schema.EntityTagsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.*

/**
 * Abstract base class for SQLite business entity repositories.
 * Provides common functionality for Project, Feature, and Task repositories including:
 * - Unified tag management via EntityTagsTable
 * - Search vector management
 * - Common CRUD operations
 * - Error handling patterns
 *
 * @param T The entity type (Project, Feature, or Task)
 * @param TStatus The status enum type for the entity
 * @param TPriority The priority enum type for the entity (null for Project)
 */
abstract class SQLiteBusinessEntityRepository<T, TStatus, TPriority>(
    protected open val databaseManager: DatabaseManager? = null
) : FilterableRepository<T, TStatus, TPriority> {

    //======================================
    // Abstract Properties and Methods
    //======================================

    /**
     * Gets the entity type for this repository.
     */
    protected abstract val entityType: EntityType

    /**
     * Gets the main table for this entity type.
     */
    protected abstract val table: Table

    /**
     * Gets the ID column for the main table.
     */
    protected abstract val idColumn: Column<EntityID<UUID>>

    /**
     * Gets the search vector column for full-text search.
     */
    protected abstract val searchVectorColumn: Column<String?>

    /**
     * Maps a database row to an entity (without tags).
     */
    protected abstract fun mapRowToEntity(row: ResultRow): T

    /**
     * Creates a copy of the entity with the provided tags.
     */
    protected abstract fun addTagsToEntity(entity: T, tags: List<String>): T

    /**
     * Gets the searchable text content for the entity to build the search vector.
     */
    protected abstract fun getSearchableText(entity: T): String

    /**
     * Validates the entity before persistence.
     */
    protected abstract fun validateEntity(entity: T)

    /**
     * Inserts the entity into the database (without tags).
     */
    protected abstract fun insertEntity(entity: T)

    /**
     * Updates the entity in the database (without tags).
     * Returns the number of rows updated.
     */
    protected abstract fun updateEntityInternal(entity: T): Int

    /**
     * Gets the entity ID for persistence operations.
     */
    protected abstract fun getEntityId(entity: T): UUID

    /**
     * Gets the modifiedAt column for the entity.
     */
    protected abstract fun getModifiedAtColumn(): Column<Instant>

    /**
     * Gets the tags from the entity.
     */
    protected abstract fun getEntityTags(entity: T): List<String>

    /**
     * Gets the status column for the entity.
     */
    protected abstract fun getStatusColumn(): Column<TStatus>?

    /**
     * Gets the priority column for the entity.
     */
    protected abstract fun getPriorityColumn(): Column<TPriority>?

    /**
     * Gets the status value from the entity.
     */
    protected abstract fun getEntityStatus(entity: T): TStatus?

    /**
     * Gets the priority value from the entity.
     */
    protected abstract fun getEntityPriority(entity: T): TPriority?

    /**
     * Returns a copy of the entity with the version incremented by 1.
     * Used after successful updates to reflect the database state.
     */
    protected abstract fun incrementEntityVersion(entity: T): T

    //======================================
    // Common CRUD Operations
    //======================================

    override suspend fun getById(id: UUID): Result<T> = withContext(Dispatchers.IO) {
        try {
            val entity = transaction {
                // Query the entity by ID
                val entityRow = table.selectAll().where { idColumn eq id }
                    .singleOrNull() ?: return@transaction null

                // Map row to entity
                val entity = mapRowToEntity(entityRow)

                // Query tags for the entity
                val tags = EntityTagsTable
                    .selectAll().where {
                        (EntityTagsTable.entityId eq id) and (EntityTagsTable.entityType eq entityType.name)
                    }
                    .map { it[EntityTagsTable.tag] }

                // Return entity with tags
                addTagsToEntity(entity, tags)
            }

            if (entity != null) {
                Result.Success(entity)
            } else {
                Result.Error(RepositoryError.NotFound(id, entityType, "${entityType.name} not found with id: $id"))
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to get ${entityType.name.lowercase()}: ${e.message}", e))
        }
    }

    override suspend fun create(entity: T): Result<T> = withContext(Dispatchers.IO) {
        try {
            // Validate the entity before storing
            validateEntity(entity)

            val result = transaction {
                // Insert the entity
                insertEntity(entity)

                // Update search vector
                updateSearchVector(entity)

                // Insert entity tags
                val entityId = getEntityId(entity)
                val tags = getEntityTags(entity)
                insertEntityTags(entityId, tags)

                // Return the entity
                entity
            }

            Result.Success(result)
        } catch (e: IllegalArgumentException) {
            Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to create ${entityType.name.lowercase()}: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun update(entity: T): Result<T> = withContext(Dispatchers.IO) {
        try {
            // Validate the entity before updating
            validateEntity(entity)

            val result = transaction {
                val entityId = getEntityId(entity)

                // Check if entity exists first to distinguish NotFound from OptimisticLockError
                val exists = table.selectAll().where { idColumn eq entityId }.count() > 0

                // Update the entity (with optimistic locking check)
                val rowsUpdated = updateEntityInternal(entity)

                if (rowsUpdated == 0) {
                    // 0 rows updated: either entity doesn't exist OR version conflict
                    if (!exists) {
                        // Entity doesn't exist
                        return@transaction Result.Error(
                            RepositoryError.NotFound(
                                entityId,
                                entityType,
                                "${entityType.name} not found with id: $entityId"
                            )
                        )
                    } else {
                        // Entity exists but version doesn't match - optimistic lock conflict
                        return@transaction Result.Error(
                            RepositoryError.ConflictError(
                                "Optimistic lock conflict: ${entityType.name} with id $entityId has been modified by another process"
                            )
                        )
                    }
                }

                // Update search vector
                updateSearchVector(entity)

                // Update tags: delete old tags and insert new ones
                // Make sure we're using the correct entity type name here
                EntityTagsTable.deleteWhere {
                    (EntityTagsTable.entityId eq entityId) and (EntityTagsTable.entityType eq entityType.name)
                }
                val tags = getEntityTags(entity)
                insertEntityTags(entityId, tags)

                // Return the updated entity with incremented version
                // Since update succeeded, we know version was incremented in the database
                Result.Success(incrementEntityVersion(entity))
            }

            // Transaction returns Result<T>, so we just return it
            result as Result<T>
        } catch (e: IllegalArgumentException) {
            Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to update ${entityType.name.lowercase()}: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun delete(id: UUID): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val deleted = transaction {
                // Delete associated tags first
                println("Deleting tags for ${entityType.name} with ID: $id")
                EntityTagsTable.deleteWhere {
                    (EntityTagsTable.entityId eq id) and (EntityTagsTable.entityType eq entityType.name)
                }

                // Delete the entity and return whether something was deleted
                val deletedRows = table.deleteWhere { idColumn eq id }
                deletedRows > 0
            }

            if (deleted) {
                Result.Success(true)
            } else {
                Result.Error(RepositoryError.NotFound(id, entityType, "${entityType.name} not found with id: $id"))
            }
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to delete ${entityType.name.lowercase()}: ${e.message}",
                    e
                )
            )
        }
    }

    //======================================
    // QueryableRepository Methods
    //======================================

    override suspend fun findAll(limit: Int): Result<List<T>> = withContext(Dispatchers.IO) {
        try {
            val entities = transaction {
                val entities = table.selectAll()
                    .orderBy(getModifiedAtColumn(), SortOrder.DESC)
                    .limit(limit)
                    .map { row -> mapRowToEntity(row) }
                    .toList()

                // Load entities with tags
                loadEntitiesWithTags(entities)
            }

            Result.Success(entities)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find all ${entityType.name.lowercase()}s: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun count(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                table.selectAll().count()
            }
            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to count ${entityType.name.lowercase()}s: ${e.message}",
                    e
                )
            )
        }
    }

    //======================================
    // SearchableRepository Methods
    //======================================

    override suspend fun search(
        query: String,
        limit: Int,
    ): Result<List<T>> = withContext(Dispatchers.IO) {
        try {
            val entities = transaction {
                // Split the query into individual words and create LIKE conditions for each
                val searchTerms = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

                if (searchTerms.isEmpty()) {
                    // If no valid search terms, return empty result
                    return@transaction emptyList()
                }

                // Build a query that requires ALL search terms to be present in the search vector
                var baseQuery = table.selectAll()
                searchTerms.forEach { term ->
                    // Use case-insensitive LIKE by using LOWER() function on both sides
                    baseQuery = baseQuery.andWhere {
                        searchVectorColumn.lowerCase() like "%${term.lowercase()}%"
                    }
                }

                val entities = baseQuery
                    .orderBy(getModifiedAtColumn(), SortOrder.DESC)
                    .limit(limit)
                    .map { row -> mapRowToEntity(row) }
                    .toList()

                // Load entities with tags
                loadEntitiesWithTags(entities)
            }

            Result.Success(entities)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to search ${entityType.name.lowercase()}s: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun countSearch(query: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                // Split the query into individual words and create LIKE conditions for each
                val searchTerms = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

                if (searchTerms.isEmpty()) {
                    // If no valid search terms, return 0
                    return@transaction 0L
                }

                // Build a query that requires ALL search terms to be present in the search vector
                var baseQuery = table.selectAll()
                searchTerms.forEach { term ->
                    // Use case-insensitive LIKE by using LOWER() function on both sides
                    baseQuery = baseQuery.andWhere {
                        searchVectorColumn.lowerCase() like "%${term.lowercase()}%"
                    }
                }

                baseQuery.count()
            }
            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to count search ${entityType.name.lowercase()}s: ${e.message}",
                    e
                )
            )
        }
    }

    //======================================
    // TaggableRepository Methods
    //======================================

    override suspend fun findByTag(
        tag: String,
        limit: Int,
    ): Result<List<T>> = withContext(Dispatchers.IO) {
        try {
            val entities = transaction {
                // Find entity IDs that have the specified tag
                val entityIds = EntityTagsTable
                    .selectAll().where {
                        (EntityTagsTable.entityType eq entityType.name) and (EntityTagsTable.tag eq tag)
                    }
                    .map { it[EntityTagsTable.entityId] }

                if (entityIds.isEmpty()) {
                    return@transaction emptyList()
                }

                // Query entities by IDs
                val entities = table.selectAll()
                    .where { idColumn inList entityIds }
                    .orderBy(getModifiedAtColumn(), SortOrder.DESC)
                    .limit(limit)
                    .map { row -> mapRowToEntity(row) }
                    .toList()

                // Load entities with tags
                loadEntitiesWithTags(entities)
            }

            Result.Success(entities)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find ${entityType.name.lowercase()}s by tag: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun findByTags(
        tags: List<String>,
        matchAll: Boolean,
        limit: Int,
    ): Result<List<T>> = withContext(Dispatchers.IO) {
        try {
            val entities = transaction {
                val entityIds = if (matchAll) {
                    // Find entities that have ALL specified tags
                    EntityTagsTable
                        .selectAll().where {
                            (EntityTagsTable.entityType eq entityType.name) and (EntityTagsTable.tag inList tags)
                        }
                        .groupBy(EntityTagsTable.entityId)
                        .having { EntityTagsTable.entityId.count() eq tags.size.toLong() }
                        .map { it[EntityTagsTable.entityId] }
                } else {
                    // Find entities that have ANY of the specified tags
                    EntityTagsTable
                        .selectAll().where {
                            (EntityTagsTable.entityType eq entityType.name) and (EntityTagsTable.tag inList tags)
                        }
                        .map { it[EntityTagsTable.entityId] }
                        .distinct()
                }

                if (entityIds.isEmpty()) {
                    return@transaction emptyList()
                }

                // Query entities by IDs
                val entities = table.selectAll()
                    .where { idColumn inList entityIds }
                    .orderBy(getModifiedAtColumn(), SortOrder.DESC)
                    .limit(limit)
                    .map { row -> mapRowToEntity(row) }
                    .toList()

                // Load entities with tags
                loadEntitiesWithTags(entities)
            }

            Result.Success(entities)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find ${entityType.name.lowercase()}s by tags: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun getAllTags(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val tags = transaction {
                EntityTagsTable
                    .selectAll().where { EntityTagsTable.entityType eq entityType.name }
                    .map { it[EntityTagsTable.tag] }
                    .distinct()
                    .sorted()
            }
            Result.Success(tags)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to get all tags: ${e.message}", e))
        }
    }

    override suspend fun countByTag(tag: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                val entityIds = EntityTagsTable
                    .selectAll().where {
                        (EntityTagsTable.entityType eq entityType.name) and (EntityTagsTable.tag eq tag)
                    }
                    .map { it[EntityTagsTable.entityId] }
                    .distinct()

                if (entityIds.isEmpty()) {
                    0L
                } else {
                    table.selectAll().where { idColumn inList entityIds }.count()
                }
            }
            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to count ${entityType.name.lowercase()}s by tag: ${e.message}",
                    e
                )
            )
        }
    }

    //======================================
    // FilterableRepository Methods
    //======================================

    override suspend fun findByFilters(
        projectId: UUID?,
        statusFilter: io.github.jpicklyk.mcptask.domain.model.StatusFilter<TStatus>?,
        priorityFilter: io.github.jpicklyk.mcptask.domain.model.StatusFilter<TPriority>?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<T>> = withContext(Dispatchers.IO) {
        try {
            val entities = transaction {
                // Start with a base query
                var query = table.selectAll()

                // Apply project filter if this implementation supports it
                // (will be overridden in project-scoped repositories)

                // Apply status filter using multi-value logic
                if (statusFilter != null && !statusFilter.isEmpty() && getStatusColumn() != null) {
                    val statusColumn = getStatusColumn()!!
                    // Include: status IN (...)
                    if (statusFilter.include.isNotEmpty()) {
                        query = query.where { statusColumn inList statusFilter.include }
                    }
                    // Exclude: status NOT IN (...)
                    if (statusFilter.exclude.isNotEmpty()) {
                        query = query.andWhere { statusColumn notInList statusFilter.exclude }
                    }
                }

                // Apply priority filter using multi-value logic
                if (priorityFilter != null && !priorityFilter.isEmpty() && getPriorityColumn() != null) {
                    val priorityColumn = getPriorityColumn()!!
                    // Include: priority IN (...)
                    if (priorityFilter.include.isNotEmpty()) {
                        query = query.andWhere { priorityColumn inList priorityFilter.include }
                    }
                    // Exclude: priority NOT IN (...)
                    if (priorityFilter.exclude.isNotEmpty()) {
                        query = query.andWhere { priorityColumn notInList priorityFilter.exclude }
                    }
                }

                // Apply text search filter
                if (!textQuery.isNullOrBlank()) {
                    // Split the query into individual words and create LIKE conditions for each
                    val searchTerms = textQuery.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    searchTerms.forEach { term ->
                        // Use case-insensitive LIKE by using LOWER() function on both sides
                        query = query.andWhere {
                            searchVectorColumn.lowerCase() like "%${term.lowercase()}%"
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
                    query = query.andWhere { idColumn inList entityIds }
                }

                // Apply ordering and pagination
                query = query.orderBy(getModifiedAtColumn(), SortOrder.DESC)
                query = query.limit(limit)

                // Execute the query and map results
                val entities = query.map { row -> mapRowToEntity(row) }.toList()

                // Load entities with tags
                loadEntitiesWithTags(entities)
            }

            Result.Success(entities)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find ${entityType.name.lowercase()}s by filters: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun countByFilters(
        projectId: UUID?,
        statusFilter: io.github.jpicklyk.mcptask.domain.model.StatusFilter<TStatus>?,
        priorityFilter: io.github.jpicklyk.mcptask.domain.model.StatusFilter<TPriority>?,
        tags: List<String>?,
        textQuery: String?
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                // Start with a base query
                var query = table.selectAll()

                // Apply project filter if this implementation supports it
                // (will be overridden in project-scoped repositories)

                // Apply status filter using multi-value logic
                if (statusFilter != null && !statusFilter.isEmpty() && getStatusColumn() != null) {
                    val statusColumn = getStatusColumn()!!
                    // Include: status IN (...)
                    if (statusFilter.include.isNotEmpty()) {
                        query = query.where { statusColumn inList statusFilter.include }
                    }
                    // Exclude: status NOT IN (...)
                    if (statusFilter.exclude.isNotEmpty()) {
                        query = query.andWhere { statusColumn notInList statusFilter.exclude }
                    }
                }

                // Apply priority filter using multi-value logic
                if (priorityFilter != null && !priorityFilter.isEmpty() && getPriorityColumn() != null) {
                    val priorityColumn = getPriorityColumn()!!
                    // Include: priority IN (...)
                    if (priorityFilter.include.isNotEmpty()) {
                        query = query.andWhere { priorityColumn inList priorityFilter.include }
                    }
                    // Exclude: priority NOT IN (...)
                    if (priorityFilter.exclude.isNotEmpty()) {
                        query = query.andWhere { priorityColumn notInList priorityFilter.exclude }
                    }
                }

                // Apply text search filter
                if (!textQuery.isNullOrBlank()) {
                    // Split the query into individual words and create LIKE conditions for each
                    val searchTerms = textQuery.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    searchTerms.forEach { term ->
                        // Use case-insensitive LIKE by using LOWER() function on both sides
                        query = query.andWhere {
                            searchVectorColumn.lowerCase() like "%${term.lowercase()}%"
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
                        return@transaction 0L
                    }
                    query = query.andWhere { idColumn inList entityIds }
                }

                // Count the results
                query.count()
            }

            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to count ${entityType.name.lowercase()}s by filters: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun findByStatus(
        status: TStatus,
        limit: Int,
    ): Result<List<T>> = withContext(Dispatchers.IO) {
        try {
            if (getStatusColumn() == null) {
                return@withContext Result.Error(RepositoryError.ValidationError("Status filtering not supported for ${entityType.name}"))
            }

            val entities = transaction {
                val entities = table.selectAll()
                    .where { getStatusColumn()!! eq status }
                    .orderBy(getModifiedAtColumn(), SortOrder.DESC)
                    .limit(limit)
                    .map { row -> mapRowToEntity(row) }
                    .toList()

                // Load entities with tags
                loadEntitiesWithTags(entities)
            }

            Result.Success(entities)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find ${entityType.name.lowercase()}s by status: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun findByPriority(
        priority: TPriority,
        limit: Int,
    ): Result<List<T>> = withContext(Dispatchers.IO) {
        try {
            if (getPriorityColumn() == null) {
                return@withContext Result.Error(RepositoryError.ValidationError("Priority filtering not supported for ${entityType.name}"))
            }

            val entities = transaction {
                val entities = table.selectAll()
                    .where { getPriorityColumn()!! eq priority }
                    .orderBy(getModifiedAtColumn(), SortOrder.DESC)
                    .limit(limit)
                    .map { row -> mapRowToEntity(row) }
                    .toList()

                // Load entities with tags
                loadEntitiesWithTags(entities)
            }

            Result.Success(entities)
        } catch (e: Exception) {
            Result.Error(
                RepositoryError.DatabaseError(
                    "Failed to find ${entityType.name.lowercase()}s by priority: ${e.message}",
                    e
                )
            )
        }
    }

    //======================================
    // Helper Methods
    //======================================

    /**
     * Updates the search vector for the entity.
     */
    private fun updateSearchVector(entity: T) {
        val entityId = getEntityId(entity)
        val searchText = getSearchableText(entity)

        table.update({ idColumn eq entityId }) {
            it[searchVectorColumn] = searchText.lowercase() // Convert to lowercase for consistent searching
        }
    }

    /**
     * Inserts entity tags into the EntityTagsTable.
     */
    private fun insertEntityTags(entityId: UUID, tags: List<String>) {
        val now = Instant.now()
        val entityTypeName = this.entityType.name
        
        // First deduplicate the input tags to avoid constraint violations
        val uniqueInputTags = tags.distinct()
        
        // Get existing tags to avoid duplicates
        val existingTags = EntityTagsTable
            .selectAll().where {
                (EntityTagsTable.entityId eq entityId) and (EntityTagsTable.entityType eq entityTypeName)
            }
            .map { it[EntityTagsTable.tag] }
            .toSet()
        
        // Only insert tags that don't already exist
        val newTags = uniqueInputTags.filter { !existingTags.contains(it) }
        
        newTags.forEach { tag ->
            try {
                EntityTagsTable.insert {
                    it[EntityTagsTable.entityId] = entityId
                    // Use the actual enum name, ensure it's correctly referenced
                    it[EntityTagsTable.entityType] = entityTypeName
                    it[EntityTagsTable.tag] = tag
                    it[EntityTagsTable.createdAt] = now
                }
            } catch (e: Exception) {
                println("ERROR inserting tag '$tag' for $entityTypeName with ID: $entityId - ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Loads tags for each entity in the list.
     */
    protected fun loadEntitiesWithTags(entities: List<T>): List<T> {
        if (entities.isEmpty()) return emptyList()

        // Get all entity IDs
        val entityIds = entities.map { getEntityId(it) }
        val entityTypeName = this.entityType.name

        // Query all tags for these entities in one go
        val entityTagMap = mutableMapOf<UUID, MutableList<String>>()
        EntityTagsTable
            .selectAll().where {
                (EntityTagsTable.entityId inList entityIds) and (EntityTagsTable.entityType eq entityTypeName)
            }
            .forEach { row ->
                val entityId = row[EntityTagsTable.entityId]
                val tag = row[EntityTagsTable.tag]
                entityTagMap.computeIfAbsent(entityId) { mutableListOf() }.add(tag)
            }

        // Create new entities with tags
        return entities.map { entity ->
            val entityId = getEntityId(entity)
            val tags = entityTagMap[entityId] ?: emptyList()
            addTagsToEntity(entity, tags)
        }
    }
}
