package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.application.service.search.RrfFusion
import io.github.jpicklyk.mcptask.current.domain.model.AncestorChain
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.domain.repository.FTS_CANDIDATE_ROWS
import io.github.jpicklyk.mcptask.current.domain.repository.ItemFetchResult
import io.github.jpicklyk.mcptask.current.domain.repository.ItemSortFields
import io.github.jpicklyk.mcptask.current.domain.repository.MAX_FTS_RESULTS
import io.github.jpicklyk.mcptask.current.domain.repository.MAX_TRAVERSAL_DEPTH
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.SearchHit
import io.github.jpicklyk.mcptask.current.domain.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.domain.repository.SearchResult
import io.github.jpicklyk.mcptask.current.domain.repository.SearchScope
import io.github.jpicklyk.mcptask.current.domain.repository.SelectorMatchCounts
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LiteralOp
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * SQLite implementation of WorkItemRepository.
 */
class SQLiteWorkItemRepository(
    private val databaseManager: DatabaseManager
) : WorkItemRepository {
    private val logger = LoggerFactory.getLogger(SQLiteWorkItemRepository::class.java)

    override suspend fun dbNow(): Instant =
        try {
            // Read CURRENT_TIMESTAMP as a raw string and parse explicitly as UTC.
            //
            // Why string-parse rather than rs.getTimestamp().toInstant():
            //   - SQLite's CURRENT_TIMESTAMP returns "YYYY-MM-DD HH:MM:SS" with NO timezone suffix
            //     and the value is in UTC (per SQLite spec).
            //   - rs.getTimestamp() interprets a no-tz string in the JVM's default zone, then
            //     toInstant() converts to UTC by adding the local offset — producing an Instant
            //     that is shifted by the JVM zone offset relative to the actual stored UTC value.
            //     This causes dbNow() to drift several hours from JVM Instant.now() in any non-UTC
            //     deployment.
            //   - Parsing as LocalDateTime + ZoneOffset.UTC explicitly treats the string as UTC,
            //     which matches the Exposed 1.0.0-beta-5+ timestamp() column behavior (post the
            //     EXPOSED-731 SQLite timestamp fix).
            //   - H2's CURRENT_TIMESTAMP returns "YYYY-MM-DD HH:MM:SS.fffffff-HH" (with a non-
            //     standard offset like "-04" missing the minute portion); we normalize that and
            //     parse via OffsetDateTime.
            suspendTransaction(db = databaseManager.getDatabase()) {
                exec("SELECT CURRENT_TIMESTAMP") { rs ->
                    if (rs.next()) {
                        rs.getString(1)?.let { parseDbTimestamp(it) }
                    } else {
                        null
                    }
                }
            } ?: Instant.now()
        } catch (e: Exception) {
            logger.warn("Failed to fetch DB-side current time, falling back to JVM clock: ${e.message}")
            Instant.now()
        }

    override suspend fun inTransaction(block: suspend () -> Unit) {
        suspendTransaction(db = databaseManager.getDatabase()) {
            block()
        }
    }

    /**
     * Parse a CURRENT_TIMESTAMP-style string from either SQLite or H2 into an [Instant] (UTC).
     *
     * Accepted forms:
     *  - "YYYY-MM-DD HH:MM:SS" — SQLite, treat as UTC
     *  - "YYYY-MM-DD HH:MM:SS.fffffff" — H2 (no offset), treat as UTC
     *  - "YYYY-MM-DD HH:MM:SS.fffffff-HH" — H2 with non-standard short offset; normalized
     *  - "YYYY-MM-DD HH:MM:SS-HH:MM" or "...Z" — full ISO offset, parse directly
     */
    private fun parseDbTimestamp(raw: String): Instant {
        val isoCandidate = raw.replace(" ", "T")
        // Detect whether a timezone suffix is present.
        val tzPattern = Regex("([+-]\\d{2}(:\\d{2})?|Z)$")
        val tzMatch = tzPattern.find(isoCandidate)
        return if (tzMatch != null) {
            val tz = tzMatch.value
            // Normalize H2's short "-04" form to "-04:00" so OffsetDateTime accepts it.
            val normalized =
                if (tz.startsWith("Z") || tz.contains(":")) {
                    isoCandidate
                } else {
                    isoCandidate.dropLast(tz.length) + tz + ":00"
                }
            OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } else {
            // No offset → treat as UTC (SQLite stores CURRENT_TIMESTAMP in UTC).
            LocalDateTime
                .parse(isoCandidate, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC)
        }
    }

    override suspend fun getById(id: UUID): Result<WorkItem> =
        databaseManager.suspendedTransaction("Failed to get WorkItem by id") {
            val row = WorkItemsTable.selectAll().where { WorkItemsTable.id eq id }.singleOrNull()
            if (row != null) {
                Result.Success(toWorkItem(row))
            } else {
                Result.Error(RepositoryError.NotFound(id, "WorkItem not found with id: $id"))
            }
        }

    /**
     * Inserts a single [WorkItem] row into [WorkItemsTable].
     *
     * **Must be called within an existing transaction** — this function does NOT open its own
     * transaction. Use [create] for the public API that wraps this in a transaction.
     */
    internal fun insertRow(item: WorkItem): Result<WorkItem> {
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
        return Result.Success(item)
    }

    override suspend fun create(item: WorkItem): Result<WorkItem> =
        databaseManager.suspendedTransaction("Failed to create WorkItem") {
            insertRow(item)
        }

    override suspend fun update(item: WorkItem): Result<WorkItem> =
        databaseManager.suspendedTransaction("Failed to update WorkItem") {
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
                    it[claimedBy] = item.claimedBy
                    it[claimedAt] = item.claimedAt
                    it[claimExpiresAt] = item.claimExpiresAt
                    it[originalClaimedAt] = item.originalClaimedAt
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
        limit: Int,
        rootIds: Set<UUID>?
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find WorkItems by role") {
            val conditions = mutableListOf<Op<Boolean>>()
            conditions.add(WorkItemsTable.role eq role.name.lowercase())

            if (rootIds != null) {
                val scope = resolveScope(rootIds)
                if (scope == ResolvedScope.Empty) return@suspendedTransaction Result.Success(emptyList())
                conditions.add(scope.toCondition())
            }

            val items =
                WorkItemsTable
                    .selectAll()
                    .where { conditions.reduce { acc, op -> acc and op } }
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

    override suspend fun findProjectRoots(): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find project root WorkItems") {
            val items =
                WorkItemsTable
                    .selectAll()
                    .where {
                        WorkItemsTable.parentId.isNull() and
                            (WorkItemsTable.depth eq 0) and
                            (WorkItemsTable.type eq "project")
                    }.mapNotNull { toWorkItemOrNull(it) }
            Result.Success(items)
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

    /**
     * Applies the shared sortBy/sortOrder mapping (AR-46) used by both [findByFilters] and
     * [findInScope]: every value [ItemSortFields] advertises (`title`, `priority`, `complexity`,
     * `createdAt`, `modifiedAt`) sorts by its own column, plus the legacy `created`/`modified`
     * aliases. `priority` sorts by rank (high/medium/low), not the raw varchar column.
     * `complexity` sorts with NULLs last regardless of direction. Unresolved/null [sortBy]
     * falls back to `createdAt` (already validated at the tool/route boundary; this is
     * defense-in-depth for direct repository callers). A secondary `id ASC` tiebreak makes
     * pagination stable across ties on the primary sort key (offset/limit no longer risk
     * skipping or repeating a row that shares a sort value with its neighbors).
     */
    private fun applySort(
        query: Query,
        sortBy: String?,
        sortOrder: String?,
    ): Query {
        val order =
            when (sortOrder?.lowercase()) {
                "asc" -> SortOrder.ASC
                "desc" -> SortOrder.DESC
                else -> SortOrder.DESC
            }
        val canonical = sortBy?.let { ItemSortFields.canonicalField(it) } ?: ItemSortFields.CREATED_AT

        return when (canonical) {
            ItemSortFields.TITLE ->
                query.orderBy(WorkItemsTable.title, order).orderBy(WorkItemsTable.id, SortOrder.ASC)
            ItemSortFields.PRIORITY -> {
                // Rank HIGH=0, MEDIUM=1, LOW=2. "desc" means high-first, i.e. rank ASCENDING;
                // "asc" means low-first, i.e. rank DESCENDING — the inverse of the raw sortOrder.
                val priorityRank =
                    Case()
                        .When(
                            WorkItemsTable.priority eq Priority.HIGH.name.lowercase(),
                            LiteralOp(IntegerColumnType(), 0),
                        ).When(
                            WorkItemsTable.priority eq Priority.MEDIUM.name.lowercase(),
                            LiteralOp(IntegerColumnType(), 1),
                        ).When(
                            WorkItemsTable.priority eq Priority.LOW.name.lowercase(),
                            LiteralOp(IntegerColumnType(), 2),
                        ).Else(LiteralOp(IntegerColumnType(), 99))
                val rankOrder = if (order == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                query.orderBy(priorityRank, rankOrder).orderBy(WorkItemsTable.id, SortOrder.ASC)
            }
            ItemSortFields.COMPLEXITY -> {
                val complexityOrder = if (order == SortOrder.ASC) SortOrder.ASC_NULLS_LAST else SortOrder.DESC_NULLS_LAST
                query.orderBy(WorkItemsTable.complexity, complexityOrder).orderBy(WorkItemsTable.id, SortOrder.ASC)
            }
            ItemSortFields.MODIFIED_AT ->
                query.orderBy(WorkItemsTable.modifiedAt, order).orderBy(WorkItemsTable.id, SortOrder.ASC)
            else ->
                query.orderBy(WorkItemsTable.createdAt, order).orderBy(WorkItemsTable.id, SortOrder.ASC)
        }
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
        claimStatus: String?
    ): Result<ItemFetchResult> {
        // Fetch DB-side now ONCE before opening the transaction so all claim-freshness
        // comparisons in buildFilteredQuery use the DB clock rather than the JVM clock, and to
        // avoid a nested transaction/savepoint (dbNow() opens its own suspendTransaction).
        val nowFromDb = if (claimStatus != null) dbNow() else Instant.now()

        return databaseManager.suspendedTransaction("Failed to find WorkItems by filters") {
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
                    roleChangedBefore,
                    type,
                    claimStatus,
                    nowFromDb
                )

            // Fetch the raw rows first so a skipped count can be derived (rows.size - items.size)
            // rather than threading a mutable counter through mapNotNull.
            val rows =
                applySort(baseQuery, sortBy, sortOrder)
                    .limit(limit)
                    .offset(offset.coerceAtLeast(0).toLong()) // No upper bound needed — absurd values safely return empty results
                    .toList()
            val items = rows.mapNotNull { toWorkItemOrNull(it) }

            Result.Success(ItemFetchResult(items, skipped = rows.size - items.size))
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
        claimStatus: String?
    ): Result<Int> {
        // Fetch DB-side now ONCE before opening the transaction (see findByFilters) to avoid a
        // nested transaction/savepoint from dbNow().
        val nowFromDb = if (claimStatus != null) dbNow() else Instant.now()

        return databaseManager.suspendedTransaction("Failed to count WorkItems by filters") {
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
                    roleChangedBefore,
                    type,
                    claimStatus,
                    nowFromDb
                ).count()

            Result.Success(count.toInt())
        }
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

    override suspend fun findRootItems(
        limit: Int,
        offset: Int,
        excludeTerminal: Boolean,
    ): Result<ItemFetchResult> =
        databaseManager.suspendedTransaction("Failed to find root items") {
            // Ordered newest-first for deterministic, dashboard-relevant pagination — previously
            // relied on SQLite's unordered natural (oldest-first) row order, which meant the
            // oldest `limit` roots were always returned regardless of how many existed. `id` is a
            // secondary sort key so rows sharing a `createdAt` millisecond still get a stable,
            // total order — required for offset-based paging to return disjoint pages.
            val rows =
                WorkItemsTable
                    .selectAll()
                    .where { rootItemsCondition(excludeTerminal) }
                    .orderBy(WorkItemsTable.createdAt, SortOrder.DESC)
                    .orderBy(WorkItemsTable.id, SortOrder.ASC)
                    .limit(limit)
                    .offset(offset.coerceAtLeast(0).toLong())
                    .toList()
            val items = rows.mapNotNull { toWorkItemOrNull(it) }
            Result.Success(ItemFetchResult(items, skipped = rows.size - items.size))
        }

    override suspend fun countRootItems(excludeTerminal: Boolean): Result<Long> =
        databaseManager.suspendedTransaction("Failed to count root items") {
            Result.Success(WorkItemsTable.selectAll().where { rootItemsCondition(excludeTerminal) }.count())
        }

    /** Shared WHERE condition for root-item queries: `parentId IS NULL`, optionally excluding terminal-role rows. */
    private fun rootItemsCondition(excludeTerminal: Boolean): Op<Boolean> {
        val isRoot = WorkItemsTable.parentId.isNull()
        return if (excludeTerminal) isRoot and (WorkItemsTable.role neq Role.TERMINAL.name.lowercase()) else isRoot
    }

    override suspend fun findDescendants(id: UUID): Result<List<WorkItem>> =
        try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                // currentDialect is only accessible within an active transaction.
                // H2 (test environment): the recursive CTE exec() path uses parameterised
                // UUID binding that is incompatible with H2's native UUID type, and H2
                // interprets the SELECT-returning exec() call as executeUpdate, throwing
                // "Method is not allowed for a query". Fall back to a dialect-agnostic
                // BFS loop using Exposed DSL instead.
                // Production SQLite uses the single-query recursive CTE (faster at depth).
                if (currentDialect is H2Dialect) {
                    // Cycle guard: `visited` is seeded with the start id and gates BOTH the result
                    // list and the queue — the shape resolveScopeIds() already uses. Every item has
                    // at most one parent, so a node can only be reached twice if the parent_id graph
                    // is cyclic; that is corruption, and this traversal feeds cascade deletes and
                    // subtree restamps, so it fails loud rather than returning a partial subtree.
                    // The level counter bounds the descent identically to the SQLite CTE below.
                    val results = mutableListOf<WorkItem>()
                    val visited = mutableSetOf(id)
                    val queue = ArrayDeque<Pair<UUID, Int>>()
                    queue.add(id to 0)
                    while (queue.isNotEmpty()) {
                        val (current, level) = queue.removeFirst()
                        if (level >= MAX_TRAVERSAL_DEPTH) {
                            logger.error("Descendant traversal of $id exceeded maximum depth $MAX_TRAVERSAL_DEPTH")
                            return@suspendTransaction Result.Error(
                                RepositoryError.DatabaseError(
                                    "Failed to find descendants: traversal exceeded the maximum " +
                                        "depth of $MAX_TRAVERSAL_DEPTH levels (item hierarchy may be cyclic)",
                                ),
                            )
                        }
                        val children =
                            WorkItemsTable
                                .selectAll()
                                .where { WorkItemsTable.parentId eq current }
                                .mapNotNull { toWorkItemOrNull(it) }
                        for (child in children) {
                            if (!visited.add(child.id)) {
                                logger.error("Cycle detected in item hierarchy under $id at ${child.id}")
                                return@suspendTransaction Result.Error(
                                    RepositoryError.DatabaseError(
                                        "Failed to find descendants: cycle detected in the item " +
                                            "hierarchy under $id at ${child.id} (traversal bounded at " +
                                            "$MAX_TRAVERSAL_DEPTH levels)",
                                    ),
                                )
                            }
                            results.add(child)
                            queue.add(child.id to level + 1)
                        }
                    }
                    Result.Success(results)
                } else {
                    // Single-query recursive CTE — the production SQLite path.
                    //
                    // The root item itself is excluded (the CTE seeds with children of :id).
                    // UUIDs are stored as BLOBs in SQLite. We use connection.prepareStatement()
                    // + executeQuery() on the Exposed ExposedConnection instead of exec(sql, args, transform)
                    // because Exposed's exec() routes through executeUpdate() on the xerial/sqlite-jdbc
                    // JDBC driver, which rejects SELECT-returning CTEs with "Query returns results".
                    val uuidType = UUIDColumnType()

                    // Collect matching IDs via recursive CTE, then load full rows via Exposed
                    // so that WorkItem mapping stays in one place (toWorkItemOrNull).
                    //
                    // Cycle guard: the `lvl` column bounds the recursive member. Without it a single
                    // cyclic parent_id edge makes the UNION ALL produce rows forever, hanging the
                    // connection while it holds a SERIALIZABLE transaction.
                    //
                    // The boundary matches the H2 branch above exactly: direct children are lvl 1
                    // (H2 level 1), the recursive arm stops expanding at lvl MAX_TRAVERSAL_DEPTH,
                    // and reaching that level is an ERROR rather than a truncated Success — the same
                    // outcome H2 produces when it dequeues a node at level >= MAX_TRAVERSAL_DEPTH.
                    // So root + (MAX-1) descendants succeeds, root + MAX descendants fails, in both
                    // dialects. Truncating silently here is not an option: this traversal feeds
                    // cascade deletes and subtree restamps.
                    val descendantIds = mutableListOf<UUID>()
                    var boundExceeded = false
                    val sql =
                        """
                        WITH RECURSIVE descendants(id, lvl) AS (
                            SELECT id, 1 FROM work_items WHERE parent_id = ?
                            UNION ALL
                            SELECT wi.id, d.lvl + 1 FROM work_items wi
                            JOIN descendants d ON wi.parent_id = d.id
                            WHERE d.lvl < $MAX_TRAVERSAL_DEPTH
                        )
                        SELECT id, lvl FROM descendants
                        """.trimIndent()

                    // Use ExposedConnection.prepareStatement() to get a JdbcPreparedStatementApi,
                    // then call executeQuery() on it — bypassing Exposed's exec() routing issue.
                    val ps = this.connection.prepareStatement(sql, false)
                    try {
                        // fillParameters binds the UUID arg using UUIDColumnType (→ ByteArray for SQLite).
                        ps.fillParameters(listOf(uuidType to id))
                        val rs = ps.executeQuery()
                        while (rs.next()) {
                            val rawId = rs.getObject("id")

                            @Suppress("UNCHECKED_CAST")
                            val uuid = (uuidType.valueFromDB(rawId!!)) as UUID
                            descendantIds.add(uuid)
                            if ((rs.getObject("lvl") as Number).toInt() >= MAX_TRAVERSAL_DEPTH) boundExceeded = true
                        }
                    } finally {
                        ps.closeIfPossible()
                    }

                    if (boundExceeded) {
                        logger.error("Descendant traversal of $id exceeded maximum depth $MAX_TRAVERSAL_DEPTH")
                        return@suspendTransaction Result.Error(
                            RepositoryError.DatabaseError(
                                "Failed to find descendants: traversal exceeded the maximum depth of " +
                                    "$MAX_TRAVERSAL_DEPTH levels (item hierarchy may be cyclic)",
                            ),
                        )
                    }

                    if (descendantIds.isEmpty()) {
                        return@suspendTransaction Result.Success(emptyList())
                    }

                    val entityIds = descendantIds.map { EntityID(it, WorkItemsTable) }
                    Result.Success(
                        WorkItemsTable
                            .selectAll()
                            .where { WorkItemsTable.id inList entityIds }
                            .mapNotNull { toWorkItemOrNull(it) },
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to find descendants of $id: ${e.message}", e)
            Result.Error(RepositoryError.DatabaseError("Failed to find descendants: ${e.message}", e))
        }

    // -----------------------------------------------------------------------
    // FTS5 full-text search
    // -----------------------------------------------------------------------

    /**
     * Full-text search on work items using the V7 FTS5 virtual tables.
     *
     * **H2 (test environment):** FTS5 is SQLite-only. When the current dialect is H2,
     * this method returns an empty [SearchResult] immediately — unit tests that exercise
     * the search path must use a real SQLite DB (see `Fts5MigrationTest`).
     *
     * **Pagination:** see [SearchResult] for the contract. A fixed [FTS_CANDIDATE_ROWS] rows are
     * fetched per FTS table regardless of [offset], fused into a total order (score descending,
     * ties broken ascending by work-item id), capped at [MAX_FTS_RESULTS], and only then sliced
     * by [offset]/[limit] — so every page is a slice of the same ordered list.
     *
     * @param sanitizedFtsQuery FTS5 query string. Callers (T4 — QueryItemsTool / FtsQuerySanitizer)
     *   are responsible for sanitizing user input before calling this method. Passing raw user input
     *   may cause FTS5 syntax errors.
     * @param matchMode Which FTS table(s) to query.
     * @param scope     Optional structural scope filters (subtree, tags, role).
     * @param limit     Maximum hits to return (enforced at [MAX_FTS_RESULTS]; default 20).
     * @param offset    Zero-based page offset, applied after fusion and capping.
     */
    override suspend fun ftsSearch(
        sanitizedFtsQuery: String,
        matchMode: SearchMatchMode,
        scope: SearchScope?,
        limit: Int,
        offset: Int,
    ): SearchResult {
        val effectiveLimit = limit.coerceIn(1, MAX_FTS_RESULTS)

        return try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                // currentDialect is only accessible within an active transaction.
                // FTS5 is SQLite-only — return empty for H2 (test environment).
                if (currentDialect is H2Dialect) {
                    return@suspendTransaction SearchResult(hits = emptyList(), totalHits = 0, nextOffset = null)
                }
                val uuidType = UUIDColumnType()

                // RRF scoring delegated to RrfFusion utility (application.service.search.RrfFusion).
                // k=60 is the standard Reciprocal Rank Fusion constant.

                // Build optional subtree CTE clause.
                // Singular path (scope.ancestorId): unchanged — single-root recursive CTE.
                // Plural path (scope.ancestorIds, non-null, non-empty): multi-root CTE with one
                //   seed row per root (OR semantics). An empty ancestorIds set → no-match stub.
                // Precedence: singular ancestorId wins if both are set (backward compat).
                val subtreeCteClause =
                    when {
                        scope?.ancestorId != null -> {
                            // SINGULAR path — behavior-identical to original; MCP query_items depends on this.
                            // The `lvl` column bounds the recursive member so cyclic parent_id data
                            // cannot spin the CTE forever. Search scope is bound-and-continue: missing
                            // nodes past the bound degrade the result, they do not fail the search.
                            """
                            WITH RECURSIVE subtree(id, lvl) AS (
                                SELECT id, 1 FROM work_items WHERE id = ?
                                UNION ALL
                                SELECT wi.id, s.lvl + 1 FROM work_items wi JOIN subtree s ON wi.parent_id = s.id
                                WHERE s.lvl < $MAX_TRAVERSAL_DEPTH
                            )
                            """.trimIndent()
                        }
                        scope?.ancestorIds != null && scope.ancestorIds.isNotEmpty() -> {
                            // PLURAL path — seed CTE with one ? per root, then walk descendants.
                            val placeholders = scope.ancestorIds.joinToString(", ") { "?" }
                            """
                            WITH RECURSIVE subtree(id, lvl) AS (
                                SELECT id, 1 FROM work_items WHERE id IN ($placeholders)
                                UNION ALL
                                SELECT wi.id, s.lvl + 1 FROM work_items wi JOIN subtree s ON wi.parent_id = s.id
                                WHERE s.lvl < $MAX_TRAVERSAL_DEPTH
                            )
                            """.trimIndent()
                        }
                        else -> ""
                    }

                // Build the WHERE clause additions for work_items column filters.
                // These are appended as literal fragments (values bound via positional params).
                val extraWhereParts = mutableListOf<String>()
                if (scope?.itemId != null) extraWhereParts.add("wi.id = ?")
                when {
                    scope?.ancestorId != null -> extraWhereParts.add("wi.id IN (SELECT id FROM subtree)")
                    scope?.ancestorIds != null && scope.ancestorIds.isNotEmpty() -> extraWhereParts.add("wi.id IN (SELECT id FROM subtree)")
                    scope?.ancestorIds != null && scope.ancestorIds.isEmpty() -> extraWhereParts.add("1 = 0") // empty scope → no hits
                }
                if (scope?.role != null) extraWhereParts.add("wi.role = ?")
                if (!scope?.tags.isNullOrEmpty()) {
                    // Tags are stored as a comma-separated string; OR-match each tag.
                    val tagConditions =
                        scope!!.tags!!.map { t ->
                            val escaped = t.trim().lowercase()
                            // SQLite LIKE on a TEXT column — safe because value comes from validated input, not FTS query.
                            "(LOWER(wi.tags) = ? OR LOWER(wi.tags) LIKE ? OR LOWER(wi.tags) LIKE ? OR LOWER(wi.tags) LIKE ?)"
                        }
                    extraWhereParts.add("(${tagConditions.joinToString(" OR ")})")
                }
                val extraWhere = if (extraWhereParts.isEmpty()) "" else " AND " + extraWhereParts.joinToString(" AND ")

                // Accumulate positional args shared across trigram / text queries.
                // Order: subtree anchors (if any), then fts query, then scope filters.
                fun buildArgs(): List<Pair<org.jetbrains.exposed.v1.core.ColumnType<*>, Any?>> {
                    val args = mutableListOf<Pair<org.jetbrains.exposed.v1.core.ColumnType<*>, Any?>>()
                    val varcharType = VarCharColumnType(4000)
                    when {
                        scope?.ancestorId != null -> args.add(uuidType to scope.ancestorId) // SINGULAR — unchanged
                        scope?.ancestorIds != null -> scope.ancestorIds.forEach { args.add(uuidType to it) } // PLURAL — one per root
                    }
                    args.add(varcharType to sanitizedFtsQuery) // FTS MATCH param
                    if (scope?.itemId != null) args.add(uuidType to scope.itemId)
                    if (scope?.role != null) args.add(varcharType to scope.role.name.lowercase())
                    if (!scope?.tags.isNullOrEmpty()) {
                        for (tag in scope!!.tags!!) {
                            val t = tag.trim().lowercase()
                            args.add(varcharType to t)
                            args.add(varcharType to "$t,%")
                            args.add(varcharType to "%,$t,%")
                            args.add(varcharType to "%,$t")
                        }
                    }
                    return args
                }

                // Collect trigram hits: { rowid, title, summary, rank, snippet_title, snippet_summary }
                data class FtsHit(
                    val rowid: Long,
                    val rank: Double,
                    val snippetTitle: String,
                    val snippetSummary: String,
                    val matchedTable: String,
                )

                val trigramHits = mutableMapOf<Long, FtsHit>()
                val textHits = mutableMapOf<Long, FtsHit>()

                // Helper to run one FTS query and populate a hit map.
                fun runFtsQuery(
                    ftsTable: String,
                    hitMap: MutableMap<Long, FtsHit>,
                    tableName: String,
                ) {
                    val sql =
                        """
                        ${subtreeCteClause.ifEmpty { "" }}
                        SELECT
                            ft.rowid,
                            ft.rank,
                            snippet($ftsTable, 0, '<mark>', '</mark>', '…', 32) AS snip_title,
                            snippet($ftsTable, 1, '<mark>', '</mark>', '…', 32) AS snip_summary,
                            wi.id AS wi_id
                        FROM $ftsTable ft
                        JOIN work_items wi ON wi.rowid = ft.rowid
                        WHERE $ftsTable MATCH ?$extraWhere
                        ORDER BY ft.rank
                        LIMIT $FTS_CANDIDATE_ROWS
                        """.trimIndent()

                    // Use ExposedConnection.prepareStatement() + executeQuery() rather than
                    // Exposed's exec() — when the SQL starts with `WITH RECURSIVE` (the
                    // subtree CTE for scope.ancestorId), exec() routes through executeUpdate()
                    // on xerial sqlite-jdbc and rejects SELECT-returning queries with
                    // "Query returns results". Same fix template used in findDescendants().
                    val ps = this.connection.prepareStatement(sql, false)
                    try {
                        ps.fillParameters(buildArgs())
                        val rs = ps.executeQuery()
                        while (rs.next()) {
                            // Exposed's JdbcResultSetApi exposes getObject(Int|String) and
                            // getString(Int), but NOT getLong/getDouble. Use getObject for
                            // numerics and cast to Number. Select order:
                            // 1=ft.rowid, 2=ft.rank, 3=snip_title, 4=snip_summary, 5=wi_id.
                            val rowid = (rs.getObject(1) as Number).toLong()
                            val rank = (rs.getObject(2) as Number).toDouble()
                            val snippetTitle = rs.getString(3) ?: ""
                            val snippetSummary = rs.getString(4) ?: ""
                            hitMap[rowid] = FtsHit(rowid, rank, snippetTitle, snippetSummary, tableName)
                        }
                    } finally {
                        ps.closeIfPossible()
                    }
                }

                when (matchMode) {
                    SearchMatchMode.SUBSTRING -> runFtsQuery("work_items_fts_trigram", trigramHits, "trigram")
                    SearchMatchMode.TEXT -> runFtsQuery("work_items_fts_text", textHits, "text")
                    SearchMatchMode.AUTO -> {
                        runFtsQuery("work_items_fts_trigram", trigramHits, "trigram")
                        runFtsQuery("work_items_fts_text", textHits, "text")
                    }
                }

                // Collect all rowids (union of both maps).
                val allRowIds = (trigramHits.keys + textHits.keys).toSet()
                if (allRowIds.isEmpty()) {
                    return@suspendTransaction SearchResult(
                        hits = emptyList(),
                        totalHits = 0,
                        nextOffset = null,
                    )
                }

                // Fetch work item UUIDs + metadata for matching rowids.
                val rowidToItem = mutableMapOf<Long, Pair<UUID, String>>() // rowid -> (uuid, tags)
                val rowidInClause = allRowIds.joinToString(",") { "?" }
                val rowidArgs =
                    allRowIds.map {
                        @Suppress("UNCHECKED_CAST")
                        (
                            org.jetbrains.exposed.v1.core
                                .LongColumnType() as org.jetbrains.exposed.v1.core.ColumnType<Any?>
                        ) to (it as Any?)
                    }
                exec("SELECT rowid, id FROM work_items WHERE rowid IN ($rowidInClause)", args = rowidArgs) { rs ->
                    while (rs.next()) {
                        val rowid = rs.getLong("rowid")
                        val rawId = rs.getObject("id")

                        @Suppress("UNCHECKED_CAST")
                        val uuid = uuidType.valueFromDB(rawId!!) as UUID
                        rowidToItem[rowid] = Pair(uuid, "")
                    }
                }

                // RRF fusion: assign row_number rank (1-based, ascending by FTS rank = most relevant first).
                data class FusedDoc(
                    val rowid: Long,
                    val itemId: UUID,
                    val trigramRank: Double?,
                    val textRank: Double?,
                    var trigramRowNum: Int = Int.MAX_VALUE,
                    var textRowNum: Int = Int.MAX_VALUE,
                )

                val docs = mutableMapOf<Long, FusedDoc>()
                for (rowid in allRowIds) {
                    val itemId = rowidToItem[rowid]?.first ?: continue
                    docs[rowid] =
                        FusedDoc(
                            rowid = rowid,
                            itemId = itemId,
                            trigramRank = trigramHits[rowid]?.rank,
                            textRank = textHits[rowid]?.rank,
                        )
                }

                // Assign row numbers based on rank (lower rank = more relevant = lower row number).
                trigramHits.entries
                    .sortedBy { it.value.rank }
                    .forEachIndexed { idx, (rowid, _) -> docs[rowid]?.trigramRowNum = idx + 1 }
                textHits.entries
                    .sortedBy { it.value.rank }
                    .forEachIndexed { idx, (rowid, _) -> docs[rowid]?.textRowNum = idx + 1 }

                // Compute the fused RRF score and impose a TOTAL order: score descending, then
                // ascending work-item id. Score alone is a partial order — RRF ties are common
                // (two docs matched by one table at the same row number score identically), and a
                // stable sort would fall back to map insertion order, which is the unspecified
                // order SQLite returned equally-ranked FTS rows in. A page boundary landing inside
                // such a tie group then duplicates one hit and skips another.
                val fused =
                    docs.values
                        .map { doc ->
                            val score =
                                (if (doc.trigramRowNum < Int.MAX_VALUE) RrfFusion.score(doc.trigramRowNum) else 0.0) +
                                    (if (doc.textRowNum < Int.MAX_VALUE) RrfFusion.score(doc.textRowNum) else 0.0)
                            doc to score
                        }.sortedWith(
                            compareByDescending<Pair<FusedDoc, Double>> { it.second }
                                .thenBy { it.first.itemId },
                        )

                // Cap BEFORE slicing so totalHits (and the nextOffset derived from it) describe the
                // whole query, not the requested page — identical at every offset.
                val capExceeded = fused.size > MAX_FTS_RESULTS
                val ranked = if (capExceeded) fused.take(MAX_FTS_RESULTS) else fused

                val totalHits = ranked.size
                val pageSlice = ranked.drop(offset).take(effectiveLimit)

                val hits =
                    pageSlice.map { (doc, score) ->
                        // Pick the best snippet: prefer the table with better rank (lower absolute value).
                        val trigramHit = trigramHits[doc.rowid]
                        val textHit = textHits[doc.rowid]
                        val primaryHit =
                            when {
                                trigramHit != null && textHit != null ->
                                    if ((trigramHit.rank) <= (textHit.rank)) trigramHit else textHit
                                trigramHit != null -> trigramHit
                                else -> textHit!!
                            }

                        // Determine which field the best snippet comes from.
                        val (field, snippet) =
                            if (primaryHit.snippetTitle.isNotEmpty()) {
                                "title" to primaryHit.snippetTitle
                            } else {
                                "summary" to primaryHit.snippetSummary
                            }

                        val matchedIn = mutableListOf<String>()
                        if (trigramHit != null) matchedIn.add("trigram")
                        if (textHit != null) matchedIn.add("text")

                        SearchHit(
                            kind = "item",
                            itemId = doc.itemId,
                            noteKey = null,
                            field = field,
                            snippet = snippet.ifEmpty { primaryHit.snippetSummary },
                            score = score,
                            matchedIn = matchedIn,
                            trigramRank = doc.trigramRank,
                            textRank = doc.textRank,
                        )
                    }

                val nextOffset =
                    if (offset + effectiveLimit < totalHits) offset + effectiveLimit else null

                SearchResult(
                    hits = hits,
                    totalHits = totalHits,
                    nextOffset = nextOffset,
                    truncated = capExceeded,
                )
            }
        } catch (e: Exception) {
            logger.error("FTS5 search failed: ${e.message}", e)
            throw e
        }
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

    override suspend fun claim(
        itemId: UUID,
        agentId: String,
        ttlSeconds: Int
    ): ClaimResult =
        try {
            // Read the DB-side clock ONCE before opening the transaction to avoid a nested
            // transaction/savepoint when dbNow() opens its own suspendTransaction internally.
            // This instant is used only for computing retryAfterMs on the AlreadyClaimed path —
            // the claim SQL itself uses datetime('now') exclusively for DB-side timestamps.
            val dbNowInstant = dbNow()

            suspendTransaction(db = databaseManager.getDatabase()) {
                // All parameters are bound as typed JDBC parameters — no string interpolation.
                // itemId binds via UUIDColumnType so the WHERE id = ? predicate uses the primary-key
                // index directly (the prior HEX(id) = ? form wrapped the column in a function call,
                // which the SQLite planner cannot push through the PK index, forcing a full scan).
                // ttlOffset binds as a VarChar parameter (+N) passed to datetime('now', ? || ' seconds')
                // so the TTL value is never interpolated into raw SQL text.
                val agentIdType = VarCharColumnType(500)
                val uuidType = UUIDColumnType()
                val ttlOffsetType = VarCharColumnType(50)
                val ttlOffset = "+$ttlSeconds"

                // Step 1 (acquire target): Claim or refresh the target item using only DB-side timestamps.
                // Matches rows that are: (a) unclaimed, (b) expired, or (c) already held by this agent.
                // TERMINAL items are always excluded.
                //
                // NOTE: Acquisition runs FIRST so that Step 2 (auto-release of prior claims) only fires
                // when acquisition succeeds. If Step 2 ran first and Step 1 then matched zero rows (e.g.
                // target held by another agent, or target is TERMINAL), the agent would lose its prior
                // claim without gaining the new one — a net loss of both claims.
                exec(
                    """
                    UPDATE work_items
                      SET claimed_by = ?,
                          claimed_at = datetime('now'),
                          claim_expires_at = datetime('now', ? || ' seconds'),
                          original_claimed_at = COALESCE(
                            CASE WHEN claimed_by = ? THEN original_claimed_at ELSE NULL END,
                            datetime('now')
                          ),
                          version = version + 1
                      WHERE id = ?
                        AND role != 'terminal'
                        AND (claimed_by IS NULL
                             OR claim_expires_at < datetime('now')
                             OR claimed_by = ?)
                    """.trimIndent(),
                    args =
                        listOf(
                            agentIdType to agentId,
                            ttlOffsetType to ttlOffset,
                            agentIdType to agentId,
                            uuidType to itemId,
                            agentIdType to agentId,
                        )
                )

                // Read back the current state: if claimedBy == agentId, acquisition succeeded.
                val row = WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()

                val result =
                    when {
                        row == null -> ClaimResult.NotFound(itemId)
                        else -> {
                            val item = toWorkItem(row)
                            when {
                                item.role == Role.TERMINAL -> ClaimResult.TerminalItem(itemId)
                                item.claimedBy == agentId -> ClaimResult.Success(item)
                                else -> {
                                    // Claimed by someone else — compute retryAfterMs using the DB clock
                                    // (read before the transaction to avoid a nested transaction).
                                    val retryAfterMs =
                                        if (item.claimExpiresAt != null) {
                                            val remaining = item.claimExpiresAt.toEpochMilli() - dbNowInstant.toEpochMilli()
                                            if (remaining > 0) remaining else null
                                        } else {
                                            null
                                        }
                                    ClaimResult.AlreadyClaimed(itemId, retryAfterMs)
                                }
                            }
                        }
                    }

                // Step 2 (auto-release prior claims): Only runs when acquisition SUCCEEDED.
                // Releases all other items held by this agent EXCEPT the newly-acquired target.
                // Skipped on any failure result to preserve the agent's existing claim.
                if (result is ClaimResult.Success) {
                    // Read the about-to-be-evicted ids BEFORE the release UPDATE, inside the same
                    // transaction/step, so the SELECT sees exactly the rows the UPDATE below will
                    // touch — the event-publishing decorator needs these ids to emit item.updated
                    // for each auto-released item (see ClaimResult.Success.releasedItemIds).
                    val releasedIds =
                        WorkItemsTable
                            .selectAll()
                            .where { (WorkItemsTable.claimedBy eq agentId) and (WorkItemsTable.id neq itemId) }
                            .map { it[WorkItemsTable.id].value }

                    exec(
                        """
                        UPDATE work_items
                          SET claimed_by = NULL,
                              claimed_at = NULL,
                              claim_expires_at = NULL,
                              original_claimed_at = NULL,
                              version = version + 1
                          WHERE claimed_by = ?
                            AND id != ?
                        """.trimIndent(),
                        args =
                            listOf(
                                agentIdType to agentId,
                                uuidType to itemId,
                            )
                    )

                    result.copy(releasedItemIds = releasedIds)
                } else {
                    result
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to claim WorkItem $itemId for agent $agentId: ${e.message}", e)
            ClaimResult.DBError(itemId, e)
        }

    override suspend fun release(
        itemId: UUID,
        agentId: String
    ): ReleaseResult =
        try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                // Both agentId and itemId are bound as typed JDBC parameters — no string interpolation.
                // itemId binds via UUIDColumnType so the WHERE id = ? predicate uses the primary-key
                // index directly.
                val agentIdType = VarCharColumnType(500)
                val uuidType = UUIDColumnType()

                // Single-statement release: the WHERE clause atomically asserts both item identity
                // and claimant ownership.  We capture the affected-row count to determine the
                // outcome — eliminating the prior SELECT-then-UPDATE TOCTOU window where a claim
                // stolen between the pre-read and the UPDATE could produce a false Success result
                // (the UPDATE would zero-match but the code would still return ReleaseResult.Success
                // with stale data because the row count was never checked).
                val sql =
                    """
                    UPDATE work_items
                      SET claimed_by = NULL,
                          claimed_at = NULL,
                          claim_expires_at = NULL,
                          original_claimed_at = NULL,
                          version = version + 1
                      WHERE id = ?
                        AND claimed_by = ?
                    """.trimIndent()
                val ps = this.connection.prepareStatement(sql, false)
                val rowsUpdated =
                    try {
                        ps.fillParameters(listOf(uuidType to itemId, agentIdType to agentId))
                        ps.executeUpdate()
                    } finally {
                        ps.closeIfPossible()
                    }

                when {
                    rowsUpdated > 0 -> {
                        // Release succeeded — read back the updated state.
                        val updatedRow =
                            WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
                                ?: return@suspendTransaction ReleaseResult.NotFound(itemId)
                        ReleaseResult.Success(toWorkItem(updatedRow))
                    }
                    else -> {
                        // Zero rows updated — item either doesn't exist or is not claimed by this agent.
                        val exists =
                            WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
                        if (exists == null) ReleaseResult.NotFound(itemId) else ReleaseResult.NotClaimedByYou(itemId)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to release WorkItem $itemId for agent $agentId: ${e.message}", e)
            ReleaseResult.DBError(itemId, e)
        }

    override suspend fun findForNextItem(
        role: Role,
        parentId: UUID?,
        excludeActiveClaims: Boolean,
        limit: Int,
        rootIds: Set<UUID>?
    ): Result<List<WorkItem>> {
        // Read DB-side clock ONCE before opening the transaction to avoid a nested
        // transaction/savepoint (dbNow() opens its own suspendTransaction internally).
        val dbNowInstant = if (excludeActiveClaims) dbNow() else null

        return databaseManager.suspendedTransaction("Failed to find next items by role") {
            val conditions = mutableListOf<Op<Boolean>>()

            // Role filter
            conditions.add(WorkItemsTable.role eq role.name.lowercase())

            // Optional parent scope
            parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }

            // Optional subtree scope — expand rootIds to the full descendant set (roots included).
            if (rootIds != null) {
                val scope = resolveScope(rootIds)
                if (scope == ResolvedScope.Empty) return@suspendedTransaction Result.Success(emptyList())
                conditions.add(scope.toCondition())
            }

            // Claim filter: exclude items with an active (non-expired) claim.
            // An item is "actively claimed" when claimed_by IS NOT NULL AND claim_expires_at > now.
            // The complement (items to include) is: claimed_by IS NULL OR claim_expires_at <= now.
            // Use DB-side now so the freshness decision is made on the DB clock, not the JVM clock.
            if (excludeActiveClaims && dbNowInstant != null) {
                val notClaimed = WorkItemsTable.claimedBy.isNull()
                val claimExpired = WorkItemsTable.claimExpiresAt lessEq dbNowInstant
                // Re-express as: NOT (claimedBy IS NOT NULL AND claimExpiresAt > now)
                //               = claimedBy IS NULL  OR  claimExpiresAt <= now
                conditions.add(notClaimed or claimExpired)
            }

            val combined = conditions.reduce { acc, op -> acc and op }
            val items =
                WorkItemsTable
                    .selectAll()
                    .where { combined }
                    .limit(limit)
                    .mapNotNull { toWorkItemOrNull(it) }

            Result.Success(items)
        }
    }

    override suspend fun findClaimable(
        role: Role,
        parentId: UUID?,
        tags: List<String>?,
        priority: Priority?,
        type: String?,
        complexityMax: Int?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        modifiedAfter: Instant?,
        modifiedBefore: Instant?,
        roleChangedAfter: Instant?,
        roleChangedBefore: Instant?,
        orderBy: NextItemOrder,
        limit: Int,
        requestingAgentId: String?,
        rootIds: Set<UUID>?,
    ): Result<List<WorkItem>> {
        // Read DB-side clock ONCE before opening the transaction to avoid a nested
        // transaction/savepoint (dbNow() opens its own suspendTransaction internally).
        val dbNowInstant = dbNow()

        return databaseManager.suspendedTransaction("Failed to find claimable items") {
            val conditions = mutableListOf<Op<Boolean>>()

            // Role filter — always required
            conditions.add(WorkItemsTable.role eq role.name.lowercase())

            // Optional parent scope
            parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }

            // Optional subtree scope — expand rootIds to the full descendant set (roots included).
            if (rootIds != null) {
                val scope = resolveScope(rootIds)
                if (scope == ResolvedScope.Empty) return@suspendedTransaction Result.Success(emptyList())
                conditions.add(scope.toCondition())
            }

            // Tag any-match — reuse buildTagFilter (OR logic within list)
            tags?.takeIf { it.isNotEmpty() }?.let { conditions.add(buildTagFilter(it)) }

            // Priority exact match
            priority?.let { conditions.add(WorkItemsTable.priority eq it.name.lowercase()) }

            // Type exact match
            type?.let { conditions.add(WorkItemsTable.type eq it) }

            // Complexity upper bound (inclusive)
            complexityMax?.let { conditions.add(WorkItemsTable.complexity lessEq it) }

            // Created-at time window
            createdAfter?.let { conditions.add(WorkItemsTable.createdAt greaterEq it) }
            createdBefore?.let { conditions.add(WorkItemsTable.createdAt lessEq it) }

            // Modified-at time window
            modifiedAfter?.let { conditions.add(WorkItemsTable.modifiedAt greaterEq it) }
            modifiedBefore?.let { conditions.add(WorkItemsTable.modifiedAt lessEq it) }

            // Role-changed-at time window
            roleChangedAfter?.let { conditions.add(WorkItemsTable.roleChangedAt greaterEq it) }
            roleChangedBefore?.let { conditions.add(WorkItemsTable.roleChangedAt lessEq it) }

            // Active-claim exclusion — always applied; claim-eligibility by definition requires this.
            // Include items where: claimed_by IS NULL  OR  claim_expires_at <= now.
            // dbNowInstant was obtained before the transaction to avoid a nested transaction.
            conditions.add(WorkItemsTable.claimedBy.isNull() or (WorkItemsTable.claimExpiresAt lessEq dbNowInstant))

            val combined = conditions.reduce { acc, op -> acc and op }

            val baseQuery = WorkItemsTable.selectAll().where { combined }

            // Apply ordering strategy
            val orderedQuery =
                when (orderBy) {
                    NextItemOrder.PRIORITY_THEN_COMPLEXITY -> {
                        // Sort priority HIGH > MEDIUM > LOW via CASE expression, then complexity ASC.
                        // Items with null complexity sort last (NULL sorts last in ASC for SQLite/H2).
                        val priorityOrder =
                            Case()
                                .When(
                                    WorkItemsTable.priority eq Priority.HIGH.name.lowercase(),
                                    LiteralOp(IntegerColumnType(), 0),
                                ).When(
                                    WorkItemsTable.priority eq Priority.MEDIUM.name.lowercase(),
                                    LiteralOp(IntegerColumnType(), 1),
                                ).When(
                                    WorkItemsTable.priority eq Priority.LOW.name.lowercase(),
                                    LiteralOp(IntegerColumnType(), 2),
                                ).Else(LiteralOp(IntegerColumnType(), 99))
                        baseQuery
                            .orderBy(priorityOrder, SortOrder.ASC)
                            .orderBy(WorkItemsTable.complexity, SortOrder.ASC_NULLS_LAST)
                    }
                    NextItemOrder.OLDEST_FIRST ->
                        baseQuery.orderBy(WorkItemsTable.createdAt, SortOrder.ASC)
                    NextItemOrder.NEWEST_FIRST ->
                        baseQuery.orderBy(WorkItemsTable.createdAt, SortOrder.DESC)
                }

            val candidates = orderedQuery.limit(limit).mapNotNull { toWorkItemOrNull(it) }

            // -----------------------------------------------------------------------
            // Ancestor-claim filter (strict-by-default sub-tree isolation)
            //
            // For each candidate with a parentId, walk the ancestor chain and disqualify
            // any candidate whose ancestor has a live claim held by a disqualifying agent.
            //
            // Disqualifying ancestor claim condition:
            //   claimed_by IS NOT NULL
            //   AND claim_expires_at > dbNow()
            //   AND (requestingAgentId == null OR claimed_by != requestingAgentId)
            //
            // Batched BFS pattern modeled on findAncestorChains, inlined within the same
            // suspendedTransaction block to avoid an extra transaction open/close.
            // -----------------------------------------------------------------------
            val candidatesWithParents = candidates.filter { it.parentId != null }
            if (candidatesWithParents.isEmpty()) {
                // No candidates have ancestors — skip the BFS entirely.
                return@suspendedTransaction Result.Success(candidates)
            }

            // BFS: batch-fetch all ancestors for all candidates in minimal round-trips.
            // Seed the cache directly from the in-memory candidate rows — no need to re-fetch
            // them from the DB just to read their parentId fields.
            val ancestorCache = mutableMapOf<String, WorkItem>()
            candidatesWithParents.forEach { ancestorCache[it.id.toString()] = it }

            // candidatesWithParents was filtered to parentId != null above, so !! is safe.
            var toFetch =
                candidatesWithParents.map { it.parentId!!.toString() }.toSet() - ancestorCache.keys
            while (toFetch.isNotEmpty()) {
                val fetchEntityIds = toFetch.map { EntityID(UUID.fromString(it), WorkItemsTable) }
                val fetched =
                    WorkItemsTable
                        .selectAll()
                        .where { WorkItemsTable.id inList fetchEntityIds }
                        .mapNotNull { toWorkItemOrNull(it) }
                fetched.forEach { ancestorCache[it.id.toString()] = it }
                toFetch = fetched.mapNotNull { it.parentId }.map { it.toString() }.toSet() - ancestorCache.keys
            }

            // Filter candidates: exclude those with a disqualifying live ancestor claim.
            val filtered =
                candidates.filter { candidate ->
                    if (candidate.parentId == null) {
                        // Root item — no ancestors to check
                        true
                    } else {
                        // Walk the ancestor chain; disqualify on first violating ancestor.
                        var parentIdStr = candidate.parentId?.toString()
                        val visited = mutableSetOf<String>()
                        var disqualified = false
                        while (parentIdStr != null && !disqualified) {
                            if (parentIdStr in visited) break // Cycle guard
                            visited.add(parentIdStr)
                            val ancestor = ancestorCache[parentIdStr] ?: break
                            val ancestorClaimBy = ancestor.claimedBy
                            val ancestorClaimExpiry = ancestor.claimExpiresAt
                            if (ancestorClaimBy != null &&
                                ancestorClaimExpiry != null &&
                                ancestorClaimExpiry > dbNowInstant
                            ) {
                                // Ancestor has a live claim — check if it disqualifies this candidate.
                                if (requestingAgentId == null || ancestorClaimBy != requestingAgentId) {
                                    disqualified = true
                                }
                            }
                            parentIdStr = ancestor.parentId?.toString()
                        }
                        !disqualified
                    }
                }

            Result.Success(filtered)
        }
    }

    override suspend fun countByClaimStatus(
        parentId: UUID?,
        rootIds: Set<UUID>?
    ): Result<ClaimStatusCounts> {
        // Read DB-side clock ONCE before opening the transaction to avoid a nested
        // transaction/savepoint (dbNow() opens its own suspendTransaction internally).
        // Use DB-side clock so counts are consistent with the DB's view of claim freshness.
        val now = dbNow()

        return databaseManager.suspendedTransaction("Failed to count WorkItems by claim status") {
            // Optional subtree scope — expand rootIds to the full descendant set (roots included).
            // Resolved once and reused across all three claim-status conditions below.
            val scope = rootIds?.let { resolveScope(it) }
            if (scope == ResolvedScope.Empty) {
                return@suspendedTransaction Result.Success(ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0))
            }
            val scopeCondition = scope?.toCondition()

            // Helper to build a base condition list optionally scoped to a parent and/or subtree
            fun baseConditions(): MutableList<Op<Boolean>> {
                val conds = mutableListOf<Op<Boolean>>()
                parentId?.let { conds.add(WorkItemsTable.parentId eq it) }
                scopeCondition?.let { conds.add(it) }
                return conds
            }

            // Active: claimed_by IS NOT NULL AND claim_expires_at > now
            val activeConds =
                baseConditions().also {
                    it.add(WorkItemsTable.claimedBy.isNotNull())
                    it.add(WorkItemsTable.claimExpiresAt greater now)
                }
            val activeCount =
                WorkItemsTable
                    .selectAll()
                    .where { activeConds.reduce { acc, op -> acc and op } }
                    .count()
                    .toInt()

            // Expired: claimed_by IS NOT NULL AND claim_expires_at <= now
            val expiredConds =
                baseConditions().also {
                    it.add(WorkItemsTable.claimedBy.isNotNull())
                    it.add(WorkItemsTable.claimExpiresAt lessEq now)
                }
            val expiredCount =
                WorkItemsTable
                    .selectAll()
                    .where { expiredConds.reduce { acc, op -> acc and op } }
                    .count()
                    .toInt()

            // Unclaimed: claimed_by IS NULL
            val unclaimedConds =
                baseConditions().also {
                    it.add(WorkItemsTable.claimedBy.isNull())
                }
            val unclaimedCount =
                WorkItemsTable
                    .selectAll()
                    .where { unclaimedConds.reduce { acc, op -> acc and op } }
                    .count()
                    .toInt()

            Result.Success(ClaimStatusCounts(active = activeCount, expired = expiredCount, unclaimed = unclaimedCount))
        }
    }

    override suspend fun countSelectorMatches(
        role: Role,
        parentId: UUID?,
        tags: List<String>?,
        priority: Priority?,
        type: String?,
        complexityMax: Int?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        modifiedAfter: Instant?,
        modifiedBefore: Instant?,
        roleChangedAfter: Instant?,
        roleChangedBefore: Instant?,
        rootIds: Set<UUID>?,
    ): Result<SelectorMatchCounts> {
        // Read DB-side clock ONCE before opening the transaction to avoid a nested
        // transaction/savepoint (dbNow() opens its own suspendTransaction internally).
        val dbNowInstant = dbNow()

        return databaseManager.suspendedTransaction("Failed to count selector matches") {
            // Same condition builder as findClaimable, minus orderBy/limit/requestingAgentId and
            // minus the active-claim exclusion findClaimable always applies — this method counts
            // matches WITH claim status broken out, not eligible-to-claim rows.
            val conditions = mutableListOf<Op<Boolean>>()
            conditions.add(WorkItemsTable.role eq role.name.lowercase())
            parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }

            if (rootIds != null) {
                val scope = resolveScope(rootIds)
                if (scope == ResolvedScope.Empty) {
                    return@suspendedTransaction Result.Success(SelectorMatchCounts(matched = 0, activelyClaimed = 0))
                }
                conditions.add(scope.toCondition())
            }

            tags?.takeIf { it.isNotEmpty() }?.let { conditions.add(buildTagFilter(it)) }
            priority?.let { conditions.add(WorkItemsTable.priority eq it.name.lowercase()) }
            type?.let { conditions.add(WorkItemsTable.type eq it) }
            complexityMax?.let { conditions.add(WorkItemsTable.complexity lessEq it) }
            createdAfter?.let { conditions.add(WorkItemsTable.createdAt greaterEq it) }
            createdBefore?.let { conditions.add(WorkItemsTable.createdAt lessEq it) }
            modifiedAfter?.let { conditions.add(WorkItemsTable.modifiedAt greaterEq it) }
            modifiedBefore?.let { conditions.add(WorkItemsTable.modifiedAt lessEq it) }
            roleChangedAfter?.let { conditions.add(WorkItemsTable.roleChangedAt greaterEq it) }
            roleChangedBefore?.let { conditions.add(WorkItemsTable.roleChangedAt lessEq it) }

            val combined = conditions.reduce { acc, op -> acc and op }

            val matched =
                WorkItemsTable
                    .selectAll()
                    .where { combined }
                    .count()
                    .toInt()

            val activelyClaimedCondition =
                combined and WorkItemsTable.claimedBy.isNotNull() and (WorkItemsTable.claimExpiresAt greater dbNowInstant)
            val activelyClaimed =
                WorkItemsTable
                    .selectAll()
                    .where { activelyClaimedCondition }
                    .count()
                    .toInt()

            Result.Success(SelectorMatchCounts(matched = matched, activelyClaimed = activelyClaimed))
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
        claimStatus: String?,
    ): Result<List<WorkItem>> {
        if (rootIds.isEmpty()) return Result.Success(emptyList())

        // Fetch DB-side now ONCE before opening the transaction to avoid a nested
        // transaction/savepoint (dbNow() opens its own suspendTransaction internally).
        val nowFromDb = if (claimStatus != null) dbNow() else Instant.now()

        return try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                val scope = resolveScope(rootIds)
                if (scope == ResolvedScope.Empty) return@suspendTransaction Result.Success(emptyList())

                val base =
                    buildScopedQuery(
                        scope,
                        parentId,
                        depth,
                        role,
                        priority,
                        tags,
                        createdAfter,
                        createdBefore,
                        modifiedAfter,
                        modifiedBefore,
                        roleChangedAfter,
                        roleChangedBefore,
                        type,
                        claimStatus,
                        nowFromDb,
                    )

                val items =
                    applySort(base, sortBy, sortOrder)
                        .limit(limit)
                        .offset(offset.coerceAtLeast(0).toLong())
                        .mapNotNull { toWorkItemOrNull(it) }

                Result.Success(items)
            }
        } catch (e: Exception) {
            logger.error("Failed to findInScope for roots ${rootIds.size}: ${e.message}", e)
            Result.Error(RepositoryError.DatabaseError("Failed to findInScope: ${e.message}", e))
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
        claimStatus: String?,
    ): Result<Int> {
        if (rootIds.isEmpty()) return Result.Success(0)

        // Fetch DB-side now ONCE before opening the transaction to avoid a nested
        // transaction/savepoint (dbNow() opens its own suspendTransaction internally).
        val nowFromDb = if (claimStatus != null) dbNow() else Instant.now()

        return try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                val scope = resolveScope(rootIds)
                if (scope == ResolvedScope.Empty) return@suspendTransaction Result.Success(0)

                val count =
                    buildScopedQuery(
                        scope,
                        parentId,
                        depth,
                        role,
                        priority,
                        tags,
                        createdAfter,
                        createdBefore,
                        modifiedAfter,
                        modifiedBefore,
                        roleChangedAfter,
                        roleChangedBefore,
                        type,
                        claimStatus,
                        nowFromDb,
                    ).count()

                Result.Success(count.toInt())
            }
        } catch (e: Exception) {
            logger.error("Failed to countInScope for roots ${rootIds.size}: ${e.message}", e)
            Result.Error(RepositoryError.DatabaseError("Failed to countInScope: ${e.message}", e))
        }
    }

    override suspend fun countInScopeByRole(rootIds: Set<UUID>): Result<Map<Role, Int>> {
        if (rootIds.isEmpty()) return Result.Success(emptyMap())

        return try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                val scope = resolveScope(rootIds)
                if (scope == ResolvedScope.Empty) return@suspendTransaction Result.Success(emptyMap())

                val scopeCondition = scope.toCondition()
                val counts =
                    WorkItemsTable
                        .selectAll()
                        .where { scopeCondition }
                        .mapNotNull { toWorkItemOrNull(it) }
                        .groupBy { it.role }
                        .mapValues { (_, items) -> items.size }
                Result.Success(counts)
            }
        } catch (e: Exception) {
            logger.error("Failed to countInScopeByRole for roots ${rootIds.size}: ${e.message}", e)
            Result.Error(RepositoryError.DatabaseError("Failed to countInScopeByRole: ${e.message}", e))
        }
    }

    /**
     * The resolved WHERE-clause form of a caller-supplied root set (see [resolveScope]).
     *
     * [ByRoot] is the fast path: it binds ONE parameter per requested root and lets SQLite use
     * `idx_work_items_root_id`. [ByIds] is the historical path: the recursive CTE (or the H2 BFS)
     * expands the scope to every descendant id and binds them all, which is O(subtree) bound
     * variables and fails outright above SQLite's SQLITE_MAX_VARIABLE_NUMBER (~32,766).
     */
    private sealed interface ResolvedScope {
        /** The condition this scope contributes to a WHERE clause. */
        fun toCondition(): Op<Boolean>

        /** Nothing is in scope. Callers short-circuit to an empty result rather than query. */
        data object Empty : ResolvedScope {
            override fun toCondition(): Op<Boolean> = Op.FALSE
        }

        /** Every requested id is a stamped depth-0 root: `root_id IN (rootIds)` is the whole scope. */
        data class ByRoot(
            val rootIds: Set<UUID>
        ) : ResolvedScope {
            override fun toCondition(): Op<Boolean> = WorkItemsTable.rootId inList rootIds
        }

        /** Fallback: the fully expanded id set (roots + all descendants). */
        data class ByIds(
            val ids: Set<UUID>
        ) : ResolvedScope {
            override fun toCondition(): Op<Boolean> = WorkItemsTable.id inList ids.map { EntityID(it, WorkItemsTable) }
        }
    }

    /**
     * Resolve a caller-supplied root set into a scope filter, preferring the flat `root_id`
     * predicate over the recursive expansion whenever it is provably equivalent.
     *
     * Fast path (ALL-OR-NOTHING): taken only when EVERY id in [rootIds] resolves to a row that is
     * a stamped depth-0 root — `parent_id IS NULL AND root_id = id`. V9 stamps every item with its
     * depth-0 ancestor's id and a depth-0 item with its own id, so for such a root R the set
     * `{x : x.root_id = R}` is exactly `subtree(R)`, R included. The scope then costs |rootIds|
     * bound variables instead of one per subtree row.
     *
     * The all-or-nothing guard is the entire safety mechanism: accepting a set containing a
     * below-root id would silently widen that id's scope to its whole containing tree. One
     * non-root, unstamped, inconsistently stamped or missing id and the whole call falls back to
     * [resolveScopeIds]. Rows left NULL by V9 (unreachable from any depth-0 root) are equally
     * unreachable from the CTE, so both paths agree on them.
     *
     * Declared behaviour delta: the fast path does not traverse, so a cyclic `parent_id` edge
     * beneath a stamped root returns rows here where the CTE path raises MAX_TRAVERSAL_DEPTH.
     *
     * Must be called from within an active Exposed transaction.
     */
    private fun resolveScope(rootIds: Set<UUID>): ResolvedScope {
        if (rootIds.isEmpty()) return ResolvedScope.Empty
        if (allAreStampedRoots(rootIds)) return ResolvedScope.ByRoot(rootIds)

        val scopeIds = resolveScopeIds(rootIds)
        return if (scopeIds.isEmpty()) ResolvedScope.Empty else ResolvedScope.ByIds(scopeIds)
    }

    /**
     * True when every id in [rootIds] exists AND is a stamped depth-0 root
     * (`parent_id IS NULL AND root_id = id`). Binds |rootIds| parameters — callers pass the roots
     * they were handed, never an expanded subtree. A missing id fails the count check, so a
     * scope naming a non-existent root falls back to the CTE and keeps its existing behaviour.
     *
     * Exposed DSL (not raw SQL) so H2 and SQLite take the same branch.
     */
    private fun allAreStampedRoots(rootIds: Set<UUID>): Boolean {
        val entityIds = rootIds.map { EntityID(it, WorkItemsTable) }
        var matched = 0
        WorkItemsTable
            .selectAll()
            .where { WorkItemsTable.id inList entityIds }
            .forEach { row ->
                val id = row[WorkItemsTable.id].value
                if (row[WorkItemsTable.parentId] != null || row[WorkItemsTable.rootId] != id) return false
                matched++
            }
        return matched == rootIds.size
    }

    /**
     * Resolve a set of root UUIDs into the full set of UUIDs (roots + all descendants).
     *
     * On SQLite (production), uses a single recursive CTE. On H2 (test environment),
     * falls back to a BFS loop using Exposed DSL (H2's CTE + xerial exec() combination
     * has the same SELECT-returning issue as findDescendants).
     *
     * The roots themselves are included in the returned set.
     *
     * Must be called from within an active Exposed transaction (uses [TransactionManager.current]).
     */
    private fun resolveScopeIds(rootIds: Set<UUID>): Set<UUID> {
        // currentDialect is only valid inside a transaction — this private method must be
        // called from within a suspendTransaction block.
        if (currentDialect is H2Dialect) {
            // H2 BFS fallback: expand the subtree using Exposed DSL instead of a raw CTE.
            // `result` doubles as the visited set — a node already in scope is never re-queued, so a
            // cyclic parent_id edge terminates instead of spinning. Overlapping roots (a root and one
            // of its own descendants) legitimately collide here, so a repeat visit is NOT treated as
            // corruption; only the level bound is. Unlike findDescendants this method is not a
            // Result-returning API — the throw is mapped to Result.Error by every caller's
            // suspendedTransaction / catch block.
            val result = rootIds.toMutableSet()
            val queue = ArrayDeque(rootIds.map { it to 0 })
            while (queue.isNotEmpty()) {
                val (current, level) = queue.removeFirst()
                if (level >= MAX_TRAVERSAL_DEPTH) {
                    throw IllegalStateException(
                        "Scope resolution exceeded the maximum traversal depth of $MAX_TRAVERSAL_DEPTH " +
                            "levels under $current (item hierarchy may be cyclic)",
                    )
                }
                val children =
                    WorkItemsTable
                        .selectAll()
                        .where { WorkItemsTable.parentId eq current }
                        .mapNotNull { toWorkItemOrNull(it) }
                for (child in children) {
                    if (result.add(child.id)) {
                        queue.add(child.id to level + 1)
                    }
                }
            }
            return result
        }

        // SQLite: single recursive CTE — all roots + their full subtrees in one query.
        // Roots are seeded by id = any of rootIds; the UNION ALL branch walks children.
        // UUIDs are stored as BLOBs in SQLite — bind via UUIDColumnType.
        // Use connection.prepareStatement() + executeQuery() to avoid exec() routing
        // through executeUpdate() (xerial sqlite-jdbc rejects SELECT-returning CTEs that way).
        // Same workaround used in findDescendants() and ftsSearch().
        val uuidType = UUIDColumnType()

        // Cycle guard: the `lvl` column bounds the recursive member — without it a cyclic parent_id
        // edge makes the UNION ALL emit rows forever. Cut one level ABOVE the bound so a legitimate
        // tree of exactly MAX_TRAVERSAL_DEPTH levels still resolves in full and any deeper row proves
        // the bound was reached; that case throws, because the scoped queries this feeds would
        // otherwise silently under-report a subtree.
        val placeholders = rootIds.joinToString(",") { "?" }
        val sql =
            """
            WITH RECURSIVE scope(id, lvl) AS (
                SELECT id, 1 FROM work_items WHERE id IN ($placeholders)
                UNION ALL
                SELECT wi.id, s.lvl + 1 FROM work_items wi
                JOIN scope s ON wi.parent_id = s.id
                WHERE s.lvl <= $MAX_TRAVERSAL_DEPTH
            )
            SELECT id, lvl FROM scope
            """.trimIndent()

        // Access the current transaction's connection via TransactionManager.current() —
        // this.connection is only available directly inside a suspendTransaction/transaction
        // lambda where `this` IS the Transaction, but resolveScopeIds() is a class method
        // where `this` is SQLiteWorkItemRepository. TransactionManager.current() is safe
        // to call here because this method is always invoked from within an active transaction.
        val conn = TransactionManager.current().connection
        val ps = conn.prepareStatement(sql, false)
        try {
            val args = rootIds.map { uuidType to it }
            ps.fillParameters(args)
            val rs = ps.executeQuery()
            val ids = mutableSetOf<UUID>()
            var boundExceeded = false
            while (rs.next()) {
                val rawId = rs.getObject("id")

                @Suppress("UNCHECKED_CAST")
                ids.add(uuidType.valueFromDB(rawId!!) as UUID)
                if ((rs.getObject("lvl") as Number).toInt() > MAX_TRAVERSAL_DEPTH) boundExceeded = true
            }
            if (boundExceeded) {
                throw IllegalStateException(
                    "Scope resolution exceeded the maximum traversal depth of $MAX_TRAVERSAL_DEPTH " +
                        "levels (item hierarchy may be cyclic)",
                )
            }
            return ids
        } finally {
            ps.closeIfPossible()
        }
    }

    override suspend fun findAncestorChains(itemIds: Set<UUID>): Result<Map<UUID, List<WorkItem>>> =
        findAncestorChainsDetailed(itemIds).map { chains -> chains.mapValues { (_, chain) -> chain.ancestors } }

    override suspend fun findAncestorChainsDetailed(itemIds: Set<UUID>): Result<Map<UUID, AncestorChain>> {
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

            // Build ancestor chains for each input itemId.
            // Both early exits are corruption, not "this item is shallow" — each one is reported on
            // the returned AncestorChain so callers that decide safety on chain completeness
            // (root-scope authorization, reparent cycle guards) can tell the two apart.
            val result =
                itemIds.associateWith { itemId ->
                    val chain = mutableListOf<WorkItem>()
                    val visited = mutableSetOf<String>()
                    var truncationReason: String? = null
                    val current = cache[itemId.toString()]
                    var parentIdStr = current?.parentId?.toString()
                    while (parentIdStr != null) {
                        if (parentIdStr in visited) {
                            logger.warn("Cycle detected in ancestor chain for item $itemId at parent $parentIdStr — breaking")
                            truncationReason = AncestorChain.REASON_CYCLE
                            break
                        }
                        visited.add(parentIdStr)
                        val ancestor = cache[parentIdStr]
                        if (ancestor == null) {
                            logger.warn(
                                "Ancestor $parentIdStr of item $itemId is missing or failed domain validation — chain truncated",
                            )
                            truncationReason = AncestorChain.REASON_MISSING_ANCESTOR
                            break
                        }
                        chain.add(0, ancestor) // prepend so order is root -> direct parent
                        parentIdStr = ancestor.parentId?.toString()
                    }
                    AncestorChain(
                        ancestors = chain,
                        truncated = truncationReason != null,
                        truncationReason = truncationReason,
                    )
                }
            Result.Success(result)
        }
    }

    /**
     * Builds a filtered SELECT query scoped to [scope] — the resolved WHERE-clause form of the
     * caller-supplied roots (see [resolveScope]).
     *
     * The scope filter is the first condition so SQLite can push it through an index early:
     * `idx_work_items_root_id` on the fast path, the primary key on the expanded-id path.
     */
    private fun buildScopedQuery(
        scope: ResolvedScope,
        parentId: UUID?,
        depth: Int?,
        role: Role?,
        priority: Priority?,
        tags: List<String>?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        modifiedAfter: Instant?,
        modifiedBefore: Instant?,
        roleChangedAfter: Instant?,
        roleChangedBefore: Instant?,
        type: String?,
        claimStatus: String?,
        now: Instant,
    ): Query {
        val conditions = mutableListOf<Op<Boolean>>()

        // Scope constraint: root_id IN (roots) on the fast path, id IN (resolved set) otherwise
        conditions.add(scope.toCondition())

        // Additional filter conditions (same logic as buildFilteredQuery)
        parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }
        depth?.let { conditions.add(WorkItemsTable.depth eq it) }
        role?.let { conditions.add(WorkItemsTable.role eq it.name.lowercase()) }
        priority?.let { conditions.add(WorkItemsTable.priority eq it.name.lowercase()) }
        tags?.takeIf { it.isNotEmpty() }?.let { conditions.add(buildTagFilter(it)) }
        createdAfter?.let { conditions.add(WorkItemsTable.createdAt greaterEq it) }
        createdBefore?.let { conditions.add(WorkItemsTable.createdAt lessEq it) }
        modifiedAfter?.let { conditions.add(WorkItemsTable.modifiedAt greaterEq it) }
        modifiedBefore?.let { conditions.add(WorkItemsTable.modifiedAt lessEq it) }
        roleChangedAfter?.let { conditions.add(WorkItemsTable.roleChangedAt greaterEq it) }
        roleChangedBefore?.let { conditions.add(WorkItemsTable.roleChangedAt lessEq it) }
        type?.let { conditions.add(WorkItemsTable.type eq it) }

        claimStatus?.let {
            when (it.lowercase()) {
                "claimed" -> {
                    conditions.add(WorkItemsTable.claimedBy.isNotNull())
                    conditions.add(WorkItemsTable.claimExpiresAt greater now)
                }
                "unclaimed" -> {
                    conditions.add(WorkItemsTable.claimedBy.isNull())
                }
                "expired" -> {
                    conditions.add(WorkItemsTable.claimedBy.isNotNull())
                    conditions.add(WorkItemsTable.claimExpiresAt lessEq now)
                }
            }
        }

        return if (conditions.size == 1) {
            WorkItemsTable.selectAll().where { conditions[0] }
        } else {
            val combined = conditions.reduce { acc, op -> acc and op }
            WorkItemsTable.selectAll().where { combined }
        }
    }

    /**
     * Build a tag filter that matches tags at word boundaries within a comma-separated string.
     * Builds a filtered SELECT query from optional filter parameters.
     * Shared by [findByFilters] and [countByFilters] to avoid duplicating condition construction.
     *
     * @param claimStatus Optional claim-status filter: "claimed", "unclaimed", or "expired".
     *   - "claimed"   — `claimed_by IS NOT NULL AND claim_expires_at > now`
     *   - "unclaimed" — `claimed_by IS NULL`
     *   - "expired"   — `claimed_by IS NOT NULL AND claim_expires_at <= now`
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
        roleChangedBefore: Instant?,
        type: String? = null,
        claimStatus: String? = null,
        /** DB-side current time. Callers must obtain this via [dbNow] before calling. */
        now: Instant = Instant.now()
    ): Query {
        val conditions = mutableListOf<Op<Boolean>>()

        parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }
        depth?.let { conditions.add(WorkItemsTable.depth eq it) }
        role?.let { conditions.add(WorkItemsTable.role eq it.name.lowercase()) }
        priority?.let { conditions.add(WorkItemsTable.priority eq it.name.lowercase()) }
        tags?.takeIf { it.isNotEmpty() }?.let { conditions.add(buildTagFilter(it)) }
        // NOTE: The old `query` LIKE-filter has been removed (breaking change, FTS5 feature T4).
        // Full-text search is now routed through ftsSearch() via QueryItemsTool's `search` operation.
        createdAfter?.let { conditions.add(WorkItemsTable.createdAt greaterEq it) }
        createdBefore?.let { conditions.add(WorkItemsTable.createdAt lessEq it) }
        modifiedAfter?.let { conditions.add(WorkItemsTable.modifiedAt greaterEq it) }
        modifiedBefore?.let { conditions.add(WorkItemsTable.modifiedAt lessEq it) }
        roleChangedAfter?.let { conditions.add(WorkItemsTable.roleChangedAt greaterEq it) }
        roleChangedBefore?.let { conditions.add(WorkItemsTable.roleChangedAt lessEq it) }
        type?.let { conditions.add(WorkItemsTable.type eq it) }

        // Claim-status filter — applied at DB level to avoid loading unclaimed/claimed rows unnecessarily.
        // `now` was obtained from dbNow() by the caller so freshness is evaluated on the DB clock.
        claimStatus?.let {
            when (it.lowercase()) {
                "claimed" -> {
                    // Active claim: claimed_by IS NOT NULL AND claim_expires_at > now
                    conditions.add(WorkItemsTable.claimedBy.isNotNull())
                    conditions.add(WorkItemsTable.claimExpiresAt greater now)
                }
                "unclaimed" -> {
                    // Never-claimed (or explicitly released): claimed_by IS NULL
                    conditions.add(WorkItemsTable.claimedBy.isNull())
                }
                "expired" -> {
                    // Claim placed but TTL has passed: claimed_by IS NOT NULL AND claim_expires_at <= now
                    conditions.add(WorkItemsTable.claimedBy.isNotNull())
                    conditions.add(WorkItemsTable.claimExpiresAt lessEq now)
                }
                // Unknown values are silently ignored here; validation happens at the tool layer.
            }
        }

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
            rootId = row[WorkItemsTable.rootId],
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
            type = row[WorkItemsTable.type],
            properties = row[WorkItemsTable.properties],
            createdAt = row[WorkItemsTable.createdAt],
            modifiedAt = row[WorkItemsTable.modifiedAt],
            roleChangedAt = row[WorkItemsTable.roleChangedAt],
            version = row[WorkItemsTable.version],
            claimedBy = row[WorkItemsTable.claimedBy],
            claimedAt = row[WorkItemsTable.claimedAt],
            claimExpiresAt = row[WorkItemsTable.claimExpiresAt],
            originalClaimedAt = row[WorkItemsTable.originalClaimedAt],
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
