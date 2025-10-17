package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Dependency
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for creating task dependencies with validation for task existence, cycle detection, and duplicate prevention.
 * 
 * Dependencies represent relationships between tasks that affect execution order and workflow planning.
 * This tool validates all aspects before creating dependencies to maintain data integrity.
 */
class CreateDependencyTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "create_dependency"

    override val title: String = "Create Task Dependency"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The created dependency object"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"), "description" to JsonPrimitive("Unique identifier for the dependency"))),
                                "fromTaskId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"), "description" to JsonPrimitive("ID of the source task"))),
                                "toTaskId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"), "description" to JsonPrimitive("ID of the target task"))),
                                "type" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO").map { JsonPrimitive(it) }),
                                        "description" to JsonPrimitive("Type of dependency relationship")
                                    )
                                ),
                                "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"), "description" to JsonPrimitive("ISO-8601 timestamp when dependency was created")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """Creates task dependency with validation for task existence, cycle detection, and duplicate prevention.

        Parameters:
        - fromTaskId (required): Source task UUID (task creating dependency)
        - toTaskId (required): Target task UUID (task affected by dependency)
        - type (optional): BLOCKS, IS_BLOCKED_BY, or RELATES_TO (default: BLOCKS)

        Dependency Types:
        - BLOCKS: Source blocks target (target cannot start until source complete)
        - IS_BLOCKED_BY: Source is blocked by target (inverse of BLOCKS)
        - RELATES_TO: General relationship (no blocking)

        Validation:
        - Both tasks must exist
        - Prevents circular dependencies (cycle detection)
        - Prevents duplicate dependencies
        - Validates UUID formats

        Usage notes:
        - Use BLOCKS for most workflow dependencies
        - Dependency affects get_blocked_tasks and get_next_task results
        - Use get_task_dependencies to view existing dependencies

        Related tools: get_task_dependencies, delete_dependency, get_blocked_tasks

For detailed examples and patterns: task-orchestrator://docs/tools/create-dependency
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "fromTaskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the source task (the task that creates the dependency)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "toTaskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the target task (the task that is affected by the dependency)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "type" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of dependency: 'BLOCKS' (source blocks target), 'IS_BLOCKED_BY' (source is blocked by target), or 'RELATES_TO' (general relationship)"),
                        "enum" to JsonArray(listOf(
                            JsonPrimitive("BLOCKS"),
                            JsonPrimitive("IS_BLOCKED_BY"), 
                            JsonPrimitive("RELATES_TO")
                        )),
                        "default" to JsonPrimitive("BLOCKS")
                    )
                )
            )
        ),
        required = listOf("fromTaskId", "toTaskId")
    )

    override fun validateParams(params: JsonElement) {
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

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing create_dependency tool")

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
                if (fromTaskResult.error is RepositoryError.NotFound) {
                    return errorResponse(
                        message = "Source task not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No task exists with fromTaskId $fromTaskId"
                    )
                } else {
                    return errorResponse(
                        message = "Error retrieving source task",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = fromTaskResult.error.message
                    )
                }
            }

            val toTaskResult = context.taskRepository().getById(toTaskId)
            if (toTaskResult is Result.Error) {
                if (toTaskResult.error is RepositoryError.NotFound) {
                    return errorResponse(
                        message = "Target task not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No task exists with toTaskId $toTaskId"
                    )
                } else {
                    return errorResponse(
                        message = "Error retrieving target task",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = toTaskResult.error.message
                    )
                }
            }

            // Create the dependency
            val dependency = Dependency(
                fromTaskId = fromTaskId,
                toTaskId = toTaskId,
                type = dependencyType
            )

            // Attempt to create the dependency (repository will handle cycle detection and duplicate validation)
            val createResult = context.dependencyRepository().create(dependency)

            return successResponse(
                message = "Dependency created successfully",
                data = JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(createResult.id.toString()),
                        "fromTaskId" to JsonPrimitive(createResult.fromTaskId.toString()),
                        "toTaskId" to JsonPrimitive(createResult.toTaskId.toString()),
                        "type" to JsonPrimitive(createResult.type.name),
                        "createdAt" to JsonPrimitive(createResult.createdAt.toString())
                    )
                )
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
}