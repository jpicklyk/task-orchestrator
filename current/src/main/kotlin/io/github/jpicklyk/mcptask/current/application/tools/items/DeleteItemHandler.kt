package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ElementOutcome
import io.github.jpicklyk.mcptask.current.application.tools.ElementResult
import io.github.jpicklyk.mcptask.current.application.tools.KeyedCall
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.application.tools.runElement
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
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
        context: ToolExecutionContext,
        keyed: KeyedCall? = null
    ): JsonElement {
        val deletion = WorkItemDeletion(context.repositoryProvider, context.unitOfWork)

        val deletedIds = mutableListOf<String>()
        var descendantsDeleted = 0
        val failures = mutableListOf<JsonObject>()

        for ((index, element) in idsArray.withIndex()) {
            val idStr = (element as? JsonPrimitive)?.content
            if (idStr == null) {
                failures.add(deleteFailure("null", "Each ID must be a string"))
                continue
            }

            val id =
                try {
                    resolveWorkItemIdString(idStr, context, "'id'")
                } catch (e: ToolValidationException) {
                    failures.add(deleteFailure(idStr, e.message ?: "Invalid ID: $idStr"))
                    continue
                }

            // A delete is recorded only once it committed: every failure rolls the element back and is
            // reported unrecorded, so a retry with the same key runs the delete again.
            val outcome =
                runElement(keyed, index, element, onError = { deleteFailure(idStr, it.message, it.code) }) {
                    when (val deleted = deletion.delete(id, recursive)) {
                        is WorkItemDeleteOutcome.Deleted ->
                            ElementResult.Done(
                                buildJsonObject {
                                    put("id", JsonPrimitive(idStr))
                                    put("descendantsDeleted", JsonPrimitive(deleted.descendantsDeleted))
                                }
                            )
                        is WorkItemDeleteOutcome.HasChildren ->
                            ElementResult.Failed(
                                deleteFailure(
                                    idStr,
                                    "Item '$idStr' has ${deleted.childCount} child item(s). " +
                                        "Use recursive=true to delete the item and all its descendants."
                                )
                            )
                        is WorkItemDeleteOutcome.NotFound -> ElementResult.Failed(deleteFailure(idStr, "Item '$idStr' not found"))
                        is WorkItemDeleteOutcome.Failed -> ElementResult.Failed(deleteFailure(idStr, deleted.message))
                    }
                }
            when (outcome) {
                is ElementOutcome.Succeeded -> {
                    deletedIds.add(idStr)
                    descendantsDeleted += (outcome.fragment["descendantsDeleted"] as? JsonPrimitive)?.intOrNull ?: 0
                }
                is ElementOutcome.Failed -> failures.add(outcome.failure)
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

    private fun deleteFailure(
        id: String,
        message: String,
        code: ErrorCode? = null
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("error", JsonPrimitive(message))
            if (code == ErrorCode.IDEMPOTENCY_MISMATCH) put("errorCode", JsonPrimitive(KeyedCall.IDEMPOTENCY_MISMATCH_CODE))
        }
}
