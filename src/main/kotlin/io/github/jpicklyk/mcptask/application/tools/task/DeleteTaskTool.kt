package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for deleting a task by its ID.
 */
class DeleteTaskTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "delete_task"

    override val description: String = "Deletes a task by its ID"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the task to delete"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "hardDelete" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to permanently delete the task (true) or soft delete it (false)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "cascade" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to also delete subtasks"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "force" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to delete even if other tasks depend on this one"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "deleteSections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to also delete associated sections"),
                        "default" to JsonPrimitive(true)
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required ID parameter
        val idStr = requireString(params, "id")

        // Validate ID format (must be a valid UUID)
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid task ID format. Must be a valid UUID.")
        }

        // Optional boolean parameters don't need validation as they default to false/true
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing delete_task tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val taskId = UUID.fromString(idStr)

            // Verify task exists before attempting to delete
            val getResult = context.taskRepository().getById(taskId)

            if (getResult is Result.Error) {
                if (getResult.error is RepositoryError.NotFound) {
                    // Task not found, return standardized error response
                    return errorResponse(
                        message = "Task not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No task exists with ID $taskId"
                    )
                } else {
                    // Other database error
                    return errorResponse(
                        message = "Failed to retrieve task",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = getResult.error.toString()
                    )
                }
            }

            // Delete associated sections if requested
            var sectionsDeletedCount = 0

            // Get sections for this task
            val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.TASK, taskId)

            if (sectionsResult is Result.Success) {
                sectionsDeletedCount = sectionsResult.data.size

                // Delete each section
                sectionsResult.data.forEach { section ->
                    context.sectionRepository().deleteSection(section.id)
                }
                logger.info("Deleted $sectionsDeletedCount sections for task $taskId")
            }

            // Delete the task
            val deleteResult = context.taskRepository().delete(taskId)

            // Return standardized response
            return when (deleteResult) {
                is Result.Success -> {
                    val data = buildJsonObject {
                        put("id", taskId.toString())
                        put("deleted", true)
                        put("sectionsDeleted", sectionsDeletedCount)
                    }

                    successResponse(data, "Task deleted successfully")
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to delete task",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = deleteResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error deleting task", e)
            return errorResponse(
                message = "Failed to delete task",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}