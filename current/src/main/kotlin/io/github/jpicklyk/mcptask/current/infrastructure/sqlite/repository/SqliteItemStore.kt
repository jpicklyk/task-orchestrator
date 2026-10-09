package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.ItemFetchResult
import io.github.jpicklyk.mcptask.current.application.port.ItemStore
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * SQLite [ItemStore]: item CRUD plus filter, scope and count queries. Never writes the claim columns on update
 * (the claim store owns them); every stored row is returned, invalid ones carrying their violations.
 */
class SqliteItemStore(
    private val databaseManager: DatabaseManager,
    private val clock: Clock
) : ItemStore {
    override suspend fun ping() {
        databaseManager.readTx {
            exec("SELECT 1") { rs -> rs.next() }
        }
    }

    override suspend fun getById(id: UUID): WorkItem? =
        databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where { WorkItemsTable.id eq id }
                .singleOrNull()
                ?.let { WorkItemRows.toWorkItem(it) }
        }

    /**
     * Inserts a single [WorkItem] row into [WorkItemsTable].
     *
     * **Must be called within an existing transaction** - this function does NOT open its own
     * transaction. Use [create] for the public API that wraps this in a transaction. The claim columns are
     * written here (always null in production; test fixtures construct claimed items through it).
     */
    private fun insertRow(item: WorkItem): WorkItem {
        item.validate()
        WorkItemsTable.insert {
            it[id] = item.id
            it[parentId] = item.parentId
            it[rootId] = item.rootId
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
            it[type] = item.type
            it[properties] = item.properties
            it[createdAt] = item.createdAt
            it[modifiedAt] = item.modifiedAt
            it[roleChangedAt] = item.roleChangedAt
            it[version] = item.version
            it[claimedBy] = item.claimedBy
            it[claimedAt] = item.claimedAt
            it[claimExpiresAt] = item.claimExpiresAt
            it[originalClaimedAt] = item.originalClaimedAt
        }
        return item
    }

    override suspend fun create(item: WorkItem): WorkItem =
        databaseManager.writeTx("WorkItemRepository.create") {
            insertRow(item)
        }

    /**
     * Optimistic-locking update. Returns null when the row does not exist. When the row exists at a
     * different version, re-reads that version in the SAME transaction (unit) and throws
     * [VersionConflictException] with both versions. Does not touch the four claim columns.
     */
    override suspend fun update(item: WorkItem): WorkItem? =
        databaseManager.writeTx("WorkItemRepository.update") {
            item.validate()
            val updatedCount =
                WorkItemsTable.update({
                    (WorkItemsTable.id eq item.id) and (WorkItemsTable.version eq item.version)
                }) {
                    it[parentId] = item.parentId
                    it[rootId] = item.rootId
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
                    it[type] = item.type
                    it[properties] = item.properties
                    it[modifiedAt] = item.modifiedAt
                    it[roleChangedAt] = item.roleChangedAt
                    it[version] = item.version + 1
                }
            if (updatedCount > 0) {
                item.copy(version = item.version + 1)
            } else {
                // Either not found or version mismatch (optimistic locking conflict)
                val actual =
                    WorkItemsTable
                        .select(WorkItemsTable.version)
                        .where { WorkItemsTable.id eq item.id }
                        .singleOrNull()
                        ?.get(WorkItemsTable.version)
                if (actual != null) {
                    throw VersionConflictException(item.id, expected = item.version, actual = actual)
                } else {
                    null
                }
            }
        }

    override suspend fun delete(id: UUID): Boolean =
        databaseManager.writeTx("WorkItemRepository.delete") {
            WorkItemsTable.deleteWhere { WorkItemsTable.id eq id } > 0
        }

    override suspend fun findByParent(
        parentId: UUID,
        limit: Int
    ): List<WorkItem> =
        databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where { WorkItemsTable.parentId eq parentId }
                .limit(limit)
                .map { WorkItemRows.toWorkItem(it) }
        }

    override suspend fun findByRole(
        role: Role,
        limit: Int,
        rootIds: Set<UUID>?
    ): List<WorkItem> =
        databaseManager.readTx {
            val conditions =
                ItemQueries.conditions(ItemQuerySpec(role = role), rootIds?.let { ScopeResolver.resolve(it) }, null)
                    ?: return@readTx emptyList()
            WorkItemsTable
                .selectAll()
                .where { conditions.reduce { acc, op -> acc and op } }
                .limit(limit)
                .map { WorkItemRows.toWorkItem(it) }
        }

    override suspend fun findByDepth(
        depth: Int,
        limit: Int
    ): List<WorkItem> =
        databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where { WorkItemsTable.depth eq depth }
                .limit(limit)
                .map { WorkItemRows.toWorkItem(it) }
        }

    override suspend fun findProjectRoots(): List<WorkItem> =
        databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where {
                    WorkItemsTable.parentId.isNull() and
                        (WorkItemsTable.depth eq 0) and
                        (WorkItemsTable.type eq "project")
                }.map { WorkItemRows.toWorkItem(it) }
        }

    override suspend fun search(
        query: String,
        limit: Int
    ): List<WorkItem> =
        databaseManager.readTx {
            val pattern = "%$query%"
            WorkItemsTable
                .selectAll()
                .where { (WorkItemsTable.title like pattern) or (WorkItemsTable.summary like pattern) }
                .limit(limit)
                .map { WorkItemRows.toWorkItem(it) }
        }

    override suspend fun count(): Long =
        databaseManager.readTx {
            WorkItemsTable.selectAll().count()
        }

    override suspend fun findChildren(parentId: UUID): List<WorkItem> =
        databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where { WorkItemsTable.parentId eq parentId }
                .map { WorkItemRows.toWorkItem(it) }
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
        offset: Int,
        type: String?,
        claimStatus: ClaimStatus?
    ): ItemFetchResult {
        // The text `query` filter was removed with FTS5 (query_items search routes through SearchIndex).
        val at = if (claimStatus != null) clock.unitNow() else null
        val spec =
            ItemQuerySpec(
                parentId = parentId,
                depth = depth,
                role = role,
                priority = priority,
                tags = tags,
                type = type,
                createdAfter = createdAfter,
                createdBefore = createdBefore,
                modifiedAfter = modifiedAfter,
                modifiedBefore = modifiedBefore,
                roleChangedAfter = roleChangedAfter,
                roleChangedBefore = roleChangedBefore,
                claimStatus = claimStatus
            )
        return databaseManager.readTx {
            val items =
                ItemQueries
                    .applySort(selectBySpec(spec, null, at), sortBy, sortOrder)
                    .limit(limit)
                    .offset(offset.coerceAtLeast(0).toLong()) // No upper bound needed: absurd values safely return empty results
                    .map { WorkItemRows.toWorkItem(it) }
            ItemFetchResult(items, skipped = 0)
        }
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
        roleChangedBefore: Instant?,
        type: String?,
        claimStatus: ClaimStatus?
    ): Int {
        val at = if (claimStatus != null) clock.unitNow() else null
        val spec =
            ItemQuerySpec(
                parentId = parentId,
                depth = depth,
                role = role,
                priority = priority,
                tags = tags,
                type = type,
                createdAfter = createdAfter,
                createdBefore = createdBefore,
                modifiedAfter = modifiedAfter,
                modifiedBefore = modifiedBefore,
                roleChangedAfter = roleChangedAfter,
                roleChangedBefore = roleChangedBefore,
                claimStatus = claimStatus
            )
        return databaseManager.readTx {
            selectBySpec(spec, null, at).count().toInt()
        }
    }

    override suspend fun countChildrenByRole(parentId: UUID): Map<Role, Int> =
        databaseManager.readTx {
            countByRole(WorkItemsTable.parentId eq parentId)
        }

    override suspend fun findRootItems(
        limit: Int,
        offset: Int,
        excludeTerminal: Boolean,
    ): ItemFetchResult =
        databaseManager.readTx {
            // Newest-first for deterministic, dashboard-relevant pagination; `id` is the secondary key so rows
            // sharing a createdAt millisecond still get a total order (offset paging returns disjoint pages).
            val items =
                WorkItemsTable
                    .selectAll()
                    .where { rootItemsCondition(excludeTerminal) }
                    .orderBy(WorkItemsTable.createdAt, SortOrder.DESC)
                    .orderBy(WorkItemsTable.id, SortOrder.ASC)
                    .limit(limit)
                    .offset(offset.coerceAtLeast(0).toLong())
                    .map { WorkItemRows.toWorkItem(it) }
            ItemFetchResult(items, skipped = 0)
        }

    override suspend fun countRootItems(excludeTerminal: Boolean): Long =
        databaseManager.readTx {
            WorkItemsTable.selectAll().where { rootItemsCondition(excludeTerminal) }.count()
        }

    /** Shared WHERE condition for root-item queries: `parentId IS NULL`, optionally excluding terminal-role rows. */
    private fun rootItemsCondition(excludeTerminal: Boolean): Op<Boolean> {
        val isRoot = WorkItemsTable.parentId.isNull()
        return if (excludeTerminal) isRoot and (WorkItemsTable.role neq Role.TERMINAL.name.lowercase()) else isRoot
    }

    override suspend fun findByIds(ids: Set<UUID>): List<WorkItem> {
        if (ids.isEmpty()) return emptyList()
        return databaseManager.readTx {
            WorkItemRows.loadByIds(ids)
        }
    }

    override suspend fun deleteAll(ids: Set<UUID>): Int {
        if (ids.isEmpty()) return 0
        return databaseManager.writeTx("WorkItemRepository.deleteAll") {
            ids.chunked(SQL_IN_CHUNK_SIZE).sumOf { chunk ->
                val entityIds = chunk.map { EntityID(it, WorkItemsTable) }
                WorkItemsTable.deleteWhere { WorkItemsTable.id inList entityIds }
            }
        }
    }

    override suspend fun findByIdPrefix(
        prefix: String,
        limit: Int
    ): List<WorkItem> {
        val normalizedPrefix = prefix.lowercase()
        return databaseManager.readTx {
            // UUIDs are BLOBs: HEX(id) yields uppercase hex without dashes, so lower-case it for a prefix match.
            val hexId = LowerCase(CustomFunction("HEX", VarCharColumnType(32), WorkItemsTable.id))
            WorkItemsTable
                .selectAll()
                .where { hexId like "$normalizedPrefix%" }
                .limit(limit)
                .map { WorkItemRows.toWorkItem(it) }
        }
    }

    override suspend fun findInScope(
        rootIds: Set<UUID>,
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
        offset: Int,
        type: String?,
        claimStatus: ClaimStatus?,
    ): List<WorkItem> {
        if (rootIds.isEmpty()) return emptyList()
        val at = if (claimStatus != null) clock.unitNow() else null
        val spec =
            ItemQuerySpec(
                parentId = parentId,
                depth = depth,
                role = role,
                priority = priority,
                tags = tags,
                type = type,
                createdAfter = createdAfter,
                createdBefore = createdBefore,
                modifiedAfter = modifiedAfter,
                modifiedBefore = modifiedBefore,
                roleChangedAfter = roleChangedAfter,
                roleChangedBefore = roleChangedBefore,
                claimStatus = claimStatus
            )
        return databaseManager.readTx {
            val scope = ScopeResolver.resolve(rootIds)
            if (scope == ResolvedScope.Empty) return@readTx emptyList()
            ItemQueries
                .applySort(selectBySpec(spec, scope, at), sortBy, sortOrder)
                .limit(limit)
                .offset(offset.coerceAtLeast(0).toLong())
                .map { WorkItemRows.toWorkItem(it) }
        }
    }

    override suspend fun countInScope(
        rootIds: Set<UUID>,
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
        type: String?,
        claimStatus: ClaimStatus?,
    ): Int {
        if (rootIds.isEmpty()) return 0
        val at = if (claimStatus != null) clock.unitNow() else null
        val spec =
            ItemQuerySpec(
                parentId = parentId,
                depth = depth,
                role = role,
                priority = priority,
                tags = tags,
                type = type,
                createdAfter = createdAfter,
                createdBefore = createdBefore,
                modifiedAfter = modifiedAfter,
                modifiedBefore = modifiedBefore,
                roleChangedAfter = roleChangedAfter,
                roleChangedBefore = roleChangedBefore,
                claimStatus = claimStatus
            )
        return databaseManager.readTx {
            val scope = ScopeResolver.resolve(rootIds)
            if (scope == ResolvedScope.Empty) return@readTx 0
            selectBySpec(spec, scope, at).count().toInt()
        }
    }

    override suspend fun countInScopeByRole(rootIds: Set<UUID>): Map<Role, Int> {
        if (rootIds.isEmpty()) return emptyMap()
        return databaseManager.readTx {
            val scope = ScopeResolver.resolve(rootIds)
            if (scope == ResolvedScope.Empty) return@readTx emptyMap()
            countByRole(scope.toCondition())
        }
    }

    /** Counts rows matching [condition] grouped by role (unknown stored roles count as QUEUE, as the mapper does). */
    private fun countByRole(condition: Op<Boolean>): Map<Role, Int> {
        val total = WorkItemsTable.id.count()
        val counts = mutableMapOf<Role, Int>()
        WorkItemsTable
            .select(WorkItemsTable.role, total)
            .where { condition }
            .groupBy(WorkItemsTable.role)
            .forEach { row ->
                val role = Role.fromString(row[WorkItemsTable.role]) ?: Role.QUEUE
                counts[role] = (counts[role] ?: 0) + row[total].toInt()
            }
        return counts
    }

    /** The SELECT for [spec] under an optional [scope]; an empty scope yields a query that matches nothing. */
    private fun selectBySpec(
        spec: ItemQuerySpec,
        scope: ResolvedScope?,
        at: Instant?
    ): Query {
        val conditions = ItemQueries.conditions(spec, scope, at) ?: return WorkItemsTable.selectAll().where { Op.FALSE }
        return if (conditions.isEmpty()) {
            WorkItemsTable.selectAll()
        } else {
            val combined = conditions.reduce { acc, op -> acc and op }
            WorkItemsTable.selectAll().where { combined }
        }
    }
}
