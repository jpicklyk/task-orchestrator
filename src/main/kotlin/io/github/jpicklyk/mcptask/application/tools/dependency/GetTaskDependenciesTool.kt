package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for retrieving all dependencies for a specific task with filtering options and dependency chain information.
 * 
 * This tool provides comprehensive dependency information for a task, including both incoming and outgoing dependencies.
 * It supports filtering by dependency type and direction for focused queries.
 */
class GetTaskDependenciesTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "get_task_dependencies"

    override val title: String = "Get Task Dependencies"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Task dependencies with optional filtering"),
                        "properties" to JsonObject(
                            mapOf(
                                "taskId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "dependencies" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Dependency objects grouped by direction or as array"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "incoming" to JsonObject(mapOf("type" to JsonPrimitive("array"), "description" to JsonPrimitive("Dependencies where other tasks depend on this task"))),
                                                "outgoing" to JsonObject(mapOf("type" to JsonPrimitive("array"), "description" to JsonPrimitive("Dependencies where this task depends on other tasks")))
                                            )
                                        )
                                    )
                                ),
                                "counts" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Count statistics for dependencies"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "total" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "incoming" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "outgoing" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "byType" to JsonObject(mapOf("type" to JsonPrimitive("object")))
                                            )
                                        )
                                    )
                                ),
                                "filters" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Applied filter parameters")
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

    override val description: String = """Retrieves all dependencies for a specific task with filtering options and dependency chain information.
        
        This tool provides comprehensive dependency information for a task, allowing you to understand 
        how a task relates to other tasks in the system. It supports filtering by dependency type 
        and direction for focused queries.
        
        Example successful response:
        {
          "success": true,
          "message": "Dependencies retrieved successfully",
          "data": {
            "taskId": "550e8400-e29b-41d4-a716-446655440000",
            "dependencies": {
              "incoming": [
                {
                  "id": "661e8511-f30c-41d4-a716-557788990000",
                  "fromTaskId": "772f9622-g41d-52e5-b827-668899101111",
                  "toTaskId": "550e8400-e29b-41d4-a716-446655440000",
                  "type": "BLOCKS",
                  "createdAt": "2025-05-10T14:30:00Z"
                }
              ],
              "outgoing": [
                {
                  "id": "883f0733-h52e-63f6-c938-779900212222",
                  "fromTaskId": "550e8400-e29b-41d4-a716-446655440000",
                  "toTaskId": "994f1844-i63f-74g7-d049-8800a1323333",
                  "type": "BLOCKS",
                  "createdAt": "2025-05-10T15:00:00Z"
                }
              ]
            },
            "counts": {
              "total": 2,
              "incoming": 1,
              "outgoing": 1,
              "byType": {
                "BLOCKS": 2,
                "IS_BLOCKED_BY": 0,
                "RELATES_TO": 0
              }
            }
          }
        }
        
        Common error responses:
        - RESOURCE_NOT_FOUND: When the specified task doesn't exist
        - VALIDATION_ERROR: When provided parameters fail validation
        - DATABASE_ERROR: When there's an issue retrieving dependencies
        - INTERNAL_ERROR: For unexpected system errors"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "taskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the task to retrieve dependencies for"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "direction" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by dependency direction: 'incoming' (dependencies pointing to this task), 'outgoing' (dependencies from this task), or 'all' (both directions)"),
                        "enum" to JsonArray(listOf(
                            JsonPrimitive("incoming"),
                            JsonPrimitive("outgoing"),
                            JsonPrimitive("all")
                        )),
                        "default" to JsonPrimitive("all")
                    )
                ),
                "type" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by dependency type: 'BLOCKS', 'IS_BLOCKED_BY', 'RELATES_TO', or 'all' for all types"),
                        "enum" to JsonArray(listOf(
                            JsonPrimitive("BLOCKS"),
                            JsonPrimitive("IS_BLOCKED_BY"),
                            JsonPrimitive("RELATES_TO"),
                            JsonPrimitive("all")
                        )),
                        "default" to JsonPrimitive("all")
                    )
                ),
                "includeTaskInfo" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include basic task information (title, status) for related tasks"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("taskId")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required taskId parameter
        val taskIdStr = requireString(params, "taskId")
        try {
            UUID.fromString(taskIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid taskId format. Must be a valid UUID.")
        }

        // Validate direction filter if provided
        val direction = optionalString(params, "direction")
        if (direction != null && direction !in listOf("incoming", "outgoing", "all")) {
            throw ToolValidationException("Invalid direction filter: $direction. Must be one of: incoming, outgoing, all")
        }

        // Validate type filter if provided
        val type = optionalString(params, "type")
        if (type != null && type != "all") {
            try {
                DependencyType.fromString(type)
            } catch (e: Exception) {
                throw ToolValidationException("Invalid dependency type: $type. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO, all")
            }
        }
    }

    /**
     * Helper function to build dependency objects with consistent filtering and enrichment.
     */
    private suspend fun buildDependencyObject(
        dependency: io.github.jpicklyk.mcptask.domain.model.Dependency,
        includeTaskInfo: Boolean,
        currentTaskId: UUID,
        context: ToolExecutionContext
    ): JsonObject {
        val baseObj = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(dependency.id.toString()),
            "fromTaskId" to JsonPrimitive(dependency.fromTaskId.toString()),
            "toTaskId" to JsonPrimitive(dependency.toTaskId.toString()),
            "type" to JsonPrimitive(dependency.type.name),
            "createdAt" to JsonPrimitive(dependency.createdAt.toString())
        )

        if (includeTaskInfo) {
            val relatedTaskId = if (dependency.fromTaskId == currentTaskId) 
                dependency.toTaskId else dependency.fromTaskId
            val relatedTaskResult = context.taskRepository().getById(relatedTaskId)
            if (relatedTaskResult is Result.Success) {
                baseObj["relatedTask"] = JsonObject(mapOf(
                    "id" to JsonPrimitive(relatedTaskResult.data.id.toString()),
                    "title" to JsonPrimitive(relatedTaskResult.data.title),
                    "status" to JsonPrimitive(relatedTaskResult.data.status.name)
                ))
            }
        }

        return JsonObject(baseObj)
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_task_dependencies tool")

        try {
            // Extract and validate parameters
            val taskIdStr = requireString(params, "taskId")
            val taskId = UUID.fromString(taskIdStr)
            
            val direction = optionalString(params, "direction") ?: "all"
            val typeFilter = optionalString(params, "type") ?: "all"
            val includeTaskInfo = optionalBoolean(params, "includeTaskInfo", false)

            // Validate that the task exists
            val taskResult = context.taskRepository().getById(taskId)
            if (taskResult is Result.Error) {
                if (taskResult.error is RepositoryError.NotFound) {
                    return errorResponse(
                        message = "Task not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No task exists with ID $taskId"
                    )
                } else {
                    return errorResponse(
                        message = "Error retrieving task",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = taskResult.error.message
                    )
                }
            }

            // Get all dependencies for the task
            val allDependencies = context.dependencyRepository().findByTaskId(taskId)

            // Separate incoming and outgoing dependencies
            val incomingDependencies = allDependencies.filter { it.toTaskId == taskId }
            val outgoingDependencies = allDependencies.filter { it.fromTaskId == taskId }

            // Apply direction filter
            val filteredDependencies = when (direction) {
                "incoming" -> incomingDependencies
                "outgoing" -> outgoingDependencies
                "all" -> allDependencies
                else -> allDependencies
            }

            // Apply type filter
            val finalDependencies = if (typeFilter != "all") {
                val targetType = DependencyType.fromString(typeFilter)
                filteredDependencies.filter { it.type == targetType }
            } else {
                filteredDependencies
            }

            // Build dependency objects
            val dependencyObjects = finalDependencies.map { dependency ->
                buildDependencyObject(dependency, includeTaskInfo, taskId, context)
            }

            // Apply type filtering consistently to incoming/outgoing dependencies for direction="all"
            val filteredIncoming = if (typeFilter != "all") {
                val targetType = DependencyType.fromString(typeFilter)
                incomingDependencies.filter { it.type == targetType }
            } else {
                incomingDependencies
            }
            
            val filteredOutgoing = if (typeFilter != "all") {
                val targetType = DependencyType.fromString(typeFilter)
                outgoingDependencies.filter { it.type == targetType }
            } else {
                outgoingDependencies
            }

            // Calculate counts based on filtered results
            val counts = JsonObject(
                mapOf(
                    "total" to JsonPrimitive(finalDependencies.size),
                    "incoming" to JsonPrimitive(filteredIncoming.size),
                    "outgoing" to JsonPrimitive(filteredOutgoing.size),
                    "byType" to JsonObject(
                        mapOf(
                            "BLOCKS" to JsonPrimitive(finalDependencies.count { it.type == DependencyType.BLOCKS }),
                            "IS_BLOCKED_BY" to JsonPrimitive(finalDependencies.count { it.type == DependencyType.IS_BLOCKED_BY }),
                            "RELATES_TO" to JsonPrimitive(finalDependencies.count { it.type == DependencyType.RELATES_TO })
                        )
                    )
                )
            )

            // Organize dependencies by direction with consistent filtering and enrichment
            val dependenciesData = if (direction == "all") {
                // Build enriched dependency objects for incoming/outgoing with filters applied
                val processedIncoming = filteredIncoming.map { 
                    buildDependencyObject(it, includeTaskInfo, taskId, context) 
                }
                val processedOutgoing = filteredOutgoing.map { 
                    buildDependencyObject(it, includeTaskInfo, taskId, context) 
                }
                
                JsonObject(
                    mapOf(
                        "incoming" to JsonArray(processedIncoming),
                        "outgoing" to JsonArray(processedOutgoing)
                    )
                )
            } else {
                JsonArray(dependencyObjects)
            }

            return successResponse(
                message = "Dependencies retrieved successfully",
                data = JsonObject(
                    mapOf(
                        "taskId" to JsonPrimitive(taskId.toString()),
                        "dependencies" to dependenciesData,
                        "counts" to counts,
                        "filters" to JsonObject(
                            mapOf(
                                "direction" to JsonPrimitive(direction),
                                "type" to JsonPrimitive(typeFilter),
                                "includeTaskInfo" to JsonPrimitive(includeTaskInfo)
                            )
                        )
                    )
                )
            )

        } catch (e: Exception) {
            logger.error("Unexpected error retrieving task dependencies", e)
            return errorResponse(
                message = "Internal error retrieving task dependencies",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}