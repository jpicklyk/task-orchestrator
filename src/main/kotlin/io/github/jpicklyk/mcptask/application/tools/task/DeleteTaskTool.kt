package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for deleting a task by its ID with dependency cleanup and validation.
 * 
 * This tool handles cascade deletion of task dependencies and provides warnings about
 * breaking dependency chains. By default, it prevents deletion of tasks with dependencies
 * unless the 'force' parameter is used.
 */
class DeleteTaskTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "delete_task"
    
    override fun shouldUseLocking(): Boolean = true

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
                        "description" to JsonPrimitive("Whether to delete even if other tasks depend on this one. When true, breaks dependency chains and deletes all associated dependencies."),
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

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing delete_task tool")

        // Extract task ID
        val taskId = extractEntityId(params, "id")

        // Execute with proper locking
        return executeWithLocking("delete_task", EntityType.TASK, taskId) {
            executeTaskDelete(params, context, taskId)
        }
    }

    /**
     * Executes the actual task deletion business logic.
     */
    private suspend fun executeTaskDelete(
        params: JsonElement,
        context: ToolExecutionContext,
        taskId: UUID
    ): JsonElement {
        // Extract parameters
        val force = optionalBoolean(params, "force", false)
        val deleteSections = optionalBoolean(params, "deleteSections", true)

        // Verify task exists before attempting to delete
        val getResult = context.taskRepository().getById(taskId)
        when (getResult) {
            is Result.Success -> { /* Task exists, continue */ }
            is Result.Error -> return handleRepositoryResult(
                getResult,
                "Failed to retrieve task"
            ) { JsonNull }
        }

        // Check for dependencies and handle them appropriately
        val dependencies = context.dependencyRepository().findByTaskId(taskId)
        val incomingDependencies = dependencies.filter { it.toTaskId == taskId }
        val outgoingDependencies = dependencies.filter { it.fromTaskId == taskId }
        var dependenciesDeletedCount = 0

        // If there are dependencies and force is not enabled, check if deletion should proceed
        if (dependencies.isNotEmpty() && !force) {
            val dependencyInfo = buildJsonObject {
                put("totalDependencies", dependencies.size)
                put("incomingDependencies", incomingDependencies.size)
                put("outgoingDependencies", outgoingDependencies.size)
                put("affectedTasks", dependencies.map { 
                    if (it.fromTaskId == taskId) it.toTaskId else it.fromTaskId 
                }.distinct().size)
            }

            return errorResponse(
                message = "Cannot delete task with existing dependencies",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Task has ${dependencies.size} dependencies. Use 'force=true' to delete anyway and break dependency chains.",
                additionalData = dependencyInfo
            )
        }

        // Delete all dependencies for this task
        if (dependencies.isNotEmpty()) {
            dependenciesDeletedCount = context.dependencyRepository().deleteByTaskId(taskId)
            logger.info("Deleted $dependenciesDeletedCount dependencies for task $taskId")
        }

        // Delete associated sections if requested
        var sectionsDeletedCount = 0

        if (deleteSections) {
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
        }

        // Delete the task
        val deleteResult = context.taskRepository().delete(taskId)

        // Return standardized response
        val message = if (dependenciesDeletedCount > 0) {
            "Task deleted successfully with $dependenciesDeletedCount dependencies and $sectionsDeletedCount sections"
        } else {
            "Task deleted successfully"
        }

        return handleRepositoryResult(deleteResult, message) { _ ->
            buildJsonObject {
                put("id", taskId.toString())
                put("deleted", true)
                put("sectionsDeleted", sectionsDeletedCount)
                put("dependenciesDeleted", dependenciesDeletedCount)
                if (dependencies.isNotEmpty() && force) {
                    put("warningsBrokenDependencies", true)
                    put("brokenDependencyChains", buildJsonObject {
                        put("incomingDependencies", incomingDependencies.size)
                        put("outgoingDependencies", outgoingDependencies.size)
                        put("affectedTasks", dependencies.map { 
                            if (it.fromTaskId == taskId) it.toTaskId else it.fromTaskId 
                        }.distinct().size)
                    })
                }
            }
        }
    }
}