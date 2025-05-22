package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for retrieving sections for a task, feature, or project.
 *
 * Sections contain detailed content for tasks, features, and projects, organized into structured blocks.
 * This approach helps optimize context usage with AI assistants by keeping the core entities
 * lightweight, while still providing access to comprehensive content when needed.
 *
 * Common section types include:
 * - Requirements
 * - Implementation Notes
 * - Testing Strategies
 * - Reference Information
 *
 * Related tools:
 * - add_section: To create a new section
 * - update_section: To modify an existing section
 * - delete_section: To remove a section
 * - get_task: To retrieve a task with options to include its sections
 * - get_feature: To retrieve a feature with options to include its sections
 */
class GetSectionsTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT
    override val name = "get_sections"
    override val description = """Retrieves sections for a task, feature, or project.
        
        ## Purpose
        
        Sections contain detailed content for tasks, features, and projects in a structured format.
        Each section has a specific purpose, content format, and ordering position. This tool
        allows retrieving all sections for a specified entity.
        
        ## Parameters
        
        | Parameter | Type | Required | Default | Description |
        |-----------|------|----------|---------|-------------|
        | entityType | string | Yes | - | Type of entity to retrieve sections for: 'PROJECT', 'TASK', or 'FEATURE' |
        | entityId | UUID string | Yes | - | ID of the entity to retrieve sections for (e.g., '550e8400-e29b-41d4-a716-446655440000') |
        
        ## Response Format
        
        ### Success Response
        
        ```json
        {
          "success": true,
          "message": "Sections retrieved successfully",
          "data": {
            "sections": [
              {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "title": "Requirements",
                "usageDescription": "Key requirements for this task",
                "content": "1. Must support OAuth2\\n2. Handle token refresh\\n3. Implement rate limiting",
                "contentFormat": "MARKDOWN",
                "ordinal": 0,
                "tags": ["requirements", "security"],
                "createdAt": "2025-05-10T14:30:00Z",
                "modifiedAt": "2025-05-10T14:30:00Z"
              },
              {
                "id": "661f9511-f30c-52e5-b827-557766551111",
                "title": "Implementation Notes",
                "usageDescription": "Technical details and implementation guidance",
                "content": "Use the AuthLib 2.0 library for OAuth implementation...",
                "contentFormat": "MARKDOWN",
                "ordinal": 1,
                "tags": ["implementation", "technical"],
                "createdAt": "2025-05-10T14:35:00Z",
                "modifiedAt": "2025-05-10T15:20:00Z"
              }
            ],
            "entityType": "TASK",
            "entityId": "772f9622-g41d-52e5-b827-668899101111",
            "count": 2
          }
        }
        ```
        
        ### Empty Result Response
        
        ```json
        {
          "success": true,
          "message": "No sections found for task",
          "data": {
            "sections": [],
            "entityType": "TASK",
            "entityId": "772f9622-g41d-52e5-b827-668899101111",
            "count": 0
          }
        }
        ```
        
        ## Error Responses
        
        - RESOURCE_NOT_FOUND (404): When the specified task or feature doesn't exist
          ```json
          {
            "success": false,
            "message": "The specified task was not found",
            "error": {
              "code": "RESOURCE_NOT_FOUND"
            }
          }
          ```
          
        - VALIDATION_ERROR (400): When parameters fail validation
          ```json
          {
            "success": false,
            "message": "Invalid entity type: INVALID. Must be one of: TASK, FEATURE",
            "error": {
              "code": "VALIDATION_ERROR"
            }
          }
          ```
          
        - DATABASE_ERROR (500): When there's an issue retrieving sections from the database
        
        - INTERNAL_ERROR (500): For unexpected system errors during execution
        
        ## Usage Examples
        
        1. Get sections for a task:
           ```json
           {
             "entityType": "TASK",
             "entityId": "550e8400-e29b-41d4-a716-446655440000"
           }
           ```
           
        2. Get sections for a feature:
           ```json
           {
             "entityType": "FEATURE",
             "entityId": "661e8511-f30c-41d4-a716-557788990000"
           }
           ```
           
        3. Get sections for a project:
           ```json
           {
             "entityType": "PROJECT",
             "entityId": "772f9622-g41d-52e5-b827-668899101111"
           }
           ```
        
        ## Content Formats
        
        Sections support multiple content formats:
        - MARKDOWN: Formatted text with Markdown syntax (default)
        - PLAIN_TEXT: Unformatted plain text
        - JSON: Structured data in JSON format
        - CODE: Source code and implementation details
        
        ## Common Section Types
        
        While you can create sections with any title, common section types include:
        - Requirements: Task or feature requirements (what needs to be done)
        - Implementation Notes: Technical details on how to implement
        - Testing Strategy: How to test the implementation
        - Reference Information: External links and resources
        - Architecture: Design and architecture details
        - Dependencies: Dependencies on other components
    """

    override val parameterSchema = Tool.Input(
        properties = JsonObject(
            mapOf(
                "entityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of entity to retrieve sections for: 'PROJECT', 'TASK', or 'FEATURE'"),
                        "enum" to JsonArray(EntityType.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "entityId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the entity to retrieve sections for (e.g., '550e8400-e29b-41d4-a716-446655440000')"),
                        "format" to JsonPrimitive("uuid")
                    )
                )
            )
        ),
        required = listOf("entityType", "entityId")
    )

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        try {
            // Parse parameters
            val paramsObj = params as? JsonObject ?: return errorResponse(
                message = "Parameters must be a JSON object",
                code = ErrorCodes.VALIDATION_ERROR
            )

            val entityTypeStr = paramsObj["entityType"]?.jsonPrimitive?.content
                ?: return errorResponse(
                    message = "Entity type is required",
                    code = ErrorCodes.VALIDATION_ERROR
                )

            val entityType = try {
                EntityType.valueOf(entityTypeStr)
            } catch (_: IllegalArgumentException) {
                return errorResponse(
                    message = "Invalid entity type: $entityTypeStr. Must be one of: ${
                        EntityType.entries.joinToString()
                    }",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            val entityIdStr = paramsObj["entityId"]?.jsonPrimitive?.content
                ?: return errorResponse(
                    message = "Entity ID is required",
                    code = ErrorCodes.VALIDATION_ERROR
                )

            val entityId = try {
                UUID.fromString(entityIdStr)
            } catch (_: IllegalArgumentException) {
                return errorResponse(
                    message = "Invalid entity ID format: $entityIdStr. Must be a valid UUID.",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            // Verify the entity exists before getting sections for it
            when (entityType) {
                EntityType.TASK -> {
                    val taskResult = context.taskRepository().getById(entityId)
                    if (taskResult is Result.Error) {
                        return handleRepositoryError(taskResult.error, "task")
                    }
                }

                EntityType.FEATURE -> {
                    val featureResult = context.featureRepository().getById(entityId)
                    if (featureResult is Result.Error) {
                        return handleRepositoryError(featureResult.error, "feature")
                    }
                }

                EntityType.PROJECT -> {
                    val projectResult = context.projectRepository().getById(entityId)
                    if (projectResult is Result.Error) {
                        return handleRepositoryError(projectResult.error, "project")
                    }
                }
                
                else -> {} // We do not need to worry about the other entity types here
            }

            // Retrieve sections for the entity
            val result = context.sectionRepository().getSectionsForEntity(entityType, entityId)

            return when (result) {
                is Result.Success -> {
                    val sections = result.data

                    val sectionsArray = JsonArray(
                        sections.map { section ->
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(section.id.toString()),
                                    "title" to JsonPrimitive(section.title),
                                    "usageDescription" to JsonPrimitive(section.usageDescription),
                                    "content" to JsonPrimitive(section.content),
                                    "contentFormat" to JsonPrimitive(section.contentFormat.name),
                                    "ordinal" to JsonPrimitive(section.ordinal),
                                    "tags" to JsonArray(section.tags.map { JsonPrimitive(it) }),
                                    "createdAt" to JsonPrimitive(section.createdAt.toString()),
                                    "modifiedAt" to JsonPrimitive(section.modifiedAt.toString())
                                )
                            )
                        }
                    )

                    successResponse(
                        message = when {
                            sections.isEmpty() -> "No sections found for ${entityType.name.lowercase()}"
                            sections.size == 1 -> "Retrieved 1 section"
                            else -> "Retrieved ${sections.size} sections"
                        },
                        data = buildJsonObject {
                            put("sections", sectionsArray)
                            put("entityType", entityType.name)
                            put("entityId", entityId.toString())
                            put("count", sections.size)
                        }
                    )
                }

                is Result.Error -> handleRepositoryError(result.error, "section")
            }

        } catch (e: Exception) {
            logger.error("Error retrieving sections: ${e.message}", e)
            return errorResponse(
                message = "Failed to retrieve sections: ${e.message}",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message
            )
        }
    }

    private fun handleRepositoryError(error: RepositoryError, entityType: String): JsonObject {
        return when (error) {
            is RepositoryError.NotFound ->
                errorResponse(
                    message = "The specified $entityType was not found",
                    code = ErrorCodes.RESOURCE_NOT_FOUND
                )

            is RepositoryError.ValidationError ->
                errorResponse(
                    message = "Validation error: ${error.message}",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = error.message
                )

            is RepositoryError.DatabaseError ->
                errorResponse(
                    message = "Database error: ${error.message}",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = error.message
                )

            is RepositoryError.ConflictError ->
                errorResponse(
                    message = "Conflict error: ${error.message}",
                    code = ErrorCodes.DUPLICATE_RESOURCE,
                    details = error.message
                )

            is RepositoryError.UnknownError ->
                errorResponse(
                    message = "Unknown error: ${error.message}",
                    code = ErrorCodes.INTERNAL_ERROR,
                    details = error.message
                )
        }
    }
}