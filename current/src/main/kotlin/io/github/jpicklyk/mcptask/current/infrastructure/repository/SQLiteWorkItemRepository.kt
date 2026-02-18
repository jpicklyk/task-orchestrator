package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.inList
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.like
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of WorkItemRepository.
 */
class SQLiteWorkItemRepository(private val databaseManager: DatabaseManager) : WorkItemRepository {

    private val logger = LoggerFactory.getLogger(SQLiteWorkItemRepository::class.java)

    override suspend fun getById(id: UUID): Result<WorkItem> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val row = WorkItemsTable.selectAll().where { WorkItemsTable.id eq id }.singleOrNull()
            if (row != null) {
                Result.Success(mapRowToWorkItem(row))
            } else {
                Result.Error(RepositoryError.NotFound(id, "WorkItem not found with id: $id"))
            }
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to get WorkItem by id: ${e.message}", e))
    }

    override suspend fun create(item: WorkItem): Result<WorkItem> = try {
        item.validate()
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            WorkItemsTable.insert {
                it[id] = item.id
                it[parentId] = item.parentId
                it[title] = item.title
                it[description] = item.description
                it[summary] = item.summary
                it[role] = item.role.name.lowercase()
                it[statusLabel] = item.statusLabel
                it[previousRole] = item.previousRole?.name?.lowercase()
                it[priority] = item.priority.name.lowercase()
                it[complexity] = item.complexity
                it[requiresVerification] = item.requiresVerification
                it[depth] = item.depth
                it[metadata] = item.metadata
                it[tags] = item.tags
                it[createdAt] = item.createdAt
                it[modifiedAt] = item.modifiedAt
                it[roleChangedAt] = item.roleChangedAt
                it[version] = item.version
            }
            Result.Success(item)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to create WorkItem: ${e.message}", e))
    }

    override suspend fun update(item: WorkItem): Result<WorkItem> = try {
        item.validate()
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val updatedCount = WorkItemsTable.update({
                (WorkItemsTable.id eq item.id) and (WorkItemsTable.version eq item.version)
            }) {
                it[parentId] = item.parentId
                it[title] = item.title
                it[description] = item.description
                it[summary] = item.summary
                it[role] = item.role.name.lowercase()
                it[statusLabel] = item.statusLabel
                it[previousRole] = item.previousRole?.name?.lowercase()
                it[priority] = item.priority.name.lowercase()
                it[complexity] = item.complexity
                it[requiresVerification] = item.requiresVerification
                it[depth] = item.depth
                it[metadata] = item.metadata
                it[tags] = item.tags
                it[modifiedAt] = item.modifiedAt
                it[roleChangedAt] = item.roleChangedAt
                it[version] = item.version + 1
            }
            if (updatedCount > 0) {
                Result.Success(item.copy(version = item.version + 1))
            } else {
                // Either not found or version mismatch (optimistic locking conflict)
                val exists = WorkItemsTable.selectAll().where { WorkItemsTable.id eq item.id }.count() > 0
                if (exists) {
                    Result.Error(RepositoryError.ConflictError("WorkItem was modified by another transaction (version mismatch)"))
                } else {
                    Result.Error(RepositoryError.NotFound(item.id, "WorkItem not found with id: ${item.id}"))
                }
            }
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to update WorkItem: ${e.message}", e))
    }

    override suspend fun delete(id: UUID): Result<Boolean> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val deletedCount = WorkItemsTable.deleteWhere { WorkItemsTable.id eq id }
            Result.Success(deletedCount > 0)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to delete WorkItem: ${e.message}", e))
    }

    override suspend fun findByParent(parentId: UUID, limit: Int): Result<List<WorkItem>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val items = WorkItemsTable.selectAll()
                .where { WorkItemsTable.parentId eq parentId }
                .limit(limit)
                .mapNotNull { mapRowToWorkItemSafe(it) }
            Result.Success(items)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find WorkItems by parent: ${e.message}", e))
    }

    override suspend fun findByRole(role: Role, limit: Int): Result<List<WorkItem>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val items = WorkItemsTable.selectAll()
                .where { WorkItemsTable.role eq role.name.lowercase() }
                .limit(limit)
                .mapNotNull { mapRowToWorkItemSafe(it) }
            Result.Success(items)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find WorkItems by role: ${e.message}", e))
    }

    override suspend fun findByDepth(depth: Int, limit: Int): Result<List<WorkItem>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val items = WorkItemsTable.selectAll()
                .where { WorkItemsTable.depth eq depth }
                .limit(limit)
                .mapNotNull { mapRowToWorkItemSafe(it) }
            Result.Success(items)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find WorkItems by depth: ${e.message}", e))
    }

    override suspend fun findRoot(): Result<WorkItem?> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val row = WorkItemsTable.selectAll()
                .where { WorkItemsTable.parentId.isNull() and (WorkItemsTable.depth eq 0) }
                .singleOrNull()
            Result.Success(row?.let { mapRowToWorkItemSafe(it) })
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find root WorkItem: ${e.message}", e))
    }

    override suspend fun search(query: String, limit: Int): Result<List<WorkItem>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val pattern = "%$query%"
            val items = WorkItemsTable.selectAll()
                .where {
                    (WorkItemsTable.title like pattern) or (WorkItemsTable.summary like pattern)
                }
                .limit(limit)
                .mapNotNull { mapRowToWorkItemSafe(it) }
            Result.Success(items)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to search WorkItems: ${e.message}", e))
    }

    override suspend fun count(): Result<Long> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val count = WorkItemsTable.selectAll().count()
            Result.Success(count)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to count WorkItems: ${e.message}", e))
    }

    override suspend fun findChildren(parentId: UUID): Result<List<WorkItem>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val items = WorkItemsTable.selectAll()
                .where { WorkItemsTable.parentId eq parentId }
                .mapNotNull { mapRowToWorkItemSafe(it) }
            Result.Success(items)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find children of WorkItem: ${e.message}", e))
    }

    override suspend fun findByFilters(
        parentId: UUID?,
        depth: Int?,
        role: Role?,
        priority: Priority?,
        tags: List<String>?,
        query: String?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        modifiedAfter: Instant?,
        modifiedBefore: Instant?,
        roleChangedAfter: Instant?,
        roleChangedBefore: Instant?,
        sortBy: String?,
        sortOrder: String?,
        limit: Int,
        offset: Int
    ): Result<List<WorkItem>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val conditions = mutableListOf<Op<Boolean>>()

            parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }
            depth?.let { conditions.add(WorkItemsTable.depth eq it) }
            role?.let { conditions.add(WorkItemsTable.role eq it.name.lowercase()) }
            priority?.let { conditions.add(WorkItemsTable.priority eq it.name.lowercase()) }
            tags?.takeIf { it.isNotEmpty() }?.let { conditions.add(buildTagFilter(it)) }
            query?.let {
                val pattern = "%$it%"
                conditions.add(
                    (WorkItemsTable.title like pattern) or (WorkItemsTable.summary like pattern)
                )
            }
            createdAfter?.let { conditions.add(WorkItemsTable.createdAt greaterEq it) }
            createdBefore?.let { conditions.add(WorkItemsTable.createdAt lessEq it) }
            modifiedAfter?.let { conditions.add(WorkItemsTable.modifiedAt greaterEq it) }
            modifiedBefore?.let { conditions.add(WorkItemsTable.modifiedAt lessEq it) }
            roleChangedAfter?.let { conditions.add(WorkItemsTable.roleChangedAt greaterEq it) }
            roleChangedBefore?.let { conditions.add(WorkItemsTable.roleChangedAt lessEq it) }

            val baseQuery = if (conditions.isEmpty()) {
                WorkItemsTable.selectAll()
            } else {
                val combined = conditions.reduce { acc, op -> acc and op }
                WorkItemsTable.selectAll().where { combined }
            }

            // Determine sort column and order
            val sortColumn = when (sortBy?.lowercase()) {
                "created" -> WorkItemsTable.createdAt
                "modified" -> WorkItemsTable.modifiedAt
                "priority" -> WorkItemsTable.priority
                else -> WorkItemsTable.createdAt
            }
            val order = when (sortOrder?.lowercase()) {
                "asc" -> SortOrder.ASC
                "desc" -> SortOrder.DESC
                else -> SortOrder.DESC
            }

            val items = baseQuery
                .orderBy(sortColumn, order)
                .limit(limit)
                .offset(offset.toLong())
                .mapNotNull { mapRowToWorkItemSafe(it) }

            Result.Success(items)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find WorkItems by filters: ${e.message}", e))
    }

    override suspend fun countByFilters(
        parentId: UUID?,
        depth: Int?,
        role: Role?,
        priority: Priority?,
        tags: List<String>?,
        query: String?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        modifiedAfter: Instant?,
        modifiedBefore: Instant?,
        roleChangedAfter: Instant?,
        roleChangedBefore: Instant?
    ): Result<Int> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val conditions = mutableListOf<Op<Boolean>>()

            parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }
            depth?.let { conditions.add(WorkItemsTable.depth eq it) }
            role?.let { conditions.add(WorkItemsTable.role eq it.name.lowercase()) }
            priority?.let { conditions.add(WorkItemsTable.priority eq it.name.lowercase()) }
            tags?.takeIf { it.isNotEmpty() }?.let { conditions.add(buildTagFilter(it)) }
            query?.let {
                val pattern = "%$it%"
                conditions.add(
                    (WorkItemsTable.title like pattern) or (WorkItemsTable.summary like pattern)
                )
            }
            createdAfter?.let { conditions.add(WorkItemsTable.createdAt greaterEq it) }
            createdBefore?.let { conditions.add(WorkItemsTable.createdAt lessEq it) }
            modifiedAfter?.let { conditions.add(WorkItemsTable.modifiedAt greaterEq it) }
            modifiedBefore?.let { conditions.add(WorkItemsTable.modifiedAt lessEq it) }
            roleChangedAfter?.let { conditions.add(WorkItemsTable.roleChangedAt greaterEq it) }
            roleChangedBefore?.let { conditions.add(WorkItemsTable.roleChangedAt lessEq it) }

            val count = if (conditions.isEmpty()) {
                WorkItemsTable.selectAll().count()
            } else {
                val combined = conditions.reduce { acc, op -> acc and op }
                WorkItemsTable.selectAll().where { combined }.count()
            }

            Result.Success(count.toInt())
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to count WorkItems by filters: ${e.message}", e))
    }

    override suspend fun countChildrenByRole(parentId: UUID): Result<Map<Role, Int>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val counts = WorkItemsTable.selectAll()
                .where { WorkItemsTable.parentId eq parentId }
                .mapNotNull { mapRowToWorkItemSafe(it) }
                .groupBy { it.role }
                .mapValues { (_, items) -> items.size }
            Result.Success(counts)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to count children by role: ${e.message}", e))
    }

    override suspend fun findRootItems(limit: Int): Result<List<WorkItem>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val items = WorkItemsTable.selectAll()
                .where { WorkItemsTable.parentId.isNull() }
                .limit(limit)
                .mapNotNull { mapRowToWorkItemSafe(it) }
            Result.Success(items)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find root items: ${e.message}", e))
    }

    override suspend fun findDescendants(id: UUID): Result<List<WorkItem>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val results = mutableListOf<WorkItem>()
            val queue = ArrayDeque<UUID>()
            queue.add(id)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val children = WorkItemsTable.selectAll()
                    .where { WorkItemsTable.parentId eq current }
                    .mapNotNull { mapRowToWorkItemSafe(it) }
                results.addAll(children)
                queue.addAll(children.map { it.id })
            }

            Result.Success(results)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find descendants: ${e.message}", e))
    }

    override suspend fun findByIds(ids: Set<UUID>): Result<List<WorkItem>> {
        if (ids.isEmpty()) return Result.Success(emptyList())
        return try {
            newSuspendedTransaction(db = databaseManager.getDatabase()) {
                val entityIds = ids.map { EntityID(it, WorkItemsTable) }
                Result.Success(
                    WorkItemsTable.selectAll()
                        .where { WorkItemsTable.id inList entityIds }
                        .mapNotNull { mapRowToWorkItemSafe(it) }
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to find items by IDs", e)
            Result.Error(RepositoryError.DatabaseError("Failed to find items by IDs: ${e.message}"))
        }
    }

    override suspend fun deleteAll(ids: Set<UUID>): Result<Int> {
        if (ids.isEmpty()) return Result.Success(0)
        return try {
            newSuspendedTransaction(db = databaseManager.getDatabase()) {
                val entityIds = ids.map { EntityID(it, WorkItemsTable) }
                val count = WorkItemsTable.deleteWhere { WorkItemsTable.id inList entityIds }
                Result.Success(count)
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to bulk-delete WorkItems: ${e.message}", e))
        }
    }

    override suspend fun findAncestorChains(itemIds: Set<UUID>): Result<Map<UUID, List<WorkItem>>> {
        if (itemIds.isEmpty()) return Result.Success(emptyMap())
        return try {
            newSuspendedTransaction(db = databaseManager.getDatabase()) {
                // Cache all fetched items by UUID string
                val cache = mutableMapOf<String, WorkItem>()

                // Fetch the input items themselves first
                val inputEntityIds = itemIds.map { EntityID(it, WorkItemsTable) }
                val inputItems = WorkItemsTable.selectAll()
                    .where { WorkItemsTable.id inList inputEntityIds }
                    .mapNotNull { mapRowToWorkItemSafe(it) }
                inputItems.forEach { cache[it.id.toString()] = it }

                // BFS upward: collect all parentIds that need fetching
                var toFetch = inputItems.mapNotNull { it.parentId }.map { it.toString() }.toSet() - cache.keys
                while (toFetch.isNotEmpty()) {
                    val fetchEntityIds = toFetch.map { EntityID(UUID.fromString(it), WorkItemsTable) }
                    val fetched = WorkItemsTable.selectAll()
                        .where { WorkItemsTable.id inList fetchEntityIds }
                        .mapNotNull { mapRowToWorkItemSafe(it) }
                    fetched.forEach { cache[it.id.toString()] = it }
                    toFetch = fetched.mapNotNull { it.parentId }.map { it.toString() }.toSet() - cache.keys
                }

                // Build ancestor chains for each input itemId
                val result = itemIds.associateWith { itemId ->
                    val chain = mutableListOf<WorkItem>()
                    var current = cache[itemId.toString()]
                    var parentIdStr = current?.parentId?.toString()
                    while (parentIdStr != null) {
                        val ancestor = cache[parentIdStr] ?: break
                        chain.add(0, ancestor) // prepend so order is root -> direct parent
                        parentIdStr = ancestor.parentId?.toString()
                    }
                    chain as List<WorkItem>
                }
                Result.Success(result)
            }
        } catch (e: Exception) {
            logger.error("Failed to find ancestor chains", e)
            Result.Error(RepositoryError.DatabaseError("Failed to find ancestor chains: ${e.message}"))
        }
    }

    /**
     * Build a tag filter that matches tags at word boundaries within a comma-separated string.
     * Handles: tag alone ("bug"), at start ("bug,feature"), in middle ("alpha,bug,beta"), at end ("alpha,bug").
     */
    private fun buildTagFilter(tags: List<String>): Op<Boolean> {
        return tags.map { tag ->
            val t = tag.trim().lowercase()
            (WorkItemsTable.tags eq t) or
                (WorkItemsTable.tags like "$t,%") or
                (WorkItemsTable.tags like "%,$t,%") or
                (WorkItemsTable.tags like "%,$t")
        }.reduce { acc, op -> acc or op }
    }

    private fun mapRowToWorkItem(row: ResultRow): WorkItem {
        return WorkItem(
            id = row[WorkItemsTable.id].value,
            parentId = row[WorkItemsTable.parentId],
            title = row[WorkItemsTable.title],
            description = row[WorkItemsTable.description],
            summary = row[WorkItemsTable.summary],
            role = Role.fromString(row[WorkItemsTable.role]) ?: Role.QUEUE,
            statusLabel = row[WorkItemsTable.statusLabel],
            previousRole = row[WorkItemsTable.previousRole]?.let { Role.fromString(it) },
            priority = Priority.fromString(row[WorkItemsTable.priority]) ?: Priority.MEDIUM,
            complexity = row[WorkItemsTable.complexity],
            requiresVerification = row[WorkItemsTable.requiresVerification],
            depth = row[WorkItemsTable.depth],
            metadata = row[WorkItemsTable.metadata],
            tags = row[WorkItemsTable.tags],
            createdAt = row[WorkItemsTable.createdAt],
            modifiedAt = row[WorkItemsTable.modifiedAt],
            roleChangedAt = row[WorkItemsTable.roleChangedAt],
            version = row[WorkItemsTable.version]
        )
    }

    /**
     * Safe variant of [mapRowToWorkItem] for bulk-read operations.
     *
     * Returns null and logs a warning if a row contains data that fails domain validation
     * (e.g. an oversized title written by an older version of the server). This prevents
     * a single corrupt row from crashing an entire list query.
     */
    private fun mapRowToWorkItemSafe(row: ResultRow): WorkItem? {
        return try {
            mapRowToWorkItem(row)
        } catch (e: Exception) {
            logger.warn(
                "Skipping corrupt WorkItem row (id={}): {}",
                runCatching { row[WorkItemsTable.id].value }.getOrNull(),
                e.message
            )
            null
        }
    }
}
