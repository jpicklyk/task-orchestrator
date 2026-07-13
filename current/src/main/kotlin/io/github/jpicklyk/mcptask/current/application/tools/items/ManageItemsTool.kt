package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
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
 * No application-layer depth cap is enforced; cycle protection is delegated to the
 * DB BEFORE-UPDATE trigger on work_items.parent_id introduced in V7.
 *
 * Each operation is handled by a focused handler class:
 * - [CreateItemHandler] for create operations
 * - [UpdateItemHandler] for update operations
 * - [DeleteItemHandler] for delete operations
 */
class ManageItemsTool :
    BaseToolDefinition(),
    ActorAware {
    private val createHandler = CreateItemHandler()
    private val updateHandler = UpdateItemHandler()
    private val deleteHandler = DeleteItemHandler()

    override val name = "manage_items"

    override val description =
        """
Unified write operations for WorkItems (create, update, delete).

**create** - Each item: `{ title (required), description?, summary?, role?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags?, type?, properties?, requiresVerification? }`. Shared top-level `parentId` is the default for all items (per-item parentId overrides). Depth is auto-computed from parent (root=0, child=parent.depth+1, unbounded). Defaults: role=queue, priority=medium; complexity has no default.

**update** - Each item: `{ id (required, UUID or hex prefix 4+ chars), title?, description?, summary?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags?, type?, properties? }`. Role changes are not allowed — use `advance_item` instead. Only provided fields change; if parentId changes, depth is recomputed from the new parent.

**delete** - Delete by `ids` array (UUIDs or hex prefixes 4+ chars); see `recursive` param.
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
                                    "Default false (children block delete with a constraint error); true deletes " +
                                        "descendants first."
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
                            put(
                                "description",
                                JsonPrimitive(
                                    "Ignored at the top level for create — set requiresVerification on each item in " +
                                        "the items array instead."
                                )
                            )
                        }
                    )
                    put(
                        "type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Schema type identifier; determines lifecycle mode and required notes. One type " +
                                        "per item (unlike tags)."
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
                                    "JSON string of extensible item properties (e.g. lifecycle overrides, traits); " +
                                        "stored as-is."
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
                                    "Comma-separated trait names adding note requirements from the traits: config; " +
                                        "merged into properties automatically."
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
                                    "Client-generated UUID for idempotency (10 min cache, keyed by actor+requestId). " +
                                        "Requires actor."
                                )
                            )
                        }
                    )
                    put(
                        "actor",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Actor: { id (required), kind (required: orchestrator|subagent|user|external), " +
                                        "parent?, proof? }. Required when requestId is provided."
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

        // Resolve trusted actor identity for the idempotency key.
        // Must be done BEFORE the cache lookup so the cache is keyed on the verified identity,
        // not the self-reported actor.id (bug 3a fix).
        val actorObj = (params as? JsonObject)?.get("actor") as? JsonObject
        val trustedActorId: String? =
            if (actorObj != null) {
                val actorResult = parseActorClaim(actorObj, context)
                when (actorResult) {
                    is ActorParseResult.Success -> {
                        when (
                            val r =
                                ActorAware.resolveTrustedActorId(
                                    actorResult.claim,
                                    actorResult.verification,
                                    context.degradedModePolicy
                                )
                        ) {
                            is PolicyResolution.Trusted -> r.trustedId
                            is PolicyResolution.Rejected -> null
                        }
                    }
                    else -> null
                }
            } else {
                null
            }

        // Atomic getOrCompute: check-compute-store under a single lock to prevent TOCTOU races.
        // kotlinx.coroutines.runBlocking bridges the suspend execution into the lock-held lambda.
        // This is safe because the operation logic only accesses DB repositories and never
        // re-acquires the IdempotencyCache lock.
        if (requestId != null && trustedActorId != null) {
            return context.idempotencyCache.getOrCompute(trustedActorId, requestId) {
                runBlocking { executeOperation(operation, params, context) }
            }
        }

        return executeOperation(operation, params, context)
    }

    private suspend fun executeOperation(
        operation: String,
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        return when (operation) {
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
        val data = (result as? JsonObject)?.get("data") as? JsonObject
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
