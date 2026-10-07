package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
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
 * resource leases (closing their lease-history intervals) inside the SAME transaction as that
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
    private val repositoryProvider: RepositoryProvider
) {
    /**
     * @param id The item to delete.
     * @param recursive When `false` and [id] has one or more direct children, returns
     *   [WorkItemDeleteOutcome.HasChildren] without deleting anything. When `true`, deletes [id]
     *   and every descendant in traversal-level batches (deepest level first, so a parent is
     *   never deleted before its children) inside ONE transaction — all-or-nothing: a failure anywhere in the subtree rolls back every row
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
        val childrenResult = repo.findChildren(id)
        if (childrenResult is Result.Error) {
            return WorkItemDeleteOutcome.Failed(id, "Failed to check children: ${childrenResult.error.message}")
        }
        val children = (childrenResult as Result.Success).data
        if (children.isNotEmpty()) {
            return WorkItemDeleteOutcome.HasChildren(id, children.size)
        }

        // Release and delete must commit (or roll back) together: repo.delete() reports failure
        // via Result.Error rather than by throwing, and a returned Result.Error does not by itself
        // abort or roll back a transaction. Both a delete Result.Error and a not-found
        // Result.Success(false) are converted into a thrown DeleteFailureException inside the
        // block, which is what actually forces the lease release to roll back together with the
        // failed/no-op delete — without this, the block would COMMIT the release while the row
        // (for Result.Error) survives, or leave the release orphaned for an item that was never
        // there to delete.
        var notFound = false
        try {
            repo.inTransaction {
                releaseLeasesOrThrow(leaseRepo, id)
                when (val result = repo.delete(id)) {
                    is Result.Success ->
                        if (!result.data) {
                            notFound = true
                            throw DeleteFailureException("Item '$id' not found")
                        }
                    is Result.Error -> throw DeleteFailureException(result.error.message)
                }
            }
        } catch (e: DeleteFailureException) {
            return if (notFound) {
                WorkItemDeleteOutcome.NotFound(id)
            } else {
                WorkItemDeleteOutcome.Failed(id, e.message ?: "Failed to delete item '$id'")
            }
        }

        return WorkItemDeleteOutcome.Deleted(id, descendantsDeleted = 0)
    }

    private suspend fun deleteRecursive(
        repo: WorkItemRepository,
        leaseRepo: ResourceLeaseRepository,
        id: UUID
    ): WorkItemDeleteOutcome {
        var localDescendantsDeleted = 0
        var notFound = false

        try {
            repo.inTransaction {
                // Find all descendants, release their leases in bulk, delete them level by level
                // (deepest level first), then the root.
                val descendantsResult = repo.findDescendants(id)
                if (descendantsResult is Result.Error) {
                    throw DeleteFailureException("Failed to find descendants: ${descendantsResult.error.message}")
                }
                val descendants = (descendantsResult as Result.Success).data
                if (descendants.isNotEmpty()) {
                    releaseLeasesBulkOrThrow(leaseRepo, descendants.map { it.id }.toSet())
                    // Group by traversal level computed from the parentId links, NOT the stored
                    // `depth` column (which can be stale). Within one level no row is the parent
                    // of another, so each batch DELETE is FK-safe regardless of chunk boundaries
                    // or whether FK checks run per row or at statement end.
                    for (levelIds in descendantLevelsDeepestFirst(id, descendants)) {
                        when (val delResult = repo.deleteAll(levelIds)) {
                            is Result.Success -> localDescendantsDeleted += delResult.data
                            is Result.Error ->
                                throw DeleteFailureException(
                                    "Failed to delete descendants: ${delResult.error.message}"
                                )
                        }
                    }
                }

                releaseLeasesOrThrow(leaseRepo, id)
                // Root not-found (Result.Success(false)) must ALSO throw, not just fall through:
                // the descendant deletes and releases above are inside this SAME transaction block,
                // so failing to throw here would let them all COMMIT even though the root itself was
                // never there to delete — breaking the all-or-nothing guarantee this class's KDoc
                // promises for a recursive delete.
                when (val result = repo.delete(id)) {
                    is Result.Success ->
                        if (!result.data) {
                            notFound = true
                            throw DeleteFailureException("Item '$id' not found")
                        }
                    is Result.Error -> throw DeleteFailureException(result.error.message)
                }
            }
        } catch (e: DeleteFailureException) {
            return if (notFound) {
                WorkItemDeleteOutcome.NotFound(id)
            } else {
                WorkItemDeleteOutcome.Failed(id, e.message ?: "Failed to delete item '$id'")
            }
        }

        return WorkItemDeleteOutcome.Deleted(id, localDescendantsDeleted)
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
     * Releases every resource lease held by any item in [itemIds] in one bulk call, throwing
     * [DeleteFailureException] on [LeaseReleaseResult.DBError] (same fail-closed contract as
     * [releaseLeasesOrThrow]).
     */
    private suspend fun releaseLeasesBulkOrThrow(
        leaseRepo: ResourceLeaseRepository,
        itemIds: Set<UUID>
    ) {
        when (val release = leaseRepo.releaseAllForItems(itemIds)) {
            is LeaseReleaseResult.Success -> Unit
            is LeaseReleaseResult.DBError ->
                throw DeleteFailureException(
                    "Failed to release resource leases for ${itemIds.size} descendants: ${release.cause.message}"
                )
        }
    }

    /**
     * Releases every resource lease held by [itemId], closing its lease-history interval(s), before
     * the caller deletes the row. Called inside the same [WorkItemRepository.inTransaction] block
     * as the delete so release and delete commit (or roll back) together. Throws
     * [DeleteFailureException] on [LeaseReleaseResult.DBError] so the enclosing transaction rolls
     * back — see this class's KDoc for why a release failure must abort rather than continue.
     */
    private suspend fun releaseLeasesOrThrow(
        leaseRepo: ResourceLeaseRepository,
        itemId: UUID
    ) {
        when (val release = leaseRepo.releaseAllForItem(itemId)) {
            is LeaseReleaseResult.Success -> Unit
            is LeaseReleaseResult.DBError ->
                throw DeleteFailureException(
                    "Failed to release resource leases for '$itemId': ${release.cause.message}"
                )
        }
    }

    /**
     * Internal marker exception used to abort the shared [WorkItemRepository.inTransaction] block
     * for a recursive delete when any descendant lookup, lease release, or delete (or the root
     * delete itself) fails. Thrown inside the block so the transaction rolls back every row
     * deleted so far; caught immediately outside the block and converted to
     * [WorkItemDeleteOutcome.Failed]. Never surfaced past [deleteRecursive].
     */
    private class DeleteFailureException(
        message: String?
    ) : Exception(message)
}
