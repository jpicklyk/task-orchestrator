package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Instant
import java.util.UUID

/**
 * Item CRUD and queries: the part of the work-item store that reads and writes item rows by id,
 * parent, filter or scope. Claim state, hierarchy walks and full-text search live in
 * [ClaimStore], [HierarchyStore] and [SearchIndex].
 *
 * Every row is returned: a stored row that fails domain validation is NOT dropped, it comes back
 * with [WorkItem.diagnostics] listing its violations (the mapper is total). Writes validate.
 * [update] never writes the claim columns; those belong to [ClaimStore].
 */
interface ItemStore {
    /**
     * A cheap round trip on the reader pool that proves the database is reachable; throws when it
     * is not. Backs the service health probe.
     */
    suspend fun ping()

    suspend fun getById(id: UUID): WorkItem?

    suspend fun create(item: WorkItem): WorkItem

    suspend fun update(item: WorkItem): WorkItem?

    suspend fun delete(id: UUID): Boolean

    suspend fun findByParent(
        parentId: UUID,
        limit: Int = 50
    ): List<WorkItem>

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
    ): List<WorkItem>

    suspend fun findByDepth(
        depth: Int,
        limit: Int = 50
    ): List<WorkItem>

    /**
     * Find all project anchor items: depth-0 roots with `type = "project"`.
     *
     * Project anchors partition a shared database into per-project subtrees (see the
     * project-root scoping convention). Returns an empty list when the workspace has no
     * project anchors — e.g. a legacy single-project database that has not been adopted.
     */
    suspend fun findProjectRoots(): List<WorkItem>

    suspend fun search(
        query: String,
        limit: Int = 20
    ): List<WorkItem>

    suspend fun count(): Long

    suspend fun findChildren(parentId: UUID): List<WorkItem>

    /**
     * Find work items matching multiple filter criteria.
     * All non-null filters are combined with AND logic. Tags use OR logic within the list.
     *
     * Returns an [ItemFetchResult]. Every matching row is returned: a row that fails domain
     * validation carries its violations in [WorkItem.diagnostics] instead of being dropped, so
     * [ItemFetchResult.skipped] is always 0 (kept only for wire compatibility).
     *
     * @param claimStatus Optional [ClaimStatus] filter, evaluated at the bound unit instant:
     *   [ClaimStatus.CLAIMED] — an active claim (expiry strictly after now); [ClaimStatus.UNCLAIMED] —
     *   no `claimed_by`; [ClaimStatus.EXPIRED] — a `claimed_by` whose claim is not active.
     * @param sortBy One of [ItemSortFields.FIELDS] (`title`, `priority`, `complexity`,
     *   `createdAt`, `modifiedAt`), case-insensitive, plus the legacy `created`/`modified`
     *   aliases; see [ItemSortFields.canonicalField]. `priority` sorts by rank
     *   (high/medium/low), not the raw column. `complexity` sorts NULLs last regardless of
     *   direction. Null or unresolved falls back to `createdAt`. Callers should validate
     *   against [ItemSortFields] before calling so an invalid value surfaces as a client
     *   error rather than silently falling back.
     * @param sortOrder One of [ItemSortFields.ORDERS] (`asc`, `desc`); null or unresolved
     *   defaults to `desc`. Every sort applies a secondary `id ASC` tiebreak for stable
     *   pagination.
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
        claimStatus: ClaimStatus? = null
    ): ItemFetchResult

    /**
     * Count work items matching multiple filter criteria (same filters as findByFilters, no pagination).
     * Returns the total number of matching rows regardless of any limit/offset.
     *
     * @param claimStatus Optional [ClaimStatus] filter (see [findByFilters]).
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
        claimStatus: ClaimStatus? = null
    ): Int

    /**
     * Count direct children of a work item grouped by their current role.
     * Returns a map of Role to count. Roles with zero children are omitted.
     */
    suspend fun countChildrenByRole(parentId: UUID): Map<Role, Int>

    /**
     * Find all root items (items with no parent), ordered newest-createdAt-first.
     * Unlike findProjectRoots() which returns only `type = "project"` anchors, this
     * returns all parentless items regardless of type.
     *
     * Returns an [ItemFetchResult]; invalid rows are returned with [WorkItem.diagnostics] set
     * ([ItemFetchResult.skipped] is always 0). Use [countRootItems] (unaffected by
     * [limit]/[offset], with the same [excludeTerminal] value) for the true root count.
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
    ): ItemFetchResult

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
    suspend fun countRootItems(excludeTerminal: Boolean = false): Long

    /**
     * Fetch multiple items by ID in one query. Missing IDs are silently omitted.
     */
    suspend fun findByIds(ids: Set<UUID>): List<WorkItem>

    /**
     * Delete multiple items by ID in one query. Returns the number of rows deleted.
     */
    suspend fun deleteAll(ids: Set<UUID>): Int

    /**
     * Find work items whose ID starts with the given hex prefix.
     * Used for short UUID prefix resolution.
     */
    suspend fun findByIdPrefix(
        prefix: String,
        limit: Int = 10
    ): List<WorkItem>

    /**
     * Find work items that are within the subtree rooted at any of [rootIds] (roots included).
     *
     * When [rootIds] is empty, returns an empty list immediately (no implicit fallback to
     * [findByFilters] — an empty scope set is an unambiguous empty result).
     *
     * The subtree is computed via a single recursive CTE.
     *
     * All filter parameters mirror [findByFilters] exactly and are AND-combined on top of the
     * scope constraint.
     *
     * @param rootIds Set of item UUIDs whose full subtrees (inclusive) are included in scope.
     *   Must be non-empty; pass `emptySet()` only when you want an empty result.
     * @param sortBy Same vocabulary and semantics as [findByFilters]'s `sortBy`.
     * @param sortOrder Same vocabulary and semantics as [findByFilters]'s `sortOrder`.
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
        claimStatus: ClaimStatus? = null,
    ): List<WorkItem>

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
        claimStatus: ClaimStatus? = null,
    ): Int

    /**
     * Count work items within the subtree rooted at any of [rootIds] (roots included), grouped by
     * role. Mirrors [countChildrenByRole]'s per-role breakdown, but scoped to the full subtree
     * (any depth) instead of direct children only. Roles with zero matches are omitted.
     *
     * When [rootIds] is empty, returns an empty map immediately.
     */
    suspend fun countInScopeByRole(rootIds: Set<UUID>): Map<Role, Int>
}

/**
 * Result of a bulk row-fetch.
 *
 * @property items   Every fetched work item, including rows whose [WorkItem.diagnostics] lists violations.
 * @property skipped Always 0: the row mapper is total and never drops a row. The field remains only
 *   so the REST/MCP response shape stays stable; it is removed with the response-contract rework.
 */
data class ItemFetchResult(
    val items: List<WorkItem>,
    val skipped: Int
)
