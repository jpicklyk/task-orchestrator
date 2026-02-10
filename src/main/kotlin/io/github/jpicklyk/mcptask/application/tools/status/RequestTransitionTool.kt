package io.github.jpicklyk.mcptask.application.tools.status

import io.github.jpicklyk.mcptask.application.service.CompletionCleanupService
import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.VerificationGateService
import io.github.jpicklyk.mcptask.application.service.WorkflowConfigLoaderImpl
import io.github.jpicklyk.mcptask.application.service.WorkflowServiceImpl
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool for trigger-based status transitions with validation.
 *
 * This tool provides a higher-level interface for status changes by using named triggers
 * (e.g., "start_work", "complete", "request_review") instead of raw status values.
 * It validates the transition against the current workflow, checks prerequisites,
 * and applies the status change if valid.
 *
 * Triggers map to status transitions defined in the workflow configuration.
 * The tool resolves the target status from the trigger name and current entity state.
 *
 * Built-in triggers (resolved from workflow flow sequence):
 * - "start": Move to the next status in the flow (general forward progression)
 * - "complete": Move to the "completed" terminal status
 * - "cancel": Move to the "cancelled" terminal status (emergency transition)
 * - "block": Move to the "blocked" status (emergency transition)
 * - "unblock": Move back to the previous non-blocked status
 *
 * Custom triggers can be defined via flow event_handlers in the workflow config.
 */
class RequestTransitionTool(
    private val statusProgressionService: StatusProgressionService
) : BaseToolDefinition() {

    private val statusValidator = StatusValidator()

    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT
    override val name: String = "request_transition"
    override val title: String = "Request Status Transition"

    override val description: String = """Trigger-based status transition with validation.

Instead of specifying a raw status, use a named trigger to advance the workflow.
The tool resolves the correct target status, validates prerequisites, and applies the change.

Parameters:
| Field | Type | Required | Description |
| containerId | UUID | Yes | Entity to transition |
| containerType | enum | Yes | project, feature, task |
| trigger | string | Yes | Named trigger (see below) |
| summary | string | No | Note about why the transition is happening |

Built-in triggers:
- "start" - Progress to next status in workflow flow
- "complete" - Move to completed (validates prerequisites)
- "cancel" - Move to cancelled (emergency transition)
- "block" - Move to blocked (emergency transition)
- "hold" - Move to on-hold (emergency transition)

Returns:
- If applied: new status, previous status, previousRole, newRole, cascade events detected
- unblockedTasks array (on task completion/cancellation): downstream tasks that are now fully unblocked, with taskId and title
- If blocked: blocking reasons and prerequisites not met
- If invalid: error with explanation

Response includes `previousRole` and `newRole` fields indicating the semantic role classification (queue, work, review, blocked, terminal) before and after the transition.

Use get_next_status for read-only recommendations without applying changes.
Related: manage_container (setStatus), get_next_status"""

    override val parameterSchema: ToolSchema = ToolSchema(
        properties = JsonObject(
            mapOf(
                "containerId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("UUID of the entity to transition"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "containerType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of container: task, feature, or project"),
                        "enum" to JsonArray(listOf("task", "feature", "project").map { JsonPrimitive(it) })
                    )
                ),
                "trigger" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Named trigger: start, complete, cancel, block, hold")
                    )
                ),
                "summary" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Optional note about the transition reason")
                    )
                )
            )
        ),
        required = listOf("containerId", "containerType", "trigger")
    )

    override val outputSchema: ToolSchema = ToolSchema(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        ),
        required = listOf("success", "message")
    )

    override fun validateParams(params: JsonElement) {
        if (params !is JsonObject) {
            throw ToolValidationException("Parameters must be a JSON object")
        }

        val containerIdStr = params["containerId"]?.jsonPrimitive?.content
            ?: throw ToolValidationException("Missing required parameter: containerId")
        try {
            UUID.fromString(containerIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid containerId format. Must be a valid UUID.")
        }

        val containerType = params["containerType"]?.jsonPrimitive?.content
            ?: throw ToolValidationException("Missing required parameter: containerType")
        if (containerType !in listOf("task", "feature", "project")) {
            throw ToolValidationException("Invalid containerType. Must be one of: task, feature, project")
        }

        val trigger = params["trigger"]?.jsonPrimitive?.content
            ?: throw ToolValidationException("Missing required parameter: trigger")
        if (trigger.isBlank()) {
            throw ToolValidationException("Trigger must not be blank")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing request_transition tool")

        return try {
            val paramsObj = params as JsonObject
            val containerId = UUID.fromString(paramsObj["containerId"]!!.jsonPrimitive.content)
            val containerType = paramsObj["containerType"]!!.jsonPrimitive.content
            val trigger = paramsObj["trigger"]!!.jsonPrimitive.content.lowercase()
            val summary = paramsObj["summary"]?.jsonPrimitive?.content

            // Fetch entity details
            val entityDetails = fetchEntityDetails(containerId, containerType, context)
                ?: return errorResponse(
                    message = "$containerType with ID $containerId not found",
                    code = ErrorCodes.RESOURCE_NOT_FOUND
                )

            val (currentStatus, tags) = entityDetails

            // Resolve trigger to target status
            val targetStatus = resolveTrigger(trigger, currentStatus, containerType, tags)
                ?: return errorResponse(
                    message = "Unknown trigger '$trigger' for $containerType in status '$currentStatus'",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "Valid triggers: start, complete, cancel, block, hold"
                )

            // If trigger resolves to same status, nothing to do
            if (normalizeStatus(targetStatus) == normalizeStatus(currentStatus)) {
                return successResponse(
                    message = "No transition needed - already at '$currentStatus'",
                    data = buildJsonObject {
                        put("containerId", containerId.toString())
                        put("containerType", containerType)
                        put("currentStatus", currentStatus)
                        put("trigger", trigger)
                        put("applied", false)
                    }
                )
            }

            // Build prerequisite context for validation
            val prerequisiteContext = StatusValidator.PrerequisiteContext(
                taskRepository = context.taskRepository(),
                featureRepository = context.featureRepository(),
                projectRepository = context.projectRepository(),
                dependencyRepository = context.dependencyRepository()
            )

            // Validate the transition
            val validationResult = statusValidator.validateTransition(
                currentStatus = currentStatus,
                newStatus = targetStatus,
                containerType = containerType,
                containerId = containerId,
                context = prerequisiteContext,
                tags = tags
            )

            when (validationResult) {
                is StatusValidator.ValidationResult.Invalid -> {
                    return errorResponse(
                        message = "Transition blocked: ${validationResult.reason}",
                        code = ErrorCodes.VALIDATION_ERROR,
                        additionalData = buildJsonObject {
                            put("trigger", trigger)
                            put("currentStatus", currentStatus)
                            put("targetStatus", targetStatus)
                            put("reason", validationResult.reason)
                            if (validationResult.suggestions.isNotEmpty()) {
                                put("suggestions", JsonArray(validationResult.suggestions.map { JsonPrimitive(it) }))
                            }
                        }
                    )
                }
                is StatusValidator.ValidationResult.Valid,
                is StatusValidator.ValidationResult.ValidWithAdvisory -> {
                    // Verification gate check: block completion if verification criteria not met
                    if (trigger == "complete") {
                        val requiresVerification = when (containerType) {
                            "task" -> context.taskRepository().getById(containerId).getOrNull()?.requiresVerification ?: false
                            "feature" -> context.featureRepository().getById(containerId).getOrNull()?.requiresVerification ?: false
                            else -> false
                        }
                        if (requiresVerification) {
                            val gateResult = VerificationGateService.checkVerificationSection(containerId, containerType, context)
                            if (gateResult is VerificationGateService.VerificationCheckResult.Failed) {
                                return errorResponse(
                                    message = "Completion blocked: ${gateResult.reason}",
                                    code = ErrorCodes.VALIDATION_ERROR,
                                    additionalData = buildJsonObject {
                                        put("gate", "verification")
                                        put("containerId", containerId.toString())
                                        put("containerType", containerType)
                                        if (gateResult.failingCriteria.isNotEmpty()) {
                                            put("failingCriteria", JsonArray(
                                                gateResult.failingCriteria.map { JsonPrimitive(it) }
                                            ))
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Apply the status change
                    val applyResult = applyStatusChange(containerId, containerType, targetStatus, context)
                    if (applyResult != null) {
                        return applyResult // Error from apply
                    }

                    // Look up roles for both statuses
                    val previousRole = statusProgressionService.getRoleForStatus(currentStatus, containerType, tags)
                    val newRole = statusProgressionService.getRoleForStatus(targetStatus, containerType, tags)

                    // Detect cascade events
                    val cascadeEvents = detectCascades(containerId, containerType, context)

                    // Find newly unblocked downstream tasks
                    val unblockedTasks = if (containerType == "task" &&
                        (normalizeStatus(targetStatus) in listOf("completed", "cancelled"))) {
                        findNewlyUnblockedTasks(containerId, context)
                    } else emptyList()

                    // Run completion cleanup for feature transitions
                    val cleanupResult = if (containerType == "feature") {
                        runCompletionCleanup(containerId, targetStatus, context)
                    } else null

                    val advisory = if (validationResult is StatusValidator.ValidationResult.ValidWithAdvisory) {
                        validationResult.advisory
                    } else null

                    return successResponse(
                        message = buildString {
                            append("Transitioned $containerType from '$currentStatus' to '$targetStatus'")
                            if (summary != null) append(" ($summary)")
                            if (advisory != null) append(". Advisory: $advisory")
                            if (cascadeEvents.isNotEmpty()) append(". ${cascadeEvents.size} cascade event(s) detected.")
                            if (unblockedTasks.isNotEmpty()) append(". ${unblockedTasks.size} task(s) now unblocked")
                            if (cleanupResult != null && cleanupResult.performed) {
                                append(". Cleanup: ${cleanupResult.tasksDeleted} task(s) deleted, ${cleanupResult.tasksRetained} retained.")
                            }
                        },
                        data = buildJsonObject {
                            put("containerId", containerId.toString())
                            put("containerType", containerType)
                            put("previousStatus", currentStatus)
                            put("newStatus", targetStatus)
                            put("trigger", trigger)
                            put("applied", true)
                            previousRole?.let { put("previousRole", it) }
                            newRole?.let { put("newRole", it) }
                            if (summary != null) put("summary", summary)
                            if (advisory != null) put("advisory", advisory)
                            if (cascadeEvents.isNotEmpty()) {
                                put("cascadeEvents", JsonArray(
                                    cascadeEvents.map { ev ->
                                        buildJsonObject {
                                            put("event", ev["event"]!!)
                                            put("targetType", ev["targetType"]!!)
                                            put("targetId", ev["targetId"]!!)
                                            put("suggestedStatus", ev["suggestedStatus"]!!)
                                            put("reason", ev["reason"]!!)
                                        }
                                    }
                                ))
                            }
                            if (unblockedTasks.isNotEmpty()) {
                                put("unblockedTasks", JsonArray(
                                    unblockedTasks.map { task ->
                                        buildJsonObject {
                                            put("taskId", task["taskId"]!!)
                                            put("title", task["title"]!!)
                                        }
                                    }
                                ))
                            }
                            if (cleanupResult != null && cleanupResult.performed) {
                                put("cleanup", buildJsonObject {
                                    put("performed", true)
                                    put("tasksDeleted", cleanupResult.tasksDeleted)
                                    put("tasksRetained", cleanupResult.tasksRetained)
                                    if (cleanupResult.retainedTaskIds.isNotEmpty()) {
                                        put("retainedTaskIds", JsonArray(
                                            cleanupResult.retainedTaskIds.map { JsonPrimitive(it.toString()) }
                                        ))
                                    }
                                    put("sectionsDeleted", cleanupResult.sectionsDeleted)
                                    put("dependenciesDeleted", cleanupResult.dependenciesDeleted)
                                    put("reason", cleanupResult.reason)
                                })
                            }
                        }
                    )
                }
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in request_transition: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in request_transition", e)
            errorResponse(
                message = "Failed to apply transition",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Resolves a named trigger to a target status based on current state and workflow config.
     */
    private suspend fun resolveTrigger(
        trigger: String,
        currentStatus: String,
        containerType: String,
        tags: List<String>
    ): String? {
        return when (trigger) {
            "start" -> {
                // Get next status in flow via StatusProgressionService
                val recommendation = statusProgressionService.getNextStatus(
                    currentStatus = currentStatus,
                    containerType = containerType,
                    tags = tags
                )
                when (recommendation) {
                    is io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation.Ready ->
                        recommendation.recommendedStatus
                    is io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation.Blocked ->
                        null // Can't start - blocked
                    is io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation.Terminal ->
                        null // Already at terminal
                }
            }
            "complete" -> "completed"
            "cancel" -> "cancelled"
            "block" -> "blocked"
            "hold" -> "on-hold"
            else -> null // Unknown trigger
        }
    }

    /**
     * Applies the status change to the entity via the appropriate repository.
     * Returns an error JsonElement if the update fails, or null on success.
     */
    private suspend fun applyStatusChange(
        containerId: UUID,
        containerType: String,
        targetStatus: String,
        context: ToolExecutionContext
    ): JsonElement? {
        return when (containerType) {
            "task" -> {
                val existing = context.taskRepository().getById(containerId).getOrNull()
                    ?: return errorResponse("Task not found", ErrorCodes.RESOURCE_NOT_FOUND)
                val status = parseTaskStatus(targetStatus)
                    ?: return errorResponse("Invalid task status: $targetStatus", ErrorCodes.VALIDATION_ERROR)
                val updated = existing.copy(status = status, modifiedAt = java.time.Instant.now())
                when (val result = context.taskRepository().update(updated)) {
                    is Result.Success -> null // success
                    is Result.Error -> errorResponse("Failed to update task: ${result.error}", ErrorCodes.INTERNAL_ERROR)
                }
            }
            "feature" -> {
                val existing = context.featureRepository().getById(containerId).getOrNull()
                    ?: return errorResponse("Feature not found", ErrorCodes.RESOURCE_NOT_FOUND)
                val status = parseFeatureStatus(targetStatus)
                    ?: return errorResponse("Invalid feature status: $targetStatus", ErrorCodes.VALIDATION_ERROR)
                val updated = existing.update(status = status)
                when (val result = context.featureRepository().update(updated)) {
                    is Result.Success -> null
                    is Result.Error -> errorResponse("Failed to update feature: ${result.error}", ErrorCodes.INTERNAL_ERROR)
                }
            }
            "project" -> {
                val existing = context.projectRepository().getById(containerId).getOrNull()
                    ?: return errorResponse("Project not found", ErrorCodes.RESOURCE_NOT_FOUND)
                val status = parseProjectStatus(targetStatus)
                    ?: return errorResponse("Invalid project status: $targetStatus", ErrorCodes.VALIDATION_ERROR)
                val updated = existing.update(status = status)
                when (val result = context.projectRepository().update(updated)) {
                    is Result.Success -> null
                    is Result.Error -> errorResponse("Failed to update project: ${result.error}", ErrorCodes.INTERNAL_ERROR)
                }
            }
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    /**
     * Detects cascade events after a status change.
     */
    private suspend fun detectCascades(
        containerId: UUID,
        containerType: String,
        context: ToolExecutionContext
    ): List<Map<String, JsonPrimitive>> {
        return try {
            val workflowService = WorkflowServiceImpl(
                workflowConfigLoader = WorkflowConfigLoaderImpl(),
                taskRepository = context.taskRepository(),
                featureRepository = context.featureRepository(),
                projectRepository = context.projectRepository(),
                statusValidator = statusValidator
            )
            val ct = ContainerType.valueOf(containerType.uppercase())
            val events = workflowService.detectCascadeEvents(containerId, ct)
            events.map { ev ->
                mapOf(
                    "event" to JsonPrimitive(ev.event),
                    "targetType" to JsonPrimitive(ev.targetType.name.lowercase()),
                    "targetId" to JsonPrimitive(ev.targetId.toString()),
                    "suggestedStatus" to JsonPrimitive(ev.suggestedStatus),
                    "reason" to JsonPrimitive(ev.reason)
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to detect cascade events: ${e.message}")
            emptyList()
        }
    }

    /**
     * Finds downstream tasks that are now fully unblocked after the given task was completed or cancelled.
     * A downstream task is "newly unblocked" when ALL of its incoming BLOCKS dependencies
     * point to tasks that are COMPLETED or CANCELLED, and the downstream task itself is still active.
     */
    private suspend fun findNewlyUnblockedTasks(
        completedTaskId: UUID,
        context: ToolExecutionContext
    ): List<Map<String, String>> {
        return try {
            // Get outgoing dependencies from the completed/cancelled task
            val outgoingDeps = context.dependencyRepository().findByFromTaskId(completedTaskId)
            val blocksDeps = outgoingDeps.filter { it.type == DependencyType.BLOCKS }

            if (blocksDeps.isEmpty()) return emptyList()

            val unblockedTasks = mutableListOf<Map<String, String>>()

            for (dep in blocksDeps) {
                val downstreamTaskId = dep.toTaskId

                // Check if the downstream task itself is still active (not completed/cancelled)
                val downstreamTask = context.taskRepository().getById(downstreamTaskId).getOrNull()
                    ?: continue
                val downstreamStatus = normalizeStatus(downstreamTask.status.name)
                if (downstreamStatus in listOf("completed", "cancelled")) continue

                // Get ALL incoming blockers for the downstream task
                val incomingDeps = context.dependencyRepository().findByToTaskId(downstreamTaskId)
                val incomingBlockers = incomingDeps.filter { it.type == DependencyType.BLOCKS }

                // Check if ALL blockers are now completed or cancelled
                val allBlockersResolved = incomingBlockers.all { blocker ->
                    val blockerTask = context.taskRepository().getById(blocker.fromTaskId).getOrNull()
                    if (blockerTask != null) {
                        val blockerStatus = normalizeStatus(blockerTask.status.name)
                        blockerStatus in listOf("completed", "cancelled")
                    } else {
                        // If blocker task doesn't exist, treat as resolved
                        true
                    }
                }

                if (allBlockersResolved) {
                    unblockedTasks.add(mapOf(
                        "taskId" to downstreamTaskId.toString(),
                        "title" to downstreamTask.title
                    ))
                }
            }

            unblockedTasks
        } catch (e: Exception) {
            logger.warn("Failed to find newly unblocked tasks: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchEntityDetails(
        containerId: UUID,
        containerType: String,
        context: ToolExecutionContext
    ): Pair<String, List<String>>? {
        return when (containerType) {
            "task" -> {
                val task = context.taskRepository().getById(containerId).getOrNull() ?: return null
                normalizeStatus(task.status.name) to task.tags
            }
            "feature" -> {
                val feature = context.featureRepository().getById(containerId).getOrNull() ?: return null
                normalizeStatus(feature.status.name) to feature.tags
            }
            "project" -> {
                val project = context.projectRepository().getById(containerId).getOrNull() ?: return null
                normalizeStatus(project.status.name) to project.tags
            }
            else -> null
        }
    }

    private fun normalizeStatus(status: String): String {
        return status.lowercase().replace('_', '-')
    }

    private fun parseTaskStatus(status: String): TaskStatus? {
        val normalized = status.uppercase().replace('-', '_')
        return try {
            TaskStatus.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseFeatureStatus(status: String): FeatureStatus? {
        val normalized = status.uppercase().replace('-', '_')
        return try {
            FeatureStatus.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseProjectStatus(status: String): ProjectStatus? {
        val normalized = status.uppercase().replace('-', '_')
        return try {
            ProjectStatus.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            null
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
}
