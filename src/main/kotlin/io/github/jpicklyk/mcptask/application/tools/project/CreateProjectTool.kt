package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for creating new projects with all required and optional properties.
 *
 * Projects are top-level organizational containers that can group related features and tasks together.
 * They provide a higher level of organization above features, allowing management of work across
 * multiple features and their related tasks.
 *
 * Like features and tasks, projects can have detailed content stored in Sections to optimize
 * context usage with AI assistants.
 *
 * Related tools:
 * - get_project: To retrieve a project by ID
 * - update_project: To modify an existing project
 * - delete_project: To remove a project
 * - create_feature: To create features that can be assigned to this project
 * - create_task: To create tasks that can be assigned to this project
 * - get_project_features: To retrieve all features within a project
 * - get_project_tasks: To retrieve all tasks directly associated with a project
 */
class CreateProjectTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT

    override val name: String = "create_project"

    override val title: String = "Create New Project"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The created project object"),
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

    override val description: String =
        """Implement the CreateProjectTool MCP tool for creating new projects. Define parameter schema and validation for all required and optional fields (name, summary, status, tags). Implement tool execution logic to create and persist project entities.
        
        This tool creates a new Project entity in the MCP Task Orchestrator system. Projects are top-level organizational containers that can group related features and tasks together. Each project requires a name and summary, and can optionally include status information and tags for categorization.
        
        Projects provide a higher level of organization above features, allowing you to manage work across multiple features and their related tasks.
        
        Example successful response:
        {
          "success": true,
          "message": "Project created successfully",
          "data": {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "name": "Mobile App Redesign",
            "summary": "Complete redesign of the mobile application with improved UI/UX",
            "status": "planning",
            "createdAt": "2025-05-10T14:30:00Z",
            "modifiedAt": "2025-05-10T14:30:00Z",
            "tags": ["mobile", "ui", "2025-roadmap"]
          },
          "error": null,
          "metadata": {
            "timestamp": "2025-05-10T14:30:00Z"
          }
        }
        
        Common error responses:
        - VALIDATION_ERROR: When provided parameters fail validation
        - DATABASE_ERROR: When there's an issue storing the project
        - INTERNAL_ERROR: For unexpected system errors
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Project name (e.g., 'Mobile App Redesign', 'Backend API')")
                    )
                ),
                "summary" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Project description explaining its purpose and scope")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Project status. Valid values: 'planning', 'in-development', 'completed', 'archived'"),
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
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of tags for categorization (e.g., 'frontend,mobile,2025-roadmap')")
                    )
                )
            )
        ),
        required = listOf("name", "summary")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        requireString(params, "name").also {
            if (it.isBlank()) {
                throw ToolValidationException("Project name cannot be empty")
            }
        }

        // Validate summary parameter
        requireString(params, "summary").also {
            if (it.isBlank()) {
                throw ToolValidationException("Project summary cannot be empty")
            }
        }

        // Validate status if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status. Must be one of: planning, in-development, completed, archived")
            }
        }

        // Validate tags if present
        optionalString(params, "tags")
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing create_project tool")

        try {
            // Extract parameters
            val name = requireString(params, "name")
            val summary = requireString(params, "summary")
            val statusStr = optionalString(params, "status") ?: "planning"
            val tagsStr = optionalString(params, "tags")

            // Convert string parameters to appropriate types
            val status = parseStatus(statusStr)
            val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            // Create a new project entity
            val project = Project(
                name = name,
                summary = summary,
                status = status,
                tags = tags
            )

            // Create a project using the repository
            val result = context.projectRepository().create(project)

            return when (result) {
                is Result.Success -> {
                    val createdProject = result.data

                    // Build the response
                    val responseBuilder = buildJsonObject {
                        put("id", createdProject.id.toString())
                        put("name", createdProject.name)
                        put("summary", createdProject.summary)
                        put("status", createdProject.status.name.lowercase().replace('_', '-'))
                        put("createdAt", createdProject.createdAt.toString())
                        put("modifiedAt", createdProject.modifiedAt.toString())

                        // Include tags if present
                        if (createdProject.tags.isNotEmpty()) {
                            put("tags", JsonArray(createdProject.tags.map { JsonPrimitive(it) }))
                        }
                    }

                    successResponse(
                        data = responseBuilder,
                        message = "Project created successfully"
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
                                message = "Failed to create project",
                                code = ErrorCodes.INTERNAL_ERROR,
                                details = error.toString()
                            )
                        }
                    }
                }
            }
        } catch (e: ToolValidationException) {
            // Handle validation errors
            logger.warn("Validation error creating project: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            // Handle unexpected errors
            logger.error("Error creating project", e)
            return errorResponse(
                message = "Failed to create project",
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