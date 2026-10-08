package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.UnitResult
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.writeUnit
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.util.UUID

/**
 * Outcome of a single [WorkItemDeletion.delete] call.
 */
sealed interface WorkItemDeleteOutcome {
    /** [id] (and, for a recursive delete, [descendantsDeleted] descendants) were deleted. */
    data class Deleted(
        val id: UUID,
        val descendantsDeleted: Int
    ) : WorkItemDeleteOutcome

    /** Non-recursive delete refused: [id] has [childCount] direct children. Nothing was deleted. */
    data class HasChildren(
        val id: UUID,
        val childCount: Int
    ) : WorkItemDeleteOutcome

    /** [id] does not exist (or no longer exists — e.g. a race between an existence check and the delete). */
    data class NotFound(
        val id: UUID
    ) : WorkItemDeleteOutcome

    /** The delete (or a lease-release/child-count check preceding it) failed. Nothing was deleted. */
    data class Failed(
        val id: UUID,
        val message: String
    ) : WorkItemDeleteOutcome
}

/**
 * Deletes a work item, optionally cascading to its descendants, releasing each deleted row's
 * resource leases (closing their lease-history intervals) inside the SAME unit of work as that
 * row's delete — so release and delete commit, or roll back, together. Shared by the MCP
 * `manage_items` delete operation ([DeleteItemHandler]) and the REST `DELETE /api/v1/items/{id}`
 * route ([io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemWriteRoutes]) so both
 * surfaces have identical delete semantics and identical lease-release-before-delete behavior.
 *
 * Fails closed on lease release, same as the logic this lifts out of: unlike
 * [io.github.jpicklyk.mcptask.current.application.service.AdvanceService]'s `releaseLeases`
 * (which logs and continues because the lease TTL is still a backstop for a surviving item), a
 * deleted item's row is gone — its lease can never be released again, so a release failure here
 * must abort the delete rather than silently leaving the interval open forever.
 */
class WorkItemDeletion(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork
) {
    /**
     * @param id The item to delete.
     * @param recursive When `false` and [id] has one or more direct children, returns
     *   [WorkItemDeleteOutcome.HasChildren] without deleting anything. When `true`, deletes [id]
     *   and every descendant in traversal-level batches (deepest level first, so a parent is
     *   never deleted before its children) inside ONE unit of work: all-or-nothing, a failure anywhere in the subtree rolls back every row
     *   deleted so far for this call.
     */
    suspend fun delete(
        id: UUID,
        recursive: Boolean
    ): WorkItemDeleteOutcome {
        val repo = repositoryProvider.workItemRepository()
        val leaseRepo = repositoryProvider.resourceLeaseRepository()

        return if (recursive) {
            deleteRecursive(repo, leaseRepo, id)
        } else {
            deleteNonRecursive(repo, leaseRepo, id)
        }
    }

    private suspend fun deleteNonRecursive(
        repo: WorkItemRepository,
        leaseRepo: ResourceLeaseRepository,
        id: UUID
    ): WorkItemDeleteOutcome {
        val children = legacyRead({ return WorkItemDeleteOutcome.Failed(id, "Failed to check children: $it") }) { repo.findChildren(id) }
        if (children.isNotEmpty()) {
            return WorkItemDeleteOutcome.HasChildren(id, children.size)
        }

        // Release and delete must commit (or roll back) together: a failed delete and a not-found
        // (false) delete both roll the unit back, so the lease release never commits while the row
        // survives, or for an item that was never there.
        return deleteUnit(id, "WorkItemDeletion.delete") {
            releaseLeases(leaseRepo, id)?.let { return@deleteUnit UnitResult.Rollback(it) }
            rootDelete(repo, id, descendantsDeleted = 0)
        }
    }

    private suspend fun deleteRecursive(
        repo: WorkItemRepository,
        leaseRepo: ResourceLeaseRepository,
        id: UUID
    ): WorkItemDeleteOutcome {
        return deleteUnit(id, "WorkItemDeletion.deleteRecursive") {
            var localDescendantsDeleted = 0
            // Find all descendants, release their leases in bulk, delete them level by level
            // (deepest level first), then the root.
            val descendants =
                legacyRead({
                    return@deleteUnit UnitResult.Rollback(WorkItemDeleteOutcome.Failed(id, "Failed to find descendants: $it"))
                }) { repo.findDescendants(id) }
            if (descendants.isNotEmpty()) {
                releaseLeasesBulk(leaseRepo, id, descendants.map { it.id }.toSet())?.let {
                    return@deleteUnit UnitResult.Rollback(it)
                }
                // Group by traversal level computed from the parentId links, NOT the stored
                // `depth` column (which can be stale). Within one level no row is the parent
                // of another, so each batch DELETE is FK-safe regardless of chunk boundaries
                // or whether FK checks run per row or at statement end.
                for (levelIds in descendantLevelsDeepestFirst(id, descendants)) {
                    localDescendantsDeleted +=
                        legacyRead({
                            return@deleteUnit UnitResult.Rollback(WorkItemDeleteOutcome.Failed(id, "Failed to delete descendants: $it"))
                        }) { repo.deleteAll(levelIds) }
                }
            }

            releaseLeases(leaseRepo, id)?.let { return@deleteUnit UnitResult.Rollback(it) }
            // Root not-found (false) must ALSO roll back, not just fall through:
            // the descendant deletes and releases above are in this SAME unit, so committing here
            // would keep them even though the root itself was never there to delete, breaking the
            // all-or-nothing guarantee this class's KDoc promises for a recursive delete.
            rootDelete(repo, id, localDescendantsDeleted)
        }
    }

    /** One write unit for a delete of [id]; a fault of the unit itself becomes [WorkItemDeleteOutcome.Failed]. */
    private suspend fun deleteUnit(
        id: UUID,
        op: String,
        block: suspend () -> UnitResult<WorkItemDeleteOutcome>
    ): WorkItemDeleteOutcome =
        unitOfWork.writeUnit(op, onFault = { WorkItemDeleteOutcome.Failed(id, LegacyFaults.message(it)) }) { block() }

    /** Deletes the root row [id]: commit on success, roll back on a failure or a missing row. */
    private suspend fun rootDelete(
        repo: WorkItemRepository,
        id: UUID,
        descendantsDeleted: Int
    ): UnitResult<WorkItemDeleteOutcome> {
        val deleted = legacyRead({ return UnitResult.Rollback(WorkItemDeleteOutcome.Failed(id, it)) }) { repo.delete(id) }
        return if (deleted) {
            UnitResult.Commit(WorkItemDeleteOutcome.Deleted(id, descendantsDeleted))
        } else {
            UnitResult.Rollback(WorkItemDeleteOutcome.NotFound(id))
        }
    }

    /**
     * Groups [descendants] of [rootId] by traversal level (direct children of [rootId] are level 1,
     * any other descendant is its parent's level + 1) and returns the id sets deepest level first.
     * Levels derive from parentId links so a stale stored `depth` cannot misorder deletes.
     */
    private fun descendantLevelsDeepestFirst(
        rootId: UUID,
        descendants: List<WorkItem>
    ): List<Set<UUID>> {
        val parentOf = descendants.associate { it.id to it.parentId }
        val levelCache = HashMap<UUID, Int>(descendants.size)

        fun levelOf(itemId: UUID): Int {
            levelCache[itemId]?.let { return it }
            // Iterative walk up the parent chain (subtrees can be deep) until a known level or the root.
            val chain = ArrayList<UUID>()
            var current: UUID? = itemId
            var base = 0
            while (current != null && current != rootId) {
                val known = levelCache[current]
                if (known != null) {
                    base = known
                    break
                }
                chain.add(current)
                current = parentOf[current]
            }
            // current == rootId (base 0), a cached ancestor, or null (orphan; treated as level 1 root-attached).
            var level = base
            for (node in chain.asReversed()) {
                level += 1
                levelCache[node] = level
            }
            return levelCache.getValue(itemId)
        }

        return descendants
            .groupBy({ levelOf(it.id) }, { it.id })
            .toSortedMap(compareByDescending { it })
            .values
            .map { it.toSet() }
    }

    /**
     * Releases every resource lease held by any item in [itemIds] in one bulk call. Returns the
     * [WorkItemDeleteOutcome.Failed] (for the delete of [rootId]) that must roll the unit back when the
     * release throws (same fail-closed contract as [releaseLeases]), or null.
     */
    private suspend fun releaseLeasesBulk(
        leaseRepo: ResourceLeaseRepository,
        rootId: UUID,
        itemIds: Set<UUID>
    ): WorkItemDeleteOutcome.Failed? {
        legacyRead({
            return WorkItemDeleteOutcome.Failed(rootId, "Failed to release resource leases for ${itemIds.size} descendants: $it")
        }) { leaseRepo.releaseAllForItems(itemIds) }
        return null
    }

    /**
     * Releases every resource lease held by [itemId], closing its lease-history interval(s), before
     * the caller deletes the row, in the same unit as the delete so release and delete commit (or roll
     * back) together. Returns the [WorkItemDeleteOutcome.Failed] that must roll the unit back when the
     * release throws (see this class's KDoc for why a release failure must abort rather than
     * continue), or null.
     */
    private suspend fun releaseLeases(
        leaseRepo: ResourceLeaseRepository,
        itemId: UUID
    ): WorkItemDeleteOutcome.Failed? {
        legacyRead({
            return WorkItemDeleteOutcome.Failed(itemId, "Failed to release resource leases for '$itemId': $it")
        }) { leaseRepo.releaseAllForItem(itemId) }
        return null
    }
}
