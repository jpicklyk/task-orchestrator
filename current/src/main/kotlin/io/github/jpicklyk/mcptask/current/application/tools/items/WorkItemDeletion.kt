package io.github.jpicklyk.mcptask.current.application.tools.items

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
     *   and every descendant leaves-first (deepest depth first, to satisfy FK constraints) inside
     *   ONE transaction — all-or-nothing: a failure anywhere in the subtree rolls back every row
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

        var leaseReleaseFailure: String? = null
        var deleteResult: Result<Boolean>? = null
        repo.inTransaction {
            when (val release = leaseRepo.releaseAllForItem(id)) {
                is LeaseReleaseResult.Success -> {
                    deleteResult = repo.delete(id)
                }
                is LeaseReleaseResult.DBError -> {
                    leaseReleaseFailure = "Failed to release resource leases for '$id': ${release.cause.message}"
                }
            }
        }

        leaseReleaseFailure?.let { return WorkItemDeleteOutcome.Failed(id, it) }

        return when (val result = deleteResult) {
            is Result.Success ->
                if (result.data) {
                    WorkItemDeleteOutcome.Deleted(id, descendantsDeleted = 0)
                } else {
                    WorkItemDeleteOutcome.NotFound(id)
                }
            is Result.Error -> WorkItemDeleteOutcome.Failed(id, result.error.message)
            null -> WorkItemDeleteOutcome.Failed(id, "Failed to delete item '$id'")
        }
    }

    private suspend fun deleteRecursive(
        repo: WorkItemRepository,
        leaseRepo: ResourceLeaseRepository,
        id: UUID
    ): WorkItemDeleteOutcome {
        var localDescendantsDeleted = 0
        var rootDeleted = false

        try {
            repo.inTransaction {
                // Find all descendants, delete leaves-first, then the root.
                val descendantsResult = repo.findDescendants(id)
                if (descendantsResult is Result.Error) {
                    throw DeleteFailureException("Failed to find descendants: ${descendantsResult.error.message}")
                }
                val descendants = (descendantsResult as Result.Success).data
                if (descendants.isNotEmpty()) {
                    // Sort leaves-first (deepest depth first) so FK constraints are satisfied.
                    // Delete individually to ensure each row is removed before referencing
                    // parents are removed (batch DELETE can trigger FK violations mid-statement).
                    val sortedDescendants = descendants.sortedByDescending { it.depth }
                    for (descendant in sortedDescendants) {
                        releaseLeasesOrThrow(leaseRepo, descendant.id)
                        when (val delResult = repo.delete(descendant.id)) {
                            is Result.Success -> if (delResult.data) localDescendantsDeleted++
                            is Result.Error ->
                                throw DeleteFailureException(
                                    "Failed to delete descendant ${descendant.id}: ${delResult.error.message}"
                                )
                        }
                    }
                }

                releaseLeasesOrThrow(leaseRepo, id)
                when (val result = repo.delete(id)) {
                    is Result.Success -> rootDeleted = result.data
                    is Result.Error -> throw DeleteFailureException(result.error.message)
                }
            }
        } catch (e: DeleteFailureException) {
            return WorkItemDeleteOutcome.Failed(id, e.message ?: "Failed to delete item '$id'")
        }

        return if (rootDeleted) {
            WorkItemDeleteOutcome.Deleted(id, localDescendantsDeleted)
        } else {
            WorkItemDeleteOutcome.NotFound(id)
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
