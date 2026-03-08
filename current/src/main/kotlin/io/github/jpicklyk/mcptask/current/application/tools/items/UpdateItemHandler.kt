package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
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
    suspend fun execute(items: JsonArray, context: ToolExecutionContext): JsonElement {
        val repo = context.workItemRepository()

        val updatedItems = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()

        for (element in items) {
            var itemId: String? = null
            try {
                val itemObj = element as? JsonObject
                    ?: throw ToolValidationException("Each update item must be a JSON object")

                itemId = extractItemString(itemObj, "id")
                    ?: throw ToolValidationException("Update item: 'id' is required")

                val id = try {
                    UUID.fromString(itemId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Update item: 'id' is not a valid UUID: $itemId")
                }

                // Fetch existing item
                val existing = when (val getResult = repo.getById(id)) {
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

                // Parse priority if provided
                val newPriority = if (newPriorityStr != null) {
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

                // Handle parentId change and depth recomputation
                val parentIdStr = extractItemString(itemObj, "parentId")
                val newParentId: UUID?
                val newDepth: Int
                if (parentIdStr != null) {
                    newParentId = try {
                        UUID.fromString(parentIdStr)
                    } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("Item '$itemId': 'parentId' is not a valid UUID")
                    }

                    newDepth = hierarchyValidator.validateAndComputeDepth(
                        itemId = id,
                        parentId = newParentId,
                        repo = repo,
                        errorPrefix = "Item '$itemId'"
                    )
                } else if (itemObj.containsKey("parentId") && itemObj["parentId"] is JsonNull) {
                    // Explicitly set parentId to null (move to root)
                    newParentId = null
                    newDepth = 0
                } else {
                    // No parentId change
                    newParentId = existing.parentId
                    newDepth = existing.depth
                }

                // Apply partial update using the update builder for monotonic modifiedAt
                val updatedItem = existing.update { item ->
                    item.copy(
                        parentId = newParentId,
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
                        tags = newTags
                    )
                }

                when (val result = repo.update(updatedItem)) {
                    is Result.Success -> {
                        updatedItems.add(buildJsonObject {
                            put("id", JsonPrimitive(result.data.id.toString()))
                            put("modifiedAt", JsonPrimitive(result.data.modifiedAt.toString()))
                            put("requiresVerification", JsonPrimitive(result.data.requiresVerification))
                        })
                    }
                    is Result.Error -> {
                        failures.add(buildJsonObject {
                            put("id", JsonPrimitive(itemId))
                            put("error", JsonPrimitive(result.error.message))
                        })
                    }
                }
            } catch (e: ToolValidationException) {
                failures.add(buildJsonObject {
                    put("id", JsonPrimitive(itemId ?: "unknown"))
                    put("error", JsonPrimitive(e.message ?: "Validation failed"))
                })
            } catch (e: Exception) {
                failures.add(buildJsonObject {
                    put("id", JsonPrimitive(itemId ?: "unknown"))
                    put("error", JsonPrimitive(e.message ?: "Unexpected error"))
                })
            }
        }

        val data = buildJsonObject {
            put("items", JsonArray(updatedItems))
            put("updated", JsonPrimitive(updatedItems.size))
            put("failed", JsonPrimitive(failures.size))
            if (failures.isNotEmpty()) {
                put("failures", JsonArray(failures))
            }
        }

        return ResponseUtil.createSuccessResponse(data)
    }
}
