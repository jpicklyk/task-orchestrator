package io.github.jpicklyk.mcptask.application.tools.feature

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
 * MCP tool for retrieving feature details with options for including related tasks.
 *
 * This tool provides detailed access to a specific feature, with options to include related
 * entities like tasks, task statistics, and more. Features are high-level entities that can
 * group related tasks together.
 *
 * Features can have multiple tasks associated with them. When retrieving a feature,
 * you can choose to include basic task information or just statistical counts.
 *
 * Related tools:
 * - create_feature: To create a new feature
 * - update_feature: To modify an existing feature
 * - delete_feature: To remove a feature
 * - search_features: To find features by various criteria
 * - get_tasks: To retrieve just the tasks for this feature
 */
class GetFeatureTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.FEATURE_MANAGEMENT

    override val name: String = "get_feature"

    override val title: String = "Get Feature Details"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The requested feature with optional related entities"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "status" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("planning", "in-development", "completed", "archived").map { JsonPrimitive(it) })
                                    )
                                ),
                                "priority" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("high", "medium", "low").map { JsonPrimitive(it) })
                                    )
                                ),
                                "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "modifiedAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "projectId" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), "format" to JsonPrimitive("uuid"))),
                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                                "taskCounts" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Task statistics (present when includeTaskCounts=true)"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "total" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "byStatus" to JsonObject(
                                                    mapOf(
                                                        "type" to JsonPrimitive("object"),
                                                        "additionalProperties" to JsonObject(mapOf("type" to JsonPrimitive("integer")))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "sections" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Array of sections (present when includeSections=true)")
                                    )
                                ),
                                "tasks" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Tasks information (present when includeTasks=true)"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "items" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                                                "total" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "included" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "hasMore" to JsonObject(mapOf("type" to JsonPrimitive("boolean")))
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

    override val description: String = """Retrieves a feature by ID with optional related entities.

Parameters:
| Field | Type | Required | Default | Description |
| id | UUID | Yes | - | Feature identifier |
| includeSections | boolean | No | false | Include detailed content sections |
| includeTasks | boolean | No | false | Include associated tasks |
| maxTaskCount | integer | No | 10 | Maximum tasks to include (1-100) |
| includeTaskCounts | boolean | No | false | Include task statistics by status |
| includeTaskDependencies | boolean | No | false | Include dependency info for tasks |
| summaryView | boolean | No | false | Truncate text fields for efficiency |

Usage notes:
- Features store detailed content in separate Section entities. Use includeSections=true for complete feature context.
- Default response includes only basic feature metadata and summary.
- Use summaryView=true to reduce token usage for large datasets.

Related: create_feature, update_feature, delete_feature, search_features, get_sections

For detailed examples and patterns: task-orchestrator://docs/tools/get-feature
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the feature to retrieve")
                    )
                ),
                "includeTasks" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include basic task information in the response. Set to true when you need to see all tasks associated with this feature."),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeTaskCounts" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include task statistics grouped by status. Useful for getting a quick overview of task progress."),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeSections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include sections (detailed content blocks) that contain the full content of the feature."),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "maxTaskCount" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum number of tasks to include"),
                        "default" to JsonPrimitive(10)
                    )
                ),
                "summaryView" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to return a summarized view for context efficiency"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeTaskDependencies" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include dependency information for tasks when includeTasks is true"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "maxsummaryLength" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum length for summary (truncates if longer)"),
                        "default" to JsonPrimitive(500)
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid id format. Must be a valid UUID")
        }

        // Validate optional parameters
        optionalInt(params, "maxTaskCount")?.let { count ->
            if (count < 1) {
                throw ToolValidationException("maxTaskCount must be at least 1")
            }
            if (count > 100) {
                throw ToolValidationException("maxTaskCount cannot exceed 100")
            }
        }

        optionalInt(params, "maxsummaryLength")?.let { length ->
            if (length < 1) {
                throw ToolValidationException("maxsummaryLength must be at least 1")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_feature tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val featureId = UUID.fromString(idStr)
            val includeTasks = optionalBoolean(params, "includeTasks", false)
            val maxTaskCount = optionalInt(params, "maxTaskCount", 10)!!
            val includeTaskCounts = optionalBoolean(params, "includeTaskCounts", false)
            val includeTaskDependencies = optionalBoolean(params, "includeTaskDependencies", false)
            val includeSections = optionalBoolean(params, "includeSections", false)
            val summaryView = optionalBoolean(params, "summaryView", false)
            val maxSummaryLength = optionalInt(params, "maxsummaryLength", 500)!!

            // Get the feature
            val featureResult = context.featureRepository().getById(featureId)

            // Return standardized response
            return when (featureResult) {
                is Result.Success -> {
                    val feature = featureResult.data

                    // Process the summary based on length constraint
                    val processedSummary = feature.summary.let {
                        if (it.length > maxSummaryLength && summaryView) {
                            "${it.substring(0, maxSummaryLength)}..."
                        } else {
                            it
                        }
                    }

                    val data = buildJsonObject {
                        // Basic feature information
                        put("id", feature.id.toString())
                        put("name", feature.name)
                        put("summary", processedSummary)
                        put("status", feature.status.name.lowercase())
                        put("priority", feature.priority.name.lowercase())
                        put("createdAt", feature.createdAt.toString())
                        put("modifiedAt", feature.modifiedAt.toString())

                        // Include project ID if present
                        if (feature.projectId != null) {
                            put("projectId", feature.projectId.toString())
                        } else {
                            put("projectId", JsonNull)
                        }

                        // Ensure tags are properly included in the response
                        put("tags", buildJsonArray {
                            // Add debugging to see the actual tags
                            logger.info("Including tags for feature ${feature.id}: ${feature.tags}")
                            feature.tags.forEach { tag ->
                                add(JsonPrimitive(tag))
                            }
                        })

                        // If requested, include sections
                        if (includeSections) {
                            try {
                                val sectionsResult =
                                    context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, featureId)

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

                        // If requested, include task counts by status
                        if (includeTaskCounts) {
                            val tasksResult = context.taskRepository().findByFeature(featureId)

                            if (tasksResult is Result.Success) {
                                val tasks = tasksResult.data
                                val statusCounts = tasks.groupBy { it.status }
                                    .mapValues { it.value.size }

                                put("taskCounts", buildJsonObject {
                                    put("total", tasks.size)
                                    put("byStatus", buildJsonObject {
                                        statusCounts.forEach { (status, count) ->
                                            put(status.name.lowercase(), count)
                                        }
                                    })
                                })
                            } else {
                                put("taskCounts", buildJsonObject {
                                    put("total", 0)
                                    put("byStatus", buildJsonObject {})
                                })
                            }
                        }

                        // If requested, include task details
                        if (includeTasks) {
                            val tasksResult = context.taskRepository().findByFeature(featureId)

                            if (tasksResult is Result.Success) {
                                val tasks = tasksResult.data
                                val tasksToInclude = tasks.take(maxTaskCount)

                                // Calculate overall dependency statistics for the feature
                                var totalDependencies = 0
                                var totalIncomingDependencies = 0
                                var totalOutgoingDependencies = 0

                                put("tasks", buildJsonObject {
                                    put("items", buildJsonArray {
                                        tasksToInclude.forEach { task ->
                                            add(buildJsonObject {
                                                put("id", task.id.toString())
                                                put("title", task.title)
                                                put("status", task.status.name.lowercase())
                                                put("priority", task.priority.name.lowercase())
                                                put("complexity", task.complexity)
                                                
                                                // Include dependency information if requested
                                                if (includeTaskDependencies) {
                                                    try {
                                                        val allDependencies = context.dependencyRepository().findByTaskId(task.id)
                                                        val incomingDependencies = allDependencies.filter { it.toTaskId == task.id }
                                                        val outgoingDependencies = allDependencies.filter { it.fromTaskId == task.id }
                                                        
                                                        // Add to overall feature statistics
                                                        totalDependencies += allDependencies.size
                                                        totalIncomingDependencies += incomingDependencies.size
                                                        totalOutgoingDependencies += outgoingDependencies.size
                                                        
                                                        put("dependencies", buildJsonObject {
                                                            put("counts", buildJsonObject {
                                                                put("total", allDependencies.size)
                                                                put("incoming", incomingDependencies.size)
                                                                put("outgoing", outgoingDependencies.size)
                                                            })
                                                        })
                                                    } catch (e: Exception) {
                                                        logger.error("Error retrieving dependencies for task ${task.id}", e)
                                                        put("dependencies", buildJsonObject {
                                                            put("counts", buildJsonObject {
                                                                put("total", 0)
                                                                put("incoming", 0)
                                                                put("outgoing", 0)
                                                            })
                                                        })
                                                    }
                                                }
                                            })
                                        }
                                    })
                                    put("total", tasks.size)
                                    put("included", tasksToInclude.size)
                                    put("hasMore", tasks.size > maxTaskCount)
                                    
                                    // Include overall dependency statistics for the feature if requested
                                    if (includeTaskDependencies) {
                                        put("dependencyStatistics", buildJsonObject {
                                            put("totalDependencies", totalDependencies)
                                            put("totalIncomingDependencies", totalIncomingDependencies)
                                            put("totalOutgoingDependencies", totalOutgoingDependencies)
                                            put("tasksWithDependencies", tasksToInclude.count { task ->
                                                try {
                                                    context.dependencyRepository().findByTaskId(task.id).isNotEmpty()
                                                } catch (e: Exception) {
                                                    false
                                                }
                                            })
                                        })
                                    }
                                })
                            } else {
                                put("tasks", buildJsonObject {
                                    put("items", buildJsonArray {})
                                    put("total", 0)
                                    put("included", 0)
                                    put("hasMore", false)
                                    if (includeTaskDependencies) {
                                        put("dependencyStatistics", buildJsonObject {
                                            put("totalDependencies", 0)
                                            put("totalIncomingDependencies", 0)
                                            put("totalOutgoingDependencies", 0)
                                            put("tasksWithDependencies", 0)
                                        })
                                    }
                                })
                            }
                        }
                    }

                    successResponse(data, "Feature retrieved successfully")
                }

                is Result.Error -> {
                    if (featureResult.error is RepositoryError.NotFound) {
                        errorResponse(
                            message = "Feature not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No feature exists with ID $featureId"
                        )
                    } else {
                        errorResponse(
                            message = "Failed to retrieve feature",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = featureResult.error.toString()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error retrieving feature", e)
            return errorResponse(
                message = "Failed to retrieve feature",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}