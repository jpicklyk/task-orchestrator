package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for updating existing project properties.
 *
 * This tool modifies the properties of an existing project identified by its ID.
 * It supports partial updates, meaning only the fields specified in the request
 * will be modified while the others retain their current values.
 *
 * Projects are top-level organizational containers that group related features and tasks together.
 * Updating a project allows changing its metadata without affecting its relationships with
 * contained features and tasks.
 *
 * Related tools:
 * - create_project: To create a new project
 * - get_project: To retrieve project details
 * - delete_project: To remove a project
 * - search_projects: To find projects matching criteria
 */
class UpdateProjectTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT

    override val name: String = "update_project"

    override val title: String = "Update Project"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The updated project object"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "status" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("planning", "in-development", "completed", "archived").map { JsonPrimitive(it) })
                                    )
                                ),
                                "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "modifiedAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override fun shouldUseLocking(): Boolean = true

    override val description: String = """Updates an existing project's properties.

        ⚡ **EFFICIENCY TIP**: Only send fields you want to change! All fields except 'id' are optional.
        Sending unchanged fields wastes 90%+ tokens. Example: To update status, send only {"id": "uuid", "status": "in-development"}

        ## Efficient vs Inefficient Updates

        ❌ **INEFFICIENT** (wastes ~400+ characters):
        ```json
        {
          "id": "project-uuid",
          "name": "Existing Project Name",        // Unchanged - unnecessary
          "summary": "Long existing summary...",  // Unchanged - 400+ chars wasted
          "status": "in-development",             // ✓ Only this changed
          "tags": "tag1,tag2,tag3"               // Unchanged - unnecessary
        }
        ```

        ✅ **EFFICIENT** (uses ~40 characters):
        ```json
        {
          "id": "project-uuid",
          "status": "in-development"  // Only send what changed!
        }
        ```

        **Token Savings**: 90% reduction by only sending changed fields!

        ## Partial Updates
        This tool modifies properties of an existing project. It supports partial updates, meaning you only need to specify
        the fields you want to change. Any fields not included in the request will retain their current values.

        Projects are top-level organizational containers that group related features and tasks together. Updating a project
        allows you to change its name, summary, status, or tags without affecting its relationships with features and tasks.
        
        Example successful response:
        {
          "success": true,
          "message": "Project updated successfully",
          "data": {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "name": "Mobile App Redesign 2.0",
            "summary": "Updated project scope with additional accessibility features",
            "status": "in-development",
            "createdAt": "2025-05-10T14:30:00Z",
            "modifiedAt": "2025-05-15T16:20:00Z",
            "tags": ["mobile", "ui", "accessibility", "2025-roadmap"]
          },
          "error": null,
          "metadata": {
            "timestamp": "2025-05-15T16:20:00Z"
          }
        }
        
        Common error responses:
        - RESOURCE_NOT_FOUND: When no project exists with the specified ID
        - VALIDATION_ERROR: When parameters fail validation (e.g., empty name)
        - DATABASE_ERROR: When there's an issue storing the updated project
        - INTERNAL_ERROR: For unexpected system errors
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the project to update")
                    )
                ),
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New project name")
                    )
                ),
                "summary" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New project summary describing its purpose and scope")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New project status. Valid values: 'planning', 'in-development', 'completed', 'archived'"),
                        "enum" to JsonArray(
                            listOf(
                                "planning",
                                "in-development",
                                "completed",
                                "archived"
                            ).map { JsonPrimitive(it) })
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New comma-separated list of tags for categorization")
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required ID parameter
        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid id format. Must be a valid UUID")
        }

        // Check if any fields contain empty strings (which get converted to null by optionalString)
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        // Check for empty name
        paramsObj["name"]?.let { value ->
            if (value is JsonPrimitive && value.isString && value.content.isBlank()) {
                throw ToolValidationException("Project name cannot be empty if provided")
            }
        }

        // Check for empty summary
        paramsObj["summary"]?.let { value ->
            if (value is JsonPrimitive && value.isString && value.content.isBlank()) {
                throw ToolValidationException("Project summary cannot be empty if provided")
            }
        }

        // Validate optional parameters if provided
        optionalString(params, "name")?.let {
            if (it.isBlank()) {
                throw ToolValidationException("Project name cannot be empty if provided")
            }
        }

        optionalString(params, "summary")?.let {
            if (it.isBlank()) {
                throw ToolValidationException("Project summary cannot be empty if provided")
            }
        }

        // Validate status if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status. Must be one of: planning, in-development, completed, archived")
            }
        }

        // Validate tags (no additional validation needed for tags)
        optionalString(params, "tags")
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing update_project tool")

        return try {
            // Extract project ID
            val projectId = extractEntityId(params, "id")

            // Execute with proper locking
            executeWithLocking("update_project", EntityType.PROJECT, projectId) {
                executeProjectUpdate(params, context, projectId)
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error updating project: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error updating project", e)
            errorResponse(
                message = "Failed to update project",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Executes the actual project update business logic.
     */
    private suspend fun executeProjectUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        projectId: UUID
    ): JsonElement {
        // Extract parameters
        val name = optionalString(params, "name")
        val summary = optionalString(params, "summary")
        val statusStr = optionalString(params, "status")
        val tagsStr = optionalString(params, "tags")

        // First, get the existing project
        val getResult = context.projectRepository().getById(projectId)

        return when (getResult) {
            is Result.Success -> {
                val existingProject = getResult.data

                // Convert string parameters to appropriate types
                val status = statusStr?.let { parseStatus(it) }
                val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

                // Create an updated project entity
                val updatedProject = existingProject.update(
                    name = name ?: existingProject.name,
                    summary = summary ?: existingProject.summary,
                    status = status ?: existingProject.status,
                    tags = tags ?: existingProject.tags
                )

                // Update the project using the repository
                val updateResult = context.projectRepository().update(updatedProject)

                when (updateResult) {
                    is Result.Success -> {
                        val project = updateResult.data

                        // Return minimal response to optimize bandwidth and performance
                        // Only return essential fields: id, status, and modifiedAt
                        val responseBuilder = buildJsonObject {
                            put("id", project.id.toString())
                            put("status", project.status.name.lowercase().replace('_', '-'))
                            put("modifiedAt", project.modifiedAt.toString())
                        }

                        successResponse(
                            data = responseBuilder,
                            message = "Project updated successfully"
                        )
                    }

                    is Result.Error -> {
                        // Determine appropriate error code and message based on error type
                        when (val error = updateResult.error) {
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

                            else -> {
                                errorResponse(
                                    message = "Failed to update project",
                                    code = ErrorCodes.INTERNAL_ERROR,
                                    details = error.toString()
                                )
                            }
                        }
                    }
                }
            }

            is Result.Error -> {
                if (getResult.error is RepositoryError.NotFound) {
                    errorResponse(
                        message = "Project not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No project exists with ID $projectId"
                    )
                } else {
                    errorResponse(
                        message = "Failed to retrieve project for update",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = getResult.error.toString()
                    )
                }
            }
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