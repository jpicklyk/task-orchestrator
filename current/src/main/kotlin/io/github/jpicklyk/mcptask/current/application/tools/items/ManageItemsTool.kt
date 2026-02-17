package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool for creating, updating, and deleting WorkItems.
 *
 * Supports three operations:
 * - **create**: Batch-create WorkItems with optional shared parentId default
 * - **update**: Partial update of existing WorkItems by ID
 * - **delete**: Batch-delete WorkItems by ID array
 *
 * Depth is computed automatically: root items get depth=0, children get parent.depth+1.
 * A maximum depth of [MAX_DEPTH] is enforced to prevent unbounded nesting.
 */
class ManageItemsTool : BaseToolDefinition() {

    companion object {
        /** Maximum allowed nesting depth for WorkItems. */
        const val MAX_DEPTH = 3
    }

    override val name = "manage_items"

    override val description = """
Unified write operations for WorkItems (create, update, delete).

**Operations:**

**create** - Create WorkItems from `items` array.
- Each item: `{ title (required), description?, summary?, role?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags? }`
- Shared `parentId` at top level serves as default for all items (per-item parentId overrides)
- Depth auto-computed from parent (root=0, child=parent.depth+1, max=$MAX_DEPTH)
- Defaults: role=queue, priority=medium, complexity=5
- Response: `{ items: [{id, title, depth, role, priority}], created: N, failed: N, failures: [{index, error}] }`

**update** - Partial update from `items` array.
- Each item: `{ id (required), title?, description?, summary?, role?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags? }`
- Only provided fields are changed; omitted fields retain existing values
- If parentId changes, depth is recomputed from new parent
- Response: `{ items: [{id, modifiedAt}], updated: N, failed: N, failures: [{id, error}] }`

**delete** - Delete by `ids` array.
- Response: `{ ids: [...], deleted: N, failed: N, failures: [{id, error}] }`
""".trimIndent()

    override val category = ToolCategory.ITEM_MANAGEMENT

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = false,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("operation", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Operation: create, update, delete"))
                put("enum", JsonArray(listOf("create", "update", "delete").map { JsonPrimitive(it) }))
            })
            put("items", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Array of item objects for create/update"))
            })
            put("ids", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Array of item UUIDs for delete"))
            })
            put("parentId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Shared default parent ID for create"))
            })
            put("requiresVerification", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Whether this item requires explicit verification before completion"))
            })
        },
        required = listOf("operation")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "create" -> {
                val items = optionalJsonArray(params, "items")
                if (items == null || items.isEmpty()) {
                    throw ToolValidationException("Create operation requires a non-empty 'items' array")
                }
            }
            "update" -> {
                val items = optionalJsonArray(params, "items")
                if (items == null || items.isEmpty()) {
                    throw ToolValidationException("Update operation requires a non-empty 'items' array")
                }
            }
            "delete" -> {
                val ids = optionalJsonArray(params, "ids")
                if (ids == null || ids.isEmpty()) {
                    throw ToolValidationException("Delete operation requires a non-empty 'ids' array")
                }
            }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be create, update, or delete")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "create" -> executeCreate(params, context)
            "update" -> executeUpdate(params, context)
            "delete" -> executeDelete(params, context)
            else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        val op = (params as? JsonObject)?.get("operation")?.let {
            (it as? JsonPrimitive)?.content
        } ?: "unknown"
        val data = (result as? JsonObject)
        return when {
            isError -> "manage_items($op) failed"
            op == "create" -> {
                val count = data?.get("created")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Created $count item(s)"
            }
            op == "update" -> {
                val count = data?.get("updated")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Updated $count item(s)"
            }
            op == "delete" -> {
                val count = data?.get("deleted")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Deleted $count item(s)"
            }
            else -> super.userSummary(params, result, isError)
        }
    }

    // ──────────────────────────────────────────────
    // Create operation
    // ──────────────────────────────────────────────

    private suspend fun executeCreate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val items = requireJsonArray(params, "items")
        val sharedParentId = extractUUID(params, "parentId", required = false)
        val repo = context.workItemRepository()

        val createdItems = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()

        for ((index, element) in items.withIndex()) {
            try {
                val itemObj = element as? JsonObject
                    ?: throw ToolValidationException("Item at index $index must be a JSON object")

                val title = extractItemString(itemObj, "title")
                    ?: throw ToolValidationException("Item at index $index: 'title' is required")

                val description = extractItemString(itemObj, "description")
                val summary = extractItemString(itemObj, "summary") ?: ""
                val roleStr = extractItemString(itemObj, "role")
                val statusLabel = extractItemString(itemObj, "statusLabel")
                val priorityStr = extractItemString(itemObj, "priority")
                val complexity = extractItemInt(itemObj, "complexity")
                val requiresVerification = itemObj["requiresVerification"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                val metadata = extractItemString(itemObj, "metadata")
                val tags = extractItemString(itemObj, "tags")

                // Resolve parentId: per-item overrides shared default
                val itemParentIdStr = extractItemString(itemObj, "parentId")
                val parentId = if (itemParentIdStr != null) {
                    try {
                        UUID.fromString(itemParentIdStr)
                    } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("Item at index $index: 'parentId' is not a valid UUID")
                    }
                } else {
                    sharedParentId
                }

                // Compute depth from parent
                val depth = if (parentId != null) {
                    val parentResult = repo.getById(parentId)
                    when (parentResult) {
                        is Result.Success -> {
                            val computedDepth = parentResult.data.depth + 1
                            if (computedDepth > MAX_DEPTH) {
                                throw ToolValidationException(
                                    "Item at index $index: depth $computedDepth exceeds maximum depth of $MAX_DEPTH"
                                )
                            }
                            computedDepth
                        }
                        is Result.Error -> throw ToolValidationException(
                            "Item at index $index: parent '$parentId' not found"
                        )
                    }
                } else {
                    0
                }

                // Parse role with default
                val role = if (roleStr != null) {
                    Role.fromString(roleStr)
                        ?: throw ToolValidationException(
                            "Item at index $index: invalid role '$roleStr'. Valid: ${Role.VALID_NAMES}"
                        )
                } else {
                    Role.QUEUE
                }

                // Parse priority with default
                val priority = if (priorityStr != null) {
                    Priority.fromString(priorityStr)
                        ?: throw ToolValidationException(
                            "Item at index $index: invalid priority '$priorityStr'. Valid: high, medium, low"
                        )
                } else {
                    Priority.MEDIUM
                }

                // Validate complexity range if provided
                if (complexity != null && complexity !in 1..10) {
                    throw ToolValidationException("Item at index $index: complexity must be between 1 and 10")
                }

                val workItem = WorkItem(
                    parentId = parentId,
                    title = title,
                    description = description,
                    summary = summary,
                    role = role,
                    statusLabel = statusLabel,
                    priority = priority,
                    complexity = complexity,
                    requiresVerification = requiresVerification,
                    depth = depth,
                    metadata = metadata,
                    tags = tags
                )

                when (val result = repo.create(workItem)) {
                    is Result.Success -> {
                        createdItems.add(buildJsonObject {
                            put("id", JsonPrimitive(result.data.id.toString()))
                            put("title", JsonPrimitive(result.data.title))
                            put("depth", JsonPrimitive(result.data.depth))
                            put("role", JsonPrimitive(result.data.role.name.lowercase()))
                            put("priority", JsonPrimitive(result.data.priority.name.lowercase()))
                            put("requiresVerification", JsonPrimitive(result.data.requiresVerification))
                        })
                    }
                    is Result.Error -> {
                        failures.add(buildJsonObject {
                            put("index", JsonPrimitive(index))
                            put("error", JsonPrimitive(result.error.message))
                        })
                    }
                }
            } catch (e: ToolValidationException) {
                failures.add(buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("error", JsonPrimitive(e.message ?: "Validation failed"))
                })
            } catch (e: Exception) {
                failures.add(buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("error", JsonPrimitive(e.message ?: "Unexpected error"))
                })
            }
        }

        val data = buildJsonObject {
            put("items", JsonArray(createdItems))
            put("created", JsonPrimitive(createdItems.size))
            put("failed", JsonPrimitive(failures.size))
            if (failures.isNotEmpty()) {
                put("failures", JsonArray(failures))
            }
        }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Update operation
    // ──────────────────────────────────────────────

    private suspend fun executeUpdate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val items = requireJsonArray(params, "items")
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
                val newRoleStr = extractItemString(itemObj, "role")
                val newStatusLabel = extractItemStringAllowNull(itemObj, "statusLabel", existing.statusLabel)
                val newPriorityStr = extractItemString(itemObj, "priority")
                val newComplexity = extractItemInt(itemObj, "complexity")
                val newRequiresVerification = itemObj["requiresVerification"]?.let { (it as? JsonPrimitive)?.booleanOrNull }
                val newMetadata = extractItemStringAllowNull(itemObj, "metadata", existing.metadata)
                val newTags = extractItemStringAllowNull(itemObj, "tags", existing.tags)

                // Parse role if provided
                val newRole = if (newRoleStr != null) {
                    Role.fromString(newRoleStr)
                        ?: throw ToolValidationException(
                            "Item '$itemId': invalid role '$newRoleStr'. Valid: ${Role.VALID_NAMES}"
                        )
                } else {
                    null
                }

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
                    val parentResult = repo.getById(newParentId)
                    when (parentResult) {
                        is Result.Success -> {
                            newDepth = parentResult.data.depth + 1
                            if (newDepth > MAX_DEPTH) {
                                throw ToolValidationException(
                                    "Item '$itemId': new depth $newDepth exceeds maximum depth of $MAX_DEPTH"
                                )
                            }
                        }
                        is Result.Error -> throw ToolValidationException(
                            "Item '$itemId': new parent '$parentIdStr' not found"
                        )
                    }
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
                        role = newRole ?: item.role,
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

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Delete operation
    // ──────────────────────────────────────────────

    private suspend fun executeDelete(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val idsArray = requireJsonArray(params, "ids")
        val repo = context.workItemRepository()

        val deletedIds = mutableListOf<String>()
        val failures = mutableListOf<JsonObject>()

        for (element in idsArray) {
            val idStr = (element as? JsonPrimitive)?.content
            if (idStr == null) {
                failures.add(buildJsonObject {
                    put("id", JsonPrimitive("null"))
                    put("error", JsonPrimitive("Each ID must be a string"))
                })
                continue
            }

            val id = try {
                UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                failures.add(buildJsonObject {
                    put("id", JsonPrimitive(idStr))
                    put("error", JsonPrimitive("Invalid UUID format: $idStr"))
                })
                continue
            }

            when (val result = repo.delete(id)) {
                is Result.Success -> deletedIds.add(idStr)
                is Result.Error -> {
                    failures.add(buildJsonObject {
                        put("id", JsonPrimitive(idStr))
                        put("error", JsonPrimitive(result.error.message))
                    })
                }
            }
        }

        val data = buildJsonObject {
            put("ids", JsonArray(deletedIds.map { JsonPrimitive(it) }))
            put("deleted", JsonPrimitive(deletedIds.size))
            put("failed", JsonPrimitive(failures.size))
            if (failures.isNotEmpty()) {
                put("failures", JsonArray(failures))
            }
        }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // JSON item field extraction helpers
    // ──────────────────────────────────────────────

    /**
     * Extracts a string field from a JsonObject item. Returns null if absent or not a string.
     */
    private fun extractItemString(obj: JsonObject, name: String): String? {
        val value = obj[name] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val content = value.content
        return if (content.isBlank()) null else content
    }

    /**
     * Extracts a string field that can be explicitly set to null via JSON null.
     * Returns [existing] if the field is absent from the object.
     * Returns null if the field is JSON null.
     * Returns the string value if present and non-blank.
     */
    private fun extractItemStringAllowNull(obj: JsonObject, name: String, existing: String?): String? {
        if (!obj.containsKey(name)) return existing
        val element = obj[name]
        if (element is JsonNull) return null
        val value = element as? JsonPrimitive ?: return existing
        if (!value.isString) return existing
        val content = value.content
        return if (content.isBlank()) null else content
    }

    /**
     * Extracts an integer field from a JsonObject item. Returns null if absent.
     */
    private fun extractItemInt(obj: JsonObject, name: String): Int? {
        val value = obj[name] as? JsonPrimitive ?: return null
        return try {
            value.content.toInt()
        } catch (_: NumberFormatException) {
            null
        }
    }
}
