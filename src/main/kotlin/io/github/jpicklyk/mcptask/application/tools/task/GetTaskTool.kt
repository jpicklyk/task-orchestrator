package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for retrieving a task by ID with options for including relationships.
 *
 * This tool provides detailed access to a specific task, with options to include related
 * entities like sections, feature information, and more. It can be used to get comprehensive
 * task information when needed, or a summary view to save tokens.
 *
 * Tasks are the primary work items in the system. They can exist independently or be
 * associated with Features. Detailed content is stored in Sections.
 *
 * Related tools:
 * - create_task: To create a new task
 * - update_task: To modify an existing task
 * - delete_task: To remove a task
 * - search_tasks: To find tasks by various criteria
 * - get_sections: To retrieve just the sections for this task
 */
class GetTaskTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "get_task"

    override val description: String = """Retrieves a task by its ID with options for including relationships.
        
        ## Purpose

        Fetches a complete task by its UUID with options to include related entities like 
        sections, subtasks, dependencies, and feature information for context. This tool allows
        getting detailed information about a specific task when its ID is known.
        
        Note: Tasks store their detailed content in separate Section entities for efficiency. 
        To retrieve the complete task with all content blocks, make sure to set includeSections=true.
        Otherwise, you'll only receive the basic task metadata and summary.
        
        ## Parameters
        
        | Parameter | Type | Required | Default | Description |
        |-----------|------|----------|---------|-------------|
        | id | UUID string | Yes | - | The unique ID of the task to retrieve (e.g., '550e8400-e29b-41d4-a716-446655440000') |
        | includeSubtasks | boolean | No | false | Whether to include subtasks in the response (experimental feature) |
        | includeDependencies | boolean | No | false | Whether to include dependencies in the response (experimental feature) |
        | includeFeature | boolean | No | false | Whether to include feature information if the task belongs to a feature |
        | includeSections | boolean | No | false | Whether to include sections (detailed content blocks) that contain the full content of the task. Set to true when you need the complete task context beyond the basic summary. |
        | summaryView | boolean | No | false | Whether to return a summarized view for context efficiency (truncates text fields) |
        
        ## Response Format
        
        ### Success Response
        
        ```json
        {
          "success": true,
          "message": "Task retrieved successfully",
          "data": {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "title": "Implement API",
            "summary": "Create REST API endpoints for data access",
            "status": "in-progress",
            "priority": "high",
            "complexity": 7,
            "createdAt": "2025-05-10T14:30:00Z",
            "modifiedAt": "2025-05-10T15:45:00Z",
            "featureId": "661e8511-f30c-41d4-a716-557788990000",
            "tags": ["api", "backend"],
            "feature": {
              "id": "661e8511-f30c-41d4-a716-557788990000",
              "name": "REST API Implementation",
              "status": "in-development"
            },
            "sections": [
              {
                "id": "772f9622-g41d-52e5-b827-668899101111",
                "title": "Requirements",
                "content": "The API should support CRUD operations...",
                "contentFormat": "markdown",
                "ordinal": 0
              }
            ]
          }
        }
        ```
        
        ## Error Responses
        
        - RESOURCE_NOT_FOUND (404): When no task exists with the specified ID 
          ```json
          {
            "success": false,
            "message": "Task not found",
            "error": {
              "code": "RESOURCE_NOT_FOUND",
              "details": "No task exists with ID 550e8400-e29b-41d4-a716-446655440000"
            }
          }
          ```
        
        - VALIDATION_ERROR (400): When the provided ID is not a valid UUID
          ```json
          {
            "success": false,
            "message": "Invalid input",
            "error": {
              "code": "VALIDATION_ERROR",
              "details": "Invalid task ID format. Must be a valid UUID."
            }
          }
          ```
        
        - DATABASE_ERROR (500): When there's an issue retrieving data from the database
        
        - INTERNAL_ERROR (500): For unexpected system errors during execution
        
        ## Usage Examples
        
        1. Get basic task information:
           ```json
           {
             "id": "550e8400-e29b-41d4-a716-446655440000"
           }
           ```
           
        2. Get task with all relationships:
           ```json
           {
             "id": "550e8400-e29b-41d4-a716-446655440000",
             "includeFeature": true,
             "includeSections": true,
             "includeSubtasks": true,
             "includeDependencies": true
           }
           ```
           
        3. Get summarized task information (for context efficiency):
           ```json
           {
             "id": "550e8400-e29b-41d4-a716-446655440000",
             "summaryView": true
           }
           ```
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID (UUID) of the task to retrieve (e.g., '550e8400-e29b-41d4-a716-446655440000')"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "includeSubtasks" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include subtasks in the response (experimental feature)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeDependencies" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include dependencies in the response (experimental feature)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeFeature" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include feature information in the response if the task belongs to a feature"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeSections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include sections (detailed content blocks) in the response"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "summaryView" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to return a summarized view for context efficiency (truncates text fields)"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required ID parameter
        val idStr = requireString(params, "id")

        // Validate ID format (must be a valid UUID)
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid task ID format. Must be a valid UUID.")
        }

        // Optional boolean parameters don't need validation as they default to false
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_task tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val taskId = UUID.fromString(idStr)
            val summaryView = optionalBoolean(params, "summaryView")
            val includeFeature = optionalBoolean(params, "includeFeature")
            val includeSections = optionalBoolean(params, "includeSections")

            // Get a task from repository
            val taskResult = context.taskRepository().getById(taskId)

            // Handle result
            return when (taskResult) {
                is Result.Success -> {
                    val task = taskResult.data
                    val dataObject = buildJsonObject {
                        // Basic task information
                        put("id", task.id.toString())
                        put("title", task.title)

                        // Full description or summary based on view type
                        if (!summaryView) {
                            put("summary", task.summary)
                        } else {
                            // For summary view, truncate description if too long
                            val truncatedDescription = if (task.summary.length > 100) {
                                "${task.summary.take(97)}..."
                            } else {
                                task.summary
                            }
                            put("summary", truncatedDescription)
                        }

                        put("status", task.status.name.lowercase())
                        put("priority", task.priority.name.lowercase())
                        put("complexity", task.complexity)
                        put("createdAt", task.createdAt.toString())
                        put("modifiedAt", task.modifiedAt.toString())

                        if (task.featureId != null) {
                            put("featureId", task.featureId.toString())

                            // If include feature is requested, fetch and include feature data
                            if (includeFeature) {
                                val featureResult = context.featureRepository().getById(task.featureId)
                                if (featureResult is Result.Success) {
                                    val feature = featureResult.data
                                    put("feature", buildJsonObject {
                                        put("id", feature.id.toString())
                                        put("name", feature.name)
                                        put("status", feature.status.name.lowercase())
                                        if (!summaryView) {
                                            put("summary", feature.summary)
                                        }
                                    })
                                }
                            }
                        } else {
                            put("featureId", JsonNull)
                        }

                        // Ensure tags are properly included in the response
                        put("tags", buildJsonArray {
                            // Add debugging to see the actual tags
                            logger.info("Including tags for task ${task.id}: ${task.tags}")
                            task.tags.forEach { tag ->
                                add(JsonPrimitive(tag))
                            }
                        })

                        // Include sections if requested
                        if (includeSections) {
                            try {
                                val sectionsResult =
                                    context.sectionRepository().getSectionsForEntity(EntityType.TASK, taskId)

                                if (sectionsResult is Result.Success) {
                                    put("sections", buildJsonArray {
                                        sectionsResult.data.forEach { section ->
                                            add(buildJsonObject {
                                                put("id", section.id.toString())
                                                put("title", section.title)
                                                put(
                                                    "content", if (summaryView && section.content.length > 100) {
                                                        "${section.content.take(97)}..."
                                                    } else {
                                                        section.content
                                                    }
                                                )
                                                put("contentFormat", section.contentFormat.name.lowercase())
                                                put("ordinal", section.ordinal)
                                            })
                                        }
                                    })
                                } else {
                                    put("sections", buildJsonArray {})
                                }
                            } catch (e: Exception) {
                                logger.error("Error retrieving sections", e)
                                put("sections", buildJsonArray {})
                            }
                        }
                    }

                    successResponse(dataObject, "Task retrieved successfully")
                }

                is Result.Error -> {
                    if (taskResult.error is RepositoryError.NotFound) {
                        errorResponse(
                            message = "Task not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No task exists with ID $taskId"
                        )
                    } else {
                        errorResponse(
                            message = "Failed to retrieve task",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = taskResult.error.toString()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error retrieving task", e)
            return errorResponse(
                message = "Failed to retrieve task",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}