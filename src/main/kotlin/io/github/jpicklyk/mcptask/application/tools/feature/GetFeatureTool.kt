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

    override val description: String = """Retrieves a feature by its ID with options for including relationships.
        
        ## Purpose

        Fetches a complete feature by its UUID with options to include related entities like 
        tasks and task statistics. This tool allows getting detailed information about a specific 
        feature when its ID is known.
        
        Note: Features store their detailed content in separate Section entities for efficiency. 
        To retrieve the complete feature with all content blocks, make sure to set includeSections=true.
        Otherwise, you'll only receive the basic feature metadata and summary.
        
        ## Parameters
        
        | Parameter | Type | Required | Default | Description |
        | id | UUID string | Yes | - | The unique ID of the feature to retrieve (e.g., '550e8400-e29b-41d4-a716-446655440000') |
        | includeTasks | boolean | No | false | Whether to include basic task information in the response. Set to true when you need to see all tasks associated with this feature. |
        | maxTaskCount | integer | No | 10 | Maximum number of tasks to include (1-100) |
        | includeTaskCounts | boolean | No | false | Whether to include task statistics grouped by status |
        | includeTaskDependencies | boolean | No | false | Whether to include dependency information for tasks when includeTasks is true |
        | includeSections | boolean | No | false | Whether to include sections (detailed content blocks) that contain the full content of the feature. Set to true when you need the complete feature context beyond the basic summary. |
        | summaryView | boolean | No | false | Whether to return a summarized view for context efficiency (truncates text fields) |
        | maxsummaryLength | integer | No | 500 | Maximum length for summary before truncation when in summary view |
        
        ## Response Format
        
        ### Success Response
        
        ```json
        {
          "success": true,
          "message": "Feature retrieved successfully",
          "data": {
            "id": "661e8511-f30c-41d4-a716-557788990000",
            "name": "REST API Implementation",
            "summary": "Implement the core REST API endpoints...",
            "status": "in-development",
            "priority": "high",
            "createdAt": "2025-05-10T14:30:00Z",
            "modifiedAt": "2025-05-10T15:45:00Z",
            "tags": ["api", "backend"],
            "taskCounts": {
              "total": 15,
              "byStatus": {
                "pending": 5,
                "in-progress": 8,
                "completed": 2
              }
            },
            "sections": [
              {
                "id": "772f9622-g41d-52e5-b827-668899101111",
                "title": "Requirements",
                "content": "The API should support CRUD operations...",
                "contentFormat": "markdown",
                "ordinal": 0
              }
            ],
            "tasks": {
              "items": [
                {
                  "id": "550e8400-e29b-41d4-a716-446655440000",
                  "title": "Implement User API",
                  "status": "in-progress",
                  "priority": "high",
                  "complexity": 7,
                  "dependencies": {
                    "counts": {
                      "total": 3,
                      "incoming": 1,
                      "outgoing": 2
                    }
                  }
                },
                // More tasks...
              ],
              "total": 15,
              "included": 10,
              "hasMore": true,
              "dependencyStatistics": {
                "totalDependencies": 25,
                "totalIncomingDependencies": 12,
                "totalOutgoingDependencies": 13,
                "tasksWithDependencies": 8
              }
            }
          }
        }
        ```
        
        ## Error Responses
        
        - RESOURCE_NOT_FOUND (404): When no feature exists with the specified ID
        - VALIDATION_ERROR (400): When the provided ID is not a valid UUID
        - DATABASE_ERROR (500): When there's an issue retrieving data from the database
        - INTERNAL_ERROR (500): For unexpected system errors during execution
        
        ## Usage Examples
        
        1. Get basic feature information:
           ```json
           {
             "id": "661e8511-f30c-41d4-a716-557788990000"
           }
           ```
           
        2. Get feature with tasks and counts:
           ```json
           {
             "id": "661e8511-f30c-41d4-a716-557788990000",
             "includeTasks": true,
             "includeTaskCounts": true
           }
           ```
           
        3. Get feature with sections (detailed content):
           ```json
           {
             "id": "661e8511-f30c-41d4-a716-557788990000",
             "includeSections": true
           }
           ```
           
        4. Get complete feature with all relationships:
           ```json
           {
             "id": "661e8511-f30c-41d4-a716-557788990000",
             "includeTasks": true,
             "includeTaskCounts": true,
             "includeSections": true
           }
           ```
           
        5. Get feature with tasks and their dependency information:
           ```json
           {
             "id": "661e8511-f30c-41d4-a716-557788990000",
             "includeTasks": true,
             "includeTaskDependencies": true
           }
           ```
           
        6. Get summarized feature information (for context efficiency):
           ```json
           {
             "id": "661e8511-f30c-41d4-a716-557788990000",
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