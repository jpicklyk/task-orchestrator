package io.github.jpicklyk.mcptask.current.claim.internal.sqlite

import io.github.jpicklyk.mcptask.current.claim.ClaimResult
import io.github.jpicklyk.mcptask.current.claim.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.claim.ClaimStore
import io.github.jpicklyk.mcptask.current.claim.EligibilityBreakdown
import io.github.jpicklyk.mcptask.current.claim.EligibilityQuery
import io.github.jpicklyk.mcptask.current.claim.ReleaseResult
import io.github.jpicklyk.mcptask.current.model.claim.ClaimState
import io.github.jpicklyk.mcptask.current.model.claim.ClaimStatus
import io.github.jpicklyk.mcptask.current.model.error.ValidationException
import io.github.jpicklyk.mcptask.current.model.item.ItemSlice
import io.github.jpicklyk.mcptask.current.model.item.NextItemOrder
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.port.ClaimWrite
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.port.unitNow
import io.github.jpicklyk.mcptask.current.platform.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.ClaimPredicates
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.ItemQueries
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.ItemQuerySpec
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.ResolvedScope
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.ScopeResolver
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.WorkItemRows
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.WorkViewSql
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.visibilityConditions
import io.github.jpicklyk.mcptask.current.platform.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.platform.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.NotOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * SQLite [ClaimStore]: the only writer of the four claim columns. Every freshness decision binds the ambient unit
 * instant ([unitNow]) and computes expiry (`now + ttl`) in Kotlin; nothing reads a database clock. Claim writes do
 * not bump `version`.
 */
class SqliteClaimStore(
    private val databaseManager: DatabaseManager,
    private val clock: Clock
) : ClaimStore {
    private companion object {
        /** Mirrors the `claimedBy` limit in [WorkItem.violations]. */
        const val MAX_AGENT_ID_LENGTH = 500
    }

    @ClaimWrite
    override suspend fun claim(
        itemId: UUID,
        agentId: String,
        ttlSeconds: Int,
        assignmentId: UUID?
    ): ClaimResult {
        // The holder identity must satisfy the item invariants BEFORE anything is written.
        if (agentId.isBlank()) throw ValidationException("claimedBy must not be blank when set")
        if (agentId.length > MAX_AGENT_ID_LENGTH) throw ValidationException("claimedBy must not exceed $MAX_AGENT_ID_LENGTH characters")
        val now = clock.unitNow()
        return databaseManager.writeTx("WorkItemRepository.claim") {
            // Step 1 (acquire): take or refresh the target only when it has no active claim or is already this
            // agent's, and is not TERMINAL. Acquisition runs FIRST so that Step 2 (auto-release of prior claims)
            // only fires when acquisition succeeds; otherwise a failed claim would cost the agent its existing one.
            val prior =
                WorkItemsTable
                    .select(WorkItemsTable.claimedBy, WorkItemsTable.claimAssignmentId, WorkItemsTable.originalClaimedAt)
                    .where { WorkItemsTable.id eq itemId }
                    .singleOrNull()
            // Same-holder refresh keeps the original claim time; a take-over (or first claim) starts it now.
            val original =
                if (prior != null &&
                    prior[WorkItemsTable.claimedBy] == agentId &&
                    prior[WorkItemsTable.claimAssignmentId] == assignmentId
                ) {
                    try {
                        prior[WorkItemsTable.originalClaimedAt] ?: now
                    } catch (e: IllegalArgumentException) {
                        now // unreadable stored text: same fallback as the total row mapper treats it (no usable value)
                    }
                } else {
                    now
                }
            WorkItemsTable.update({
                (WorkItemsTable.id eq itemId) and
                    (WorkItemsTable.role neq Role.TERMINAL.name.lowercase()) and
                    ClaimPredicates.acquirable(agentId, assignmentId, now)
            }) {
                it[claimedBy] = agentId
                it[claimAssignmentId] = assignmentId
                it[claimedAt] = now
                it[claimExpiresAt] = now.plusSeconds(ttlSeconds.toLong())
                it[originalClaimedAt] = original
            }

            // Read back the current state: if the claim columns name the holder pair, acquisition succeeded.
            val row = WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
            val result =
                if (row == null) {
                    ClaimResult.NotFound(itemId)
                } else {
                    val item = WorkItemRows.withTraits(WorkItemRows.toWorkItem(row))
                    when {
                        item.role == Role.TERMINAL -> ClaimResult.TerminalItem(itemId)
                        // A lifecycle-free (NULL role) row never matches the acquire predicate (`role <> 'terminal'` is
                        // not true for NULL) and is never claimable: report it before the AlreadyClaimed branch, whose
                        // active-claim read would fail on an unclaimed row.
                        item.role == null -> ClaimResult.LifecycleFree(itemId)
                        item.claimedBy == agentId && item.claimAssignmentId == assignmentId -> ClaimResult.Success(item)
                        else -> {
                            // Held by someone else with an active claim: the hint is the time left, at least 1 ms.
                            val held = checkNotNull(ClaimState.of(item)) { "A refused claim on $itemId has no active claim on the row" }
                            val retryAfterMs = maxOf(1L, held.expiresAt.toEpochMilli() - now.toEpochMilli())
                            ClaimResult.AlreadyClaimed(itemId, held.expiresAt, retryAfterMs)
                        }
                    }
                }

            // Step 2 (auto-release prior claims): only when acquisition SUCCEEDED, and only the claims of the SAME holder
            // pair (a seat never evicts a sibling seat of its principal, nor a principal-level claim). The about-to-be-
            // evicted ids are read first, in this same transaction, so the event decorator can emit item.updated for each.
            if (result is ClaimResult.Success) {
                val releasedIds =
                    WorkItemsTable
                        .select(WorkItemsTable.id)
                        .where { ClaimPredicates.holderIs(agentId, assignmentId) and (WorkItemsTable.id neq itemId) }
                        .map { it[WorkItemsTable.id].value }
                if (releasedIds.isNotEmpty()) {
                    WorkItemsTable.update({ ClaimPredicates.holderIs(agentId, assignmentId) and (WorkItemsTable.id neq itemId) }) {
                        clearClaimColumns(it)
                    }
                }
                result.copy(releasedItemIds = releasedIds)
            } else {
                result
            }
        }
    }

    @ClaimWrite
    override suspend fun release(
        itemId: UUID,
        agentId: String,
        assignmentId: UUID?
    ): ReleaseResult =
        databaseManager.writeTx("WorkItemRepository.release") {
            // Single statement: the WHERE clause atomically asserts both item identity and claimant ownership,
            // and the affected-row count decides the outcome (no SELECT-then-UPDATE window).
            val rowsUpdated =
                WorkItemsTable.update({ (WorkItemsTable.id eq itemId) and ClaimPredicates.holderIs(agentId, assignmentId) }) {
                    clearClaimColumns(it)
                }
            if (rowsUpdated > 0) {
                val updatedRow =
                    WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
                        ?: return@writeTx ReleaseResult.NotFound(itemId)
                ReleaseResult.Success(WorkItemRows.withTraits(WorkItemRows.toWorkItem(updatedRow)))
            } else {
                val exists = WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
                if (exists == null) ReleaseResult.NotFound(itemId) else ReleaseResult.NotClaimedByYou(itemId)
            }
        }

    @ClaimWrite
    override suspend fun clear(itemId: UUID): Boolean =
        databaseManager.writeTx("WorkItemRepository.clearClaim") {
            WorkItemsTable.update({ WorkItemsTable.id eq itemId }) { clearClaimColumns(it) } > 0
        }

    override suspend fun findHeldBy(
        agentId: String,
        assignmentId: UUID?
    ): List<WorkItem> =
        databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where { ClaimPredicates.holderIs(agentId, assignmentId) }
                .map { WorkItemRows.toWorkItem(it) }
                .let { WorkItemRows.withTraits(it) }
        }

    override suspend fun findLapsedClaims(): List<WorkItem> {
        val now = clock.unitNow()
        return databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where { ClaimPredicates.lapsed(now) }
                .map { WorkItemRows.toWorkItem(it) }
                .let { WorkItemRows.withTraits(it) }
        }
    }

    private fun clearClaimColumns(stmt: org.jetbrains.exposed.v1.core.statements.UpdateStatement) {
        stmt[WorkItemsTable.claimedBy] = null
        stmt[WorkItemsTable.claimedAt] = null
        stmt[WorkItemsTable.claimExpiresAt] = null
        stmt[WorkItemsTable.originalClaimedAt] = null
        stmt[WorkItemsTable.claimAssignmentId] = null
    }

    override suspend fun findEligible(
        query: EligibilityQuery,
        limit: Int,
        offset: Int,
        scope: AccessScope,
    ): ItemSlice {
        val now = clock.unitNow()
        return databaseManager.readTx {
            val selector = selectorConditions(query, now, scope) ?: return@readTx ItemSlice(emptyList(), 0)
            if (!query.includeClaimed) {
                selector.add(ClaimPredicates.notActive(now))
                selector.add(NotOp(shieldedByAncestorClaim(query.requestingAgentId, query.requestingAssignmentId, now)))
            }
            selector.add(NotOp(WorkViewSql.dependencyBlocked()))
            val condition = selector.reduce { acc, op -> acc and op }
            val items =
                orderEligible(WorkItemsTable.selectAll().where { condition }, query.orderBy)
                    .limit(limit)
                    .offset(offset.coerceAtLeast(0).toLong())
                    .map { WorkItemRows.toWorkItem(it) }
                    .let { WorkItemRows.withTraits(it) }
            // The exact eligible count, always a second statement (never inferred from a short page) so the
            // statement count of a call does not depend on how many rows match.
            val total =
                WorkItemsTable
                    .selectAll()
                    .where { condition }
                    .count()
                    .toInt()
            ItemSlice(items, total)
        }
    }

    override suspend fun explainIneligible(
        query: EligibilityQuery,
        scope: AccessScope,
    ): EligibilityBreakdown {
        val now = clock.unitNow()
        return databaseManager.readTx {
            val selector = selectorConditions(query, now, scope) ?: return@readTx EligibilityBreakdown(0, 0, 0)
            val matched = WorkItemsTable.id.count()
            val activelyClaimed = WorkViewSql.countWhen(ClaimPredicates.active(now))
            val dependencyBlocked =
                WorkViewSql.countWhen(
                    ClaimPredicates.notActive(now) and
                        NotOp(shieldedByAncestorClaim(query.requestingAgentId, query.requestingAssignmentId, now)) and
                        WorkViewSql.dependencyBlocked()
                )
            val condition = selector.reduce { acc, op -> acc and op }
            val row =
                WorkItemsTable
                    .select(matched, activelyClaimed, dependencyBlocked)
                    .where { condition }
                    .single()
            EligibilityBreakdown(
                matched = row[matched].toInt(),
                activelyClaimed = row[activelyClaimed] ?: 0,
                dependencyBlocked = row[dependencyBlocked] ?: 0,
            )
        }
    }

    override suspend fun claimSummary(
        rootIds: Set<UUID>?,
        scope: AccessScope,
    ): ClaimStatusCounts {
        if (rootIds?.isEmpty() == true || scope.rootIds?.isEmpty() == true) return ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0)
        val now = clock.unitNow()
        return databaseManager.readTx {
            val resolved = rootIds?.let { ScopeResolver.subtree(it) }
            if (resolved == ResolvedScope.Empty) return@readTx ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0)
            val visible = visibilityConditions(scope) ?: return@readTx ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0)
            val conditions = ArrayList<Op<Boolean>>(visible)
            resolved?.let { conditions.add(it.toCondition()) }
            val buckets = claimBuckets(now)
            val base = WorkItemsTable.select(buckets.active, buckets.expired, buckets.unclaimed)
            val row = (if (conditions.isNotEmpty()) base.where { conditions.reduce { acc, op -> acc and op } } else base).single()
            buckets.read(row)
        }
    }

    override suspend fun claimSummaryByParent(
        parentIds: Set<UUID>,
        scope: AccessScope,
    ): Map<UUID, ClaimStatusCounts> {
        if (parentIds.isEmpty()) return emptyMap()
        val now = clock.unitNow()
        return databaseManager.readTx {
            val result = LinkedHashMap<UUID, ClaimStatusCounts>()
            parentIds.forEach { result[it] = ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0) }
            val visible = visibilityConditions(scope) ?: return@readTx result
            val buckets = claimBuckets(now)
            val condition = (listOf(WorkViewSql.inIdSet(WorkItemsTable.parentId, parentIds)) + visible).reduce { acc, op -> acc and op }
            WorkItemsTable
                .select(WorkItemsTable.parentId, buckets.active, buckets.expired, buckets.unclaimed)
                .where { condition }
                .groupBy(WorkItemsTable.parentId)
                .forEach { row -> row[WorkItemsTable.parentId]?.let { result[it] = buckets.read(row) } }
            result
        }
    }

    /** The three claim-status buckets as `SUM(CASE ...)` columns of one statement, from the claim predicates. */
    private class ClaimBuckets(
        val active: Sum<Int>,
        val expired: Sum<Int>,
        val unclaimed: Sum<Int>,
    ) {
        fun read(row: ResultRow): ClaimStatusCounts =
            ClaimStatusCounts(active = row[active] ?: 0, expired = row[expired] ?: 0, unclaimed = row[unclaimed] ?: 0)
    }

    private fun claimBuckets(now: Instant): ClaimBuckets =
        ClaimBuckets(
            active = WorkViewSql.countWhen(ClaimPredicates.forStatus(ClaimStatus.CLAIMED, now)),
            expired = WorkViewSql.countWhen(ClaimPredicates.forStatus(ClaimStatus.EXPIRED, now)),
            unclaimed = WorkViewSql.countWhen(ClaimPredicates.forStatus(ClaimStatus.UNCLAIMED, now)),
        )

    /**
     * The selector filters of [query] AND the visibility of [scope], or null when
     * either is empty.
     * Must run inside a transaction: resolving `rootIds` may probe or walk the hierarchy.
     */
    private fun selectorConditions(
        query: EligibilityQuery,
        now: Instant,
        scope: AccessScope = AccessScope.unrestricted(),
    ): MutableList<Op<Boolean>>? {
        val spec =
            ItemQuerySpec(
                role = query.role,
                parentId = query.parentId,
                tags = query.tags,
                priority = query.priority,
                type = query.type,
                complexityMax = query.complexityMax,
                createdAfter = query.createdAfter,
                createdBefore = query.createdBefore,
                modifiedAfter = query.modifiedAfter,
                modifiedBefore = query.modifiedBefore,
                roleChangedAfter = query.roleChangedAfter,
                roleChangedBefore = query.roleChangedBefore,
            )
        val conditions = ItemQueries.conditions(spec, query.rootIds?.let { ScopeResolver.subtree(it) }, now) ?: return null
        // The caller's access scope, in the same SQL as the selector (before paging and counting); nothing visible = nothing.
        conditions.addAll(visibilityConditions(scope) ?: return null)
        if (query.leavesOnly) conditions.add(NotOp(WorkViewSql.hasLifecycleChild()))
        if (query.buildingOnly) conditions.add(WorkItemsTable.builds.isNull() or (WorkItemsTable.builds eq true))
        return conditions
    }

    /**
     * True when an ancestor (any depth) of the outer item holds an active claim at [now] by a disqualifying agent:
     * any agent when [requestingAgentId] is null, otherwise any holder other than the pair ([requestingAgentId],
     * [requestingAssignmentId]). The claimed items are the seed of one downward walk ([WorkViewSql.shieldedByAncestorClaim]).
     */
    private fun shieldedByAncestorClaim(
        requestingAgentId: String?,
        requestingAssignmentId: UUID?,
        now: Instant,
    ): Op<Boolean> {
        val claimed =
            if (requestingAgentId == null) {
                ClaimPredicates.active(now)
            } else {
                ClaimPredicates.active(now) and NotOp(ClaimPredicates.holderIs(requestingAgentId, requestingAssignmentId))
            }
        val seed: Query = WorkItemsTable.select(WorkItemsTable.id).where { claimed }
        return WorkViewSql.shieldedByAncestorClaim(seed)
    }

    /** [orderBy] plus the `id ASC` tiebreak that makes offset pages disjoint and stable. */
    private fun orderEligible(
        query: Query,
        orderBy: NextItemOrder,
    ): Query {
        val ordered =
            when (orderBy) {
                NextItemOrder.PRIORITY_THEN_COMPLEXITY ->
                    query
                        .orderBy(ItemQueries.priorityRank(), SortOrder.ASC)
                        .orderBy(WorkItemsTable.rank, SortOrder.ASC_NULLS_LAST)
                        .orderBy(WorkItemsTable.complexity, SortOrder.ASC_NULLS_LAST)
                        .orderBy(WorkItemsTable.createdAt, SortOrder.ASC)
                NextItemOrder.OLDEST_FIRST -> query.orderBy(WorkItemsTable.createdAt, SortOrder.ASC)
                NextItemOrder.NEWEST_FIRST -> query.orderBy(WorkItemsTable.createdAt, SortOrder.DESC)
            }
        return ordered.orderBy(WorkItemsTable.id, SortOrder.ASC)
    }
}
