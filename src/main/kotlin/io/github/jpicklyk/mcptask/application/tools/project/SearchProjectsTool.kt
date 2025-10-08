package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * MCP tool for searching projects based on various criteria.
 */
class SearchProjectsTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT

    override val name: String = "search_projects"

    override val title: String = "Search Projects"

    override val description: String = "Find projects matching specified criteria"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "query" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Text to search for in project names and summaries")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by project status (planning, in-development, completed, archived)")
                    )
                ),
                "tag" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by tag (projects containing this tag)")
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
                        "description" to JsonPrimitive("Sort results by this field (createdAt, modifiedAt, name, status)"),
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
            val validSortFields = setOf("createdAt", "modifiedAt", "name", "status")
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
        logger.info("Executing search_projects tool")

        try {
            // Extract search parameters
            val query = optionalString(params, "query")
            val statusStr = optionalString(params, "status")
            val tag = optionalString(params, "tag")
            val createdAfterStr = optionalString(params, "createdAfter")
            val createdBeforeStr = optionalString(params, "createdBefore")
            val sortBy = optionalString(params, "sortBy", "modifiedAt")
            val sortDirection = optionalString(params, "sortDirection", "desc")
            val limit = optionalInt(params, "limit", 20)!!
            val offset = optionalInt(params, "offset", 0)!!

            // Convert string parameters to appropriate types
            val status = statusStr?.let { parseStatus(it) }
            val createdAfter = createdAfterStr?.let { Instant.parse(it) }
            val createdBefore = createdBeforeStr?.let { Instant.parse(it) }

            // Convert tag to tags list if provided
            val tags = tag?.let { listOf(it) }

            // Use the repository's findByFilters method for efficient database querying
            val projectsResult = context.projectRepository().findByFilters(
                projectId = null,
                status = status,
                priority = null, // Projects don't have priority
                tags = tags,
                textQuery = query,
                limit = 1000, // Use large limit to get all matching results then filter client-side for date range

            )

            when (projectsResult) {
                is Result.Success -> {
                    var filteredProjects = projectsResult.data

                    // Apply date range filters (these need to be done client-side)
                    if (createdAfter != null) {
                        filteredProjects = filteredProjects.filter { it.createdAt.isAfter(createdAfter) }
                    }

                    if (createdBefore != null) {
                        filteredProjects = filteredProjects.filter { it.createdAt.isBefore(createdBefore) }
                    }

                    // Apply sorting
                    filteredProjects = when (sortBy) {
                        "name" -> if (sortDirection == "asc") filteredProjects.sortedBy { it.name } else filteredProjects.sortedByDescending { it.name }
                        "status" -> if (sortDirection == "asc") filteredProjects.sortedBy { it.status } else filteredProjects.sortedByDescending { it.status }
                        "createdAt" -> if (sortDirection == "asc") filteredProjects.sortedBy { it.createdAt } else filteredProjects.sortedByDescending { it.createdAt }
                        else -> if (sortDirection == "asc") filteredProjects.sortedBy { it.modifiedAt } else filteredProjects.sortedByDescending { it.modifiedAt }
                    }

                    // Apply pagination
                    val totalCount = filteredProjects.size
                    val paginatedProjects = filteredProjects.drop(offset).take(limit)

                    // Create project items with the standardized format
                    val projectItems = buildJsonArray {
                        paginatedProjects.forEach { project ->
                            add(buildJsonObject {
                                put("id", project.id.toString())
                                put("name", project.name)

                                // Truncate summary if needed
                                if (project.summary.length > 100) {
                                    put("summary", "${project.summary.take(97)}...")
                                } else {
                                    put("summary", project.summary)
                                }

                                put("status", project.status.name.lowercase().replace('_', '-'))
                                put("createdAt", project.createdAt.toString())
                                put("modifiedAt", project.modifiedAt.toString())

                                // Include tags if present
                                if (project.tags.isNotEmpty()) {
                                    put("tags", project.tags.joinToString(", "))
                                }
                            })
                        }
                    }

                    // Create the data object with pagination information
                    val responseData = buildJsonObject {
                        put("items", projectItems)
                        put("total", totalCount)
                        put("limit", limit)
                        put("offset", offset)
                        put("hasMore", offset + paginatedProjects.size < totalCount)
                    }

                    // Create message based on results
                    val message = when {
                        paginatedProjects.isEmpty() -> "Found 0 projects"
                        paginatedProjects.size == 1 -> "Found 1 project"
                        else -> "Found ${paginatedProjects.size} projects"
                    }

                    return successResponse(
                        data = responseData,
                        message = message
                    )
                }

                is Result.Error -> {
                    // Return standardized error response
                    return errorResponse(
                        message = "Failed to search projects",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = projectsResult.error.toString()
                    )
                }
            }
        } catch (e: ToolValidationException) {
            // Handle validation errors
            logger.warn("Validation error searching projects: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            // Handle unexpected errors
            logger.error("Error searching projects", e)
            return errorResponse(
                message = "Failed to search projects",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Checks if a string is a valid project status.
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
     * Parses a string into a ProjectStatus enum.
     *
     * @param status The status string to parse
     * @return The corresponding ProjectStatus enum value
     * @throws IllegalArgumentException If the status string is invalid
     */
    private fun parseStatus(status: String): ProjectStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> ProjectStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> ProjectStatus.IN_DEVELOPMENT
            "completed" -> ProjectStatus.COMPLETED
            "archived" -> ProjectStatus.ARCHIVED
            else -> throw IllegalArgumentException("Invalid status: $status")
        }
    }
}