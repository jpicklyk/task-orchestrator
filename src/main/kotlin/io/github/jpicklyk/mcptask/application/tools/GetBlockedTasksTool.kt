package io.github.jpicklyk.mcptask.application.tools

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

    override val description: String = """Identifies tasks currently blocked by incomplete dependencies.

        ## Purpose
        Helps users identify tasks that cannot progress because they depend on other
        incomplete tasks. Essential for workflow management, bottleneck identification,
        and understanding project blockers.

        ## What Makes a Task "Blocked"?

        A task is considered blocked when:
        1. Task status is `pending` or `in-progress` (active work)
        2. Task has incoming dependencies (other tasks block it)
        3. At least one blocking task is NOT `completed` or `cancelled`

        ## Features
        - **Automatic Blocking Detection**: Identifies blocked tasks across project
        - **Blocker Information**: Shows which tasks are causing blocks
        - **Project/Feature Filtering**: Focus on specific scope
        - **Status Context**: Shows blocker task statuses
        - **Workflow Insights**: Helps prioritize unblocking actions

        ## Use Cases
        - **Daily Standup**: "What tasks are blocked today?"
        - **Sprint Planning**: Identify tasks that can't start yet
        - **Bottleneck Analysis**: Find what's blocking multiple tasks
        - **Priority Setting**: Focus on unblocking critical paths
        - **Team Coordination**: Know which tasks need other teams' work

        ## Usage Examples

        **Find All Blocked Tasks**:
        ```json
        {}
        ```

        **Blocked Tasks in Specific Project**:
        ```json
        {
          "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039"
        }
        ```

        **Blocked Tasks in Specific Feature**:
        ```json
        {
          "featureId": "6b787bca-2ca2-461c-90f4-25adf53e0aa0"
        }
        ```

        **Include All Task Details**:
        ```json
        {
          "includeTaskDetails": true
        }
        ```

        ## Output Format

        Returns list of blocked tasks with:
        - **Basic Info**: Task ID, title, status, priority, complexity
        - **Blocking Info**: List of tasks blocking this task
        - **Blocker Details**: ID, title, status of each blocker
        - **Block Count**: Number of incomplete blockers

        ## Filtering Options

        **projectId**: Only show blocked tasks in this project
        **featureId**: Only show blocked tasks in this feature
        **includeTaskDetails**: Include full task metadata (default: false for efficiency)

        ## AI Usage Patterns

        **Daily Workflow Check**:
        ```
        User: "What tasks are blocked?"
        AI: get_blocked_tasks
        → Shows tasks that can't progress
        → AI suggests focusing on unblocking tasks
        ```

        **Sprint Planning**:
        ```
        User: "What can we start next sprint?"
        AI:
        1. get_blocked_tasks (see what's blocked)
        2. search_tasks --status pending (see what's ready)
        3. Recommend unblocked, high-priority tasks
        ```

        **Bottleneck Identification**:
        ```
        User: "Why is nothing moving forward?"
        AI:
        1. get_blocked_tasks
        2. Analyze which blocker tasks appear most often
        3. Suggest prioritizing those blocker tasks
        ```

        ## Response Example

        ```json
        {
          "success": true,
          "data": {
            "blockedTasks": [
              {
                "taskId": "task-uuid-1",
                "title": "Implement user dashboard",
                "status": "pending",
                "priority": "high",
                "complexity": 7,
                "blockedBy": [
                  {
                    "taskId": "blocker-uuid-1",
                    "title": "Design dashboard mockups",
                    "status": "in-progress",
                    "priority": "high"
                  },
                  {
                    "taskId": "blocker-uuid-2",
                    "title": "Create API endpoints",
                    "status": "pending",
                    "priority": "medium"
                  }
                ],
                "blockerCount": 2
              }
            ],
            "totalBlocked": 1
          }
        }
        ```

        ## Performance Notes

        - Uses efficient dependency queries
        - Only checks active tasks (pending/in-progress)
        - Optional task details reduce payload size
        - Suitable for frequent polling
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
