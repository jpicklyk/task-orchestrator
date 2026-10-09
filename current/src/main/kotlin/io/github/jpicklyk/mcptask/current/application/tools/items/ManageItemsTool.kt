package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.withEventActor
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

**create** - Each item: `{ title (required), description?, summary?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags?, type?, properties?, requiresVerification? }`. Shared top-level `parentId` is the default (per-item overrides). Depth = parent.depth+1 (root=0, unbounded). Items start in queue; a `role` field fails the item. Default priority=medium.

**update** - Each item: `{ itemId (required, UUID or hex prefix 4+ chars), title?, description?, summary?, statusLabel?, priority?, complexity?, parentId?, metadata?, tags?, type?, properties? }`. Role changes go through `advance_item`. Only provided fields change.

**delete** - Delete by `itemIds` array (UUIDs or hex prefixes 4+ chars); see `recursive` param.

Creating or moving under a terminal auto-lifecycle parent fails (reopen it). Moving away or deleting re-evaluates the old parent as a child completion, reported as `cascadeEvents` on update/delete elements when non-empty.
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
                        "itemIds",
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
                                    "Ignored at the top level — set type on each item in the items array instead."
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
                                    "Ignored at the top level — set properties on each item in the items array " +
                                        "instead."
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
                                        "merged into properties automatically. Shared default for every item in the " +
                                        "create or update batch; a per-item `traits` field overrides it for that item. " +
                                        "On update, absent from both levels leaves existing traits untouched; an " +
                                        "explicit empty string clears them."
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
                                    "Client-generated UUID; each element runs once per 24h (keyed by actor+requestId); " +
                                        "requires actor; malformed values rejected."
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
        validateRequestIdParam(params)
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
                val ids = optionalJsonArray(params, "itemIds")
                if (ids == null || ids.isEmpty()) {
                    throw ToolValidationException("Delete operation requires a non-empty 'itemIds' array")
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
        // Defence-in-depth: unreachable via MCP (validateParams already ran validateRequestIdParam),
        // but guards a direct in-process call to execute() that skipped validateParams.
        validateRequestIdParam(params)
        val requestIdStr = optionalString(params, "requestId")
        val requestId = requestIdStr?.let { UUID.fromString(it.trim()) }

        // Resolve trusted actor identity for the idempotency key.
        // Must be done BEFORE the cache lookup so the cache is keyed on the verified identity,
        // not the self-reported actor.id (bug 3a fix).
        val actorObj = (params as? JsonObject)?.get("actor") as? JsonObject
        val parsedActor = if (actorObj != null) parseActorClaim(actorObj, context) else null
        val eventActor = (parsedActor as? ActorParseResult.Success)?.claim
        val trustedActorId: String? =
            if (parsedActor != null) {
                val actorResult = parsedActor
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

        // A keyed call (requestId plus a trusted principal) is keyed PER ELEMENT of the operation array: each
        // element runs, and is recorded, in its own unit. Unkeyed calls never touch the idempotency service.
        val keyed =
            if (requestId != null && trustedActorId != null) {
                KeyedCall(
                    context.idempotency,
                    trustedActorId,
                    requestId,
                    KeyedCall.op(name, operation),
                    KeyedCall.sharedOf(params, "items", "itemIds")
                )
            } else {
                null
            }

        return withEventActor(eventActor) { executeOperation(operation, params, context, keyed) }
    }

    private suspend fun executeOperation(
        operation: String,
        params: JsonElement,
        context: ToolExecutionContext,
        keyed: KeyedCall?
    ): JsonElement {
        return when (operation) {
            "create" -> {
                val (parentId, parentIdError) = resolveItemId(params, "parentId", context, required = false)
                if (parentIdError != null) return parentIdError
                createHandler.execute(
                    requireJsonArray(params, "items"),
                    parentId,
                    optionalString(params, "traits"),
                    context,
                    keyed
                )
            }
            "update" ->
                updateHandler.execute(
                    requireJsonArray(params, "items"),
                    optionalString(params, "traits"),
                    context,
                    keyed
                )
            "delete" ->
                deleteHandler.execute(
                    requireJsonArray(params, "itemIds"),
                    optionalBoolean(params, "recursive", false),
                    context,
                    keyed
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
