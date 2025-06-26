package io.github.jpicklyk.mcptask.application.tools.project

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
 * MCP tool for deleting projects with options for handling associated features and tasks.
 */
class DeleteProjectTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT

    override val name: String = "delete_project"
    
    override fun shouldUseLocking(): Boolean = true

    override val description: String = "Remove a project and its associated features/tasks"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the project to delete")
                    )
                ),
                "hardDelete" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to permanently remove the project from the database"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "cascade" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to delete all features and tasks associated with this project"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "force" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to force deletion even with active features or tasks"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid id format. Must be a valid UUID")
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing delete_project tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val projectId = UUID.fromString(idStr)
            val hardDelete = optionalBoolean(params, "hardDelete", false)
            val cascade = optionalBoolean(params, "cascade", false)
            val force = optionalBoolean(params, "force", false)

            // Check for operation conflicts before proceeding
            checkOperationPermissions("delete_project", EntityType.PROJECT, projectId)?.let { lockError ->
                return lockError
            }

            // Check if the project exists
            val projectResult = context.projectRepository().getById(projectId)

            when (projectResult) {
                is Result.Error -> {
                    if (projectResult.error is RepositoryError.NotFound) {
                        return errorResponse(
                            message = "Project not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No project exists with ID $projectId"
                        )
                    } else {
                        return errorResponse(
                            message = "Failed to retrieve project",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = projectResult.error.toString()
                        )
                    }
                }

                is Result.Success -> {
                    // Project exists, continue with deletion process
                }
            }

            // Check for associated features
            val featuresResult = context.featureRepository().findByProject(
                projectId = projectId,
                limit = 20,
            )

            val features = when (featuresResult) {
                is Result.Success -> featuresResult.data
                is Result.Error -> {
                    logger.warn("Error retrieving features for project $projectId: ${featuresResult.error}")
                    emptyList() // Assume no features if we can't retrieve them
                }
            }

            // Check for associated tasks (directly associated with project, not through features)
            val tasksResult = context.taskRepository().findByProject(
                projectId = projectId,
                limit = 20,
            )

            val directTasks = when (tasksResult) {
                is Result.Success -> tasksResult.data
                is Result.Error -> {
                    logger.warn("Error retrieving tasks for project $projectId: ${tasksResult.error}")
                    emptyList() // Assume no tasks if we can't retrieve them
                }
            }

            // If there are features or tasks, and we're not forcing or cascading, prevent deletion
            if ((features.isNotEmpty() || directTasks.isNotEmpty()) && !force && !cascade) {
                return errorResponse(
                    message = "Cannot delete project with associated features or tasks",
                    code = ErrorCodes.DEPENDENCY_ERROR,
                    details = "Project with ID $projectId has ${features.size} associated features and " +
                            "${directTasks.size} directly associated tasks. " +
                            "Use 'force=true' to delete anyway, or 'cascade=true' to delete associated entities as well."
                )
            }

            // If cascading, delete associated tasks and features
            val featureTasksMap = mutableMapOf<UUID, List<UUID>>()
            var totalTasksDeleted = 0
            var totalFeaturesDeleted = 0
            var failedTaskDeletes = 0
            var failedFeatureDeletes = 0

            if (cascade) {
                // First collect all tasks associated with features
                for (feature in features) {
                    val featureTasksResult = context.taskRepository().findByFeature(feature.id)
                    val featureTasks = when (featureTasksResult) {
                        is Result.Success -> featureTasksResult.data
                        is Result.Error -> {
                            logger.warn("Error retrieving tasks for feature ${feature.id}: ${featureTasksResult.error}")
                            emptyList()
                        }
                    }

                    featureTasksMap[feature.id] = featureTasks.map { it.id }
                }

                // Delete all tasks associated with features
                for ((featureId, taskIds) in featureTasksMap) {
                    for (taskId in taskIds) {
                        val deleteResult = context.taskRepository().delete(taskId)
                        if (deleteResult is Result.Error) {
                            logger.warn("Failed to delete feature linked task $taskId: ${deleteResult.error}")
                            failedTaskDeletes++
                        } else {
                            totalTasksDeleted++
                        }
                    }
                }

                // Delete directly associated tasks
                for (task in directTasks) {
                    val deleteResult = context.taskRepository().delete(task.id)
                    if (deleteResult is Result.Error) {
                        logger.warn("Failed to delete direct task ${task.id}: ${deleteResult.error}")
                        failedTaskDeletes++
                    } else {
                        totalTasksDeleted++
                    }
                }

                // Delete features
                for (feature in features) {
                    val deleteResult = context.featureRepository().delete(feature.id)
                    if (deleteResult is Result.Error) {
                        logger.warn("Failed to delete feature ${feature.id}: ${deleteResult.error}")
                        failedFeatureDeletes++
                    } else {
                        totalFeaturesDeleted++
                    }
                }

                if (failedTaskDeletes > 0 || failedFeatureDeletes > 0) {
                    logger.warn("Failed to delete $failedTaskDeletes tasks and $failedFeatureDeletes features during cascade operation")
                }
            }

            // Delete the project
            // TODO: Implement soft delete mechanism when supported
            // For now, we'll do a hard delete in both cases
            val deleteResult = context.projectRepository().delete(projectId)

            return when (deleteResult) {
                is Result.Success -> {
                    if (!deleteResult.data) {
                        errorResponse(
                            message = "Failed to delete project",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = "Operation returned false"
                        )
                    } else {
                        // Create a response with deletion info
                        val responseData = buildJsonObject {
                            put("id", projectId.toString())
                            put("deleteType", if (hardDelete) "hard" else "soft")
                            put("cascaded", cascade && (features.isNotEmpty() || directTasks.isNotEmpty()))
                            put("featuresAffected", totalFeaturesDeleted)
                            put("tasksAffected", totalTasksDeleted)
                        }

                        // Create an appropriate success message
                        val message = when {
                            cascade && (features.isNotEmpty() || directTasks.isNotEmpty()) ->
                                "Project deleted successfully with $totalFeaturesDeleted associated features and $totalTasksDeleted tasks"

                            else -> "Project deleted successfully"
                        }

                        successResponse(
                            data = responseData,
                            message = message
                        )
                    }
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to delete project",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = deleteResult.error.toString()
                    )
                }
            }
        } catch (e: ToolValidationException) {
            // Handle validation errors
            logger.warn("Validation error deleting project: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            // Handle unexpected errors
            logger.error("Error deleting project", e)
            return errorResponse(
                message = "Failed to delete project",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}