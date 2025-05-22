package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Tool for creating multiple sections in a single operation.
 *
 * This is the preferred tool for adding multiple sections to a task or feature, especially
 * when the sections have short content. It's more efficient than making multiple calls to
 * add_section as it requires only a single database transaction.
 *
 * Related tools:
 * - add_section: To add a single section (use only when adding a single section)
 * - get_sections: To retrieve sections for a task or feature
 * - update_section: To modify an existing section
 * - bulk_update_sections: To update multiple sections in one operation
 * - bulk_delete_sections: To delete multiple sections in one operation
 */
class BulkCreateSectionsTool : BaseToolDefinition() {
    override val category = ToolCategory.TASK_MANAGEMENT
    override val name = "bulk_create_sections"
    override val description = """Creates multiple sections in a single operation.
        
        ## Purpose
        
        This tool efficiently creates multiple sections for tasks, features, or projects in a single operation.
        It's the preferred method for adding multiple sections, as it reduces the number of database
        transactions and network calls compared to making multiple individual add_section calls.
        
        > **RECOMMENDED**: Use this tool rather than multiple `add_section` calls when adding
        > multiple sections to the same entity. This is especially important for sections with
        > shorter content or when creating the initial set of sections for an entity.
        
        ## Parameters
        
        | Parameter | Type | Required | Default | Description |
        |-----------|------|----------|---------|-------------|
        | sections | array | Yes | - | Array of section objects to create |
        
        Each section object in the array must include:
        
        | Field | Type | Required | Default | Description |
        |-------|------|----------|---------|-------------|
        | entityType | string | Yes | - | Type of entity: 'PROJECT', 'TASK', or 'FEATURE' |
        | entityId | UUID string | Yes | - | ID of the project, task, or feature to add the section to |
        | title | string | Yes | - | Section title (e.g., 'Requirements', 'Implementation Notes') |
        | usageDescription | string | Yes | - | Description of how this section should be used |
        | content | string | Yes | - | The actual content of the section |
        | contentFormat | string | No | "MARKDOWN" | Format of the content: 'MARKDOWN', 'PLAIN_TEXT', 'JSON', or 'CODE' |
        | ordinal | integer | Yes | - | Order position (0-based). Lower numbers appear first. |
        | tags | string | No | - | Comma-separated list of tags for categorization |
        
        ## Response Format
        
        ### Success Response (All Sections Created)
        
        ```json
        {
          "success": true,
          "message": "3 sections created successfully",
          "data": {
            "items": [
              {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "entityType": "task",
                "entityId": "661f9511-f30c-52e5-b827-557766551111",
                "title": "Requirements",
                "contentFormat": "markdown",
                "ordinal": 0,
                "createdAt": "2025-05-10T14:30:00Z"
              }
            ],
            "count": 3,
            "failed": 0
          }
        }
        ```
        
        ## Error Responses
        
        - VALIDATION_ERROR (400): When the input doesn't match the expected format or values are invalid
        - OPERATION_FAILED (500): When all sections fail to create
        - RESOURCE_NOT_FOUND (404): When an entity doesn't exist
        
        ## Usage Examples
        
        1. Create multiple sections for a task:
           ```json
           {
             "sections": [
               {
                 "entityType": "TASK",
                 "entityId": "550e8400-e29b-41d4-a716-446655440000",
                 "title": "Requirements",
                 "usageDescription": "Key requirements for implementation",
                 "content": "1. Must support user authentication\n2. Should integrate with existing systems",
                 "contentFormat": "MARKDOWN",
                 "ordinal": 0,
                 "tags": "requirements,core"
               },
               {
                 "entityType": "TASK",
                 "entityId": "550e8400-e29b-41d4-a716-446655440000",
                 "title": "Technical Approach",
                 "usageDescription": "Implementation strategy and technical details",
                 "content": "We'll use Spring Security for authentication and implement REST endpoints...",
                 "contentFormat": "MARKDOWN",
                 "ordinal": 1,
                 "tags": "technical,implementation"
               },
               {
                 "entityType": "TASK",
                 "entityId": "550e8400-e29b-41d4-a716-446655440000",
                 "title": "Testing Strategy",
                 "usageDescription": "Test approach and coverage requirements",
                 "content": "Ensure 80% unit test coverage and implement integration tests for key flows",
                 "contentFormat": "MARKDOWN",
                 "ordinal": 2,
                 "tags": "testing,quality"
               }
             ]
           }
           ```
           
        2. Create multiple sections for a project:
           ```json
           {
             "sections": [
               {
                 "entityType": "PROJECT",
                 "entityId": "772f9622-g41d-52e5-b827-668899101111",
                 "title": "Project Overview",
                 "usageDescription": "High-level description of the project",
                 "content": "This project aims to create a scalable infrastructure...",
                 "contentFormat": "MARKDOWN",
                 "ordinal": 0,
                 "tags": "overview,documentation"
               },
               {
                 "entityType": "PROJECT",
                 "entityId": "772f9622-g41d-52e5-b827-668899101111",
                 "title": "Project Architecture",
                 "usageDescription": "Technical architecture details",
                 "content": "The project uses a modular architecture with the following components...",
                 "contentFormat": "MARKDOWN",
                 "ordinal": 1,
                 "tags": "architecture,technical"
               }
             ]
           }
           ```
        
        ## Performance Benefits
        
        Using `bulk_create_sections` instead of multiple calls to `add_section` offers significant 
        advantages:
        
        1. Reduced network overhead: Only one API call instead of multiple calls
        2. Improved transaction efficiency: Database operations are batched
        3. Faster execution: Creating multiple sections in parallel
        4. Atomic operations: All sections succeed or useful error information is provided
        
        ## Common Section Combinations
        
        Typical section combinations for tasks include:
        
        1. Requirements + Implementation Approach + Testing Strategy
        2. Problem Statement + Solution Design + Acceptance Criteria
        3. Context + Technical Details + References
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "sections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Array of sections to create"),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(
                                    mapOf(
                                        "entityType" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("Type of entity (PROJECT, TASK, or FEATURE)"),
                                                "enum" to JsonArray(EntityType.entries.map { JsonPrimitive(it.name) })
                                            )
                                        ),
                                        "entityId" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("ID of the project, task, or feature"),
                                                "format" to JsonPrimitive("uuid")
                                            )
                                        ),
                                        "title" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("Section title")
                                            )
                                        ),
                                        "usageDescription" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("Guidance for LLMs on how to use this content")
                                            )
                                        ),
                                        "content" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("Section content")
                                            )
                                        ),
                                        "contentFormat" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("Format of the content"),
                                                "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) }),
                                                "default" to JsonPrimitive(ContentFormat.MARKDOWN.name)
                                            )
                                        ),
                                        "ordinal" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("integer"),
                                                "description" to JsonPrimitive("Order position (0-based)"),
                                                "minimum" to JsonPrimitive(0)
                                            )
                                        ),
                                        "tags" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("Comma-separated list of tags")
                                            )
                                        )
                                    )
                                ),
                                "required" to JsonArray(
                                    listOf(
                                        JsonPrimitive("entityType"),
                                        JsonPrimitive("entityId"),
                                        JsonPrimitive("title"),
                                        JsonPrimitive("usageDescription"),
                                        JsonPrimitive("content"),
                                        JsonPrimitive("ordinal")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("sections")
    )

    override fun validateParams(params: JsonElement) {
        // Make sure sections is present
        val sectionsArray = params.jsonObject["sections"]
            ?: throw ToolValidationException("Missing required parameter: sections")

        if (sectionsArray !is JsonArray) {
            throw ToolValidationException("Parameter 'sections' must be an array")
        }

        if (sectionsArray.isEmpty()) {
            throw ToolValidationException("At least one section must be provided")
        }

        // Validate each section
        sectionsArray.forEachIndexed { index, sectionElement ->
            if (sectionElement !is JsonObject) {
                throw ToolValidationException("Section at index $index must be an object")
            }

            val sectionObj = sectionElement.jsonObject

            // Validate required fields
            val requiredFields = listOf("entityType", "entityId", "title", "usageDescription", "content", "ordinal")
            for (field in requiredFields) {
                if (!sectionObj.containsKey(field)) {
                    throw ToolValidationException("Section at index $index is missing required field: $field")
                }
            }

            // Validate entityType
            val entityTypeStr = sectionObj["entityType"]?.jsonPrimitive?.content
            if (entityTypeStr != null) {
                try {
                    EntityType.valueOf(entityTypeStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException(
                        "Invalid entityType at index $index: $entityTypeStr. " +
                                "Must be one of: ${EntityType.entries.joinToString()}"
                    )
                }
            }

            // Validate entityId
            val entityIdStr = sectionObj["entityId"]?.jsonPrimitive?.content
            if (entityIdStr != null) {
                try {
                    UUID.fromString(entityIdStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid entityId at index $index: $entityIdStr. Must be a valid UUID.")
                }
            }

            // Validate contentFormat if provided
            val contentFormatStr = sectionObj["contentFormat"]?.jsonPrimitive?.content
            if (contentFormatStr != null) {
                try {
                    ContentFormat.valueOf(contentFormatStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException(
                        "Invalid contentFormat at index $index: $contentFormatStr. " +
                                "Must be one of: ${ContentFormat.entries.joinToString()}"
                    )
                }
            }

            // Validate ordinal
            val ordinalElement = sectionObj["ordinal"]
            if (ordinalElement is JsonPrimitive) {
                val ordinal = when {
                    ordinalElement.isString -> ordinalElement.content.toIntOrNull()
                    else -> ordinalElement.intOrNull
                }
                if (ordinal == null || ordinal < 0) {
                    throw ToolValidationException(
                        "Invalid ordinal at index $index: ${ordinalElement.content}. " +
                                "Must be a non-negative integer."
                    )
                }
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing bulk_create_sections tool")

        try {
            val sectionsArray = params.jsonObject["sections"] as JsonArray
            val successfulSections = mutableListOf<JsonObject>()
            val failedSections = mutableListOf<JsonObject>()

            sectionsArray.forEachIndexed { index, sectionElement ->
                val sectionObj = sectionElement.jsonObject

                // Parse section parameters
                val entityTypeStr = sectionObj["entityType"]!!.jsonPrimitive.content
                val entityType = EntityType.valueOf(entityTypeStr)
                val entityIdStr = sectionObj["entityId"]!!.jsonPrimitive.content
                val entityId = UUID.fromString(entityIdStr)
                val title = sectionObj["title"]!!.jsonPrimitive.content
                val usageDescription = sectionObj["usageDescription"]!!.jsonPrimitive.content
                val content = sectionObj["content"]!!.jsonPrimitive.content

                val contentFormatStr =
                    sectionObj["contentFormat"]?.jsonPrimitive?.content ?: ContentFormat.MARKDOWN.name
                val contentFormat = ContentFormat.valueOf(contentFormatStr)

                val ordinalElement = sectionObj["ordinal"]!!
                val ordinal = when {
                    ordinalElement.jsonPrimitive.isString -> ordinalElement.jsonPrimitive.content.toInt()
                    else -> ordinalElement.jsonPrimitive.int
                }

                val tagsStr = sectionObj["tags"]?.jsonPrimitive?.content ?: ""
                val tags = if (tagsStr.isNotEmpty()) {
                    tagsStr.split(",").map { it.trim() }
                } else {
                    emptyList()
                }

                // Verify the entity exists
                val entityExistsResult = when (entityType) {
                    EntityType.TASK -> context.taskRepository().getById(entityId)
                    EntityType.FEATURE -> context.featureRepository().getById(entityId)
                    EntityType.PROJECT -> context.projectRepository().getById(entityId)
                    else -> Result.Error(RepositoryError.ValidationError("Unsupported entity type: $entityType"))
                }

                if (entityExistsResult is Result.Error) {
                    failedSections.add(buildJsonObject {
                        put("index", index)
                        put("error", buildJsonObject {
                            put("code", ErrorCodes.RESOURCE_NOT_FOUND)
                            put("details", "Entity not found: ${entityType.name} with ID $entityId")
                        })
                    })
                    return@forEachIndexed
                }

                // Create and add the section
                val now = Instant.now()
                val newSection = Section(
                    id = UUID.randomUUID(),
                    entityType = entityType,
                    entityId = entityId,
                    title = title,
                    usageDescription = usageDescription,
                    content = content,
                    contentFormat = contentFormat,
                    ordinal = ordinal,
                    tags = tags,
                    createdAt = now,
                    modifiedAt = now
                )

                val addResult = context.sectionRepository().addSection(entityType, entityId, newSection)

                when (addResult) {
                    is Result.Success -> {
                        successfulSections.add(serializeSection(addResult.data))
                    }

                    is Result.Error -> {
                        failedSections.add(buildJsonObject {
                            put("index", index)
                            put("error", buildJsonObject {
                                put(
                                    "code", when (addResult.error) {
                                        is RepositoryError.ValidationError -> ErrorCodes.VALIDATION_ERROR
                                        is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                        else -> ErrorCodes.DATABASE_ERROR
                                    }
                                )
                                put("details", addResult.error.toString())
                            })
                        })
                    }
                }
            }

            // Build the response
            val totalRequested = sectionsArray.size
            val successCount = successfulSections.size
            val failedCount = failedSections.size

            return if (failedCount == 0) {
                // All sections created successfully
                successResponse(
                    data = buildJsonObject {
                        put("items", JsonArray(successfulSections))
                        put("count", successCount)
                        put("failed", 0)
                    },
                    message = "$successCount sections created successfully"
                )
            } else if (successCount == 0) {
                // All sections failed to create
                errorResponse(
                    message = "Failed to create any sections",
                    code = ErrorCodes.INTERNAL_ERROR,
                    details = "All $totalRequested sections failed to create",
                    additionalData = buildJsonObject {
                        put("failures", JsonArray(failedSections))
                    }
                )
            } else {
                // Partial success
                successResponse(
                    data = buildJsonObject {
                        put("items", JsonArray(successfulSections))
                        put("count", successCount)
                        put("failed", failedCount)
                        put("failures", JsonArray(failedSections))
                    },
                    message = "$successCount sections created successfully, $failedCount failed"
                )
            }
        } catch (e: Exception) {
            logger.error("Error creating sections in bulk", e)
            return errorResponse(
                message = "Failed to create sections",
                code = ErrorCodes.OPERATION_FAILED,
                details = e.message ?: "Unknown error",
                additionalData = buildJsonObject {
                    put("exception", e.javaClass.simpleName)
                }
            )
        }
    }

    /**
     * Helper method to serialize a Section to JsonObject
     */
    private fun serializeSection(section: Section): JsonObject {
        return buildJsonObject {
            put("id", section.id.toString())
            put("entityType", section.entityType.name.lowercase())
            put("entityId", section.entityId.toString())
            put("title", section.title)
            put("usageDescription", section.usageDescription)
            put("contentFormat", section.contentFormat.name.lowercase())
            put("ordinal", section.ordinal)
            put("createdAt", section.createdAt.toString())
        }
    }
}