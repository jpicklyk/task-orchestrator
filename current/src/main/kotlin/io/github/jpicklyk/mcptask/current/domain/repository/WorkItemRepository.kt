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

    /**
     * Execute [block] inside a single shared database transaction.
     *
     * All repository calls made inside [block] that use the same underlying [Database]
     * instance will participate in the same transaction: if [block] throws, all writes
     * are rolled back atomically. Callers MUST NOT call [dbNow] inside [block] — that
     * would open a nested transaction; read DB time before entering [inTransaction].
     *
     * Used by [io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler]
     * to write the item update and the audit trail row atomically in [applyTransition].
     */
    suspend fun inTransaction(block: suspend () -> Unit)

    suspend fun getById(id: UUID): Result<WorkItem>

    suspend fun create(item: WorkItem): Result<WorkItem>

    suspend fun update(item: WorkItem): Result<WorkItem>

    suspend fun delete(id: UUID): Result<Boolean>

    suspend fun findByParent(
        parentId: UUID,
        limit: Int = 50
    ): Result<List<WorkItem>>

    /**
     * @param rootIds Optional subtree scope. When null (default), unscoped — behavior is
     *   identical to omitting the parameter. When non-null, results are additionally restricted
     *   to items within the subtree(s) rooted at [rootIds] (roots included), resolved the same
     *   way as [findInScope]. An empty (non-null) set yields an empty result — no matching rows.
     */
    suspend fun findByRole(
        role: Role,
        limit: Int = 50,
        rootIds: Set<UUID>? = null
    ): Result<List<WorkItem>>

    suspend fun findByDepth(
        depth: Int,
        limit: Int = 50
    ): Result<List<WorkItem>>

    /**
     * Find all project anchor items: depth-0 roots with `type = "project"`.
     *
     * Project anchors partition a shared database into per-project subtrees (see the
     * project-root scoping convention). Returns an empty list when the workspace has no
     * project anchors — e.g. a legacy single-project database that has not been adopted.
     */
    suspend fun findProjectRoots(): Result<List<WorkItem>>

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
     * Returns an [ItemFetchResult]: rows that fail domain validation (see [WorkItem.validate])
     * are dropped rather than failing the whole query — [ItemFetchResult.skipped] reports how
     * many were dropped. Combine with [countByFilters] (which counts at the raw-SQL level,
     * unaffected by validation) to detect the divergence: `total = returned + skipped` when
     * no rows beyond `limit`/`offset` remain unfetched.
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
    ): Result<ItemFetchResult>

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
     * Find all root items (items with no parent), ordered newest-createdAt-first.
     * Unlike findProjectRoots() which returns only `type = "project"` anchors, this
     * returns all parentless items regardless of type.
     *
     * Returns an [ItemFetchResult]: rows that fail domain validation are dropped rather than
     * failing the whole query — [ItemFetchResult.skipped] reports how many were dropped. Use
     * [countRootItems] (unaffected by [limit]/[offset] or validation, with the same
     * [excludeTerminal] value) for the true root count.
     *
     * @param offset Zero-based row offset applied at the SQL level, for paging through roots
     *   beyond the first [limit] rows.
     * @param excludeTerminal When true, roots whose `role` is `terminal` are filtered out at the
     *   SQL level (applied before `limit`/`offset`), so a terminal root is never fetched and
     *   never counts against the page.
     */
    suspend fun findRootItems(
        limit: Int = 50,
        offset: Int = 0,
        excludeTerminal: Boolean = false,
    ): Result<ItemFetchResult>

    /**
     * True count of root items (items with `parentId IS NULL`), unaffected by any `limit` and
     * computed at the raw-SQL level (not subject to the domain-validation drops that
     * [findRootItems] applies). Pair with [findRootItems] (same [excludeTerminal] value) to
     * detect truncation: `truncated = offset + returned < total` where
     * `returned = findRootItems(limit, offset).items.size`.
     *
     * @param excludeTerminal When true, counts only roots whose `role` is not `terminal` —
     *   i.e. the count matches the filtered set [findRootItems] returns with the same flag.
     */
    suspend fun countRootItems(excludeTerminal: Boolean = false): Result<Long>

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
     * @param rootIds Optional subtree scope. When null (default), unscoped — behavior is
     *   identical to the pre-scoping contract. When non-null, results are additionally
     *   restricted to items within the subtree(s) rooted at [rootIds] (roots included),
     *   resolved the same way as [findInScope]. An empty (non-null) set yields an empty result.
     */
    suspend fun findForNextItem(
        role: Role,
        parentId: UUID? = null,
        excludeActiveClaims: Boolean = true,
        limit: Int = 200,
        rootIds: Set<UUID>? = null
    ): Result<List<WorkItem>>

    /**
     * Find work items that are eligible to be claimed, combining the filter flexibility of
     * [findByFilters] with the active-claim exclusion logic of [findForNextItem].
     *
     * Active-claim exclusion (`claimed_by IS NULL OR claim_expires_at <= now`) is **always** applied —
     * it is not a parameter because claim-eligibility by definition means the item is unclaimed or
     * its claim has expired. All filters are combined with AND logic; tags use OR logic within the list.
     *
     * **Ancestor-claim filtering (strict-by-default sub-tree isolation):**
     *
     * After the initial candidate query, each candidate's ancestor chain is walked to enforce
     * fleet isolation:
     *
     * - When `requestingAgentId` is **non-null**: any candidate whose ancestor chain contains
     *   a live claim (`claimed_by IS NOT NULL AND claim_expires_at > now`) held by a *different*
     *   agent is excluded. Candidates whose ancestor is claimed by the *same* agent are retained
     *   (supporting the hybrid pattern: claim at parent feature, orchestrate sub-tree below).
     * - When `requestingAgentId` is **null** (default): any candidate whose ancestor chain
     *   contains a live claim by *any* agent is excluded. This is the strict exclusion mode used
     *   by read-only callers such as `get_next_item` that do not have actor context.
     *
     * Ancestor-claim freshness is evaluated using [dbNow] — the DB-side clock — consistent with
     * the existing item-level claim exclusion contract. The walk is a batched BFS over the full
     * ancestor chain (unbounded depth since V7), so the total extra query cost scales with depth.
     *
     * Items with no parent (root items, depth=0) are unaffected by this filter.
     *
     * **Filter ordering vs. limit:** [limit] is applied to the candidate query *before* the
     * ancestor-claim filter runs. The returned list size is therefore at most [limit], and may
     * be smaller — even when more matching items exist in the DB — if some candidates are
     * excluded by the ancestor walk. Callers that need a guaranteed minimum result size should
     * over-fetch (e.g. `NextItemRecommender` uses `OVER_FETCH_LIMIT` for this reason).
     *
     * @param role              The role to query (required — e.g. QUEUE, WORK, REVIEW).
     * @param parentId          Optional parent UUID to scope results to direct children only.
     * @param tags              Optional list of tags; items matching ANY tag are included.
     * @param priority          Optional priority filter.
     * @param type              Optional type string filter (exact match).
     * @param complexityMax     Optional upper bound (inclusive) on item complexity.
     * @param createdAfter      Optional lower bound (inclusive) on [WorkItem.createdAt].
     * @param createdBefore     Optional upper bound (inclusive) on [WorkItem.createdAt].
     * @param modifiedAfter     Optional lower bound (inclusive) on [WorkItem.modifiedAt].
     * @param modifiedBefore    Optional upper bound (inclusive) on [WorkItem.modifiedAt].
     * @param roleChangedAfter  Optional lower bound (inclusive) on [WorkItem.roleChangedAt].
     * @param roleChangedBefore Optional upper bound (inclusive) on [WorkItem.roleChangedAt].
     * @param orderBy           Ordering strategy for the result set (default: priority then complexity).
     * @param limit             Maximum number of rows to return (default: 200).
     * @param requestingAgentId Optional agent identifier for sub-tree isolation. When non-null,
     *                          only ancestor claims held by a *different* agent disqualify a
     *                          candidate. When null, any live ancestor claim disqualifies the
     *                          candidate (strict exclusion for callers without actor context).
     * @param rootIds Optional subtree scope. When null (default), unscoped — behavior is
     *   identical to the pre-scoping contract. When non-null, candidates are additionally
     *   restricted to items within the subtree(s) rooted at [rootIds] (roots included),
     *   resolved the same way as [findInScope]. An empty (non-null) set yields an empty result.
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
        modifiedAfter: Instant? = null,
        modifiedBefore: Instant? = null,
        roleChangedAfter: Instant? = null,
        roleChangedBefore: Instant? = null,
        orderBy: NextItemOrder = NextItemOrder.PRIORITY_THEN_COMPLEXITY,
        limit: Int = 200,
        requestingAgentId: String? = null,
        rootIds: Set<UUID>? = null,
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
     * @param rootIds Optional subtree scope. When null (default), unscoped — behavior is
     *   identical to the pre-scoping contract. When non-null, counts are additionally restricted
     *   to items within the subtree(s) rooted at [rootIds] (roots included), resolved the same
     *   way as [findInScope]. [rootIds] and [parentId] compose with AND. An empty (non-null)
     *   [rootIds] set yields all-zero counts.
     */
    suspend fun countByClaimStatus(parentId: UUID? = null, rootIds: Set<UUID>? = null): Result<ClaimStatusCounts>

    /**
     * Find work items that are within the subtree rooted at any of [rootIds] (roots included).
     *
     * When [rootIds] is empty, returns an empty list immediately (no implicit fallback to
     * [findByFilters] — an empty scope set is an unambiguous empty result).
     *
     * On SQLite the subtree is computed via a single recursive CTE. On H2 (test environment)
     * a BFS loop is used instead (H2's recursive CTE syntax differs from SQLite's).
     *
     * All filter parameters mirror [findByFilters] exactly and are AND-combined on top of the
     * scope constraint.
     *
     * @param rootIds Set of item UUIDs whose full subtrees (inclusive) are included in scope.
     *   Must be non-empty; pass `emptySet()` only when you want an empty result.
     */
    suspend fun findInScope(
        rootIds: Set<UUID>,
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
        claimStatus: String? = null,
    ): Result<List<WorkItem>>

    /**
     * Count work items within the subtree rooted at any of [rootIds] (roots included).
     *
     * Mirrors [findInScope] exactly (same scope semantics and filter parameters) but returns
     * the total row count without pagination.
     *
     * When [rootIds] is empty, returns 0 immediately.
     */
    suspend fun countInScope(
        rootIds: Set<UUID>,
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
        claimStatus: String? = null,
    ): Result<Int>
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

/**
 * Result of a bulk row-fetch whose mapping step may drop rows that fail domain validation
 * (e.g. [WorkItem.validate] rejecting a corrupt/legacy row).
 *
 * @property items   Successfully mapped and validated work items.
 * @property skipped Count of rows present in the underlying query that were dropped because
 *   they failed domain validation. A drop is logged at WARN with the row's id and the
 *   validation message so operators can locate and repair the corrupt row.
 */
data class ItemFetchResult(
    val items: List<WorkItem>,
    val skipped: Int
)
