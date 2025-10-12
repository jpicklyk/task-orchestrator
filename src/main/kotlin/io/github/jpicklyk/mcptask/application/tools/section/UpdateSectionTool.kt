package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Tool for updating an existing section.
 */
class UpdateSectionTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category = ToolCategory.TASK_MANAGEMENT

    override val name = "update_section"

    override val title: String = "Update Section"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The updated section object"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "usageDescription" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "content" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "contentFormat" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "ordinal" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
                                "modifiedAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description = """Updates an existing section by its ID.

        ⚡ **EFFICIENCY TIP**: Only send fields you want to change! All fields except 'id' are optional.
        For content-only changes, use 'update_section_text' (more efficient). Example: {"id": "uuid", "title": "New Title"}

        ## Efficient vs Inefficient Updates

        ❌ **INEFFICIENT** (wastes ~300+ characters):
        ```json
        {
          "id": "section-uuid",
          "title": "Existing Title",                        // Unchanged - unnecessary
          "usageDescription": "Existing description...",    // Unchanged - unnecessary
          "content": "Long existing content...",            // Unchanged - 300+ chars wasted
          "contentFormat": "MARKDOWN",                      // Unchanged - unnecessary
          "ordinal": 0,                                     // Unchanged - unnecessary
          "tags": "tag1,tag2"                              // ✓ Only this changed
        }
        ```

        ✅ **EFFICIENT** (uses ~40 characters):
        ```json
        {
          "id": "section-uuid",
          "tags": "tag1,tag2,tag3"  // Only send what changed!
        }
        ```

        **Token Savings**: 88% reduction by only sending changed fields!

        ## Alternative Efficient Tools
        - For content changes: Use `update_section_text` (90%+ more efficient)
        - For metadata only: Use `update_section_metadata` (excludes content)
        - For full replacement: Use this tool with selective fields
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the section to update"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New section title")
                    )
                ),
                "usageDescription" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New usage description for the section")
                    )
                ),
                "content" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New section content")
                    )
                ),
                "contentFormat" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New format of the content (MARKDOWN, PLAIN_TEXT, JSON, CODE)"),
                        "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "ordinal" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("(optional) New display order position (0-based)"),
                        "minimum" to JsonPrimitive(0)
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) Comma-separated list of new tags")
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required ID parameter
        val idStr = requireString(params, "id")

        // Validate ID format (must be a valid UUID)
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid section ID format. Must be a valid UUID.")
        }

        // Validate optional parameters
        // Validate content format if provided
        if (params is JsonObject && params.containsKey("contentFormat")) {
            val contentFormatStr = optionalString(params, "contentFormat", "")
            if (contentFormatStr.isNotEmpty()) {
                try {
                    ContentFormat.valueOf(contentFormatStr.uppercase())
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException(
                        "Invalid content format. Must be one of: ${
                            ContentFormat.entries.joinToString(
                                ", "
                            )
                        }"
                    )
                }
            }
        }

        // Validate ordinal if provided
        if (params is JsonObject && params.containsKey("ordinal")) {
            val ordinalValue = params["ordinal"]
            if (ordinalValue is JsonPrimitive) {
                val ordinal = when {
                    ordinalValue.isString -> ordinalValue.content.toIntOrNull() ?: -1
                    ordinalValue.content.toString().toIntOrNull() != null -> ordinalValue.content.toString().toInt()
                    else -> -1
                }
                if (ordinal < 0) {
                    throw ToolValidationException("Ordinal must be a non-negative integer")
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing update_section tool")

        try {
            // Extract ID parameter
            val idStr = requireString(params, "id")
            val sectionId = UUID.fromString(idStr)

            // Get the existing section
            val getSectionResult = context.sectionRepository().getSection(sectionId)
            if (getSectionResult is Result.Error) {
                when (getSectionResult.error) {
                    is RepositoryError.NotFound -> {
                        return errorResponse(
                            message = "Section not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No section exists with ID $sectionId"
                        )
                    }

                    else -> {
                        return errorResponse(
                            message = "Failed to retrieve section",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = getSectionResult.error.toString()
                        )
                    }
                }
            }

            val existingSection = (getSectionResult as Result.Success).data

            // Prepare updated values
            val title = optionalString(params, "title", existingSection.title)
            val usageDescription = optionalString(params, "usageDescription", existingSection.usageDescription)
            val content = optionalString(params, "content", existingSection.content)

            val contentFormatStr = optionalString(params, "contentFormat", "")
            val contentFormat = if (contentFormatStr.isNotEmpty()) {
                try {
                    ContentFormat.valueOf(contentFormatStr.uppercase())
                } catch (e: IllegalArgumentException) {
                    existingSection.contentFormat
                }
            } else {
                existingSection.contentFormat
            }

            val ordinal = if (params is JsonObject && params.containsKey("ordinal")) {
                val ordinalValue = params["ordinal"]
                if (ordinalValue is JsonPrimitive) {
                    when {
                        ordinalValue.isString -> ordinalValue.content.toIntOrNull() ?: existingSection.ordinal
                        ordinalValue.content.toString().toIntOrNull() != null -> ordinalValue.content.toString().toInt()
                        else -> existingSection.ordinal
                    }
                } else {
                    existingSection.ordinal
                }
            } else {
                existingSection.ordinal
            }

            val tagsStr = optionalString(params, "tags", "")
            val tags = if (tagsStr.isNotEmpty()) {
                tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                existingSection.tags
            }

            // Create the updated section
            val updatedSection = existingSection.copy(
                title = title,
                usageDescription = usageDescription,
                content = content,
                contentFormat = contentFormat,
                ordinal = ordinal,
                tags = tags,
                modifiedAt = Instant.now()
            )

            // Update the section in the repository
            val updateResult = context.sectionRepository().updateSection(updatedSection)

            return when (updateResult) {
                is Result.Success -> {
                    val updatedSection = updateResult.data

                    // Return minimal response to optimize bandwidth and performance
                    // Sections don't have a status field, so only return id and modifiedAt
                    val data = buildJsonObject {
                        put("id", updatedSection.id.toString())
                        put("modifiedAt", updatedSection.modifiedAt.toString())
                    }
                    successResponse(data, "Section updated successfully")
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to update section",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = updateResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error updating section", e)
            return errorResponse(
                message = "Failed to update section",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Helper method to serialize a Section to JsonObject
     */
    private fun serializeSection(section: io.github.jpicklyk.mcptask.domain.model.Section): JsonObject {
        return buildJsonObject {
            put("id", section.id.toString())
            put("entityType", section.entityType.name.lowercase())
            put("entityId", section.entityId.toString())
            put("title", section.title)
            put("usageDescription", section.usageDescription)
            put("content", section.content)
            put("contentFormat", section.contentFormat.name.lowercase())
            put("ordinal", section.ordinal)
            put("tags", JsonArray(section.tags.map { JsonPrimitive(it) }))
            put("createdAt", section.createdAt.toString())
            put("modifiedAt", section.modifiedAt.toString())
        }
    }
}