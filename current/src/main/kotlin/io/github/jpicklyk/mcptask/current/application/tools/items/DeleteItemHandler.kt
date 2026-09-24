package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import kotlinx.serialization.json.*

/**
 * Handles the `delete` operation for [ManageItemsTool].
 *
 * Supports both direct deletion and recursive deletion of item hierarchies.
 * When `recursive` is true, descendants are deleted leaves-first to satisfy
 * foreign key constraints. Per-id semantics (children guard, lease release before delete,
 * atomic all-or-nothing recursive subtree delete) live in [WorkItemDeletion], shared with the
 * REST `DELETE /api/v1/items/{id}` route so both surfaces behave identically.
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
        val deletion = WorkItemDeletion(context.repositoryProvider)

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

            when (val outcome = deletion.delete(id, recursive)) {
                is WorkItemDeleteOutcome.Deleted -> {
                    deletedIds.add(idStr)
                    descendantsDeleted += outcome.descendantsDeleted
                }
                is WorkItemDeleteOutcome.HasChildren -> {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put(
                                "error",
                                JsonPrimitive(
                                    "Item '$idStr' has ${outcome.childCount} child item(s). " +
                                        "Use recursive=true to delete the item and all its descendants."
                                )
                            )
                        }
                    )
                }
                is WorkItemDeleteOutcome.NotFound -> {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put("error", JsonPrimitive("Item '$idStr' not found"))
                        }
                    )
                }
                is WorkItemDeleteOutcome.Failed -> {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put("error", JsonPrimitive(outcome.message))
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
