package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
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
                    when (val getResult = repo.getById(id)) {
                        is Result.Success -> getResult.data
                        is Result.Error -> throw ToolValidationException(
                            "Item '$itemId' not found: ${getResult.error.message}"
                        )
                    }

                val spec = parseUpdateFields(itemObj, itemId, id, existing, sharedTraits, context, repo)
                val updateResult = persistWithPlacement(id, itemId, existing, spec, repo)

                when (updateResult) {
                    is Result.Success -> {
                        updatedItems.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(updateResult.data.id.toString()))
                                put("modifiedAt", JsonPrimitive(updateResult.data.modifiedAt.toString()))
                                put("requiresVerification", JsonPrimitive(updateResult.data.requiresVerification))
                            }
                        )
                    }
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(itemId))
                                put("error", JsonPrimitive(updateResult.error.message))
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

        if (parentIdStr != null) {
            // Guard checks only (self-parent, ancestor cycle) — the returned depth is
            // NOT used to stamp; placement is resolved fresh inside the write transaction.
            hierarchyValidator.validateAndComputeDepth(
                itemId = id,
                parentId = newParentId,
                repo = repo,
                errorPrefix = "Item '$itemId'"
            )
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
     * When the parent actually changes, resolves the new placement, writes the item's own
     * row, and cascades descendant depth/rootId all inside ONE transaction: resolving
     * placement inside the same transaction as the write protects against a concurrent
     * reparent/delete of the new parent (AR-19), and the cascade must roll back together
     * with the parent's own write on failure (a thrown [DepthCascadeException] aborts the
     * transaction and propagates to the per-item catch in [execute]). Mirrors the
     * pre-refactor inline logic byte-for-byte.
     */
    private suspend fun persistWithPlacement(
        id: UUID,
        itemId: String,
        existing: WorkItem,
        spec: ParsedUpdateSpec,
        repo: WorkItemRepository
    ): Result<WorkItem> {
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

        var updateResult: Result<WorkItem>? = null
        var placementNotFoundMessage: String? = null
        if (spec.parentChanged) {
            repo.inTransaction {
                val newDepth: Int
                val newRootId: UUID?
                if (spec.newParentId != null) {
                    when (val placementResult = repo.resolveChildPlacement(spec.newParentId)) {
                        is Result.Success -> {
                            newDepth = placementResult.data.depth
                            newRootId = placementResult.data.rootId
                        }
                        is Result.Error -> {
                            placementNotFoundMessage = "Item '$itemId': parent '${spec.newParentId}' not found"
                            return@inTransaction
                        }
                    }
                } else {
                    // Explicit move-to-root — no parent to read.
                    newDepth = 0
                    newRootId = id
                }

                val updatedItem = buildUpdatedItem(newDepth, newRootId)
                val depthDelta = newDepth - existing.depth
                val txResult = repo.update(updatedItem)
                updateResult = txResult
                if (txResult is Result.Success) {
                    when (
                        val cascadeResult =
                            hierarchyValidator.recomputeDescendantDepths(id, depthDelta, newRootId ?: id, repo)
                    ) {
                        is Result.Success -> {}
                        is Result.Error -> throw DepthCascadeException(
                            "Item '$itemId': failed to update descendant depths: ${cascadeResult.error.message}"
                        )
                    }
                }
            }
            if (placementNotFoundMessage != null) {
                throw ToolValidationException(placementNotFoundMessage!!)
            }
        } else {
            val updatedItem = buildUpdatedItem(existing.depth, existing.rootId)
            updateResult = repo.update(updatedItem)
        }
        return updateResult!!
    }

    /**
     * Internal marker exception used to abort the shared [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.inTransaction]
     * block when the descendant-depth cascade fails after the parent's own depth write succeeded.
     * Caught by the per-item `catch (e: Exception)` block above and converted into a failure entry;
     * never surfaced past [execute].
     */
    private class DepthCascadeException(
        message: String
    ) : Exception(message)
}
