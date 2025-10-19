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
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * Consolidated MCP tool for write operations on task dependencies.
 * Includes 2 operations: create and delete with comprehensive validation.
 *
 * This tool provides unified dependency management with validation for task existence,
 * cycle detection, and duplicate prevention.
 */
class ManageDependencyTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "manage_dependency"

    override val title: String = "Manage Task Dependency"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        ),
        required = listOf("success", "message")
    )

    override fun shouldUseLocking(): Boolean = true

    override val description: String = """Unified write operations for task dependencies.

Operations: create, delete

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | create, delete |
| id | UUID | Varies | Dependency ID (required for: delete by ID) |
| fromTaskId | UUID | Varies | Source task ID (required for: create; optional for: delete) |
| toTaskId | UUID | Varies | Target task ID (required for: create; optional for: delete) |
| type | enum | No | Dependency type (BLOCKS, IS_BLOCKED_BY, RELATES_TO) |
| deleteAll | boolean | No | Delete all dependencies for task (delete only, default: false) |

Dependency Types:
- BLOCKS: Source blocks target (target cannot start until source complete)
- IS_BLOCKED_BY: Source is blocked by target (inverse of BLOCKS)
- RELATES_TO: General relationship (no blocking)

Validation (create):
- Both tasks must exist
- Prevents circular dependencies (cycle detection)
- Prevents duplicate dependencies
- Validates UUID formats

Delete Methods:
- By dependency ID: Provide 'id' parameter
- By task relationship: Provide 'fromTaskId' + 'toTaskId' (optional 'type' filter)
- All for a task: Provide 'fromTaskId' OR 'toTaskId' with 'deleteAll=true'

Usage: Consolidates create/delete for task dependencies with comprehensive validation.
Related: get_task_dependencies, manage_container, query_tasks
Docs: task-orchestrator://docs/tools/manage-dependency
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(listOf("create", "delete").map { JsonPrimitive(it) })
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
                        "description" to JsonPrimitive("Source task ID (required for: create; optional for: delete by relationship)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "toTaskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Target task ID (required for: create; optional for: delete by relationship)"),
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
        logger.info("Executing manage_dependency tool")

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
            logger.warn("Validation error in manage_dependency: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in manage_dependency", e)
            errorResponse(
                message = "Failed to execute dependency operation",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== CREATE OPERATION ==========

    private suspend fun executeCreate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing create operation for dependency")

        try {
            // Extract and validate parameters
            val fromTaskIdStr = requireString(params, "fromTaskId")
            val toTaskIdStr = requireString(params, "toTaskId")
            val fromTaskId = UUID.fromString(fromTaskIdStr)
            val toTaskId = UUID.fromString(toTaskIdStr)

            val typeStr = optionalString(params, "type") ?: "BLOCKS"
            val dependencyType = DependencyType.fromString(typeStr)

            // Validate that both tasks exist
            val fromTaskResult = context.taskRepository().getById(fromTaskId)
            if (fromTaskResult is Result.Error) {
                return if (fromTaskResult.error is RepositoryError.NotFound) {
                    errorResponse(
                        message = "Source task not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No task exists with fromTaskId $fromTaskId"
                    )
                } else {
                    errorResponse(
                        message = "Error retrieving source task",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = fromTaskResult.error.message
                    )
                }
            }

            val toTaskResult = context.taskRepository().getById(toTaskId)
            if (toTaskResult is Result.Error) {
                return if (toTaskResult.error is RepositoryError.NotFound) {
                    errorResponse(
                        message = "Target task not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No task exists with toTaskId $toTaskId"
                    )
                } else {
                    errorResponse(
                        message = "Error retrieving target task",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = toTaskResult.error.message
                    )
                }
            }

            // Check for self-dependency (task cannot depend on itself)
            if (fromTaskId == toTaskId) {
                return errorResponse(
                    message = "Self-dependency not allowed",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "A task cannot depend on itself. fromTaskId and toTaskId must be different."
                )
            }

            // Check for duplicate dependency
            val existingDependencies = context.dependencyRepository().findByFromTaskId(fromTaskId)
            val duplicate = existingDependencies.find {
                it.toTaskId == toTaskId && it.type == dependencyType
            }
            if (duplicate != null) {
                return errorResponse(
                    message = "Duplicate dependency",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "A dependency of type $dependencyType already exists between these tasks (ID: ${duplicate.id})"
                )
            }

            // Check for circular dependency (cycle detection)
            val wouldCreateCycle = context.dependencyRepository().hasCyclicDependency(fromTaskId, toTaskId)
            if (wouldCreateCycle) {
                return errorResponse(
                    message = "Circular dependency detected",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "Creating this dependency would create a circular dependency chain. Tasks cannot depend on each other directly or indirectly."
                )
            }

            // Create the dependency
            val dependency = Dependency(
                fromTaskId = fromTaskId,
                toTaskId = toTaskId,
                type = dependencyType
            )

            // Create the dependency in the repository
            val createdDependency = context.dependencyRepository().create(dependency)

            return successResponse(
                buildJsonObject {
                    put("id", createdDependency.id.toString())
                    put("fromTaskId", createdDependency.fromTaskId.toString())
                    put("toTaskId", createdDependency.toTaskId.toString())
                    put("type", createdDependency.type.name)
                    put("createdAt", createdDependency.createdAt.toString())
                },
                "Dependency created successfully"
            )

        } catch (e: ValidationException) {
            logger.warn("Validation error creating dependency: ${e.message}")
            return errorResponse(
                message = "Validation failed",
                code = ErrorCodes.VALIDATION_ERROR,
                details = e.message ?: "Unknown validation error"
            )
        } catch (e: Exception) {
            logger.error("Unexpected error creating dependency", e)
            return errorResponse(
                message = "Internal error creating dependency",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
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
}
