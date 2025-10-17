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

    override val title: String = "Get Task Details"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The requested task with optional related entities"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "status" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("pending", "in-progress", "completed", "cancelled", "deferred").map { JsonPrimitive(it) })
                                    )
                                ),
                                "priority" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("high", "medium", "low").map { JsonPrimitive(it) })
                                    )
                                ),
                                "complexity" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(10))),
                                "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "modifiedAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "featureId" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), "format" to JsonPrimitive("uuid"))),
                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                                "feature" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Feature object (only present when includeFeature=true and task belongs to feature)"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "summary" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                            )
                                        )
                                    )
                                ),
                                "sections" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Array of sections (only present when includeSections=true)"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "content" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "contentFormat" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "ordinal" to JsonObject(mapOf("type" to JsonPrimitive("integer")))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "dependencies" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Dependency information (only present when includeDependencies=true)"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "incoming" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                                                "outgoing" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                                                "counts" to JsonObject(
                                                    mapOf(
                                                        "type" to JsonPrimitive("object"),
                                                        "properties" to JsonObject(
                                                            mapOf(
                                                                "total" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                                "incoming" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                                "outgoing" to JsonObject(mapOf("type" to JsonPrimitive("integer")))
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
                        "required" to JsonArray(listOf("id", "title", "summary", "status", "priority", "complexity", "createdAt", "modifiedAt", "tags").map { JsonPrimitive(it) })
                    )
                )
            )
        ),
        required = listOf("success", "message")
    )

    override val description: String = """Retrieves a task by ID with optional related entities.

Parameters:
| Field | Type | Required | Default | Description |
| id | UUID | Yes | - | Task identifier |
| includeSections | boolean | No | false | Include detailed content sections |
| includeFeature | boolean | No | false | Include parent feature information |
| includeDependencies | boolean | No | false | Include dependency information |
| summaryView | boolean | No | false | Truncate text fields for efficiency |

Usage notes:
- Tasks store detailed content in separate Section entities. Use includeSections=true for complete task context.
- Default response includes only basic task metadata and summary.
- Use summaryView=true to reduce token usage for large datasets.

Related: create_task, update_task, delete_task, search_tasks, get_sections

For detailed examples and patterns: task-orchestrator://docs/tools/get-task
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
                        "description" to JsonPrimitive("Whether to include dependency information (incoming and outgoing dependencies with counts)"),
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
            val includeDependencies = optionalBoolean(params, "includeDependencies")

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

                        // Include dependencies if requested
                        if (includeDependencies) {
                            try {
                                val allDependencies = context.dependencyRepository().findByTaskId(taskId)

                                // Separate incoming and outgoing dependencies
                                val incomingDependencies = allDependencies.filter { it.toTaskId == taskId }
                                val outgoingDependencies = allDependencies.filter { it.fromTaskId == taskId }

                                put("dependencies", buildJsonObject {
                                    put("incoming", buildJsonArray {
                                        incomingDependencies.forEach { dependency ->
                                            add(buildJsonObject {
                                                put("id", dependency.id.toString())
                                                put("fromTaskId", dependency.fromTaskId.toString())
                                                put("toTaskId", dependency.toTaskId.toString())
                                                put("type", dependency.type.name)
                                                put("createdAt", dependency.createdAt.toString())
                                            })
                                        }
                                    })
                                    put("outgoing", buildJsonArray {
                                        outgoingDependencies.forEach { dependency ->
                                            add(buildJsonObject {
                                                put("id", dependency.id.toString())
                                                put("fromTaskId", dependency.fromTaskId.toString())
                                                put("toTaskId", dependency.toTaskId.toString())
                                                put("type", dependency.type.name)
                                                put("createdAt", dependency.createdAt.toString())
                                            })
                                        }
                                    })
                                    put("counts", buildJsonObject {
                                        put("total", allDependencies.size)
                                        put("incoming", incomingDependencies.size)
                                        put("outgoing", outgoingDependencies.size)
                                    })
                                })
                            } catch (e: Exception) {
                                logger.error("Error retrieving dependencies", e)
                                put("dependencies", buildJsonObject {
                                    put("incoming", buildJsonArray {})
                                    put("outgoing", buildJsonArray {})
                                    put("counts", buildJsonObject {
                                        put("total", 0)
                                        put("incoming", 0)
                                        put("outgoing", 0)
                                    })
                                })
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