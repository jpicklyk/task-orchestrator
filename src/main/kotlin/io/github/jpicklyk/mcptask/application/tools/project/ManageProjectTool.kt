package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.UpdateEfficiencyMetrics
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.rendering.MarkdownRenderer
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Consolidated MCP tool for all single-entity project operations.
 * Replaces create_project, get_project, update_project, delete_project, and project_to_markdown.
 * Reduces token overhead through parameter and description consolidation.
 */
class ManageProjectTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT

    override val name: String = "manage_project"

    override val title: String = "Manage Project"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        ),
        required = listOf("success", "message")
    )

    override fun shouldUseLocking(): Boolean = true

    override val description: String = """Unified project management: create, get, update, delete, or export projects.

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | Operation: create, get, update, delete, export |
| id | UUID | Yes* | Project ID (*required for: get, update, delete, export) |
| name | string | Yes** | Project name (**required for: create) |
| summary | string | No | Brief summary (max 500 chars) |
| description | string | No | Detailed description (user-provided) |
| status | enum | No | planning, in-development, completed, archived |
| tags | string | No | Comma-separated tags |
| format | enum | No | Export format: markdown, json (default: markdown) |
| includeSections | boolean | No | Include sections (get/export) |
| includeFeatures | boolean | No | Include features (get) |
| includeTasks | boolean | No | Include tasks (get) |
| summaryView | boolean | No | Truncate text fields (get) |
| deleteSections | boolean | No | Delete sections (default: true) |
| force | boolean | No | Delete with features/tasks |

Usage: Single tool for all project operations. Specify operation, then provide relevant parameters.
Related: search_projects, create_feature, create_task
Docs: task-orchestrator://docs/tools/manage-project
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(listOf("create", "get", "update", "delete", "export").map { JsonPrimitive(it) })
                    )
                ),
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Project ID (required for: get, update, delete, export)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Project name (required for: create)")
                    )
                ),
                "description" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Detailed description")
                    )
                ),
                "summary" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Brief summary (max 500 chars)")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Project status"),
                        "enum" to JsonArray(ProjectStatus.entries.map { JsonPrimitive(it.name.lowercase().replace('_', '-')) })
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated tags")
                    )
                ),
                "format" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Export format (export only)"),
                        "enum" to JsonArray(listOf("markdown", "json").map { JsonPrimitive(it) }),
                        "default" to JsonPrimitive("markdown")
                    )
                ),
                "includeSections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include sections (get/export)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeFeatures" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include features (get)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeTasks" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include tasks (get)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "summaryView" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Truncate text fields (get)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "deleteSections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Delete sections (delete)"),
                        "default" to JsonPrimitive(true)
                    )
                ),
                "force" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Delete with features/tasks (delete)"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("operation")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")

        // Validate operation is valid
        if (operation !in listOf("create", "get", "update", "delete", "export")) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: create, get, update, delete, export")
        }

        // Validate ID for operations that require it
        if (operation in listOf("get", "update", "delete", "export")) {
            val idStr = requireString(params, "id")
            try {
                UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid project ID format. Must be a valid UUID.")
            }
        }

        // Validate name for create
        if (operation == "create") {
            val name = requireString(params, "name")
            if (name.isBlank()) {
                throw ToolValidationException("Name cannot be blank")
            }
        }

        // Validate optional parameters if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status. Must be one of: planning, in-development, completed, archived")
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing manage_project tool")

        return try {
            val operation = requireString(params, "operation")

            when (operation) {
                "create" -> executeCreate(params, context)
                "get" -> executeGet(params, context)
                "update" -> {
                    val projectId = extractEntityId(params, "id")
                    executeWithLocking("update_project", EntityType.PROJECT, projectId) {
                        executeUpdate(params, context, projectId)
                    }
                }
                "delete" -> {
                    val projectId = extractEntityId(params, "id")
                    executeWithLocking("delete_project", EntityType.PROJECT, projectId) {
                        executeDelete(params, context, projectId)
                    }
                }
                "export" -> executeExport(params, context)
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in manage_project: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in manage_project", e)
            errorResponse(
                message = "Failed to execute project operation",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Executes create operation.
     */
    private suspend fun executeCreate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing create operation")

        // Extract parameters
        val name = requireString(params, "name")
        val description = optionalString(params, "description")
        val summary = optionalString(params, "summary") ?: ""

        if (name.isBlank()) {
            throw ToolValidationException("Name cannot be blank")
        }

        // Parse status
        val statusStr = optionalString(params, "status") ?: "planning"
        val status = parseStatus(statusStr)

        // Parse tags
        val tags = optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: emptyList()

        // Create the project entity
        val project = Project(
            name = name,
            description = description,
            summary = summary,
            status = status,
            tags = tags,
        )

        // Create project in repository
        val result = context.projectRepository().create(project)

        // Process result
        return when (result) {
            is Result.Success -> {
                val createdProject = result.data

                // Build response
                val responseBuilder = buildJsonObject {
                    put("id", createdProject.id.toString())
                    put("name", createdProject.name)
                    if (createdProject.description != null) {
                        put("description", createdProject.description)
                    } else {
                        put("description", JsonNull)
                    }
                    put("summary", createdProject.summary)
                    put("status", createdProject.status.name.lowercase().replace('_', '-'))
                    put("createdAt", createdProject.createdAt.toString())
                    put("modifiedAt", createdProject.modifiedAt.toString())

                    put("tags", buildJsonArray {
                        createdProject.tags.forEach { add(it) }
                    })
                }

                successResponse(responseBuilder, "Project created successfully")
            }

            is Result.Error -> {
                errorResponse(
                    message = "Failed to create project: ${result.error}",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = result.error.toString()
                )
            }
        }
    }

    /**
     * Executes get operation.
     */
    private suspend fun executeGet(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get operation")

        // Extract parameters
        val idStr = requireString(params, "id")
        val projectId = UUID.fromString(idStr)
        val summaryView = optionalBoolean(params, "summaryView")
        val includeFeatures = optionalBoolean(params, "includeFeatures")
        val includeTasks = optionalBoolean(params, "includeTasks")
        val includeSections = optionalBoolean(params, "includeSections")

        // Get project from repository
        val projectResult = context.projectRepository().getById(projectId)

        // Handle result
        return when (projectResult) {
            is Result.Success -> {
                val project = projectResult.data
                val dataObject = buildJsonObject {
                    // Basic project information
                    put("id", project.id.toString())
                    put("name", project.name)

                    // Full or truncated summary
                    if (!summaryView) {
                        put("summary", project.summary)
                    } else {
                        val truncatedSummary = if (project.summary.length > 100) {
                            "${project.summary.take(97)}..."
                        } else {
                            project.summary
                        }
                        put("summary", truncatedSummary)
                    }

                    if (project.description != null) {
                        if (!summaryView) {
                            put("description", project.description)
                        } else {
                            val truncatedDescription = if (project.description.length > 100) {
                                "${project.description.take(97)}..."
                            } else {
                                project.description
                            }
                            put("description", truncatedDescription)
                        }
                    } else {
                        put("description", JsonNull)
                    }

                    put("status", project.status.name.lowercase().replace('_', '-'))
                    put("createdAt", project.createdAt.toString())
                    put("modifiedAt", project.modifiedAt.toString())

                    put("tags", buildJsonArray {
                        project.tags.forEach { tag -> add(JsonPrimitive(tag)) }
                    })

                    // Include features if requested
                    if (includeFeatures) {
                        try {
                            val featuresResult = context.featureRepository().findByProject(projectId)
                            when (featuresResult) {
                                is Result.Success -> {
                                    put("features", buildJsonArray {
                                        featuresResult.data.forEach { feature ->
                                            add(buildJsonObject {
                                                put("id", feature.id.toString())
                                                put("name", feature.name)
                                                put("status", feature.status.name.lowercase().replace('_', '-'))
                                                if (!summaryView) {
                                                    put("summary", feature.summary)
                                                }
                                            })
                                        }
                                    })
                                    put("featureCount", featuresResult.data.size)
                                }
                                is Result.Error -> {
                                    put("features", buildJsonArray {})
                                    put("featureCount", 0)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Error retrieving features", e)
                            put("features", buildJsonArray {})
                            put("featureCount", 0)
                        }
                    }

                    // Include tasks if requested
                    if (includeTasks) {
                        try {
                            val tasksResult = context.taskRepository().findByProject(projectId)
                            when (tasksResult) {
                                is Result.Success -> {
                                    put("tasks", buildJsonArray {
                                        tasksResult.data.forEach { task ->
                                            add(buildJsonObject {
                                                put("id", task.id.toString())
                                                put("title", task.title)
                                                put("status", task.status.name.lowercase().replace('_', '-'))
                                                if (!summaryView) {
                                                    put("summary", task.summary)
                                                }
                                            })
                                        }
                                    })
                                    put("taskCount", tasksResult.data.size)
                                }
                                is Result.Error -> {
                                    put("tasks", buildJsonArray {})
                                    put("taskCount", 0)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Error retrieving tasks", e)
                            put("tasks", buildJsonArray {})
                            put("taskCount", 0)
                        }
                    }

                    // Include sections if requested
                    if (includeSections) {
                        try {
                            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.PROJECT, projectId)

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

                successResponse(dataObject, "Project retrieved successfully")
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
    }

    /**
     * Executes update operation.
     */
    private suspend fun executeUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        projectId: UUID
    ): JsonElement {
        logger.info("Executing update operation")

        // Analyze update efficiency
        val efficiencyMetrics = UpdateEfficiencyMetrics.analyzeUpdate("update_project", params)

        // Get existing project
        val existingProjectResult = context.projectRepository().getById(projectId)
        val existingProject = when (existingProjectResult) {
            is Result.Success -> existingProjectResult.data
            is Result.Error -> return handleRepositoryResult(
                existingProjectResult,
                "Failed to retrieve project"
            ) { JsonNull }
        }

        // Extract update parameters
        val name = optionalString(params, "name") ?: existingProject.name
        val description = optionalString(params, "description") ?: existingProject.description
        val summary = optionalString(params, "summary") ?: existingProject.summary

        val statusStr = optionalString(params, "status")
        val status = if (statusStr != null) parseStatus(statusStr) else existingProject.status

        val tags = optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: existingProject.tags

        // Create updated project
        val updatedProject = existingProject.copy(
            name = name,
            description = description,
            summary = summary,
            status = status,
            tags = tags,
            modifiedAt = Instant.now()
        )

        // Save updated project
        val updateResult = context.projectRepository().update(updatedProject)

        // Build response with efficiency guidance
        val efficiencyLevel = efficiencyMetrics["efficiencyLevel"]?.jsonPrimitive?.content
        val efficiencyGuidance = efficiencyMetrics["guidance"]?.jsonPrimitive?.content ?: ""
        val baseMessage = "Project updated successfully"
        val message = if (efficiencyLevel == "inefficient") {
            "$baseMessage. ⚠️ $efficiencyGuidance"
        } else {
            baseMessage
        }

        // Return minimal response
        return handleRepositoryResult(updateResult, message) { updatedProjectData ->
            buildJsonObject {
                put("id", updatedProjectData.id.toString())
                put("status", updatedProjectData.status.name.lowercase().replace('_', '-'))
                put("modifiedAt", updatedProjectData.modifiedAt.toString())
            }
        }
    }

    /**
     * Executes delete operation.
     */
    private suspend fun executeDelete(
        params: JsonElement,
        context: ToolExecutionContext,
        projectId: UUID
    ): JsonElement {
        logger.info("Executing delete operation")

        // Extract parameters
        val force = optionalBoolean(params, "force", false)
        val deleteSections = optionalBoolean(params, "deleteSections", true)

        // Verify project exists
        val getResult = context.projectRepository().getById(projectId)
        when (getResult) {
            is Result.Success -> { /* Project exists, continue */ }
            is Result.Error -> return handleRepositoryResult(
                getResult,
                "Failed to retrieve project"
            ) { JsonNull }
        }

        // Check for features and tasks
        val featuresResult = context.featureRepository().findByProject(projectId)
        val tasksResult = context.taskRepository().findByProject(projectId)

        val features = when (featuresResult) {
            is Result.Success -> featuresResult.data
            is Result.Error -> emptyList()
        }

        val tasks = when (tasksResult) {
            is Result.Success -> tasksResult.data
            is Result.Error -> emptyList()
        }

        val hasChildren = features.isNotEmpty() || tasks.isNotEmpty()

        // If children exist and force not enabled, error
        if (hasChildren && !force) {
            val childInfo = buildJsonObject {
                put("featureCount", features.size)
                put("taskCount", tasks.size)
            }

            return errorResponse(
                message = "Cannot delete project with existing features or tasks",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Project has ${features.size} features and ${tasks.size} tasks. Use 'force=true' to delete anyway.",
                additionalData = childInfo
            )
        }

        // Delete children if force enabled
        var featuresDeletedCount = 0
        var tasksDeletedCount = 0
        if (force) {
            // Delete all tasks
            tasks.forEach { task ->
                context.taskRepository().delete(task.id)
                tasksDeletedCount++
            }
            // Delete all features
            features.forEach { feature ->
                context.featureRepository().delete(feature.id)
                featuresDeletedCount++
            }
            logger.info("Deleted $featuresDeletedCount features and $tasksDeletedCount tasks for project $projectId")
        }

        // Delete sections if requested
        var sectionsDeletedCount = 0
        if (deleteSections) {
            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.PROJECT, projectId)

            if (sectionsResult is Result.Success) {
                sectionsDeletedCount = sectionsResult.data.size
                sectionsResult.data.forEach { section ->
                    context.sectionRepository().deleteSection(section.id)
                }
                logger.info("Deleted $sectionsDeletedCount sections for project $projectId")
            }
        }

        // Delete the project
        val deleteResult = context.projectRepository().delete(projectId)

        // Build response message
        val message = if (hasChildren && force) {
            "Project deleted successfully with $featuresDeletedCount features, $tasksDeletedCount tasks, and $sectionsDeletedCount sections"
        } else {
            "Project deleted successfully"
        }

        return handleRepositoryResult(deleteResult, message) { _ ->
            buildJsonObject {
                put("id", projectId.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeletedCount)
                put("featuresDeleted", featuresDeletedCount)
                put("tasksDeleted", tasksDeletedCount)
            }
        }
    }

    /**
     * Executes export operation.
     */
    private suspend fun executeExport(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing export operation")

        // Extract parameters
        val idStr = requireString(params, "id")
        val projectId = UUID.fromString(idStr)
        val format = optionalString(params, "format") ?: "markdown"

        // Validate format
        if (format !in listOf("markdown", "json")) {
            throw ToolValidationException("Invalid format: $format. Must be 'markdown' or 'json'")
        }

        // Get the project
        val projectResult = context.projectRepository().getById(projectId)

        return when (projectResult) {
            is Result.Success -> {
                val project = projectResult.data

                if (format == "markdown") {
                    // Get sections for the project
                    val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.PROJECT, projectId)
                    val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                    // Render to markdown
                    val renderer = MarkdownRenderer()
                    val markdown = renderer.renderProject(project, sections)

                    val data = buildJsonObject {
                        put("markdown", markdown)
                        put("projectId", projectId.toString())
                    }

                    successResponse(data, "Project transformed to markdown successfully")
                } else {
                    // JSON format - use get logic
                    executeGet(params, context)
                }
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
    }

    // Helper methods for parsing enums

    private fun isValidStatus(status: String): Boolean {
        return try {
            parseStatus(status)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseStatus(status: String): ProjectStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> ProjectStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> ProjectStatus.IN_DEVELOPMENT
            "completed" -> ProjectStatus.COMPLETED
            "archived" -> ProjectStatus.ARCHIVED
            else -> throw IllegalArgumentException("Invalid status: $status")
        }
    }
}
