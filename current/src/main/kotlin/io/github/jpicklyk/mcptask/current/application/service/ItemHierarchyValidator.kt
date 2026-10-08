package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.util.UUID

/**
 * Descendant depth/rootId maintenance for WorkItem hierarchy moves.
 *
 * Holds only [recomputeDescendantDepths]. The hierarchy guard rules (parent existence,
 * self-parent, descendant cycle) and the placement-aware write pipeline live in
 * [WorkItemPlacementService]; the DB BEFORE-UPDATE trigger on work_items.parent_id (V7) remains
 * the authoritative cycle guard for persistence.
 */
class ItemHierarchyValidator {
    /**
     * Recomputes the stored `depth` and `rootId` for every descendant of [itemId] after the
     * item's own depth changed by [delta] (`newDepth - oldDepth`) and its own root ancestor
     * changed to [newRootId].
     *
     * Unlike the depth-only cascade this replaced, this is NOT a no-op when [delta] is zero:
     * reparenting an item to a different root subtree at the *same* depth (e.g. moving a
     * depth-1 leaf from one root's children to another root's children) leaves depth
     * unchanged but must still restamp `rootId` on every descendant. Fetches the full
     * descendant set via [WorkItemRepository.findDescendants] and applies [delta] to each
     * descendant's depth while overwriting `rootId` with [newRootId], persisting through
     * [WorkItemRepository.update] (the update builder keeps `modifiedAt` monotonic, and
     * `update` enforces the same optimistic-version check used for any other item write).
     *
     * This issues one `update` per descendant on purpose: each per-row write carries the
     * optimistic version check, monotonic `modifiedAt`, and a per-descendant ITEM_UPDATED event
     * through the event-publishing decorator. A bulk restamp primitive (a new decorator override
     * plus SQL-side version/`modifiedAt` semantics) is a known follow-up, not a missing
     * primitive; the descendant fetch itself is chunked and has no bound-variable limit. Callers that need the parent's
     * own depth/rootId write and this cascade to be atomic (all-or-nothing) MUST invoke both
     * inside one shared unit of work (`UnitOfWork.write`).
     *
     * @return null once every descendant has been updated (including the trivial case of zero
     *   descendants); otherwise the failure message of the first failure encountered: the descendant
     *   fetch or a single descendant's update throwing (e.g. a version-mismatch conflict), or a
     *   descendant row that vanished. The caller rolls its unit back on a non-null return.
     */
    suspend fun recomputeDescendantDepths(
        itemId: UUID,
        delta: Int,
        newRootId: UUID,
        repo: WorkItemRepository
    ): String? =
        try {
            val descendants = repo.findDescendants(itemId)
            var failure: String? = null
            for (descendant in descendants) {
                val updatedDescendant = descendant.update { d -> d.copy(depth = d.depth + delta, rootId = newRootId) }
                if (repo.update(updatedDescendant) == null) {
                    failure = "WorkItem not found with id: ${descendant.id}"
                    break
                }
            }
            failure
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            LegacyFaults.message(e)
        }
}
