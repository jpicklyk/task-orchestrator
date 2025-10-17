package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for retrieving all tasks associated with a specific feature.
 */
class GetFeatureTasksTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.FEATURE_MANAGEMENT

    override val name: String = "get_feature_tasks"

    override val title: String = "Get Feature Tasks"

    override val description: String = """Retrieve all tasks associated with a specific feature.

For detailed examples and patterns: task-orchestrator://docs/tools/get-feature-tasks
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the feature to retrieve tasks for")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by task status (pending, in-progress, completed, cancelled, deferred)")
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by priority (high, medium, low)")
                    )
                ),
                "complexityMin" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Filter by minimum complexity (1-10)"),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(10)
                    )
                ),
                "complexityMax" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Filter by maximum complexity (1-10)"),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(10)
                    )
                ),
                "sortBy" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Sort results by this field (createdAt, modifiedAt, title, status, priority, complexity)"),
                        "default" to JsonPrimitive("modifiedAt")
                    )
                ),
                "sortDirection" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Sort direction (asc, desc)"),
                        "default" to JsonPrimitive("desc")
                    )
                ),
                "limit" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum number of results to return"),
                        "default" to JsonPrimitive(20),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(100)
                    )
                ),
                "offset" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Number of results to skip (for pagination)"),
                        "default" to JsonPrimitive(0),
                        "minimum" to JsonPrimitive(0)
                    )
                )
            )
        ),
        required = listOf("featureId")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val idStr = requireString(params, "featureId")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid featureId format. Must be a valid UUID")
        }

        // Validate status if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status. Must be one of: pending, in-progress, completed, cancelled, deferred")
            }
        }

        // Validate priority if present
        optionalString(params, "priority")?.let { priority ->
            if (!isValidPriority(priority)) {
                throw ToolValidationException("Invalid priority: $priority. Must be one of: high, medium, low")
            }
        }

        // Validate complexity range
        val minComplexity = optionalInt(params, "complexityMin")
        val maxComplexity = optionalInt(params, "complexityMax")

        if (minComplexity != null && maxComplexity != null) {
            if (minComplexity > maxComplexity) {
                throw ToolValidationException("complexityMin cannot be greater than complexityMax")
            }
        }

        // Validate sortBy
        optionalString(params, "sortBy")?.let { sortBy ->
            val validSortFields = setOf("createdAt", "modifiedAt", "title", "status", "priority", "complexity")
            if (sortBy !in validSortFields) {
                throw ToolValidationException(
                    "Invalid sortBy value: $sortBy. Must be one of: ${
                        validSortFields.joinToString(
                            ", "
                        )
                    }"
                )
            }
        }

        // Validate sortDirection
        optionalString(params, "sortDirection")?.let { sortDirection ->
            val validDirections = setOf("asc", "desc")
            if (sortDirection !in validDirections) {
                throw ToolValidationException(
                    "Invalid sortDirection value: $sortDirection. Must be one of: ${
                        validDirections.joinToString(
                            ", "
                        )
                    }"
                )
            }
        }

        // Validate limit
        optionalInt(params, "limit")?.let { limit ->
            if (limit < 1) {
                throw ToolValidationException("limit must be at least 1")
            }
            if (limit > 100) {
                throw ToolValidationException("limit cannot exceed 100")
            }
        }

        // Validate offset
        optionalInt(params, "offset")?.let { offset ->
            if (offset < 0) {
                throw ToolValidationException("offset must be at least 0")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_feature_tasks tool")

        try {
            // Extract parameters
            val featureIdStr = requireString(params, "featureId")
            val featureId = UUID.fromString(featureIdStr)
            val statusStr = optionalString(params, "status")
            val priorityStr = optionalString(params, "priority")
            val complexityMin = optionalInt(params, "complexityMin")
            val complexityMax = optionalInt(params, "complexityMax")
            val sortBy = optionalString(params, "sortBy", "modifiedAt")
            val sortDirection = optionalString(params, "sortDirection", "desc")
            val limit = optionalInt(params, "limit", 20)!!
            val offset = optionalInt(params, "offset", 0)!!

            // Convert string parameters to appropriate types
            val status = statusStr?.let { parseStatus(it) }
            val priority = priorityStr?.let { parsePriority(it) }

            // Check if the feature exists
            val featureResult = context.featureRepository().getById(featureId)

            when (featureResult) {
                is Result.Success -> {
                    val feature = featureResult.data

                    // Get all tasks for this feature
                    val tasksResult = context.taskRepository().findByFeature(featureId)

                    when (tasksResult) {
                        is Result.Success -> {
                            val allTasks = tasksResult.data

                            // Apply filters
                            var filteredTasks = allTasks

                            // Status filter
                            if (status != null) {
                                filteredTasks = filteredTasks.filter { it.status == status }
                            }

                            // Priority filter
                            if (priority != null) {
                                filteredTasks = filteredTasks.filter { it.priority == priority }
                            }

                            // Complexity range filter
                            if (complexityMin != null) {
                                filteredTasks = filteredTasks.filter { it.complexity >= complexityMin }
                            }

                            if (complexityMax != null) {
                                filteredTasks = filteredTasks.filter { it.complexity <= complexityMax }
                            }

                            // Apply sorting
                            filteredTasks = when (sortBy) {
                                "title" -> if (sortDirection == "asc") filteredTasks.sortedBy { it.title } else filteredTasks.sortedByDescending { it.title }
                                "status" -> if (sortDirection == "asc") filteredTasks.sortedBy { it.status } else filteredTasks.sortedByDescending { it.status }
                                "priority" -> if (sortDirection == "asc") filteredTasks.sortedBy { it.priority } else filteredTasks.sortedByDescending { it.priority }
                                "complexity" -> if (sortDirection == "asc") filteredTasks.sortedBy { it.complexity } else filteredTasks.sortedByDescending { it.complexity }
                                "createdAt" -> if (sortDirection == "asc") filteredTasks.sortedBy { it.createdAt } else filteredTasks.sortedByDescending { it.createdAt }
                                else -> if (sortDirection == "asc") filteredTasks.sortedBy { it.modifiedAt } else filteredTasks.sortedByDescending { it.modifiedAt }
                            }

                            // Apply pagination
                            val totalCount = filteredTasks.size
                            val paginatedTasks = filteredTasks.drop(offset).take(limit)

                            // Create task items
                            val taskItems = buildJsonArray {
                                paginatedTasks.forEach { task ->
                                    add(buildJsonObject {
                                        put("id", task.id.toString())
                                        put("title", task.title)

                                        // Truncate summary if needed
                                        if (task.summary.length > 100) {
                                            put("summary", "${task.summary.take(97)}...")
                                        } else {
                                            put("summary", task.summary)
                                        }

                                        put("status", task.status.name.lowercase().replace('_', '-'))
                                        put("priority", task.priority.name.lowercase())
                                        put("complexity", task.complexity)
                                        put("createdAt", task.createdAt.toString())
                                        put("modifiedAt", task.modifiedAt.toString())

                                        // Include tags if present
                                        if (task.tags.isNotEmpty()) {
                                            put("tags", task.tags.joinToString(", "))
                                        }
                                    })
                                }
                            }

                            // Create the response data object with the format expected by the tests
                            val responseData = buildJsonObject {
                                put("featureId", feature.id.toString())
                                put("featureName", feature.name)
                                put("items", taskItems)
                                put("total", totalCount)
                                put("limit", limit)
                                put("offset", offset)
                                put("hasMore", offset + paginatedTasks.size < totalCount)
                            }

                            // Create message based on results
                            val message = when {
                                paginatedTasks.isEmpty() -> "No tasks found for feature: ${feature.name}"
                                paginatedTasks.size == 1 -> "Found 1 task for feature: ${feature.name}"
                                else -> "Found ${paginatedTasks.size} tasks for feature: ${feature.name}"
                            }

                            return successResponse(responseData, message)
                        }

                        is Result.Error -> {
                            return errorResponse(
                                message = "Failed to retrieve tasks for feature",
                                code = ErrorCodes.DATABASE_ERROR,
                                details = tasksResult.error.toString()
                            )
                        }
                    }
                }

                is Result.Error -> {
                    if (featureResult.error is RepositoryError.NotFound) {
                        return errorResponse(
                            message = "Feature not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No feature exists with ID $featureId"
                        )
                    } else {
                        return errorResponse(
                            message = "Error retrieving feature",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = featureResult.error.toString()
                        )
                    }
                }
            }
        } catch (e: ToolValidationException) {
            // Handle validation errors
            logger.warn("Validation error retrieving feature tasks: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            // Handle unexpected errors
            logger.error("Error retrieving feature tasks", e)
            return errorResponse(
                message = "Failed to retrieve feature tasks",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
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