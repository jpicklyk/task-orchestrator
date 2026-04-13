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
                // Find all descendants, delete leaves-first, then the root
                val descendantsResult = repo.findDescendants(id)
                if (descendantsResult is Result.Error) {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put("error", JsonPrimitive("Failed to find descendants: ${descendantsResult.error.message}"))
                        }
                    )
                    continue
                }
                val descendants = (descendantsResult as Result.Success).data
                if (descendants.isNotEmpty()) {
                    // Sort leaves-first (deepest depth first) so FK constraints are satisfied.
                    // Delete individually to ensure each row is removed before referencing parents
                    // are removed (batch DELETE can trigger FK violations mid-statement).
                    val sortedDescendants = descendants.sortedByDescending { it.depth }
                    var descendantDeleteFailed = false
                    for (descendant in sortedDescendants) {
                        when (val delResult = repo.delete(descendant.id)) {
                            is Result.Success -> if (delResult.data) descendantsDeleted++
                            is Result.Error -> {
                                failures.add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(idStr))
                                        put(
                                            "error",
                                            JsonPrimitive("Failed to delete descendant ${descendant.id}: ${delResult.error.message}")
                                        )
                                    }
                                )
                                descendantDeleteFailed = true
                                break
                            }
                        }
                    }
                    if (descendantDeleteFailed) continue
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
}
