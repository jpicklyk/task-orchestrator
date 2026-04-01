package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.inList
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.like
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of WorkItemRepository.
 */
class SQLiteWorkItemRepository(
    private val databaseManager: DatabaseManager
) : WorkItemRepository {
    private val logger = LoggerFactory.getLogger(SQLiteWorkItemRepository::class.java)

    override suspend fun getById(id: UUID): Result<WorkItem> =
        databaseManager.suspendedTransaction("Failed to get WorkItem by id") {
            val row = WorkItemsTable.selectAll().where { WorkItemsTable.id eq id }.singleOrNull()
            if (row != null) {
                Result.Success(toWorkItem(row))
            } else {
                Result.Error(RepositoryError.NotFound(id, "WorkItem not found with id: $id"))
            }
        }

    override suspend fun create(item: WorkItem): Result<WorkItem> =
        databaseManager.suspendedTransaction("Failed to create WorkItem") {
            item.validate()
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

    override suspend fun update(item: WorkItem): Result<WorkItem> =
        databaseManager.suspendedTransaction("Failed to update WorkItem") {
            item.validate()
            val updatedCount =
                WorkItemsTable.update({
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

    override suspend fun delete(id: UUID): Result<Boolean> =
        databaseManager.suspendedTransaction("Failed to delete WorkItem") {
            val deletedCount = WorkItemsTable.deleteWhere { WorkItemsTable.id eq id }
            Result.Success(deletedCount > 0)
        }

    override suspend fun findByParent(
        parentId: UUID,
        limit: Int
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find WorkItems by parent") {
            val items =
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.parentId eq parentId }
                    .limit(limit)
                    .mapNotNull { toWorkItemOrNull(it) }
            Result.Success(items)
        }

    override suspend fun findByRole(
        role: Role,
        limit: Int
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find WorkItems by role") {
            val items =
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.role eq role.name.lowercase() }
                    .limit(limit)
                    .mapNotNull { toWorkItemOrNull(it) }
            Result.Success(items)
        }

    override suspend fun findByDepth(
        depth: Int,
        limit: Int
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find WorkItems by depth") {
            val items =
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.depth eq depth }
                    .limit(limit)
                    .mapNotNull { toWorkItemOrNull(it) }
            Result.Success(items)
        }

    override suspend fun findRoot(): Result<WorkItem?> =
        databaseManager.suspendedTransaction("Failed to find root WorkItem") {
            val row =
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.parentId.isNull() and (WorkItemsTable.depth eq 0) }
                    .singleOrNull()
            Result.Success(row?.let { toWorkItemOrNull(it) })
        }

    override suspend fun search(
        query: String,
        limit: Int
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to search WorkItems") {
            val pattern = "%$query%"
            val items =
                WorkItemsTable
                    .selectAll()
                    .where {
                        (WorkItemsTable.title like pattern) or (WorkItemsTable.summary like pattern)
                    }.limit(limit)
                    .mapNotNull { toWorkItemOrNull(it) }
            Result.Success(items)
        }

    override suspend fun count(): Result<Long> =
        databaseManager.suspendedTransaction("Failed to count WorkItems") {
            val count = WorkItemsTable.selectAll().count()
            Result.Success(count)
        }

    override suspend fun findChildren(parentId: UUID): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find children of WorkItem") {
            val items =
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.parentId eq parentId }
                    .mapNotNull { toWorkItemOrNull(it) }
            Result.Success(items)
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
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find WorkItems by filters") {
            val baseQuery =
                buildFilteredQuery(
                    parentId,
                    depth,
                    role,
                    priority,
                    tags,
                    query,
                    createdAfter,
                    createdBefore,
                    modifiedAfter,
                    modifiedBefore,
                    roleChangedAfter,
                    roleChangedBefore
                )

            // Determine sort column and order
            val sortColumn =
                when (sortBy?.lowercase()) {
                    "created" -> WorkItemsTable.createdAt
                    "modified" -> WorkItemsTable.modifiedAt
                    "priority" -> WorkItemsTable.priority
                    else -> WorkItemsTable.createdAt
                }
            val order =
                when (sortOrder?.lowercase()) {
                    "asc" -> SortOrder.ASC
                    "desc" -> SortOrder.DESC
                    else -> SortOrder.DESC
                }

            val items =
                baseQuery
                    .orderBy(sortColumn, order)
                    .limit(limit)
                    .offset(offset.coerceAtLeast(0).toLong()) // No upper bound needed — absurd values safely return empty results
                    .mapNotNull { toWorkItemOrNull(it) }

            Result.Success(items)
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
    ): Result<Int> =
        databaseManager.suspendedTransaction("Failed to count WorkItems by filters") {
            val count =
                buildFilteredQuery(
                    parentId,
                    depth,
                    role,
                    priority,
                    tags,
                    query,
                    createdAfter,
                    createdBefore,
                    modifiedAfter,
                    modifiedBefore,
                    roleChangedAfter,
                    roleChangedBefore
                ).count()

            Result.Success(count.toInt())
        }

    override suspend fun countChildrenByRole(parentId: UUID): Result<Map<Role, Int>> =
        databaseManager.suspendedTransaction("Failed to count children by role") {
            val counts =
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.parentId eq parentId }
                    .mapNotNull { toWorkItemOrNull(it) }
                    .groupBy { it.role }
                    .mapValues { (_, items) -> items.size }
            Result.Success(counts)
        }

    override suspend fun findRootItems(limit: Int): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find root items") {
            val items =
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.parentId.isNull() }
                    .limit(limit)
                    .mapNotNull { toWorkItemOrNull(it) }
            Result.Success(items)
        }

    override suspend fun findDescendants(id: UUID): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find descendants") {
            val results = mutableListOf<WorkItem>()
            val queue = ArrayDeque<UUID>()
            queue.add(id)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val children =
                    WorkItemsTable
                        .selectAll()
                        .where { WorkItemsTable.parentId eq current }
                        .mapNotNull { toWorkItemOrNull(it) }
                results.addAll(children)
                queue.addAll(children.map { it.id })
            }

            Result.Success(results)
        }

    override suspend fun findByIds(ids: Set<UUID>): Result<List<WorkItem>> {
        if (ids.isEmpty()) return Result.Success(emptyList())
        return databaseManager.suspendedTransaction("Failed to find items by IDs") {
            val entityIds = ids.map { EntityID(it, WorkItemsTable) }
            Result.Success(
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.id inList entityIds }
                    .mapNotNull { toWorkItemOrNull(it) }
            )
        }
    }

    override suspend fun deleteAll(ids: Set<UUID>): Result<Int> {
        if (ids.isEmpty()) return Result.Success(0)
        return databaseManager.suspendedTransaction("Failed to bulk-delete WorkItems") {
            val entityIds = ids.map { EntityID(it, WorkItemsTable) }
            val count = WorkItemsTable.deleteWhere { WorkItemsTable.id inList entityIds }
            Result.Success(count)
        }
    }

    override suspend fun findByIdPrefix(
        prefix: String,
        limit: Int
    ): Result<List<WorkItem>> {
        val normalizedPrefix = prefix.lowercase()
        return databaseManager.suspendedTransaction("Failed to find WorkItems by ID prefix") {
            // Convert UUID id column to lowercase hex string (no dashes) for prefix matching.
            // H2 uses RAWTOHEX() for native UUID columns; SQLite uses HEX() for BLOB columns.
            // Both produce uppercase hex without dashes — wrap in LOWER for case-insensitive match.
            val hexFunctionName = if (currentDialect is H2Dialect) "RAWTOHEX" else "HEX"
            val hexId =
                LowerCase(
                    CustomFunction(hexFunctionName, VarCharColumnType(32), WorkItemsTable.id),
                )
            val items =
                WorkItemsTable
                    .selectAll()
                    .where { hexId like "$normalizedPrefix%" }
                    .limit(limit)
                    .mapNotNull { toWorkItemOrNull(it) }
            Result.Success(items)
        }
    }

    override suspend fun findAncestorChains(itemIds: Set<UUID>): Result<Map<UUID, List<WorkItem>>> {
        if (itemIds.isEmpty()) return Result.Success(emptyMap())
        return databaseManager.suspendedTransaction("Failed to find ancestor chains") {
            // Cache all fetched items by UUID string
            val cache = mutableMapOf<String, WorkItem>()

            // Fetch the input items themselves first
            val inputEntityIds = itemIds.map { EntityID(it, WorkItemsTable) }
            val inputItems =
                WorkItemsTable
                    .selectAll()
                    .where { WorkItemsTable.id inList inputEntityIds }
                    .mapNotNull { toWorkItemOrNull(it) }
            inputItems.forEach { cache[it.id.toString()] = it }

            // BFS upward: collect all parentIds that need fetching
            var toFetch = inputItems.mapNotNull { it.parentId }.map { it.toString() }.toSet() - cache.keys
            while (toFetch.isNotEmpty()) {
                val fetchEntityIds = toFetch.map { EntityID(UUID.fromString(it), WorkItemsTable) }
                val fetched =
                    WorkItemsTable
                        .selectAll()
                        .where { WorkItemsTable.id inList fetchEntityIds }
                        .mapNotNull { toWorkItemOrNull(it) }
                fetched.forEach { cache[it.id.toString()] = it }
                toFetch = fetched.mapNotNull { it.parentId }.map { it.toString() }.toSet() - cache.keys
            }

            // Build ancestor chains for each input itemId
            val result =
                itemIds.associateWith { itemId ->
                    val chain = mutableListOf<WorkItem>()
                    val visited = mutableSetOf<String>()
                    var current = cache[itemId.toString()]
                    var parentIdStr = current?.parentId?.toString()
                    while (parentIdStr != null) {
                        if (parentIdStr in visited) {
                            logger.warn("Cycle detected in ancestor chain for item $itemId at parent $parentIdStr — breaking")
                            break
                        }
                        visited.add(parentIdStr)
                        val ancestor = cache[parentIdStr] ?: break
                        chain.add(0, ancestor) // prepend so order is root -> direct parent
                        parentIdStr = ancestor.parentId?.toString()
                    }
                    chain as List<WorkItem>
                }
            Result.Success(result)
        }
    }

    /**
     * Build a tag filter that matches tags at word boundaries within a comma-separated string.
     * Builds a filtered SELECT query from optional filter parameters.
     * Shared by [findByFilters] and [countByFilters] to avoid duplicating condition construction.
     */
    private fun buildFilteredQuery(
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
    ): Query {
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

        return if (conditions.isEmpty()) {
            WorkItemsTable.selectAll()
        } else {
            val combined = conditions.reduce { acc, op -> acc and op }
            WorkItemsTable.selectAll().where { combined }
        }
    }

    /**
     * Handles: tag alone ("bug"), at start ("bug,feature"), in middle ("alpha,bug,beta"), at end ("alpha,bug").
     */
    private fun buildTagFilter(tags: List<String>): Op<Boolean> =
        tags
            .map { tag ->
                val t = tag.trim().lowercase()
                (WorkItemsTable.tags eq t) or
                    (WorkItemsTable.tags like "$t,%") or
                    (WorkItemsTable.tags like "%,$t,%") or
                    (WorkItemsTable.tags like "%,$t")
            }.reduce { acc, op -> acc or op }

    private fun toWorkItem(row: ResultRow): WorkItem =
        WorkItem(
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

    /**
     * Safe variant of [toWorkItem] that returns null on failure, for bulk-read operations.
     *
     * Returns null and logs a warning if a row contains data that fails domain validation
     * (e.g. an oversized title written by an older version of the server). This prevents
     * a single corrupt row from crashing an entire list query.
     */
    private fun toWorkItemOrNull(row: ResultRow): WorkItem? =
        try {
            toWorkItem(row)
        } catch (e: Exception) {
            logger.warn(
                "Skipping corrupt WorkItem row (id={}): {}",
                runCatching { row[WorkItemsTable.id].value }.getOrNull(),
                e.message
            )
            null
        }
}
