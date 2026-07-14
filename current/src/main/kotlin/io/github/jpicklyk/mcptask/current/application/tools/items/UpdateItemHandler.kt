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
     * @param items JSON array of item objects with `id` (required) and optional fields to update
     * @param context The tool execution context providing repository access
     * @return A JSON response envelope with updated item IDs, timestamps, counts, and any failures
     */
    suspend fun execute(
        items: JsonArray,
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

                itemId = extractItemString(itemObj, "id")
                    ?: throw ToolValidationException("Update item: 'id' is required")

                val id = resolveWorkItemIdString(itemId, context, "Update item: 'id'")

                // Fetch existing item
                val existing =
                    when (val getResult = repo.getById(id)) {
                        is Result.Success -> getResult.data
                        is Result.Error -> throw ToolValidationException(
                            "Item '$itemId' not found: ${getResult.error.message}"
                        )
                    }

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
                val traitsStr = extractItemString(itemObj, "traits")
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

                // Handle parentId change and depth/rootId recomputation
                val parentIdStr = extractItemString(itemObj, "parentId")
                val newParentId: UUID?
                val newDepth: Int
                val newRootId: UUID?
                if (parentIdStr != null) {
                    newParentId = resolveWorkItemIdString(parentIdStr, context, "Item '$itemId': 'parentId'")

                    newDepth =
                        hierarchyValidator.validateAndComputeDepth(
                            itemId = id,
                            parentId = newParentId,
                            repo = repo,
                            errorPrefix = "Item '$itemId'"
                        )

                    // rootId: inherit the new parent's root (or the parent's own id, if the
                    // parent predates the root_id backfill and has no rootId yet).
                    newRootId =
                        when (val parentResult = repo.getById(newParentId)) {
                            is Result.Success -> parentResult.data.rootId ?: parentResult.data.id
                            is Result.Error -> throw ToolValidationException(
                                "Item '$itemId': parent '$newParentId' not found"
                            )
                        }
                } else if (itemObj.containsKey("parentId") && itemObj["parentId"] is JsonNull) {
                    // Explicitly set parentId to null (move to root) — the item becomes its own root.
                    newParentId = null
                    newDepth = 0
                    newRootId = id
                } else {
                    // No parentId change
                    newParentId = existing.parentId
                    newDepth = existing.depth
                    newRootId = existing.rootId
                }

                // Apply partial update using the update builder for monotonic modifiedAt
                val updatedItem =
                    existing.update { item ->
                        item.copy(
                            parentId = newParentId,
                            rootId = newRootId,
                            title = newTitle ?: item.title,
                            description = newDescription,
                            summary = newSummary ?: item.summary,
                            role = item.role,
                            statusLabel = newStatusLabel,
                            priority = newPriority ?: item.priority,
                            complexity = newComplexity ?: item.complexity,
                            requiresVerification = newRequiresVerification ?: item.requiresVerification,
                            depth = newDepth,
                            metadata = newMetadata,
                            tags = newTags,
                            type = newType,
                            properties = newProperties
                        )
                    }

                // When the parent actually changes, the item's own write and the
                // descendant-depth/rootId cascade must succeed or fail together — wrap both in a
                // shared transaction so a cascade failure (e.g. a version-mismatch conflict on a
                // descendant) rolls back the parent's own write too, rather than leaving the tree
                // half-updated. Gated on parentId change rather than depthDelta != 0: moving an
                // item between two different root subtrees at the same depth leaves depth
                // unchanged but still requires a rootId cascade over every descendant.
                val depthDelta = newDepth - existing.depth
                val parentChanged = newParentId != existing.parentId
                var updateResult: Result<WorkItem>? = null
                if (parentChanged) {
                    repo.inTransaction {
                        val txResult = repo.update(updatedItem)
                        updateResult = txResult
                        if (txResult is Result.Success) {
                            // newRootId is always non-null on this branch: parentChanged is only
                            // true when either the parent-lookup branch (always non-null) or the
                            // move-to-root branch (= id) set it — never the unchanged branch. The
                            // `?: id` fallback exists only to satisfy the nullable type, not as a
                            // reachable behavior.
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
                } else {
                    updateResult = repo.update(updatedItem)
                }

                when (val result = updateResult!!) {
                    is Result.Success -> {
                        updatedItems.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(result.data.id.toString()))
                                put("modifiedAt", JsonPrimitive(result.data.modifiedAt.toString()))
                                put("requiresVerification", JsonPrimitive(result.data.requiresVerification))
                            }
                        )
                    }
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(itemId))
                                put("error", JsonPrimitive(result.error.message))
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
     * Internal marker exception used to abort the shared [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.inTransaction]
     * block when the descendant-depth cascade fails after the parent's own depth write succeeded.
     * Caught by the per-item `catch (e: Exception)` block above and converted into a failure entry;
     * never surfaced past [execute].
     */
    private class DepthCascadeException(
        message: String
    ) : Exception(message)
}
