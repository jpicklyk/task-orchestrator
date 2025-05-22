package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Tool for updating a section's metadata without affecting its content.
 * This allows efficient context usage by targeting only specific fields.
 */
class UpdateSectionMetadataTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT
    override val name: String = "update_section_metadata"

    override val description: String = """Updates a section's metadata (title, usage description, format, ordinal, tags)
        without affecting its content.
        
        This tool allows you to update specific metadata fields without having to provide
        the entire section content, which is more efficient for context usage.
        
        Parameters:
        - id (required): UUID of the section to update
        - title (optional): New section title
        - usageDescription (optional): New usage description for the section
        - contentFormat (optional): New format of the content
        - ordinal (optional): New display order position (0-based)
        - tags (optional): Comma-separated list of new tags
        
        Validation Rules:
        - Section must exist
        - Parent template must not be protected
        - Title must not be empty if provided
        - Ordinal must be a non-negative integer
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
                        "description" to JsonPrimitive("New section title")
                    )
                ),
                "usageDescription" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New usage description for the section")
                    )
                ),
                "contentFormat" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New format of the content (MARKDOWN, PLAIN_TEXT, JSON, CODE)"),
                        "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "ordinal" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("New display order position (0-based)"),
                        "minimum" to JsonPrimitive(0)
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of new tags")
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

        // Validate content format if provided
        if (params is JsonObject && params.containsKey("contentFormat")) {
            val contentFormatStr = optionalString(params, "contentFormat", "")
            if (contentFormatStr.isNotEmpty()) {
                try {
                    ContentFormat.valueOf(contentFormatStr.uppercase())
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException(
                        "Invalid content format. Must be one of: ${
                            ContentFormat.entries.joinToString(", ")
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
                    ordinalValue.content.toIntOrNull() != null -> ordinalValue.content.toInt()
                    else -> -1
                }
                if (ordinal < 0) {
                    throw ToolValidationException("Ordinal must be a non-negative integer")
                }
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing update_section_metadata tool")

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
            if (title.isBlank()) {
                return errorResponse(
                    message = "Section title cannot be empty",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            val usageDescription = optionalString(params, "usageDescription", existingSection.usageDescription)
            if (usageDescription.isBlank()) {
                return errorResponse(
                    message = "Section usage description cannot be empty",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            val contentFormatStr = optionalString(params, "contentFormat", "")
            val contentFormat = if (contentFormatStr.isNotEmpty()) {
                try {
                    ContentFormat.valueOf(contentFormatStr.uppercase())
                } catch (_: IllegalArgumentException) {
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
                        ordinalValue.content.toIntOrNull() != null -> ordinalValue.content.toInt()
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
                contentFormat = contentFormat,
                ordinal = ordinal,
                tags = tags,
                modifiedAt = Instant.now()
            )

            // Update the section in the repository
            val updateResult = context.sectionRepository().updateSection(updatedSection)

            return when (updateResult) {
                is Result.Success -> {
                    val data = buildJsonObject {
                        put("section", serializeSection(updateResult.data))
                    }
                    successResponse(data, "Section metadata updated successfully")
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to update section metadata",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = updateResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error updating section metadata", e)
            return errorResponse(
                message = "Failed to update section metadata",
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
            // Note: We don't include content for metadata update response for efficiency
            put("contentFormat", section.contentFormat.name.lowercase())
            put("ordinal", section.ordinal)
            put("tags", JsonArray(section.tags.map { JsonPrimitive(it) }))
            put("createdAt", section.createdAt.toString())
            put("modifiedAt", section.modifiedAt.toString())
        }
    }
}