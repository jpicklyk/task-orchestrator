package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.UpdateEfficiencyMetrics
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * MCP tool for updating an existing task with the specified properties.
 */
class UpdateTaskTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "update_task"

    override val title: String = "Update Task"

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
                        "description" to JsonPrimitive("The updated task object"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("uuid")
                                    )
                                ),
                                "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "status" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("pending", "in-progress", "completed", "cancelled", "deferred").map { JsonPrimitive(it) })
                                    )
                                ),
                                "priority" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("high", "medium", "low").map { JsonPrimitive(it) })
                                    )
                                ),
                                "complexity" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "minimum" to JsonPrimitive(1),
                                        "maximum" to JsonPrimitive(10)
                                    )
                                ),
                                "createdAt" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("date-time")
                                    )
                                ),
                                "modifiedAt" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("date-time")
                                    )
                                ),
                                "featureId" to JsonObject(
                                    mapOf(
                                        "type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))),
                                        "format" to JsonPrimitive("uuid")
                                    )
                                ),
                                "tags" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                )
                            )
                        ),
                        "required" to JsonArray(
                            listOf("id", "title", "summary", "status", "priority", "complexity", "createdAt", "modifiedAt", "tags").map { JsonPrimitive(it) }
                        )
                    )
                )
            )
        ),
        required = listOf("success", "message")
    )

    override fun shouldUseLocking(): Boolean = true

    override val description: String = """Updates task properties. Only send fields you want to change.

        Parameters:
        | Field | Type | Required | Description |
        | id | UUID | Yes | Task identifier |
        | title | string | No | New title |
        | summary | string | No | New summary (max 500 chars) |
        | description | string | No | New detailed description |
        | status | enum | No | pending, in-progress, completed, cancelled, deferred |
        | priority | enum | No | high, medium, low |
        | complexity | integer | No | Complexity rating (1-10) |
        | featureId | UUID | No | New feature association (or null for orphaned) |
        | tags | string | No | Comma-separated tags (replaces entire set) |

        Related: create_task, get_task, delete_task, bulk_update_tasks
        Docs: task-orchestrator://docs/tools/update-task
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the task to update"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New title for the task")
                    )
                ),
                "description" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) Detailed description of what needs to be done (user-provided)")
                    )
                ),
                "summary" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) Brief summary of what was accomplished (agent-generated, max 500 chars)")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New task status (pending, in-progress, completed, cancelled, deferred)"),
                        "enum" to JsonArray(TaskStatus.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New task priority (high, medium, low)"),
                        "enum" to JsonArray(Priority.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "complexity" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("(optional) New task complexity on a scale from 1-10"),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(10)
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New feature ID to associate this task with"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New comma-separated list of tags")
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required ID parameter
        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid task ID format. Must be a valid UUID.")
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

        optionalString(params, "dueDate")?.let { dueDate ->
            if (dueDate.isNotEmpty()) {
                try {
                    Instant.parse(dueDate)
                } catch (_: Exception) {
                    throw ToolValidationException("Invalid due date format. Use ISO-8601 format (e.g., 2025-05-10T14:30:00Z)")
                }
            }
        }

        optionalString(params, "estimatedEffort")?.let { effort ->
            if (effort.isNotEmpty()) {
                try {
                    Duration.parse(effort)
                } catch (_: Exception) {
                    throw ToolValidationException("Invalid estimated effort format. Use ISO-8601 duration format (e.g., PT4H30M)")
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing update_task tool")

        return try {
            // Extract task ID
            val taskId = extractEntityId(params, "id")

            // Execute with proper locking
            executeWithLocking("update_task", EntityType.TASK, taskId) {
                executeTaskUpdate(params, context, taskId)
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error updating task: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error updating task", e)
            errorResponse(
                message = "Failed to update task",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Executes the actual task update business logic.
     */
    private suspend fun executeTaskUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        taskId: UUID
    ): JsonElement {
        // Analyze update efficiency
        val efficiencyMetrics = UpdateEfficiencyMetrics.analyzeUpdate("update_task", params)

        // Get an existing task from repository
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

        // Validate that referenced feature exists if featureId is being set/changed
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

        // Create an updated task entity
        val updatedTask = existingTask.copy(
            title = title,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            complexity = complexity,
            featureId = featureId,
            tags = tags,
            modifiedAt = Instant.now() // Always update modification time
        )

        // Save updated task to repository
        val updateResult = context.taskRepository().update(updatedTask)

        // Build response message with efficiency guidance
        val efficiencyLevel = efficiencyMetrics["efficiencyLevel"]?.jsonPrimitive?.content
        val efficiencyGuidance = efficiencyMetrics["guidance"]?.jsonPrimitive?.content ?: ""
        val baseMessage = "Task updated successfully"
        val message = if (efficiencyLevel == "inefficient") {
            "$baseMessage. ⚠️ $efficiencyGuidance"
        } else {
            baseMessage
        }

        // Return minimal response to optimize bandwidth and performance
        // Only return essential fields: id (to identify what was updated),
        // status (current state), and modifiedAt (timestamp of update)
        return handleRepositoryResult(updateResult, message) { updatedTaskData ->
            buildJsonObject {
                put("id", updatedTaskData.id.toString())
                put("status", updatedTaskData.status.name.lowercase().replace('_', '-'))
                put("modifiedAt", updatedTaskData.modifiedAt.toString())
            }
        }
    }

    /**
     * Checks if a string is a valid task status.
     *
     * @param status The status string to check
     * @return true if the status is valid, false otherwise
     */
    private fun isValidStatus(status: String): Boolean {
        return try {
            parseStatus(status)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses a string into a TaskStatus enum.
     *
     * @param status The status string to parse
     * @return The corresponding TaskStatus enum value
     * @throws IllegalArgumentException If the status string is invalid
     */
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

    /**
     * Checks if a string is a valid priority level.
     *
     * @param priority The priority string to check
     * @return true if the priority is valid, false otherwise
     */
    private fun isValidPriority(priority: String): Boolean {
        return try {
            parsePriority(priority)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses a string into a Priority enum.
     *
     * @param priority The priority string to parse
     * @return The corresponding Priority enum value
     * @throws IllegalArgumentException If the priority string is invalid
     */
    private fun parsePriority(priority: String): Priority {
        return when (priority.lowercase()) {
            "high" -> Priority.HIGH
            "medium", "med" -> Priority.MEDIUM
            "low" -> Priority.LOW
            else -> throw IllegalArgumentException("Invalid priority: $priority")
        }
    }
}