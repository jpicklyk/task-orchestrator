package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for identifying tasks that are currently blocked by incomplete dependencies.
 * Essential for workflow management and identifying bottlenecks.
 */
class GetBlockedTasksTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "get_blocked_tasks"

    override val title: String = "Get Blocked Tasks"

    override val description: String = """⚠️ DEPRECATED: Use query_tasks with queryType="blocked" instead.

        Identifies tasks blocked by incomplete dependencies. Essential for workflow management and bottleneck identification.

        Task is Blocked When:
        1. Status is pending or in-progress (active work)
        2. Has incoming dependencies (other tasks block it)
        3. At least one blocking task is NOT completed or cancelled

        Parameters:
        - projectId (optional): Filter to specific project
        - featureId (optional): Filter to specific feature
        - includeTaskDetails (optional): Include full metadata (default: false for efficiency)

        Returns for Each Blocked Task:
        - Basic info: Task ID, title, status, priority, complexity
        - blockedBy array: List of blocking tasks with their statuses
        - blockerCount: Number of incomplete blockers

        Use Cases:
        - Daily standup ("What tasks are blocked today?")
        - Sprint planning (identify tasks that can't start yet)
        - Bottleneck analysis (find what's blocking multiple tasks)
        - Team coordination (know which tasks need other teams' work)

        Usage notes:
        - No parameters returns all blocked tasks
        - Filter by project/feature to focus on specific scope
        - Analyze blocker tasks that appear most often to find bottlenecks
        - Use with get_next_task to find unblocked work

        Related tools: get_next_task, get_task_dependencies, search_tasks

        For detailed examples and patterns: task-orchestrator://docs/tools/get-blocked-tasks
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter blocked tasks to specific project (UUID)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter blocked tasks to specific feature (UUID)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "includeTaskDetails" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include full task details (default: false for efficiency)"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf()
    )

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether the operation succeeded")
                    )
                ),
                "message" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Human-readable message describing the result")
                    )
                ),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Blocked tasks results"),
                        "properties" to JsonObject(
                            mapOf(
                                "blockedTasks" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("List of blocked tasks"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "taskId" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "priority" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "complexity" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                        "blockedBy" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("array"),
                                                                "description" to JsonPrimitive("Tasks blocking this task")
                                                            )
                                                        ),
                                                        "blockerCount" to JsonObject(mapOf("type" to JsonPrimitive("integer")))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "totalBlocked" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Total number of blocked tasks")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("success", "message")
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
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_blocked_tasks tool")

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

            val includeTaskDetails = if (params is JsonObject) {
                params["includeTaskDetails"]?.jsonPrimitive?.boolean ?: false
            } else false

            // Get active tasks (pending or in-progress)
            val activeTasks = getActiveTasks(projectId, featureId, context)

            // Find blocked tasks
            val blockedTasksData = findBlockedTasks(activeTasks, includeTaskDetails, context)

            successResponse(
                data = buildJsonObject {
                    put("blockedTasks", JsonArray(blockedTasksData))
                    put("totalBlocked", blockedTasksData.size)
                },
                message = "Found ${blockedTasksData.size} blocked task(s)"
            )
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in get_blocked_tasks: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in get_blocked_tasks", e)
            errorResponse(
                message = "Failed to get blocked tasks",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Gets all active (pending or in-progress) tasks, optionally filtered by project/feature.
     */
    private suspend fun getActiveTasks(
        projectId: UUID?,
        featureId: UUID?,
        context: ToolExecutionContext
    ): List<Task> {
        // Get tasks based on filtering
        val tasksResult = when {
            featureId != null -> {
                // Feature-specific tasks
                context.taskRepository().findByFeature(featureId, limit = 1000)
            }
            projectId != null -> {
                // Project-specific tasks
                context.taskRepository().findByProject(projectId, limit = 1000)
            }
            else -> {
                // All tasks
                context.taskRepository().findAll(limit = 1000)
            }
        }

        return when (tasksResult) {
            is Result.Success -> {
                // Filter to only active statuses
                tasksResult.data.filter { task ->
                    task.status == TaskStatus.PENDING || task.status == TaskStatus.IN_PROGRESS
                }
            }
            is Result.Error -> {
                logger.warn("Failed to get tasks: ${tasksResult.error.message}")
                emptyList()
            }
        }
    }

    /**
     * Finds which tasks are blocked by incomplete dependencies.
     */
    private suspend fun findBlockedTasks(
        tasks: List<Task>,
        includeDetails: Boolean,
        context: ToolExecutionContext
    ): List<JsonObject> {
        val blockedTasks = mutableListOf<JsonObject>()

        for (task in tasks) {
            // Get incoming dependencies (tasks that block this one)
            val incomingDeps = context.repositoryProvider.dependencyRepository().findByToTaskId(task.id)

            if (incomingDeps.isEmpty()) {
                // No dependencies, not blocked
                continue
            }

            // Check which blockers are incomplete
            val incompleteBlockers = mutableListOf<JsonObject>()

            for (dep in incomingDeps) {
                // Get the blocking task
                when (val blockerResult = context.taskRepository().getById(dep.fromTaskId)) {
                    is Result.Success -> {
                        val blocker = blockerResult.data
                        // Check if blocker is not complete
                        if (blocker.status != TaskStatus.COMPLETED && blocker.status != TaskStatus.CANCELLED) {
                            incompleteBlockers.add(
                                buildJsonObject {
                                    put("taskId", blocker.id.toString())
                                    put("title", blocker.title)
                                    put("status", blocker.status.name.lowercase().replace('_', '-'))
                                    put("priority", blocker.priority.name.lowercase())
                                    if (includeDetails) {
                                        put("complexity", blocker.complexity)
                                        put("featureId", blocker.featureId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                    }
                                }
                            )
                        }
                    }
                    is Result.Error -> {
                        logger.warn("Failed to get blocker task ${dep.fromTaskId}: ${blockerResult.error.message}")
                    }
                }
            }

            // If task has incomplete blockers, it's blocked
            if (incompleteBlockers.isNotEmpty()) {
                blockedTasks.add(
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
                        put("blockedBy", JsonArray(incompleteBlockers))
                        put("blockerCount", incompleteBlockers.size)
                    }
                )
            }
        }

        return blockedTasks
    }
}
