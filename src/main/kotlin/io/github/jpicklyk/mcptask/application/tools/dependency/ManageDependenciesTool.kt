package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Dependency
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.*

/**
 * Consolidated MCP tool for write operations on task dependencies.
 * Supports batch creation with pattern shortcuts (linear, fan-out, fan-in)
 * and comprehensive validation including cycle detection across the full batch.
 *
 * Operations: create, delete
 */
class ManageDependenciesTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val toolAnnotations: ToolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = false,
        openWorldHint = false
    )

    override val name: String = "manage_dependencies"

    override val title: String = "Manage Task Dependencies"

    override val outputSchema: ToolSchema = ToolSchema(
        buildJsonObject {
            put("type", "object")
            put("description", "Data payload shape varies by operation. See tool description for per-operation response details.")
        }
    )

    override fun shouldUseLocking(): Boolean = true

    override val description: String = """Unified write operations for task dependencies with batch support.

Operations: create, delete

Create Modes (mutually exclusive):
1. **dependencies array** — Explicit list of dependency objects. Each: {fromTaskId, toTaskId, type?}
2. **pattern shortcut** — Generate dependencies from a named pattern

Create Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | "create" |
| dependencies | array | No* | Array of {fromTaskId: UUID, toTaskId: UUID, type?: BLOCKS|IS_BLOCKED_BY|RELATES_TO} objects |
| pattern | enum | No* | Shortcut pattern: "linear", "fan-out", "fan-in" |
| taskIds | array | No | Ordered task IDs for linear pattern. Creates chain: A→B→C→D |
| source | UUID | No | Source task ID for fan-out pattern |
| targets | array | No | Target task IDs for fan-out pattern |
| sources | array | No | Source task IDs for fan-in pattern |
| target | UUID | No | Target task ID for fan-in pattern |
| type | enum | No | Default dependency type: BLOCKS, IS_BLOCKED_BY, RELATES_TO (default: BLOCKS) |

*Provide either dependencies array OR pattern — not both.

Pattern Shortcuts:
- **linear** + taskIds=[A,B,C,D] → A→B, B→C, C→D (chain)
- **fan-out** + source=A + targets=[B,C,D] → A→B, A→C, A→D (one blocks many)
- **fan-in** + sources=[B,C,D] + target=E → B→E, C→E, D→E (many block one)

Batch Validation:
- All tasks must exist
- Cycle detection considers the entire batch as a graph
- Atomic: if any dependency fails validation, none are created
- Duplicate detection within batch and against existing dependencies

Dependency Types:
- BLOCKS: Source blocks target (target cannot start until source complete)
- IS_BLOCKED_BY: Source is blocked by target (inverse of BLOCKS)
- RELATES_TO: General relationship (no blocking)

Delete Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | "delete" |
| id | UUID | No | Dependency ID (delete by ID) |
| fromTaskId | UUID | No | Source task ID (delete by relationship, or with deleteAll) |
| toTaskId | UUID | No | Target task ID (delete by relationship, or with deleteAll) |
| type | enum | No | Filter by type when deleting by relationship |
| deleteAll | boolean | No | Delete all dependencies for the specified task (default: false) |

Delete Methods:
- By dependency ID: Provide 'id' parameter
- By task relationship: Provide 'fromTaskId' + 'toTaskId' (optional 'type' filter)
- All for a task: Provide 'fromTaskId' OR 'toTaskId' with 'deleteAll=true'

Usage: Consolidates create/delete for task dependencies with batch support and comprehensive validation.
Related: query_dependencies, manage_container, get_blocked_tasks
Docs: task-orchestrator://docs/tools/manage-dependencies
"""

    override val parameterSchema: ToolSchema = ToolSchema(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(listOf("create", "delete").map { JsonPrimitive(it) })
                    )
                ),
                "dependencies" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Array of dependency definitions for batch create. Each object: {fromTaskId: UUID, toTaskId: UUID, type?: BLOCKS|IS_BLOCKED_BY|RELATES_TO}"),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(
                                    mapOf(
                                        "fromTaskId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                        "toTaskId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                        "type" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "enum" to JsonArray(listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO").map { JsonPrimitive(it) }),
                                                "default" to JsonPrimitive("BLOCKS")
                                            )
                                        )
                                    )
                                ),
                                "required" to JsonArray(listOf("fromTaskId", "toTaskId").map { JsonPrimitive(it) })
                            )
                        )
                    )
                ),
                "pattern" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Shortcut pattern for generating dependencies: linear, fan-out, fan-in"),
                        "enum" to JsonArray(listOf("linear", "fan-out", "fan-in").map { JsonPrimitive(it) })
                    )
                ),
                "taskIds" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Ordered task IDs for linear pattern. Creates chain: A→B→C→D"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid")))
                    )
                ),
                "source" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Source task ID for fan-out pattern"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "targets" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Target task IDs for fan-out pattern"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid")))
                    )
                ),
                "sources" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Source task IDs for fan-in pattern"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid")))
                    )
                ),
                "target" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Target task ID for fan-in pattern"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Dependency ID (required for: delete by ID; mutually exclusive with task relationship parameters)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "fromTaskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Source task ID (delete by relationship, or with deleteAll)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "toTaskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Target task ID (delete by relationship, or with deleteAll)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "type" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Dependency type (default: BLOCKS)"),
                        "enum" to JsonArray(listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO").map { JsonPrimitive(it) }),
                        "default" to JsonPrimitive("BLOCKS")
                    )
                ),
                "deleteAll" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Delete all dependencies for task (delete only, default: false)"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("operation")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")

        // Validate operation
        if (operation !in listOf("create", "delete")) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: create, delete")
        }

        when (operation) {
            "create" -> validateCreateParams(params)
            "delete" -> validateDeleteParams(params)
        }
    }

    private fun validateCreateParams(params: JsonElement) {
        val dependenciesArray = optionalJsonArray(params, "dependencies")
        val pattern = optionalString(params, "pattern")
        val fromTaskId = optionalString(params, "fromTaskId")
        val toTaskId = optionalString(params, "toTaskId")

        // Count how many create modes are specified
        val modes = listOfNotNull(
            if (dependenciesArray != null) "dependencies" else null,
            if (pattern != null) "pattern" else null,
            if (fromTaskId != null || toTaskId != null) "legacy" else null
        )

        if (modes.isEmpty()) {
            throw ToolValidationException(
                "Must specify one of: 'dependencies' array or 'pattern' shortcut for create operation"
            )
        }

        if (modes.size > 1) {
            throw ToolValidationException(
                "Cannot mix create modes. Use exactly one of: 'dependencies' array or 'pattern' shortcut. Found: ${modes.joinToString(", ")}"
            )
        }

        when {
            dependenciesArray != null -> validateDependenciesArray(dependenciesArray)
            pattern != null -> validatePattern(params, pattern)
            else -> validateLegacyCreate(params)
        }
    }

    private fun validateDependenciesArray(dependenciesArray: JsonArray) {
        if (dependenciesArray.isEmpty()) {
            throw ToolValidationException("'dependencies' array must not be empty")
        }

        for ((index, element) in dependenciesArray.withIndex()) {
            val obj = element as? JsonObject
                ?: throw ToolValidationException("dependencies[$index] must be a JSON object")

            val fromStr = obj["fromTaskId"]?.jsonPrimitive?.content
                ?: throw ToolValidationException("dependencies[$index] missing required 'fromTaskId'")
            val toStr = obj["toTaskId"]?.jsonPrimitive?.content
                ?: throw ToolValidationException("dependencies[$index] missing required 'toTaskId'")

            val fromId = try {
                UUID.fromString(fromStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("dependencies[$index].fromTaskId is not a valid UUID: $fromStr")
            }

            val toId = try {
                UUID.fromString(toStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("dependencies[$index].toTaskId is not a valid UUID: $toStr")
            }

            if (fromId == toId) {
                throw ToolValidationException("dependencies[$index]: a task cannot depend on itself (fromTaskId == toTaskId)")
            }

            val typeStr = obj["type"]?.jsonPrimitive?.content
            if (typeStr != null) {
                try {
                    DependencyType.fromString(typeStr)
                } catch (_: ValidationException) {
                    throw ToolValidationException("dependencies[$index].type is invalid: $typeStr. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO")
                }
            }
        }
    }

    private fun validatePattern(params: JsonElement, pattern: String) {
        when (pattern) {
            "linear" -> {
                val taskIds = optionalJsonArray(params, "taskIds")
                    ?: throw ToolValidationException("Pattern 'linear' requires 'taskIds' array")
                if (taskIds.size < 2) {
                    throw ToolValidationException("Pattern 'linear' requires at least 2 task IDs in 'taskIds'")
                }
                // Validate each UUID
                for ((index, element) in taskIds.withIndex()) {
                    val str = element.jsonPrimitive.content
                    try {
                        UUID.fromString(str)
                    } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("taskIds[$index] is not a valid UUID: $str")
                    }
                }
                // Check for duplicates
                val uuids = taskIds.map { it.jsonPrimitive.content }
                if (uuids.size != uuids.toSet().size) {
                    throw ToolValidationException("taskIds contains duplicate task IDs")
                }
            }
            "fan-out" -> {
                val source = optionalString(params, "source")
                    ?: throw ToolValidationException("Pattern 'fan-out' requires 'source' parameter")
                try {
                    UUID.fromString(source)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("'source' is not a valid UUID: $source")
                }
                val targets = optionalJsonArray(params, "targets")
                    ?: throw ToolValidationException("Pattern 'fan-out' requires 'targets' array")
                if (targets.isEmpty()) {
                    throw ToolValidationException("Pattern 'fan-out' requires at least 1 target in 'targets'")
                }
                for ((index, element) in targets.withIndex()) {
                    val str = element.jsonPrimitive.content
                    try {
                        val targetId = UUID.fromString(str)
                        if (targetId == UUID.fromString(source)) {
                            throw ToolValidationException("targets[$index]: target cannot be the same as source")
                        }
                    } catch (e: IllegalArgumentException) {
                        if (e.message?.contains("Invalid UUID") == true || e !is ToolValidationException) {
                            throw ToolValidationException("targets[$index] is not a valid UUID: $str")
                        }
                        throw e
                    }
                }
            }
            "fan-in" -> {
                val target = optionalString(params, "target")
                    ?: throw ToolValidationException("Pattern 'fan-in' requires 'target' parameter")
                try {
                    UUID.fromString(target)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("'target' is not a valid UUID: $target")
                }
                val sources = optionalJsonArray(params, "sources")
                    ?: throw ToolValidationException("Pattern 'fan-in' requires 'sources' array")
                if (sources.isEmpty()) {
                    throw ToolValidationException("Pattern 'fan-in' requires at least 1 source in 'sources'")
                }
                for ((index, element) in sources.withIndex()) {
                    val str = element.jsonPrimitive.content
                    try {
                        val sourceId = UUID.fromString(str)
                        if (sourceId == UUID.fromString(target)) {
                            throw ToolValidationException("sources[$index]: source cannot be the same as target")
                        }
                    } catch (e: IllegalArgumentException) {
                        if (e.message?.contains("Invalid UUID") == true || e !is ToolValidationException) {
                            throw ToolValidationException("sources[$index] is not a valid UUID: $str")
                        }
                        throw e
                    }
                }
            }
            else -> throw ToolValidationException("Invalid pattern: $pattern. Must be one of: linear, fan-out, fan-in")
        }

        // Validate type override if provided
        val typeStr = optionalString(params, "type")
        if (typeStr != null) {
            try {
                DependencyType.fromString(typeStr)
            } catch (_: ValidationException) {
                throw ToolValidationException("Invalid dependency type: $typeStr. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO")
            }
        }
    }

    private fun validateLegacyCreate(params: JsonElement) {
        // Validate required fromTaskId parameter
        val fromTaskIdStr = requireString(params, "fromTaskId")
        val fromTaskId = try {
            UUID.fromString(fromTaskIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid fromTaskId format. Must be a valid UUID.")
        }

        // Validate required toTaskId parameter
        val toTaskIdStr = requireString(params, "toTaskId")
        val toTaskId = try {
            UUID.fromString(toTaskIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid toTaskId format. Must be a valid UUID.")
        }

        // Validate that fromTaskId and toTaskId are different
        if (fromTaskId == toTaskId) {
            throw ToolValidationException("A task cannot depend on itself. fromTaskId and toTaskId must be different.")
        }

        // Validate dependency type if provided
        val typeStr = optionalString(params, "type")
        if (typeStr != null) {
            try {
                DependencyType.fromString(typeStr)
            } catch (e: ValidationException) {
                throw ToolValidationException("Invalid dependency type: $typeStr. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO")
            }
        }
    }

    private fun validateDeleteParams(params: JsonElement) {
        val id = optionalString(params, "id")
        val fromTaskId = optionalString(params, "fromTaskId")
        val toTaskId = optionalString(params, "toTaskId")
        val deleteAll = optionalBoolean(params, "deleteAll", false)

        // Validate that at least one deletion method is specified
        if (id == null && fromTaskId == null && toTaskId == null) {
            throw ToolValidationException("Must specify either 'id' for specific dependency deletion, or 'fromTaskId'/'toTaskId' for relationship-based deletion")
        }

        // Validate UUID formats
        if (id != null) {
            try {
                UUID.fromString(id)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid dependency ID format. Must be a valid UUID.")
            }

            // If ID is provided, other parameters should not be used
            if (fromTaskId != null || toTaskId != null) {
                throw ToolValidationException("Cannot specify both 'id' and task relationship parameters (fromTaskId/toTaskId)")
            }
        }

        if (fromTaskId != null) {
            try {
                UUID.fromString(fromTaskId)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid fromTaskId format. Must be a valid UUID.")
            }
        }

        if (toTaskId != null) {
            try {
                UUID.fromString(toTaskId)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid toTaskId format. Must be a valid UUID.")
            }
        }

        // Validate deleteAll usage
        if (deleteAll) {
            if (fromTaskId != null && toTaskId != null) {
                throw ToolValidationException("When using 'deleteAll=true', specify only one of 'fromTaskId' or 'toTaskId', not both")
            }
            if (fromTaskId == null && toTaskId == null) {
                throw ToolValidationException("When using 'deleteAll=true', must specify either 'fromTaskId' or 'toTaskId'")
            }
        } else {
            // For specific relationship deletion, both tasks must be specified
            if ((fromTaskId != null || toTaskId != null) && id == null) {
                if (fromTaskId == null || toTaskId == null) {
                    throw ToolValidationException("For relationship-based deletion, must specify both 'fromTaskId' and 'toTaskId' (or use 'deleteAll=true' with only one)")
                }
            }
        }

        // Validate dependency type if provided
        val type = optionalString(params, "type")
        if (type != null) {
            try {
                DependencyType.fromString(type)
            } catch (e: ValidationException) {
                throw ToolValidationException("Invalid dependency type: $type. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO")
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing manage_dependencies tool")

        return try {
            val operation = requireString(params, "operation")

            when (operation) {
                "create" -> executeCreate(params, context)
                "delete" -> executeDelete(params, context)
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in manage_dependencies: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in manage_dependencies", e)
            errorResponse(
                message = "Failed to execute dependency operation",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== CREATE OPERATION ==========

    private suspend fun executeCreate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing create operation for dependencies")

        try {
            // Resolve dependencies from whichever mode was used
            val dependencyDefs = resolveDependencyDefinitions(params)
            val defaultType = optionalString(params, "type")?.let { DependencyType.fromString(it) } ?: DependencyType.BLOCKS

            // Validate all referenced tasks exist
            val allTaskIds = dependencyDefs.flatMap { listOf(it.first, it.second) }.toSet()
            for (taskId in allTaskIds) {
                val taskResult = context.taskRepository().getById(taskId)
                if (taskResult is Result.Error) {
                    return if (taskResult.error is RepositoryError.NotFound) {
                        errorResponse(
                            message = "Task not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No task exists with ID ${shortId(taskId.toString())} ($taskId)"
                        )
                    } else {
                        errorResponse(
                            message = "Error retrieving task",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = taskResult.error.message
                        )
                    }
                }
            }

            // Build Dependency objects
            val dependencies = dependencyDefs.map { (fromId, toId, typeOverride) ->
                Dependency(
                    fromTaskId = fromId,
                    toTaskId = toId,
                    type = typeOverride ?: defaultType
                )
            }

            // Use batch create for atomic validation and insertion
            val createdDependencies = context.dependencyRepository().createBatch(dependencies)

            // Build response
            val createdArray = createdDependencies.map { dep ->
                buildJsonObject {
                    put("id", dep.id.toString())
                    put("fromTaskId", dep.fromTaskId.toString())
                    put("toTaskId", dep.toTaskId.toString())
                    put("type", dep.type.name)
                    put("createdAt", dep.createdAt.toString())
                }
            }

            val message = if (createdDependencies.size == 1) {
                "Dependency created successfully"
            } else {
                "${createdDependencies.size} dependencies created successfully"
            }

            return successResponse(
                buildJsonObject {
                    put("createdCount", createdDependencies.size)
                    put("dependencies", JsonArray(createdArray))
                },
                message
            )
        } catch (e: ValidationException) {
            logger.warn("Validation error creating dependencies: ${e.message}")
            return errorResponse(
                message = "Validation failed",
                code = ErrorCodes.VALIDATION_ERROR,
                details = e.message ?: "Unknown validation error"
            )
        } catch (e: Exception) {
            logger.error("Unexpected error creating dependencies", e)
            return errorResponse(
                message = "Internal error creating dependencies",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Resolves dependency definitions from the three possible create modes.
     * Returns a list of triples: (fromTaskId, toTaskId, typeOverride?)
     */
    private fun resolveDependencyDefinitions(params: JsonElement): List<Triple<UUID, UUID, DependencyType?>> {
        val dependenciesArray = optionalJsonArray(params, "dependencies")
        val pattern = optionalString(params, "pattern")

        return when {
            dependenciesArray != null -> resolveFromArray(dependenciesArray)
            pattern != null -> resolveFromPattern(params, pattern)
            else -> resolveFromLegacy(params)
        }
    }

    private fun resolveFromArray(array: JsonArray): List<Triple<UUID, UUID, DependencyType?>> {
        return array.map { element ->
            val obj = element.jsonObject
            val fromId = UUID.fromString(obj["fromTaskId"]!!.jsonPrimitive.content)
            val toId = UUID.fromString(obj["toTaskId"]!!.jsonPrimitive.content)
            val typeStr = obj["type"]?.jsonPrimitive?.content
            val type = typeStr?.let { DependencyType.fromString(it) }
            Triple(fromId, toId, type)
        }
    }

    private fun resolveFromPattern(params: JsonElement, pattern: String): List<Triple<UUID, UUID, DependencyType?>> {
        return when (pattern) {
            "linear" -> {
                val taskIds = optionalJsonArray(params, "taskIds")!!.map {
                    UUID.fromString(it.jsonPrimitive.content)
                }
                // Chain: A→B, B→C, C→D
                taskIds.zipWithNext().map { (from, to) -> Triple(from, to, null) }
            }
            "fan-out" -> {
                val sourceId = UUID.fromString(requireString(params, "source"))
                val targetIds = optionalJsonArray(params, "targets")!!.map {
                    UUID.fromString(it.jsonPrimitive.content)
                }
                targetIds.map { targetId -> Triple(sourceId, targetId, null) }
            }
            "fan-in" -> {
                val targetId = UUID.fromString(requireString(params, "target"))
                val sourceIds = optionalJsonArray(params, "sources")!!.map {
                    UUID.fromString(it.jsonPrimitive.content)
                }
                sourceIds.map { sourceId -> Triple(sourceId, targetId, null) }
            }
            else -> throw ToolValidationException("Invalid pattern: $pattern")
        }
    }

    private fun resolveFromLegacy(params: JsonElement): List<Triple<UUID, UUID, DependencyType?>> {
        val fromId = UUID.fromString(requireString(params, "fromTaskId"))
        val toId = UUID.fromString(requireString(params, "toTaskId"))
        return listOf(Triple(fromId, toId, null))
    }

    // ========== DELETE OPERATION ==========

    private suspend fun executeDelete(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing delete operation for dependency")

        try {
            // Extract parameters
            val id = optionalString(params, "id")
            val fromTaskIdStr = optionalString(params, "fromTaskId")
            val toTaskIdStr = optionalString(params, "toTaskId")
            val typeStr = optionalString(params, "type")
            val deleteAll = optionalBoolean(params, "deleteAll", false)

            // Convert to UUIDs
            val dependencyId = id?.let { UUID.fromString(it) }
            val fromTaskId = fromTaskIdStr?.let { UUID.fromString(it) }
            val toTaskId = toTaskIdStr?.let { UUID.fromString(it) }
            val dependencyType = typeStr?.let { DependencyType.fromString(it) }

            var deletedCount = 0
            val deletedDependencies = mutableListOf<JsonObject>()

            when {
                // Delete by specific dependency ID
                dependencyId != null -> {
                    // Get the dependency before deletion to include in response
                    val dependency = context.dependencyRepository().findById(dependencyId)
                    if (dependency == null) {
                        return errorResponse(
                            message = "Dependency not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No dependency exists with ID $dependencyId"
                        )
                    }

                    val success = context.dependencyRepository().delete(dependencyId)
                    if (success) {
                        deletedCount = 1
                        deletedDependencies.add(
                            buildJsonObject {
                                put("id", dependency.id.toString())
                                put("fromTaskId", dependency.fromTaskId.toString())
                                put("toTaskId", dependency.toTaskId.toString())
                                put("type", dependency.type.name)
                            }
                        )
                    }
                }

                // Delete all dependencies for a specific task
                deleteAll && (fromTaskId != null || toTaskId != null) -> {
                    val taskId = fromTaskId ?: toTaskId!!

                    // Get dependencies before deletion to include in response
                    val dependencies = context.dependencyRepository().findByTaskId(taskId)

                    // Filter by type if specified
                    val filteredDependencies = if (dependencyType != null) {
                        dependencies.filter { it.type == dependencyType }
                    } else {
                        dependencies
                    }

                    // Delete the dependencies
                    deletedCount = context.dependencyRepository().deleteByTaskId(taskId)

                    // Add to response (limited by what was actually filtered)
                    filteredDependencies.forEach { dependency ->
                        deletedDependencies.add(
                            buildJsonObject {
                                put("id", dependency.id.toString())
                                put("fromTaskId", dependency.fromTaskId.toString())
                                put("toTaskId", dependency.toTaskId.toString())
                                put("type", dependency.type.name)
                            }
                        )
                    }
                }

                // Delete by task relationship
                fromTaskId != null && toTaskId != null -> {
                    // Find matching dependencies
                    val allDependencies = context.dependencyRepository().findByTaskId(fromTaskId)
                    val matchingDependencies = allDependencies.filter { dependency ->
                        dependency.fromTaskId == fromTaskId &&
                                dependency.toTaskId == toTaskId &&
                                (dependencyType == null || dependency.type == dependencyType)
                    }

                    if (matchingDependencies.isEmpty()) {
                        return errorResponse(
                            message = "No matching dependencies found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No dependencies found between tasks $fromTaskId and $toTaskId" +
                                    if (dependencyType != null) " of type $dependencyType" else ""
                        )
                    }

                    // Delete each matching dependency
                    matchingDependencies.forEach { dependency ->
                        val success = context.dependencyRepository().delete(dependency.id)
                        if (success) {
                            deletedCount++
                            deletedDependencies.add(
                                buildJsonObject {
                                    put("id", dependency.id.toString())
                                    put("fromTaskId", dependency.fromTaskId.toString())
                                    put("toTaskId", dependency.toTaskId.toString())
                                    put("type", dependency.type.name)
                                }
                            )
                        }
                    }
                }

                else -> {
                    return errorResponse(
                        message = "Invalid deletion parameters",
                        code = ErrorCodes.VALIDATION_ERROR,
                        details = "Must specify valid deletion criteria"
                    )
                }
            }

            return successResponse(
                buildJsonObject {
                    put("deletedCount", deletedCount)
                    put("deletedDependencies", JsonArray(deletedDependencies))
                },
                if (deletedCount == 1) "Dependency deleted successfully" else "$deletedCount dependencies deleted successfully"
            )

        } catch (e: Exception) {
            logger.error("Unexpected error deleting dependency", e)
            return errorResponse(
                message = "Internal error deleting dependency",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return super.userSummary(params, result, true)
        val p = params as? JsonObject ?: return "Dependency operation completed"
        val operation = p["operation"]?.jsonPrimitive?.content ?: "unknown"
        val data = (result as? JsonObject)?.get("data")?.jsonObject
        return when (operation) {
            "create" -> {
                val count = data?.get("createdCount")?.jsonPrimitive?.int ?: 0
                if (count == 1) {
                    val deps = data?.get("dependencies")?.jsonArray
                    val dep = deps?.firstOrNull()?.jsonObject
                    val fromId = dep?.get("fromTaskId")?.jsonPrimitive?.content?.let { shortId(it) } ?: ""
                    val toId = dep?.get("toTaskId")?.jsonPrimitive?.content?.let { shortId(it) } ?: ""
                    val type = dep?.get("type")?.jsonPrimitive?.content ?: ""
                    "Created $type dependency: $fromId -> $toId"
                } else {
                    "Created $count dependencies"
                }
            }
            "delete" -> {
                val count = data?.get("deletedCount")?.jsonPrimitive?.content ?: "0"
                "Deleted $count dependenc${if (count == "1") "y" else "ies"}"
            }
            else -> "Dependency operation completed"
        }
    }
}
