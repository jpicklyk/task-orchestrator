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

    override val description: String = """Retrieves all dependencies for a task with filtering by type and direction.

        Parameters:
        - taskId (required): Task UUID
        - direction (optional): 'incoming', 'outgoing', or 'all' (default: all)
        - type (optional): 'BLOCKS', 'IS_BLOCKED_BY', 'RELATES_TO', or 'all' (default: all)
        - includeTaskInfo (optional): Include basic task info (title, status) for related tasks (default: false)

        Directions:
        - incoming: Dependencies pointing TO this task (tasks that block this task)
        - outgoing: Dependencies FROM this task (tasks this task blocks)
        - all: Both directions

        Returns:
        - dependencies object with incoming/outgoing arrays
        - counts object with total, incoming, outgoing, byType breakdown
        - Each dependency includes: id, fromTaskId, toTaskId, type, createdAt

        Usage notes:
        - Use direction filter to focus on specific relationship types
        - Use type filter to see only specific dependency types
        - includeTaskInfo adds title/status for related tasks (helpful for understanding context)

        Related tools: create_dependency, delete_dependency, get_blocked_tasks

        For detailed examples and patterns: task-orchestrator://docs/tools/get-task-dependencies
        """

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