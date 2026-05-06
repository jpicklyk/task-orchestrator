package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
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
            newSuspendedTransaction(db = databaseManager.getDatabase()) {
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
        offset: Int,
        type: String?,
        claimStatus: String?
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find WorkItems by filters") {
            // Fetch DB-side now once so all claim-freshness comparisons in buildFilteredQuery
            // use the DB clock rather than the JVM clock.
            val nowFromDb = if (claimStatus != null) dbNow() else Instant.now()
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
        roleChangedBefore: Instant?,
        type: String?,
        claimStatus: String?
    ): Result<Int> =
        databaseManager.suspendedTransaction("Failed to count WorkItems by filters") {
            val nowFromDb = if (claimStatus != null) dbNow() else Instant.now()
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

    override suspend fun claim(
        itemId: UUID,
        agentId: String,
        ttlSeconds: Int
    ): ClaimResult =
        try {
            newSuspendedTransaction(db = databaseManager.getDatabase()) {
                // Both agentId and itemId are bound as typed JDBC parameters — no string interpolation.
                // itemId binds via UUIDColumnType so the WHERE id = ? predicate uses the primary-key
                // index directly (the prior HEX(id) = ? form wrapped the column in a function call,
                // which the SQLite planner cannot push through the PK index, forcing a full scan).
                val agentIdType = VarCharColumnType(500)
                val uuidType = UUIDColumnType()

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
                          claim_expires_at = datetime('now', '+$ttlSeconds seconds'),
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
                                    // so the hint reflects DB-side remaining TTL, not JVM-side.
                                    val retryAfterMs =
                                        if (item.claimExpiresAt != null) {
                                            val dbNowInstant = dbNow()
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
                }

                result
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
            newSuspendedTransaction(db = databaseManager.getDatabase()) {
                // Both agentId and itemId are bound as typed JDBC parameters — no string interpolation.
                // itemId binds via UUIDColumnType so the WHERE id = ? predicate uses the primary-key
                // index directly.
                val agentIdType = VarCharColumnType(500)
                val uuidType = UUIDColumnType()

                // Check existence and current claimant before attempting update.
                val row =
                    WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
                        ?: return@newSuspendedTransaction ReleaseResult.NotFound(itemId)

                val item = toWorkItem(row)
                if (item.claimedBy != agentId) {
                    return@newSuspendedTransaction ReleaseResult.NotClaimedByYou(itemId)
                }

                // Release: clear all four claim fields atomically.
                exec(
                    """
                    UPDATE work_items
                      SET claimed_by = NULL,
                          claimed_at = NULL,
                          claim_expires_at = NULL,
                          original_claimed_at = NULL,
                          version = version + 1
                      WHERE id = ?
                        AND claimed_by = ?
                    """.trimIndent(),
                    args =
                        listOf(
                            uuidType to itemId,
                            agentIdType to agentId,
                        )
                )

                // Read back updated state.
                val updatedRow =
                    WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
                        ?: return@newSuspendedTransaction ReleaseResult.NotFound(itemId)

                ReleaseResult.Success(toWorkItem(updatedRow))
            }
        } catch (e: Exception) {
            logger.error("Failed to release WorkItem $itemId for agent $agentId: ${e.message}", e)
            ReleaseResult.DBError(itemId, e)
        }

    override suspend fun findForNextItem(
        role: Role,
        parentId: UUID?,
        excludeActiveClaims: Boolean,
        limit: Int
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find next items by role") {
            val conditions = mutableListOf<Op<Boolean>>()

            // Role filter
            conditions.add(WorkItemsTable.role eq role.name.lowercase())

            // Optional parent scope
            parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }

            // Claim filter: exclude items with an active (non-expired) claim.
            // An item is "actively claimed" when claimed_by IS NOT NULL AND claim_expires_at > now.
            // The complement (items to include) is: claimed_by IS NULL OR claim_expires_at <= now.
            // Use DB-side now so the freshness decision is made on the DB clock, not the JVM clock.
            if (excludeActiveClaims) {
                val notClaimed = WorkItemsTable.claimedBy.isNull()
                val dbNowInstant = dbNow()
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

    override suspend fun findClaimable(
        role: Role,
        parentId: UUID?,
        tags: List<String>?,
        priority: Priority?,
        type: String?,
        complexityMax: Int?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        roleChangedAfter: Instant?,
        roleChangedBefore: Instant?,
        orderBy: NextItemOrder,
        limit: Int,
    ): Result<List<WorkItem>> =
        databaseManager.suspendedTransaction("Failed to find claimable items") {
            val conditions = mutableListOf<Op<Boolean>>()

            // Role filter — always required
            conditions.add(WorkItemsTable.role eq role.name.lowercase())

            // Optional parent scope
            parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }

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

            // Role-changed-at time window
            roleChangedAfter?.let { conditions.add(WorkItemsTable.roleChangedAt greaterEq it) }
            roleChangedBefore?.let { conditions.add(WorkItemsTable.roleChangedAt lessEq it) }

            // Active-claim exclusion — always applied; claim-eligibility by definition requires this.
            // Include items where: claimed_by IS NULL  OR  claim_expires_at <= now.
            // DB-side now avoids JVM/SQLite clock skew.
            val dbNowInstant = dbNow()
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

            val items = orderedQuery.limit(limit).mapNotNull { toWorkItemOrNull(it) }

            Result.Success(items)
        }

    override suspend fun countByClaimStatus(parentId: UUID?): Result<ClaimStatusCounts> =
        databaseManager.suspendedTransaction("Failed to count WorkItems by claim status") {
            // Use DB-side clock so counts are consistent with the DB's view of claim freshness.
            val now = dbNow()

            // Helper to build a base condition list optionally scoped to a parent
            fun baseConditions(): MutableList<Op<Boolean>> {
                val conds = mutableListOf<Op<Boolean>>()
                parentId?.let { conds.add(WorkItemsTable.parentId eq it) }
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
