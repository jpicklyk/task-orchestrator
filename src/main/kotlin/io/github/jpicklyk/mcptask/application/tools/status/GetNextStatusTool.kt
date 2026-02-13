package io.github.jpicklyk.mcptask.application.tools.status

import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool for getting intelligent status progression recommendations.
 *
 * This read-only tool suggests the next status in a workflow based on:
 * - Entity tags to determine active workflow flow
 * - Current status position in the flow
 * - Prerequisite readiness (via StatusValidator integration)
 * - Terminal status blocking
 *
 * NOTE: This tool only SUGGESTS next status. Use request_transition tool to actually apply the status change.
 */
class GetNextStatusTool(
    private val statusProgressionService: StatusProgressionService
) : BaseToolDefinition() {

    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val toolAnnotations: ToolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val name: String = "get_next_status"

    override val title: String = "Get Next Status Recommendation"

    override val description: String = """
        Get intelligent status progression recommendations based on workflow configuration and entity state.

        This read-only tool analyzes the entity and suggests the next status in the workflow. It does NOT change status.

        Analysis considers:
        - Entity tags to determine active workflow (bug_fix_flow, documentation_flow, default_flow)
        - Current position in workflow sequence
        - Prerequisite readiness (summary populated, tasks completed, etc.)
        - Terminal status blocking (cannot progress from completed/cancelled)
        - Emergency transitions (blocked, cancelled, archived)

        Required Parameters:
        - containerId: UUID of task/feature/project to analyze
        - containerType: "task", "feature", or "project"

        Optional Parameters:
        - currentStatus: Current status (if not provided, fetched from entity)
        - tags: Override entity tags for flow determination (uses entity's tags by default)

        Returns:
        - recommendation: "Ready", "Blocked", or "Terminal"
        - If Ready: recommendedStatus, activeFlow, flowSequence, currentPosition, matchedTags, reason
        - If Blocked: currentStatus, blockers, activeFlow, flowSequence, currentPosition
        - If Terminal: terminalStatus, activeFlow, reason

        Use Cases:
        - Status Progression Skill ("What's next?" / "Can I complete this?")
        - AI workflow automation (checking if entity can progress)
        - UI/dashboard showing next available statuses
        - Validation before applying status transitions

        To apply recommended status:
        request_transition(transitions=[{containerId: "...", containerType: "...", trigger: "start"}])

        Related tools: request_transition, manage_container, query_container

        For detailed examples: task-orchestrator://docs/tools/get-next-status
    """.trimIndent()

    override val parameterSchema: ToolSchema = ToolSchema(
        properties = JsonObject(
            mapOf(
                "containerId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("UUID of the task/feature/project to analyze"),
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
                "currentStatus" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Optional: Current status (if not provided, fetched from entity)")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Optional: Override entity tags for flow determination"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                    )
                )
            )
        ),
        required = listOf("containerId", "containerType")
    )

    override val outputSchema: ToolSchema = ToolSchema(
        buildJsonObject {
            put("type", "object")
            put("description", "Status recommendation details")
            putJsonObject("properties") {
                putJsonObject("recommendation") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("Ready")
                        add("Blocked")
                        add("Terminal")
                    }
                    put("description", "Type of recommendation: Ready (can progress), Blocked (has blockers), Terminal (cannot progress)")
                }
                putJsonObject("recommendedStatus") {
                    put("type", "string")
                    put("description", "Next status to transition to (Ready only)")
                }
                putJsonObject("currentStatus") {
                    put("type", "string")
                    put("description", "Current entity status")
                }
                putJsonObject("activeFlow") {
                    put("type", "string")
                    put("description", "Active workflow name (e.g., bug_fix_flow, default_flow)")
                }
                putJsonObject("flowSequence") {
                    put("type", "array")
                    put("description", "Complete status sequence for active flow")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                }
                putJsonObject("currentPosition") {
                    put("type", "integer")
                    put("description", "0-based index of current status in flow")
                }
                putJsonObject("matchedTags") {
                    put("type", "array")
                    put("description", "Tags that matched to determine flow")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                }
                putJsonObject("blockers") {
                    put("type", "array")
                    put("description", "List of blocking reasons (Blocked only)")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                }
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "Human-readable explanation of recommendation")
                }
            }
        }
    )

    override fun validateParams(params: JsonElement) {
        if (params !is JsonObject) {
            throw ToolValidationException("Parameters must be a JSON object")
        }

        // Validate containerId
        val containerIdStr = params["containerId"]?.jsonPrimitive?.content
            ?: throw ToolValidationException("Missing required parameter: containerId")

        try {
            UUID.fromString(containerIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid containerId format. Must be a valid UUID.")
        }

        // Validate containerType
        val containerType = params["containerType"]?.jsonPrimitive?.content
            ?: throw ToolValidationException("Missing required parameter: containerType")

        if (containerType !in listOf("task", "feature", "project")) {
            throw ToolValidationException("Invalid containerType. Must be one of: task, feature, project")
        }

        // Validate tags if provided
        if (params.containsKey("tags")) {
            val tagsElement = params["tags"]
            if (tagsElement !is JsonArray) {
                throw ToolValidationException("Parameter 'tags' must be an array")
            }

            // Validate each tag is a string
            tagsElement.forEach { tagElement ->
                if (tagElement !is JsonPrimitive || !tagElement.isString) {
                    throw ToolValidationException("All tags must be strings")
                }
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_next_status tool")

        return try {
            val paramsObj = params as JsonObject

            // Parse parameters
            val containerId = UUID.fromString(paramsObj["containerId"]!!.jsonPrimitive.content)
            val containerType = paramsObj["containerType"]!!.jsonPrimitive.content

            // Fetch entity to get current status, tags, and verification flag
            val entityInfo = fetchEntityDetails(containerId, containerType, context)
                ?: return errorResponse(
                    message = "$containerType with ID $containerId not found",
                    code = ErrorCodes.RESOURCE_NOT_FOUND
                )
            val (currentStatus, entityTags) = entityInfo
            val entityRequiresVerification = fetchRequiresVerification(containerId, containerType, context)

            // Use provided status/tags or fall back to entity's values
            val statusToUse = paramsObj["currentStatus"]?.jsonPrimitive?.content ?: currentStatus
            val tagsToUse = if (paramsObj.containsKey("tags")) {
                (paramsObj["tags"] as JsonArray).map { it.jsonPrimitive.content }
            } else {
                entityTags
            }

            logger.debug("Analyzing status progression for $containerType $containerId: status=$statusToUse, tags=$tagsToUse")

            // Get recommendation from StatusProgressionService
            val recommendation = statusProgressionService.getNextStatus(
                currentStatus = statusToUse,
                containerType = containerType,
                tags = tagsToUse,
                containerId = containerId
            )

            // Build response based on recommendation type
            val responseData = when (recommendation) {
                is NextStatusRecommendation.Ready -> {
                    buildJsonObject {
                        put("recommendation", "Ready")
                        put("recommendedStatus", recommendation.recommendedStatus)
                        put("currentStatus", statusToUse)
                        put("activeFlow", recommendation.activeFlow)
                        put("flowSequence", JsonArray(recommendation.flowSequence.map { JsonPrimitive(it) }))
                        put("currentPosition", recommendation.currentPosition)
                        put("matchedTags", JsonArray(recommendation.matchedTags.map { JsonPrimitive(it) }))
                        put("reason", recommendation.reason)
                        if (recommendation.currentRole != null) put("currentRole", recommendation.currentRole)
                        if (recommendation.nextRole != null) put("nextRole", recommendation.nextRole)
                        if (entityRequiresVerification) put("verificationGateActive", true)
                    }
                }
                is NextStatusRecommendation.Blocked -> {
                    buildJsonObject {
                        put("recommendation", "Blocked")
                        put("currentStatus", recommendation.currentStatus)
                        put("blockers", JsonArray(recommendation.blockers.map { JsonPrimitive(it) }))
                        put("activeFlow", recommendation.activeFlow)
                        put("flowSequence", JsonArray(recommendation.flowSequence.map { JsonPrimitive(it) }))
                        put("currentPosition", recommendation.currentPosition)
                        put("reason", "Cannot progress: ${recommendation.blockers.joinToString("; ")}")
                    }
                }
                is NextStatusRecommendation.Terminal -> {
                    buildJsonObject {
                        put("recommendation", "Terminal")
                        put("currentStatus", recommendation.terminalStatus)
                        put("activeFlow", recommendation.activeFlow)
                        put("reason", recommendation.reason)
                    }
                }
            }

            successResponse(
                data = responseData,
                message = when (recommendation) {
                    is NextStatusRecommendation.Ready ->
                        "Ready to progress to '${recommendation.recommendedStatus}' in ${recommendation.activeFlow}"
                    is NextStatusRecommendation.Blocked ->
                        "Blocked by ${recommendation.blockers.size} issue(s)"
                    is NextStatusRecommendation.Terminal ->
                        "At terminal status '${recommendation.terminalStatus}'"
                }
            )
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in get_next_status: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in get_next_status", e)
            errorResponse(
                message = "Failed to get status recommendation",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Fetches the requiresVerification flag for an entity.
     * Returns false if entity not found or container type doesn't support verification.
     */
    private suspend fun fetchRequiresVerification(
        containerId: UUID,
        containerType: String,
        context: ToolExecutionContext
    ): Boolean {
        return when (containerType) {
            "task" -> {
                when (val result = context.taskRepository().getById(containerId)) {
                    is Result.Success -> result.data.requiresVerification
                    is Result.Error -> false
                }
            }
            "feature" -> {
                when (val result = context.featureRepository().getById(containerId)) {
                    is Result.Success -> result.data.requiresVerification
                    is Result.Error -> false
                }
            }
            else -> false
        }
    }

    /**
     * Fetches entity details (current status and tags) from the appropriate repository.
     * Returns null if entity not found.
     */
    private suspend fun fetchEntityDetails(
        containerId: UUID,
        containerType: String,
        context: ToolExecutionContext
    ): Pair<String, List<String>>? {
        return when (containerType) {
            "task" -> {
                when (val result = context.taskRepository().getById(containerId)) {
                    is Result.Success -> {
                        val task = result.data
                        Pair(task.status.name.lowercase().replace('_', '-'), task.tags)
                    }
                    is Result.Error -> {
                        logger.warn("Task not found: $containerId")
                        null
                    }
                }
            }
            "feature" -> {
                when (val result = context.featureRepository().getById(containerId)) {
                    is Result.Success -> {
                        val feature = result.data
                        Pair(feature.status.name.lowercase().replace('_', '-'), feature.tags)
                    }
                    is Result.Error -> {
                        logger.warn("Feature not found: $containerId")
                        null
                    }
                }
            }
            "project" -> {
                when (val result = context.projectRepository().getById(containerId)) {
                    is Result.Success -> {
                        val project = result.data
                        Pair(project.status.name.lowercase().replace('_', '-'), project.tags)
                    }
                    is Result.Error -> {
                        logger.warn("Project not found: $containerId")
                        null
                    }
                }
            }
            else -> {
                logger.error("Unsupported container type: $containerType")
                null
            }
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return super.userSummary(params, result, true)
        val data = (result as? JsonObject)?.get("data")?.jsonObject
        val recommendation = data?.get("recommendation")?.jsonPrimitive?.content ?: ""
        return when (recommendation) {
            "Ready" -> {
                val current = data?.get("currentStatus")?.jsonPrimitive?.content ?: ""
                val recommended = data?.get("recommendedStatus")?.jsonPrimitive?.content ?: ""
                "Ready: $current → $recommended"
            }
            "Blocked" -> {
                val blockers = data?.get("blockers")?.jsonArray?.size ?: 0
                "Blocked by $blockers prerequisite${if (blockers == 1) "" else "s"}"
            }
            "Terminal" -> {
                val status = data?.get("currentStatus")?.jsonPrimitive?.content ?: ""
                "Terminal status: $status"
            }
            else -> "Status recommendation: $recommendation"
        }
    }
}
