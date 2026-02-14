package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.StatusRole
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for recommending the next task to work on based on priority, complexity,
 * status, and dependencies. Helps users prioritize work effectively.
 */
class GetNextTaskTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val toolAnnotations: ToolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val name: String = "get_next_task"

    override val title: String = "Get Next Task Recommendation"

    override val description: String = """Recommends next task based on status, dependencies, priority, and complexity. Filters out blocked tasks and ranks by priority (quick wins first).

        Selection Logic:
        1. Retrieves all pending tasks (not yet started)
        2. Filters out blocked tasks (with incomplete dependencies)
        3. Sorts by priority (HIGH → MEDIUM → LOW)
        4. Within same priority, sorts by complexity (lower first for quick wins)
        5. Returns top recommendations

        Parameters:
        - limit (optional): Number of recommendations (default: 1, max: 20)
        - projectId (optional): Filter to specific project
        - featureId (optional): Filter to specific feature
        - includeDetails (optional): Include summary, tags, featureId (default: false)

        Returns Array With:
        - taskId, title, status, priority, complexity (always)
        - summary, tags, featureId (if includeDetails=true)

        Sorting Rationale:
        High-priority, low-complexity tasks provide maximum impact with minimum effort (quick wins). Higher complexity high-priority tasks come after quick wins.

        Use Cases:
        - Daily planning ("What should I work on today?")
        - Context switching ("I just finished X, what's next?")
        - Quick wins ("Show me easy, high-priority tasks")

        Usage notes:
        - Automatically excludes blocked tasks
        - Default returns single top recommendation
        - Filter by project/feature for focused scope
        - Use with manage_container to mark tasks complete

        Related tools: get_blocked_tasks, query_container, manage_container

        For detailed examples and patterns: task-orchestrator://docs/tools/get-next-task
        """

    override val parameterSchema: ToolSchema = ToolSchema(
        properties = JsonObject(
            mapOf(
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter recommendations to specific project (UUID)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter recommendations to specific feature (UUID)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "limit" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum number of recommendations to return (default: 1, max: 20)"),
                        "default" to JsonPrimitive(1),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(20)
                    )
                ),
                "includeDetails" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include full task details (summary, tags, featureId). Default: false for efficiency"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf()
    )

    override val outputSchema: ToolSchema = ToolSchema(
        buildJsonObject {
            put("type", "object")
            put("description", "Task recommendations")
            putJsonObject("properties") {
                putJsonObject("recommendations") {
                    put("type", "array")
                    put("description", "Recommended tasks sorted by priority then complexity")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("taskId") {
                                put("type", "string")
                            }
                            putJsonObject("title") {
                                put("type", "string")
                            }
                            putJsonObject("status") {
                                put("type", "string")
                            }
                            putJsonObject("priority") {
                                put("type", "string")
                            }
                            putJsonObject("complexity") {
                                put("type", "integer")
                            }
                        }
                    }
                }
                putJsonObject("totalCandidates") {
                    put("type", "integer")
                    put("description", "Total number of unblocked tasks available")
                }
            }
        }
    )

    override fun validateParams(params: JsonElement) {
        // Validate projectId if provided
        if (params is JsonObject && params.containsKey("projectId")) {
            val projectIdStr = params["projectId"]?.jsonPrimitive?.content
            if (projectIdStr != null) {
                try {
                    UUID.fromString(projectIdStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid projectId format. Must be a valid UUID.")
                }
            }
        }

        // Validate featureId if provided
        if (params is JsonObject && params.containsKey("featureId")) {
            val featureIdStr = params["featureId"]?.jsonPrimitive?.content
            if (featureIdStr != null) {
                try {
                    UUID.fromString(featureIdStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid featureId format. Must be a valid UUID.")
                }
            }
        }

        // Validate limit if provided
        if (params is JsonObject && params.containsKey("limit")) {
            val limit = params["limit"]?.jsonPrimitive?.int
            if (limit != null && (limit < 1 || limit > 20)) {
                throw ToolValidationException("Invalid limit. Must be between 1 and 20.")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_next_task tool")

        return try {
            // Parse parameters
            val projectId = if (params is JsonObject && params.containsKey("projectId")) {
                val idStr = params["projectId"]?.jsonPrimitive?.content
                if (idStr != null) UUID.fromString(idStr) else null
            } else null

            val featureId = if (params is JsonObject && params.containsKey("featureId")) {
                val idStr = params["featureId"]?.jsonPrimitive?.content
                if (idStr != null) UUID.fromString(idStr) else null
            } else null

            val limit = if (params is JsonObject) {
                params["limit"]?.jsonPrimitive?.int ?: 1
            } else 1

            val includeDetails = if (params is JsonObject) {
                params["includeDetails"]?.jsonPrimitive?.boolean ?: false
            } else false

            // Get active tasks (pending or in-progress)
            val activeTasks = getActiveTasks(projectId, featureId, context)

            // Filter out blocked tasks
            val unblockedTasks = filterUnblockedTasks(activeTasks, context)

            // Sort by priority then complexity
            val sortedTasks = sortTasksByPriorityAndComplexity(unblockedTasks)

            // Take top N recommendations
            val recommendations = sortedTasks.take(limit).map { task ->
                buildJsonObject {
                    put("taskId", task.id.toString())
                    put("title", task.title)
                    put("status", task.status.name.lowercase().replace('_', '-'))
                    put("priority", task.priority.name.lowercase())
                    put("complexity", task.complexity)
                    if (includeDetails) {
                        put("summary", task.summary)
                        put("featureId", task.featureId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("tags", JsonArray(task.tags.map { JsonPrimitive(it) }))
                    }
                }
            }

            successResponse(
                data = buildJsonObject {
                    put("recommendations", JsonArray(recommendations))
                    put("totalCandidates", unblockedTasks.size)
                },
                message = if (recommendations.isEmpty()) {
                    "No unblocked tasks available"
                } else {
                    "Found ${recommendations.size} recommendation(s) from ${unblockedTasks.size} unblocked task(s)"
                }
            )
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in get_next_task: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in get_next_task", e)
            errorResponse(
                message = "Failed to get task recommendations",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Gets all pending (not yet started) tasks, optionally filtered by project/feature.
     */
    private suspend fun getActiveTasks(
        projectId: UUID?,
        featureId: UUID?,
        context: ToolExecutionContext
    ): List<Task> {
        // Get tasks based on filtering
        val tasksResult = when {
            featureId != null -> {
                context.taskRepository().findByFeature(featureId, limit = 1000)
            }
            projectId != null -> {
                context.taskRepository().findByProject(projectId, limit = 1000)
            }
            else -> {
                context.taskRepository().findAll(limit = 1000)
            }
        }

        return when (tasksResult) {
            is Result.Success -> {
                // Filter to only pending tasks (not yet started)
                tasksResult.data.filter { task ->
                    task.status == TaskStatus.PENDING
                }
            }
            is Result.Error -> {
                logger.warn("Failed to get tasks: ${tasksResult.error.message}")
                emptyList()
            }
        }
    }

    /**
     * Filters out tasks that are blocked by incomplete dependencies.
     * Uses role-aware checking when StatusProgressionService is available.
     */
    private suspend fun filterUnblockedTasks(
        tasks: List<Task>,
        context: ToolExecutionContext
    ): List<Task> {
        val statusProgressionService = context.statusProgressionService()

        return tasks.filter { task ->
            // Get incoming dependencies (tasks that block this one)
            val incomingDeps = context.repositoryProvider.dependencyRepository().findByToTaskId(task.id)

            if (incomingDeps.isEmpty()) {
                // No dependencies, not blocked
                return@filter true
            }

            // Check if all blockers are complete
            val hasIncompleteBlockers = incomingDeps.any { dep ->
                // Only BLOCKS and IS_BLOCKED_BY create actual blocking
                if (dep.type == DependencyType.RELATES_TO) return@any false

                val blockerTaskId = dep.getBlockerTaskId()
                when (val blockerResult = context.taskRepository().getById(blockerTaskId)) {
                    is Result.Success -> {
                        val blocker = blockerResult.data
                        val threshold = dep.effectiveUnblockRole() ?: "terminal"

                        if (statusProgressionService != null) {
                            val blockerRole = statusProgressionService.getRoleForStatus(
                                blocker.status.name.lowercase().replace('_', '-'), "task", blocker.tags
                            )
                            !StatusRole.isRoleAtOrBeyond(blockerRole, threshold)
                        } else {
                            // Fallback: only terminal statuses unblock
                            blocker.status != TaskStatus.COMPLETED && blocker.status != TaskStatus.CANCELLED
                        }
                    }
                    is Result.Error -> {
                        // If we can't find the blocker, assume it's incomplete (conservative approach)
                        logger.warn("Failed to get blocker task ${blockerTaskId}: ${blockerResult.error.message}")
                        true
                    }
                }
            }

            // Task is unblocked if it has no incomplete blockers
            !hasIncompleteBlockers
        }
    }

    /**
     * Sorts tasks by priority (HIGH → MEDIUM → LOW) then complexity (lower first).
     */
    private fun sortTasksByPriorityAndComplexity(tasks: List<Task>): List<Task> {
        return tasks.sortedWith(
            compareBy<Task> { task ->
                // Sort by priority descending (HIGH=0, MEDIUM=1, LOW=2)
                when (task.priority) {
                    Priority.HIGH -> 0
                    Priority.MEDIUM -> 1
                    Priority.LOW -> 2
                }
            }.thenBy { task ->
                // Then sort by complexity ascending (lower complexity first)
                task.complexity
            }
        )
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return super.userSummary(params, result, true)
        val data = (result as? JsonObject)?.get("data")?.jsonObject
        val recommendations = data?.get("recommendations")?.jsonArray
        if (recommendations == null || recommendations.isEmpty()) {
            return "No unblocked tasks available"
        }
        val top = recommendations[0].jsonObject
        val title = top["title"]?.jsonPrimitive?.content ?: ""
        val priority = top["priority"]?.jsonPrimitive?.content ?: ""
        val complexity = top["complexity"]?.jsonPrimitive?.content ?: ""
        return "Top recommendation: \"$title\" (priority=$priority, complexity=$complexity)"
    }
}
