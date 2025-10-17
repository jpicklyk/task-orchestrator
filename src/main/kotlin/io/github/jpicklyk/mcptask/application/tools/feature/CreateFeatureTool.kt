package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for creating new features with all required and optional properties.
 *
 * Features are optional organizational groupings for tasks. They represent larger units of work
 * or project components that contain multiple related tasks. Features help organize tasks into
 * logical groups, but tasks can also exist independently without being assigned to a feature.
 *
 * Like tasks, features can have detailed content stored in Sections to optimize context usage
 * with AI assistants.
 *
 * Related tools:
 * - get_feature: To retrieve a feature by ID
 * - update_feature: To modify an existing feature
 * - delete_feature: To remove a feature
 * - create_task: To create tasks that can be assigned to this feature
 * - get_feature_tasks: To retrieve all tasks within a feature
 * - add_section: To add detailed content sections to a feature
 */
class CreateFeatureTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.FEATURE_MANAGEMENT

    override val name: String = "create_feature"

    override val title: String = "Create New Feature"

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
                        "description" to JsonPrimitive("The created feature object"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("uuid"),
                                        "description" to JsonPrimitive("Unique identifier for the feature")
                                    )
                                ),
                                "name" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Feature name")
                                    )
                                ),
                                "description" to JsonObject(
                                    mapOf(
                                        "type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))),
                                        "description" to JsonPrimitive("Detailed description of what needs to be done (user-provided)")
                                    )
                                ),
                                "summary" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Brief summary of what was accomplished (agent-generated)")
                                    )
                                ),
                                "status" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("planning", "in-development", "completed", "archived").map { JsonPrimitive(it) }),
                                        "description" to JsonPrimitive("Current status of the feature")
                                    )
                                ),
                                "priority" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("high", "medium", "low").map { JsonPrimitive(it) }),
                                        "description" to JsonPrimitive("Feature priority level")
                                    )
                                ),
                                "createdAt" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("date-time"),
                                        "description" to JsonPrimitive("ISO-8601 timestamp when feature was created")
                                    )
                                ),
                                "modifiedAt" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("date-time"),
                                        "description" to JsonPrimitive("ISO-8601 timestamp when feature was last modified")
                                    )
                                ),
                                "projectId" to JsonObject(
                                    mapOf(
                                        "type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))),
                                        "format" to JsonPrimitive("uuid"),
                                        "description" to JsonPrimitive("ID of the project this feature belongs to, or null")
                                    )
                                ),
                                "tags" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Comma-separated list of tags")
                                    )
                                ),
                                "appliedTemplates" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Array of templates that were applied (only present if templates were used)"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "templateId" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("string"),
                                                                "format" to JsonPrimitive("uuid"),
                                                                "description" to JsonPrimitive("ID of the applied template")
                                                            )
                                                        ),
                                                        "sectionsCreated" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("integer"),
                                                                "description" to JsonPrimitive("Number of sections created by this template")
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                "error" to JsonObject(
                    mapOf(
                        "type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))),
                        "description" to JsonPrimitive("Error details if the operation failed, null otherwise")
                    )
                ),
                "metadata" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Additional metadata about the operation")
                    )
                )
            )
        )
    )

    override val description: String = """Creates a feature with metadata and optional template application. Features group related tasks representing coherent functionality.

Parameters:
| Field | Type | Required | Default | Description |
| name | string | Yes | - | Feature name |
| summary | string | No | - | Brief summary (max 500 chars) |
| status | enum | No | planning | Feature status (planning, in-development, completed, archived) |
| priority | enum | No | medium | Priority level (high, medium, low) |
| projectId | UUID | No | - | Parent project |
| templateIds | array | No | - | Templates to apply |
| tags | string | No | - | Comma-separated tags |

Usage notes:
- Features organize multiple related tasks (3+ tasks recommended)
- Apply templates during creation for structured documentation (use list_templates to discover)
- Associate tasks with features using featureId in create_task
- Summary limited to 500 characters

Related: update_feature, get_feature, delete_feature, search_features, create_task, list_templates

For detailed examples and patterns: task-orchestrator://docs/tools/create-feature
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Required feature name (e.g., 'User Authentication', 'Data Export')")
                    )
                ),
                "description" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Detailed description of what needs to be done (user-provided)")
                    )
                ),
                "summary" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Brief summary of what was accomplished (agent-generated, max 500 chars)")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Feature status. Valid values: 'planning', 'in-development', 'completed', 'archived'"),
                        "default" to JsonPrimitive("planning"),
                        "enum" to JsonArray(
                            listOf(
                                "planning",
                                "in-development",
                                "completed",
                                "archived"
                            ).map { JsonPrimitive(it) })
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Priority level. Valid values: 'high', 'medium', 'low'"),
                        "default" to JsonPrimitive("medium"),
                        "enum" to JsonArray(Priority.entries.map { JsonPrimitive(it.name.lowercase()) })
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Optional UUID of the project this feature belongs to (e.g., '550e8400-e29b-41d4-a716-446655440000')"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of tags for categorization (e.g., 'frontend,ui,critical')")
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
        required = listOf("name")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        requireString(params, "name").also {
            if (it.isBlank()) {
                throw ToolValidationException("Feature name cannot be empty")
            }
        }

        // Validate optional description and summary
        optionalString(params, "description")
        optionalString(params, "summary")

        // Validate status if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status. Must be one of: planning, in-development, completed, archived")
            }
        }

        // Validate priority if present
        optionalString(params, "priority")?.let { priority ->
            if (!isValidPriority(priority)) {
                throw ToolValidationException("Invalid priority: $priority. Must be one of: high, medium, low")
            }
        }

        // Validate tags if present
        optionalString(params, "tags")

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
        logger.info("Executing create_feature tool")

        try {
            // Extract parameters
            val name = requireString(params, "name")
            val description = optionalString(params, "description")
            val summary = optionalString(params, "summary") ?: ""
            val statusStr = optionalString(params, "status") ?: "planning"
            val priorityStr = optionalString(params, "priority") ?: "medium"
            val tagsStr = optionalString(params, "tags")

            // Parse project ID
            val projectId = optionalString(params, "projectId")?.let {
                if (it.isEmpty()) null else UUID.fromString(it)
            }
            
            // Validate that referenced project exists before attempting to create feature
            if (projectId != null) {
                when (val projectResult = context.repositoryProvider.projectRepository().getById(projectId)) {
                    is Result.Error -> {
                        return errorResponse(
                            message = "Project not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No project exists with ID $projectId"
                        )
                    }
                    is Result.Success -> { /* Project exists, continue */ }
                }
            }
            
            // Convert string parameters to appropriate types
            val status = parseStatus(statusStr)
            val priority = parsePriority(priorityStr)
            val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            
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

            // Create a new feature entity
            val feature = Feature(
                name = name,
                description = description,
                summary = summary,
                status = status,
                priority = priority,
                projectId = projectId,
                tags = tags
            )

            // Create a feature using the repository
            val result = context.featureRepository().create(feature)

            return when (result) {
                is Result.Success -> {
                    val createdFeature = result.data
                    
                    // Apply templates if specified
                    val appliedTemplatesResult = if (templateIds.isNotEmpty()) {
                        context.repositoryProvider.templateRepository()
                            .applyMultipleTemplates(templateIds, EntityType.FEATURE, createdFeature.id)
                    } else {
                        null
                    }
                    
                    // Build the response
                    val responseBuilder = buildJsonObject {
                        put("id", createdFeature.id.toString())
                        put("name", createdFeature.name)
                        if (createdFeature.description != null) {
                            put("description", createdFeature.description)
                        } else {
                            put("description", JsonNull)
                        }
                        put("summary", createdFeature.summary)
                        put("status", createdFeature.status.name.lowercase().replace('_', '-'))
                        put("priority", createdFeature.priority.name.lowercase())
                        put("createdAt", createdFeature.createdAt.toString())
                        put("modifiedAt", createdFeature.modifiedAt.toString())

                        // Include project ID if present
                        if (createdFeature.projectId != null) {
                            put("projectId", createdFeature.projectId.toString())
                        } else {
                            put("projectId", JsonNull)
                        }

                        // Include tags if present
                        if (createdFeature.tags.isNotEmpty()) {
                            put("tags", createdFeature.tags.joinToString(", "))
                        }
                        
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
                        "Feature created successfully with $templateCount template(s) applied, creating $sectionCount section(s)"
                    } else {
                        "Feature created successfully"
                    }

                    successResponse(
                        data = responseBuilder,
                        message = message
                    )
                }

                is Result.Error -> {
                    // Determine appropriate error code and message based on error type
                    when (val error = result.error) {
                        is RepositoryError.ValidationError -> {
                            errorResponse(
                                message = "Validation error: ${error.message}",
                                code = ErrorCodes.VALIDATION_ERROR,
                                details = error.message
                            )
                        }

                        is RepositoryError.DatabaseError -> {
                            errorResponse(
                                message = "Database error occurred",
                                code = ErrorCodes.DATABASE_ERROR,
                                details = error.message
                            )
                        }

                        is RepositoryError.ConflictError -> {
                            errorResponse(
                                message = "Conflict error: ${error.message}",
                                code = ErrorCodes.DUPLICATE_RESOURCE,
                                details = error.message
                            )
                        }

                        else -> {
                            errorResponse(
                                message = "Failed to create feature",
                                code = ErrorCodes.INTERNAL_ERROR,
                                details = error.toString()
                            )
                        }
                    }
                }
            }
        } catch (e: ToolValidationException) {
            // Handle validation errors
            logger.warn("Validation error creating feature: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            // Handle unexpected errors
            logger.error("Error creating feature", e)
            return errorResponse(
                message = "Failed to create feature",
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