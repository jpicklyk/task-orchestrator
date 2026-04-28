package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.tools.*
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
 *
 * Each operation is handled by a focused handler class:
 * - [CreateItemHandler] for create operations
 * - [UpdateItemHandler] for update operations
 * - [DeleteItemHandler] for delete operations
 */
class ManageItemsTool :
    BaseToolDefinition(),
    ActorAware {
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

**Idempotency:** Pass `requestId` (client-generated UUID) to enable idempotent retries. Repeated calls with the same (actor, requestId) within ~10 minutes return the cached response without re-executing.

**Operations:**

**create** - Create WorkItems from `items` array.
- Each item: `{ title (required), description?, summary?, role?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags?, type?, properties? }`
- Shared `parentId` at top level serves as default for all items (per-item parentId overrides)
- Depth auto-computed from parent (root=0, child=parent.depth+1, max=$MAX_DEPTH)
- Defaults: role=queue, priority=medium, complexity=5
- Response: `{ items: [{id, title, depth, role, priority, requiresVerification, tags, schemaMatch, expectedNotes}], created: N, failed: N, failures: [{index, error}] }`
- `tags` is always included (null if not set). `expectedNotes` is always included (empty array when no schema matches). `schemaMatch` indicates whether the item's tags matched a configured note schema.

**update** - Partial update from `items` array.
- Each item: `{ id (required, UUID or hex prefix 4+ chars), title?, description?, summary?, statusLabel?, priority?, complexity?, parentId? (UUID or hex prefix 4+ chars), metadata?, tags?, type?, properties? }`
- Note: role changes are not allowed in update operations. Use advance_item with triggers (start, complete, block, hold, resume, cancel, reopen) instead.
- Only provided fields are changed; omitted fields retain existing values
- If parentId changes, depth is recomputed from new parent
- Response: `{ items: [{id, modifiedAt}], updated: N, failed: N, failures: [{id, error}] }`

**delete** - Delete by `ids` array (UUIDs or hex prefixes 4+ chars).
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
                            put("description", JsonPrimitive("Array of item UUIDs or hex prefixes (4+ chars) for delete"))
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
                            put("description", JsonPrimitive("Shared default parent ID for create (UUID or hex prefix 4+ chars)"))
                        }
                    )
                    put(
                        "requiresVerification",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Whether this item requires explicit verification before completion"))
                        }
                    )
                    put(
                        "type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Schema type identifier for this work item. Determines which work_item_schema applies " +
                                        "(lifecycle mode, required notes). One-to-one lookup — unlike tags, only one type per item."
                                )
                            )
                        }
                    )
                    put(
                        "properties",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "JSON string containing extensible item properties (e.g., lifecycle overrides, traits). " +
                                        "Stored as-is; validated by consuming code."
                                )
                            )
                        }
                    )
                    put(
                        "traits",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Comma-separated list of trait names to apply (e.g., 'needs-security-review,needs-perf-review'). " +
                                        "Traits add additional note requirements from the traits: config section. " +
                                        "Merged into the properties JSON automatically."
                                )
                            )
                        }
                    )
                    put(
                        "requestId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Client-generated UUID for idempotency. Repeated calls with the same (actor, requestId) " +
                                        "within ~10 minutes return the cached response without re-executing."
                                )
                            )
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
        val requestIdStr = optionalString(params, "requestId")
        val requestId =
            requestIdStr?.let {
                try {
                    UUID.fromString(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        // Resolve actor identity for idempotency key (use actor.id if provided)
        val actorId =
            (params as? JsonObject)
                ?.get("actor")
                ?.let { it as? JsonObject }
                ?.get("id")
                ?.let { (it as? JsonPrimitive)?.content }

        // Check idempotency cache before executing
        if (requestId != null && actorId != null) {
            val cached = context.idempotencyCache.get(actorId, requestId)
            if (cached != null) return cached as JsonElement
        }

        val result =
            when (operation) {
                "create" -> {
                    val (parentId, parentIdError) = resolveItemId(params, "parentId", context, required = false)
                    if (parentIdError != null) return parentIdError
                    createHandler.execute(
                        requireJsonArray(params, "items"),
                        parentId,
                        optionalString(params, "traits"),
                        context
                    )
                }
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

        // Store result in idempotency cache
        if (requestId != null && actorId != null) {
            context.idempotencyCache.put(actorId, requestId, result)
        }

        return result
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
