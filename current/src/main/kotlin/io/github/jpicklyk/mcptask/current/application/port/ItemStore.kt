package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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
     *   way as [find]'s [AccessScope.rootIds]. An empty (non-null) set yields an empty result.
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
     * One page of the items [filter] selects that [scope] lets the caller see, in [sort] order.
     *
     * [scope] is applied in the query, before [page]: [AccessScope.rootIds] restricts to those
     * subtrees (roots included; an empty set selects nothing) and is AND-ed with
     * [ItemFilter.ancestorId]; [AccessScope.tagsInclude] keeps an item only when one of its
     * trimmed, non-empty tags equals a listed tag exactly (case-sensitive). Every sort ends with
     * an `id` ascending tiebreak, so offset pages are disjoint.
     */
    suspend fun find(
        filter: ItemFilter,
        scope: AccessScope = AccessScope.unrestricted(),
        page: ItemPage,
        sort: ItemSort = ItemSort.DEFAULT,
    ): List<WorkItem>

    /** The number of items [find] selects for [filter] and [scope] across all pages. */
    suspend fun count(
        filter: ItemFilter,
        scope: AccessScope = AccessScope.unrestricted(),
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
     * The items whose id, as 32 lower-case hex digits without dashes, starts with [prefix]
     * (compared ignoring case), in ascending id order, at most [limit] of them. Backs short-id
     * resolution (`IdResolver`); a prefix that is not hex, or longer than 32 digits, matches
     * nothing.
     */
    suspend fun findByIdPrefix(
        prefix: String,
        limit: Int = 10
    ): List<WorkItem>

    /**
     * Count work items within the subtree rooted at any of [rootIds] (roots included), grouped by
     * role. Mirrors [countChildrenByRole]'s per-role breakdown, but scoped to the full subtree
     * (any depth) instead of direct children only. Roles with zero matches are omitted.
     *
     * When [rootIds] is empty, returns an empty map immediately.
     */
    suspend fun countInScopeByRole(rootIds: Set<UUID>): Map<Role, Int>

    // ---------------------------------------------------------------------------------------------
    // Batched read primitives (the read model's building blocks, used by WorkViewQueries). Each is
    // ONE statement for an id set of any size (the ids are bound as one JSON-array parameter),
    // however many rows, children or descendants lie behind the ids; the exception is [rolesOf],
    // which is one statement per 500 ids (a larger set is chunked inside the same transaction). An
    // empty id set returns an empty result without touching the database.
    // ---------------------------------------------------------------------------------------------

    /**
     * The stored role of each id in [ids]. An id with no row is absent from the result; callers
     * that evaluate blockers treat an absent role as unsatisfied (fail-closed).
     */
    suspend fun rolesOf(ids: Set<UUID>): Map<UUID, Role>

    /**
     * The items with the ids in [ids], in no particular order, as ONE statement for any number of
     * ids (unlike [findByIds], which chunks its IN list). An id with no row is absent.
     */
    suspend fun findByIdSet(ids: Set<UUID>): List<WorkItem>

    /**
     * Direct-children counts grouped by role, per parent in [parentIds]. Every requested id is a
     * key; a parent with no children (or no row) maps to an empty map. Roles with zero children are
     * omitted from the inner map.
     */
    suspend fun countByParentAndRole(parentIds: Set<UUID>): Map<UUID, Map<Role, Int>>

    /**
     * Descendant counts grouped by role, per top item in [topIds]: every item below the top at any
     * depth, the top itself EXCLUDED. One recursive walk carries the top id, so overlapping tops
     * (one inside another's subtree) each get their own full count. Every requested id is a key; a
     * top with no descendants maps to an empty map.
     */
    suspend fun rollupByTopChild(topIds: Set<UUID>): Map<UUID, Map<Role, Int>>

    /**
     * Every direct child of each parent in [parentIds], grouped by parent. Every requested id is a
     * key (an empty list when it has no children). Children are ordered newest `createdAt` first,
     * then `id` ascending.
     */
    suspend fun findByParents(parentIds: Set<UUID>): Map<UUID, List<WorkItem>>

    /**
     * One page of the direct children of [parentId] (the parentless roots when [parentId] is null),
     * newest `createdAt` first, then `id` ascending, plus the exact count of the whole filtered set.
     *
     * With [excludeTerminal], a TERMINAL child is dropped UNLESS it still has a non-terminal
     * descendant (QUEUE, WORK, REVIEW or BLOCKED) anywhere below it: active work parked under a
     * done container stays visible (bug 18fd99a7). The same rule applies to roots.
     */
    suspend fun findChildrenPage(
        parentId: UUID?,
        excludeTerminal: Boolean,
        limit: Int,
        offset: Int,
    ): ItemSlice

    /**
     * One page of the items whose role is in [roles], optionally within the subtree(s) of
     * [rootIds] (roots included; an empty set selects nothing), plus the exact count of the whole
     * set. Ordered by role (queue, work, review, blocked, terminal), then `role_changed_at`
     * ascending (longest in the role first), then `id` ascending.
     */
    suspend fun findByRoles(
        roles: Set<Role>,
        rootIds: Set<UUID>?,
        limit: Int,
        offset: Int,
    ): ItemSlice

    /**
     * One page of the blocked items, plus the exact count of the whole set: role BLOCKED
     * ("explicit"), or role QUEUE, WORK or REVIEW with at least one unsatisfied blocking dependency
     * ("dependency"). A blocking dependency is unsatisfied when its blocker's role is not at or
     * beyond the edge threshold (`unblock_at`, TERMINAL when null); a BLOCKED or missing blocker
     * never satisfies. TERMINAL items are never returned. The optional [parentId] (direct children)
     * and [ancestorId] (subtree, inclusive) filters are AND-ed. Ordered BLOCKED first, then QUEUE,
     * WORK, REVIEW, then `id` ascending.
     */
    suspend fun findBlocked(
        parentId: UUID?,
        ancestorId: UUID?,
        limit: Int,
        offset: Int,
    ): ItemSlice
}

/**
 * One page of items plus [total]: the exact number of items the query selects across all pages,
 * counted in the same transaction as [items].
 */
data class ItemSlice(
    val items: List<WorkItem>,
    val total: Int,
)

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
