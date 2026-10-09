package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.application.port.ClaimStore
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.SelectorMatchCounts
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * THE claim predicates: the only place SQL decides whether a claim is active, expired or acquirable. They are the
 * SQL form of [ClaimState.isActive] (active iff `claim_expires_at > at`, so an expiry equal to `at` is expired; a
 * `claimed_by` with no usable timestamps is never active). Every claim-aware query in the item and claim stores
 * builds its condition here; a guard test keeps `claim_expires_at` comparisons out of every other file.
 */
internal object ClaimPredicates {
    /** `claimed_by` is set and the claim is still running at [at]. */
    fun active(at: Instant): Op<Boolean> =
        WorkItemsTable.claimedBy.isNotNull() and
            WorkItemsTable.claimedAt.isNotNull() and
            WorkItemsTable.claimExpiresAt.isNotNull() and
            (WorkItemsTable.claimExpiresAt greater at)

    /** `claimed_by` is set but the claim is not active at [at] (lapsed, or missing its timestamps). */
    fun expired(at: Instant): Op<Boolean> =
        WorkItemsTable.claimedBy.isNotNull() and
            (WorkItemsTable.claimedAt.isNull() or WorkItemsTable.claimExpiresAt.isNull() or (WorkItemsTable.claimExpiresAt lessEq at))

    /** `claimed_by` is set with a recorded expiry that is at or before [at]: a claim that ran out. */
    fun lapsed(at: Instant): Op<Boolean> =
        WorkItemsTable.claimedBy.isNotNull() and
            WorkItemsTable.claimExpiresAt.isNotNull() and
            (WorkItemsTable.claimExpiresAt lessEq at)

    /** Nobody holds the item. */
    fun unclaimed(): Op<Boolean> = WorkItemsTable.claimedBy.isNull()

    /** No active claim at [at]: [unclaimed] or [expired]. */
    fun notActive(at: Instant): Op<Boolean> = unclaimed() or expired(at)

    /** [agentId] may take or refresh the item at [at]: no active claim, or already its own. */
    fun acquirable(
        agentId: String,
        at: Instant
    ): Op<Boolean> = notActive(at) or (WorkItemsTable.claimedBy eq agentId)

    /** The condition for a [ClaimStatus] filter at [at]. */
    fun forStatus(
        status: ClaimStatus,
        at: Instant
    ): Op<Boolean> =
        when (status) {
            ClaimStatus.CLAIMED -> active(at)
            ClaimStatus.UNCLAIMED -> unclaimed()
            ClaimStatus.EXPIRED -> expired(at)
        }
}

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

    override suspend fun claim(
        itemId: UUID,
        agentId: String,
        ttlSeconds: Int
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
                    .select(WorkItemsTable.claimedBy, WorkItemsTable.originalClaimedAt)
                    .where { WorkItemsTable.id eq itemId }
                    .singleOrNull()
            // Same-agent refresh keeps the original claim time; a take-over (or first claim) starts it now.
            val original =
                if (prior != null && prior[WorkItemsTable.claimedBy] == agentId) {
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
                    ClaimPredicates.acquirable(agentId, now)
            }) {
                it[claimedBy] = agentId
                it[claimedAt] = now
                it[claimExpiresAt] = now.plusSeconds(ttlSeconds.toLong())
                it[originalClaimedAt] = original
            }

            // Read back the current state: if claimedBy == agentId, acquisition succeeded.
            val row = WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
            val result =
                if (row == null) {
                    ClaimResult.NotFound(itemId)
                } else {
                    val item = WorkItemRows.toWorkItem(row)
                    when {
                        item.role == Role.TERMINAL -> ClaimResult.TerminalItem(itemId)
                        item.claimedBy == agentId -> ClaimResult.Success(item)
                        else -> {
                            // Held by someone else with an active claim: the hint is the time left, at least 1 ms.
                            val retryAfterMs =
                                ClaimState.of(item)?.let { maxOf(1L, it.expiresAt.toEpochMilli() - now.toEpochMilli()) }
                            ClaimResult.AlreadyClaimed(itemId, retryAfterMs)
                        }
                    }
                }

            // Step 2 (auto-release prior claims): only when acquisition SUCCEEDED. The about-to-be-evicted ids are
            // read first, in this same transaction, so the event decorator can emit item.updated for each.
            if (result is ClaimResult.Success) {
                val releasedIds =
                    WorkItemsTable
                        .select(WorkItemsTable.id)
                        .where { (WorkItemsTable.claimedBy eq agentId) and (WorkItemsTable.id neq itemId) }
                        .map { it[WorkItemsTable.id].value }
                if (releasedIds.isNotEmpty()) {
                    WorkItemsTable.update({ (WorkItemsTable.claimedBy eq agentId) and (WorkItemsTable.id neq itemId) }) {
                        clearClaimColumns(it)
                    }
                }
                result.copy(releasedItemIds = releasedIds)
            } else {
                result
            }
        }
    }

    override suspend fun release(
        itemId: UUID,
        agentId: String
    ): ReleaseResult =
        databaseManager.writeTx("WorkItemRepository.release") {
            // Single statement: the WHERE clause atomically asserts both item identity and claimant ownership,
            // and the affected-row count decides the outcome (no SELECT-then-UPDATE window).
            val rowsUpdated =
                WorkItemsTable.update({ (WorkItemsTable.id eq itemId) and (WorkItemsTable.claimedBy eq agentId) }) {
                    clearClaimColumns(it)
                }
            if (rowsUpdated > 0) {
                val updatedRow =
                    WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
                        ?: return@writeTx ReleaseResult.NotFound(itemId)
                ReleaseResult.Success(WorkItemRows.toWorkItem(updatedRow))
            } else {
                val exists = WorkItemsTable.selectAll().where { WorkItemsTable.id eq itemId }.singleOrNull()
                if (exists == null) ReleaseResult.NotFound(itemId) else ReleaseResult.NotClaimedByYou(itemId)
            }
        }

    override suspend fun clear(itemId: UUID): Boolean =
        databaseManager.writeTx("WorkItemRepository.clearClaim") {
            WorkItemsTable.update({ WorkItemsTable.id eq itemId }) { clearClaimColumns(it) } > 0
        }

    override suspend fun findHeldBy(agentId: String): List<WorkItem> =
        databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where { WorkItemsTable.claimedBy eq agentId }
                .map { WorkItemRows.toWorkItem(it) }
        }

    override suspend fun findLapsedClaims(): List<WorkItem> {
        val now = clock.unitNow()
        return databaseManager.readTx {
            WorkItemsTable
                .selectAll()
                .where { ClaimPredicates.lapsed(now) }
                .map { WorkItemRows.toWorkItem(it) }
        }
    }

    private fun clearClaimColumns(stmt: org.jetbrains.exposed.v1.core.statements.UpdateStatement) {
        stmt[WorkItemsTable.claimedBy] = null
        stmt[WorkItemsTable.claimedAt] = null
        stmt[WorkItemsTable.claimExpiresAt] = null
        stmt[WorkItemsTable.originalClaimedAt] = null
    }

    override suspend fun findForNextItem(
        role: Role,
        parentId: UUID?,
        excludeActiveClaims: Boolean,
        limit: Int,
        rootIds: Set<UUID>?
    ): List<WorkItem> {
        val now = if (excludeActiveClaims) clock.unitNow() else null
        return databaseManager.readTx {
            val spec = ItemQuerySpec(role = role, parentId = parentId)
            val conditions = ItemQueries.conditions(spec, rootIds?.let { ScopeResolver.resolve(it) }, now)
            if (conditions == null) return@readTx emptyList()
            // Claim filter: exclude items with an active claim, decided on the unit instant.
            if (now != null) conditions.add(ClaimPredicates.notActive(now))
            WorkItemsTable
                .selectAll()
                .where { conditions.reduce { acc, op -> acc and op } }
                .limit(limit)
                .map { WorkItemRows.toWorkItem(it) }
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
    ): List<WorkItem> {
        val now = clock.unitNow()
        return databaseManager.readTx {
            val spec =
                ItemQuerySpec(
                    role = role,
                    parentId = parentId,
                    tags = tags,
                    priority = priority,
                    type = type,
                    complexityMax = complexityMax,
                    createdAfter = createdAfter,
                    createdBefore = createdBefore,
                    modifiedAfter = modifiedAfter,
                    modifiedBefore = modifiedBefore,
                    roleChangedAfter = roleChangedAfter,
                    roleChangedBefore = roleChangedBefore
                )
            val conditions = ItemQueries.conditions(spec, rootIds?.let { ScopeResolver.resolve(it) }, now)
            if (conditions == null) return@readTx emptyList()
            // Active-claim exclusion is always applied: claim-eligibility by definition requires it.
            conditions.add(ClaimPredicates.notActive(now))

            val baseQuery = WorkItemsTable.selectAll().where { conditions.reduce { acc, op -> acc and op } }
            val orderedQuery =
                when (orderBy) {
                    NextItemOrder.PRIORITY_THEN_COMPLEXITY ->
                        baseQuery
                            .orderBy(ItemQueries.priorityRank(), SortOrder.ASC)
                            .orderBy(WorkItemsTable.complexity, SortOrder.ASC_NULLS_LAST)
                    NextItemOrder.OLDEST_FIRST -> baseQuery.orderBy(WorkItemsTable.createdAt, SortOrder.ASC)
                    NextItemOrder.NEWEST_FIRST -> baseQuery.orderBy(WorkItemsTable.createdAt, SortOrder.DESC)
                }

            val candidates = orderedQuery.limit(limit).map { WorkItemRows.toWorkItem(it) }

            // Ancestor-claim filter (strict-by-default sub-tree isolation): disqualify a candidate whose ancestor
            // holds an active claim by a disqualifying agent. With a requesting agent only OTHER agents' claims
            // disqualify; without one, any active ancestor claim does.
            val candidatesWithParents = candidates.filter { it.parentId != null }
            if (candidatesWithParents.isEmpty()) return@readTx candidates

            // Batched BFS upward, seeded from the candidate rows already in memory.
            val ancestorCache = mutableMapOf<UUID, WorkItem>()
            candidatesWithParents.forEach { ancestorCache[it.id] = it }
            var toFetch = candidatesWithParents.mapNotNull { it.parentId }.toSet() - ancestorCache.keys
            while (toFetch.isNotEmpty()) {
                val fetched = WorkItemRows.loadByIds(toFetch)
                fetched.forEach { ancestorCache[it.id] = it }
                toFetch = fetched.mapNotNull { it.parentId }.toSet() - ancestorCache.keys
            }

            candidates.filter { candidate ->
                var parentId = candidate.parentId
                val visited = mutableSetOf<UUID>()
                var disqualified = false
                while (parentId != null && !disqualified) {
                    if (!visited.add(parentId)) break // cycle guard
                    val ancestor = ancestorCache[parentId] ?: break
                    val claim = ClaimState.of(ancestor)
                    if (claim != null && claim.isActive(now) && (requestingAgentId == null || claim.claimedBy != requestingAgentId)) {
                        disqualified = true
                    }
                    parentId = ancestor.parentId
                }
                !disqualified
            }
        }
    }

    override suspend fun countByClaimStatus(
        parentId: UUID?,
        rootIds: Set<UUID>?
    ): ClaimStatusCounts {
        val now = clock.unitNow()
        return databaseManager.readTx {
            val scope = rootIds?.let { ScopeResolver.resolve(it) }
            if (scope == ResolvedScope.Empty) return@readTx ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0)

            fun count(status: ClaimStatus): Int {
                val conditions = mutableListOf<Op<Boolean>>()
                parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }
                scope?.let { conditions.add(it.toCondition()) }
                conditions.add(ClaimPredicates.forStatus(status, now))
                return WorkItemsTable
                    .selectAll()
                    .where { conditions.reduce { acc, op -> acc and op } }
                    .count()
                    .toInt()
            }
            ClaimStatusCounts(
                active = count(ClaimStatus.CLAIMED),
                expired = count(ClaimStatus.EXPIRED),
                unclaimed = count(ClaimStatus.UNCLAIMED)
            )
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
    ): SelectorMatchCounts {
        val now = clock.unitNow()
        return databaseManager.readTx {
            // The same condition builder as findClaimable, minus the active-claim exclusion: this counts matches
            // WITH claim status broken out, not rows eligible to claim.
            val spec =
                ItemQuerySpec(
                    role = role,
                    parentId = parentId,
                    tags = tags,
                    priority = priority,
                    type = type,
                    complexityMax = complexityMax,
                    createdAfter = createdAfter,
                    createdBefore = createdBefore,
                    modifiedAfter = modifiedAfter,
                    modifiedBefore = modifiedBefore,
                    roleChangedAfter = roleChangedAfter,
                    roleChangedBefore = roleChangedBefore
                )
            val conditions = ItemQueries.conditions(spec, rootIds?.let { ScopeResolver.resolve(it) }, now)
            if (conditions == null) return@readTx SelectorMatchCounts(matched = 0, activelyClaimed = 0)
            val combined = conditions.reduce { acc, op -> acc and op }
            val matched =
                WorkItemsTable
                    .selectAll()
                    .where { combined }
                    .count()
                    .toInt()
            val activelyClaimed =
                WorkItemsTable
                    .selectAll()
                    .where { combined and ClaimPredicates.active(now) }
                    .count()
                    .toInt()
            SelectorMatchCounts(matched = matched, activelyClaimed = activelyClaimed)
        }
    }
}
