package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import kotlinx.serialization.json.*

/**
 * Handles the `delete` operation for [ManageItemsTool].
 *
 * Supports both direct deletion and recursive deletion of item hierarchies.
 * When `recursive` is true, descendants are deleted leaves-first to satisfy
 * foreign key constraints.
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
                                when (val delResult = repo.delete(descendant.id)) {
                                    is Result.Success -> if (delResult.data) localDescendantsDeleted++
                                    is Result.Error ->
                                        throw DeleteFailureException(
                                            "Failed to delete descendant ${descendant.id}: ${delResult.error.message}"
                                        )
                                }
                            }
                        }

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

                when (val result = repo.delete(id)) {
                    is Result.Success ->
                        if (result.data) {
                            deletedIds.add(idStr)
                        } else {
                            failures.add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(idStr))
                                    put("error", JsonPrimitive("Item '$idStr' not found"))
                                }
                            )
                        }
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(idStr))
                                put("error", JsonPrimitive(result.error.message))
                            }
                        )
                    }
                }
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
