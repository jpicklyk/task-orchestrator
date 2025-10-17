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

    override val title: String = "Bulk Create Sections"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Bulk creation results"),
                        "properties" to JsonObject(
                            mapOf(
                                "items" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Created sections"),
                                        "items" to JsonObject(
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
                                ),
                                "count" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                "failed" to JsonObject(mapOf("type" to JsonPrimitive("integer")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description = """Creates multiple sections in a single operation. More efficient than multiple
        add_section calls due to atomic transaction and single network round-trip. Use for initial section
        sets (3+ sections) or template-based content creation.

        Key features:
        - Atomic operation (all sections succeed or all fail)
        - Single database transaction
        - Maintains section ordering via ordinal field
        - Supports all content formats (MARKDOWN, PLAIN_TEXT, JSON, CODE)

        | Field | Type | Required | Default | Description |
        | sections | array | Yes | - | Array of section objects to create |

        Each section object requires:
        - entityType: TASK, FEATURE, or PROJECT
        - entityId: UUID of parent entity
        - title: Section heading
        - usageDescription: Purpose description for AI/users
        - content: Section content
        - ordinal: Display order (0-based)
        - contentFormat: Format type (default: MARKDOWN)
        - tags: Comma-separated tags (optional)

        Usage notes:
        - All sections must belong to same entity
        - Operation fails if any section validation fails
        - For single sections, use add_section instead
        - Section title becomes ## H2 heading in markdown output - do NOT duplicate in content field

        Related: add_section, get_sections, bulk_update_sections, bulk_delete_sections

        For detailed examples and patterns: task-orchestrator://docs/tools/bulk-create-sections
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