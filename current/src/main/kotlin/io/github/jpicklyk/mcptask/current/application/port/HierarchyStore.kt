package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.AncestorChain
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * Hard bound on how many levels any repository-level hierarchy traversal will walk.
 *
 * Item depth is intentionally unbounded from V7 onward, so this is not a product limit — it is a
 * corruption backstop. A cyclic `parent_id` edge (reachable through pre-V7 rows, before the
 * cycle-check triggers were installed) otherwise makes every recursive descent run forever.
 * The value sits far above any plausible real tree, so hitting it means the data is cyclic, not deep.
 *
 * Traversals that feed destructive or whole-subtree work ([HierarchyStore.findDescendants],
 * scope resolution behind [ItemStore.findInScope] / [ItemStore.countInScope])
 * fail loud (throw) when the bound is hit — a silently short list is
 * worse than an error there. Search-scope traversals are merely bounded, since degraded results
 * beat failing every search on a corrupt database.
 */
const val MAX_TRAVERSAL_DEPTH: Int = 1000

/**
 * Hierarchy walks over the `parent_id` graph: descendants, ancestor chains and child placement.
 * The store has NO write methods in P7: placement (`parent_id`, `root_id`, `depth`) is written
 * only by item create/update today, and a later item moves those writes here.
 */
interface HierarchyStore {
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
    suspend fun findAncestorChains(itemIds: Set<UUID>): Map<UUID, List<WorkItem>>

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
     * Reads only, so the event-publishing decorator needs no override: no event is published by a read.
     * The placement is `depth = parent.depth + 1`, `rootId = parent.rootId ?: parent.id`.
     *
     * @return the resolved [ChildPlacement], or null when [parentId] does not resolve to an existing item.
     */
    suspend fun resolveChildPlacement(parentId: UUID): ChildPlacement?
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
