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

    override val title: String = "Get Entity Sections"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Sections for the entity"),
                        "properties" to JsonObject(
                            mapOf(
                                "sections" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "usageDescription" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "content" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "contentFormat" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "ordinal" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                                                        "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                                        "modifiedAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time")))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "entityType" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "entityId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "count" to JsonObject(mapOf("type" to JsonPrimitive("integer")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

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
        | includeContent | boolean | No | true | Whether to include section content. Set false to get only metadata (saves 85-99% tokens) |
        | sectionIds | array | No | all | Optional list of specific section IDs to retrieve. Allows selective loading of sections |
        
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

        1. Get all sections with full content (default behavior):
           ```json
           {
             "entityType": "TASK",
             "entityId": "550e8400-e29b-41d4-a716-446655440000"
           }
           ```

        2. Browse section structure without content (85-99% token savings):
           ```json
           {
             "entityType": "TASK",
             "entityId": "550e8400-e29b-41d4-a716-446655440000",
             "includeContent": false
           }
           ```
           Returns only: id, title, usageDescription, contentFormat, ordinal, tags

        3. Get specific sections by ID (selective loading):
           ```json
           {
             "entityType": "TASK",
             "entityId": "550e8400-e29b-41d4-a716-446655440000",
             "sectionIds": ["section-id-1", "section-id-3"]
           }
           ```

        4. Two-step workflow - browse then fetch:
           Step 1: Get section metadata
           ```json
           {
             "entityType": "TASK",
             "entityId": "550e8400-e29b-41d4-a716-446655440000",
             "includeContent": false
           }
           ```
           Step 2: Fetch specific sections with content
           ```json
           {
             "entityType": "TASK",
             "entityId": "550e8400-e29b-41d4-a716-446655440000",
             "sectionIds": ["requirements-section-id", "testing-section-id"]
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
                ),
                "includeContent" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include section content. Set to false to retrieve only section metadata (title, ordinal, format) without content, saving significant tokens. Default: true"),
                        "default" to JsonPrimitive(true)
                    )
                ),
                "sectionIds" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Optional list of specific section IDs to retrieve. If provided, only sections with these IDs will be returned. Use with includeContent=false to first browse available sections, then fetch specific ones."),
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

            // Parse optional parameters
            val includeContent = paramsObj["includeContent"]?.jsonPrimitive?.booleanOrNull ?: true

            val sectionIds = paramsObj["sectionIds"]?.jsonArray?.let { array ->
                try {
                    array.map { UUID.fromString(it.jsonPrimitive.content) }
                } catch (_: IllegalArgumentException) {
                    return errorResponse(
                        message = "Invalid section ID format in sectionIds array. All IDs must be valid UUIDs.",
                        code = ErrorCodes.VALIDATION_ERROR
                    )
                }
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
                    val allSections = result.data

                    // Apply sectionIds filter if provided
                    val filteredSections = if (sectionIds != null) {
                        allSections.filter { section -> section.id in sectionIds }
                    } else {
                        allSections
                    }

                    val sectionsArray = JsonArray(
                        filteredSections.map { section ->
                            val baseFields = mutableMapOf(
                                "id" to JsonPrimitive(section.id.toString()),
                                "title" to JsonPrimitive(section.title),
                                "usageDescription" to JsonPrimitive(section.usageDescription),
                                "contentFormat" to JsonPrimitive(section.contentFormat.name),
                                "ordinal" to JsonPrimitive(section.ordinal),
                                "tags" to JsonArray(section.tags.map { JsonPrimitive(it) }),
                                "createdAt" to JsonPrimitive(section.createdAt.toString()),
                                "modifiedAt" to JsonPrimitive(section.modifiedAt.toString())
                            )

                            // Only include content if requested
                            if (includeContent) {
                                baseFields["content"] = JsonPrimitive(section.content)
                            }

                            JsonObject(baseFields)
                        }
                    )

                    successResponse(
                        message = when {
                            filteredSections.isEmpty() -> "No sections found for ${entityType.name.lowercase()}"
                            filteredSections.size == 1 -> "Retrieved 1 section"
                            else -> "Retrieved ${filteredSections.size} sections"
                        },
                        data = buildJsonObject {
                            put("sections", sectionsArray)
                            put("entityType", entityType.name)
                            put("entityId", entityId.toString())
                            put("count", filteredSections.size)
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