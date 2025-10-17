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

    override val description = """Retrieves sections for a task, feature, or project with optional filtering.
        Sections contain detailed content in structured format with specific purposes and ordering.

        Parameters:
        | Field | Type | Required | Default | Description |
        | entityType | enum | Yes | - | TASK, FEATURE, or PROJECT |
        | entityId | UUID | Yes | - | Entity identifier |
        | includeContent | boolean | No | true | Include section content (false for metadata only, 85-99% token savings) |
        | sectionIds | array | No | all | Specific section IDs to retrieve |
        | tags | string | No | - | Filter by tags (comma-separated, returns sections with ANY tag)

        Returns sections ordered by ordinal field (0-based). Each section includes: id, title,
        usageDescription, contentFormat, ordinal, tags, timestamps. Content included unless includeContent=false.

        Usage notes:
        - Set includeContent=false to browse structure without content (saves 85-99% tokens)
        - Use sectionIds for selective loading after browsing metadata
        - Tag filtering enables agent-specific content queries
        - Two-step workflow: browse metadata, then fetch specific sections

        Related: add_section, update_section, delete_section, bulk_create_sections

        For detailed examples and patterns: task-orchestrator://docs/tools/get-sections
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
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of tags to filter sections (e.g., 'requirements,technical-approach'). Returns sections that contain ANY of these tags. Useful for agents to query only relevant sections.")
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

            // Parse tags parameter
            val filterTags = paramsObj["tags"]?.jsonPrimitive?.content?.let { tagsString ->
                tagsString.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
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
                    val filteredBySectionIds = if (sectionIds != null) {
                        allSections.filter { section -> section.id in sectionIds }
                    } else {
                        allSections
                    }

                    // Apply tags filter if provided
                    val filteredSections = if (filterTags != null && filterTags.isNotEmpty()) {
                        filteredBySectionIds.filter { section ->
                            // Return sections that contain ANY of the filter tags (OR logic)
                            val sectionTags = section.tags.map { it.lowercase() }
                            filterTags.any { filterTag -> sectionTags.contains(filterTag) }
                        }
                    } else {
                        filteredBySectionIds
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