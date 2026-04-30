package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool for creating and deleting dependencies between WorkItems.
 *
 * Supports two operations:
 * - **create**: Create dependencies via an explicit `dependencies` array or a `pattern` shortcut
 * - **delete**: Delete dependencies by ID, by relationship (fromItemId + toItemId), or bulk via `deleteAll`
 *
 * Create via dependencies array is atomic — all succeed or all fail (cycle detection is batch-aware).
 * Pattern shortcuts (`linear`, `fan-out`, `fan-in`) generate dependency arrays then delegate to createBatch.
 */
class ManageDependenciesTool :
    BaseToolDefinition(),
    ActorAware {
    override val name = "manage_dependencies"

    override val description =
        """
Unified write operations for WorkItem dependencies (create, delete).

**Idempotency:** Pass `requestId` (client-generated UUID) together with a top-level `actor.id` to enable idempotent retries. Repeated calls with the same (actor, requestId) within ~10 minutes return the cached response without re-executing.

**Create via dependencies array:**
- `dependencies` array: each object has `fromItemId` (required), `toItemId` (required), `type` (optional), `unblockAt` (optional)
- Shared `type` at top level defaults all deps (per-dep type overrides). Default: BLOCKS
- Shared `unblockAt` at top level defaults all deps (per-dep unblockAt overrides). Default: null (terminal)
- ATOMIC: all succeed or all fail (cycle/duplicate detection across entire batch)
- Response: `{ dependencies: [{id, fromItemId, toItemId, type}], created: N }`

**Create via pattern shortcut** (mutually exclusive with dependencies array):
- `linear` + `itemIds=[A,B,C,D]` → A→B, B→C, C→D
- `fan-out` + `source=A` + `targets=[B,C,D]` → A→B, A→C, A→D
- `fan-in` + `sources=[B,C,D]` + `target=E` → B→E, C→E, D→E

**Delete modes:**
- By `id`: delete single dependency by its UUID
- By `fromItemId` + `toItemId` (+ optional `type`): find and delete matching deps
- By `fromItemId` or `toItemId` with `deleteAll=true`: delete ALL deps for that item
        """.trimIndent()

    override val category = ToolCategory.DEPENDENCY_MANAGEMENT

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
                            put("description", JsonPrimitive("Operation: create, delete"))
                            put("enum", JsonArray(listOf("create", "delete").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "dependencies",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive("Array of dependency objects for create: {fromItemId, toItemId, type?, unblockAt?}")
                            )
                        }
                    )
                    put(
                        "pattern",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Pattern shortcut: linear, fan-out, fan-in"))
                            put("enum", JsonArray(listOf("linear", "fan-out", "fan-in").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "itemIds",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive("Ordered item IDs (UUIDs or hex prefixes 4+ chars) for linear pattern")
                            )
                        }
                    )
                    put(
                        "source",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Source item UUID or hex prefix (4+ chars) for fan-out pattern")
                            )
                        }
                    )
                    put(
                        "targets",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive("Target item UUIDs or hex prefixes (4+ chars) for fan-out pattern")
                            )
                        }
                    )
                    put(
                        "sources",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive("Source item UUIDs or hex prefixes (4+ chars) for fan-in pattern")
                            )
                        }
                    )
                    put(
                        "target",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Target item UUID or hex prefix (4+ chars) for fan-in pattern")
                            )
                        }
                    )
                    put(
                        "type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Shared default dependency type: BLOCKS, IS_BLOCKED_BY, RELATES_TO (default: BLOCKS)")
                            )
                        }
                    )
                    put(
                        "unblockAt",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Shared default unblockAt threshold: queue, work, review, terminal (default: null = terminal)"
                                )
                            )
                        }
                    )
                    put(
                        "id",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Dependency UUID for delete by ID"))
                        }
                    )
                    put(
                        "fromItemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Source item UUID or hex prefix (4+ chars) for delete by relationship")
                            )
                        }
                    )
                    put(
                        "toItemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Target item UUID or hex prefix (4+ chars) for delete by relationship")
                            )
                        }
                    )
                    put(
                        "deleteAll",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Delete ALL dependencies for the given fromItemId or toItemId"))
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
                                        "within ~10 minutes return the cached response without re-executing. " +
                                        "Requires a top-level actor parameter to function."
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
                                    "Top-level actor for idempotency key resolution: " +
                                        "{ id (required string), kind (required: orchestrator|subagent|user|external), " +
                                        "parent? (optional string), proof? (optional string) }"
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
            "create" -> validateCreateParams(params)
            "delete" -> validateDeleteParams(params)
            else -> throw ToolValidationException("Invalid operation: $operation. Must be create or delete")
        }
    }

    private fun validateCreateParams(params: JsonElement) {
        val depsArray = optionalJsonArray(params, "dependencies")
        val pattern = optionalString(params, "pattern")

        if (depsArray != null && pattern != null) {
            throw ToolValidationException("Provide either 'dependencies' array or 'pattern', not both")
        }

        if (depsArray == null && pattern == null) {
            throw ToolValidationException("Create operation requires either 'dependencies' array or 'pattern'")
        }

        if (depsArray != null && depsArray.isEmpty()) {
            throw ToolValidationException("Create operation requires a non-empty 'dependencies' array")
        }

        if (pattern != null) {
            when (pattern) {
                "linear" -> {
                    val itemIds = optionalJsonArray(params, "itemIds")
                    if (itemIds == null || itemIds.size < 2) {
                        throw ToolValidationException("Linear pattern requires 'itemIds' array with at least 2 items")
                    }
                }
                "fan-out" -> {
                    val source = optionalString(params, "source")
                    val targets = optionalJsonArray(params, "targets")
                    if (source == null) {
                        throw ToolValidationException("Fan-out pattern requires 'source' parameter")
                    }
                    if (targets == null || targets.isEmpty()) {
                        throw ToolValidationException("Fan-out pattern requires non-empty 'targets' array")
                    }
                }
                "fan-in" -> {
                    val sources = optionalJsonArray(params, "sources")
                    val target = optionalString(params, "target")
                    if (sources == null || sources.isEmpty()) {
                        throw ToolValidationException("Fan-in pattern requires non-empty 'sources' array")
                    }
                    if (target == null) {
                        throw ToolValidationException("Fan-in pattern requires 'target' parameter")
                    }
                }
                else -> throw ToolValidationException("Invalid pattern: $pattern. Must be linear, fan-out, or fan-in")
            }
        }
    }

    private fun validateDeleteParams(params: JsonElement) {
        val id = optionalString(params, "id")
        val fromItemId = optionalString(params, "fromItemId")
        val toItemId = optionalString(params, "toItemId")
        val deleteAll = optionalBoolean(params, "deleteAll", false)

        if (id == null && fromItemId == null && toItemId == null && !deleteAll) {
            throw ToolValidationException(
                "Delete operation requires at least one of: 'id', 'fromItemId'+'toItemId', or 'fromItemId'/'toItemId' with 'deleteAll=true'"
            )
        }

        if (deleteAll && fromItemId == null && toItemId == null) {
            throw ToolValidationException("deleteAll requires 'fromItemId' or 'toItemId' to identify which item's dependencies to delete")
        }

        if (!deleteAll && id == null && (fromItemId == null || toItemId == null)) {
            if (fromItemId != null && toItemId == null && !deleteAll) {
                throw ToolValidationException("Delete by relationship requires both 'fromItemId' and 'toItemId', or use 'deleteAll=true'")
            }
            if (toItemId != null && fromItemId == null && !deleteAll) {
                throw ToolValidationException("Delete by relationship requires both 'fromItemId' and 'toItemId', or use 'deleteAll=true'")
            }
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

        // Resolve trusted actor identity from the top-level actor for the idempotency key.
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
                runBlocking {
                    when (operation) {
                        "create" -> executeCreate(params, context)
                        "delete" -> executeDelete(params, context)
                        else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
                    }
                }
            }
        }

        return when (operation) {
            "create" -> executeCreate(params, context)
            "delete" -> executeDelete(params, context)
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
            isError -> {
                val msg =
                    (result as? JsonObject)
                        ?.get("error")
                        ?.let { (it as? JsonObject)?.get("message") }
                        ?.let { (it as? JsonPrimitive)?.content }
                if (msg != null) "manage_dependencies($op) failed: $msg" else "manage_dependencies($op) failed"
            }
            op == "create" -> {
                val count = data?.get("created")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Created $count dependency(ies)"
            }
            op == "delete" -> {
                val count = data?.get("deleted")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Deleted $count dependency(ies)"
            }
            else -> super.userSummary(params, result, isError)
        }
    }

    // ──────────────────────────────────────────────
    // Create operation
    // ──────────────────────────────────────────────

    private suspend fun executeCreate(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val depsArray = optionalJsonArray(params, "dependencies")
        val pattern = optionalString(params, "pattern")

        // Resolve shared defaults
        val sharedTypeStr = optionalString(params, "type")
        val sharedType =
            if (sharedTypeStr != null) {
                DependencyType.fromString(sharedTypeStr)
                    ?: return errorResponse(
                        "Invalid dependency type: $sharedTypeStr. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO",
                        ErrorCodes.VALIDATION_ERROR
                    )
            } else {
                DependencyType.BLOCKS
            }
        val sharedUnblockAt = optionalString(params, "unblockAt")

        val dependencies: List<Dependency> =
            try {
                if (depsArray != null) {
                    parseDependenciesArray(depsArray, sharedType, sharedUnblockAt, context)
                } else {
                    generateFromPattern(params, pattern!!, sharedType, sharedUnblockAt, context)
                }
            } catch (e: ToolValidationException) {
                val failedCount = depsArray?.size ?: 1
                return successResponse(buildValidationFailureResponse(e.message ?: "Validation failed", failedCount))
            } catch (e: ValidationException) {
                val failedCount = depsArray?.size ?: 1
                return successResponse(buildValidationFailureResponse(e.message ?: "Domain validation failed", failedCount))
            }

        val repo = context.dependencyRepository()

        return try {
            val created = repo.createBatch(dependencies)
            val data =
                buildJsonObject {
                    put(
                        "dependencies",
                        JsonArray(
                            created.map { dep ->
                                buildJsonObject {
                                    put("id", JsonPrimitive(dep.id.toString()))
                                    put("fromItemId", JsonPrimitive(dep.fromItemId.toString()))
                                    put("toItemId", JsonPrimitive(dep.toItemId.toString()))
                                    put("type", JsonPrimitive(dep.type.name))
                                    if (dep.unblockAt != null) {
                                        put("unblockAt", JsonPrimitive(dep.unblockAt))
                                    }
                                }
                            }
                        )
                    )
                    put("created", JsonPrimitive(created.size))
                }
            successResponse(data)
        } catch (e: ValidationException) {
            successResponse(buildValidationFailureResponse(e.message ?: "Dependency creation failed", dependencies.size))
        } catch (e: Exception) {
            errorResponse(e.message ?: "Unexpected error creating dependencies", ErrorCodes.INTERNAL_ERROR)
        }
    }

    private suspend fun parseDependenciesArray(
        depsArray: JsonArray,
        sharedType: DependencyType,
        sharedUnblockAt: String?,
        context: ToolExecutionContext
    ): List<Dependency> {
        val result = mutableListOf<Dependency>()
        for ((index, element) in depsArray.withIndex()) {
            val depObj =
                element as? JsonObject
                    ?: throw ToolValidationException("Dependency at index $index must be a JSON object")

            val fromItemIdStr =
                extractItemString(depObj, "fromItemId")
                    ?: throw ToolValidationException("Dependency at index $index: 'fromItemId' is required")
            val toItemIdStr =
                extractItemString(depObj, "toItemId")
                    ?: throw ToolValidationException("Dependency at index $index: 'toItemId' is required")

            val (fromItemId, fromErr) = resolveIdString(fromItemIdStr, context)
            if (fromErr != null || fromItemId == null) {
                throw ToolValidationException(
                    "Dependency at index $index: 'fromItemId' could not be resolved: $fromItemIdStr"
                )
            }
            val (toItemId, toErr) = resolveIdString(toItemIdStr, context)
            if (toErr != null || toItemId == null) {
                throw ToolValidationException(
                    "Dependency at index $index: 'toItemId' could not be resolved: $toItemIdStr"
                )
            }

            val typeStr = extractItemString(depObj, "type")
            val type =
                if (typeStr != null) {
                    DependencyType.fromString(typeStr)
                        ?: throw ToolValidationException(
                            "Dependency at index $index: invalid type '$typeStr'. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO"
                        )
                } else {
                    sharedType
                }

            val unblockAt = extractItemString(depObj, "unblockAt") ?: sharedUnblockAt

            try {
                result.add(Dependency(fromItemId = fromItemId, toItemId = toItemId, type = type, unblockAt = unblockAt))
            } catch (e: ValidationException) {
                throw ToolValidationException("Dependency at index $index: ${e.message}")
            }
        }
        return result
    }

    private suspend fun generateFromPattern(
        params: JsonElement,
        pattern: String,
        sharedType: DependencyType,
        sharedUnblockAt: String?,
        context: ToolExecutionContext
    ): List<Dependency> =
        when (pattern) {
            "linear" -> {
                val itemIdsArray = requireJsonArray(params, "itemIds")
                val itemIds = mutableListOf<UUID>()
                for (element in itemIdsArray) {
                    val str =
                        (element as? JsonPrimitive)?.content
                            ?: throw ToolValidationException("Each itemId must be a string")
                    val (resolved, err) = resolveIdString(str, context)
                    if (err != null || resolved == null) {
                        throw ToolValidationException("Could not resolve itemId: $str")
                    }
                    itemIds.add(resolved)
                }
                itemIds.zipWithNext().map { (from, to) ->
                    Dependency(fromItemId = from, toItemId = to, type = sharedType, unblockAt = sharedUnblockAt)
                }
            }
            "fan-out" -> {
                val sourceStr = requireString(params, "source")
                val (sourceId, sourceErr) = resolveIdString(sourceStr, context)
                if (sourceErr != null || sourceId == null) {
                    throw ToolValidationException("Could not resolve 'source': $sourceStr")
                }
                val targetsArray = requireJsonArray(params, "targets")
                val targetIds = mutableListOf<UUID>()
                for (element in targetsArray) {
                    val str =
                        (element as? JsonPrimitive)?.content
                            ?: throw ToolValidationException("Each target must be a string")
                    val (resolved, err) = resolveIdString(str, context)
                    if (err != null || resolved == null) {
                        throw ToolValidationException("Could not resolve target: $str")
                    }
                    targetIds.add(resolved)
                }
                targetIds.map { targetId ->
                    Dependency(fromItemId = sourceId, toItemId = targetId, type = sharedType, unblockAt = sharedUnblockAt)
                }
            }
            "fan-in" -> {
                val targetStr = requireString(params, "target")
                val (targetId, targetErr) = resolveIdString(targetStr, context)
                if (targetErr != null || targetId == null) {
                    throw ToolValidationException("Could not resolve 'target': $targetStr")
                }
                val sourcesArray = requireJsonArray(params, "sources")
                val sourceIds = mutableListOf<UUID>()
                for (element in sourcesArray) {
                    val str =
                        (element as? JsonPrimitive)?.content
                            ?: throw ToolValidationException("Each source must be a string")
                    val (resolved, err) = resolveIdString(str, context)
                    if (err != null || resolved == null) {
                        throw ToolValidationException("Could not resolve source: $str")
                    }
                    sourceIds.add(resolved)
                }
                sourceIds.map { sourceId ->
                    Dependency(fromItemId = sourceId, toItemId = targetId, type = sharedType, unblockAt = sharedUnblockAt)
                }
            }
            else -> throw ToolValidationException("Invalid pattern: $pattern")
        }

    // ──────────────────────────────────────────────
    // Delete operation
    // ──────────────────────────────────────────────

    private suspend fun executeDelete(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val repo = context.dependencyRepository()
        val id = extractUUID(params, "id", required = false)
        val (fromItemId, fromError) = resolveItemId(params, "fromItemId", context, required = false)
        if (fromError != null) return fromError
        val (toItemId, toError) = resolveItemId(params, "toItemId", context, required = false)
        if (toError != null) return toError
        val deleteAll = optionalBoolean(params, "deleteAll", false)
        val typeFilter = optionalString(params, "type")

        return try {
            when {
                // Delete by dependency ID
                id != null -> {
                    val deleted = repo.delete(id)
                    val data =
                        buildJsonObject {
                            put("id", JsonPrimitive(id.toString()))
                            put("deleted", JsonPrimitive(if (deleted) 1 else 0))
                        }
                    if (deleted) {
                        successResponse(data)
                    } else {
                        successResponse(data, "Dependency not found: $id")
                    }
                }

                // Delete all dependencies for an item
                deleteAll -> {
                    val itemId =
                        fromItemId ?: toItemId
                            ?: return errorResponse(
                                "deleteAll requires 'fromItemId' or 'toItemId'",
                                ErrorCodes.VALIDATION_ERROR
                            )
                    val count = repo.deleteByItemId(itemId)
                    val data =
                        buildJsonObject {
                            put("itemId", JsonPrimitive(itemId.toString()))
                            put("deleted", JsonPrimitive(count))
                        }
                    successResponse(data)
                }

                // Delete by relationship (fromItemId + toItemId)
                fromItemId != null && toItemId != null -> {
                    val deps =
                        repo.findByFromItemId(fromItemId).filter { dep ->
                            dep.toItemId == toItemId &&
                                (typeFilter == null || dep.type == DependencyType.fromString(typeFilter))
                        }
                    var deletedCount = 0
                    for (dep in deps) {
                        if (repo.delete(dep.id)) {
                            deletedCount++
                        }
                    }
                    val data =
                        buildJsonObject {
                            put("fromItemId", JsonPrimitive(fromItemId.toString()))
                            put("toItemId", JsonPrimitive(toItemId.toString()))
                            put("deleted", JsonPrimitive(deletedCount))
                        }
                    successResponse(data)
                }

                else ->
                    errorResponse(
                        "Delete requires 'id', 'fromItemId'+'toItemId', or 'deleteAll' with an item ID",
                        ErrorCodes.VALIDATION_ERROR
                    )
            }
        } catch (e: Exception) {
            errorResponse(e.message ?: "Unexpected error deleting dependencies", ErrorCodes.INTERNAL_ERROR)
        }
    }

    // ──────────────────────────────────────────────
    // JSON item field extraction helpers
    // ──────────────────────────────────────────────

    private fun extractItemString(
        obj: JsonObject,
        name: String
    ): String? {
        val value = obj[name] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val content = value.content
        return if (content.isBlank()) null else content
    }

    private fun buildValidationFailureResponse(
        error: String,
        failedCount: Int
    ): JsonObject =
        buildJsonObject {
            put("dependencies", JsonArray(emptyList()))
            put("created", JsonPrimitive(0))
            put("failed", JsonPrimitive(failedCount))
            put(
                "failures",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put("error", JsonPrimitive(error))
                        }
                    )
                )
            )
        }
}
