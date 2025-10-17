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

    override val description: String = """Updates an existing task with the specified properties.

        ⚡ **EFFICIENCY TIP**: Only send fields you want to change! All fields except 'id' are optional.
        Sending unchanged fields wastes 90%+ tokens. Example: To update status, send only {"id": "uuid", "status": "completed"}

        ## Purpose
        Modifies specific fields of an existing task without affecting other properties.
        Critical for task lifecycle management and maintaining accurate project state.
        
        ## Common Update Patterns
        
        **Status Progression** (typical workflow):
        1. `pending` → `in_progress` (when starting work)
        2. `in_progress` → `completed` (when finished)
        3. `pending` → `deferred` (when postponing)
        4. Any status → `cancelled` (when no longer needed)
        
        **Priority Adjustments**:
        - Increase to `high` when blockers are resolved or deadlines approach
        - Decrease to `low` when other priorities take precedence
        - Use `medium` as default for most standard work
        
        **Complexity Refinement**:
        - Increase complexity (1-10) as unknowns are discovered during implementation
        - Decrease complexity when simpler solutions are found
        - Update complexity to inform future estimation accuracy
        
        ## Workflow Integration Best Practices
        
        **Before Starting Work**:
        ```json
        {
          "id": "task-uuid",
          "status": "in_progress"
        }
        ```
        
        **When Completing Work**:
        ```json
        {
          "id": "task-uuid",
          "status": "completed"
        }
        ```
        
        **When Reassigning to Feature**:
        ```json
        {
          "id": "orphaned-task-uuid",
          "featureId": "feature-uuid"
        }
        ```
        
        **When Requirements Change**:
        ```json
        {
          "id": "task-uuid",
          "title": "Updated Task Title",
          "summary": "Updated comprehensive summary with new requirements",
          "complexity": 8
        }
        ```

        ## Efficient vs Inefficient Updates

        ❌ **INEFFICIENT** (wastes ~500+ characters):
        ```json
        {
          "id": "task-uuid",
          "title": "Existing Title",              // Unchanged - unnecessary
          "summary": "Long existing summary...",   // Unchanged - 500+ chars wasted
          "status": "completed",                   // ✓ Only this changed
          "priority": "medium",                    // Unchanged - unnecessary
          "complexity": 5,                         // Unchanged - unnecessary
          "tags": "tag1,tag2,tag3"                // Unchanged - unnecessary
        }
        ```

        ✅ **EFFICIENT** (uses ~30 characters):
        ```json
        {
          "id": "task-uuid",
          "status": "completed"  // Only send what changed!
        }
        ```

        **Token Savings**: 94% reduction by only sending changed fields!

        ## Field Update Guidelines
        
        **Partial Updates**: Only specify fields you want to change. Unspecified fields remain unchanged.
        
        **Title Updates**: Keep titles concise but descriptive. Update when scope or focus changes.
        
        **Summary Updates**: Update summaries when requirements change or acceptance criteria evolve.
        
        **Tag Management**: Replace the entire tag set. To add a tag, include all existing tags plus the new one.
        
        **Feature Association**: Set featureId to associate task with a feature, or null to make orphaned.
        
        ## Locking System Integration
        This tool respects the locking system to prevent concurrent modifications.
        Updates may be queued if the task is currently locked by another operation.
        
        ## Error Handling
        - RESOURCE_NOT_FOUND: Task with specified ID doesn't exist
        - VALIDATION_ERROR: Invalid status, priority, complexity, or UUID format
        - LOCK_ERROR: Task is currently locked by another operation
        - DATABASE_ERROR: Issue persisting the update
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
        // Analyze update efficiency and log metrics
        val efficiencyMetrics = UpdateEfficiencyMetrics.analyzeUpdate("update_task", params)
        logger.debug("Update efficiency metrics: $efficiencyMetrics")

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

        // Return minimal response to optimize bandwidth and performance
        // Only return essential fields: id (to identify what was updated),
        // status (current state), and modifiedAt (timestamp of update)
        return handleRepositoryResult(updateResult, "Task updated successfully") { updatedTaskData ->
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