package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.UpdateEfficiencyMetrics
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.rendering.MarkdownRenderer
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Consolidated MCP tool for all single-entity feature operations.
 * Replaces create_feature, get_feature, update_feature, delete_feature, and feature_to_markdown.
 * Reduces token overhead through parameter and description consolidation.
 */
class ManageFeatureTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.FEATURE_MANAGEMENT

    override val name: String = "manage_feature"

    override val title: String = "Manage Feature"

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

    override val description: String = """Unified feature management: create, get, update, delete, or export features.

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | Operation: create, get, update, delete, export |
| id | UUID | Yes* | Feature ID (*required for: get, update, delete, export) |
| name | string | Yes** | Feature name (**required for: create) |
| summary | string | No | Brief summary (max 500 chars) |
| description | string | No | Detailed description (user-provided) |
| status | enum | No | planning, in-development, completed, archived |
| priority | enum | No | high, medium, low |
| projectId | UUID | No | Parent project |
| templateIds | array | No | Templates to apply (create only) |
| tags | string | No | Comma-separated tags |
| format | enum | No | Export format: markdown, json (default: markdown) |
| includeSections | boolean | No | Include sections (get/export) |
| includeProject | boolean | No | Include project info (get) |
| summaryView | boolean | No | Truncate text fields (get) |
| deleteSections | boolean | No | Delete sections (default: true) |
| force | boolean | No | Delete with tasks/dependencies |

Usage: Single tool for all feature operations. Specify operation, then provide relevant parameters.
Related: search_features, create_dependency
Docs: task-orchestrator://docs/tools/manage-feature
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
                        "description" to JsonPrimitive("Feature ID (required for: get, update, delete, export)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Feature name (required for: create)")
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
                        "description" to JsonPrimitive("Feature status"),
                        "enum" to JsonArray(FeatureStatus.entries.map { JsonPrimitive(it.name.lowercase().replace('_', '-')) })
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Priority level"),
                        "enum" to JsonArray(Priority.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Parent project ID"),
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
                "includeProject" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include project info (get)"),
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
                        "description" to JsonPrimitive("Delete with tasks/dependencies (delete)"),
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
                throw ToolValidationException("Invalid feature ID format. Must be a valid UUID.")
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

        optionalString(params, "priority")?.let { priority ->
            if (!isValidPriority(priority)) {
                throw ToolValidationException("Invalid priority: $priority. Must be one of: high, medium, low")
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
        logger.info("Executing manage_feature tool")

        return try {
            val operation = requireString(params, "operation")

            when (operation) {
                "create" -> executeCreate(params, context)
                "get" -> executeGet(params, context)
                "update" -> {
                    val featureId = extractEntityId(params, "id")
                    executeWithLocking("update_feature", EntityType.FEATURE, featureId) {
                        executeUpdate(params, context, featureId)
                    }
                }
                "delete" -> {
                    val featureId = extractEntityId(params, "id")
                    executeWithLocking("delete_feature", EntityType.FEATURE, featureId) {
                        executeDelete(params, context, featureId)
                    }
                }
                "export" -> executeExport(params, context)
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in manage_feature: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in manage_feature", e)
            errorResponse(
                message = "Failed to execute feature operation",
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

        // Parse priority
        val priorityStr = optionalString(params, "priority") ?: "medium"
        val priority = parsePriority(priorityStr)

        // Parse project ID
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }

        // Validate that referenced project exists
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

        // Create the feature entity
        val feature = Feature(
            name = name,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            projectId = projectId,
            tags = tags,
        )

        // Create feature in repository
        val result = context.featureRepository().create(feature)

        // Process result and apply templates
        return when (result) {
            is Result.Success -> {
                val createdFeature = result.data

                // Apply templates if specified
                val appliedTemplatesResult = if (templateIds.isNotEmpty()) {
                    context.repositoryProvider.templateRepository()
                        .applyMultipleTemplates(templateIds, EntityType.FEATURE, createdFeature.id)
                } else {
                    null
                }

                // Build response
                val responseBuilder = buildJsonObject {
                    put("id", createdFeature.id.toString())
                    put("name", createdFeature.name)
                    if (createdFeature.description != null) {
                        put("description", createdFeature.description)
                    } else {
                        put("description", JsonNull)
                    }
                    put("summary", createdFeature.summary)
                    put("status", createdFeature.status.name.lowercase().replace('_', '-'))
                    put("priority", createdFeature.priority.name.lowercase())
                    put("createdAt", createdFeature.createdAt.toString())
                    put("modifiedAt", createdFeature.modifiedAt.toString())

                    if (createdFeature.projectId != null) {
                        put("projectId", createdFeature.projectId.toString())
                    } else {
                        put("projectId", JsonNull)
                    }

                    put("tags", buildJsonArray {
                        createdFeature.tags.forEach { add(it) }
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
                    "Feature created successfully with $templateCount template(s) applied, creating $sectionCount section(s)"
                } else {
                    "Feature created successfully"
                }

                successResponse(responseBuilder, message)
            }

            is Result.Error -> {
                errorResponse(
                    message = "Failed to create feature: ${result.error}",
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
        val featureId = UUID.fromString(idStr)
        val summaryView = optionalBoolean(params, "summaryView")
        val includeProject = optionalBoolean(params, "includeProject")
        val includeSections = optionalBoolean(params, "includeSections")

        // Get feature from repository
        val featureResult = context.featureRepository().getById(featureId)

        // Handle result
        return when (featureResult) {
            is Result.Success -> {
                val feature = featureResult.data
                val dataObject = buildJsonObject {
                    // Basic feature information
                    put("id", feature.id.toString())
                    put("name", feature.name)

                    // Full or truncated summary
                    if (!summaryView) {
                        put("summary", feature.summary)
                    } else {
                        val truncatedSummary = if (feature.summary.length > 100) {
                            "${feature.summary.take(97)}..."
                        } else {
                            feature.summary
                        }
                        put("summary", truncatedSummary)
                    }

                    put("status", feature.status.name.lowercase().replace('_', '-'))
                    put("priority", feature.priority.name.lowercase())
                    put("createdAt", feature.createdAt.toString())
                    put("modifiedAt", feature.modifiedAt.toString())

                    if (feature.projectId != null) {
                        put("projectId", feature.projectId.toString())

                        // Include project if requested
                        if (includeProject) {
                            val projectResult = context.projectRepository().getById(feature.projectId)
                            if (projectResult is Result.Success) {
                                val project = projectResult.data
                                put("project", buildJsonObject {
                                    put("id", project.id.toString())
                                    put("name", project.name)
                                    put("status", project.status.name.lowercase().replace('_', '-'))
                                    if (!summaryView) {
                                        put("summary", project.summary)
                                    }
                                })
                            }
                        }
                    } else {
                        put("projectId", JsonNull)
                    }

                    put("tags", buildJsonArray {
                        feature.tags.forEach { tag -> add(JsonPrimitive(tag)) }
                    })

                    // Include sections if requested
                    if (includeSections) {
                        try {
                            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, featureId)

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

                successResponse(dataObject, "Feature retrieved successfully")
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
    }

    /**
     * Executes update operation.
     */
    private suspend fun executeUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        featureId: UUID
    ): JsonElement {
        logger.info("Executing update operation")

        // Analyze update efficiency
        val efficiencyMetrics = UpdateEfficiencyMetrics.analyzeUpdate("update_feature", params)

        // Get existing feature
        val existingFeatureResult = context.featureRepository().getById(featureId)
        val existingFeature = when (existingFeatureResult) {
            is Result.Success -> existingFeatureResult.data
            is Result.Error -> return handleRepositoryResult(
                existingFeatureResult,
                "Failed to retrieve feature"
            ) { JsonNull }
        }

        // Extract update parameters
        val name = optionalString(params, "name") ?: existingFeature.name
        val description = optionalString(params, "description") ?: existingFeature.description
        val summary = optionalString(params, "summary") ?: existingFeature.summary

        val statusStr = optionalString(params, "status")
        val status = if (statusStr != null) parseStatus(statusStr) else existingFeature.status

        val priorityStr = optionalString(params, "priority")
        val priority = if (priorityStr != null) parsePriority(priorityStr) else existingFeature.priority

        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        } ?: existingFeature.projectId

        // Validate project exists if changed
        if (projectId != null && projectId != existingFeature.projectId) {
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

        val tags = optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: existingFeature.tags

        // Create updated feature
        val updatedFeature = existingFeature.copy(
            name = name,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            projectId = projectId,
            tags = tags,
            modifiedAt = Instant.now()
        )

        // Save updated feature
        val updateResult = context.featureRepository().update(updatedFeature)

        // Build response with efficiency guidance
        val efficiencyLevel = efficiencyMetrics["efficiencyLevel"]?.jsonPrimitive?.content
        val efficiencyGuidance = efficiencyMetrics["guidance"]?.jsonPrimitive?.content ?: ""
        val baseMessage = "Feature updated successfully"
        val message = if (efficiencyLevel == "inefficient") {
            "$baseMessage. ⚠️ $efficiencyGuidance"
        } else {
            baseMessage
        }

        // Return minimal response
        return handleRepositoryResult(updateResult, message) { updatedFeatureData ->
            buildJsonObject {
                put("id", updatedFeatureData.id.toString())
                put("status", updatedFeatureData.status.name.lowercase().replace('_', '-'))
                put("modifiedAt", updatedFeatureData.modifiedAt.toString())
            }
        }
    }

    /**
     * Executes delete operation.
     */
    private suspend fun executeDelete(
        params: JsonElement,
        context: ToolExecutionContext,
        featureId: UUID
    ): JsonElement {
        logger.info("Executing delete operation")

        // Extract parameters
        val force = optionalBoolean(params, "force", false)
        val deleteSections = optionalBoolean(params, "deleteSections", true)

        // Verify feature exists
        val getResult = context.featureRepository().getById(featureId)
        when (getResult) {
            is Result.Success -> { /* Feature exists, continue */ }
            is Result.Error -> return handleRepositoryResult(
                getResult,
                "Failed to retrieve feature"
            ) { JsonNull }
        }

        // Check for associated tasks
        val tasksResult = context.taskRepository().findByFeature(featureId, limit = 1000)
        val tasks = when (tasksResult) {
            is Result.Success -> tasksResult.data
            is Result.Error -> emptyList()
        }
        var tasksDeletedCount = 0

        // If tasks exist and force not enabled, error
        if (tasks.isNotEmpty() && !force) {
            val taskInfo = buildJsonObject {
                put("totalTasks", tasks.size)
                put("taskIds", buildJsonArray {
                    tasks.forEach { task -> add(task.id.toString()) }
                })
            }

            return errorResponse(
                message = "Cannot delete feature with existing tasks",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Feature has ${tasks.size} tasks. Use 'force=true' to delete anyway and orphan tasks.",
                additionalData = taskInfo
            )
        }

        // Delete tasks if force enabled
        if (tasks.isNotEmpty() && force) {
            tasks.forEach { task ->
                context.taskRepository().delete(task.id)
            }
            tasksDeletedCount = tasks.size
            logger.info("Deleted $tasksDeletedCount tasks for feature $featureId")
        }

        // Delete sections if requested
        var sectionsDeletedCount = 0
        if (deleteSections) {
            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, featureId)

            if (sectionsResult is Result.Success) {
                sectionsDeletedCount = sectionsResult.data.size
                sectionsResult.data.forEach { section ->
                    context.sectionRepository().deleteSection(section.id)
                }
                logger.info("Deleted $sectionsDeletedCount sections for feature $featureId")
            }
        }

        // Delete the feature
        val deleteResult = context.featureRepository().delete(featureId)

        // Build response message
        val message = if (tasksDeletedCount > 0) {
            "Feature deleted successfully with $tasksDeletedCount tasks and $sectionsDeletedCount sections"
        } else {
            "Feature deleted successfully"
        }

        return handleRepositoryResult(deleteResult, message) { _ ->
            buildJsonObject {
                put("id", featureId.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeletedCount)
                put("tasksDeleted", tasksDeletedCount)
                if (tasksDeletedCount > 0 && force) {
                    put("warningsOrphanedTasks", true)
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
        val featureId = UUID.fromString(idStr)
        val format = optionalString(params, "format") ?: "markdown"

        // Validate format
        if (format !in listOf("markdown", "json")) {
            throw ToolValidationException("Invalid format: $format. Must be 'markdown' or 'json'")
        }

        // Get the feature
        val featureResult = context.featureRepository().getById(featureId)

        return when (featureResult) {
            is Result.Success -> {
                val feature = featureResult.data

                if (format == "markdown") {
                    // Get sections for the feature
                    val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, featureId)
                    val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                    // Render to markdown
                    val renderer = MarkdownRenderer()
                    val markdown = renderer.renderFeature(feature, sections)

                    val data = buildJsonObject {
                        put("markdown", markdown)
                        put("featureId", featureId.toString())
                    }

                    successResponse(data, "Feature transformed to markdown successfully")
                } else {
                    // JSON format - use get logic
                    executeGet(params, context)
                }
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

    private fun parseStatus(status: String): FeatureStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> FeatureStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> FeatureStatus.IN_DEVELOPMENT
            "completed" -> FeatureStatus.COMPLETED
            "archived" -> FeatureStatus.ARCHIVED
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
