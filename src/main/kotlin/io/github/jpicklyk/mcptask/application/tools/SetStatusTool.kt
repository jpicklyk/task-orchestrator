package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Unified MCP tool for updating the status of any entity (task, feature, or project).
 * Auto-detects entity type from UUID, validates status based on entity type,
 * and provides smart features for tasks (dependency warnings, etc).
 */
class SetStatusTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "set_status"

    override val title: String = "Set Entity Status"

    override val description: String = """Updates the status of a task, feature, or project.

        ## Purpose
        Provides a simple, unified interface for status updates across all entity types.
        Auto-detects whether the ID belongs to a task, feature, or project and validates
        the status accordingly.

        ## Smart Features by Entity Type

        **For Tasks:**
        - Validates against task statuses: pending, in-progress, completed, cancelled, deferred
        - Warns if marking task complete while it blocks other tasks
        - Returns count of affected dependencies

        **For Features:**
        - Validates against feature statuses: planning, in-development, completed, archived
        - Updates modification timestamp

        **For Projects:**
        - Validates against project statuses: planning, in-development, completed, archived
        - Updates modification timestamp

        ## Usage Examples

        **Update Task Status:**
        ```json
        {
          "id": "640522b7-810e-49a2-865c-3725f5d39608",
          "status": "completed"
        }
        ```

        **Update Feature Status:**
        ```json
        {
          "id": "6b787bca-2ca2-461c-90f4-25adf53e0aa0",
          "status": "in-development"
        }
        ```

        **Update Project Status:**
        ```json
        {
          "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
          "status": "completed"
        }
        ```

        ## Benefits Over update_task/update_feature/update_project
        - **Simpler**: Only 2 parameters (id + status) instead of many optional fields
        - **More efficient**: Saves tokens by not requiring entity type parameter
        - **Smart validation**: Automatically validates based on detected entity type
        - **Task-aware**: Provides dependency warnings for tasks

        ## Error Handling
        - RESOURCE_NOT_FOUND: ID doesn't exist in any entity table
        - VALIDATION_ERROR: Invalid status for the detected entity type
        - DATABASE_ERROR: Issue persisting the update
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the entity (task, feature, or project) to update"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New status value (validated based on entity type)")
                    )
                )
            )
        ),
        required = listOf("id", "status")
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
                        "description" to JsonPrimitive("Status update result"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("uuid")
                                    )
                                ),
                                "entityType" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("TASK", "FEATURE", "PROJECT").map { JsonPrimitive(it) })
                                    )
                                ),
                                "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "modifiedAt" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("date-time")
                                    )
                                ),
                                "blockingTasksCount" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("For tasks only: number of tasks blocked by this task")
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
        // Validate UUID format
        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid ID format. Must be a valid UUID.")
        }

        // Validate status is provided (specific validation happens after entity type detection)
        requireString(params, "status")
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing set_status tool")

        return try {
            val id = UUID.fromString(requireString(params, "id"))
            val statusStr = requireString(params, "status")

            // Auto-detect entity type and update accordingly
            detectAndUpdateStatus(id, statusStr, context)
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in set_status: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in set_status", e)
            errorResponse(
                message = "Failed to update status",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Detects entity type by trying to find the ID in each repository,
     * then updates the status accordingly.
     */
    private suspend fun detectAndUpdateStatus(
        id: UUID,
        statusStr: String,
        context: ToolExecutionContext
    ): JsonElement {
        // Try task repository first (most common case)
        context.taskRepository().getById(id).let { result ->
            if (result is Result.Success) {
                return updateTaskStatus(result.data, statusStr, context)
            }
        }

        // Try feature repository
        context.repositoryProvider.featureRepository().getById(id).let { result ->
            if (result is Result.Success) {
                return updateFeatureStatus(result.data, statusStr, context)
            }
        }

        // Try project repository
        context.repositoryProvider.projectRepository().getById(id).let { result ->
            if (result is Result.Success) {
                return updateProjectStatus(result.data, statusStr, context)
            }
        }

        // Entity not found in any repository
        return errorResponse(
            message = "Entity not found",
            code = ErrorCodes.RESOURCE_NOT_FOUND,
            details = "No task, feature, or project exists with ID $id"
        )
    }

    /**
     * Updates a task's status with smart dependency checking.
     */
    private suspend fun updateTaskStatus(
        task: Task,
        statusStr: String,
        context: ToolExecutionContext
    ): JsonElement {
        // Parse and validate task status
        val status = try {
            parseTaskStatus(statusStr)
        } catch (e: IllegalArgumentException) {
            return errorResponse(
                message = "Invalid task status: $statusStr",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Valid task statuses: pending, in-progress, completed, cancelled, deferred"
            )
        }

        // Check for blocking dependencies if marking task complete
        var blockingCount = 0
        if (status == TaskStatus.COMPLETED) {
            val blockingDeps = context.repositoryProvider.dependencyRepository().findByFromTaskId(task.id)
            blockingCount = blockingDeps.size
        }

        // Update task
        val updatedTask = task.copy(
            status = status,
            modifiedAt = Instant.now()
        )

        val updateResult = context.taskRepository().update(updatedTask)

        return when (updateResult) {
            is Result.Success -> {
                successResponse(
                    data = buildJsonObject {
                        put("id", updateResult.data.id.toString())
                        put("entityType", "TASK")
                        put("status", updateResult.data.status.name.lowercase().replace('_', '-'))
                        put("modifiedAt", updateResult.data.modifiedAt.toString())
                        if (blockingCount > 0) {
                            put("blockingTasksCount", blockingCount)
                            put("warning", "This task blocks $blockingCount other task(s)")
                        }
                    },
                    message = "Task status updated successfully"
                )
            }
            is Result.Error -> {
                errorResponse(
                    message = "Failed to update task status",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = updateResult.error.message
                )
            }
        }
    }

    /**
     * Updates a feature's status.
     */
    private suspend fun updateFeatureStatus(
        feature: Feature,
        statusStr: String,
        context: ToolExecutionContext
    ): JsonElement {
        // Parse and validate feature status
        val status = try {
            parseFeatureStatus(statusStr)
        } catch (e: IllegalArgumentException) {
            return errorResponse(
                message = "Invalid feature status: $statusStr",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Valid feature statuses: planning, in-development, completed, archived"
            )
        }

        // Update feature
        val updatedFeature = feature.update(status = status)
        val updateResult = context.repositoryProvider.featureRepository().update(updatedFeature)

        return when (updateResult) {
            is Result.Success -> {
                successResponse(
                    data = buildJsonObject {
                        put("id", updateResult.data.id.toString())
                        put("entityType", "FEATURE")
                        put("status", updateResult.data.status.name.lowercase().replace('_', '-'))
                        put("modifiedAt", updateResult.data.modifiedAt.toString())
                    },
                    message = "Feature status updated successfully"
                )
            }
            is Result.Error -> {
                errorResponse(
                    message = "Failed to update feature status",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = updateResult.error.message
                )
            }
        }
    }

    /**
     * Updates a project's status.
     */
    private suspend fun updateProjectStatus(
        project: Project,
        statusStr: String,
        context: ToolExecutionContext
    ): JsonElement {
        // Parse and validate project status
        val status = try {
            parseProjectStatus(statusStr)
        } catch (e: IllegalArgumentException) {
            return errorResponse(
                message = "Invalid project status: $statusStr",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Valid project statuses: planning, in-development, completed, archived"
            )
        }

        // Update project
        val updatedProject = project.update(status = status)
        val updateResult = context.repositoryProvider.projectRepository().update(updatedProject)

        return when (updateResult) {
            is Result.Success -> {
                successResponse(
                    data = buildJsonObject {
                        put("id", updateResult.data.id.toString())
                        put("entityType", "PROJECT")
                        put("status", updateResult.data.status.name.lowercase().replace('_', '-'))
                        put("modifiedAt", updateResult.data.modifiedAt.toString())
                    },
                    message = "Project status updated successfully"
                )
            }
            is Result.Error -> {
                errorResponse(
                    message = "Failed to update project status",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = updateResult.error.message
                )
            }
        }
    }

    /**
     * Parses a string into a TaskStatus enum.
     */
    private fun parseTaskStatus(status: String): TaskStatus {
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
     * Parses a string into a FeatureStatus enum.
     */
    private fun parseFeatureStatus(status: String): FeatureStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> FeatureStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> FeatureStatus.IN_DEVELOPMENT
            "completed" -> FeatureStatus.COMPLETED
            "archived" -> FeatureStatus.ARCHIVED
            else -> throw IllegalArgumentException("Invalid status: $status")
        }
    }

    /**
     * Parses a string into a ProjectStatus enum.
     */
    private fun parseProjectStatus(status: String): ProjectStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> ProjectStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> ProjectStatus.IN_DEVELOPMENT
            "completed" -> ProjectStatus.COMPLETED
            "archived" -> ProjectStatus.ARCHIVED
            else -> throw IllegalArgumentException("Invalid status: $status")
        }
    }
}
