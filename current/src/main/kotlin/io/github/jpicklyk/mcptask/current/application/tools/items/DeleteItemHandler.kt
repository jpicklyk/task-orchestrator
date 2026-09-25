package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Handles the `delete` operation for [ManageItemsTool].
 *
 * Supports both direct deletion and recursive deletion of item hierarchies.
 * When `recursive` is true, descendants are deleted leaves-first to satisfy
 * foreign key constraints.
 *
 * Every deleted row's resource leases are released (closing their lease-history intervals)
 * immediately before that row is deleted, inside the same transaction — for both the recursive
 * and the non-recursive path. Because `repo.delete()` reports failure via [Result.Error] rather
 * than by throwing, and a returned [Result.Error] does not by itself abort or roll back a
 * transaction, both paths convert a delete failure (`Result.Error`, or `Result.Success(false)` =
 * not found) into a thrown [DeleteFailureException] inside the transaction block — this is what
 * actually forces the release to roll back together with the failed delete, rather than the
 * block committing with the lease released and the row still present.
 */
class DeleteItemHandler {
    /**
     * Executes a batch delete of WorkItems by ID.
     *
     * @param idsArray JSON array of UUID strings to delete
     * @param recursive When true, recursively delete all descendants before each item
     * @param context The tool execution context providing repository access
     * @return A JSON response envelope with deleted IDs, counts, and any failures
     */
    suspend fun execute(
        idsArray: JsonArray,
        recursive: Boolean,
        context: ToolExecutionContext
    ): JsonElement {
        val repo = context.workItemRepository()
        val leaseRepo = context.repositoryProvider.resourceLeaseRepository()

        val deletedIds = mutableListOf<String>()
        var descendantsDeleted = 0
        val failures = mutableListOf<JsonObject>()

        for (element in idsArray) {
            val idStr = (element as? JsonPrimitive)?.content
            if (idStr == null) {
                failures.add(
                    buildJsonObject {
                        put("id", JsonPrimitive("null"))
                        put("error", JsonPrimitive("Each ID must be a string"))
                    }
                )
                continue
            }

            val id =
                try {
                    resolveWorkItemIdString(idStr, context, "'id'")
                } catch (e: ToolValidationException) {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put("error", JsonPrimitive(e.message ?: "Invalid ID: $idStr"))
                        }
                    )
                    continue
                }

            if (recursive) {
                // Recursive delete of this root id and all its descendants must be all-or-nothing:
                // a failure anywhere in the subtree must leave every row of that subtree untouched.
                // repo.delete() returns Result.Error rather than throwing, so any failure inside the
                // transaction block is surfaced by throwing DeleteFailureException, which aborts the
                // transaction and rolls back every write made so far for this root id. The exception
                // is caught immediately below (outside the block) to produce the usual per-id failure
                // entry — the transaction scope is this one root id, not the whole batch, so an
                // earlier or later id in the same call is unaffected.
                var localDescendantsDeleted = 0
                var rootDeleted = false
                try {
                    repo.inTransaction {
                        // Find all descendants, delete leaves-first, then the root
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
                            is Result.Success ->
                                if (result.data) {
                                    rootDeleted = true
                                } else {
                                    throw DeleteFailureException("Item '$idStr' not found")
                                }
                            is Result.Error -> throw DeleteFailureException(result.error.message)
                        }
                    }
                } catch (e: DeleteFailureException) {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put("error", JsonPrimitive(e.message ?: "Failed to delete item '$idStr'"))
                        }
                    )
                    continue
                }
                descendantsDeleted += localDescendantsDeleted
                if (rootDeleted) {
                    deletedIds.add(idStr)
                }
            } else {
                // Non-recursive: guard against FK constraint violation by checking for children first
                val childrenResult = repo.findChildren(id)
                if (childrenResult is Result.Success && childrenResult.data.isNotEmpty()) {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put(
                                "error",
                                JsonPrimitive(
                                    "Item '$idStr' has ${childrenResult.data.size} child item(s). " +
                                        "Use recursive=true to delete the item and all its descendants."
                                )
                            )
                        }
                    )
                    continue
                }
                if (childrenResult is Result.Error) {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put("error", JsonPrimitive("Failed to check children: ${childrenResult.error.message}"))
                        }
                    )
                    continue
                }

                // Same all-or-nothing discipline as the recursive path above: release and delete
                // must commit (or roll back) together. repo.delete() returns Result.Error rather
                // than throwing, so a plain Result-based branch inside the transaction block would
                // let the block COMMIT the lease release while the row survives (Result.Error is
                // not a thrown exception the transaction sees). Throwing DeleteFailureException on
                // both Result.Error and "not found" forces the rollback; it is caught immediately
                // outside the block and converted into the usual per-id outcome.
                try {
                    repo.inTransaction {
                        releaseLeasesOrThrow(leaseRepo, id)
                        when (val result = repo.delete(id)) {
                            is Result.Success ->
                                if (!result.data) {
                                    throw DeleteFailureException("Item '$idStr' not found")
                                }
                            is Result.Error -> throw DeleteFailureException(result.error.message)
                        }
                    }
                } catch (e: DeleteFailureException) {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put("error", JsonPrimitive(e.message ?: "Failed to delete item '$idStr'"))
                        }
                    )
                    continue
                }
                deletedIds.add(idStr)
            }
        }

        val data =
            buildJsonObject {
                put("ids", JsonArray(deletedIds.map { JsonPrimitive(it) }))
                put("deleted", JsonPrimitive(deletedIds.size + descendantsDeleted))
                put("failed", JsonPrimitive(failures.size))
                if (descendantsDeleted > 0) {
                    put("descendantsDeleted", JsonPrimitive(descendantsDeleted))
                }
                if (failures.isNotEmpty()) {
                    put("failures", JsonArray(failures))
                }
            }

        return ResponseUtil.createSuccessResponse(data)
    }

    /**
     * Releases every resource lease held by [itemId], closing its lease-history interval(s), before
     * the caller deletes the row. Called inside the same [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.inTransaction]
     * block as the delete so release and delete commit (or roll back) together.
     *
     * Fails closed on purpose: unlike [io.github.jpicklyk.mcptask.current.application.service.AdvanceService]'s
     * `releaseLeases` (which logs and continues because the lease TTL is still a backstop for a
     * surviving item), a deleted item's row is gone — its lease can never be released again, so a
     * release failure here must abort the delete rather than silently leaving the interval open
     * forever. Throws [DeleteFailureException] on [io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult.DBError]
     * so the enclosing transaction rolls back.
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
     * Internal marker exception used to abort the shared [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.inTransaction]
     * block for a single root id's recursive delete when any descendant lookup or delete (or the
     * root delete itself) fails or reports "not found". Thrown inside the block so the transaction
     * rolls back every row deleted so far for that root id; caught immediately outside the block
     * and converted into that id's per-id failure entry. Never surfaced past [execute].
     */
    private class DeleteFailureException(
        message: String?
    ) : Exception(message)
}
