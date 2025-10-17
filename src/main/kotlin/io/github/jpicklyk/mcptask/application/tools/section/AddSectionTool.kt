package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for adding a section to a task, feature, or project.
 *
 * Sections provide a way to organize detailed content for tasks, features, and projects in a structured way.
 * Rather than storing large amounts of content directly in task, feature, or project entities, sections
 * allow for more granular content organization and help optimize context usage with AI models.
 *
 * An entity can have multiple sections, each with a specific purpose (e.g., "Requirements",
 * "Implementation Notes", "Testing Strategy"). Sections are ordered by their ordinal value.
 *
 * Related tools:
 * - get_sections: To retrieve sections for an entity
 * - update_section: To modify an existing section
 * - delete_section: To remove a section
 * - bulk_create_sections: To create multiple sections at once
 */
class AddSectionTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name = "add_section"

    override val title: String = "Add Section to Entity"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The created section object"),
                        "properties" to JsonObject(
                            mapOf(
                                "section" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                "entityType" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "entityId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "contentFormat" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "ordinal" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time")))
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description = """Adds a section to a task, feature, or project. Sections store detailed content
        in structured blocks with specific formats and purposes, keeping core entities lightweight.

        Key features:
        - Supports MARKDOWN, PLAIN_TEXT, JSON, and CODE formats
        - Ordinal field controls display order (0-based)
        - Optional tags for categorization
        - Section title becomes ## H2 heading in markdown output

        Parameters
        
        | Parameter | Type | Required | Default | Description |
        |-----------|------|----------|---------|-------------|
        | entityType | string | Yes | - | Type of entity to attach this section to: 'PROJECT', 'TASK', or 'FEATURE' |
        | entityId | UUID string | Yes | - | ID of the project, task, or feature to add the section to (e.g., '550e8400-e29b-41d4-a716-446655440000') |
        | title | string | Yes | - | Section title (e.g., 'Requirements', 'Implementation Notes', 'Testing Plan') |
        | usageDescription | string | Yes | - | Description of how this section should be used by AI models or users |
        | content | string | Yes | - | The actual content of the section in the specified format |
        | contentFormat | string | No | "MARKDOWN" | Format of the content: 'MARKDOWN', 'PLAIN_TEXT', 'JSON', or 'CODE' |
        | ordinal | integer | Yes | - | Order position (0-based). Lower numbers appear first when ordered. |
        | tags | string | No | - | Comma-separated list of tags for categorization |

        Usage notes:
        - For multiple sections, use bulk_create_sections instead (more efficient)
        - Do NOT duplicate section title as ## H2 heading in content field
        - Use ordinal to control display order (lower numbers first)
        - Templates create sections automatically; use this to add custom content

        Related: bulk_create_sections, get_sections, update_section, delete_section

        For detailed examples and patterns: task-orchestrator://docs/tools/add-section
    """

    override val parameterSchema = Tool.Input(
        properties = JsonObject(
            mapOf(
                "entityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of entity to attach this section to: 'PROJECT', 'TASK', or 'FEATURE'"),
                        "enum" to JsonArray(EntityType.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "entityId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID (UUID) of the project, task, or feature to add the section to (e.g., '550e8400-e29b-41d4-a716-446655440000')"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Section title (e.g., 'Requirements', 'Implementation Notes', 'Testing Plan')")
                    )
                ),
                "usageDescription" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Description of how this section should be used by AI models or users")
                    )
                ),
                "content" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The actual content of the section in the specified format")
                    )
                ),
                "contentFormat" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Format of the content: 'MARKDOWN' for formatted text, 'PLAIN_TEXT' for unformatted text, 'JSON' for data, 'CODE' for source code"),
                        "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) }),
                        "default" to JsonPrimitive(ContentFormat.MARKDOWN.name)
                    )
                ),
                "ordinal" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Order position (0-based). Lower numbers appear first when ordered."),
                        "minimum" to JsonPrimitive(0)
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of tags for categorization (e.g., 'documentation,frontend,api')")
                    )
                )
            )
        ),
        required = listOf("entityType", "entityId", "title", "usageDescription", "content", "ordinal")
    )

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        try {
            // Parse parameters
            val paramsObj = params as? JsonObject ?: return errorResponse("Parameters must be a JSON object")

            val entityTypeStr = paramsObj["entityType"]?.jsonPrimitive?.content
                ?: return errorResponse("Entity type is required")

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
                ?: return errorResponse("Entity ID is required")

            val entityId = try {
                UUID.fromString(entityIdStr)
            } catch (_: IllegalArgumentException) {
                return errorResponse(
                    message = "Invalid entity ID format: $entityIdStr. Must be a valid UUID.",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            val title = paramsObj["title"]?.jsonPrimitive?.content
                ?: return errorResponse(
                    message = "Title is required",
                    code = ErrorCodes.VALIDATION_ERROR
                )

            val usageDescription = paramsObj["usageDescription"]?.jsonPrimitive?.content
                ?: return errorResponse(
                    message = "Usage description is required",
                    code = ErrorCodes.VALIDATION_ERROR
                )

            val content = paramsObj["content"]?.jsonPrimitive?.content
                ?: return errorResponse(
                    message = "Content is required",
                    code = ErrorCodes.VALIDATION_ERROR
                )

            val contentFormatStr = paramsObj["contentFormat"]?.jsonPrimitive?.content ?: ContentFormat.MARKDOWN.name
            val contentFormat = try {
                ContentFormat.valueOf(contentFormatStr)
            } catch (_: IllegalArgumentException) {
                return errorResponse(
                    message = "Invalid content format: $contentFormatStr. Must be one of: ${
                        ContentFormat.entries.joinToString()
                    }",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            val ordinalValue = paramsObj["ordinal"]
            val ordinal = when {
                ordinalValue is JsonPrimitive && ordinalValue.isString ->
                    ordinalValue.content.toIntOrNull()
                        ?: return errorResponse(
                            message = "Ordinal must be a non-negative integer",
                            code = ErrorCodes.VALIDATION_ERROR
                        )

                ordinalValue is JsonPrimitive && ordinalValue.intOrNull != null ->
                    ordinalValue.int

                else -> return errorResponse(
                    message = "Ordinal is required and must be a non-negative integer",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            if (ordinal < 0) {
                return errorResponse(
                    message = "Ordinal must be a non-negative integer",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            // Parse tags - either as a string or array
            val tags = when (val tagsValue = paramsObj["tags"]) {
                is JsonPrimitive -> tagsValue.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: emptyList()

                is JsonArray -> tagsValue.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    .filter { it.isNotEmpty() }

                else -> emptyList()
            }

            // Verify the entity exists before adding a section to it
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

                EntityType.TEMPLATE -> {
                    val templateResult = context.templateRepository().getTemplate(entityId)
                    if (templateResult is Result.Error) {
                        return handleRepositoryError(templateResult.error, "template")
                    }
                }

                else -> {} // No validation needed for other entity types
            }

            // Create the section
            val section = Section(
                entityType = entityType,
                entityId = entityId,
                title = title,
                usageDescription = usageDescription,
                content = content,
                contentFormat = contentFormat,
                ordinal = ordinal,
                tags = tags
            )

            // Add the section to the repository
            val result = context.sectionRepository().addSection(entityType, entityId, section)

            return handleRepositoryResult(result, "Section added successfully") { addedSection ->
                buildJsonObject {
                    put("section", buildJsonObject {
                        put("id", addedSection.id.toString())
                        put("entityType", addedSection.entityType.name)
                        put("entityId", addedSection.entityId.toString())
                        put("title", addedSection.title)
                        put("contentFormat", addedSection.contentFormat.name)
                        put("ordinal", addedSection.ordinal)
                        put("createdAt", addedSection.createdAt.toString())
                    })
                }
            }

        } catch (e: Exception) {
            logger.error("Error adding section: ${e.message}", e)
            throw e // Let SimpleLockAwareToolDefinition handle the error formatting
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