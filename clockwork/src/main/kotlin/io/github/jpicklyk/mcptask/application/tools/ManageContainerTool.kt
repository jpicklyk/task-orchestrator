package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.service.CompletionCleanupService
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.VerificationGateService
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Consolidated MCP tool for write operations on all container types (project, feature, task).
 * Includes 3 operations: create, update, delete. All operations use array parameters (containers for create/update, ids for delete).
 *
 * This tool unifies container management across all entity types, reducing token overhead
 * and providing a consistent interface for AI agents.
 */
class ManageContainerTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {

    private data class BulkCreateResult(
        val id: UUID?,
        val name: String?,
        val status: String?,
        val appliedTemplates: List<JsonObject> = emptyList(),
        val error: String? = null,
        val errorCode: String? = null
    )

    // StatusValidator without service — used for status string validation in validateParams()
    // and parse*Status() methods which don't need role-aware dependency checks.
    private val statusValidator = StatusValidator()

    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val toolAnnotations: ToolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = false,
        openWorldHint = false
    )

    override val name: String = "manage_container"

    override val title: String = "Manage Container"

    override val outputSchema: ToolSchema = ToolSchema(
        buildJsonObject {
            put("type", "object")
            put("description", "Data payload shape varies by operation. See tool description for per-operation response details.")
        }
    )

    override fun shouldUseLocking(): Boolean = true

    override val description: String = """Unified write operations for containers (project, feature, task).

Operations: create, update, delete — all operations use array parameters for batch support (max 100 items per call).

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | create, update, delete |
| containerType | enum | Yes | project, feature, task |
| containers | array | Varies | Array of container objects (required for: create, update). Max 100 items. |
| ids | array | Varies | Array of container UUIDs (required for: delete). Max 100 items. |
| projectId | UUID | No | Default parent project ID inherited by all items (create only) |
| featureId | UUID | No | Default parent feature ID inherited by all items (create only, task containers) |
| templateIds | array | No | Default template UUIDs inherited by all items (create only) |
| tags | string | No | Default comma-separated tags inherited by all items (create only) |
| deleteSections | boolean | No | Delete sections when deleting (default: true, delete only) |
| force | boolean | No | Force delete with dependencies (default: false, delete only) |

Create operation:
- containers array item: { name/title (required), description, summary, status, priority, complexity, projectId, featureId, templateIds, requiresVerification, tags }
- Top-level projectId, featureId, templateIds, and tags serve as shared defaults for all items
- Per-item fields override shared defaults

Update operation:
- containers array item: { id (required), name/title, description, summary, status, priority, complexity, projectId, featureId, requiresVerification, tags }
- Partial updates supported — only provide fields to change
- Status changes trigger validation automatically

Delete operation:
- ids array: UUIDs of containers to delete
- deleteSections: whether to delete associated sections (default: true)
- force: bypass dependency checks (default: false)

Response shapes:
- Create: { items: [{id, title/name, status, appliedTemplates}], created: N, failed: N, failures: [...] }
- Update: { items: [{id, modifiedAt}], updated: N, failed: N, failures: [...], cascadeEvents: [...], unblockedTasks: [...] }
- Delete: { ids: [...], deleted: N, failed: N, failures: [...] }

All batch operations return success: true even when all items fail (check data.failed count).

Usage: Consolidates create/update/delete for all container types with batch support. Prefer request_transition for workflow-based status changes with cascade detection.
Related: query_container, get_next_task, get_blocked_tasks, request_transition
Docs: task-orchestrator://docs/tools/manage-container
"""

    override val parameterSchema: ToolSchema = ToolSchema(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(listOf("create", "update", "delete").map { JsonPrimitive(it) })
                    )
                ),
                "containerType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of container"),
                        "enum" to JsonArray(listOf("project", "feature", "task").map { JsonPrimitive(it) })
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Default parent project ID inherited by all items (create only)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Default parent feature ID inherited by all items (create only, task containers)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Default comma-separated tags inherited by all items (create only)")
                    )
                ),
                "templateIds" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Default template IDs inherited by all items (create only)"),
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
                        "description" to JsonPrimitive("Array of container objects for create/update (max 100)"),
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
                                        "projectId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                        "featureId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                        "templateIds" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))))),
                                        "requiresVerification" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                )
                            )
                        )
                    )
                ),
                "ids" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Array of container IDs for delete (max 100)"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid")))
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
        if (operation !in listOf("create", "update", "delete")) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: create, update, delete")
        }

        // Validate container type
        if (containerType !in listOf("project", "feature", "task")) {
            throw ToolValidationException("Invalid containerType: $containerType. Must be one of: project, feature, task")
        }

        when (operation) {
            "create" -> validateBatchCreateParams(params, containerType)
            "update" -> validateBatchUpdateParams(params, containerType)
            "delete" -> validateBatchDeleteParams(params)
        }

        // Validate shared default fields (create only)
        if (operation == "create") {
            validateSharedDefaults(params, containerType)
        }
    }

    private fun validateBatchCreateParams(params: JsonElement, containerType: String) {
        val containersArray = params.jsonObject["containers"]
            ?: throw ToolValidationException("Missing required parameter for create: containers")

        if (containersArray !is JsonArray) {
            throw ToolValidationException("Parameter 'containers' must be an array")
        }

        if (containersArray.isEmpty()) {
            throw ToolValidationException("At least one container must be provided for create")
        }

        if (containersArray.size > 100) {
            throw ToolValidationException("Maximum 100 containers allowed per call (got ${containersArray.size})")
        }

        containersArray.forEachIndexed { index, containerElement ->
            if (containerElement !is JsonObject) {
                throw ToolValidationException("Container at index $index must be an object")
            }

            val containerObj = containerElement.jsonObject

            // Validate name/title is present
            val name = containerObj["name"]?.jsonPrimitive?.content
                ?: containerObj["title"]?.jsonPrimitive?.content
            if (name.isNullOrBlank()) {
                throw ToolValidationException("Container at index $index missing required field: ${if (containerType == "task") "title" else "name"}")
            }

            // Validate status if present (config-aware via StatusValidator)
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

            // Validate per-item UUID fields
            containerObj["projectId"]?.jsonPrimitive?.content?.let { projectId ->
                try { UUID.fromString(projectId) } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid projectId at index $index: not a valid UUID")
                }
            }

            containerObj["featureId"]?.jsonPrimitive?.content?.let { featureId ->
                try { UUID.fromString(featureId) } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid featureId at index $index: not a valid UUID")
                }
            }

            // Validate per-item templateIds
            containerObj["templateIds"]?.let { templateIdsElement ->
                if (templateIdsElement !is JsonArray) {
                    throw ToolValidationException("templateIds at index $index must be an array of UUIDs")
                }
                templateIdsElement.forEachIndexed { tIdx, item ->
                    if (item !is JsonPrimitive || !item.isString) {
                        throw ToolValidationException("templateIds[$tIdx] at container index $index must be a string UUID")
                    }
                    try { UUID.fromString(item.content) } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("templateIds[$tIdx] at container index $index is not a valid UUID")
                    }
                }
            }
        }
    }

    private fun validateBatchUpdateParams(params: JsonElement, containerType: String) {
        val containersArray = params.jsonObject["containers"]
            ?: throw ToolValidationException("Missing required parameter for update: containers")

        if (containersArray !is JsonArray) {
            throw ToolValidationException("Parameter 'containers' must be an array")
        }

        if (containersArray.isEmpty()) {
            throw ToolValidationException("At least one container must be provided for update")
        }

        if (containersArray.size > 100) {
            throw ToolValidationException("Maximum 100 containers allowed for update (got ${containersArray.size})")
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

    private fun validateBatchDeleteParams(params: JsonElement) {
        val idsArray = params.jsonObject["ids"]
            ?: throw ToolValidationException("Missing required parameter for delete: ids")

        if (idsArray !is JsonArray) {
            throw ToolValidationException("Parameter 'ids' must be an array")
        }

        if (idsArray.isEmpty()) {
            throw ToolValidationException("At least one ID must be provided for delete")
        }

        if (idsArray.size > 100) {
            throw ToolValidationException("Maximum 100 IDs allowed per delete call (got ${idsArray.size})")
        }

        idsArray.forEachIndexed { index, idElement ->
            val idStr = (idElement as? JsonPrimitive)?.content
                ?: throw ToolValidationException("ID at index $index must be a string")
            try {
                UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid UUID at index $index: $idStr")
            }
        }
    }

    private fun validateSharedDefaults(params: JsonElement, containerType: String) {
        // Validate shared projectId default
        optionalString(params, "projectId")?.let { projectId ->
            if (projectId.isNotEmpty()) {
                try { UUID.fromString(projectId) } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid projectId format. Must be a valid UUID")
                }
            }
        }

        // Validate shared featureId default
        optionalString(params, "featureId")?.let { featureId ->
            if (featureId.isNotEmpty()) {
                try { UUID.fromString(featureId) } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid featureId format. Must be a valid UUID")
                }
            }
        }

        // Validate shared templateIds default
        val paramsObj = params as? JsonObject
        paramsObj?.get("templateIds")?.let { templateIdsElement ->
            if (templateIdsElement !is JsonArray) {
                throw ToolValidationException("Parameter 'templateIds' must be an array of strings (UUIDs)")
            }
            templateIdsElement.forEachIndexed { index, item ->
                if (item !is JsonPrimitive || !item.isString) {
                    throw ToolValidationException("templateIds[$index] must be a string (UUID)")
                }
                try { UUID.fromString(item.content) } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("templateIds[$index] is not a valid UUID format")
                }
            }
        }
    }

    /**
     * Runs completion cleanup for a feature transition.
     * The cleanup service internally checks if the status is terminal and if cleanup is enabled.
     */
    private suspend fun runCompletionCleanup(
        featureId: UUID,
        targetStatus: String,
        context: ToolExecutionContext
    ): io.github.jpicklyk.mcptask.application.service.CleanupResult? {
        return try {
            val cleanupService = CompletionCleanupService(
                taskRepository = context.taskRepository(),
                sectionRepository = context.sectionRepository(),
                dependencyRepository = context.dependencyRepository()
            )
            cleanupService.cleanupFeatureTasks(featureId, targetStatus)
        } catch (e: Exception) {
            logger.warn("Failed to run completion cleanup for feature $featureId: ${e.message}")
            null
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing manage_container tool")

        return try {
            val operation = requireString(params, "operation")
            val containerType = requireString(params, "containerType")

            when (operation) {
                "create" -> executeCreate(params, context, containerType)
                "update" -> executeBatchUpdate(params, context, containerType)
                "delete" -> executeBatchDelete(params, context, containerType)
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
        logger.info("Executing batch create operation for $containerType")

        val containersArray = params.jsonObject["containers"] as JsonArray

        // Extract shared defaults from top-level params
        val sharedDefaults = extractSharedDefaults(params)

        // Cache parent validation for shared defaults
        val sharedProjectId = sharedDefaults["projectId"]
        val sharedFeatureId = sharedDefaults["featureId"]

        if (sharedProjectId != null) {
            when (context.repositoryProvider.projectRepository().getById(UUID.fromString(sharedProjectId))) {
                is Result.Error -> return errorResponse(
                    "Shared default project not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    "No project exists with ID $sharedProjectId"
                )
                is Result.Success -> { /* exists */ }
            }
        }

        if (sharedFeatureId != null && containerType == "task") {
            when (context.repositoryProvider.featureRepository().getById(UUID.fromString(sharedFeatureId))) {
                is Result.Error -> return errorResponse(
                    "Shared default feature not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    "No feature exists with ID $sharedFeatureId"
                )
                is Result.Success -> { /* exists */ }
            }
        }

        val successItems = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()

        // Track validated parent IDs to avoid redundant lookups
        val validatedProjectIds = mutableSetOf<UUID>()
        val validatedFeatureIds = mutableSetOf<UUID>()
        if (sharedProjectId != null) validatedProjectIds.add(UUID.fromString(sharedProjectId))
        if (sharedFeatureId != null) validatedFeatureIds.add(UUID.fromString(sharedFeatureId))

        containersArray.forEachIndexed { index, containerElement ->
            val containerObj = containerElement.jsonObject

            try {
                // Merge per-item fields with shared defaults
                val mergedParams = mergeWithDefaults(containerObj, sharedDefaults)

                val result = when (containerType) {
                    "project" -> createProjectFromBatch(mergedParams, context)
                    "feature" -> createFeatureFromBatch(mergedParams, context, validatedProjectIds)
                    "task" -> createTaskFromBatch(mergedParams, context, validatedProjectIds, validatedFeatureIds)
                    else -> BulkCreateResult(null, null, null, error = "Unsupported container type: $containerType", errorCode = ErrorCodes.VALIDATION_ERROR)
                }

                if (result.error != null) {
                    failures.add(buildJsonObject {
                        put("index", index)
                        result.name?.let { put("name", it) }
                        put("error", buildJsonObject {
                            put("code", result.errorCode ?: ErrorCodes.INTERNAL_ERROR)
                            put("message", result.error)
                        })
                    })
                } else {
                    successItems.add(buildJsonObject {
                        put("id", result.id.toString())
                        if (containerType == "task") {
                            put("title", result.name ?: "")
                        } else {
                            put("name", result.name ?: "")
                        }
                        put("status", result.status ?: "pending")
                        if (result.appliedTemplates.isNotEmpty()) {
                            put("appliedTemplates", JsonArray(result.appliedTemplates))
                        }
                    })
                }
            } catch (e: Exception) {
                failures.add(buildJsonObject {
                    put("index", index)
                    put("error", buildJsonObject {
                        put("code", ErrorCodes.INTERNAL_ERROR)
                        put("message", e.message ?: "Unknown error")
                    })
                })
            }
        }

        val message = if (successItems.isNotEmpty()) {
            "Created ${successItems.size} ${containerType}(s)${if (failures.isNotEmpty()) " (${failures.size} failed)" else ""}"
        } else {
            "All ${failures.size} ${containerType}(s) failed to create"
        }

        // Check if any created items had templates applied
        val anyTemplatesApplied = successItems.any { item ->
            val templates = item["appliedTemplates"]
            templates != null && templates is JsonArray && templates.isNotEmpty()
        }
        val showTemplateWarning = successItems.isNotEmpty() && !anyTemplatesApplied &&
            containerType in listOf("task", "feature")

        return successResponse(
            buildJsonObject {
                put("items", JsonArray(successItems))
                put("created", successItems.size)
                put("failed", failures.size)
                if (failures.isNotEmpty()) {
                    put("failures", JsonArray(failures))
                }
                if (showTemplateWarning) {
                    put("warning", "No templates applied. Use query_templates to discover " +
                        "templates for structured workflows, then include templateIds in the create call.")
                }
            },
            message
        )
    }

    private suspend fun createProjectFromBatch(
        mergedParams: JsonObject,
        context: ToolExecutionContext
    ): BulkCreateResult {
        val name = mergedParams["name"]?.jsonPrimitive?.content
            ?: mergedParams["title"]?.jsonPrimitive?.content ?: ""
        val description = mergedParams["description"]?.jsonPrimitive?.content
        val summary = mergedParams["summary"]?.jsonPrimitive?.content ?: ""
        val statusStr = mergedParams["status"]?.jsonPrimitive?.content ?: "planning"
        val status = parseProjectStatus(statusStr)
        val tags = mergedParams["tags"]?.jsonPrimitive?.content?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val project = Project(
            name = name,
            description = description,
            summary = summary,
            status = status,
            tags = tags
        )

        return when (val result = context.projectRepository().create(project)) {
            is Result.Success -> BulkCreateResult(
                id = result.data.id,
                name = result.data.name,
                status = result.data.status.name.lowercase().replace('_', '-')
            )
            is Result.Error -> BulkCreateResult(
                id = null,
                name = name,
                status = null,
                error = "Failed to create project: ${result.error}",
                errorCode = ErrorCodes.DATABASE_ERROR
            )
        }
    }

    private suspend fun createFeatureFromBatch(
        mergedParams: JsonObject,
        context: ToolExecutionContext,
        validatedProjectIds: MutableSet<UUID>
    ): BulkCreateResult {
        val name = mergedParams["name"]?.jsonPrimitive?.content
            ?: mergedParams["title"]?.jsonPrimitive?.content ?: ""
        val description = mergedParams["description"]?.jsonPrimitive?.content
        val summary = mergedParams["summary"]?.jsonPrimitive?.content ?: ""
        val statusStr = mergedParams["status"]?.jsonPrimitive?.content ?: "planning"
        val status = parseFeatureStatus(statusStr)
        val priorityStr = mergedParams["priority"]?.jsonPrimitive?.content ?: "medium"
        val priority = parsePriority(priorityStr)
        val projectId = mergedParams["projectId"]?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val tags = mergedParams["tags"]?.jsonPrimitive?.content?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val requiresVerification = mergedParams["requiresVerification"]?.jsonPrimitive?.booleanOrNull ?: false

        // Validate project exists (with caching)
        if (projectId != null && projectId !in validatedProjectIds) {
            when (context.repositoryProvider.projectRepository().getById(projectId)) {
                is Result.Error -> return BulkCreateResult(
                    id = null, name = name, status = null,
                    error = "Project not found: $projectId",
                    errorCode = ErrorCodes.RESOURCE_NOT_FOUND
                )
                is Result.Success -> validatedProjectIds.add(projectId)
            }
        }

        val feature = Feature(
            name = name,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            projectId = projectId,
            requiresVerification = requiresVerification,
            tags = tags
        )

        return when (val result = context.featureRepository().create(feature)) {
            is Result.Success -> {
                val createdFeature = result.data

                // Apply templates
                val templateIds = extractTemplateIdsFromObject(mergedParams)
                val appliedTemplates = if (templateIds.isNotEmpty()) {
                    applyTemplatesAndBuildResponse(templateIds, EntityType.FEATURE, createdFeature.id, context)
                } else emptyList()

                BulkCreateResult(
                    id = createdFeature.id,
                    name = createdFeature.name,
                    status = createdFeature.status.name.lowercase().replace('_', '-'),
                    appliedTemplates = appliedTemplates
                )
            }
            is Result.Error -> BulkCreateResult(
                id = null, name = name, status = null,
                error = "Failed to create feature: ${result.error}",
                errorCode = ErrorCodes.DATABASE_ERROR
            )
        }
    }

    private suspend fun createTaskFromBatch(
        mergedParams: JsonObject,
        context: ToolExecutionContext,
        validatedProjectIds: MutableSet<UUID>,
        validatedFeatureIds: MutableSet<UUID>
    ): BulkCreateResult {
        val title = mergedParams["title"]?.jsonPrimitive?.content
            ?: mergedParams["name"]?.jsonPrimitive?.content ?: ""
        val description = mergedParams["description"]?.jsonPrimitive?.content
        val summary = mergedParams["summary"]?.jsonPrimitive?.content ?: ""
        val statusStr = mergedParams["status"]?.jsonPrimitive?.content ?: "pending"
        val status = parseTaskStatus(statusStr)
        val priorityStr = mergedParams["priority"]?.jsonPrimitive?.content ?: "medium"
        val priority = parsePriority(priorityStr)
        val complexity = mergedParams["complexity"]?.let {
            if (it is JsonPrimitive && it.isString) it.content.toIntOrNull() else it.jsonPrimitive.intOrNull
        } ?: 5
        val projectId = mergedParams["projectId"]?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val featureId = mergedParams["featureId"]?.jsonPrimitive?.content?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val tags = mergedParams["tags"]?.jsonPrimitive?.content?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val requiresVerification = mergedParams["requiresVerification"]?.jsonPrimitive?.booleanOrNull ?: false

        // Validate parent entities (with caching)
        if (projectId != null && projectId !in validatedProjectIds) {
            when (context.repositoryProvider.projectRepository().getById(projectId)) {
                is Result.Error -> return BulkCreateResult(
                    id = null, name = title, status = null,
                    error = "Project not found: $projectId",
                    errorCode = ErrorCodes.RESOURCE_NOT_FOUND
                )
                is Result.Success -> validatedProjectIds.add(projectId)
            }
        }

        if (featureId != null && featureId !in validatedFeatureIds) {
            when (context.repositoryProvider.featureRepository().getById(featureId)) {
                is Result.Error -> return BulkCreateResult(
                    id = null, name = title, status = null,
                    error = "Feature not found: $featureId",
                    errorCode = ErrorCodes.RESOURCE_NOT_FOUND
                )
                is Result.Success -> validatedFeatureIds.add(featureId)
            }
        }

        val task = Task(
            title = title,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            complexity = complexity,
            projectId = projectId,
            featureId = featureId,
            requiresVerification = requiresVerification,
            tags = tags
        )

        return when (val result = context.taskRepository().create(task)) {
            is Result.Success -> {
                val createdTask = result.data

                // Apply templates
                val templateIds = extractTemplateIdsFromObject(mergedParams)
                val appliedTemplates = if (templateIds.isNotEmpty()) {
                    applyTemplatesAndBuildResponse(templateIds, EntityType.TASK, createdTask.id, context)
                } else emptyList()

                BulkCreateResult(
                    id = createdTask.id,
                    name = createdTask.title,
                    status = createdTask.status.name.lowercase().replace('_', '-'),
                    appliedTemplates = appliedTemplates
                )
            }
            is Result.Error -> BulkCreateResult(
                id = null, name = title, status = null,
                error = "Failed to create task: ${result.error}",
                errorCode = ErrorCodes.DATABASE_ERROR
            )
        }
    }

    // ========== UPDATE OPERATION ==========
    // Note: Update now always uses batch path (executeBatchUpdate) even for single items

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

        // When force=true, cascade-delete all child entities in FK-safe order
        var taskDependenciesDeleted = 0
        var taskSectionsDeleted = 0
        var tasksDeletedCount = 0
        var featureSectionsDeleted = 0
        var featuresDeletedCount = 0

        if (tasks.isNotEmpty() || features.isNotEmpty()) {
            // 1. Delete all tasks first (dependencies → sections → task)
            for (task in tasks) {
                taskDependenciesDeleted += context.dependencyRepository().deleteByTaskId(task.id)

                // Always delete task sections during cascade (child is being removed entirely)
                val taskSections = context.sectionRepository().getSectionsForEntity(EntityType.TASK, task.id)
                if (taskSections is Result.Success) {
                    taskSectionsDeleted += taskSections.data.size
                    taskSections.data.forEach { section ->
                        context.sectionRepository().deleteSection(section.id)
                    }
                }

                when (context.taskRepository().delete(task.id)) {
                    is Result.Success -> tasksDeletedCount++
                    is Result.Error -> { /* task deletion failed, continue with remaining */ }
                }
            }

            // 2. Delete all features (feature sections → feature)
            for (feature in features) {
                // Always delete feature sections during cascade (child is being removed entirely)
                val featureSections = context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, feature.id)
                if (featureSections is Result.Success) {
                    featureSectionsDeleted += featureSections.data.size
                    featureSections.data.forEach { section ->
                        context.sectionRepository().deleteSection(section.id)
                    }
                }

                when (context.featureRepository().delete(feature.id)) {
                    is Result.Success -> featuresDeletedCount++
                    is Result.Error -> { /* feature deletion failed, continue with remaining */ }
                }
            }
        }

        // Delete project's own sections if requested
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
            if (tasksDeletedCount > 0 || featuresDeletedCount > 0) {
                "Project deleted with $featuresDeletedCount features, $tasksDeletedCount tasks, $taskSectionsDeleted task sections, $featureSectionsDeleted feature sections, and $taskDependenciesDeleted task dependencies"
            } else {
                "Project deleted successfully"
            }
        ) { _ ->
            buildJsonObject {
                put("id", id.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeleted)
                put("featuresDeleted", featuresDeletedCount)
                put("featureSectionsDeleted", featureSectionsDeleted)
                put("tasksDeleted", tasksDeletedCount)
                put("taskSectionsDeleted", taskSectionsDeleted)
                put("taskDependenciesDeleted", taskDependenciesDeleted)
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

        // When force=true and tasks exist, cascade-delete child tasks
        var taskDependenciesDeleted = 0
        var taskSectionsDeleted = 0
        var tasksDeleted = 0

        if (tasks.isNotEmpty()) {
            for (task in tasks) {
                // 1. Delete task dependencies first
                taskDependenciesDeleted += context.dependencyRepository().deleteByTaskId(task.id)

                // 2. Always delete task sections during cascade (child is being removed entirely)
                val taskSections = context.sectionRepository().getSectionsForEntity(EntityType.TASK, task.id)
                if (taskSections is Result.Success) {
                    taskSectionsDeleted += taskSections.data.size
                    taskSections.data.forEach { section ->
                        context.sectionRepository().deleteSection(section.id)
                    }
                }

                // 3. Delete the task
                when (context.taskRepository().delete(task.id)) {
                    is Result.Success -> tasksDeleted++
                    is Result.Error -> { /* task deletion failed, continue with remaining */ }
                }
            }
        }

        // Delete feature's own sections if requested
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
            if (tasksDeleted > 0) {
                "Feature deleted with $tasksDeleted tasks, $taskSectionsDeleted task sections, and $taskDependenciesDeleted task dependencies"
            } else {
                "Feature deleted successfully"
            }
        ) { _ ->
            buildJsonObject {
                put("id", id.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeleted)
                put("tasksDeleted", tasksDeleted)
                put("taskSectionsDeleted", taskSectionsDeleted)
                put("taskDependenciesDeleted", taskDependenciesDeleted)
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


    // ========== DELETE OPERATION ==========

    private suspend fun executeBatchDelete(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String
    ): JsonElement {
        logger.info("Executing batch delete operation for $containerType")

        val idsArray = params.jsonObject["ids"] as JsonArray
        val force = optionalBoolean(params, "force", false)
        val deleteSections = optionalBoolean(params, "deleteSections", true)

        val deletedIds = mutableListOf<String>()
        val failures = mutableListOf<JsonObject>()

        idsArray.forEachIndexed { index, idElement ->
            val idStr = idElement.jsonPrimitive.content
            try {
                val id = UUID.fromString(idStr)
                val result = when (containerType) {
                    "project" -> deleteProject(context, id, force, deleteSections)
                    "feature" -> deleteFeature(context, id, force, deleteSections)
                    "task" -> deleteTask(context, id, force, deleteSections)
                    else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
                }

                // Check if the result indicates success
                val resultObj = result as? JsonObject
                val success = resultObj?.get("success")?.jsonPrimitive?.booleanOrNull ?: false
                if (success) {
                    deletedIds.add(idStr)
                } else {
                    val errorMsg = resultObj?.get("error")?.let { err ->
                        if (err is JsonObject) err["message"]?.jsonPrimitive?.content
                        else err.jsonPrimitive.content
                    } ?: "Delete failed"
                    failures.add(buildJsonObject {
                        put("index", index)
                        put("id", idStr)
                        put("error", buildJsonObject {
                            put("code", ErrorCodes.OPERATION_FAILED)
                            put("message", errorMsg)
                        })
                    })
                }
            } catch (e: Exception) {
                logger.error("Error deleting $containerType $idStr in batch delete", e)
                failures.add(buildJsonObject {
                    put("index", index)
                    put("id", idStr)
                    put("error", buildJsonObject {
                        put("code", ErrorCodes.INTERNAL_ERROR)
                        put("message", e.message ?: "Unknown error")
                    })
                })
            }
        }

        val message = if (deletedIds.isNotEmpty()) {
            "Deleted ${deletedIds.size} ${containerType}(s)${if (failures.isNotEmpty()) " (${failures.size} failed)" else ""}"
        } else {
            "All ${idsArray.size} ${containerType}(s) failed to delete"
        }

        return successResponse(
            buildJsonObject {
                put("ids", JsonArray(deletedIds.map { JsonPrimitive(it) }))
                put("deleted", deletedIds.size)
                put("failed", failures.size)
                if (failures.isNotEmpty()) {
                    put("failures", JsonArray(failures))
                }
            },
            message
        )
    }

    // ========== BATCH UPDATE OPERATION ==========

    private suspend fun executeBatchUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String
    ): JsonElement {
        logger.info("Executing batchUpdate operation for $containerType")

        val containersArray = params.jsonObject["containers"] as JsonArray

        val successfulContainers = mutableListOf<JsonObject>()
        val failedContainers = mutableListOf<JsonObject>()
        // Track entities that had successful status changes for cascade/unblock detection
        val statusChangedEntities = mutableListOf<Triple<UUID, String, String>>() // id, previousStatus, newStatus

        containersArray.forEachIndexed { index, containerElement ->
            val containerParams = containerElement.jsonObject
            val idStr = containerParams["id"]?.jsonPrimitive?.content ?: return@forEachIndexed
            val id = UUID.fromString(idStr)

            try {
                // NOTE: Per-entity locking is not applied in bulk updates. Transition validation
                // provides the primary safety guarantee. Locking could be added in a future iteration
                // if concurrent bulk updates on overlapping entities become a concern.
                val bulkResult = when (containerType) {
                    "project" -> updateProjectBulk(containerParams, context, id)
                    "feature" -> updateFeatureBulk(containerParams, context, id)
                    "task" -> updateTaskBulk(containerParams, context, id)
                    else -> null
                }

                if (bulkResult == null) {
                    failedContainers.add(buildJsonObject {
                        put("index", index)
                        put("id", idStr)
                        put("error", buildJsonObject {
                            put("code", ErrorCodes.INTERNAL_ERROR)
                            put("details", "Unknown error during bulk update")
                        })
                    })
                    return@forEachIndexed
                }

                when (bulkResult.result) {
                    is Result.Success -> {
                        successfulContainers.add(buildJsonObject {
                            put("id", id.toString())
                            put("modifiedAt", Instant.now().toString())
                        })
                        // Track successful status changes for cascade/unblock detection
                        if (bulkResult.statusChanged && bulkResult.previousStatus != null && bulkResult.newStatus != null) {
                            statusChangedEntities.add(Triple(id, bulkResult.previousStatus, bulkResult.newStatus))
                        }
                    }
                    is Result.Error -> {
                        failedContainers.add(buildJsonObject {
                            put("index", index)
                            put("id", idStr)
                            put("error", buildJsonObject {
                                put("code", when (bulkResult.result.error) {
                                    is RepositoryError.ValidationError -> ErrorCodes.VALIDATION_ERROR
                                    is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                    else -> ErrorCodes.DATABASE_ERROR
                                })
                                put("details", bulkResult.validationError ?: bulkResult.result.error.toString())
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

        // Detect cascade events and unblocked tasks for entities that had status changes
        val allCascadeEvents = mutableListOf<JsonObject>()
        val allUnblockedTasks = mutableListOf<JsonObject>()

        for ((entityId, _, newStatus) in statusChangedEntities) {
            try {
                val cascadeService = context.cascadeService()
                if (cascadeService != null) {
                    // Detect cascades (parent advancement suggestions)
                    try {
                        val ct = ContainerType.valueOf(containerType.uppercase())
                        val events = cascadeService.detectCascadeEvents(entityId, ct)
                        val cascadeEvents = events.map { ev ->
                            buildJsonObject {
                                put("event", ev.event)
                                put("targetType", ev.targetType.name.lowercase())
                                put("targetId", ev.targetId.toString())
                                put("suggestedStatus", ev.suggestedStatus)
                                put("reason", ev.reason)
                                put("applied", false)  // ManageContainerTool never applies cascades
                            }
                        }
                        allCascadeEvents.addAll(cascadeEvents)
                    } catch (e: Exception) {
                        logger.warn("Failed to detect cascade events for entity $entityId: ${e.message}")
                    }

                    // For tasks reaching terminal status, find newly unblocked downstream tasks
                    val normalizedNewStatus = newStatus.lowercase().replace('_', '-')
                    if (containerType == "task" && normalizedNewStatus in listOf("completed", "cancelled")) {
                        try {
                            val unblockedTasks = cascadeService.findNewlyUnblockedTasks(entityId).map { task ->
                                buildJsonObject {
                                    put("taskId", task.taskId.toString())
                                    put("title", task.title)
                                }
                            }
                            allUnblockedTasks.addAll(unblockedTasks)
                        } catch (e: Exception) {
                            logger.warn("Failed to find unblocked tasks for entity $entityId: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to detect cascades/unblocked tasks for entity $entityId: ${e.message}")
            }
        }

        val totalRequested = containersArray.size
        val successCount = successfulContainers.size
        val failedCount = failedContainers.size

        // Build response data with cascade and unblocked info
        val responseData = buildJsonObject {
            put("items", JsonArray(successfulContainers))
            put("updated", successCount)
            put("failed", failedCount)
            if (failedContainers.isNotEmpty()) {
                put("failures", JsonArray(failedContainers))
            }
            if (allCascadeEvents.isNotEmpty()) {
                put("cascadeEvents", JsonArray(allCascadeEvents))
            }
            if (allUnblockedTasks.isNotEmpty()) {
                put("unblockedTasks", JsonArray(allUnblockedTasks))
            }
        }

        val message = buildString {
            if (failedCount == 0) {
                append("$successCount ${containerType}s updated successfully")
            } else if (successCount == 0) {
                append("Failed to update any ${containerType}s")
            } else {
                append("$successCount ${containerType}s updated, $failedCount failed")
            }
            if (allCascadeEvents.isNotEmpty()) append(". ${allCascadeEvents.size} cascade event(s) detected")
            if (allUnblockedTasks.isNotEmpty()) append(". ${allUnblockedTasks.size} task(s) now unblocked")
        }

        return successResponse(responseData, message)
    }



    /**
     * Result wrapper for bulk update methods that includes the previous status
     * when a status change occurred, enabling cascade/unblock detection after update.
     */
    private data class BulkUpdateResult<T>(
        val result: Result<T>,
        val statusChanged: Boolean = false,
        val previousStatus: String? = null,
        val newStatus: String? = null,
        val validationError: String? = null
    )

    private suspend fun updateProjectBulk(
        containerParams: JsonObject,
        context: ToolExecutionContext,
        id: UUID
    ): BulkUpdateResult<Project> {
        val existingResult = context.projectRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return BulkUpdateResult(existingResult)
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

        // Validate status transition when status is being changed
        val currentStatusStr = existing.status.name.lowercase().replace('_', '-')
        if (statusStr != null && statusStr != currentStatusStr) {
            // Create service-aware StatusValidator for role-based dependency checks
            val transitionValidator = StatusValidator(context.statusProgressionService())
            val prerequisiteContext = StatusValidator.PrerequisiteContext(
                taskRepository = context.taskRepository(),
                featureRepository = context.featureRepository(),
                projectRepository = context.projectRepository(),
                dependencyRepository = context.dependencyRepository()
            )
            val transitionValidation = transitionValidator.validateTransition(
                currentStatusStr, statusStr, "project", id, prerequisiteContext, existing.tags
            )
            if (transitionValidation is StatusValidator.ValidationResult.Invalid) {
                return BulkUpdateResult(
                    result = Result.Error(RepositoryError.ValidationError(transitionValidation.reason)),
                    validationError = transitionValidation.reason
                )
            }
        }

        val updated = existing.update(
            name = name,
            description = description,
            summary = summary,
            status = status,
            tags = tags
        )

        val statusChanged = statusStr != null && statusStr != currentStatusStr
        return BulkUpdateResult(
            result = context.projectRepository().update(updated),
            statusChanged = statusChanged,
            previousStatus = if (statusChanged) currentStatusStr else null,
            newStatus = if (statusChanged) statusStr else null
        )
    }

    private suspend fun updateFeatureBulk(
        containerParams: JsonObject,
        context: ToolExecutionContext,
        id: UUID
    ): BulkUpdateResult<Feature> {
        val existingResult = context.featureRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return BulkUpdateResult(existingResult)
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

        val requiresVerification = containerParams["requiresVerification"]?.jsonPrimitive?.boolean
            ?: existing.requiresVerification

        // Validate status transition when status is being changed
        val currentStatusStr = existing.status.name.lowercase().replace('_', '-')
        if (statusStr != null && statusStr != currentStatusStr) {
            // Create service-aware StatusValidator for role-based dependency checks
            val transitionValidator = StatusValidator(context.statusProgressionService())
            val prerequisiteContext = StatusValidator.PrerequisiteContext(
                taskRepository = context.taskRepository(),
                featureRepository = context.featureRepository(),
                projectRepository = context.projectRepository(),
                dependencyRepository = context.dependencyRepository()
            )
            val transitionValidation = transitionValidator.validateTransition(
                currentStatusStr, statusStr, "feature", id, prerequisiteContext, existing.tags
            )
            if (transitionValidation is StatusValidator.ValidationResult.Invalid) {
                return BulkUpdateResult(
                    result = Result.Error(RepositoryError.ValidationError(transitionValidation.reason)),
                    validationError = transitionValidation.reason
                )
            }
        }

        val updated = existing.update(
            name = name,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            projectId = projectId,
            requiresVerification = requiresVerification,
            tags = tags
        )

        val statusChanged = statusStr != null && statusStr != currentStatusStr
        return BulkUpdateResult(
            result = context.featureRepository().update(updated),
            statusChanged = statusChanged,
            previousStatus = if (statusChanged) currentStatusStr else null,
            newStatus = if (statusChanged) statusStr else null
        )
    }

    private suspend fun updateTaskBulk(
        containerParams: JsonObject,
        context: ToolExecutionContext,
        id: UUID
    ): BulkUpdateResult<Task> {
        val existingResult = context.taskRepository().getById(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return BulkUpdateResult(existingResult)
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
        val requiresVerification = containerParams["requiresVerification"]?.jsonPrimitive?.boolean
            ?: existing.requiresVerification

        // Validate status transition when status is being changed
        val currentStatusStr = existing.status.name.lowercase().replace('_', '-')
        if (statusStr != null && statusStr != currentStatusStr) {
            // Create service-aware StatusValidator for role-based dependency checks
            val transitionValidator = StatusValidator(context.statusProgressionService())
            val prerequisiteContext = StatusValidator.PrerequisiteContext(
                taskRepository = context.taskRepository(),
                featureRepository = context.featureRepository(),
                projectRepository = context.projectRepository(),
                dependencyRepository = context.dependencyRepository()
            )
            val transitionValidation = transitionValidator.validateTransition(
                currentStatusStr, statusStr, "task", id, prerequisiteContext, existing.tags
            )
            if (transitionValidation is StatusValidator.ValidationResult.Invalid) {
                return BulkUpdateResult(
                    result = Result.Error(RepositoryError.ValidationError(transitionValidation.reason)),
                    validationError = transitionValidation.reason
                )
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
            requiresVerification = requiresVerification,
            tags = tags,
            modifiedAt = Instant.now()
        )

        val statusChanged = statusStr != null && statusStr != currentStatusStr
        return BulkUpdateResult(
            result = context.taskRepository().update(updated),
            statusChanged = statusChanged,
            previousStatus = if (statusChanged) currentStatusStr else null,
            newStatus = if (statusChanged) statusStr else null
        )
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

    private fun extractSharedDefaults(params: JsonElement): Map<String, String?> {
        val paramsObj = params as? JsonObject ?: return emptyMap()
        return mapOf(
            "projectId" to paramsObj["projectId"]?.jsonPrimitive?.content,
            "featureId" to paramsObj["featureId"]?.jsonPrimitive?.content,
            "tags" to paramsObj["tags"]?.jsonPrimitive?.content
        )
        // templateIds handled separately since it's an array
    }

    private fun mergeWithDefaults(containerObj: JsonObject, sharedDefaults: Map<String, String?>): JsonObject {
        val merged = buildJsonObject {
            // Start with shared defaults
            sharedDefaults.forEach { (key, value) ->
                if (value != null && !containerObj.containsKey(key)) {
                    put(key, value)
                }
            }
            // Copy all per-item fields (these override defaults)
            containerObj.forEach { (key, value) ->
                put(key, value)
            }
        }
        return merged
    }

    private fun extractTemplateIdsFromObject(obj: JsonObject): List<UUID> {
        val templateIdsArray = obj["templateIds"] as? JsonArray ?: return emptyList()
        return templateIdsArray.mapNotNull { item ->
            if (item is JsonPrimitive && item.isString) {
                try { UUID.fromString(item.content) } catch (_: Exception) { null }
            } else null
        }
    }

    private suspend fun applyTemplatesAndBuildResponse(
        templateIds: List<UUID>,
        entityType: EntityType,
        entityId: UUID,
        context: ToolExecutionContext
    ): List<JsonObject> {
        val appliedResult = context.repositoryProvider.templateRepository()
            .applyMultipleTemplates(templateIds, entityType, entityId)

        return if (appliedResult is Result.Success && appliedResult.data.isNotEmpty()) {
            appliedResult.data.map { (templateId, sections) ->
                buildJsonObject {
                    put("templateId", templateId.toString())
                    put("sectionsCreated", sections.size)
                }
            }
        } else emptyList()
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

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return super.userSummary(params, result, true)
        val p = params as? JsonObject ?: return "Container operation completed"
        val operation = p["operation"]?.jsonPrimitive?.content ?: "unknown"
        val containerType = p["containerType"]?.jsonPrimitive?.content ?: "container"
        val data = (result as? JsonObject)?.get("data")?.jsonObject
        return when (operation) {
            "create" -> {
                val created = data?.get("created")?.jsonPrimitive?.intOrNull ?: 0
                val failed = data?.get("failed")?.jsonPrimitive?.intOrNull ?: 0
                if (created == 1 && failed == 0) {
                    val item = data?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
                    val name = item?.get("name")?.jsonPrimitive?.content
                        ?: item?.get("title")?.jsonPrimitive?.content ?: ""
                    val id = item?.get("id")?.jsonPrimitive?.content?.let { shortId(it) } ?: ""
                    "Created $containerType '$name' ($id)"
                } else {
                    "Created $created ${containerType}(s)${if (failed > 0) " ($failed failed)" else ""}"
                }
            }
            "update" -> {
                val updated = data?.get("updated")?.jsonPrimitive?.intOrNull ?: 0
                val failed = data?.get("failed")?.jsonPrimitive?.intOrNull ?: 0
                if (updated == 1 && failed == 0) {
                    val item = data?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
                    val id = item?.get("id")?.jsonPrimitive?.content?.let { shortId(it) } ?: ""
                    "Updated $containerType ($id)"
                } else {
                    "Updated $updated ${containerType}(s)${if (failed > 0) " ($failed failed)" else ""}"
                }
            }
            "delete" -> {
                val deleted = data?.get("deleted")?.jsonPrimitive?.intOrNull ?: 0
                val failed = data?.get("failed")?.jsonPrimitive?.intOrNull ?: 0
                if (deleted == 1 && failed == 0) {
                    val id = data?.get("ids")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.let { shortId(it) } ?: ""
                    "Deleted $containerType ($id)"
                } else {
                    "Deleted $deleted ${containerType}(s)${if (failed > 0) " ($failed failed)" else ""}"
                }
            }
            else -> "Container operation completed"
        }
    }
}
