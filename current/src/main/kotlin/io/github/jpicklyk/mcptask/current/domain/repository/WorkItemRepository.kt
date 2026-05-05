package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Instant
import java.util.UUID

/**
 * Sealed result type for [WorkItemRepository.claim] operations.
 *
 * - [Success] — the claim was atomically placed (or refreshed for same agent). [item] reflects DB state after the claim.
 * - [AlreadyClaimed] — another agent holds a live (non-expired) claim. [retryAfterMs] is a hint for backoff.
 * - [NotFound] — no work item with the given [id] exists (row absent).
 * - [TerminalItem] — the item's role is TERMINAL; claiming terminal items is not supported.
 * - [DBError] — an unexpected database exception occurred; the operation did not complete.
 */
sealed class ClaimResult {
    data class Success(
        val item: WorkItem
    ) : ClaimResult()

    data class AlreadyClaimed(
        val itemId: UUID,
        /** Milliseconds until the existing claim expires; null if expiry could not be determined. */
        val retryAfterMs: Long?
    ) : ClaimResult()

    data class NotFound(
        val itemId: UUID
    ) : ClaimResult()

    data class TerminalItem(
        val itemId: UUID
    ) : ClaimResult()

    data class DBError(
        val itemId: UUID,
        val cause: Exception
    ) : ClaimResult()
}

/**
 * Sealed result type for [WorkItemRepository.release] operations.
 *
 * - [Success] — the claim was cleared; [item] reflects DB state after release.
 * - [NotClaimedByYou] — the item is claimed by a different agent (or is unclaimed).
 * - [NotFound] — no work item with the given [id] exists (row absent).
 * - [DBError] — an unexpected database exception occurred; the operation did not complete.
 */
sealed class ReleaseResult {
    data class Success(
        val item: WorkItem
    ) : ReleaseResult()

    data class NotClaimedByYou(
        val itemId: UUID
    ) : ReleaseResult()

    data class NotFound(
        val itemId: UUID
    ) : ReleaseResult()

    data class DBError(
        val itemId: UUID,
        val cause: Exception
    ) : ReleaseResult()
}

interface WorkItemRepository {
    /**
     * Return the database server's current wall-clock time as an [Instant].
     *
     * All claim-freshness comparisons (ownership checks, visibility filters) MUST use this
     * value instead of [java.time.Instant.now] so that decisions are made against the DB
     * clock — eliminating skew when the JVM clock and the SQLite clock diverge.
     *
     * Implemented as a lightweight `SELECT datetime('now')` (SQLite) or equivalent.
     */
    suspend fun dbNow(): Instant

    suspend fun getById(id: UUID): Result<WorkItem>

    suspend fun create(item: WorkItem): Result<WorkItem>

    suspend fun update(item: WorkItem): Result<WorkItem>

    suspend fun delete(id: UUID): Result<Boolean>

    suspend fun findByParent(
        parentId: UUID,
        limit: Int = 50
    ): Result<List<WorkItem>>

    suspend fun findByRole(
        role: Role,
        limit: Int = 50
    ): Result<List<WorkItem>>

    suspend fun findByDepth(
        depth: Int,
        limit: Int = 50
    ): Result<List<WorkItem>>

    suspend fun findRoot(): Result<WorkItem?>

    suspend fun search(
        query: String,
        limit: Int = 20
    ): Result<List<WorkItem>>

    suspend fun count(): Result<Long>

    suspend fun findChildren(parentId: UUID): Result<List<WorkItem>>

    /**
     * Find work items matching multiple filter criteria.
     * All non-null filters are combined with AND logic. Tags use OR logic within the list.
     *
     * @param claimStatus Optional claim-status filter: "claimed", "unclaimed", or "expired".
     *   - "claimed"   — items where `claimed_by IS NOT NULL AND claim_expires_at > now`
     *   - "unclaimed" — items where `claimed_by IS NULL`
     *   - "expired"   — items where `claimed_by IS NOT NULL AND claim_expires_at <= now`
     */
    suspend fun findByFilters(
        parentId: UUID? = null,
        depth: Int? = null,
        role: Role? = null,
        priority: Priority? = null,
        tags: List<String>? = null,
        query: String? = null,
        createdAfter: Instant? = null,
        createdBefore: Instant? = null,
        modifiedAfter: Instant? = null,
        modifiedBefore: Instant? = null,
        roleChangedAfter: Instant? = null,
        roleChangedBefore: Instant? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        type: String? = null,
        claimStatus: String? = null
    ): Result<List<WorkItem>>

    /**
     * Count work items matching multiple filter criteria (same filters as findByFilters, no pagination).
     * Returns the total number of matching rows regardless of any limit/offset.
     *
     * @param claimStatus Optional claim-status filter: "claimed", "unclaimed", or "expired".
     */
    suspend fun countByFilters(
        parentId: UUID? = null,
        depth: Int? = null,
        role: Role? = null,
        priority: Priority? = null,
        tags: List<String>? = null,
        query: String? = null,
        createdAfter: Instant? = null,
        createdBefore: Instant? = null,
        modifiedAfter: Instant? = null,
        modifiedBefore: Instant? = null,
        roleChangedAfter: Instant? = null,
        roleChangedBefore: Instant? = null,
        type: String? = null,
        claimStatus: String? = null
    ): Result<Int>

    /**
     * Count direct children of a work item grouped by their current role.
     * Returns a map of Role to count. Roles with zero children are omitted.
     */
    suspend fun countChildrenByRole(parentId: UUID): Result<Map<Role, Int>>

    /**
     * Find all root items (items with no parent).
     * Unlike findRoot() which expects a single root, this returns all parentless items.
     */
    suspend fun findRootItems(limit: Int = 50): Result<List<WorkItem>>

    /**
     * Find all descendants of the given item (children, grandchildren, etc.) recursively.
     * Does not include the item itself.
     */
    suspend fun findDescendants(id: UUID): Result<List<WorkItem>>

    /**
     * Fetch multiple items by ID in one query. Missing IDs are silently omitted.
     */
    suspend fun findByIds(ids: Set<UUID>): Result<List<WorkItem>>

    /**
     * Delete multiple items by ID in one query. Returns the number of rows deleted.
     */
    suspend fun deleteAll(ids: Set<UUID>): Result<Int>

    /**
     * Atomically claim a work item for the given agent, or refresh an existing claim.
     *
     * Implements the two-step canonical SQL pattern:
     * 1. Release all prior claims by [agentId] **except** the item being claimed.
     * 2. Claim (or refresh) [itemId], but only if it is unclaimed, expired, or already held by [agentId],
     *    and the item's role is not TERMINAL.
     *
     * Both steps execute inside a single SERIALIZABLE transaction. The Kotlin layer does NOT compute
     * any timestamps — `datetime('now')` is the sole time source.
     *
     * @param itemId    UUID of the item to claim.
     * @param agentId   Opaque agent identifier to record as the claim holder.
     * @param ttlSeconds Number of seconds until the claim expires (default 900).
     * @return [ClaimResult.Success] with the updated item, or a failure variant.
     */
    suspend fun claim(
        itemId: UUID,
        agentId: String,
        ttlSeconds: Int = 900
    ): ClaimResult

    /**
     * Release a claim held by [agentId] on [itemId].
     *
     * Clears all four claim fields (`claimed_by`, `claimed_at`, `claim_expires_at`,
     * `original_claimed_at`) atomically. Only succeeds when the current `claimed_by`
     * matches [agentId]; returns [ReleaseResult.NotClaimedByYou] otherwise.
     *
     * @param itemId  UUID of the item to release.
     * @param agentId The agent releasing the claim (must be the current holder).
     * @return [ReleaseResult.Success] with the updated item, or a failure variant.
     */
    suspend fun release(
        itemId: UUID,
        agentId: String
    ): ReleaseResult

    /**
     * Find work items whose ID starts with the given hex prefix.
     * Used for short UUID prefix resolution.
     */
    suspend fun findByIdPrefix(
        prefix: String,
        limit: Int = 10
    ): Result<List<WorkItem>>

    /**
     * For each itemId, resolve its full ancestor chain (root -> direct parent).
     * Returns Map<itemId, List<WorkItem>> ordered root-first, ancestors only (item itself excluded).
     * Items with no parent (depth=0 root items) map to an empty list.
     */
    suspend fun findAncestorChains(itemIds: Set<UUID>): Result<Map<UUID, List<WorkItem>>>

    /**
     * Find work items for the "get next" recommendation query, supporting optional claim filtering.
     *
     * Returns items in the specified [role] that are not in TERMINAL. When [excludeActiveClaims]
     * is true, items with a live (non-expired) claim are omitted from the result — i.e., only items
     * where `claimed_by IS NULL OR claim_expires_at <= datetime('now')` are returned.
     *
     * The claim filter is applied at the DB level to avoid loading and discarding claimed rows.
     *
     * @param role            The role to query (e.g. QUEUE, WORK, REVIEW, BLOCKED).
     * @param parentId        Optional parent UUID to scope results to direct children only.
     * @param excludeActiveClaims When true, omit items with a live (non-expired) claim.
     * @param limit           Maximum number of rows to return.
     */
    suspend fun findForNextItem(
        role: Role,
        parentId: UUID? = null,
        excludeActiveClaims: Boolean = true,
        limit: Int = 200
    ): Result<List<WorkItem>>

    /**
     * Find work items that are eligible to be claimed, combining the filter flexibility of
     * [findByFilters] with the active-claim exclusion logic of [findForNextItem].
     *
     * Active-claim exclusion (`claimed_by IS NULL OR claim_expires_at <= now`) is **always** applied —
     * it is not a parameter because claim-eligibility by definition means the item is unclaimed or
     * its claim has expired. All filters are combined with AND logic; tags use OR logic within the list.
     *
     * @param role              The role to query (required — e.g. QUEUE, WORK, REVIEW).
     * @param parentId          Optional parent UUID to scope results to direct children only.
     * @param tags              Optional list of tags; items matching ANY tag are included.
     * @param priority          Optional priority filter.
     * @param type              Optional type string filter (exact match).
     * @param complexityMax     Optional upper bound (inclusive) on item complexity.
     * @param createdAfter      Optional lower bound (inclusive) on [WorkItem.createdAt].
     * @param createdBefore     Optional upper bound (inclusive) on [WorkItem.createdAt].
     * @param roleChangedAfter  Optional lower bound (inclusive) on [WorkItem.roleChangedAt].
     * @param roleChangedBefore Optional upper bound (inclusive) on [WorkItem.roleChangedAt].
     * @param orderBy           Ordering strategy for the result set (default: priority then complexity).
     * @param limit             Maximum number of rows to return (default: 200).
     * @return [Result.Success] with the list of matching, claimable items (may be empty), or
     *         [Result.Error] on a database failure.
     */
    suspend fun findClaimable(
        role: Role,
        parentId: UUID? = null,
        tags: List<String>? = null,
        priority: Priority? = null,
        type: String? = null,
        complexityMax: Int? = null,
        createdAfter: Instant? = null,
        createdBefore: Instant? = null,
        roleChangedAfter: Instant? = null,
        roleChangedBefore: Instant? = null,
        orderBy: NextItemOrder = NextItemOrder.PRIORITY_THEN_COMPLEXITY,
        limit: Int = 200,
    ): Result<List<WorkItem>>

    /**
     * Count work items by claim status within an optional parent scope.
     *
     * Returns three counts:
     * - [ClaimStatusCounts.active]   — items where `claimed_by IS NOT NULL AND claim_expires_at > now`
     * - [ClaimStatusCounts.expired]  — items where `claimed_by IS NOT NULL AND claim_expires_at <= now`
     * - [ClaimStatusCounts.unclaimed] — items where `claimed_by IS NULL`
     *
     * When [parentId] is provided, counts are scoped to direct children of that item. When null,
     * counts are global across the entire work-item tree.
     *
     * Counts are computed at the DB level (SQL aggregation) — not load-all-rows-then-count.
     *
     * @param parentId Optional parent UUID to scope the aggregation.
     */
    suspend fun countByClaimStatus(parentId: UUID? = null): Result<ClaimStatusCounts>
}

/**
 * Three-way claim-status count returned by [WorkItemRepository.countByClaimStatus].
 *
 * @property active    Items with a live (non-expired) claim.
 * @property expired   Items that were claimed but the TTL has passed.
 * @property unclaimed Items that have never been claimed (or whose claim was cleared).
 */
data class ClaimStatusCounts(
    val active: Int,
    val expired: Int,
    val unclaimed: Int
)
