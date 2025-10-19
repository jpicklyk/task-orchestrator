package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.UpdateEfficiencyMetrics
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.rendering.MarkdownRenderer
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Consolidated MCP tool for all single-entity task operations.
 * Replaces create_task, get_task, update_task, delete_task, and task_to_markdown.
 * Reduces token overhead by ~5.8k tokens through parameter and description consolidation.
 */
class ManageTaskTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "manage_task"

    override val title: String = "Manage Task"

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

    override val description: String = """Unified task management: create, get, update, delete, or export tasks.

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | Operation: create, get, update, delete, export |
| id | UUID | Yes* | Task ID (*required for: get, update, delete, export) |
| title | string | Yes** | Task title (**required for: create) |
| summary | string | No | Brief summary (max 500 chars) |
| description | string | No | Detailed description (user-provided) |
| status | enum | No | pending, in-progress, completed, cancelled, deferred |
| priority | enum | No | high, medium, low |
| complexity | integer | No | Rating (1-10) |
| featureId | UUID | No | Parent feature |
| projectId | UUID | No | Parent project |
| templateIds | array | No | Templates to apply (create only) |
| tags | string | No | Comma-separated tags |
| format | enum | No | Export format: markdown, json (default: markdown) |
| includeSections | boolean | No | Include sections (get/export) |
| includeFeature | boolean | No | Include feature info (get) |
| includeDependencies | boolean | No | Include dependencies (get) |
| summaryView | boolean | No | Truncate text fields (get) |
| deleteSections | boolean | No | Delete sections (default: true) |
| cascade | boolean | No | Delete subtasks (experimental) |
| force | boolean | No | Delete with dependencies |

Usage: Single tool for all task operations. Specify operation, then provide relevant parameters.
Related: search_tasks, bulk_update_tasks, create_dependency
Docs: task-orchestrator://docs/tools/manage-task
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
                        "description" to JsonPrimitive("Task ID (required for: get, update, delete, export)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Task title (required for: create)")
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
                        "description" to JsonPrimitive("Task status"),
                        "enum" to JsonArray(TaskStatus.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Priority level"),
                        "enum" to JsonArray(Priority.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "complexity" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Complexity (1-10)"),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(10)
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Parent project ID"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Parent feature ID"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated tags")
                    )
                ),
                "templateIds" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Template IDs to apply (create only)"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid")))
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
                "includeFeature" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include feature info (get)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeDependencies" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include dependencies (get)"),
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
                "cascade" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Delete subtasks (delete, experimental)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "force" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Delete with dependencies (delete)"),
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
                throw ToolValidationException("Invalid task ID format. Must be a valid UUID.")
            }
        }

        // Validate title for create
        if (operation == "create") {
            val title = requireString(params, "title")
            if (title.isBlank()) {
                throw ToolValidationException("Title cannot be blank")
            }
        }

        // Validate optional parameters if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status. Must be one of: pending, in-progress, completed, cancelled, deferred")
            }
        }

        optionalString(params, "priority")?.let { priority ->
            if (!isValidPriority(priority)) {
                throw ToolValidationException("Invalid priority: $priority. Must be one of: high, medium, low")
            }
        }

        optionalInt(params, "complexity")?.let { complexity ->
            if (complexity < 1 || complexity > 10) {
                throw ToolValidationException("Complexity must be between 1 and 10")
            }
        }

        optionalString(params, "featureId")?.let { featureId ->
            if (featureId.isNotEmpty()) {
                try {
                    UUID.fromString(featureId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid feature ID format. Must be a valid UUID")
                }
            }
        }

        optionalString(params, "projectId")?.let { projectId ->
            if (projectId.isNotEmpty()) {
                try {
                    UUID.fromString(projectId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid project ID format. Must be a valid UUID")
                }
            }
        }

        // Validate templateIds for create operation
        if (operation == "create") {
            val paramsObj = params as? JsonObject
                ?: throw ToolValidationException("Parameters must be a JSON object")

            if (paramsObj.containsKey("templateIds")) {
                val templateIdsElement = paramsObj["templateIds"]
                if (templateIdsElement !is JsonArray) {
                    throw ToolValidationException("Parameter 'templateIds' must be an array of strings (UUIDs)")
                }

                if (templateIdsElement.isNotEmpty()) {
                    for ((index, item) in templateIdsElement.withIndex()) {
                        if (item !is JsonPrimitive || !item.isString) {
                            throw ToolValidationException("templateIds[$index] must be a string (UUID)")
                        }

                        try {
                            UUID.fromString(item.content)
                        } catch (_: IllegalArgumentException) {
                            throw ToolValidationException("templateIds[$index] is not a valid UUID format")
                        }
                    }
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing manage_task tool")

        return try {
            val operation = requireString(params, "operation")

            when (operation) {
                "create" -> executeCreate(params, context)
                "get" -> executeGet(params, context)
                "update" -> {
                    val taskId = extractEntityId(params, "id")
                    executeWithLocking("update_task", EntityType.TASK, taskId) {
                        executeUpdate(params, context, taskId)
                    }
                }
                "delete" -> {
                    val taskId = extractEntityId(params, "id")
                    executeWithLocking("delete_task", EntityType.TASK, taskId) {
                        executeDelete(params, context, taskId)
                    }
                }
                "export" -> executeExport(params, context)
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in manage_task: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in manage_task", e)
            errorResponse(
                message = "Failed to execute task operation",
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
        val title = requireString(params, "title")
        val description = optionalString(params, "description")
        val summary = optionalString(params, "summary") ?: ""

        if (title.isBlank()) {
            throw ToolValidationException("Title cannot be blank")
        }

        // Parse status
        val statusStr = optionalString(params, "status") ?: "pending"
        val status = parseStatus(statusStr)

        // Parse priority
        val priorityStr = optionalString(params, "priority") ?: "medium"
        val priority = parsePriority(priorityStr)

        // Parse complexity
        val complexity = optionalInt(params, "complexity") ?: 5

        // Parse project ID
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }

        // Parse feature ID
        val featureId = optionalString(params, "featureId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }

        // Validate that referenced entities exist
        if (projectId != null) {
            when (val projectResult = context.repositoryProvider.projectRepository().getById(projectId)) {
                is Result.Error -> {
                    return errorResponse(
                        message = "Project not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No project exists with ID $projectId"
                    )
                }
                is Result.Success -> { /* Project exists, continue */ }
            }
        }

        if (featureId != null) {
            when (val featureResult = context.repositoryProvider.featureRepository().getById(featureId)) {
                is Result.Error -> {
                    return errorResponse(
                        message = "Feature not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No feature exists with ID $featureId"
                    )
                }
                is Result.Success -> { /* Feature exists, continue */ }
            }
        }

        // Parse tags
        val tags = optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: emptyList()

        // Get template IDs to apply
        val templateIds = mutableListOf<UUID>()
        val paramsObj = params as JsonObject
        if (paramsObj.containsKey("templateIds")) {
            val templateIdsArray = paramsObj["templateIds"] as? JsonArray
            templateIdsArray?.forEach { item ->
                if (item is JsonPrimitive && item.isString) {
                    templateIds.add(UUID.fromString(item.content))
                }
            }
        }

        // Create the task entity
        val task = Task(
            title = title,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            complexity = complexity,
            projectId = projectId,
            featureId = featureId,
            tags = tags,
        )

        // Create task in repository
        val result = context.taskRepository().create(task)

        // Process result and apply templates
        return when (result) {
            is Result.Success -> {
                val createdTask = result.data

                // Apply templates if specified
                val appliedTemplatesResult = if (templateIds.isNotEmpty()) {
                    context.repositoryProvider.templateRepository()
                        .applyMultipleTemplates(templateIds, EntityType.TASK, createdTask.id)
                } else {
                    null
                }

                // Build response
                val responseBuilder = buildJsonObject {
                    put("id", createdTask.id.toString())
                    put("title", createdTask.title)
                    if (createdTask.description != null) {
                        put("description", createdTask.description)
                    } else {
                        put("description", JsonNull)
                    }
                    put("summary", createdTask.summary)
                    put("status", createdTask.status.name.lowercase())
                    put("priority", createdTask.priority.name.lowercase())
                    put("complexity", createdTask.complexity)
                    put("createdAt", createdTask.createdAt.toString())
                    put("modifiedAt", createdTask.modifiedAt.toString())

                    if (createdTask.projectId != null) {
                        put("projectId", createdTask.projectId.toString())
                    } else {
                        put("projectId", JsonNull)
                    }

                    if (createdTask.featureId != null) {
                        put("featureId", createdTask.featureId.toString())
                    } else {
                        put("featureId", JsonNull)
                    }

                    put("tags", buildJsonArray {
                        createdTask.tags.forEach { add(it) }
                    })

                    // Add applied templates info if any
                    if (appliedTemplatesResult is Result.Success && appliedTemplatesResult.data.isNotEmpty()) {
                        put("appliedTemplates", buildJsonArray {
                            appliedTemplatesResult.data.forEach { (templateId, sections) ->
                                add(buildJsonObject {
                                    put("templateId", templateId.toString())
                                    put("sectionsCreated", sections.size)
                                })
                            }
                        })
                    }
                }

                // Create message based on templates
                val message = if (appliedTemplatesResult is Result.Success && appliedTemplatesResult.data.isNotEmpty()) {
                    val templateCount = appliedTemplatesResult.data.size
                    val sectionCount = appliedTemplatesResult.data.values.sumOf { it.size }
                    "Task created successfully with $templateCount template(s) applied, creating $sectionCount section(s)"
                } else {
                    "Task created successfully"
                }

                successResponse(responseBuilder, message)
            }

            is Result.Error -> {
                errorResponse(
                    message = "Failed to create task: ${result.error}",
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
        val taskId = UUID.fromString(idStr)
        val summaryView = optionalBoolean(params, "summaryView")
        val includeFeature = optionalBoolean(params, "includeFeature")
        val includeSections = optionalBoolean(params, "includeSections")
        val includeDependencies = optionalBoolean(params, "includeDependencies")

        // Get task from repository
        val taskResult = context.taskRepository().getById(taskId)

        // Handle result
        return when (taskResult) {
            is Result.Success -> {
                val task = taskResult.data
                val dataObject = buildJsonObject {
                    // Basic task information
                    put("id", task.id.toString())
                    put("title", task.title)

                    // Full or truncated summary
                    if (!summaryView) {
                        put("summary", task.summary)
                    } else {
                        val truncatedSummary = if (task.summary.length > 100) {
                            "${task.summary.take(97)}..."
                        } else {
                            task.summary
                        }
                        put("summary", truncatedSummary)
                    }

                    put("status", task.status.name.lowercase())
                    put("priority", task.priority.name.lowercase())
                    put("complexity", task.complexity)
                    put("createdAt", task.createdAt.toString())
                    put("modifiedAt", task.modifiedAt.toString())

                    if (task.featureId != null) {
                        put("featureId", task.featureId.toString())

                        // Include feature if requested
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

                    put("tags", buildJsonArray {
                        task.tags.forEach { tag -> add(JsonPrimitive(tag)) }
                    })

                    // Include sections if requested
                    if (includeSections) {
                        try {
                            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.TASK, taskId)

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
    }

    /**
     * Executes update operation.
     */
    private suspend fun executeUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        taskId: UUID
    ): JsonElement {
        logger.info("Executing update operation")

        // Analyze update efficiency
        val efficiencyMetrics = UpdateEfficiencyMetrics.analyzeUpdate("update_task", params)

        // Get existing task
        val existingTaskResult = context.taskRepository().getById(taskId)
        val existingTask = when (existingTaskResult) {
            is Result.Success -> existingTaskResult.data
            is Result.Error -> return handleRepositoryResult(
                existingTaskResult,
                "Failed to retrieve task"
            ) { JsonNull }
        }

        // Extract update parameters
        val title = optionalString(params, "title") ?: existingTask.title
        val description = optionalString(params, "description") ?: existingTask.description
        val summary = optionalString(params, "summary") ?: existingTask.summary

        val statusStr = optionalString(params, "status")
        val status = if (statusStr != null) parseStatus(statusStr) else existingTask.status

        val priorityStr = optionalString(params, "priority")
        val priority = if (priorityStr != null) parsePriority(priorityStr) else existingTask.priority

        val complexity = optionalInt(params, "complexity") ?: existingTask.complexity

        val featureId = optionalString(params, "featureId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        } ?: existingTask.featureId

        // Validate feature exists if changed
        if (featureId != null && featureId != existingTask.featureId) {
            when (val featureResult = context.repositoryProvider.featureRepository().getById(featureId)) {
                is Result.Error -> {
                    return errorResponse(
                        message = "Feature not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No feature exists with ID $featureId"
                    )
                }
                is Result.Success -> { /* Feature exists, continue */ }
            }
        }

        val tags = optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: existingTask.tags

        // Create updated task
        val updatedTask = existingTask.copy(
            title = title,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            complexity = complexity,
            featureId = featureId,
            tags = tags,
            modifiedAt = Instant.now()
        )

        // Save updated task
        val updateResult = context.taskRepository().update(updatedTask)

        // Build response with efficiency guidance
        val efficiencyLevel = efficiencyMetrics["efficiencyLevel"]?.jsonPrimitive?.content
        val efficiencyGuidance = efficiencyMetrics["guidance"]?.jsonPrimitive?.content ?: ""
        val baseMessage = "Task updated successfully"
        val message = if (efficiencyLevel == "inefficient") {
            "$baseMessage. ⚠️ $efficiencyGuidance"
        } else {
            baseMessage
        }

        // Return minimal response
        return handleRepositoryResult(updateResult, message) { updatedTaskData ->
            buildJsonObject {
                put("id", updatedTaskData.id.toString())
                put("status", updatedTaskData.status.name.lowercase().replace('_', '-'))
                put("modifiedAt", updatedTaskData.modifiedAt.toString())
            }
        }
    }

    /**
     * Executes delete operation.
     */
    private suspend fun executeDelete(
        params: JsonElement,
        context: ToolExecutionContext,
        taskId: UUID
    ): JsonElement {
        logger.info("Executing delete operation")

        // Extract parameters
        val force = optionalBoolean(params, "force", false)
        val deleteSections = optionalBoolean(params, "deleteSections", true)

        // Verify task exists
        val getResult = context.taskRepository().getById(taskId)
        when (getResult) {
            is Result.Success -> { /* Task exists, continue */ }
            is Result.Error -> return handleRepositoryResult(
                getResult,
                "Failed to retrieve task"
            ) { JsonNull }
        }

        // Check for dependencies
        val dependencies = context.dependencyRepository().findByTaskId(taskId)
        val incomingDependencies = dependencies.filter { it.toTaskId == taskId }
        val outgoingDependencies = dependencies.filter { it.fromTaskId == taskId }
        var dependenciesDeletedCount = 0

        // If dependencies exist and force not enabled, error
        if (dependencies.isNotEmpty() && !force) {
            val dependencyInfo = buildJsonObject {
                put("totalDependencies", dependencies.size)
                put("incomingDependencies", incomingDependencies.size)
                put("outgoingDependencies", outgoingDependencies.size)
                put("affectedTasks", dependencies.map {
                    if (it.fromTaskId == taskId) it.toTaskId else it.fromTaskId
                }.distinct().size)
            }

            return errorResponse(
                message = "Cannot delete task with existing dependencies",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Task has ${dependencies.size} dependencies. Use 'force=true' to delete anyway and break dependency chains.",
                additionalData = dependencyInfo
            )
        }

        // Delete dependencies
        if (dependencies.isNotEmpty()) {
            dependenciesDeletedCount = context.dependencyRepository().deleteByTaskId(taskId)
            logger.info("Deleted $dependenciesDeletedCount dependencies for task $taskId")
        }

        // Delete sections if requested
        var sectionsDeletedCount = 0
        if (deleteSections) {
            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.TASK, taskId)

            if (sectionsResult is Result.Success) {
                sectionsDeletedCount = sectionsResult.data.size
                sectionsResult.data.forEach { section ->
                    context.sectionRepository().deleteSection(section.id)
                }
                logger.info("Deleted $sectionsDeletedCount sections for task $taskId")
            }
        }

        // Delete the task
        val deleteResult = context.taskRepository().delete(taskId)

        // Build response message
        val message = if (dependenciesDeletedCount > 0) {
            "Task deleted successfully with $dependenciesDeletedCount dependencies and $sectionsDeletedCount sections"
        } else {
            "Task deleted successfully"
        }

        return handleRepositoryResult(deleteResult, message) { _ ->
            buildJsonObject {
                put("id", taskId.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeletedCount)
                put("dependenciesDeleted", dependenciesDeletedCount)
                if (dependencies.isNotEmpty() && force) {
                    put("warningsBrokenDependencies", true)
                    put("brokenDependencyChains", buildJsonObject {
                        put("incomingDependencies", incomingDependencies.size)
                        put("outgoingDependencies", outgoingDependencies.size)
                        put("affectedTasks", dependencies.map {
                            if (it.fromTaskId == taskId) it.toTaskId else it.fromTaskId
                        }.distinct().size)
                    })
                }
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
        val taskId = UUID.fromString(idStr)
        val format = optionalString(params, "format") ?: "markdown"

        // Validate format
        if (format !in listOf("markdown", "json")) {
            throw ToolValidationException("Invalid format: $format. Must be 'markdown' or 'json'")
        }

        // Get the task
        val taskResult = context.taskRepository().getById(taskId)

        return when (taskResult) {
            is Result.Success -> {
                val task = taskResult.data

                if (format == "markdown") {
                    // Get sections for the task
                    val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.TASK, taskId)
                    val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                    // Render to markdown
                    val renderer = MarkdownRenderer()
                    val markdown = renderer.renderTask(task, sections)

                    val data = buildJsonObject {
                        put("markdown", markdown)
                        put("taskId", taskId.toString())
                    }

                    successResponse(data, "Task transformed to markdown successfully")
                } else {
                    // JSON format - use get logic
                    executeGet(params, context)
                }
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

    private fun parseStatus(status: String): TaskStatus {
        return when (status.lowercase().replace('-', '_')) {
            "pending" -> TaskStatus.PENDING
            "in_progress", "inprogress", "in-progress" -> TaskStatus.IN_PROGRESS
            "completed" -> TaskStatus.COMPLETED
            "cancelled", "canceled" -> TaskStatus.CANCELLED
            "deferred" -> TaskStatus.DEFERRED
            else -> throw IllegalArgumentException("Invalid status: $status")
        }
    }

    private fun isValidPriority(priority: String): Boolean {
        return try {
            parsePriority(priority)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parsePriority(priority: String): Priority {
        return when (priority.lowercase()) {
            "high" -> Priority.HIGH
            "medium", "med" -> Priority.MEDIUM
            "low" -> Priority.LOW
            else -> throw IllegalArgumentException("Invalid priority: $priority")
        }
    }
}
