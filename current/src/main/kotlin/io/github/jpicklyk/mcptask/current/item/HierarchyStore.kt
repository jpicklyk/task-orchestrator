package io.github.jpicklyk.mcptask.current.item

import io.github.jpicklyk.mcptask.current.model.item.AncestorChain
import io.github.jpicklyk.mcptask.current.model.item.MAX_TRAVERSAL_DEPTH
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.port.AncestorChains
import java.util.UUID

/**
 * Hierarchy walks over the `parent_id` graph: descendants, ancestor chains and child placement, plus
 * the one bulk placement write, [restampSubtree]. An item's own placement (`parent_id`, `root_id`,
 * `depth`) is still written by the item store's insert/update; the descendant restamp after a reparent
 * lives here.
 */
interface HierarchyStore : AncestorChains {
    /**
     * Find all descendants of the given item (children, grandchildren, etc.) recursively.
     * Does not include the item itself.
     *
     * The traversal is bounded at [MAX_TRAVERSAL_DEPTH] levels and never revisits a node. Cyclic
     * or pathologically deep `parent_id` data throws an exception naming the bound — callers of this method drive cascade
     * deletes and subtree restamps, where a silently truncated list would corrupt more than it
     * reports.
     */
    suspend fun findDescendants(id: UUID): List<WorkItem>

    /**
     * The ids of every descendant of [id] (children, grandchildren, ...), excluding [id] itself,
     * from the same bounded traversal as [findDescendants] but without loading rows. Fails loud
     * at [MAX_TRAVERSAL_DEPTH] like [findDescendants].
     */
    suspend fun descendantIds(id: UUID): Set<UUID>

    /**
     * For each itemId, resolve its full ancestor chain (root -> direct parent).
     * Returns Map<itemId, List<WorkItem>> ordered root-first, ancestors only (item itself excluded).
     * Items with no parent (depth=0 root items) map to an empty list.
     *
     * A chain may be **silently truncated** when the `parent_id` graph is cyclic or an ancestor row
     * is missing/domain-invalid: the returned list is then shorter than the real chain and is
     * indistinguishable from a genuinely shallow item. Callers that make a safety decision on chain
     * completeness must use [findAncestorChainsDetailed] instead.
     */
    override suspend fun findAncestorChains(itemIds: Set<UUID>): Map<UUID, List<WorkItem>>

    /**
     * [findAncestorChains] with an explicit completeness signal per item.
     *
     * Same walk, same ordering and same contents — each entry additionally reports whether the
     * upward walk reached a parentless root ([AncestorChain.truncated] false) or stopped early on a
     * cycle or a missing ancestor, with [AncestorChain.truncationReason] naming which.
     * [findAncestorChains] is defined as this method with the flag dropped.
     */
    suspend fun findAncestorChainsDetailed(itemIds: Set<UUID>): Map<UUID, AncestorChain>

    /**
     * Resolve the placement (`depth`/`rootId`) a new or reparented child of [parentId] must be
     * stamped with, reading the parent AS OF the call site.
     *
     * **MUST be called inside the same unit of work as the write that stamps the
     * returned [ChildPlacement] onto a child row.** Reading the parent in its own transaction and
     * writing the child in a later, separate transaction lets a concurrent reparent or delete of
     * the parent commit in between, silently stamping the child with stale placement — see AR-19.
     * A read inside the write transaction instead makes a concurrent commit surface as a
     * transaction failure (e.g. `SQLITE_BUSY_SNAPSHOT`) rather than a silent stale write.
     *
     * A read: it records no event. The placement is `depth = parent.depth + 1`, `rootId = parent.rootId ?: parent.id`.
     *
     * @return the resolved [ChildPlacement], or null when [parentId] does not resolve to an existing item.
     */
    suspend fun resolveChildPlacement(parentId: UUID): ChildPlacement?

    /**
     * Restamps every DESCENDANT of [itemId] ([itemId] itself excluded) after the item moved: `depth += depthDelta`,
     * `root_id = newRootId`, `version += 1` and `modified_at` = the unit instant, in ONE statement over the same
     * bounded subtree walk as [findDescendants] (fails loud at [MAX_TRAVERSAL_DEPTH]). Must run inside the write
     * unit that moved the item. Records no event: the caller owns the `item.updated` rows.
     *
     * @return the number of rows restamped.
     */
    suspend fun restampSubtree(
        itemId: UUID,
        depthDelta: Int,
        newRootId: UUID
    ): Int
}

/**
 * The `depth`/`rootId` placement a child of [parentId] must be stamped with, resolved by
 * [HierarchyStore.resolveChildPlacement]. See that method's KDoc for the transactional
 * contract this type's caller must uphold.
 */
data class ChildPlacement(
    val parentId: UUID,
    val depth: Int,
    val rootId: UUID
)
