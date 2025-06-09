package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
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

    override val description: String = "Updates an existing task with the specified properties"

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
                        "description" to JsonPrimitive("The title of the task")
                    )
                ),
                "description" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Detailed description of the task")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Task status (pending, in-progress, completed, cancelled, deferred)"),
                        "enum" to JsonArray(TaskStatus.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Task priority (high, medium, low)"),
                        "enum" to JsonArray(Priority.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "complexity" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Task complexity on a scale from 1-10"),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(10)
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Optional ID of the feature this task belongs to"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of tags")
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

        try {
            // Extract task ID
            val idStr = requireString(params, "id")
            val taskId = UUID.fromString(idStr)

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
            val description = optionalString(params, "description") ?: existingTask.summary

            val statusStr = optionalString(params, "status")
            val status = if (statusStr != null) parseStatus(statusStr) else existingTask.status

            val priorityStr = optionalString(params, "priority")
            val priority = if (priorityStr != null) parsePriority(priorityStr) else existingTask.priority

            val complexity = optionalInt(params, "complexity") ?: existingTask.complexity

            val featureId = optionalString(params, "featureId")?.let {
                if (it.isEmpty()) null else UUID.fromString(it)
            } ?: existingTask.featureId

            val tags = optionalString(params, "tags")?.let {
                if (it.isEmpty()) emptyList()
                else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
            } ?: existingTask.tags

            // Create an updated task entity
            val updatedTask = existingTask.copy(
                title = title,
                summary = description,
                status = status,
                priority = priority,
                complexity = complexity,
                featureId = featureId,
                tags = tags,
                modifiedAt = Instant.now() // Always update modification time
            )

            // Save updated task to repository
            val updateResult = context.taskRepository().update(updatedTask)

            // Return standardized response
            return handleRepositoryResult(updateResult, "Task updated successfully") { updatedTaskData ->
                buildJsonObject {
                    put("id", updatedTaskData.id.toString())
                    put("title", updatedTaskData.title)
                    put("summary", updatedTaskData.summary)
                    put("status", updatedTaskData.status.name.lowercase())
                    put("priority", updatedTaskData.priority.name.lowercase())
                    put("complexity", updatedTaskData.complexity)
                    put("createdAt", updatedTaskData.createdAt.toString())
                    put("modifiedAt", updatedTaskData.modifiedAt.toString())

                    if (updatedTaskData.featureId != null) {
                        put("featureId", updatedTaskData.featureId.toString())
                    } else {
                        put("featureId", JsonNull)
                    }

                    put("tags", buildJsonArray {
                        updatedTaskData.tags.forEach { add(it) }
                    })
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error updating task", e)
            throw e // Let SimpleLockAwareToolDefinition handle the error formatting
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