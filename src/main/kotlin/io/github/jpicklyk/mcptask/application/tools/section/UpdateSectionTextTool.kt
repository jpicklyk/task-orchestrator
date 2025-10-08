package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Tool for updating specific text within a section's content.
 * This allows efficient context usage by targeting only specific text segments.
 */
class UpdateSectionTextTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "update_section_text"

    override val title: String = "Update Section Text"

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
                                "content" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
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

    override val description: String = """Updates specific text within a section without requiring the entire content.
        This tool allows changing portions of section content by providing the text to replace
        and its replacement.
        
        ## Context Efficiency Strategy
        
        **PREFERRED** for targeted content updates in large sections:
        - Only send the specific text segment to replace and its replacement
        - Much more efficient than sending entire content for small changes
        - Ideal for correcting typos, updating specific paragraphs, or modifying parts of documentation
        - Significantly reduces token usage compared to full content updates
        
        **When to Use**:
        - Correcting typos in template-generated content
        - Updating specific values or references within larger documentation
        - Making incremental improvements to existing sections
        - Modifying parts of sections without affecting the overall structure
        
        **Usage Examples**:
        - Fixing typos: `oldText: "straegy"` → `newText: "strategy"`
        - Updating references: `oldText: "version 1.0"` → `newText: "version 2.0"`
        - Modifying template placeholders: `oldText: "[Insert details here]"` → `newText: "Actual implementation details"`
        
        **Compared to Other Update Tools**:
        - Use `update_section_text` for content changes (most efficient for partial updates)
        - Use `update_section_metadata` for title, format, ordinal, or tag changes
        - Use `update_section` for complete content replacement (less efficient)
        
        Parameters:
        - id (required): UUID of the section to update
        - oldText (required): The text segment to be replaced (must match exactly)
        - newText (required): The new text to replace the matched segment with
        
        Validation Rules:
        - Section must exist
        - Old text must exist in the section content
        - Both oldText and newText parameters must be provided
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
                "oldText" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The text segment to be replaced (must match exactly)")
                    )
                ),
                "newText" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The new text to replace the matched segment with")
                    )
                )
            )
        ),
        required = listOf("id", "oldText", "newText")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val idStr = requireString(params, "id")
        requireString(params, "oldText")
        requireString(params, "newText")

        // Validate ID format (must be a valid UUID)
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid section ID format. Must be a valid UUID.")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing update_section_text tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val oldText = requireString(params, "oldText")
            val newText = requireString(params, "newText")

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

            // Check if the old text exists in the content
            if (!existingSection.content.contains(oldText)) {
                return errorResponse(
                    message = "Text not found",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "The specified text to replace was not found in the section content"
                )
            }

            // Replace the text in the content
            val updatedContent = existingSection.content.replace(oldText, newText)

            // Create the updated section
            val updatedSection = existingSection.copy(
                content = updatedContent,
                modifiedAt = Instant.now()
            )

            // Update the section in the repository
            val updateResult = context.sectionRepository().updateSection(updatedSection)

            return when (updateResult) {
                is Result.Success -> {
                    val data = buildJsonObject {
                        put("success", true)
                        put("id", updatedSection.id.toString())
                        put("replacedTextLength", oldText.length)
                        put("newTextLength", newText.length)
                        // We don't include the full content for efficiency
                        put("contentPreview", truncateContent(updatedContent, 100))
                    }
                    successResponse(data, "Section text updated successfully")
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to update section text",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = updateResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error updating section text", e)
            return errorResponse(
                message = "Failed to update section text",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Helper method to truncate content for previews
     */
    private fun truncateContent(content: String, maxLength: Int): String {
        return if (content.length <= maxLength) {
            content
        } else {
            content.substring(0, maxLength) + "..."
        }
    }
}