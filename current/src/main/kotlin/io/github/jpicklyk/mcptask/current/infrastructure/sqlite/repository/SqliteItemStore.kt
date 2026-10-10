package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.ItemFetchResult
import io.github.jpicklyk.mcptask.current.application.port.ItemFilter
import io.github.jpicklyk.mcptask.current.application.port.ItemPage
import io.github.jpicklyk.mcptask.current.application.port.ItemSort
import io.github.jpicklyk.mcptask.current.application.port.ItemStore
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
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
            // The query is a literal substring: LIKE metacharacters in it are escaped, not wildcards.
            val pattern = LikeLiteral.pattern("%${LikeLiteral.escape(query)}%")
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
                // role is nullable since V26; Q31a replaces this fallback with Role?.
                val role = row[WorkItemsTable.role]?.let { Role.fromString(it) } ?: Role.QUEUE
                counts[role] = (counts[role] ?: 0) + row[total].toInt()
            }
        return counts
    }

    override suspend fun find(
        filter: ItemFilter,
        scope: AccessScope,
        page: ItemPage,
        sort: ItemSort,
    ): List<WorkItem> {
        if (scope.rootIds?.isEmpty() == true) return emptyList()
        val at = if (filter.claimStatus != null) clock.unitNow() else null
        return databaseManager.readTx {
            val conditions = conditionsFor(filter, scope, at) ?: return@readTx emptyList()
            ItemQueries
                .applySort(selectWhere(conditions), sort)
                .limit(page.limit)
                .offset(page.offset.toLong())
                .map { WorkItemRows.toWorkItem(it) }
        }
    }

    override suspend fun count(
        filter: ItemFilter,
        scope: AccessScope,
    ): Int {
        if (scope.rootIds?.isEmpty() == true) return 0
        val at = if (filter.claimStatus != null) clock.unitNow() else null
        return databaseManager.readTx {
            val conditions = conditionsFor(filter, scope, at) ?: return@readTx 0
            selectWhere(conditions).count().toInt()
        }
    }

    /**
     * The AND-ed WHERE conditions of [find]/[count], or null when nothing can match (an empty scope or ancestor subtree).
     * Must run inside a transaction: resolving a root set may probe or walk the hierarchy.
     *
     * The access scope's roots and the filter's ancestor are two AND-ed subtree conditions. When the ancestor is
     * itself a listed scope root, its subtree lies inside the scope and the scope condition is dropped (one
     * resolution instead of two). The `tags_include` allowlist is [TagAllowlistOp], the same SQL the search
     * engine applies.
     */
    private fun conditionsFor(
        filter: ItemFilter,
        scope: AccessScope,
        at: Instant?,
    ): List<Op<Boolean>>? {
        val ancestorId = filter.ancestorId
        val scopeRoots = scope.rootIds?.takeUnless { ancestorId != null && ancestorId in it }
        val accessScope = scopeRoots?.let { ScopeResolver.resolve(it) }
        val conditions = ItemQueries.conditions(ItemQueries.specOf(filter), accessScope, at) ?: return null
        if (ancestorId != null) {
            val ancestorScope = ScopeResolver.resolve(setOf(ancestorId))
            if (ancestorScope == ResolvedScope.Empty) return null
            conditions.add(ancestorScope.toCondition())
        }
        if (scope.tagsInclude.isNotEmpty()) conditions.add(TagAllowlistOp(WorkItemsTable.tags, scope.tagsInclude))
        return conditions
    }

    /** SELECT * with [conditions] AND-ed (no WHERE when there are none). */
    private fun selectWhere(conditions: List<Op<Boolean>>) =
        if (conditions.isEmpty()) {
            WorkItemsTable.selectAll()
        } else {
            val combined = conditions.reduce { acc, op -> acc and op }
            WorkItemsTable.selectAll().where { combined }
        }

    /**
     * A range scan on the BLOB primary key: ids are stored as 16 big-endian bytes, so "hex starts with p" is
     * `lo <= id < hi` with lo = p padded with `0` to 32 digits and hi = (p + 1) padded the same way. An odd-length
     * prefix pads its last byte's low nibble; an all-`f` prefix has no upper bound.
     */
    override suspend fun findByIdPrefix(
        prefix: String,
        limit: Int
    ): List<WorkItem> {
        val bounds = IdPrefixRange.of(prefix) ?: return emptyList()
        return databaseManager.readTx {
            val lower = WorkItemsTable.id greaterEq bounds.lower
            val condition = bounds.upper?.let { lower and (WorkItemsTable.id less it) } ?: lower
            WorkItemsTable
                .selectAll()
                .where { condition }
                .orderBy(WorkItemsTable.id, SortOrder.ASC)
                .limit(limit)
                .map { WorkItemRows.toWorkItem(it) }
        }
    }
}

/** The id range a hex prefix selects: `lower <= id < upper`, [upper] null when unbounded. */
internal data class IdPrefixRange(
    val lower: UUID,
    val upper: UUID?,
) {
    companion object {
        private const val HEX_DIGITS = 32
        private val HEX = Regex("^[0-9a-f]+$")

        /** The range for [prefix] (any case), or null when it is empty, not hex, or longer than an id. */
        fun of(prefix: String): IdPrefixRange? {
            val p = prefix.lowercase()
            if (p.isEmpty() || p.length > HEX_DIGITS || !HEX.matches(p)) return null
            val lower = uuidOfHex(p.padEnd(HEX_DIGITS, '0'))
            val upper = increment(p)?.let { uuidOfHex(it.padEnd(HEX_DIGITS, '0')) }
            return IdPrefixRange(lower, upper)
        }

        /** [hex] plus one at its own length, or null when it is all `f` (the carry overflows). */
        private fun increment(hex: String): String? {
            val digits = hex.toCharArray()
            var i = digits.size - 1
            while (i >= 0) {
                val d = Character.digit(digits[i], 16)
                if (d < 15) {
                    digits[i] = Character.forDigit(d + 1, 16)
                    return String(digits)
                }
                digits[i] = '0'
                i--
            }
            return null
        }

        private fun uuidOfHex(hex: String): UUID =
            UUID(java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(hex.substring(16), 16))
    }
}
