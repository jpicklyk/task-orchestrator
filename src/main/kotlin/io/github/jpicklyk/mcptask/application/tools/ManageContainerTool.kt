package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.WorkflowConfigLoaderImpl
import io.github.jpicklyk.mcptask.application.service.WorkflowService
import io.github.jpicklyk.mcptask.application.service.WorkflowServiceImpl
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Consolidated MCP tool for write operations on all container types (project, feature, task).
 * Includes 5 operations: create, update, delete, setStatus, and bulkUpdate.
 *
 * This tool unifies container management across all entity types, reducing token overhead
 * and providing a consistent interface for AI agents.
 */
class ManageContainerTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {

    private val statusValidator = StatusValidator()
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "manage_container"

    override val title: String = "Manage Container"

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

    override val description: String = """Unified write operations for containers (project, feature, task).

Operations: create, update, delete, setStatus, bulkUpdate

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | create, update, delete, setStatus, bulkUpdate |
| containerType | enum | Yes | project, feature, task |
| id | UUID | Varies | Container ID (required for: update, delete, setStatus) |
| ids | array | No | Container IDs (required for: bulkUpdate) |
| name/title | string | Varies | Name/title (required for: create) |
| summary | string | No | Brief summary (max 500 chars) |
| description | string | No | Detailed description |
| status | enum | No | Container status |
| priority | enum | No | Priority (feature/task only) |
| complexity | integer | No | Complexity 1-10 (task only) |
| projectId | UUID | No | Parent project ID (feature/task) |
| featureId | UUID | No | Parent feature ID (task only) |
| templateIds | array | No | Templates to apply (create only) |
| tags | string | No | Comma-separated tags |
| deleteSections | boolean | No | Delete sections (default: true) |
| force | boolean | No | Force delete with dependencies |
| containers | array | No | Bulk update array (bulkUpdate only) |

Usage: Consolidates create/update/delete/setStatus/bulkUpdate for all container types.
Related: query_tasks, get_overview
Docs: task-orchestrator://docs/tools/manage-container
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(listOf("create", "update", "delete", "setStatus", "bulkUpdate").map { JsonPrimitive(it) })
                    )
                ),
                "containerType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of container"),
                        "enum" to JsonArray(listOf("project", "feature", "task").map { JsonPrimitive(it) })
                    )
                ),
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Container ID (required for: update, delete, setStatus)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "ids" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Container IDs (deprecated, use containers array for bulkUpdate)"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid")))
                    )
                ),
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Container name (project/feature) or title (task)")
                    )
                ),
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Task title (alias for name)")
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
                        "description" to JsonPrimitive("Container status")
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Priority level (feature/task)"),
                        "enum" to JsonArray(Priority.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "complexity" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Complexity 1-10 (task only)"),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(10)
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Parent project ID (feature/task)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Parent feature ID (task only)"),
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
                        "description" to JsonPrimitive("Force delete with dependencies"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "containers" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Array of containers for bulkUpdate (max 100)"),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(
                                    mapOf(
                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                        "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "description" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "priority" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "complexity" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                        "projectId" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "featureId" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "required" to JsonArray(listOf(JsonPrimitive("id")))
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("operation", "containerType")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        val containerType = requireString(params, "containerType")

        // Validate operation
        if (operation !in listOf("create", "update", "delete", "setStatus", "bulkUpdate")) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: create, update, delete, setStatus, bulkUpdate")
        }

        // Validate container type
        if (containerType !in listOf("project", "feature", "task")) {
            throw ToolValidationException("Invalid containerType: $containerType. Must be one of: project, feature, task")
        }

        // Validate ID for operations that require it
        if (operation in listOf("update", "delete", "setStatus")) {
            val idStr = requireString(params, "id")
            try {
                UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid $containerType ID format. Must be a valid UUID.")
            }
        }

        // Validate name/title for create
        if (operation == "create") {
            val name = optionalString(params, if (containerType == "task") "title" else "name")
                ?: optionalString(params, "name") // Allow both name and title
                ?: optionalString(params, "title")
            if (name.isNullOrBlank()) {
                throw ToolValidationException("${if (containerType == "task") "Title" else "Name"} is required for create operation")
            }
        }

        // Validate bulk update
        if (operation == "bulkUpdate") {
            validateBulkUpdateParams(params, containerType)
        }

        // Validate optional parameters
        validateOptionalParams(params, containerType)
    }

    private fun validateBulkUpdateParams(params: JsonElement, containerType: String) {
        val containersArray = params.jsonObject["containers"]
            ?: throw ToolValidationException("Missing required parameter for bulkUpdate: containers")

        if (containersArray !is JsonArray) {
            throw ToolValidationException("Parameter 'containers' must be an array")
        }

        if (containersArray.isEmpty()) {
            throw ToolValidationException("At least one container must be provided for bulkUpdate")
        }

        if (containersArray.size > 100) {
            throw ToolValidationException("Maximum 100 containers allowed for bulkUpdate (got ${containersArray.size})")
        }

        containersArray.forEachIndexed { index, containerElement ->
            if (containerElement !is JsonObject) {
                throw ToolValidationException("Container at index $index must be an object")
            }

            val containerObj = containerElement.jsonObject

            if (!containerObj.containsKey("id")) {
                throw ToolValidationException("Container at index $index missing required field: id")
            }

            try {
                UUID.fromString(containerObj["id"]?.jsonPrimitive?.content)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid id at index $index")
            }

            val updateFields = listOf("name", "title", "description", "summary", "status", "priority", "complexity", "projectId", "featureId", "tags")
            if (updateFields.none { containerObj.containsKey(it) }) {
                throw ToolValidationException("Container at index $index has no fields to update")
            }

            // Validate status (config-aware via StatusValidator)
            containerObj["status"]?.jsonPrimitive?.content?.let { status ->
                val validationResult = statusValidator.validateStatus(status, containerType)
                if (validationResult is StatusValidator.ValidationResult.Invalid) {
                    throw ToolValidationException("At index $index: ${validationResult.reason}")
                }
            }

            // Validate priority (if applicable)
            if (containerType in listOf("feature", "task")) {
                containerObj["priority"]?.jsonPrimitive?.content?.let { priority ->
                    if (!isValidPriority(priority)) {
                        throw ToolValidationException("Invalid priority at index $index: $priority")
                    }
                }
            }

            // Validate complexity (task only)
            if (containerType == "task") {
                containerObj["complexity"]?.let {
                    val complexity = if (it is JsonPrimitive && it.isString) it.content.toIntOrNull() else it.jsonPrimitive.intOrNull
                    if (complexity == null || complexity < 1 || complexity > 10) {
                        throw ToolValidationException("Invalid complexity at index $index: must be 1-10")
                    }
                }
            }
        }
    }

    private fun validateOptionalParams(params: JsonElement, containerType: String) {
        // Validate status if present (config-aware via StatusValidator)
        optionalString(params, "status")?.let { status ->
            val validationResult = statusValidator.validateStatus(status, containerType)
            if (validationResult is StatusValidator.ValidationResult.Invalid) {
                throw ToolValidationException(validationResult.reason)
            }
        }

        // Validate priority if present (feature/task only)
        if (containerType in listOf("feature", "task")) {
            optionalString(params, "priority")?.let { priority ->
                if (!isValidPriority(priority)) {
                    throw ToolValidationException("Invalid priority: $priority. Must be one of: high, medium, low")
                }
            }
        }

        // Validate complexity if present (task only)
        if (containerType == "task") {
            optionalInt(params, "complexity")?.let { complexity ->
                if (complexity < 1 || complexity > 10) {
                    throw ToolValidationException("Complexity must be between 1 and 10")
                }
            }
        }

        // Validate UUID parameters
        optionalString(params, "projectId")?.let { projectId ->
            if (projectId.isNotEmpty()) {
                try {
                    UUID.fromString(projectId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid projectId format. Must be a valid UUID")
                }
            }
        }

        optionalString(params, "featureId")?.let { featureId ->
            if (featureId.isNotEmpty()) {
                try {
                    UUID.fromString(featureId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid featureId format. Must be a valid UUID")
                }
            }
        }

        // Validate templateIds if present
        val paramsObj = params as? JsonObject
        paramsObj?.get("templateIds")?.let { templateIdsElement ->
            if (templateIdsElement !is JsonArray) {
                throw ToolValidationException("Parameter 'templateIds' must be an array of strings (UUIDs)")
            }

            templateIdsElement.forEachIndexed { index, item ->
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

    /**
     * Helper method to create WorkflowService from execution context.
     * WorkflowService requires repositories which are only available at execution time.
     */
    private fun createWorkflowService(context: ToolExecutionContext): WorkflowService {
        return WorkflowServiceImpl(
            workflowConfigLoader = WorkflowConfigLoaderImpl(),
            taskRepository = context.taskRepository(),
            featureRepository = context.featureRepository(),
            projectRepository = context.projectRepository(),
            statusValidator = statusValidator
        )
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing manage_container tool")

        return try {
            val operation = requireString(params, "operation")
            val containerType = requireString(params, "containerType")

            when (operation) {
                "create" -> executeCreate(params, context, containerType)
                "update" -> {
                    val id = extractEntityId(params, "id")
                    val entityType = getEntityType(containerType)
                    executeWithLocking("update_$containerType", entityType, id) {
                        executeUpdate(params, context, containerType, id)
                    }
                }
                "delete" -> {
                    val id = extractEntityId(params, "id")
                    val entityType = getEntityType(containerType)
                    executeWithLocking("delete_$containerType", entityType, id) {
                        executeDelete(params, context, containerType, id)
                    }
                }
                "setStatus" -> {
                    val id = extractEntityId(params, "id")
                    val entityType = getEntityType(containerType)
                    executeWithLocking("set_status_$containerType", entityType, id) {
                        executeSetStatus(params, context, containerType, id)
                    }
                }
                "bulkUpdate" -> executeBulkUpdate(params, context, containerType)
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in manage_container: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in manage_container", e)
            errorResponse(
                message = "Failed to execute container operation",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== CREATE OPERATION ==========

    private suspend fun executeCreate(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String
    ): JsonElement {
        logger.info("Executing create operation for $containerType")

        return when (containerType) {
            "project" -> createProject(params, context)
            "feature" -> createFeature(params, context)
            "task" -> createTask(params, context)
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    private suspend fun createProject(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val name = requireString(params, "name")
        val description = optionalString(params, "description")
        val summary = optionalString(params, "summary") ?: ""
        val statusStr = optionalString(params, "status") ?: "planning"
        val status = parseProjectStatus(statusStr)
        val tags = parseTags(params)

        val project = Project(
            name = name,
            description = description,
            summary = summary,
            status = status,
            tags = tags
        )

        return when (val result = context.projectRepository().create(project)) {
            is Result.Success -> {
                val createdProject = result.data
                successResponse(
                    buildJsonObject {
                        put("id", createdProject.id.toString())
                        put("name", createdProject.name)
                        put("status", createdProject.status.name.lowercase().replace('_', '-'))
                        put("createdAt", createdProject.createdAt.toString())
                    },
                    "Project created successfully"
                )
            }
            is Result.Error -> errorResponse(
                "Failed to create project: ${result.error}",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }

    private suspend fun createFeature(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val name = requireString(params, "name")
        val description = optionalString(params, "description")
        val summary = optionalString(params, "summary") ?: ""
        val statusStr = optionalString(params, "status") ?: "planning"
        val status = parseFeatureStatus(statusStr)
        val priorityStr = optionalString(params, "priority") ?: "medium"
        val priority = parsePriority(priorityStr)
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val tags = parseTags(params)

        // Validate project exists if specified
        if (projectId != null) {
            when (context.repositoryProvider.projectRepository().getById(projectId)) {
                is Result.Error -> return errorResponse(
                    "Project not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    "No project exists with ID $projectId"
                )
                is Result.Success -> { /* Project exists */ }
            }
        }

        // Get template IDs
        val templateIds = extractTemplateIds(params)

        val feature = Feature(
            name = name,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            projectId = projectId,
            tags = tags
        )

        return when (val result = context.featureRepository().create(feature)) {
            is Result.Success -> {
                val createdFeature = result.data

                // Apply templates if specified
                val appliedTemplatesResult = if (templateIds.isNotEmpty()) {
                    context.repositoryProvider.templateRepository()
                        .applyMultipleTemplates(templateIds, EntityType.FEATURE, createdFeature.id)
                } else null

                val message = if (appliedTemplatesResult is Result.Success && appliedTemplatesResult.data.isNotEmpty()) {
                    val templateCount = appliedTemplatesResult.data.size
                    val sectionCount = appliedTemplatesResult.data.values.sumOf { it.size }
                    "Feature created successfully with $templateCount template(s) applied, creating $sectionCount section(s)"
                } else {
                    "Feature created successfully"
                }

                successResponse(
                    buildJsonObject {
                        put("id", createdFeature.id.toString())
                        put("name", createdFeature.name)
                        put("status", createdFeature.status.name.lowercase().replace('_', '-'))
                        put("createdAt", createdFeature.createdAt.toString())
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
                    },
                    message
                )
            }
            is Result.Error -> errorResponse(
                "Failed to create feature: ${result.error}",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }

    private suspend fun createTask(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val title = optionalString(params, "title") ?: requireString(params, "name")
        val description = optionalString(params, "description")
        val summary = optionalString(params, "summary") ?: ""
        val statusStr = optionalString(params, "status") ?: "pending"
        val status = parseTaskStatus(statusStr)
        val priorityStr = optionalString(params, "priority") ?: "medium"
        val priority = parsePriority(priorityStr)
        val complexity = optionalInt(params, "complexity") ?: 5
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val featureId = optionalString(params, "featureId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val tags = parseTags(params)

        // Validate parent entities exist
        if (projectId != null) {
            when (context.repositoryProvider.projectRepository().getById(projectId)) {
                is Result.Error -> return errorResponse(
                    "Project not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    "No project exists with ID $projectId"
                )
                is Result.Success -> { /* Project exists */ }
            }
        }

        if (featureId != null) {
            when (context.repositoryProvider.featureRepository().getById(featureId)) {
                is Result.Error -> return errorResponse(
                    "Feature not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    "No feature exists with ID $featureId"
                )
                is Result.Success -> { /* Feature exists */ }
            }
        }

        // Get template IDs
        val templateIds = extractTemplateIds(params)

        val task = Task(
            title = title,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            complexity = complexity,
            projectId = projectId,
            featureId = featureId,
            tags = tags
        )

        return when (val result = context.taskRepository().create(task)) {
            is Result.Success -> {
                val createdTask = result.data

                // Apply templates if specified
                val appliedTemplatesResult = if (templateIds.isNotEmpty()) {
                    context.repositoryProvider.templateRepository()
                        .applyMultipleTemplates(templateIds, EntityType.TASK, createdTask.id)
                } else null

                val message = if (appliedTemplatesResult is Result.Success && appliedTemplatesResult.data.isNotEmpty()) {
                    val templateCount = appliedTemplatesResult.data.size
                    val sectionCount = appliedTemplatesResult.data.values.sumOf { it.size }
                    "Task created successfully with $templateCount template(s) applied, creating $sectionCount section(s)"
                } else {
                    "Task created successfully"
                }

                successResponse(
                    buildJsonObject {
                        put("id", createdTask.id.toString())
                        put("title", createdTask.title)
                        put("status", createdTask.status.name.lowercase().replace('_', '-'))
                        put("createdAt", createdTask.createdAt.toString())
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
                    },
                    message
                )
            }
            is Result.Error -> errorResponse(
                "Failed to create task: ${result.error}",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }

    // ========== UPDATE OPERATION ==========

    private suspend fun executeUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String,
        id: UUID
    ): JsonElement {
        logger.info("Executing update operation for $containerType: $id")

        return when (containerType) {
            "project" -> updateProject(params, context, id)
            "feature" -> updateFeature(params, context, id)
            "task" -> updateTask(params, context, id)
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    private suspend fun updateProject(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        val existingResult = context.projectRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return handleRepositoryResult(existingResult, "Failed to retrieve project") { JsonNull }
        }

        val name = optionalString(params, "name") ?: existing.name
        val description = optionalString(params, "description") ?: existing.description
        val summary = optionalString(params, "summary") ?: existing.summary
        val statusStr = optionalString(params, "status")
        val status = if (statusStr != null) parseProjectStatus(statusStr) else existing.status
        val tags = optionalString(params, "tags")?.let { parseTags(params) } ?: existing.tags

        val updated = existing.update(
            name = name,
            description = description,
            summary = summary,
            status = status,
            tags = tags
        )

        return handleRepositoryResult(
            context.projectRepository().update(updated),
            "Project updated successfully"
        ) { updatedProject ->
            buildJsonObject {
                put("id", updatedProject.id.toString())
                put("status", updatedProject.status.name.lowercase().replace('_', '-'))
                put("modifiedAt", updatedProject.modifiedAt.toString())
            }
        }
    }

    private suspend fun updateFeature(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        val existingResult = context.featureRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return handleRepositoryResult(existingResult, "Failed to retrieve feature") { JsonNull }
        }

        val name = optionalString(params, "name") ?: existing.name
        val description = optionalString(params, "description") ?: existing.description
        val summary = optionalString(params, "summary") ?: existing.summary
        val statusStr = optionalString(params, "status")
        val status = if (statusStr != null) parseFeatureStatus(statusStr) else existing.status
        val priorityStr = optionalString(params, "priority")
        val priority = if (priorityStr != null) parsePriority(priorityStr) else existing.priority
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        } ?: existing.projectId
        val tags = optionalString(params, "tags")?.let { parseTags(params) } ?: existing.tags

        // Validate project exists if changed
        if (projectId != null && projectId != existing.projectId) {
            when (context.repositoryProvider.projectRepository().getById(projectId)) {
                is Result.Error -> return errorResponse(
                    "Project not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    "No project exists with ID $projectId"
                )
                is Result.Success -> { /* Project exists */ }
            }
        }

        val updated = existing.update(
            name = name,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            projectId = projectId,
            tags = tags
        )

        return handleRepositoryResult(
            context.featureRepository().update(updated),
            "Feature updated successfully"
        ) { updatedFeature ->
            buildJsonObject {
                put("id", updatedFeature.id.toString())
                put("status", updatedFeature.status.name.lowercase().replace('_', '-'))
                put("modifiedAt", updatedFeature.modifiedAt.toString())
            }
        }
    }

    private suspend fun updateTask(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        val existingResult = context.taskRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return handleRepositoryResult(existingResult, "Failed to retrieve task") { JsonNull }
        }

        val title = optionalString(params, "title") ?: optionalString(params, "name") ?: existing.title
        val description = optionalString(params, "description") ?: existing.description
        val summary = optionalString(params, "summary") ?: existing.summary
        val statusStr = optionalString(params, "status")
        val status = if (statusStr != null) parseTaskStatus(statusStr) else existing.status
        val priorityStr = optionalString(params, "priority")
        val priority = if (priorityStr != null) parsePriority(priorityStr) else existing.priority
        val complexity = optionalInt(params, "complexity") ?: existing.complexity
        val featureId = optionalString(params, "featureId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        } ?: existing.featureId
        val tags = optionalString(params, "tags")?.let { parseTags(params) } ?: existing.tags

        // Validate feature exists if changed
        if (featureId != null && featureId != existing.featureId) {
            when (context.repositoryProvider.featureRepository().getById(featureId)) {
                is Result.Error -> return errorResponse(
                    "Feature not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    "No feature exists with ID $featureId"
                )
                is Result.Success -> { /* Feature exists */ }
            }
        }

        val updated = existing.copy(
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

        return handleRepositoryResult(
            context.taskRepository().update(updated),
            "Task updated successfully"
        ) { updatedTask ->
            buildJsonObject {
                put("id", updatedTask.id.toString())
                put("status", updatedTask.status.name.lowercase().replace('_', '-'))
                put("modifiedAt", updatedTask.modifiedAt.toString())
            }
        }
    }

    // ========== DELETE OPERATION ==========

    private suspend fun executeDelete(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String,
        id: UUID
    ): JsonElement {
        logger.info("Executing delete operation for $containerType: $id")

        val force = optionalBoolean(params, "force", false)
        val deleteSections = optionalBoolean(params, "deleteSections", true)

        return when (containerType) {
            "project" -> deleteProject(context, id, force, deleteSections)
            "feature" -> deleteFeature(context, id, force, deleteSections)
            "task" -> deleteTask(context, id, force, deleteSections)
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    private suspend fun deleteProject(
        context: ToolExecutionContext,
        id: UUID,
        force: Boolean,
        deleteSections: Boolean
    ): JsonElement {
        // Verify project exists
        when (context.projectRepository().getById(id)) {
            is Result.Success -> { /* Project exists */ }
            is Result.Error -> return errorResponse(
                "Project not found",
                ErrorCodes.RESOURCE_NOT_FOUND,
                "No project exists with ID $id"
            )
        }

        // Check for child features and tasks
        val features = when (val result = context.featureRepository().findByProject(id)) {
            is Result.Success -> result.data
            is Result.Error -> emptyList()
        }

        val tasks = when (val result = context.taskRepository().findByProject(id, limit = 1000)) {
            is Result.Success -> result.data
            is Result.Error -> emptyList()
        }

        if ((features.isNotEmpty() || tasks.isNotEmpty()) && !force) {
            return errorResponse(
                "Cannot delete project with existing features or tasks",
                ErrorCodes.VALIDATION_ERROR,
                "Project has ${features.size} features and ${tasks.size} tasks. Use 'force=true' to delete anyway.",
                buildJsonObject {
                    put("featureCount", features.size)
                    put("taskCount", tasks.size)
                }
            )
        }

        // Delete sections if requested
        var sectionsDeleted = 0
        if (deleteSections) {
            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.PROJECT, id)
            if (sectionsResult is Result.Success) {
                sectionsDeleted = sectionsResult.data.size
                sectionsResult.data.forEach { section ->
                    context.sectionRepository().deleteSection(section.id)
                }
            }
        }

        // Delete the project
        return handleRepositoryResult(
            context.projectRepository().delete(id),
            if (features.isNotEmpty() || tasks.isNotEmpty()) {
                "Project deleted with ${features.size} features and ${tasks.size} tasks"
            } else {
                "Project deleted successfully"
            }
        ) { _ ->
            buildJsonObject {
                put("id", id.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeleted)
                if (features.isNotEmpty() || tasks.isNotEmpty()) {
                    put("featuresDeleted", features.size)
                    put("tasksDeleted", tasks.size)
                }
            }
        }
    }

    private suspend fun deleteFeature(
        context: ToolExecutionContext,
        id: UUID,
        force: Boolean,
        deleteSections: Boolean
    ): JsonElement {
        // Verify feature exists
        when (context.featureRepository().getById(id)) {
            is Result.Success -> { /* Feature exists */ }
            is Result.Error -> return errorResponse(
                "Feature not found",
                ErrorCodes.RESOURCE_NOT_FOUND,
                "No feature exists with ID $id"
            )
        }

        // Check for child tasks
        val tasks = when (val result = context.taskRepository().findByFeature(id)) {
            is Result.Success -> result.data
            is Result.Error -> emptyList()
        }

        if (tasks.isNotEmpty() && !force) {
            return errorResponse(
                "Cannot delete feature with existing tasks",
                ErrorCodes.VALIDATION_ERROR,
                "Feature has ${tasks.size} tasks. Use 'force=true' to delete anyway.",
                buildJsonObject {
                    put("taskCount", tasks.size)
                }
            )
        }

        // Delete sections if requested
        var sectionsDeleted = 0
        if (deleteSections) {
            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, id)
            if (sectionsResult is Result.Success) {
                sectionsDeleted = sectionsResult.data.size
                sectionsResult.data.forEach { section ->
                    context.sectionRepository().deleteSection(section.id)
                }
            }
        }

        // Delete the feature
        return handleRepositoryResult(
            context.featureRepository().delete(id),
            if (tasks.isNotEmpty()) {
                "Feature deleted with ${tasks.size} tasks"
            } else {
                "Feature deleted successfully"
            }
        ) { _ ->
            buildJsonObject {
                put("id", id.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeleted)
                if (tasks.isNotEmpty()) {
                    put("tasksDeleted", tasks.size)
                }
            }
        }
    }

    private suspend fun deleteTask(
        context: ToolExecutionContext,
        id: UUID,
        force: Boolean,
        deleteSections: Boolean
    ): JsonElement {
        // Verify task exists
        when (context.taskRepository().getById(id)) {
            is Result.Success -> { /* Task exists */ }
            is Result.Error -> return errorResponse(
                "Task not found",
                ErrorCodes.RESOURCE_NOT_FOUND,
                "No task exists with ID $id"
            )
        }

        // Check for dependencies
        val dependencies = context.dependencyRepository().findByTaskId(id)
        val incomingDeps = dependencies.filter { it.toTaskId == id }
        val outgoingDeps = dependencies.filter { it.fromTaskId == id }

        if (dependencies.isNotEmpty() && !force) {
            // Calculate affected tasks (unique task IDs involved in dependencies)
            val affectedTaskIds = dependencies.flatMap { listOf(it.fromTaskId, it.toTaskId) }.distinct().filter { it != id }

            return errorResponse(
                "Cannot delete task with existing dependencies",
                ErrorCodes.VALIDATION_ERROR,
                "Task has ${dependencies.size} dependencies. Use 'force=true' to delete anyway and break dependency chains.",
                buildJsonObject {
                    put("totalDependencies", dependencies.size)
                    put("incomingDependencies", incomingDeps.size)
                    put("outgoingDependencies", outgoingDeps.size)
                    put("affectedTasks", affectedTaskIds.size)
                }
            )
        }

        // Delete dependencies
        var dependenciesDeleted = 0
        if (dependencies.isNotEmpty()) {
            dependenciesDeleted = context.dependencyRepository().deleteByTaskId(id)
        }

        // Delete sections if requested
        var sectionsDeleted = 0
        if (deleteSections) {
            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.TASK, id)
            if (sectionsResult is Result.Success) {
                sectionsDeleted = sectionsResult.data.size
                sectionsResult.data.forEach { section ->
                    context.sectionRepository().deleteSection(section.id)
                }
            }
        }

        // Delete the task
        return handleRepositoryResult(
            context.taskRepository().delete(id),
            if (dependenciesDeleted > 0) {
                "Task deleted with $dependenciesDeleted dependencies and $sectionsDeleted sections"
            } else {
                "Task deleted successfully"
            }
        ) { _ ->
            buildJsonObject {
                put("id", id.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeleted)
                put("dependenciesDeleted", dependenciesDeleted)
                if (dependencies.isNotEmpty() && force) {
                    // Calculate affected tasks (unique task IDs involved in dependencies)
                    val affectedTaskIds = dependencies.flatMap { listOf(it.fromTaskId, it.toTaskId) }.distinct().filter { it != id }

                    put("warningsBrokenDependencies", true)
                    put("brokenDependencyChains", buildJsonObject {
                        put("incomingDependencies", incomingDeps.size)
                        put("outgoingDependencies", outgoingDeps.size)
                        put("affectedTasks", affectedTaskIds.size)
                    })
                }
            }
        }
    }

    // ========== SET STATUS OPERATION ==========

    private suspend fun executeSetStatus(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String,
        id: UUID
    ): JsonElement {
        logger.info("Executing setStatus operation for $containerType: $id")

        val statusStr = requireString(params, "status")

        return when (containerType) {
            "project" -> setProjectStatus(context, id, statusStr)
            "feature" -> setFeatureStatus(context, id, statusStr)
            "task" -> setTaskStatus(context, id, statusStr)
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    private suspend fun setProjectStatus(context: ToolExecutionContext, id: UUID, statusStr: String): JsonElement {
        // Get existing project
        val existingResult = context.projectRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return handleRepositoryResult(existingResult, "Failed to retrieve project") { JsonNull }
        }

        // Build prerequisite context for validation
        val prerequisiteContext = StatusValidator.PrerequisiteContext(
            taskRepository = context.taskRepository(),
            featureRepository = context.featureRepository(),
            projectRepository = context.projectRepository(),
            dependencyRepository = context.dependencyRepository()
        )

        // Validate transition with StatusValidator (including prerequisites)
        val currentStatusStr = existing.status.name.lowercase().replace('_', '-')
        val transitionValidation = statusValidator.validateTransition(
            currentStatusStr,
            statusStr,
            "project",
            id,
            prerequisiteContext,
            existing.tags
        )

        if (transitionValidation is StatusValidator.ValidationResult.Invalid) {
            return errorResponse(
                message = transitionValidation.reason,
                code = ErrorCodes.VALIDATION_ERROR,
                additionalData = buildJsonObject {
                    put("currentStatus", currentStatusStr)
                    put("attemptedStatus", statusStr)
                    if (transitionValidation.suggestions.isNotEmpty()) {
                        put("suggestions", JsonArray(transitionValidation.suggestions.map { JsonPrimitive(it) }))
                    }
                }
            )
        }

        // Parse and update
        val status = parseProjectStatus(statusStr)
        val updated = existing.update(status = status)

        // Prepare success message (include advisory if present)
        val successMessage = if (transitionValidation is StatusValidator.ValidationResult.ValidWithAdvisory) {
            "Project status updated to ${status.name.lowercase().replace('_', '-')}. Advisory: ${transitionValidation.advisory}"
        } else {
            "Project status updated to ${status.name.lowercase().replace('_', '-')}"
        }

        val updateResult = context.projectRepository().update(updated)

        return when (updateResult) {
            is Result.Success -> {
                val updatedProject = updateResult.data

                // Detect cascade events after status change
                val workflowService = createWorkflowService(context)
                val cascadeEvents = workflowService.detectCascadeEvents(
                    updatedProject.id,
                    ContainerType.PROJECT
                )

                successResponse(
                    message = successMessage,
                    data = buildJsonObject {
                        put("id", updatedProject.id.toString())
                        put("status", updatedProject.status.name.lowercase().replace('_', '-'))
                        put("modifiedAt", updatedProject.modifiedAt.toString())
                        if (transitionValidation is StatusValidator.ValidationResult.ValidWithAdvisory) {
                            put("advisory", transitionValidation.advisory)
                        }
                        if (cascadeEvents.isNotEmpty()) {
                            put("cascadeEvents", JsonArray(
                                cascadeEvents.map { ev ->
                                    buildJsonObject {
                                        put("event", ev.event)
                                        put("targetType", ev.targetType.name.lowercase())
                                        put("targetId", ev.targetId.toString())
                                        put("targetName", ev.targetName)
                                        put("currentStatus", ev.currentStatus)
                                        put("suggestedStatus", ev.suggestedStatus)
                                        put("flow", ev.flow)
                                        put("automatic", ev.automatic)
                                        put("reason", ev.reason)
                                    }
                                }
                            ))
                        }
                    }
                )
            }
            is Result.Error -> handleRepositoryResult(updateResult, "Failed to update project") { JsonNull }
        }
    }

    private suspend fun setFeatureStatus(context: ToolExecutionContext, id: UUID, statusStr: String): JsonElement {
        // Get existing feature
        val existingResult = context.featureRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return handleRepositoryResult(existingResult, "Failed to retrieve feature") { JsonNull }
        }

        // Build prerequisite context for validation
        val prerequisiteContext = StatusValidator.PrerequisiteContext(
            taskRepository = context.taskRepository(),
            featureRepository = context.featureRepository(),
            projectRepository = context.projectRepository(),
            dependencyRepository = context.dependencyRepository()
        )

        // Validate transition with StatusValidator (including prerequisites)
        val currentStatusStr = existing.status.name.lowercase().replace('_', '-')
        val transitionValidation = statusValidator.validateTransition(
            currentStatusStr,
            statusStr,
            "feature",
            id,
            prerequisiteContext,
            existing.tags
        )

        if (transitionValidation is StatusValidator.ValidationResult.Invalid) {
            return errorResponse(
                message = transitionValidation.reason,
                code = ErrorCodes.VALIDATION_ERROR,
                additionalData = buildJsonObject {
                    put("currentStatus", currentStatusStr)
                    put("attemptedStatus", statusStr)
                    if (transitionValidation.suggestions.isNotEmpty()) {
                        put("suggestions", JsonArray(transitionValidation.suggestions.map { JsonPrimitive(it) }))
                    }
                }
            )
        }

        // Parse and update
        val status = parseFeatureStatus(statusStr)
        val updated = existing.update(status = status)

        // Prepare success message (include advisory if present)
        val successMessage = if (transitionValidation is StatusValidator.ValidationResult.ValidWithAdvisory) {
            "Feature status updated to ${status.name.lowercase().replace('_', '-')}. Advisory: ${transitionValidation.advisory}"
        } else {
            "Feature status updated to ${status.name.lowercase().replace('_', '-')}"
        }

        val updateResult = context.featureRepository().update(updated)

        return when (updateResult) {
            is Result.Success -> {
                val updatedFeature = updateResult.data

                // Detect cascade events after status change
                val workflowService = createWorkflowService(context)
                val cascadeEvents = workflowService.detectCascadeEvents(
                    updatedFeature.id,
                    ContainerType.FEATURE
                )

                successResponse(
                    message = successMessage,
                    data = buildJsonObject {
                        put("id", updatedFeature.id.toString())
                        put("status", updatedFeature.status.name.lowercase().replace('_', '-'))
                        put("modifiedAt", updatedFeature.modifiedAt.toString())
                        if (transitionValidation is StatusValidator.ValidationResult.ValidWithAdvisory) {
                            put("advisory", transitionValidation.advisory)
                        }
                        if (cascadeEvents.isNotEmpty()) {
                            put("cascadeEvents", JsonArray(
                                cascadeEvents.map { ev ->
                                    buildJsonObject {
                                        put("event", ev.event)
                                        put("targetType", ev.targetType.name.lowercase())
                                        put("targetId", ev.targetId.toString())
                                        put("targetName", ev.targetName)
                                        put("currentStatus", ev.currentStatus)
                                        put("suggestedStatus", ev.suggestedStatus)
                                        put("flow", ev.flow)
                                        put("automatic", ev.automatic)
                                        put("reason", ev.reason)
                                    }
                                }
                            ))
                        }
                    }
                )
            }
            is Result.Error -> handleRepositoryResult(updateResult, "Failed to update feature") { JsonNull }
        }
    }

    private suspend fun setTaskStatus(context: ToolExecutionContext, id: UUID, statusStr: String): JsonElement {
        // Get existing task
        val existingResult = context.taskRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return handleRepositoryResult(existingResult, "Failed to retrieve task") { JsonNull }
        }

        // Build prerequisite context for validation
        val prerequisiteContext = StatusValidator.PrerequisiteContext(
            taskRepository = context.taskRepository(),
            featureRepository = context.featureRepository(),
            projectRepository = context.projectRepository(),
            dependencyRepository = context.dependencyRepository()
        )

        // Validate transition with StatusValidator (including prerequisites)
        val currentStatusStr = existing.status.name.lowercase().replace('_', '-')
        val transitionValidation = statusValidator.validateTransition(
            currentStatusStr,
            statusStr,
            "task",
            id,
            prerequisiteContext,
            existing.tags
        )

        if (transitionValidation is StatusValidator.ValidationResult.Invalid) {
            return errorResponse(
                message = transitionValidation.reason,
                code = ErrorCodes.VALIDATION_ERROR,
                additionalData = buildJsonObject {
                    put("currentStatus", currentStatusStr)
                    put("attemptedStatus", statusStr)
                    if (transitionValidation.suggestions.isNotEmpty()) {
                        put("suggestions", JsonArray(transitionValidation.suggestions.map { JsonPrimitive(it) }))
                    }
                }
            )
        }

        // Parse and update
        val status = parseTaskStatus(statusStr)
        val updated = existing.copy(status = status, modifiedAt = Instant.now())

        // Prepare success message (include advisory if present)
        val successMessage = if (transitionValidation is StatusValidator.ValidationResult.ValidWithAdvisory) {
            "Task status updated to ${status.name.lowercase().replace('_', '-')}. Advisory: ${transitionValidation.advisory}"
        } else {
            "Task status updated to ${status.name.lowercase().replace('_', '-')}"
        }

        val updateResult = context.taskRepository().update(updated)

        return when (updateResult) {
            is Result.Success -> {
                val updatedTask = updateResult.data

                // Detect cascade events after status change
                val workflowService = createWorkflowService(context)
                val cascadeEvents = workflowService.detectCascadeEvents(
                    updatedTask.id,
                    ContainerType.TASK
                )

                successResponse(
                    message = successMessage,
                    data = buildJsonObject {
                        put("id", updatedTask.id.toString())
                        put("status", updatedTask.status.name.lowercase().replace('_', '-'))
                        put("modifiedAt", updatedTask.modifiedAt.toString())
                        if (transitionValidation is StatusValidator.ValidationResult.ValidWithAdvisory) {
                            put("advisory", transitionValidation.advisory)
                        }
                        if (cascadeEvents.isNotEmpty()) {
                            put("cascadeEvents", JsonArray(
                                cascadeEvents.map { ev ->
                                    buildJsonObject {
                                        put("event", ev.event)
                                        put("targetType", ev.targetType.name.lowercase())
                                        put("targetId", ev.targetId.toString())
                                        put("targetName", ev.targetName)
                                        put("currentStatus", ev.currentStatus)
                                        put("suggestedStatus", ev.suggestedStatus)
                                        put("flow", ev.flow)
                                        put("automatic", ev.automatic)
                                        put("reason", ev.reason)
                                    }
                                }
                            ))
                        }
                    }
                )
            }
            is Result.Error -> handleRepositoryResult(updateResult, "Failed to update task") { JsonNull }
        }
    }

    // ========== BULK UPDATE OPERATION ==========

    private suspend fun executeBulkUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String
    ): JsonElement {
        logger.info("Executing bulkUpdate operation for $containerType")

        val containersArray = params.jsonObject["containers"] as JsonArray

        val successfulContainers = mutableListOf<JsonObject>()
        val failedContainers = mutableListOf<JsonObject>()

        containersArray.forEachIndexed { index, containerElement ->
            val containerParams = containerElement.jsonObject
            val idStr = containerParams["id"]?.jsonPrimitive?.content ?: return@forEachIndexed
            val id = UUID.fromString(idStr)

            try {
                val result = when (containerType) {
                    "project" -> updateProjectBulk(containerParams, context, id)
                    "feature" -> updateFeatureBulk(containerParams, context, id)
                    "task" -> updateTaskBulk(containerParams, context, id)
                    else -> null
                }

                when (result) {
                    is Result.Success -> {
                        successfulContainers.add(buildJsonObject {
                            put("id", id.toString())
                            put("modifiedAt", Instant.now().toString())
                        })
                    }
                    is Result.Error -> {
                        failedContainers.add(buildJsonObject {
                            put("index", index)
                            put("id", idStr)
                            put("error", buildJsonObject {
                                put("code", when (result.error) {
                                    is RepositoryError.ValidationError -> ErrorCodes.VALIDATION_ERROR
                                    is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                    else -> ErrorCodes.DATABASE_ERROR
                                })
                                put("details", result.error.toString())
                            })
                        })
                    }
                    null -> {
                        failedContainers.add(buildJsonObject {
                            put("index", index)
                            put("id", idStr)
                            put("error", buildJsonObject {
                                put("code", ErrorCodes.INTERNAL_ERROR)
                                put("details", "Unknown error during bulk update")
                            })
                        })
                    }
                }
            } catch (e: Exception) {
                logger.error("Error updating container $id in bulk update", e)
                failedContainers.add(buildJsonObject {
                    put("index", index)
                    put("id", idStr)
                    put("error", buildJsonObject {
                        put("code", ErrorCodes.INTERNAL_ERROR)
                        put("details", e.message ?: "Unknown error")
                    })
                })
            }
        }

        val totalRequested = containersArray.size
        val successCount = successfulContainers.size
        val failedCount = failedContainers.size

        return if (failedCount == 0) {
            successResponse(
                buildJsonObject {
                    put("items", JsonArray(successfulContainers))
                    put("updated", successCount)
                    put("failed", 0)
                },
                "$successCount ${containerType}s updated successfully"
            )
        } else if (successCount == 0) {
            errorResponse(
                "Failed to update any ${containerType}s",
                ErrorCodes.OPERATION_FAILED,
                "All $totalRequested ${containerType}s failed to update",
                buildJsonObject {
                    put("failures", JsonArray(failedContainers))
                }
            )
        } else {
            successResponse(
                buildJsonObject {
                    put("items", JsonArray(successfulContainers))
                    put("updated", successCount)
                    put("failed", failedCount)
                    put("failures", JsonArray(failedContainers))
                },
                "$successCount ${containerType}s updated, $failedCount failed"
            )
        }
    }

    private suspend fun updateProjectBulk(
        containerParams: JsonObject,
        context: ToolExecutionContext,
        id: UUID
    ): Result<Project> {
        val existingResult = context.projectRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return existingResult
        }

        val name = containerParams["name"]?.jsonPrimitive?.content ?: existing.name
        val description = containerParams["description"]?.jsonPrimitive?.content ?: existing.description
        val summary = containerParams["summary"]?.jsonPrimitive?.content ?: existing.summary
        val statusStr = containerParams["status"]?.jsonPrimitive?.content
        val status = if (statusStr != null) parseProjectStatus(statusStr) else existing.status
        val tags = containerParams["tags"]?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: existing.tags

        val updated = existing.update(
            name = name,
            description = description,
            summary = summary,
            status = status,
            tags = tags
        )

        return context.projectRepository().update(updated)
    }

    private suspend fun updateFeatureBulk(
        containerParams: JsonObject,
        context: ToolExecutionContext,
        id: UUID
    ): Result<Feature> {
        val existingResult = context.featureRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return existingResult
        }

        val name = containerParams["name"]?.jsonPrimitive?.content ?: existing.name
        val description = containerParams["description"]?.jsonPrimitive?.content ?: existing.description
        val summary = containerParams["summary"]?.jsonPrimitive?.content ?: existing.summary
        val statusStr = containerParams["status"]?.jsonPrimitive?.content
        val status = if (statusStr != null) parseFeatureStatus(statusStr) else existing.status
        val priorityStr = containerParams["priority"]?.jsonPrimitive?.content
        val priority = if (priorityStr != null) parsePriority(priorityStr) else existing.priority
        val projectId = containerParams["projectId"]?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        } ?: existing.projectId
        val tags = containerParams["tags"]?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: existing.tags

        val updated = existing.update(
            name = name,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            projectId = projectId,
            tags = tags
        )

        return context.featureRepository().update(updated)
    }

    private suspend fun updateTaskBulk(
        containerParams: JsonObject,
        context: ToolExecutionContext,
        id: UUID
    ): Result<Task> {
        val existingResult = context.taskRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return existingResult
        }

        val title = containerParams["title"]?.jsonPrimitive?.content
            ?: containerParams["name"]?.jsonPrimitive?.content
            ?: existing.title
        val description = containerParams["description"]?.jsonPrimitive?.content ?: existing.description
        val summary = containerParams["summary"]?.jsonPrimitive?.content ?: existing.summary
        val statusStr = containerParams["status"]?.jsonPrimitive?.content
        val status = if (statusStr != null) parseTaskStatus(statusStr) else existing.status
        val priorityStr = containerParams["priority"]?.jsonPrimitive?.content
        val priority = if (priorityStr != null) parsePriority(priorityStr) else existing.priority
        val complexity = containerParams["complexity"]?.let {
            if (it is JsonPrimitive && it.isString) it.content.toIntOrNull() else it.jsonPrimitive.intOrNull
        } ?: existing.complexity
        val featureId = containerParams["featureId"]?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        } ?: existing.featureId
        val tags = containerParams["tags"]?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: existing.tags

        val updated = existing.copy(
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

        return context.taskRepository().update(updated)
    }

    // ========== HELPER METHODS ==========

    private fun getEntityType(containerType: String): EntityType {
        return when (containerType) {
            "project" -> EntityType.PROJECT
            "feature" -> EntityType.FEATURE
            "task" -> EntityType.TASK
            else -> throw IllegalArgumentException("Invalid container type: $containerType")
        }
    }

    private fun parseTags(params: JsonElement): List<String> {
        return optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: emptyList()
    }

    private fun extractTemplateIds(params: JsonElement): List<UUID> {
        val paramsObj = params as? JsonObject ?: return emptyList()
        val templateIdsArray = paramsObj["templateIds"] as? JsonArray ?: return emptyList()

        return templateIdsArray.mapNotNull { item ->
            if (item is JsonPrimitive && item.isString) {
                UUID.fromString(item.content)
            } else null
        }
    }

    // Status parsing methods
    // Note: Status validation is handled by StatusValidator in validateParams()

    /**
     * Parses a project status string using the enum's fromString() method.
     * This delegates to ProjectStatus.fromString(), which automatically supports
     * all current and future status values defined in the enum.
     *
     * Note: Validation should occur in validateParams() using StatusValidator.
     * This method should only be called after validation passes.
     */
    private fun parseProjectStatus(status: String): ProjectStatus {
        return ProjectStatus.fromString(status) ?: run {
            val allowedStatuses = statusValidator.getAllowedStatuses("project")
            throw IllegalArgumentException(
                "Invalid project status '$status'. Allowed statuses: ${allowedStatuses.joinToString(", ")}"
            )
        }
    }

    /**
     * Parses a feature status string using the enum's fromString() method.
     * This delegates to FeatureStatus.fromString(), which automatically supports
     * all current and future status values defined in the enum.
     *
     * Note: Validation should occur in validateParams() using StatusValidator.
     * This method should only be called after validation passes.
     */
    private fun parseFeatureStatus(status: String): FeatureStatus {
        return FeatureStatus.fromString(status) ?: run {
            val allowedStatuses = statusValidator.getAllowedStatuses("feature")
            throw IllegalArgumentException(
                "Invalid feature status '$status'. Allowed statuses: ${allowedStatuses.joinToString(", ")}"
            )
        }
    }

    /**
     * Parses a task status string using the enum's fromString() method.
     * This delegates to TaskStatus.fromString(), which automatically supports
     * all current and future status values defined in the enum.
     *
     * Note: Validation should occur in validateParams() using StatusValidator.
     * This method should only be called after validation passes.
     */
    private fun parseTaskStatus(status: String): TaskStatus {
        return TaskStatus.fromString(status) ?: run {
            val allowedStatuses = statusValidator.getAllowedStatuses("task")
            throw IllegalArgumentException(
                "Invalid task status '$status'. Allowed statuses: ${allowedStatuses.joinToString(", ")}"
            )
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
