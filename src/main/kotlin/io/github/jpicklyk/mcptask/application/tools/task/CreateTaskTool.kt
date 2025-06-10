package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for creating a new task with all required and optional properties.
 *
 * Tasks are the primary work items in the system. They can exist independently or be associated with Features.
 * Each task has essential metadata like title, summary, status, and priority.
 * For detailed content and structured information, tasks can have multiple Sections attached to them.
 *
 * Related tools:
 * - update_task: To modify an existing task
 * - get_task: To retrieve a task by ID
 * - delete_task: To remove a task
 * - get_overview: For a lightweight view of all tasks
 * - add_section: To add detailed content sections to a task
 */
class CreateTaskTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "create_task"

    override val description: String = """Creates a new task with the specified properties.
        
        ## Purpose
        Tasks are the primary work items in the system and can be organized into Features.
        Each task has basic metadata (title, status, etc.) and can have Sections for detailed content.
        
        ## Template Integration
        RECOMMENDED: Apply templates at creation time using templateIds parameter for consistent
        documentation structure. Use `list_templates` first to find appropriate templates.
        
        Template application creates standardized sections automatically:
        - Use single-item array for one template: ["template-uuid"]
        - Use multiple templates for comprehensive coverage: ["uuid1", "uuid2"]
        - Templates are applied in order, with later templates' sections appearing after earlier ones
        
        ## Best Practices
        - Use descriptive titles that clearly indicate the work to be done
        - Write comprehensive summaries with acceptance criteria when helpful
        - Set appropriate complexity ratings (1=trivial, 10=highly complex)
        - Use consistent tagging conventions (e.g., "task-type-feature", "task-type-bug")
        - Associate with features when the task is part of larger functionality
        - Apply templates that match the work type (implementation, testing, documentation)
        
        ## Entity Relationships
        - Tasks can belong to Features (use featureId to establish relationship)
        - Tasks can belong to Projects (use projectId for top-level organization)
        - Use dependencies to link related tasks (see create_dependency tool)
        
        ## Workflow Integration
        Tasks integrate with the complete project management workflow:
        1. Start with get_overview to understand current work
        2. Create task with appropriate templates and metadata
        3. Use add_section or bulk_create_sections for additional content
        4. Update status as work progresses (pending → in_progress → completed)
        5. Create dependencies between related tasks
        
        Example successful response:
        {
          "success": true,
          "message": "Task created successfully with 2 template(s) applied, creating 6 section(s)",
          "data": {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "title": "Implement OAuth Authentication API",
            "summary": "Create secure API endpoints for OAuth 2.0 authentication with JWT tokens",
            "status": "pending",
            "priority": "high",
            "complexity": 8,
            "createdAt": "2025-05-10T14:30:00Z",
            "modifiedAt": "2025-05-10T14:30:00Z",
            "featureId": "661e8511-f30c-41d4-a716-557788990000",
            "tags": ["task-type-feature", "oauth", "authentication", "api", "security"],
            "appliedTemplates": [
              {
                "templateId": "technical-approach-uuid",
                "sectionsCreated": 3
              },
              {
                "templateId": "implementation-workflow-uuid",
                "sectionsCreated": 3
              }
            ]
          }
        }
        
        Common error responses:
        - VALIDATION_ERROR: When provided parameters fail validation (empty title/summary, invalid UUIDs)
        - RESOURCE_NOT_FOUND: When specified featureId, projectId, or templateIds don't exist
        - DATABASE_ERROR: When there's an issue storing the task or applying templates
        - INTERNAL_ERROR: For unexpected system errors
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The title of the task (required)")
                    )
                ),
                "summary" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Brief summary of the task content (required for task creation)")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Task status. Valid values: 'pending', 'in-progress', 'completed', 'cancelled', 'deferred'"),
                        "default" to JsonPrimitive("pending"),
                        "enum" to JsonArray(TaskStatus.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Task priority. Valid values: 'high', 'medium', 'low'"),
                        "default" to JsonPrimitive("medium"),
                        "enum" to JsonArray(Priority.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "complexity" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Task complexity on a scale from 1-10 (1=simplest, 10=most complex)"),
                        "default" to JsonPrimitive(5),
                        "minimum" to JsonPrimitive(1),
                        "maximum" to JsonPrimitive(10)
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Optional UUID of the project this task belongs to (e.g., '550e8400-e29b-41d4-a716-446655440000')"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Optional UUID of the feature this task belongs to (e.g., '550e8400-e29b-41d4-a716-446655440000')"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of tags for categorization (e.g., 'api,backend,urgent')")
                    )
                ),
                "templateIds" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("List of template IDs to apply. Use a single-item array for applying just one template."),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "format" to JsonPrimitive("uuid")
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("title", "summary")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required title parameter
        try {
            requireString(params, "title")
        } catch (e: ToolValidationException) {
            throw ToolValidationException("Title validation failed: ${e.message}")
        }

        // Validate required summary parameter  
        try {
            requireString(params, "summary")
        } catch (e: ToolValidationException) {
            throw ToolValidationException("Summary validation failed: ${e.message}")
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

        // Validate complexity if present
        optionalInt(params, "complexity")?.let { complexity ->
            if (complexity < 1 || complexity > 10) {
                throw ToolValidationException("Complexity must be between 1 and 10")
            }
        }

        // Validate featureId if present
        optionalString(params, "featureId")?.let { featureId ->
            if (featureId.isNotEmpty()) {
                try {
                    UUID.fromString(featureId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid feature ID format. Must be a valid UUID")
                }
            }
        }

        // Validate projectId if present
        optionalString(params, "projectId")?.let { projectId ->
            if (projectId.isNotEmpty()) {
                try {
                    UUID.fromString(projectId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid project ID format. Must be a valid UUID")
                }
            }
        }
        
        // Validate templateIds if present
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        if (paramsObj.containsKey("templateIds")) {
            val templateIdsElement = paramsObj["templateIds"]
            if (templateIdsElement !is JsonArray) {
                throw ToolValidationException("Parameter 'templateIds' must be an array of strings (UUIDs)")
            }
            
            if (templateIdsElement.isNotEmpty()) {
                for ((index, item) in templateIdsElement.withIndex()) {
                    if (item !is JsonPrimitive || !item.isString) {
                        throw ToolValidationException("templateIds[$index] must be a string (UUID)")
                    }
                    
                    try {
                        UUID.fromString(item.content)
                    } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("templateIds[$index] is not a valid UUID format")
                    }
                }
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing create_task tool")
        logger.info("DEBUG: Raw input parameters: $params")
        logger.info("DEBUG: Parameter type: ${params::class.simpleName}")
        
        // Log parameter details
        if (params is JsonObject) {
            params.entries.forEach { (key, value) ->
                logger.info("DEBUG: Parameter '$key' = '$value' (type: ${value::class.simpleName})")
                if (value is JsonPrimitive) {
                    logger.info("DEBUG: Parameter '$key' content: '${value.content}' (isString: ${value.isString})")
                }
            }
        }

        try {
            // Extract parameters
            val title = requireString(params, "title")
            logger.info("DEBUG: Extracted title: '$title' (length=${title.length})")
            val summary = requireString(params, "summary")
            logger.info("DEBUG: Extracted summary: '$summary' (length=${summary.length})")

            // Additional debug info
            logger.info("DEBUG: Title isBlank: ${title.isBlank()}, isEmpty: ${title.isEmpty()}")
            logger.info("DEBUG: Summary isBlank: ${summary.isBlank()}, isEmpty: ${summary.isEmpty()}")
            logger.info("DEBUG: Title chars: ${title.map { it.code }.joinToString(",")}")
            logger.info("DEBUG: Summary chars: ${summary.map { it.code }.joinToString(",")}")

            if (title.isBlank()) {
                logger.error("Title is blank: '$title'")
                throw ToolValidationException("Title cannot be blank")
            }
            
            if (summary.isBlank()) {
                logger.error("Summary is blank: '$summary'")
                throw ToolValidationException("Summary cannot be blank")
            }

            // Parse status
            val statusStr = optionalString(params, "status") ?: "pending"
            val status = parseStatus(statusStr)

            // Parse priority
            val priorityStr = optionalString(params, "priority") ?: "medium"
            val priority = parsePriority(priorityStr)

            // Parse complexity
            val complexity = optionalInt(params, "complexity") ?: 5

            // Parse project ID
            val projectId = optionalString(params, "projectId")?.let {
                if (it.isEmpty()) null else UUID.fromString(it)
            }

            // Parse feature ID
            val featureId = optionalString(params, "featureId")?.let {
                if (it.isEmpty()) null else UUID.fromString(it)
            }

            // Parse tags
            val tags = optionalString(params, "tags")?.let {
                if (it.isEmpty()) emptyList()
                else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
            } ?: emptyList()
            
            // Get template IDs to apply, if any
            val templateIds = mutableListOf<UUID>()

            // Get templateIds array if provided
            val paramsObj = params as JsonObject
            if (paramsObj.containsKey("templateIds")) {
                val templateIdsArray = paramsObj["templateIds"] as? JsonArray
                templateIdsArray?.forEach { item ->
                    if (item is JsonPrimitive && item.isString) {
                        templateIds.add(UUID.fromString(item.content))
                    }
                }
            }

            // Create the task entity
            val task = Task(
                title = title,
                summary = summary,
                status = status,
                priority = priority,
                complexity = complexity,
                projectId = projectId,
                featureId = featureId,
                tags = tags,
            )
            logger.debug("Created task entity: $task")

            // Create a task in the repository
            val result = context.taskRepository().create(task)
            logger.debug("Repository result: $result")

            // Process result and apply templates if necessary
            return when (result) {
                is Result.Success -> {
                    val createdTask = result.data
                    
                    // Apply templates if specified
                    val appliedTemplatesResult = if (templateIds.isNotEmpty()) {
                        context.repositoryProvider.templateRepository()
                            .applyMultipleTemplates(templateIds, EntityType.TASK, createdTask.id)
                    } else {
                        null
                    }
                    
                    // Build the response
                    val responseBuilder = buildJsonObject {
                        put("id", createdTask.id.toString())
                        put("title", createdTask.title)
                        put("summary", createdTask.summary)
                        put("status", createdTask.status.name.lowercase())
                        put("priority", createdTask.priority.name.lowercase())
                        put("complexity", createdTask.complexity)
                        put("createdAt", createdTask.createdAt.toString())
                        put("modifiedAt", createdTask.modifiedAt.toString())

                        if (createdTask.projectId != null) {
                            put("projectId", createdTask.projectId.toString())
                        } else {
                            put("projectId", JsonNull)
                        }

                        if (createdTask.featureId != null) {
                            put("featureId", createdTask.featureId.toString())
                        } else {
                            put("featureId", JsonNull)
                        }

                        put("tags", buildJsonArray {
                            createdTask.tags.forEach { add(it) }
                        })
                        
                        // Add applied templates info if any
                        if (appliedTemplatesResult is Result.Success && appliedTemplatesResult.data.isNotEmpty()) {
                            put("appliedTemplates", buildJsonArray {
                                appliedTemplatesResult.data.forEach { (templateId, sections) ->
                                    add(buildJsonObject {
                                        put("templateId", templateId.toString())
                                        put("sectionsCreated", sections.size)
                                    })
                                }
                            })
                        }
                    }
                    
                    // Create a message based on templates applied
                    val message = if (appliedTemplatesResult is Result.Success && appliedTemplatesResult.data.isNotEmpty()) {
                        val templateCount = appliedTemplatesResult.data.size
                        val sectionCount = appliedTemplatesResult.data.values.sumOf { it.size }
                        "Task created successfully with $templateCount template(s) applied, creating $sectionCount section(s)"
                    } else {
                        "Task created successfully"
                    }
                    
                    successResponse(responseBuilder, message)
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to create task: ${result.error}",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = result.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error creating task", e)
            return errorResponse(
                message = "Failed to create task",
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