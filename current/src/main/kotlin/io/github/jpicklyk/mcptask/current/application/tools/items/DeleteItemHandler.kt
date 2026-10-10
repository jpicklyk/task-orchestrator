package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.tools.ElementOutcome
import io.github.jpicklyk.mcptask.current.application.tools.ElementResult
import io.github.jpicklyk.mcptask.current.application.tools.KeyedCall
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.putElementError
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.application.tools.runElement
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.serialization.json.*

/**
 * Handles the `delete` operation for [ManageItemsTool].
 *
 * Supports both direct deletion and recursive deletion of item hierarchies.
 * When `recursive` is true, descendants are deleted leaves-first to satisfy
 * foreign key constraints. Per-id semantics (children guard, lease release before delete,
 * atomic all-or-nothing recursive subtree delete, the old parent's cascade re-evaluation) live in
 * [io.github.jpicklyk.mcptask.current.application.service.ItemCommandService.delete], shared with the
 * REST `DELETE /api/v1/items/{id}` route so both surfaces behave identically. A delete that cascades the
 * parent reports it on the element as `cascadeEvents` (only when non-empty).
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
        val deletedIds = mutableListOf<String>()
        var descendantsDeleted = 0
        // The delete response has no per-element array, so each element's cascades are reported in one list.
        val cascadeEvents = mutableListOf<JsonElement>()
        val failures = mutableListOf<JsonObject>()

        for ((index, element) in idsArray.withIndex()) {
            val idStr = (element as? JsonPrimitive)?.content
            if (idStr == null) {
                failures.add(deleteFailure("null", "Each ID must be a string", ErrorCode.INVALID_REQUEST))
                continue
            }

            val id =
                try {
                    resolveWorkItemIdString(idStr, context, "'id'")
                } catch (e: ToolValidationException) {
                    failures.add(deleteFailure(idStr, e.message ?: "Invalid ID: $idStr", e.errorCode))
                    continue
                }

            // A delete is recorded only once it committed: every failure rolls the element back and is
            // reported unrecorded, so a retry with the same key runs the delete again.
            val outcome =
                runElement(keyed, index, element, onError = { deleteFailure(idStr, it.message, it.code) }) {
                    when (val deleted = context.itemCommandService.delete(id, recursive)) {
                        is Outcome.Ok ->
                            ElementResult.Done(
                                buildJsonObject {
                                    put("id", JsonPrimitive(idStr))
                                    put("descendantsDeleted", JsonPrimitive(deleted.value.descendantsDeleted))
                                    cascadeEventsJson(deleted.value.cascadeEvents)?.let { put("cascadeEvents", it) }
                                }
                            )
                        is Outcome.Err -> {
                            val error = deleted.error
                            val childCount = ItemCommandErrors.childCount(error)
                            when {
                                childCount != null ->
                                    ElementResult.Failed(
                                        deleteFailure(
                                            idStr,
                                            "Item '$idStr' has $childCount child item(s). " +
                                                "Use recursive=true to delete the item and all its descendants.",
                                            error.code
                                        )
                                    )
                                error.code == ErrorCode.NOT_FOUND ->
                                    ElementResult.Failed(
                                        deleteFailure(idStr, "Item '$idStr' not found", ErrorCode.NOT_FOUND)
                                    )
                                else -> ElementResult.Failed(deleteFailure(idStr, LegacyFaults.message(error), error.code))
                            }
                        }
                    }
                }
            when (outcome) {
                is ElementOutcome.Succeeded -> {
                    deletedIds.add(idStr)
                    descendantsDeleted += (outcome.fragment["descendantsDeleted"] as? JsonPrimitive)?.intOrNull ?: 0
                    (outcome.fragment["cascadeEvents"] as? JsonArray)?.let { cascadeEvents.addAll(it) }
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
                if (cascadeEvents.isNotEmpty()) {
                    put("cascadeEvents", JsonArray(cascadeEvents))
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
        code: ErrorCode
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("error", JsonPrimitive(message))
            putElementError(code)
        }
}
