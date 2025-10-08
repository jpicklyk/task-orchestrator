package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for enabling a previously disabled template.
 * This allows templates to be made available for use after they've been disabled.
 * Enabled templates show up in template lists and can be applied to tasks/features.
 */
class EnableTemplateTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "enable_template"

    override val title: String = "Enable Template"

    override val description: String = """Enables a previously disabled template, making it available for use again.
        
        Parameters:
        - id (required): UUID of the template to enable
        
        Validation Rules:
        - Template must exist
        
        Example:
        ```json
        {
          "id": "550e8400-e29b-41d4-a716-446655440000"
        }
        ```
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the template to enable"),
                        "format" to JsonPrimitive("uuid")
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
            throw ToolValidationException("Invalid template ID format. Must be a valid UUID.")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing enable_template tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val templateId = UUID.fromString(idStr)

            // Enable the template
            val enableResult = context.repositoryProvider.templateRepository().enableTemplate(templateId)

            return when (enableResult) {
                is Result.Success -> {
                    val template = enableResult.data
                    buildJsonObject {
                        put("success", true)
                        put("message", "Template enabled successfully")
                        put("data", buildJsonObject {
                            put("id", template.id.toString())
                            put("name", template.name)
                            put("description", template.description)
                            put("targetEntityType", template.targetEntityType.name)
                            put("isBuiltIn", template.isBuiltIn)
                            put("isProtected", template.isProtected)
                            put("isEnabled", template.isEnabled)
                            put("createdBy", template.createdBy?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("tags", buildJsonArray {
                                template.tags.forEach { add(JsonPrimitive(it)) }
                            })
                            put("createdAt", template.createdAt.toString())
                            put("modifiedAt", template.modifiedAt.toString())
                        })
                    }
                }
                is Result.Error -> {
                    when (enableResult.error) {
                        is RepositoryError.NotFound -> {
                            errorResponse(
                                message = "Template not found",
                                code = ErrorCodes.RESOURCE_NOT_FOUND,
                                details = "No template exists with ID $templateId"
                            )
                        }
                        else -> {
                            errorResponse(
                                message = "Failed to enable template",
                                code = ErrorCodes.DATABASE_ERROR,
                                details = enableResult.error.message
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error enabling template", e)
            return errorResponse(
                message = "Failed to enable template",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}