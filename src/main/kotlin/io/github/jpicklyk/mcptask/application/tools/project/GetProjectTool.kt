package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.StatusFilter
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for retrieving project details with options for including relationships.
 *
 * This tool provides detailed access to a specific project with options to include related
 * entities like features, tasks, and sections. Projects are top-level organizational containers
 * that can group related work together.
 *
 * The tool supports progressive loading of details to optimize context usage, allowing you
 * to request only the information you need.
 *
 * Related tools:
 * - create_project: To create a new project
 * - update_project: To modify an existing project
 * - delete_project: To remove a project
 * - search_projects: To find projects matching criteria
 * - get_project_features: To get all features in a project
 * - get_project_tasks: To get all tasks in a project
 */
class GetProjectTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT

    override val name: String = "get_project"

    override val title: String = "Get Project Details"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The requested project with optional related entities"),
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
                                "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "modifiedAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                                "features" to JsonObject(mapOf("type" to JsonPrimitive("object"), "description" to JsonPrimitive("Features information (present when includeFeatures=true)"))),
                                "tasks" to JsonObject(mapOf("type" to JsonPrimitive("object"), "description" to JsonPrimitive("Tasks information (present when includeTasks=true)"))),
                                "sections" to JsonObject(mapOf("type" to JsonPrimitive("array"), "description" to JsonPrimitive("Array of sections (present when includeSections=true)")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """⚠️ DEPRECATED: Use manage_project with operation="get" instead.

Retrieves a project by ID with optional related entities.

Parameters:
| Field | Type | Required | Default | Description |
| id | UUID | Yes | - | Project identifier |
| includeSections | boolean | No | false | Include detailed content sections |
| includeFeatures | boolean | No | false | Include associated features |
| includeTasks | boolean | No | false | Include associated tasks |
| maxFeatureCount | integer | No | 10 | Maximum features to include (1-100) |
| maxTaskCount | integer | No | 10 | Maximum tasks to include (1-100) |
| summaryView | boolean | No | false | Truncate text fields for efficiency |

Usage notes:
- Projects are top-level containers grouping features and tasks
- Use progressive loading to optimize context usage
- Limit feature/task counts for large projects

Related: create_project, update_project, delete_project, search_projects, get_sections

For detailed examples and patterns: task-orchestrator://docs/tools/get-project
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID (UUID) of the project to retrieve")
                    )
                ),
                "includeFeatures" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include basic feature information in the response"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeTasks" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include basic task information in the response"),
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
                "maxFeatureCount" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum number of features to include (1-100)"),
                        "default" to JsonPrimitive(10)
                    )
                ),
                "maxTaskCount" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum number of tasks to include (1-100)"),
                        "default" to JsonPrimitive(10)
                    )
                ),
                "summaryView" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to return a summarized view for context efficiency"),
                        "default" to JsonPrimitive(false)
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
        optionalInt(params, "maxFeatureCount")?.let { count ->
            if (count < 1) {
                throw ToolValidationException("maxFeatureCount must be at least 1")
            }
            if (count > 100) {
                throw ToolValidationException("maxFeatureCount cannot exceed 100")
            }
        }

        optionalInt(params, "maxTaskCount")?.let { count ->
            if (count < 1) {
                throw ToolValidationException("maxTaskCount must be at least 1")
            }
            if (count > 100) {
                throw ToolValidationException("maxTaskCount cannot exceed 100")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_project tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val projectId = UUID.fromString(idStr)
            val includeFeatures = optionalBoolean(params, "includeFeatures", false)
            val includeTasks = optionalBoolean(params, "includeTasks", false)
            val includeSections = optionalBoolean(params, "includeSections", false)
            val maxFeatureCount = optionalInt(params, "maxFeatureCount", 10)!!
            val maxTaskCount = optionalInt(params, "maxTaskCount", 10)!!
            val summaryView = optionalBoolean(params, "summaryView", false)

            // Get the project
            val projectResult = context.projectRepository().getById(projectId)

            // Return standardized response
            return when (projectResult) {
                is Result.Success -> {
                    val project = projectResult.data

                    // Process the summary based on length constraint for context efficiency
                    val processedSummary = project.summary.let {
                        if (it.length > 500 && summaryView) {
                            "${it.substring(0, 497)}..."
                        } else {
                            it
                        }
                    }

                    val data = buildJsonObject {
                        // Basic project information
                        put("id", project.id.toString())
                        put("name", project.name)
                        put("summary", processedSummary)
                        put("status", project.status.name.lowercase().replace('_', '-'))
                        put("createdAt", project.createdAt.toString())
                        put("modifiedAt", project.modifiedAt.toString())

                        // Ensure tags are properly included in the response
                        put("tags", buildJsonArray {
                            // Add debugging to see the actual tags
                            logger.info("Including tags for project ${project.id}: ${project.tags}")
                            project.tags.forEach { tag ->
                                add(JsonPrimitive(tag))
                            }
                        })

                        // If requested, include sections
                        if (includeSections) {
                            try {
                                val sectionsResult =
                                    context.sectionRepository().getSectionsForEntity(EntityType.PROJECT, projectId)

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
                                logger.error("Error retrieving sections for project", e)
                                put("sections", buildJsonArray {})
                            }
                        }

                        // If requested, include feature details
                        if (includeFeatures) {
                            try {
                                // Get features for this project
                                val featuresFuture = context.featureRepository().findByFilters(
                                    projectId = projectId,
                                    statusFilter = null,
                                    priorityFilter = null,
                                    tags = null,
                                    textQuery = null,
                                    limit = 20,
                                )

                                if (featuresFuture is Result.Success<List<Feature>>) {
                                    val features = featuresFuture.data
                                    val featuresToInclude = features.take(maxFeatureCount)

                                    put("features", buildJsonObject {
                                        put("items", buildJsonArray {
                                            featuresToInclude.forEach { feature ->
                                                add(buildJsonObject {
                                                    put("id", feature.id.toString())
                                                    put("name", feature.name)
                                                    put("status", feature.status.name.lowercase().replace('_', '-'))
                                                    put("priority", feature.priority.name.lowercase())

                                                    // If in summary view, include a truncated summary
                                                    if (!summaryView) {
                                                        put("summary", feature.summary)
                                                    } else if (feature.summary.length > 100) {
                                                        put("summary", "${feature.summary.take(97)}...")
                                                    } else {
                                                        put("summary", feature.summary)
                                                    }
                                                })
                                            }
                                        })
                                        put("total", features.size)
                                        put("included", featuresToInclude.size)
                                        put("hasMore", features.size > maxFeatureCount)
                                    })
                                } else {
                                    put("features", buildJsonObject {
                                        put("items", buildJsonArray {})
                                        put("total", 0)
                                        put("included", 0)
                                        put("hasMore", false)
                                    })
                                }
                            } catch (e: Exception) {
                                logger.error("Error retrieving features for project", e)
                                put("features", buildJsonObject {
                                    put("items", buildJsonArray {})
                                    put("total", 0)
                                    put("included", 0)
                                    put("hasMore", false)
                                })
                            }
                        }

                        // If requested, include task details
                        if (includeTasks) {
                            try {
                                // Get tasks directly associated with this project (not through features)
                                val tasksResult = context.taskRepository().findByFilters(
                                    projectId = projectId,
                                    statusFilter = null,
                                    priorityFilter = null,
                                    tags = null,
                                    textQuery = null,
                                    limit = 20,
                                )

                                if (tasksResult is Result.Success<List<Task>>) {
                                    val tasks = tasksResult.data
                                    val tasksToInclude = tasks.take(maxTaskCount)

                                    put("tasks", buildJsonObject {
                                        put("items", buildJsonArray {
                                            tasksToInclude.forEach { task ->
                                                add(buildJsonObject {
                                                    put("id", task.id.toString())
                                                    put("title", task.title)
                                                    put("status", task.status.name.lowercase().replace('_', '-'))
                                                    put("priority", task.priority.name.lowercase())
                                                    put("complexity", task.complexity)

                                                    // Include truncated summary if in summary view
                                                    if (!summaryView) {
                                                        put("summary", task.summary)
                                                    } else if (task.summary.length > 100) {
                                                        put("summary", "${task.summary.take(97)}...")
                                                    } else {
                                                        put("summary", task.summary)
                                                    }
                                                })
                                            }
                                        })
                                        put("total", tasks.size)
                                        put("included", tasksToInclude.size)
                                        put("hasMore", tasks.size > maxTaskCount)
                                    })
                                } else {
                                    put("tasks", buildJsonObject {
                                        put("items", buildJsonArray {})
                                        put("total", 0)
                                        put("included", 0)
                                        put("hasMore", false)
                                    })
                                }
                            } catch (e: Exception) {
                                logger.error("Error retrieving tasks for project", e)
                                put("tasks", buildJsonObject {
                                    put("items", buildJsonArray {})
                                    put("total", 0)
                                    put("included", 0)
                                    put("hasMore", false)
                                })
                            }
                        }
                    }

                    successResponse(data, "Project retrieved successfully")
                }

                is Result.Error -> {
                    if (projectResult.error is RepositoryError.NotFound) {
                        errorResponse(
                            message = "Project not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No project exists with ID $projectId"
                        )
                    } else {
                        errorResponse(
                            message = "Failed to retrieve project",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = projectResult.error.toString()
                        )
                    }
                }
            }
        } catch (e: ToolValidationException) {
            // Handle validation errors
            logger.warn("Validation error retrieving project: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            // Handle unexpected errors
            logger.error("Error retrieving project", e)
            return errorResponse(
                message = "Failed to retrieve project",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}