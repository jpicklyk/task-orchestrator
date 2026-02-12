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
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
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

    override val toolAnnotations: ToolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = false,
        openWorldHint = false
    )

    override val name: String = "request_transition"
    override val title: String = "Request Status Transition"

    override val description: String = """Trigger-based status transition with validation.

Instead of specifying a raw status, use a named trigger to advance the workflow.
The tool resolves the correct target status, validates prerequisites, and applies the change.

Parameters:
| Field | Type | Required | Description |
| containerId | UUID | No* | Entity to transition (legacy single mode) |
| containerType | enum | No* | project, feature, task (legacy single mode) |
| trigger | string | No* | Named trigger (legacy single mode) |
| summary | string | No | Note about why the transition is happening |
| transitions | array | No** | Array of transition objects for batch processing. Each: {containerId, containerType, trigger, summary?}. Mutually exclusive with individual params. |

*Required when transitions array is not provided (legacy single-entity mode).
**Required when individual params are not provided (batch mode).

Built-in triggers:
- "start" - Progress to next status in workflow flow
- "complete" - Move to completed (validates prerequisites)
- "cancel" - Move to cancelled (emergency transition)
- "block" - Move to blocked (emergency transition)
- "hold" - Move to on-hold (emergency transition)

Returns:
- Single mode: new status, previous status, previousRole, newRole, cascade events detected, flow context
- Batch mode: results array with per-item outcomes, summary with counts and aggregated unblockedTasks
- unblockedTasks array (on task completion/cancellation): downstream tasks that are now fully unblocked, with taskId and title
- Flow context in all responses: activeFlow, flowSequence, flowPosition for workflow awareness
- If blocked: blocking reasons and prerequisites not met
- If invalid: error with explanation

Response includes `previousRole` and `newRole` fields indicating the semantic role classification (queue, work, review, blocked, terminal) before and after the transition.

get_next_status is optional (useful for preview but not required) before requesting transitions.
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
                ),
                "transitions" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Array of transition objects for batch processing. Each: {containerId, containerType, trigger, summary?}. Mutually exclusive with individual params."),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "required" to JsonArray(listOf("containerId", "containerType", "trigger").map { JsonPrimitive(it) }),
                                "properties" to JsonObject(
                                    mapOf(
                                        "containerId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                        "containerType" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("task", "feature", "project").map { JsonPrimitive(it) }))),
                                        "trigger" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "summary" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
        required = emptyList()
    )

    override val outputSchema: ToolSchema = ToolSchema(
        buildJsonObject {
            put("type", "object")
            put("description", "Data payload shape varies by operation. See tool description for per-operation response details.")
        }
    )

    override fun validateParams(params: JsonElement) {
        if (params !is JsonObject) {
            throw ToolValidationException("Parameters must be a JSON object")
        }

        val transitions = params["transitions"]?.jsonArray
        val containerId = params["containerId"]?.jsonPrimitive?.content
        val containerType = params["containerType"]?.jsonPrimitive?.content
        val trigger = params["trigger"]?.jsonPrimitive?.content

        // Check for mutual exclusivity
        if (transitions != null && (containerId != null || containerType != null || trigger != null)) {
            throw ToolValidationException("Cannot provide both 'transitions' array and individual params (containerId/containerType/trigger). Use one mode or the other.")
        }

        if (transitions != null) {
            // Batch mode validation
            if (transitions.isEmpty()) {
                throw ToolValidationException("transitions array must not be empty")
            }

            transitions.forEachIndexed { index, element ->
                if (element !is JsonObject) {
                    throw ToolValidationException("transitions[$index] must be a JSON object")
                }

                val tContainerId = element["containerId"]?.jsonPrimitive?.content
                    ?: throw ToolValidationException("transitions[$index]: missing required field 'containerId'")
                try {
                    UUID.fromString(tContainerId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("transitions[$index]: invalid containerId format. Must be a valid UUID.")
                }

                val tContainerType = element["containerType"]?.jsonPrimitive?.content
                    ?: throw ToolValidationException("transitions[$index]: missing required field 'containerType'")
                if (tContainerType !in listOf("task", "feature", "project")) {
                    throw ToolValidationException("transitions[$index]: invalid containerType. Must be one of: task, feature, project")
                }

                val tTrigger = element["trigger"]?.jsonPrimitive?.content
                    ?: throw ToolValidationException("transitions[$index]: missing required field 'trigger'")
                if (tTrigger.isBlank()) {
                    throw ToolValidationException("transitions[$index]: trigger must not be blank")
                }
            }
        } else {
            // Legacy single-entity mode validation
            val containerIdStr = containerId
                ?: throw ToolValidationException("Missing required parameter: containerId (or provide 'transitions' array for batch mode)")
            try {
                UUID.fromString(containerIdStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid containerId format. Must be a valid UUID.")
            }

            val ct = containerType
                ?: throw ToolValidationException("Missing required parameter: containerType (or provide 'transitions' array for batch mode)")
            if (ct !in listOf("task", "feature", "project")) {
                throw ToolValidationException("Invalid containerType. Must be one of: task, feature, project")
            }

            val trg = trigger
                ?: throw ToolValidationException("Missing required parameter: trigger (or provide 'transitions' array for batch mode)")
            if (trg.isBlank()) {
                throw ToolValidationException("Trigger must not be blank")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing request_transition tool")

        return try {
            val paramsObj = params as JsonObject
            val transitions = paramsObj["transitions"]?.jsonArray

            if (transitions != null) {
                processBatchTransitions(transitions, context)
            } else {
                // Legacy single-entity path
                val containerId = UUID.fromString(paramsObj["containerId"]!!.jsonPrimitive.content)
                val containerType = paramsObj["containerType"]!!.jsonPrimitive.content
                val trigger = paramsObj["trigger"]!!.jsonPrimitive.content.lowercase()
                val summary = paramsObj["summary"]?.jsonPrimitive?.content

                val result = processSingleTransition(containerId, containerType, trigger, summary, context)
                // Check if it was an error
                if (result.containsKey("error") || result["applied"]?.jsonPrimitive?.boolean == false) {
                    val errorMsg = result["error"]?.jsonPrimitive?.content
                    if (errorMsg != null) {
                        errorResponse(
                            message = errorMsg,
                            code = ErrorCodes.VALIDATION_ERROR,
                            additionalData = result
                        )
                    } else {
                        // applied=false but no error (e.g., no transition needed)
                        successResponse(
                            message = buildTransitionMessage(result),
                            data = result
                        )
                    }
                } else {
                    successResponse(
                        message = buildTransitionMessage(result),
                        data = result
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

    private fun buildTransitionMessage(result: JsonObject): String {
        val containerId = result["containerId"]?.jsonPrimitive?.content ?: ""
        val containerType = result["containerType"]?.jsonPrimitive?.content ?: "entity"
        val currentStatus = result["currentStatus"]?.jsonPrimitive?.content
        val previousStatus = result["previousStatus"]?.jsonPrimitive?.content
        val newStatus = result["newStatus"]?.jsonPrimitive?.content
        val trigger = result["trigger"]?.jsonPrimitive?.content
        val summary = result["summary"]?.jsonPrimitive?.content
        val advisory = result["advisory"]?.jsonPrimitive?.content
        val cascadeEvents = result["cascadeEvents"]?.jsonArray
        val unblockedTasks = result["unblockedTasks"]?.jsonArray
        val cleanup = result["cleanup"]?.jsonObject

        return buildString {
            if (currentStatus != null && result["applied"]?.jsonPrimitive?.boolean == false) {
                append("No transition needed - already at '$currentStatus'")
            } else if (previousStatus != null && newStatus != null) {
                append("Transitioned $containerType from '$previousStatus' to '$newStatus'")
                if (summary != null) append(" ($summary)")
                if (advisory != null) append(". Advisory: $advisory")
                if (cascadeEvents != null && cascadeEvents.isNotEmpty()) {
                    append(". ${cascadeEvents.size} cascade event(s) detected.")
                }
                if (unblockedTasks != null && unblockedTasks.isNotEmpty()) {
                    append(". ${unblockedTasks.size} task(s) now unblocked")
                }
                if (cleanup != null && cleanup["performed"]?.jsonPrimitive?.boolean == true) {
                    val tasksDeleted = cleanup["tasksDeleted"]?.jsonPrimitive?.int ?: 0
                    val tasksRetained = cleanup["tasksRetained"]?.jsonPrimitive?.int ?: 0
                    append(". Cleanup: $tasksDeleted task(s) deleted, $tasksRetained retained.")
                }
            } else {
                append("Transition result")
            }
        }
    }

    private suspend fun processBatchTransitions(
        transitions: JsonArray,
        context: ToolExecutionContext
    ): JsonElement {
        val results = mutableListOf<JsonObject>()
        val allUnblockedTasks = mutableListOf<JsonObject>()
        var succeeded = 0
        var failed = 0

        for (element in transitions) {
            val t = element.jsonObject
            val containerId = UUID.fromString(t["containerId"]!!.jsonPrimitive.content)
            val containerType = t["containerType"]!!.jsonPrimitive.content
            val trigger = t["trigger"]!!.jsonPrimitive.content.lowercase()
            val summary = t["summary"]?.jsonPrimitive?.content

            val result = processSingleTransition(containerId, containerType, trigger, summary, context)
            results.add(result)

            if (result["applied"]?.jsonPrimitive?.boolean == true) {
                succeeded++
                result["unblockedTasks"]?.jsonArray?.forEach {
                    allUnblockedTasks.add(it.jsonObject)
                }
            } else {
                failed++
            }
        }

        return successResponse(
            message = "$succeeded of ${results.size} transitions applied" +
                if (failed > 0) " ($failed failed)" else "" +
                if (allUnblockedTasks.isNotEmpty()) ". ${allUnblockedTasks.size} task(s) unblocked" else "",
            data = buildJsonObject {
                put("results", JsonArray(results))
                put("summary", buildJsonObject {
                    put("total", results.size)
                    put("succeeded", succeeded)
                    put("failed", failed)
                    if (allUnblockedTasks.isNotEmpty()) {
                        put("allUnblockedTasks", JsonArray(allUnblockedTasks))
                    }
                })
            }
        )
    }

    private suspend fun processSingleTransition(
        containerId: UUID,
        containerType: String,
        trigger: String,
        summary: String?,
        context: ToolExecutionContext
    ): JsonObject {
        return try {

            // Fetch entity details
            val entityDetails = fetchEntityDetails(containerId, containerType, context)
                ?: return buildJsonObject {
                    put("applied", false)
                    put("error", "$containerType with ID $containerId not found")
                    put("containerId", containerId.toString())
                    put("containerType", containerType)
                }

            val (currentStatus, tags) = entityDetails

            // Resolve trigger to target status
            val targetStatus = resolveTrigger(trigger, currentStatus, containerType, tags)
                ?: return buildJsonObject {
                    put("applied", false)
                    put("error", "Unknown trigger '$trigger' for $containerType in status '$currentStatus'")
                    put("containerId", containerId.toString())
                    put("containerType", containerType)
                    put("trigger", trigger)
                    put("currentStatus", currentStatus)
                    // Add flow context even for trigger resolution failures
                    try {
                        val flowPath = statusProgressionService.getFlowPath(containerType, tags, currentStatus)
                        put("activeFlow", flowPath.activeFlow)
                        put("flowSequence", JsonArray(flowPath.flowSequence.map { JsonPrimitive(it) }))
                        put("flowPosition", flowPath.currentPosition ?: -1)
                    } catch (_: Exception) { }
                }

            // If trigger resolves to same status, nothing to do
            if (normalizeStatus(targetStatus) == normalizeStatus(currentStatus)) {
                return buildJsonObject {
                    put("containerId", containerId.toString())
                    put("containerType", containerType)
                    put("currentStatus", currentStatus)
                    put("trigger", trigger)
                    put("applied", false)
                }
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
                    return buildJsonObject {
                        put("applied", false)
                        put("error", "Transition blocked: ${validationResult.reason}")
                        put("containerId", containerId.toString())
                        put("containerType", containerType)
                        put("trigger", trigger)
                        put("currentStatus", currentStatus)
                        put("targetStatus", targetStatus)
                        put("reason", validationResult.reason)
                        if (validationResult.suggestions.isNotEmpty()) {
                            put("suggestions", JsonArray(validationResult.suggestions.map { JsonPrimitive(it) }))
                        }
                        // Add flow context
                        try {
                            val flowPath = statusProgressionService.getFlowPath(containerType, tags, currentStatus)
                            put("activeFlow", flowPath.activeFlow)
                            put("flowSequence", JsonArray(flowPath.flowSequence.map { JsonPrimitive(it) }))
                            put("flowPosition", flowPath.currentPosition ?: -1)
                        } catch (_: Exception) {
                            // Non-fatal — flow context is informational
                        }
                    }
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
                                return buildJsonObject {
                                    put("applied", false)
                                    put("error", "Completion blocked: ${gateResult.reason}")
                                    put("containerId", containerId.toString())
                                    put("containerType", containerType)
                                    put("gate", "verification")
                                    if (gateResult.failingCriteria.isNotEmpty()) {
                                        put("failingCriteria", JsonArray(
                                            gateResult.failingCriteria.map { JsonPrimitive(it) }
                                        ))
                                    }
                                }
                            }
                        }
                    }

                    // Apply the status change
                    val applyError = applyStatusChange(containerId, containerType, targetStatus, context)
                    if (applyError != null) {
                        return buildJsonObject {
                            put("applied", false)
                            put("error", applyError)
                            put("containerId", containerId.toString())
                            put("containerType", containerType)
                        }
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

                    return buildJsonObject {
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
                        // Add flow context
                        try {
                            val flowPath = statusProgressionService.getFlowPath(containerType, tags, currentStatus)
                            put("activeFlow", flowPath.activeFlow)
                            put("flowSequence", JsonArray(flowPath.flowSequence.map { JsonPrimitive(it) }))
                            put("flowPosition", flowPath.currentPosition ?: -1)
                        } catch (e: Exception) {
                            logger.warn("Failed to get flow path for response enrichment: ${e.message}")
                            // Non-fatal — flow context is informational
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error in processSingleTransition", e)
            return buildJsonObject {
                put("applied", false)
                put("error", "Failed to apply transition: ${e.message ?: "Unknown error"}")
                put("containerId", containerId.toString())
                put("containerType", containerType)
            }
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
     * Returns an error message String if the update fails, or null on success.
     */
    private suspend fun applyStatusChange(
        containerId: UUID,
        containerType: String,
        targetStatus: String,
        context: ToolExecutionContext
    ): String? {
        return when (containerType) {
            "task" -> {
                val existing = context.taskRepository().getById(containerId).getOrNull()
                    ?: return "Task not found"
                val status = parseTaskStatus(targetStatus)
                    ?: return "Invalid task status: $targetStatus"
                val updated = existing.copy(status = status, modifiedAt = java.time.Instant.now())
                when (val result = context.taskRepository().update(updated)) {
                    is Result.Success -> null // success
                    is Result.Error -> "Failed to update task: ${result.error}"
                }
            }
            "feature" -> {
                val existing = context.featureRepository().getById(containerId).getOrNull()
                    ?: return "Feature not found"
                val status = parseFeatureStatus(targetStatus)
                    ?: return "Invalid feature status: $targetStatus"
                val updated = existing.update(status = status)
                when (val result = context.featureRepository().update(updated)) {
                    is Result.Success -> null
                    is Result.Error -> "Failed to update feature: ${result.error}"
                }
            }
            "project" -> {
                val existing = context.projectRepository().getById(containerId).getOrNull()
                    ?: return "Project not found"
                val status = parseProjectStatus(targetStatus)
                    ?: return "Invalid project status: $targetStatus"
                val updated = existing.update(status = status)
                when (val result = context.projectRepository().update(updated)) {
                    is Result.Success -> null
                    is Result.Error -> "Failed to update project: ${result.error}"
                }
            }
            else -> "Unsupported container type: $containerType"
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

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return super.userSummary(params, result, true)
        val data = (result as? JsonObject)?.get("data")?.jsonObject ?: return super.userSummary(params, result, false)

        // Batch response
        val summary = data["summary"]?.jsonObject
        if (summary != null) {
            val total = summary["total"]?.jsonPrimitive?.int ?: 0
            val succeeded = summary["succeeded"]?.jsonPrimitive?.int ?: 0
            val failed = summary["failed"]?.jsonPrimitive?.int ?: 0
            val unblocked = summary["allUnblockedTasks"]?.jsonArray?.size ?: 0
            return buildString {
                append("batch: $succeeded/$total succeeded")
                if (failed > 0) append(", $failed failed")
                if (unblocked > 0) append(" ($unblocked unblocked)")
            }
        }

        // Single response
        val containerType = data["containerType"]?.jsonPrimitive?.content ?: "entity"
        val containerId = data["containerId"]?.jsonPrimitive?.content?.let { shortId(it) } ?: ""
        val previous = data["previousStatus"]?.jsonPrimitive?.content ?: ""
        val newStatus = data["newStatus"]?.jsonPrimitive?.content ?: ""
        val unblockedTasks = data["unblockedTasks"]?.jsonArray?.size ?: 0
        return buildString {
            append("$containerType ($containerId): $previous → $newStatus")
            if (unblockedTasks > 0) {
                append(" ($unblockedTasks task${if (unblockedTasks == 1) "" else "s"} unblocked)")
            }
        }
    }
}
