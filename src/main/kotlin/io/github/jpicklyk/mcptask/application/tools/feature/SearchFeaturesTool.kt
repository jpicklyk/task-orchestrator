package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * MCP tool for searching features based on various criteria.
 */
class SearchFeaturesTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.FEATURE_MANAGEMENT

    override val name: String = "search_features"

    override val description: String = "Find features matching specified criteria"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "query" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Text to search for in feature names and descriptions")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by feature status (planning, in-development, completed, archived)")
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by priority (high, medium, low)")
                    )
                ),
                "tag" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by tag (features containing this tag)")
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by project ID (UUID) to get only features for a specific project"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "createdAfter" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by creation date after this ISO-8601 date (e.g., 2025-05-10T14:30:00Z)")
                    )
                ),
                "createdBefore" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by creation date before this ISO-8601 date (e.g., 2025-05-10T14:30:00Z)")
                    )
                ),
                "sortBy" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Sort results by this field (createdAt, modifiedAt, name, status, priority)"),
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
        required = listOf()
    )

    override fun validateParams(params: JsonElement) {
        // Validate status if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status. Must be one of: planning, in-development, completed, archived")
            }
        }

        // Validate projectId if present
        optionalString(params, "projectId")?.let { projectId ->
            try {
                UUID.fromString(projectId)
            } catch (_: Exception) {
                throw ToolValidationException("Invalid projectId format. Must be a valid UUID")
            }
        }

        // Validate priority if present
        optionalString(params, "priority")?.let { priority ->
            if (!isValidPriority(priority)) {
                throw ToolValidationException("Invalid priority: $priority. Must be one of: high, medium, low")
            }
        }

        // Validate date parameters
        optionalString(params, "createdAfter")?.let { dateStr ->
            try {
                Instant.parse(dateStr)
            } catch (_: Exception) {
                throw ToolValidationException("Invalid createdAfter format. Must be in ISO-8601 format (e.g., 2025-05-10T14:30:00Z)")
            }
        }

        optionalString(params, "createdBefore")?.let { dateStr ->
            try {
                Instant.parse(dateStr)
            } catch (_: Exception) {
                throw ToolValidationException("Invalid createdBefore format. Must be in ISO-8601 format (e.g., 2025-05-10T14:30:00Z)")
            }
        }

        // Validate sortBy
        optionalString(params, "sortBy")?.let { sortBy ->
            val validSortFields = setOf("createdAt", "modifiedAt", "name", "status", "priority")
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
        logger.info("Executing search_features tool")

        try {
            // Extract search parameters
            val query = optionalString(params, "query")
            val statusStr = optionalString(params, "status")
            val priorityStr = optionalString(params, "priority")
            val projectIdStr = optionalString(params, "projectId")
            val tag = optionalString(params, "tag")
            val createdAfterStr = optionalString(params, "createdAfter")
            val createdBeforeStr = optionalString(params, "createdBefore")
            val sortBy = optionalString(params, "sortBy", "modifiedAt")
            val sortDirection = optionalString(params, "sortDirection", "desc")
            val limit = optionalInt(params, "limit", 20)!!
            val offset = optionalInt(params, "offset", 0)!!

            // Convert string parameters to appropriate types
            val status = statusStr?.let { parseStatus(it) }
            val priority = priorityStr?.let { parsePriority(it) }
            val projectId = projectIdStr?.let { UUID.fromString(it) }
            val createdAfter = createdAfterStr?.let { Instant.parse(it) }
            val createdBefore = createdBeforeStr?.let { Instant.parse(it) }

            // Determine whether to use text search or filter search
            val featuresResult = if (!query.isNullOrBlank()) {
                // Use text search method when there's a query
                context.featureRepository().search(
                    query = query,
                    limit = 1000, // Use max limit for client-side filtering of non-repo parameters
                )
            } else {
                // Use filter search for other criteria, let the repository handle status, priority, projectId
                context.featureRepository().findByFilters(
                    projectId = projectId,
                    status = status,
                    priority = priority,
                    tags = if (!tag.isNullOrBlank()) listOf(tag) else null,
                    textQuery = null,
                    limit = 1000, // Use max limit for client-side filtering of non-repo parameters
                )
            }

            when (featuresResult) {
                is Result.Success -> {
                    var filteredFeatures = featuresResult.data

                    // Apply additional filters that aren't supported by the repository
                    // Tag filter is now directly handled in the repository call
                    
                    // Date range filters
                    if (createdAfter != null) {
                        filteredFeatures = filteredFeatures.filter { it.createdAt.isAfter(createdAfter) }
                    }

                    if (createdBefore != null) {
                        filteredFeatures = filteredFeatures.filter { it.createdAt.isBefore(createdBefore) }
                    }

                    // Apply sorting
                    filteredFeatures = when (sortBy) {
                        "name" -> if (sortDirection == "asc") filteredFeatures.sortedBy { it.name } else filteredFeatures.sortedByDescending { it.name }
                        "status" -> if (sortDirection == "asc") filteredFeatures.sortedBy { it.status } else filteredFeatures.sortedByDescending { it.status }
                        "priority" -> if (sortDirection == "asc") filteredFeatures.sortedBy { it.priority } else filteredFeatures.sortedByDescending { it.priority }
                        "createdAt" -> if (sortDirection == "asc") filteredFeatures.sortedBy { it.createdAt } else filteredFeatures.sortedByDescending { it.createdAt }
                        else -> if (sortDirection == "asc") filteredFeatures.sortedBy { it.modifiedAt } else filteredFeatures.sortedByDescending { it.modifiedAt }
                    }

                    // Apply pagination
                    val totalCount = filteredFeatures.size
                    val paginatedFeatures = filteredFeatures.drop(offset).take(limit)

                    // Create feature items with the standardized format
                    val featureItems = buildJsonArray {
                        paginatedFeatures.forEach { feature ->
                            add(buildJsonObject {
                                put("id", feature.id.toString())
                                put("name", feature.name)

                                // Truncate summary if needed
                                if (feature.summary.length > 100) {
                                    put("summary", "${feature.summary.take(97)}...")
                                } else {
                                    put("summary", feature.summary)
                                }

                                put("status", feature.status.name.lowercase().replace('_', '-'))
                                put("priority", feature.priority.name.lowercase())
                                put("createdAt", feature.createdAt.toString())
                                put("modifiedAt", feature.modifiedAt.toString())

                                // Include projectId if present
                                if (feature.projectId != null) {
                                    put("projectId", feature.projectId.toString())
                                }

                                // Include tags if present
                                if (feature.tags.isNotEmpty()) {
                                    put("tags", feature.tags.joinToString(", "))
                                }
                            })
                        }
                    }

                    // Create the data object with format expected by the tests
                    val responseData = buildJsonObject {
                        put("items", featureItems)
                        put("total", totalCount)
                        put("limit", limit)
                        put("offset", offset)
                        put("hasMore", offset + paginatedFeatures.size < totalCount)
                    }

                    // Create message based on results
                    val message = if (totalCount == 1) {
                        "Found 1 features"
                    } else {
                        "Found $totalCount features"
                    }

                    return successResponse(
                        data = responseData,
                        message = message
                    )
                }

                is Result.Error -> {
                    // Return standardized error response
                    return errorResponse(
                        message = "Failed to search features",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = featuresResult.error.toString()
                    )
                }
            }
        } catch (e: ToolValidationException) {
            // Handle validation errors
            logger.warn("Validation error searching features: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            // Handle unexpected errors
            logger.error("Error searching features", e)
            return errorResponse(
                message = "Failed to search features",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Checks if a string is a valid feature status.
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
     * Parses a string into a FeatureStatus enum.
     *
     * @param status The status string to parse
     * @return The corresponding FeatureStatus enum value
     * @throws IllegalArgumentException If the status string is invalid
     */
    private fun parseStatus(status: String): FeatureStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> FeatureStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> FeatureStatus.IN_DEVELOPMENT
            "completed" -> FeatureStatus.COMPLETED
            "archived" -> FeatureStatus.ARCHIVED
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