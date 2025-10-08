package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for deleting task dependencies by dependency ID or by task relationship criteria.
 * 
 * This tool provides flexible dependency deletion capabilities, allowing removal by specific 
 * dependency ID or by task relationship criteria (from/to task pairs with optional type filtering).
 */
class DeleteDependencyTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "delete_dependency"

    override val title: String = "Delete Task Dependency"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Deletion result information"),
                        "properties" to JsonObject(
                            mapOf(
                                "deletedCount" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Number of dependencies deleted"))),
                                "deletedDependencies" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Array of deleted dependency objects"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                        "fromTaskId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                        "toTaskId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                        "type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO").map { JsonPrimitive(it) })))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """Deletes task dependencies by dependency ID or by task relationship criteria.
        
        This tool provides flexible dependency deletion capabilities. You can delete dependencies:
        - By specific dependency ID (most precise)
        - By task relationship (fromTaskId and toTaskId with optional type filter)
        - All dependencies for a specific task (using deleteAll parameter)
        
        Example successful response:
        {
          "success": true,
          "message": "Dependency deleted successfully",
          "data": {
            "deletedCount": 1,
            "deletedDependencies": [
              {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "fromTaskId": "661e8511-f30c-41d4-a716-557788990000",
                "toTaskId": "772f9622-g41d-52e5-b827-668899101111",
                "type": "BLOCKS"
              }
            ]
          }
        }
        
        Common error responses:
        - RESOURCE_NOT_FOUND: When the specified dependency doesn't exist
        - VALIDATION_ERROR: When provided parameters fail validation
        - DATABASE_ERROR: When there's an issue deleting the dependency
        - INTERNAL_ERROR: For unexpected system errors"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the specific dependency to delete (mutually exclusive with task relationship parameters)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "fromTaskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the source task (used with toTaskId for relationship-based deletion)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "toTaskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the target task (used with fromTaskId for relationship-based deletion)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "type" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by dependency type when using task relationship deletion (optional)"),
                        "enum" to JsonArray(listOf(
                            JsonPrimitive("BLOCKS"),
                            JsonPrimitive("IS_BLOCKED_BY"),
                            JsonPrimitive("RELATES_TO")
                        ))
                    )
                ),
                "deleteAll" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("When used with fromTaskId OR toTaskId (not both), deletes all dependencies for that task"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf()
    )

    override fun validateParams(params: JsonElement) {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

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

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing delete_dependency tool")

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
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(dependency.id.toString()),
                                    "fromTaskId" to JsonPrimitive(dependency.fromTaskId.toString()),
                                    "toTaskId" to JsonPrimitive(dependency.toTaskId.toString()),
                                    "type" to JsonPrimitive(dependency.type.name)
                                )
                            )
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
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(dependency.id.toString()),
                                    "fromTaskId" to JsonPrimitive(dependency.fromTaskId.toString()),
                                    "toTaskId" to JsonPrimitive(dependency.toTaskId.toString()),
                                    "type" to JsonPrimitive(dependency.type.name)
                                )
                            )
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
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(dependency.id.toString()),
                                        "fromTaskId" to JsonPrimitive(dependency.fromTaskId.toString()),
                                        "toTaskId" to JsonPrimitive(dependency.toTaskId.toString()),
                                        "type" to JsonPrimitive(dependency.type.name)
                                    )
                                )
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
                message = if (deletedCount == 1) "Dependency deleted successfully" else "$deletedCount dependencies deleted successfully",
                data = JsonObject(
                    mapOf(
                        "deletedCount" to JsonPrimitive(deletedCount),
                        "deletedDependencies" to JsonArray(deletedDependencies)
                    )
                )
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