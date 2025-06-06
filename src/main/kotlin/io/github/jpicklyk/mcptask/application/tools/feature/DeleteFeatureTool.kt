package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for deleting features with options for handling associated tasks.
 */
class DeleteFeatureTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.FEATURE_MANAGEMENT

    override val name: String = "delete_feature"

    override val description: String = "Remove a feature and its associated tasks"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the feature to delete")
                    )
                ),
                "hardDelete" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to completely remove the feature from the database"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "cascade" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to delete tasks associated with this feature"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "force" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to force deletion even with active tasks"),
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

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing delete_feature tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val featureId = UUID.fromString(idStr)
            val hardDelete = optionalBoolean(params, "hardDelete", false)
            val cascade = optionalBoolean(params, "cascade", false)
            val force = optionalBoolean(params, "force", false)

            // Check if the feature exists
            val featureResult = context.featureRepository().getById(featureId)

            when (featureResult) {
                is Result.Error -> {
                    if (featureResult.error is RepositoryError.NotFound) {
                        return errorResponse(
                            message = "Feature not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No feature exists with ID $featureId"
                        )
                    } else {
                        return errorResponse(
                            message = "Failed to retrieve feature",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = featureResult.error.toString()
                        )
                    }
                }

                is Result.Success -> {
                    // Feature exists, continue with deletion process
                }
            }

            // Check for associated tasks
            val tasksResult = context.taskRepository().findByFeature(featureId)

            val tasks = when (tasksResult) {
                is Result.Success -> tasksResult.data
                is Result.Error -> {
                    logger.warn("Error retrieving tasks for feature $featureId: ${tasksResult.error}")
                    emptyList() // Assume no tasks if we can't retrieve them
                }
            }

            // If there are tasks, and we're not forcing or cascading, prevent deletion
            if (tasks.isNotEmpty() && !force && !cascade) {
                return errorResponse(
                    message = "Cannot delete feature with associated tasks",
                    code = ErrorCodes.DEPENDENCY_ERROR,
                    details = "Feature with ID $featureId has ${tasks.size} associated tasks. " +
                            "Use 'force=true' to delete anyway, or 'cascade=true' to delete tasks as well."
                )
            }

            // If cascading, delete associated tasks
            if (cascade && tasks.isNotEmpty()) {
                var failedDeletes = 0
                
                for (task in tasks) {
                    val deleteResult = context.taskRepository().delete(task.id)
                    if (deleteResult is Result.Error) {
                        logger.warn("Failed to delete task ${task.id}: ${deleteResult.error}")
                        failedDeletes++
                    }
                }

                if (failedDeletes > 0) {
                    logger.warn("Failed to delete $failedDeletes out of ${tasks.size} tasks")
                }
            }

            // Delete the feature
            // TODO: Implement soft delete mechanism when supported
            // For now, we'll do a hard delete in both cases
            val deleteResult = context.featureRepository().delete(featureId)

            return when (deleteResult) {
                is Result.Success -> {
                    if (!deleteResult.data) {
                        errorResponse(
                            message = "Failed to delete feature",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = "Operation returned false"
                        )
                    } else {
                        // Create a response with deletion info
                        val responseData = buildJsonObject {
                            put("id", featureId.toString())
                            put("deleteType", if (hardDelete) "hard" else "soft")
                            put("cascaded", cascade && tasks.isNotEmpty())
                            put("tasksAffected", if (cascade) tasks.size else 0)
                        }

                        // Create an appropriate success message
                        val message = when {
                            cascade && tasks.isNotEmpty() -> "Feature deleted with ${tasks.size} associated tasks"
                            else -> "Feature deleted successfully"
                        }

                        successResponse(
                            data = responseData,
                            message = message
                        )
                    }
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to delete feature",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = deleteResult.error.toString()
                    )
                }
            }
        } catch (e: ToolValidationException) {
            // Handle validation errors
            logger.warn("Validation error deleting feature: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            // Handle unexpected errors
            logger.error("Error deleting feature", e)
            return errorResponse(
                message = "Failed to delete feature",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}