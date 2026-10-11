package io.github.jpicklyk.mcptask.current.application.port

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
 *
 * A database failure is thrown, never returned.
 */
sealed class ClaimResult {
    data class Success(
        val item: WorkItem,
        /**
         * Ids of items the claiming agent held that were auto-released as part of this claim
         * (step 2 of the atomic claim SQL — every OTHER item the agent held is released when
         * a claim succeeds). Empty when the agent held no other claims. Populated by
         * [io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository.claim]
         * from a pre-release SELECT run inside the same transaction/step as the release UPDATE, so
         * event-publishing callers can emit `item.updated` for each evicted item. Defaulted so the
         * many existing call sites that construct [Success] without this field keep compiling.
         */
        val releasedItemIds: List<UUID> = emptyList()
    ) : ClaimResult()

    data class AlreadyClaimed(
        val itemId: UUID,
        /** When the existing, active claim expires. An active claim always has an expiry. */
        val expiresAt: Instant,
        /** Milliseconds until the existing claim expires (at least 1): a backoff hint. */
        val retryAfterMs: Long
    ) : ClaimResult()

    data class NotFound(
        val itemId: UUID
    ) : ClaimResult()

    data class TerminalItem(
        val itemId: UUID
    ) : ClaimResult()
}

/**
 * Sealed result type for [WorkItemRepository.release] operations.
 *
 * - [Success] — the claim was cleared; [item] reflects DB state after release.
 * - [NotClaimedByYou] — the item is claimed by a different agent (or is unclaimed).
 * - [NotFound] — no work item with the given [id] exists (row absent).
 *
 * A database failure is thrown, never returned.
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
}

/**
 * Claim state on work items: acquiring, refreshing, releasing and clearing a claim, and every
 * claim-aware query. The ONLY writer of the claim columns (`claimed_by`, `claimed_at`,
 * `claim_expires_at`, `original_claimed_at`), and the only place claim freshness is decided in SQL.
 *
 * "Now" is always the ambient unit's instant (see [unitNow]); nothing reads the database clock. A
 * claim is active while its expiry is strictly after "now": an expiry equal to "now" is expired.
 * Claim writes do not bump the item `version`.
 */
interface ClaimStore {
    /**
     * Atomically claim a work item for the given agent, or refresh an existing claim.
     *
     * Implements the two-step canonical SQL pattern:
     * 1. Release all prior claims by [agentId] **except** the item being claimed.
     * 2. Claim (or refresh) [itemId], but only if it is unclaimed, expired, or already held by [agentId],
     *    and the item's role is not TERMINAL.
     *
     * Both steps execute inside a single SERIALIZABLE transaction. The Kotlin layer does NOT compute
     * any timestamps — the bound clock instant is the sole time source.
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
     * Clears all four claim columns of [itemId] unconditionally (no ownership check) and returns
     * whether the item exists. Used where a claim must not outlive the item's state, e.g. when an
     * item enters or leaves a terminal role. Does not bump `version`.
     */
    suspend fun clear(itemId: UUID): Boolean

    /**
     * Every item whose claim is held by [agentId], active or lapsed, in no particular order. Read inside the claim
     * unit by `ClaimService` to tell which of the agent's other claims a new claim will auto-release were already lapsed.
     */
    suspend fun findHeldBy(agentId: String): List<WorkItem>

    /**
     * Every item whose claim has lapsed at the bound instant: `claimed_by` set and `claim_expires_at <= now`. A claim
     * with no usable expiry is not returned (there is no instant to report). The columns are not touched.
     */
    suspend fun findLapsedClaims(): List<WorkItem>

    /**
     * Find work items for the "get next" recommendation query, supporting optional claim filtering.
     *
     * Returns items in the specified [role] that are not in TERMINAL. When [excludeActiveClaims]
     * is true, items with a live (non-expired) claim are omitted from the result — i.e., only items
     * where the claim is not active at the bound instant (`claimed_by IS NULL`, no expiry, or `claim_expires_at <= now`) are returned.
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
     *   resolved the same way as [ItemStore.find]'s `AccessScope.rootIds`. An empty (non-null) set yields an empty result.
     */
    suspend fun findForNextItem(
        role: Role,
        parentId: UUID? = null,
        excludeActiveClaims: Boolean = true,
        limit: Int = 200,
        rootIds: Set<UUID>? = null
    ): List<WorkItem>

    /**
     * Find work items that are eligible to be claimed, combining the filter flexibility of
     * [ItemStore.find] with the active-claim exclusion logic of [findForNextItem].
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
     * Ancestor-claim freshness is evaluated at the bound unit instant (unitNow), consistent with
     * the existing item-level claim exclusion contract. The walk is a batched BFS over the full
     * ancestor chain (unbounded depth since V7), so the total extra query cost scales with depth.
     *
     * Items with no parent (root items, depth=0) are unaffected by this filter.
     *
     * **Filter ordering vs. limit:** [limit] is applied to the candidate query *before* the
     * ancestor-claim filter runs. The returned list size is therefore at most [limit], and may
     * be smaller — even when more matching items exist in the DB — if some candidates are
     * excluded by the ancestor walk, and dependency blocking is not applied at all. Callers that
     * need the exact eligible set use [findEligible], which applies every predicate in SQL before
     * the limit (`NextItemRecommender` moved onto it; this method is retained for its existing
     * callers until the 3.x read paths are removed).
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
     *   resolved the same way as [ItemStore.find]'s `AccessScope.rootIds`. An empty (non-null) set yields an empty result.
     * @return the list of matching, claimable items (may be empty); a database failure is thrown.
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
    ): List<WorkItem>

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
     *   way as [ItemStore.find]'s `AccessScope.rootIds`. [rootIds] and [parentId] compose with AND. An empty (non-null)
     *   [rootIds] set yields all-zero counts.
     */
    suspend fun countByClaimStatus(
        parentId: UUID? = null,
        rootIds: Set<UUID>? = null
    ): ClaimStatusCounts

    /**
     * Count how many items match the selector filters used by [findClaimable] — same filter set
     * (minus [findClaimable]'s `orderBy`, `limit` and `requestingAgentId`, which affect ranking
     * and ancestor-claim isolation rather than which rows match) — without the active-claim
     * exclusion [findClaimable] always applies: an empty queue (`matched == 0`) versus a queue that
     * has matches but none are currently claimable. `NextItemRecommender.explainEmpty` now uses
     * [explainIneligible], which also counts the dependency-blocked rows exactly; this method is
     * retained for its existing callers until the 3.x read paths are removed.
     *
     * @return [SelectorMatchCounts.matched] — total rows matching the filters, claimed or not.
     *   [SelectorMatchCounts.activelyClaimed] — subset of [SelectorMatchCounts.matched] with a
     *   live claim (`claimed_by IS NOT NULL AND claim_expires_at > now`), by any holder including
     *   the caller.
     */
    suspend fun countSelectorMatches(
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
        rootIds: Set<UUID>? = null,
    ): SelectorMatchCounts

    /**
     * One page of the items eligible under [query], plus the exact eligible count, both decided in
     * SQL in one transaction (no over-fetch window: an eligible item ranked behind any number of
     * ineligible ones is found). An item is eligible when it:
     * - matches every selector filter of [query] (the same filters as [findClaimable]);
     * - is not dependency-blocked: every BLOCKS edge into it has a blocker whose role is at or
     *   beyond the edge threshold (`unblock_at`, TERMINAL when null), and a BLOCKED or missing
     *   blocker never satisfies; RELATES_TO edges are ignored;
     * - unless [EligibilityQuery.includeClaimed]: has no active claim at the unit instant (an expiry
     *   equal to "now" is expired), and no ancestor at any depth holds an active claim by a
     *   disqualifying agent (any agent when [EligibilityQuery.requestingAgentId] is null, otherwise
     *   only other agents).
     *
     * Ordered by [EligibilityQuery.orderBy] (priority rank then complexity ascending with nulls
     * last; or `createdAt` ascending; or `createdAt` descending), then `id` ascending, so offset
     * pages are disjoint and stable. An empty (non-null) [EligibilityQuery.rootIds] selects nothing.
     */
    suspend fun findEligible(
        query: EligibilityQuery,
        limit: Int,
        offset: Int = 0,
    ): ItemSlice

    /**
     * Why items matching [query]'s selector filters are or are not eligible, in ONE statement over
     * the matched rows (claim state ignored when counting [EligibilityBreakdown.matched]):
     * [EligibilityBreakdown.activelyClaimed] counts rows with an active claim (any holder, the
     * caller included); [EligibilityBreakdown.dependencyBlocked] counts rows with no active claim,
     * not shielded by an ancestor claim, and dependency-blocked. [EligibilityQuery.includeClaimed]
     * is ignored here.
     */
    suspend fun explainIneligible(query: EligibilityQuery): EligibilityBreakdown

    /**
     * Claim-status counts over the items within the subtree(s) of [rootIds] (roots included; all
     * items when null; all zeros for an empty set), in ONE `SUM(CASE ...)` statement built from
     * the claim predicates.
     */
    suspend fun claimSummary(rootIds: Set<UUID>? = null): ClaimStatusCounts

    /**
     * Claim-status counts over the DIRECT children of each parent in [parentIds], in ONE grouped
     * `SUM(CASE ...)` statement for any number of parents. Every requested id is a key (all zeros when
     * it has no children). An empty [parentIds] returns an empty map without touching the database.
     */
    suspend fun claimSummaryByParent(parentIds: Set<UUID>): Map<UUID, ClaimStatusCounts>
}

/**
 * The selector of [ClaimStore.findEligible] and [ClaimStore.explainIneligible]: the filters of
 * [ClaimStore.findClaimable] plus [includeClaimed]. Timestamp bounds are inclusive; [tags] match
 * ANY listed tag as a whole comma-separated token, exactly as [ClaimStore.findClaimable] matches
 * them (the tag is trimmed and lower-cased); [complexityMax] excludes
 * items without a complexity; [rootIds] restricts to those subtrees (roots included) and composes
 * with [parentId] (direct children) by AND.
 *
 * @property includeClaimed When true, the claim predicates are off: claimed items stay eligible
 *   and ancestor claims shield nothing. Dependency blocking still applies.
 */
data class EligibilityQuery(
    val role: Role = Role.QUEUE,
    val parentId: UUID? = null,
    val tags: List<String>? = null,
    val priority: Priority? = null,
    val type: String? = null,
    val complexityMax: Int? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val modifiedAfter: Instant? = null,
    val modifiedBefore: Instant? = null,
    val roleChangedAfter: Instant? = null,
    val roleChangedBefore: Instant? = null,
    val orderBy: NextItemOrder = NextItemOrder.PRIORITY_THEN_COMPLEXITY,
    val requestingAgentId: String? = null,
    val rootIds: Set<UUID>? = null,
    val includeClaimed: Boolean = false,
)

/**
 * Result of [ClaimStore.explainIneligible].
 *
 * @property matched Rows matching the selector filters, claimed or not.
 * @property activelyClaimed Subset of [matched] with an active claim (any holder).
 * @property dependencyBlocked Subset of [matched] with no active claim, no shielding ancestor
 *   claim, and at least one unsatisfied blocking dependency.
 */
data class EligibilityBreakdown(
    val matched: Int,
    val activelyClaimed: Int,
    val dependencyBlocked: Int,
)

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
 * Result of [WorkItemRepository.countSelectorMatches].
 *
 * @property matched Total rows matching the selector filters, claimed or not.
 * @property activelyClaimed Subset of [matched] with a live claim (any holder, incl. the caller).
 */
data class SelectorMatchCounts(
    val matched: Int,
    val activelyClaimed: Int
)
