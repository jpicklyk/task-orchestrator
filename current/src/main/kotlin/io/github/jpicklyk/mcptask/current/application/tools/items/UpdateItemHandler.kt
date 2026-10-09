package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.application.service.ItemPatchCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.tools.ElementOutcome
import io.github.jpicklyk.mcptask.current.application.tools.ElementResult
import io.github.jpicklyk.mcptask.current.application.tools.KeyedCall
import io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.application.tools.runElement
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Handles the `update` operation for [ManageItemsTool].
 *
 * Supports partial updates of existing WorkItems. Only provided fields are changed; omitted fields retain their
 * existing values. The merged values become an [ItemPatchCommand] for
 * [io.github.jpicklyk.mcptask.current.application.service.ItemCommandService.patch], which owns the hierarchy
 * guards (self-parent, own-descendant, closed parent), the placement read, the descendant restamp and the old
 * parent's cascade re-evaluation, all in one unit. A reparent's cascades are reported on the element as
 * `cascadeEvents` (only when non-empty).
 *
 * Three-way parentId branching:
 * 1. Non-null parentId string -> move under that parent
 * 2. Explicit JSON null -> move to root (depth = 0)
 * 3. Absent -> no change (keep existing parent and depth)
 */
class UpdateItemHandler {
    /**
     * Executes a batch update of WorkItems.
     *
     * @param items JSON array of item objects with `itemId` (required) and optional fields to update
     * @param context The tool execution context providing repository access
     * @return A JSON response envelope with updated item IDs, timestamps, counts, and any failures
     */
    suspend fun execute(
        items: JsonArray,
        sharedTraits: String?,
        context: ToolExecutionContext,
        keyed: KeyedCall? = null
    ): JsonElement {
        val repo = context.workItemRepository()

        val updatedItems = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()

        for ((index, element) in items.withIndex()) {
            var itemId: String? = null
            try {
                val outcome =
                    runElement(keyed, index, element, onError = { updateFailure(null, it.message, it.code) }) {
                        val itemObj = element as? JsonObject
                        if (itemObj == null) {
                            val message = "Each update item must be a JSON object"
                            ElementResult.Invalid(updateFailure(null, message, null), message)
                        } else {
                            val itemIdStr =
                                extractItemString(itemObj, "itemId")
                                    ?: throw ToolValidationException("Update item: 'itemId' is required")
                            itemId = itemIdStr

                            val id = resolveWorkItemIdString(itemIdStr, context, "Update item: 'itemId'")

                            // Fetch existing item
                            val existing =
                                legacyRead({ throw IllegalStateException(it) }) { repo.getById(id) }
                                    ?: throw ToolValidationException("Item '$itemIdStr' not found: WorkItem not found with id: $id")

                            val spec = parseUpdateFields(itemObj, itemIdStr, existing, sharedTraits, context)
                            persist(itemIdStr, existing, spec, context)
                        }
                    }
                when (outcome) {
                    is ElementOutcome.Succeeded -> updatedItems.add(outcome.fragment)
                    is ElementOutcome.Failed -> failures.add(outcome.failure)
                }
            } catch (e: ToolValidationException) {
                failures.add(
                    buildJsonObject {
                        put("id", JsonPrimitive(itemId ?: "unknown"))
                        put("error", JsonPrimitive(e.message ?: "Validation failed"))
                    }
                )
            } catch (e: PerRootConfigUnavailableException) {
                // A per-root config fault resolving a TERMINAL new parent's lifecycle fails only this element.
                failures.add(configUnavailableFailure("id", JsonPrimitive(itemId ?: "unknown"), e))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                failures.add(
                    buildJsonObject {
                        put("id", JsonPrimitive(itemId ?: "unknown"))
                        put("error", JsonPrimitive(e.message ?: "Unexpected error"))
                    }
                )
            }
        }

        val data =
            buildJsonObject {
                put("items", JsonArray(updatedItems))
                put("updated", JsonPrimitive(updatedItems.size))
                put("failed", JsonPrimitive(failures.size))
                if (failures.isNotEmpty()) {
                    put("failures", JsonArray(failures))
                }
            }

        return ResponseUtil.createSuccessResponse(data)
    }

    private fun updateFailure(
        itemId: String?,
        message: String,
        code: ErrorCode?
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(itemId ?: "unknown"))
            put("error", JsonPrimitive(message))
            if (code == ErrorCode.IDEMPOTENCY_MISMATCH) put("errorCode", JsonPrimitive(KeyedCall.IDEMPOTENCY_MISMATCH_CODE))
        }

    /**
     * Extracts the per-item "traits" override, distinguishing an absent field from a
     * present-but-blank one.
     *
     * Unlike [extractItemString] (which squashes blank strings to null for every other field so
     * that "not provided" and "provided empty" collapse to the same no-op), an explicit blank
     * `traits: ""` on update is the caller's signal to clear all traits — it must reach
     * [PropertiesHelper.mergeTraitsFromString] as `""` (which resolves to an empty trait list and
     * replaces the traits key), not as `null` (which means "leave traits untouched"). Returns null
     * only when the key is absent or not a JSON string, so the caller can still fall back to
     * `sharedTraits` exactly when the per-item field was never provided.
     */
    private fun extractTraitsOverride(obj: JsonObject): String? {
        val value = obj["traits"] as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }

    /**
     * A single item's parsed and validated partial-update fields, plus the parent change. Depth/rootId are
     * deliberately NOT part of this spec: the service resolves them inside the write unit (AR-19).
     */
    private data class ParsedUpdateSpec(
        val newTitle: String?,
        val newDescription: String?,
        val newSummary: String?,
        val newStatusLabel: String?,
        val newPriority: Priority?,
        val newComplexity: Int?,
        val newRequiresVerification: Boolean?,
        val newMetadata: String?,
        val newTags: String?,
        val newType: String?,
        val newProperties: String?,
        val newParentId: UUID?,
        val parent: ParentChange
    )

    /**
     * Extracts and validates all optional partial-update fields for one update item: the role-change rejection,
     * priority/complexity parsing, and the three-way parentId resolution. The hierarchy guards run in the service.
     */
    private suspend fun parseUpdateFields(
        itemObj: JsonObject,
        itemId: String,
        existing: WorkItem,
        sharedTraits: String?,
        context: ToolExecutionContext
    ): ParsedUpdateSpec {
        // Extract optional fields
        val newTitle = extractItemString(itemObj, "title")
        val newDescription = extractItemStringAllowNull(itemObj, "description", existing.description)
        val newSummary = extractItemString(itemObj, "summary")

        // Reject role field in updates — all role changes must go through advance_item
        if (itemObj.containsKey("role")) {
            throw ToolValidationException(
                "Item '$itemId': role changes are not allowed via manage_items update. " +
                    "Use advance_item with an appropriate trigger instead (start, complete, block, hold, resume, cancel, reopen)."
            )
        }

        val newStatusLabel = extractItemStringAllowNull(itemObj, "statusLabel", existing.statusLabel)
        val newPriorityStr = extractItemString(itemObj, "priority")
        val newComplexity = extractItemInt(itemObj, "complexity")
        val newRequiresVerification = extractItemBoolean(itemObj, "requiresVerification")
        val newMetadata = extractItemStringAllowNull(itemObj, "metadata", existing.metadata)
        val newTags = extractItemStringAllowNull(itemObj, "tags", existing.tags)
        val newType = extractItemStringAllowNull(itemObj, "type", existing.type)
        val rawNewProperties = extractItemStringAllowNull(itemObj, "properties", existing.properties)
        val traitsStr = extractTraitsOverride(itemObj) ?: sharedTraits
        val newProperties = PropertiesHelper.mergeTraitsFromString(rawNewProperties, traitsStr)

        // Parse priority if provided
        val newPriority =
            if (newPriorityStr != null) {
                Priority.fromString(newPriorityStr)
                    ?: throw ToolValidationException(
                        "Item '$itemId': invalid priority '$newPriorityStr'. Valid: high, medium, low"
                    )
            } else {
                null
            }

        // Validate complexity if provided
        if (newComplexity != null && newComplexity !in 1..10) {
            throw ToolValidationException("Item '$itemId': complexity must be between 1 and 10")
        }

        // Handle parentId change. Depth/rootId are resolved from the CURRENT parent state inside the write unit
        // (AR-19); the existence, self-parent, own-descendant and closed-parent guards run there too.
        val parentIdStr = extractItemString(itemObj, "parentId")
        val explicitNullParent = itemObj.containsKey("parentId") && itemObj["parentId"] is JsonNull
        val newParentId: UUID? =
            when {
                parentIdStr != null -> resolveWorkItemIdString(parentIdStr, context, "Item '$itemId': 'parentId'")
                explicitNullParent -> null
                else -> existing.parentId
            }
        val parent =
            when {
                parentIdStr != null && newParentId != null -> ParentChange.MoveUnder(newParentId)
                explicitNullParent -> ParentChange.MoveToRoot
                else -> ParentChange.Keep
            }

        return ParsedUpdateSpec(
            newTitle = newTitle,
            newDescription = newDescription,
            newSummary = newSummary,
            newStatusLabel = newStatusLabel,
            newPriority = newPriority,
            newComplexity = newComplexity,
            newRequiresVerification = newRequiresVerification,
            newMetadata = newMetadata,
            newTags = newTags,
            newType = newType,
            newProperties = newProperties,
            newParentId = newParentId,
            parent = parent
        )
    }

    /**
     * Writes the update through the item command service. The final field values are merged against [existing]
     * (read before the unit), so the patch carries [existing]'s version: a concurrent write in between loses the
     * race as a version conflict instead of being silently overwritten.
     */
    private suspend fun persist(
        itemId: String,
        existing: WorkItem,
        spec: ParsedUpdateSpec,
        context: ToolExecutionContext
    ): ElementResult {
        val command =
            ItemPatchCommand(
                itemId = existing.id,
                expectedVersion = existing.version,
                parent = spec.parent,
                title = spec.newTitle ?: existing.title,
                description = spec.newDescription,
                summary = spec.newSummary ?: existing.summary,
                statusLabel = spec.newStatusLabel,
                priority = spec.newPriority ?: existing.priority,
                complexity = spec.newComplexity ?: existing.complexity,
                requiresVerification = spec.newRequiresVerification ?: existing.requiresVerification,
                metadata = spec.newMetadata,
                tags = spec.newTags,
                type = spec.newType,
                properties = spec.newProperties
            )
        val newParentId = spec.newParentId
        return when (val outcome = context.itemCommandService.patch(command)) {
            is Outcome.Ok -> {
                val result = outcome.value
                ElementResult.Done(
                    buildJsonObject {
                        put("id", JsonPrimitive(result.item.id.toString()))
                        put("modifiedAt", JsonPrimitive(result.item.modifiedAt.toString()))
                        put("requiresVerification", JsonPrimitive(result.item.requiresVerification))
                        cascadeEventsJson(result.cascadeEvents)?.let { put("cascadeEvents", it) }
                    }
                )
            }
            is Outcome.Err -> {
                val error = outcome.error
                when {
                    error.code == ErrorCode.NOT_FOUND && ItemCommandErrors.notFoundId(error) != existing.id.toString() ->
                        throw ToolValidationException("Item '$itemId': parent '$newParentId' not found")
                    ItemCommandErrors.isSelfParent(error) -> throw ToolValidationException("Item '$itemId': cannot be its own parent")
                    error.code == ErrorCode.CYCLE_DETECTED ->
                        throw ToolValidationException("Item '$itemId': reparenting to '$newParentId' would create a circular hierarchy")
                    ItemCommandErrors.isHierarchyLookupFailure(error) -> throw ToolValidationException("Item '$itemId': ${error.message}")
                    ItemCommandErrors.isClosedParent(error) ->
                        ElementResult.Failed(
                            updateFailure(
                                itemId,
                                "Item '$itemId': parent '$newParentId' is terminal under auto lifecycle; " +
                                    "reopen it before moving items under it",
                                null
                            )
                        )
                    error.code == ErrorCode.INVALID_REQUEST -> throw ToolValidationException(error.message)
                    else -> ElementResult.Failed(updateFailure(itemId, LegacyFaults.message(error), null))
                }
            }
        }
    }
}
