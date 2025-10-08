package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool for adding a section to a template.
 * 
 * This tool allows creating structured section definitions that will be instantiated
 * when the template is applied to a task or feature.
 */
class AddTemplateSectionTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "add_template_section"

    override val title: String = "Add Section to Template"

    override val description: String = """Add a section to a template.
        
        Templates define a structured documentation pattern with multiple sections.
        This tool adds a section definition to a template, which will be created
        when the template is applied to tasks or features.
        
        Example successful response:
        {
          "success": true,
          "message": "Template section added successfully",
          "data": {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "templateId": "661e8511-f30c-41d4-a716-557788990000",
            "title": "Implementation Steps",
            "usageDescription": "Detailed steps to implement this task",
            "contentSample": "1. First step\\n2. Second step\\n3. Third step",
            "contentFormat": "markdown",
            "ordinal": 1,
            "isRequired": true,
            "tags": ["implementation", "steps"]
          }
        }
        
        Common error responses:
        - VALIDATION_ERROR: When provided parameters fail validation
        - RESOURCE_NOT_FOUND: When the template doesn't exist
        - DATABASE_ERROR: When there's an issue storing the section
        - INTERNAL_ERROR: For unexpected system errors
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "templateId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the template to add the section to"),
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
                "contentSample" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Sample content that will be used when creating from the template")
                    )
                ),
                "contentFormat" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Format of the content"),
                        "default" to JsonPrimitive(ContentFormat.MARKDOWN.name),
                        "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "ordinal" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Order position (0-based). Lower numbers appear first when ordered."),
                        "minimum" to JsonPrimitive(0)
                    )
                ),
                "isRequired" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether this section is required when applying the template"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of tags for categorization")
                    )
                )
            )
        ),
        required = listOf("templateId", "title", "usageDescription", "contentSample", "ordinal")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val templateIdStr = requireString(params, "templateId")
        requireString(params, "title")
        requireString(params, "usageDescription")
        requireString(params, "contentSample")

        // Validate template ID format
        try {
            UUID.fromString(templateIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid template ID format. Must be a valid UUID.")
        }

        // Validate ordinal
        val ordinal = requireInt(params, "ordinal")
        if (ordinal < 0) {
            throw ToolValidationException("Ordinal must be a non-negative integer")
        }

        // Validate content format if present
        optionalString(params, "contentFormat")?.let { formatString ->
            try {
                ContentFormat.valueOf(formatString)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException(
                    "Invalid content format: $formatString. Must be one of: ${ContentFormat.entries.joinToString()}"
                )
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing add_template_section tool")

        try {
            // Extract parameters
            val templateIdStr = requireString(params, "templateId")
            val templateId = UUID.fromString(templateIdStr)

            val title = requireString(params, "title")
            val usageDescription = requireString(params, "usageDescription")
            val contentSample = requireString(params, "contentSample")
            val ordinal = requireInt(params, "ordinal")

            // Extract optional parameters with defaults
            val contentFormatStr = optionalString(params, "contentFormat") ?: ContentFormat.MARKDOWN.name
            val contentFormat = ContentFormat.valueOf(contentFormatStr)

            val isRequired = optionalBoolean(params, "isRequired", false)

            val tagsStr = optionalString(params, "tags")
            val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            // First check if template exists
            val templateResult = context.repositoryProvider.templateRepository().getTemplate(templateId)

            if (templateResult is Result.Error) {
                return if (templateResult.error is RepositoryError.NotFound) {
                    errorResponse(
                        message = "Template not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No template exists with ID $templateId"
                    )
                } else {
                    errorResponse(
                        message = "Failed to retrieve template",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = templateResult.error.toString()
                    )
                }
            }

            // Create a template section
            val templateSection = TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = title,
                usageDescription = usageDescription,
                contentSample = contentSample,
                contentFormat = contentFormat,
                ordinal = ordinal,
                isRequired = isRequired,
                tags = tags
            )

            // Validate section
            try {
                templateSection.validate()
            } catch (e: IllegalArgumentException) {
                return errorResponse(
                    message = e.message ?: "Template section validation failed",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            // Add a section to template
            val addResult = context.repositoryProvider.templateRepository()
                .addTemplateSection(templateId, templateSection)

            return when (addResult) {
                is Result.Success -> {
                    val section = addResult.data

                    // Build the response
                    val responseData = buildJsonObject {
                        put("id", section.id.toString())
                        put("templateId", section.templateId.toString())
                        put("title", section.title)
                        put("usageDescription", section.usageDescription)
                        put("contentSample", section.contentSample)
                        put("contentFormat", section.contentFormat.name.lowercase())
                        put("ordinal", section.ordinal)
                        put("isRequired", section.isRequired)
                        put("tags", buildJsonArray {
                            section.tags.forEach { add(it) }
                        })
                    }

                    successResponse(responseData, "Template section added successfully")
                }

                is Result.Error -> {
                    when (addResult.error) {
                        is RepositoryError.NotFound -> {
                            errorResponse(
                                message = "Template not found",
                                code = ErrorCodes.RESOURCE_NOT_FOUND,
                                details = addResult.error.message
                            )
                        }

                        is RepositoryError.ValidationError -> {
                            errorResponse(
                                message = addResult.error.message,
                                code = ErrorCodes.VALIDATION_ERROR
                            )
                        }

                        else -> {
                            errorResponse(
                                message = "Failed to add template section",
                                code = ErrorCodes.DATABASE_ERROR,
                                details = addResult.error.message
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error adding template section", e)
            return errorResponse(
                message = "Failed to add template section",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}
