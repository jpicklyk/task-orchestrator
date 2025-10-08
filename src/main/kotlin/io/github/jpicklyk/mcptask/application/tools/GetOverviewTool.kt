package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tool for retrieving a lightweight overview of all tasks and features in a hierarchical structure.
 *
 * This tool provides a project overview with optimized token usage by returning essential
 * metadata rather than complete details. Tasks are organized by their parent features
 * with orphaned tasks (those not associated with any feature) listed separately.
 */
class GetOverviewTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "get_overview"

    override val title: String = "Get Project Overview"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Hierarchical project overview with features and tasks"),
                        "properties" to JsonObject(
                            mapOf(
                                "features" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Array of features with their associated tasks"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Truncated summary (optional based on summaryLength parameter)"))),
                                                        "tasks" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("array"),
                                                                "items" to JsonObject(
                                                                    mapOf(
                                                                        "type" to JsonPrimitive("object"),
                                                                        "properties" to JsonObject(
                                                                            mapOf(
                                                                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                                                "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                                                "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                                                "priority" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                                                "complexity" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                                                "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Truncated summary (optional based on summaryLength parameter)"))),
                                                                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Comma-separated tag string")))
                                                                            )
                                                                        )
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
                                "orphanedTasks" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Array of tasks not associated with any feature"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "priority" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "complexity" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                        "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "counts" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Summary counts for quick reference"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "features" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "tasks" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "orphanedTasks" to JsonObject(mapOf("type" to JsonPrimitive("integer")))
                                            )
                                        ),
                                        "required" to JsonArray(listOf("features", "tasks", "orphanedTasks").map { JsonPrimitive(it) })
                                    )
                                )
                            )
                        ),
                        "required" to JsonArray(listOf("features", "orphanedTasks", "counts").map { JsonPrimitive(it) })
                    )
                )
            )
        ),
        required = listOf("success", "message")
    )

    override val description: String = """Retrieves a lightweight, token-efficient overview of tasks and features.
        
        ## Purpose
        This tool provides a hierarchical project overview optimized for context efficiency.
        Essential for understanding current work state and making informed task planning decisions.
        
        ## Usage Guidance
        **RECOMMENDED WORKFLOW START**: Always begin work sessions with get_overview to:
        - Understand current project state and priorities
        - Identify in-progress tasks that need attention
        - Plan new work based on existing features and tasks
        - Locate orphaned tasks that might need feature association
        
        ## Data Organization
        - **Features**: Top-level functionality groupings with their associated tasks
        - **Orphaned Tasks**: Tasks not associated with any feature (may need organization)
        - **Hierarchical View**: Tasks organized under their parent features for clear context
        - **Essential Metadata**: Status, priority, complexity without full content for efficiency
        
        ## Context Efficiency Features
        - Configurable summary length (0-200 chars) to control token usage
        - Essential metadata only (no full task content or sections)
        - Hierarchical organization reduces cognitive overhead
        - Count summaries provide quick project metrics
        
        ## Integration with Other Tools
        Use this overview to inform decisions for:
        - `create_task`: Understand existing work before creating new tasks
        - `create_feature`: Identify orphaned tasks that could be grouped
        - `update_task`: Find tasks that need status updates
        - `search_tasks`: Narrow down specific searches based on overview insights
        
        ## Best Practices
        - Run get_overview at the start of work sessions
        - Use summaryLength=0 when you only need structure and metadata
        - Use summaryLength=100-200 when you need content context
        - Pay attention to orphaned tasks - they may need feature association
        - Monitor task status distribution across features
        
        Example response:
        {
          "success": true,
          "message": "Task overview retrieved successfully",
          "data": {
            "features": [
              {
                "id": "661e8511-f30c-41d4-a716-557788990000",
                "name": "User Authentication",
                "status": "in-development",
                "summary": "Implements secure user authentication mechanisms with OAuth 2.0 and JWT tokens...",
                "tasks": [
                  {
                    "id": "550e8400-e29b-41d4-a716-446655440000",
                    "title": "Implement OAuth Authentication API",
                    "summary": "Create secure authentication flow with OAuth 2.0 protocol and JWT token management...",
                    "status": "in-progress",
                    "priority": "high",
                    "complexity": 8,
                    "tags": "task-type-feature, oauth, authentication, api"
                  }
                ]
              }
            ],
            "orphanedTasks": [
              {
                "id": "772f9622-g41d-52e5-b827-668899101111",
                "title": "Setup CI/CD Pipeline",
                "summary": "Configure automated build and deployment pipeline using GitHub Actions...",
                "status": "pending",
                "priority": "medium",
                "complexity": 6,
                "tags": "task-type-infrastructure, ci-cd, automation"
              }
            ],
            "counts": {
              "features": 5,
              "tasks": 23,
              "orphanedTasks": 7
            }
          }
        }"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "summaryLength" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum length of task/feature summaries (0 to exclude summaries)"),
                        "default" to JsonPrimitive(100),
                        "minimum" to JsonPrimitive(0),
                        "maximum" to JsonPrimitive(200)
                    )
                )
            )
        ),
        required = listOf()
    )

    override fun validateParams(params: JsonElement) {
        // Validate summaryLength if present
        optionalInt(params, "summaryLength")?.let { length ->
            if (length < 0 || length > 200) {
                throw ToolValidationException("Summary length must be between 0 and 200")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_overview tool")

        try {
            // Extract parameter
            val summaryLength = optionalInt(params, "summaryLength", 100) ?: 100

            // Fetch all tasks and features
            val taskResult = context.taskRepository().findAll(limit = 20)
            val featureResult = context.featureRepository().findAll(limit = 20)

            // Handle task fetch error
            if (taskResult is Result.Error) {
                return errorResponse(
                    message = "Failed to retrieve tasks",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = taskResult.error.toString()
                )
            }

            // Handle feature fetch error
            if (featureResult is Result.Error) {
                return errorResponse(
                    message = "Failed to retrieve features",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = featureResult.error.toString()
                )
            }

            // Process successful results
            val tasks = (taskResult as Result.Success<List<Task>>).data
            val features = (featureResult as Result.Success<List<Feature>>).data

            // Build hierarchical response
            val responseData = buildJsonObject {
                // Features with their tasks
                put("features", buildJsonArray {
                    features.forEach { feature ->
                        add(createFeatureEntry(feature, tasks, summaryLength))
                    }
                })

                // Orphaned tasks (not associated with any feature)
                val orphanedTasks = tasks.filter { it.featureId == null }
                put("orphanedTasks", buildJsonArray {
                    orphanedTasks.forEach { task ->
                        add(createTaskEntry(task, summaryLength))
                    }
                })

                // Basic counts for quick reference
                put("counts", buildJsonObject {
                    put("features", features.size)
                    put("tasks", tasks.size)
                    put("orphanedTasks", orphanedTasks.size)
                })
            }

            return successResponse(
                data = responseData,
                message = "Task overview retrieved successfully"
            )
        } catch (e: Exception) {
            logger.error("Error retrieving task overview", e)
            return errorResponse(
                message = "Failed to retrieve task overview",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Creates a feature entry with its associated tasks.
     *
     * @param feature The feature to create an entry for
     * @param allTasks All tasks to find those associated with this feature
     * @param summaryLength Maximum length for the summary (0 to exclude)
     * @return A JsonObject containing the feature and its tasks
     */
    private fun createFeatureEntry(feature: Feature, allTasks: List<Task>, summaryLength: Int): JsonObject {
        // Get tasks associated with this feature
        val featureTasks = allTasks.filter { it.featureId == feature.id }

        return buildJsonObject {
            put("id", feature.id.toString())
            put("name", feature.name)
            put("status", feature.status.name.lowercase())

            // Include summary with configurable length
            if (summaryLength > 0) {
                feature.summary.let {
                    if (it.length > summaryLength) {
                        put("summary", it.take(summaryLength - 3) + "...")
                    } else {
                        put("summary", it)
                    }
                }
            }

            // Include the tasks for this feature
            put("tasks", buildJsonArray {
                featureTasks.forEach { task ->
                    add(createTaskEntry(task, summaryLength))
                }
            })
        }
    }

    /**
     * Creates a task entry with essential metadata.
     *
     * @param task The task to create an entry for
     * @param summaryLength Maximum length for the summary (0 to exclude)
     * @return A JsonObject containing the task metadata
     */
    private fun createTaskEntry(task: Task, summaryLength: Int): JsonObject {
        return buildJsonObject {
            put("id", task.id.toString())
            put("title", task.title)
            put("status", task.status.name.lowercase().replace('_', '-'))
            put("priority", task.priority.name.lowercase())
            put("complexity", task.complexity)

            // Include summary with configurable length
            if (summaryLength > 0) {
                task.summary.let {
                    if (it.length > summaryLength) {
                        put("summary", it.take(summaryLength - 3) + "...")
                    } else {
                        put("summary", it)
                    }
                }
            }

            // Include tags as a comma-separated string to save tokens
            if (task.tags.isNotEmpty()) {
                put("tags", task.tags.joinToString(", "))
            }
        }
    }
}