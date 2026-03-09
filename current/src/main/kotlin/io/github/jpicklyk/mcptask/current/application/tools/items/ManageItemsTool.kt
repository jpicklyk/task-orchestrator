package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

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
 *
 * Each operation is handled by a focused handler class:
 * - [CreateItemHandler] for create operations
 * - [UpdateItemHandler] for update operations
 * - [DeleteItemHandler] for delete operations
 */
class ManageItemsTool : BaseToolDefinition() {
    companion object {
        /** Maximum allowed nesting depth for WorkItems. Delegates to [ItemHierarchyValidator]. */
        const val MAX_DEPTH = ItemHierarchyValidator.MAX_DEPTH
    }

    private val createHandler = CreateItemHandler()
    private val updateHandler = UpdateItemHandler()
    private val deleteHandler = DeleteItemHandler()

    override val name = "manage_items"

    override val description =
        """
Unified write operations for WorkItems (create, update, delete).

**Operations:**

**create** - Create WorkItems from `items` array.
- Each item: `{ title (required), description?, summary?, role?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags? }`
- Shared `parentId` at top level serves as default for all items (per-item parentId overrides)
- Depth auto-computed from parent (root=0, child=parent.depth+1, max=$MAX_DEPTH)
- Defaults: role=queue, priority=medium, complexity=5
- Response: `{ items: [{id, title, depth, role, priority, requiresVerification, tags, expectedNotes?}], created: N, failed: N, failures: [{index, error}] }`
- `tags` is always included (null if not set). `expectedNotes` is included only when the item's tags match a configured note schema — check it immediately after creation to know which notes to fill.

**update** - Partial update from `items` array.
- Each item: `{ id (required), title?, description?, summary?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags? }`
- Note: role changes are not allowed in update operations. Use advance_item with triggers (start, complete, block, hold, resume, cancel, reopen) instead.
- Only provided fields are changed; omitted fields retain existing values
- If parentId changes, depth is recomputed from new parent
- Response: `{ items: [{id, modifiedAt}], updated: N, failed: N, failures: [{id, error}] }`

**delete** - Delete by `ids` array.
- Response: `{ ids: [...], deleted: N, failed: N, failures: [{id, error}] }`
- Use `recursive: true` to recursively delete all descendant items before deleting the specified items.
  Without this flag, deleting an item with children will fail with a constraint error.
        """.trimIndent()

    override val category = ToolCategory.ITEM_MANAGEMENT

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = false
        )

    override val parameterSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "operation",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Operation: create, update, delete"))
                            put("enum", JsonArray(listOf("create", "update", "delete").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "items",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Array of item objects for create/update"))
                        }
                    )
                    put(
                        "ids",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Array of item UUIDs for delete"))
                        }
                    )
                    put(
                        "recursive",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true, recursively deletes all descendant items before deleting the specified items. " +
                                        "Default false — without this flag, deleting an item with children will fail with a constraint error."
                                )
                            )
                        }
                    )
                    put(
                        "parentId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Shared default parent ID for create"))
                        }
                    )
                    put(
                        "requiresVerification",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Whether this item requires explicit verification before completion"))
                        }
                    )
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

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "create" ->
                createHandler.execute(
                    requireJsonArray(params, "items"),
                    extractUUID(params, "parentId", required = false),
                    context
                )
            "update" ->
                updateHandler.execute(
                    requireJsonArray(params, "items"),
                    context
                )
            "delete" ->
                deleteHandler.execute(
                    requireJsonArray(params, "ids"),
                    optionalBoolean(params, "recursive", false),
                    context
                )
            else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
        }
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        val op =
            (params as? JsonObject)?.get("operation")?.let {
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
}
