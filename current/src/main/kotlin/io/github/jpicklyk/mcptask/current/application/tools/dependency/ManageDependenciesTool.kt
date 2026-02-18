package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
class ManageDependenciesTool : BaseToolDefinition() {

    override val name = "manage_dependencies"

    override val description = """
Unified write operations for WorkItem dependencies (create, delete).

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
                put("description", JsonPrimitive("Operation: create, delete"))
                put("enum", JsonArray(listOf("create", "delete").map { JsonPrimitive(it) }))
            })
            put("dependencies", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Array of dependency objects for create: {fromItemId, toItemId, type?, unblockAt?}"))
            })
            put("pattern", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Pattern shortcut: linear, fan-out, fan-in"))
                put("enum", JsonArray(listOf("linear", "fan-out", "fan-in").map { JsonPrimitive(it) }))
            })
            put("itemIds", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Ordered item IDs for linear pattern"))
            })
            put("source", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Source item UUID for fan-out pattern"))
            })
            put("targets", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Target item UUIDs for fan-out pattern"))
            })
            put("sources", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Source item UUIDs for fan-in pattern"))
            })
            put("target", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Target item UUID for fan-in pattern"))
            })
            put("type", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Shared default dependency type: BLOCKS, IS_BLOCKED_BY, RELATES_TO (default: BLOCKS)"))
            })
            put("unblockAt", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Shared default unblockAt threshold: queue, work, review, terminal (default: null = terminal)"))
            })
            put("id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Dependency UUID for delete by ID"))
            })
            put("fromItemId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Source item UUID for delete by relationship"))
            })
            put("toItemId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Target item UUID for delete by relationship"))
            })
            put("deleteAll", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Delete ALL dependencies for the given fromItemId or toItemId"))
            })
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

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "create" -> executeCreate(params, context)
            "delete" -> executeDelete(params, context)
            else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        val op = (params as? JsonObject)?.get("operation")?.let {
            (it as? JsonPrimitive)?.content
        } ?: "unknown"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        return when {
            isError -> {
                val msg = (result as? JsonObject)?.get("error")
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

    private fun executeCreate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val depsArray = optionalJsonArray(params, "dependencies")
        val pattern = optionalString(params, "pattern")

        // Resolve shared defaults
        val sharedTypeStr = optionalString(params, "type")
        val sharedType = if (sharedTypeStr != null) {
            DependencyType.fromString(sharedTypeStr)
                ?: return errorResponse(
                    "Invalid dependency type: $sharedTypeStr. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO",
                    ErrorCodes.VALIDATION_ERROR
                )
        } else {
            DependencyType.BLOCKS
        }
        val sharedUnblockAt = optionalString(params, "unblockAt")

        val dependencies: List<Dependency> = try {
            if (depsArray != null) {
                parseDependenciesArray(depsArray, sharedType, sharedUnblockAt)
            } else {
                generateFromPattern(params, pattern!!, sharedType, sharedUnblockAt)
            }
        } catch (e: ToolValidationException) {
            return errorResponse(e.message ?: "Validation failed", ErrorCodes.VALIDATION_ERROR)
        } catch (e: ValidationException) {
            return errorResponse(e.message ?: "Domain validation failed", ErrorCodes.VALIDATION_ERROR)
        }

        val repo = context.dependencyRepository()

        return try {
            val created = repo.createBatch(dependencies)
            val data = buildJsonObject {
                put("dependencies", JsonArray(created.map { dep ->
                    buildJsonObject {
                        put("id", JsonPrimitive(dep.id.toString()))
                        put("fromItemId", JsonPrimitive(dep.fromItemId.toString()))
                        put("toItemId", JsonPrimitive(dep.toItemId.toString()))
                        put("type", JsonPrimitive(dep.type.name))
                        if (dep.unblockAt != null) {
                            put("unblockAt", JsonPrimitive(dep.unblockAt))
                        }
                    }
                }))
                put("created", JsonPrimitive(created.size))
            }
            successResponse(data)
        } catch (e: ValidationException) {
            errorResponse(e.message ?: "Dependency creation failed", ErrorCodes.CONFLICT_ERROR)
        } catch (e: Exception) {
            errorResponse(e.message ?: "Unexpected error creating dependencies", ErrorCodes.INTERNAL_ERROR)
        }
    }

    private fun parseDependenciesArray(
        depsArray: JsonArray,
        sharedType: DependencyType,
        sharedUnblockAt: String?
    ): List<Dependency> {
        return depsArray.mapIndexed { index, element ->
            val depObj = element as? JsonObject
                ?: throw ToolValidationException("Dependency at index $index must be a JSON object")

            val fromItemIdStr = extractItemString(depObj, "fromItemId")
                ?: throw ToolValidationException("Dependency at index $index: 'fromItemId' is required")
            val toItemIdStr = extractItemString(depObj, "toItemId")
                ?: throw ToolValidationException("Dependency at index $index: 'toItemId' is required")

            val fromItemId = try {
                UUID.fromString(fromItemIdStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Dependency at index $index: 'fromItemId' is not a valid UUID: $fromItemIdStr")
            }
            val toItemId = try {
                UUID.fromString(toItemIdStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Dependency at index $index: 'toItemId' is not a valid UUID: $toItemIdStr")
            }

            val typeStr = extractItemString(depObj, "type")
            val type = if (typeStr != null) {
                DependencyType.fromString(typeStr)
                    ?: throw ToolValidationException(
                        "Dependency at index $index: invalid type '$typeStr'. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO"
                    )
            } else {
                sharedType
            }

            val unblockAt = extractItemString(depObj, "unblockAt") ?: sharedUnblockAt

            // Dependency constructor validates (self-reference, RELATES_TO+unblockAt, valid thresholds)
            Dependency(
                fromItemId = fromItemId,
                toItemId = toItemId,
                type = type,
                unblockAt = unblockAt
            )
        }
    }

    private fun generateFromPattern(
        params: JsonElement,
        pattern: String,
        sharedType: DependencyType,
        sharedUnblockAt: String?
    ): List<Dependency> {
        return when (pattern) {
            "linear" -> {
                val itemIdsArray = requireJsonArray(params, "itemIds")
                val itemIds = itemIdsArray.map { element ->
                    val str = (element as? JsonPrimitive)?.content
                        ?: throw ToolValidationException("Each itemId must be a string UUID")
                    try {
                        UUID.fromString(str)
                    } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("Invalid UUID in itemIds: $str")
                    }
                }
                // Generate chain: A→B, B→C, C→D
                itemIds.zipWithNext().map { (from, to) ->
                    Dependency(fromItemId = from, toItemId = to, type = sharedType, unblockAt = sharedUnblockAt)
                }
            }
            "fan-out" -> {
                val sourceStr = requireString(params, "source")
                val sourceId = try {
                    UUID.fromString(sourceStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("'source' is not a valid UUID: $sourceStr")
                }
                val targetsArray = requireJsonArray(params, "targets")
                val targetIds = targetsArray.map { element ->
                    val str = (element as? JsonPrimitive)?.content
                        ?: throw ToolValidationException("Each target must be a string UUID")
                    try {
                        UUID.fromString(str)
                    } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("Invalid UUID in targets: $str")
                    }
                }
                targetIds.map { targetId ->
                    Dependency(fromItemId = sourceId, toItemId = targetId, type = sharedType, unblockAt = sharedUnblockAt)
                }
            }
            "fan-in" -> {
                val targetStr = requireString(params, "target")
                val targetId = try {
                    UUID.fromString(targetStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("'target' is not a valid UUID: $targetStr")
                }
                val sourcesArray = requireJsonArray(params, "sources")
                val sourceIds = sourcesArray.map { element ->
                    val str = (element as? JsonPrimitive)?.content
                        ?: throw ToolValidationException("Each source must be a string UUID")
                    try {
                        UUID.fromString(str)
                    } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("Invalid UUID in sources: $str")
                    }
                }
                sourceIds.map { sourceId ->
                    Dependency(fromItemId = sourceId, toItemId = targetId, type = sharedType, unblockAt = sharedUnblockAt)
                }
            }
            else -> throw ToolValidationException("Invalid pattern: $pattern")
        }
    }

    // ──────────────────────────────────────────────
    // Delete operation
    // ──────────────────────────────────────────────

    private fun executeDelete(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val repo = context.dependencyRepository()
        val id = extractUUID(params, "id", required = false)
        val fromItemId = extractUUID(params, "fromItemId", required = false)
        val toItemId = extractUUID(params, "toItemId", required = false)
        val deleteAll = optionalBoolean(params, "deleteAll", false)
        val typeFilter = optionalString(params, "type")

        return try {
            when {
                // Delete by dependency ID
                id != null -> {
                    val deleted = repo.delete(id)
                    val data = buildJsonObject {
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
                    val itemId = fromItemId ?: toItemId
                        ?: return errorResponse(
                            "deleteAll requires 'fromItemId' or 'toItemId'",
                            ErrorCodes.VALIDATION_ERROR
                        )
                    val count = repo.deleteByItemId(itemId)
                    val data = buildJsonObject {
                        put("itemId", JsonPrimitive(itemId.toString()))
                        put("deleted", JsonPrimitive(count))
                    }
                    successResponse(data)
                }

                // Delete by relationship (fromItemId + toItemId)
                fromItemId != null && toItemId != null -> {
                    val deps = repo.findByFromItemId(fromItemId).filter { dep ->
                        dep.toItemId == toItemId &&
                                (typeFilter == null || dep.type == DependencyType.fromString(typeFilter))
                    }
                    var deletedCount = 0
                    for (dep in deps) {
                        if (repo.delete(dep.id)) {
                            deletedCount++
                        }
                    }
                    val data = buildJsonObject {
                        put("fromItemId", JsonPrimitive(fromItemId.toString()))
                        put("toItemId", JsonPrimitive(toItemId.toString()))
                        put("deleted", JsonPrimitive(deletedCount))
                    }
                    successResponse(data)
                }

                else -> errorResponse(
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

    private fun extractItemString(obj: JsonObject, name: String): String? {
        val value = obj[name] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val content = value.content
        return if (content.isBlank()) null else content
    }
}
