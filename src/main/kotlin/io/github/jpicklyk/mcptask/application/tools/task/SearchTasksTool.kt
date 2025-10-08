package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for searching tasks based on various criteria.
 *
 * This tool allows for flexible task search using multiple filters and criteria. It returns
 * paginated results with standardized pagination information to facilitate browsing through
 * large result sets. It's designed for efficiency when exploring the task database.
 *
 * Tasks are the primary work items in the system. They can exist independently or be
 * associated with Features. Detailed content is stored in Sections.
 *
 * Related tools:
 * - get_task: To retrieve complete details for a specific task
 * - update_task: To modify an existing task
 * - get_overview: To get a lightweight overview of all tasks
 * - create_task: To create a new task
 * - get_feature_tasks: To get tasks associated with a specific feature
 */
class SearchTasksTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "search_tasks"

    override val title: String = "Search Tasks"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "items" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Array of task objects matching the search criteria"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "priority" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "complexity" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                        "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                                        "modifiedAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                                        "featureId" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))),
                                                        "projectId" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))),
                                                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "pagination" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Pagination information for result set"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "page" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Current page number (1-indexed)"))),
                                                "pageSize" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Number of items per page"))),
                                                "totalItems" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Total number of items matching the search"))),
                                                "totalPages" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Total number of pages"))),
                                                "hasNext" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("Whether there are more pages after this one"))),
                                                "hasPrevious" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("Whether there are pages before this one")))
                                            )
                                        ),
                                        "required" to JsonArray(listOf("page", "pageSize", "totalItems", "totalPages", "hasNext", "hasPrevious").map { JsonPrimitive(it) })
                                    )
                                )
                            )
                        ),
                        "required" to JsonArray(listOf("items", "pagination").map { JsonPrimitive(it) })
                    )
                )
            )
        ),
        required = listOf("success", "message")
    )

    override val description: String = """Searches for tasks based on various criteria.
        
        ## Purpose
        Provides flexible task discovery and filtering capabilities for project management,
        work planning, and task analysis. Essential for finding specific tasks or analyzing
        work patterns across the project.
        
        ## Search Strategy Guidelines
        
        **Start Broad, Narrow Down**:
        1. Begin with no parameters to see all tasks
        2. Add status filter to focus on specific work states
        3. Add priority filter for urgency-based searches
        4. Use tag filters for domain-specific searches
        5. Combine multiple filters for precise targeting
        
        **Common Search Patterns**:
        
        **Finding Work to Do**:
        ```json
        {
          "status": "pending",
          "priority": "high",
          "sortBy": "priority",
          "sortDirection": "desc"
        }
        ```
        
        **Reviewing In-Progress Work**:
        ```json
        {
          "status": "in-progress",
          "sortBy": "modifiedAt",
          "sortDirection": "desc"
        }
        ```
        
        **Finding Feature-Specific Tasks**:
        ```json
        {
          "featureId": "feature-uuid",
          "sortBy": "complexity",
          "sortDirection": "asc"
        }
        ```
        
        **Tag-Based Searches** (using consistent tagging conventions):
        ```json
        {
          "tag": "task-type-bug",
          "priority": "high"
        }
        ```
        
        **Text-Based Discovery**:
        ```json
        {
          "query": "authentication oauth",
          "sortBy": "modifiedAt"
        }
        ```
        
        ## Filter Combinations for Different Use Cases
        
        **Sprint Planning**:
        - Filter by status="pending" and priority="high" or "medium"
        - Sort by complexity to group similar-sized work
        - Use pagination to handle large backlogs
        
        **Bug Triage**:
        - Filter by tag="task-type-bug"
        - Sort by priority and modifiedAt
        - Review high priority bugs first
        
        **Feature Development**:
        - Filter by featureId to see all related tasks
        - Sort by status to see progression
        - Check complexity distribution for estimation
        
        **Technical Debt Management**:
        - Filter by tag="technical-debt" or tag="refactoring"
        - Sort by complexity to tackle manageable items
        - Combine with priority for impact assessment
        
        ## Pagination Best Practices
        
        **Default Behavior**: No parameters returns all tasks, newest first, 20 per page
        
        **Efficiency Guidelines**:
        - Use limit=5-10 for quick overviews
        - Use limit=50-100 for comprehensive analysis
        - Use offset for paging through large result sets
        - Sort by modifiedAt for recent activity
        - Sort by priority for work planning
        - Sort by complexity for estimation analysis
        
        ## Integration with Other Tools
        
        **After Search Results**:
        - Use `get_task` with includeSections=true for detailed task info
        - Use `update_task` to modify status, priority, or assignments
        - Use `create_dependency` to link related tasks found in search
        - Use `get_feature` to understand feature context for tasks
        
        **Complement get_overview**:
        - get_overview: High-level project state and hierarchical view
        - search_tasks: Detailed filtering and analysis of specific task subsets
        
        ## Context Efficiency Features
        
        **Lightweight Results**: Returns essential metadata without full content or sections
        **Paginated Results**: Controls token usage for large datasets
        **Flexible Sorting**: Enables different analytical perspectives
        **Multiple Filters**: Precise targeting reduces noise
        
        Example successful response:
        {
          "success": true,
          "message": "Found 12 tasks",
          "data": {
            "items": [
              {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "title": "Implement OAuth Authentication API",
                "summary": "Create secure authentication flow with OAuth 2.0 and JWT tokens...",
                "status": "in-progress",
                "priority": "high",
                "complexity": 8,
                "createdAt": "2025-05-10T14:30:00Z",
                "modifiedAt": "2025-05-10T15:45:00Z",
                "featureId": "661e8511-f30c-41d4-a716-557788990000",
                "tags": ["task-type-feature", "oauth", "authentication", "api", "security"]
              }
            ],
            "pagination": {
              "page": 1,
              "pageSize": 20,
              "totalItems": 12,
              "totalPages": 1,
              "hasNext": false,
              "hasPrevious": false
            }
          }
        }
        
        Common error responses:
        - VALIDATION_ERROR: When provided parameters fail validation (invalid status, priority, UUID)
        - DATABASE_ERROR: When there's an issue searching for tasks
        - INTERNAL_ERROR: For unexpected system errors
        - INTERNAL_ERROR: For unexpected system errors"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "query" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Text to search for in task titles and descriptions (searches both fields for partial matches)")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by task status. Valid values: 'pending', 'in-progress', 'completed', 'cancelled', 'deferred'"),
                        "enum" to JsonArray(
                            listOf(
                                "pending",
                                "in-progress",
                                "completed",
                                "cancelled",
                                "deferred"
                            ).map { JsonPrimitive(it) })
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by task priority. Valid values: 'high', 'medium', 'low'"),
                        "enum" to JsonArray(listOf("high", "medium", "low").map { JsonPrimitive(it) })
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by feature ID (UUID) to get only tasks for a specific feature (e.g., '550e8400-e29b-41d4-a716-446655440000')"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by project ID (UUID) to get only tasks for a specific project (e.g., '550e8400-e29b-41d4-a716-446655440000')"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tag" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by tag to find tasks containing this specific tag (case insensitive)")
                    )
                ),
                "limit" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum number of results to return"),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(100),
                        "default" to JsonPrimitive(20)
                    )
                ),
                "offset" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Number of results to skip (for pagination)"),
                        "minimum" to JsonPrimitive(0),
                        "default" to JsonPrimitive(0)
                    )
                ),
                "sortBy" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Field to sort results by: 'createdAt', 'modifiedAt', 'priority', 'status', or 'complexity'"),
                        "default" to JsonPrimitive("modifiedAt"),
                        "enum" to JsonArray(
                            listOf(
                                "createdAt",
                                "modifiedAt",
                                "priority",
                                "status",
                                "complexity"
                            ).map { JsonPrimitive(it) })
                    )
                ),
                "sortDirection" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Sort direction: 'asc' (ascending) or 'desc' (descending)"),
                        "default" to JsonPrimitive("desc"),
                        "enum" to JsonArray(listOf("asc", "desc").map { JsonPrimitive(it) })
                    )
                )
            )
        ),
        required = listOf()
    )

    override fun validateParams(params: JsonElement) {
        // Validate optional parameters if present
        optionalString(params, "query")

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

        // Validate featureId if present
        optionalString(params, "featureId")?.let { featureId ->
            try {
                UUID.fromString(featureId)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid featureId format. Must be a valid UUID")
            }
        }

        // Validate projectId if present
        optionalString(params, "projectId")?.let { projectId ->
            try {
                UUID.fromString(projectId)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid projectId format. Must be a valid UUID")
            }
        }

        // Validate limit parameter if present
        optionalInt(params, "limit")?.let { limit ->
            if (limit < 1 || limit > 100) {
                throw ToolValidationException("Limit must be between 1 and 100")
            }
        }

        // Validate offset parameter if present
        optionalInt(params, "offset")?.let { offset ->
            if (offset < 0) {
                throw ToolValidationException("Offset must be a non-negative integer")
            }
        }

        // Validate sortBy parameter if present
        optionalString(params, "sortBy")?.let { sortBy ->
            val validSortFields = setOf("createdAt", "modifiedAt", "priority", "status", "complexity")
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

        // Validate sortDirection parameter if present
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
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing search_tasks tool")

        try {
            // Extract search parameters
            val query = optionalString(params, "query")
            val statusStr = optionalString(params, "status")
            val priorityStr = optionalString(params, "priority")
            val featureIdStr = optionalString(params, "featureId")
            val projectIdStr = optionalString(params, "projectId")
            val tag = optionalString(params, "tag")
            val limit = optionalInt(params, "limit", 20) ?: 20
            val offset = optionalInt(params, "offset", 0) ?: 0
            val sortBy = optionalString(params, "sortBy", "modifiedAt")
            val sortDirection = optionalString(params, "sortDirection", "desc")

            // Convert string parameters to appropriate types if present
            val status = statusStr?.let { parseStatus(it) }
            val priority = priorityStr?.let { parsePriority(it) }
            val featureId = featureIdStr?.let { UUID.fromString(it) }
            val projectId = projectIdStr?.let { UUID.fromString(it) }

            // Use the appropriate repository method based on available parameters
            // Execute the search using the unified findByFilters method from base repository
            val allTasksResult = when {
                // If we have text query or any filters, use findByFilters
                query != null || projectId != null || featureId != null || status != null || priority != null || tag != null -> {
                    // Convert tag to tags list if provided
                    val tags = tag?.let { listOf(it) }

                    // For tasks, we need to use the specific repository methods since findByFilters doesn't support featureId/projectId directly
                    when {
                        featureId != null -> {
                            context.taskRepository().findByFeatureAndFilters(
                                featureId = featureId,
                                status = status,
                                priority = priority,
                                tags = tags,
                                textQuery = query,
                                limit = 1000, // Use large limit to get all matching results then filter client-side
                            )
                        }

                        projectId != null -> {
                            context.taskRepository().findByProjectAndFilters(
                                projectId = projectId,
                                status = status,
                                priority = priority,
                                tags = tags,
                                textQuery = query,
                                limit = 1000, // Use large limit to get all matching results then filter client-side
                            )
                        }

                        else -> {
                            // Use the unified findByFilters from base repository
                            context.taskRepository().findByFilters(
                                projectId = projectId,
                                status = status,
                                priority = priority,
                                tags = tags,
                                textQuery = query,
                                limit = 1000, // Use large limit to get all matching results then filter client-side
                            )
                        }
                    }
                }
                // Otherwise, get all tasks
                else -> {
                    context.taskRepository().findAll(limit = 1000)
                }
            }

            // Handle search results
            when (allTasksResult) {
                is Result.Success -> {
                    val tasks = allTasksResult.data

                    // Sort the results
                    val sortedTasks = sortResults(tasks, sortBy, sortDirection)

                    // Apply pagination
                    val paginatedTasks = sortedTasks
                        .drop(offset)
                        .take(limit)

                    // Calculate pagination info
                    val totalItems = sortedTasks.size
                    val totalPages = if (limit > 0) (totalItems + limit - 1) / limit else 0
                    val page = if (limit > 0) (offset / limit) + 1 else 1
                    val hasNext = offset + paginatedTasks.size < totalItems
                    val hasPrevious = offset > 0

                    // Convert tasks to a JSON array
                    val tasksArray = buildJsonArray {
                        paginatedTasks.forEach { task ->
                            add(buildJsonObject {
                                put("id", task.id.toString())
                                put("title", task.title)

                                // Provide a truncated summary for search results
                                val summaryPreview = if (task.summary.length > 100) {
                                    "${task.summary.take(97)}..."
                                } else {
                                    task.summary
                                }
                                put("summary", summaryPreview)

                                put("status", task.status.name.lowercase())
                                put("priority", task.priority.name.lowercase())
                                put("complexity", task.complexity)
                                put("createdAt", task.createdAt.toString())
                                put("modifiedAt", task.modifiedAt.toString())

                                if (task.featureId != null) {
                                    put("featureId", task.featureId.toString())
                                } else {
                                    put("featureId", JsonNull)
                                }

                                if (task.projectId != null) {
                                    put("projectId", task.projectId.toString())
                                } else {
                                    put("projectId", JsonNull)
                                }

                                // Ensure tags are properly included in the response
                                put("tags", buildJsonArray {
                                    // Add debugging to see the actual tags
                                    logger.info("Including tags for task ${task.id} in search results: ${task.tags}")
                                    task.tags.forEach { tag ->
                                        add(JsonPrimitive(tag))
                                    }
                                })
                            })
                        }
                    }

                    // Create pagination info
                    val pagination = buildJsonObject {
                        put("page", page)
                        put("pageSize", limit)
                        put("totalItems", totalItems)
                        put("totalPages", totalPages)
                        put("hasNext", hasNext)
                        put("hasPrevious", hasPrevious)
                    }

                    // Create the data object with items and pagination
                    val data = buildJsonObject {
                        put("items", tasksArray)
                        put("pagination", pagination)
                    }

                    // Create the success message based on how many tasks were found
                    val message = when {
                        paginatedTasks.isEmpty() -> "No tasks found matching the criteria"
                        paginatedTasks.size == 1 -> "Found 1 task"
                        else -> "Found ${paginatedTasks.size} tasks"
                    }

                    return successResponse(data, message)
                }

                is Result.Error -> {
                    return errorResponse(
                        message = "Failed to search tasks: ${allTasksResult.error}",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = allTasksResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error searching tasks", e)
            return errorResponse(
                message = "Failed to search tasks",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Sorts a list of tasks based on the specified field and direction.
     *
     * @param tasks The tasks to sort
     * @param sortBy The field to sort by
     * @param sortDirection The direction to sort in (asc or desc)
     * @return The sorted list of tasks
     */
    private fun sortResults(tasks: List<Task>, sortBy: String, sortDirection: String): List<Task> {
        val comparator = when (sortBy) {
            "createdAt" -> compareBy { it.createdAt }
            "modifiedAt" -> compareBy { it.modifiedAt }
            "priority" -> compareBy { it.priority.ordinal }
            "status" -> compareBy { it.status.ordinal }
            "complexity" -> compareBy { it.complexity }
            else -> compareBy<Task> { it.modifiedAt }
        }

        return if (sortDirection == "desc") {
            tasks.sortedWith(comparator.reversed())
        } else {
            tasks.sortedWith(comparator)
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