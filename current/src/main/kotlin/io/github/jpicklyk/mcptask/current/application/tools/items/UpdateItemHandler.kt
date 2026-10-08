package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.service.PlacedWriteOutcome
import io.github.jpicklyk.mcptask.current.application.service.ReparentCheck
import io.github.jpicklyk.mcptask.current.application.service.WorkItemPlacementService
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Handles the `update` operation for [ManageItemsTool].
 *
 * Supports partial updates of existing WorkItems. Only provided fields are changed;
 * omitted fields retain their existing values. Parent changes trigger depth recomputation
 * with full cycle detection.
 *
 * Three-way parentId branching:
 * 1. Non-null parentId string -> validate and recompute depth
 * 2. Explicit JSON null -> move to root (depth = 0)
 * 3. Absent -> no change (keep existing parent and depth)
 */
class UpdateItemHandler(
    private val hierarchyValidator: ItemHierarchyValidator = ItemHierarchyValidator()
) {
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
        context: ToolExecutionContext
    ): JsonElement {
        val repo = context.workItemRepository()

        val updatedItems = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()

        for (element in items) {
            var itemId: String? = null
            try {
                val itemObj =
                    element as? JsonObject
                        ?: throw ToolValidationException("Each update item must be a JSON object")

                itemId = extractItemString(itemObj, "itemId")
                    ?: throw ToolValidationException("Update item: 'itemId' is required")

                val id = resolveWorkItemIdString(itemId, context, "Update item: 'itemId'")

                // Fetch existing item
                val existing =
                    legacyRead({ throw IllegalStateException(it) }) { repo.getById(id) }
                        ?: throw ToolValidationException("Item '$itemId' not found: WorkItem not found with id: $id")

                val spec = parseUpdateFields(itemObj, itemId, id, existing, sharedTraits, context, repo)
                when (val updateResult = persistWithPlacement(id, itemId, existing, spec, repo, context.unitOfWork)) {
                    is PersistedUpdate.Written -> {
                        updatedItems.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(updateResult.item.id.toString()))
                                put("modifiedAt", JsonPrimitive(updateResult.item.modifiedAt.toString()))
                                put("requiresVerification", JsonPrimitive(updateResult.item.requiresVerification))
                            }
                        )
                    }
                    is PersistedUpdate.Failed -> {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(itemId))
                                put("error", JsonPrimitive(updateResult.message))
                            }
                        )
                    }
                }
            } catch (e: ToolValidationException) {
                failures.add(
                    buildJsonObject {
                        put("id", JsonPrimitive(itemId ?: "unknown"))
                        put("error", JsonPrimitive(e.message ?: "Validation failed"))
                    }
                )
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
     * A single item's parsed and validated partial-update fields, plus whether the parent
     * changed. Depth/rootId are deliberately NOT part of this spec — they are resolved fresh
     * inside the write transaction in [persistWithPlacement] (AR-19).
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
        val parentChanged: Boolean
    )

    /**
     * Extracts and validates all optional partial-update fields for one update item: the
     * role-change rejection, priority/complexity parsing, and the three-way parentId
     * resolution + hierarchy guards (self-parent, ancestor cycle). Mirrors the pre-refactor
     * inline logic byte-for-byte, including error messages.
     */
    private suspend fun parseUpdateFields(
        itemObj: JsonObject,
        itemId: String,
        id: UUID,
        existing: WorkItem,
        sharedTraits: String?,
        context: ToolExecutionContext,
        repo: WorkItemRepository
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

        // Handle parentId change. Depth/rootId are resolved from the CURRENT parent state
        // below — inside the same transaction as the write when the parent actually
        // changes, so a concurrent reparent/delete of the new parent cannot leave this
        // item stamped with stale placement (AR-19).
        val parentIdStr = extractItemString(itemObj, "parentId")
        val explicitNullParent = itemObj.containsKey("parentId") && itemObj["parentId"] is JsonNull
        val newParentId: UUID? =
            when {
                parentIdStr != null -> resolveWorkItemIdString(parentIdStr, context, "Item '$itemId': 'parentId'")
                explicitNullParent -> null
                else -> existing.parentId
            }

        if (parentIdStr != null && newParentId != null) {
            // Guard checks only (existence, self-parent, descendant cycle); fails CLOSED when the
            // ancestor lookup errors. Placement is resolved inside the write transaction.
            when (val check = WorkItemPlacementService(repo, hierarchyValidator).checkReparent(id, newParentId)) {
                ReparentCheck.Ok -> {}
                ReparentCheck.SelfParent ->
                    throw ToolValidationException("Item '$itemId': cannot be its own parent")
                ReparentCheck.DescendantCycle ->
                    throw ToolValidationException(
                        "Item '$itemId': reparenting to '$newParentId' would create a circular hierarchy"
                    )
                is ReparentCheck.ParentNotFound ->
                    throw ToolValidationException("Item '$itemId': parent '$newParentId' not found")
                is ReparentCheck.LookupFailed ->
                    throw ToolValidationException(
                        "Item '$itemId': failed to verify hierarchy for parent '$newParentId': ${check.message}"
                    )
            }
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
            parentChanged = newParentId != existing.parentId
        )
    }

    /**
     * Writes the update via [WorkItemPlacementService.update]: when the parent actually changes,
     * placement resolution, the item's own row and the descendant depth/rootId cascade all run
     * inside ONE transaction (AR-19), and a cascade failure rolls back the item's own write too.
     */
    private suspend fun persistWithPlacement(
        id: UUID,
        itemId: String,
        existing: WorkItem,
        spec: ParsedUpdateSpec,
        repo: WorkItemRepository,
        unitOfWork: UnitOfWork
    ): PersistedUpdate {
        // Builds the fully-updated WorkItem given a resolved placement, applying all the
        // other partial-update fields extracted above via the update builder (monotonic
        // modifiedAt).
        fun buildUpdatedItem(
            depth: Int,
            rootId: UUID?
        ): WorkItem =
            existing.update { item ->
                item.copy(
                    parentId = spec.newParentId,
                    rootId = rootId,
                    title = spec.newTitle ?: item.title,
                    description = spec.newDescription,
                    summary = spec.newSummary ?: item.summary,
                    role = item.role,
                    statusLabel = spec.newStatusLabel,
                    priority = spec.newPriority ?: item.priority,
                    complexity = spec.newComplexity ?: item.complexity,
                    requiresVerification = spec.newRequiresVerification ?: item.requiresVerification,
                    depth = depth,
                    metadata = spec.newMetadata,
                    tags = spec.newTags,
                    type = spec.newType,
                    properties = spec.newProperties
                )
            }

        return when (
            val outcome =
                WorkItemPlacementService(repo, hierarchyValidator)
                    .update(unitOfWork, existing, spec.newParentId, spec.parentChanged) { depth, rootId ->
                        buildUpdatedItem(depth, rootId)
                    }
        ) {
            is PlacedWriteOutcome.Written -> PersistedUpdate.Written(outcome.item)
            is PlacedWriteOutcome.ParentNotFound ->
                throw ToolValidationException("Item '$itemId': parent '${outcome.parentId}' not found")
            is PlacedWriteOutcome.BuildFailed -> throw ToolValidationException(outcome.message)
            is PlacedWriteOutcome.CascadeFailed ->
                throw ToolValidationException("Item '$itemId': failed to update descendant depths: ${outcome.message}")
            is PlacedWriteOutcome.WriteFailed -> PersistedUpdate.Failed(LegacyFaults.message(outcome.error))
        }
    }

    /** The per-item result of [persistWithPlacement]: the written item, or the legacy failure message. */
    private sealed interface PersistedUpdate {
        data class Written(
            val item: WorkItem
        ) : PersistedUpdate

        data class Failed(
            val message: String
        ) : PersistedUpdate
    }
}
