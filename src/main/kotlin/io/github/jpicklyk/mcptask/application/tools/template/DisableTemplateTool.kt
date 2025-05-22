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
 * MCP tool for disabling a template.
 * This prevents the template from being used while maintaining it in the system.
 * Disabled templates will not show up in search results or be available for application
 * to tasks or features, unless specifically requested.
 */
class DisableTemplateTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "disable_template"

    override val description: String = """Disables a template including system templates. Disabled templates are not available for use.
        
        Parameters:
        - id (required): UUID of the template to disable
        
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
                        "description" to JsonPrimitive("The unique ID of the template to disable"),
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
        logger.info("Executing disable_template tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val templateId = UUID.fromString(idStr)

            // Disable the template
            val disableResult = context.repositoryProvider.templateRepository().disableTemplate(templateId)

            return when (disableResult) {
                is Result.Success -> {
                    val template = disableResult.data
                    buildJsonObject {
                        put("success", true)
                        put("message", "Template disabled successfully")
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
                    when (disableResult.error) {
                        is RepositoryError.NotFound -> {
                            errorResponse(
                                message = "Template not found",
                                code = ErrorCodes.RESOURCE_NOT_FOUND,
                                details = "No template exists with ID $templateId"
                            )
                        }
                        else -> {
                            errorResponse(
                                message = "Failed to disable template",
                                code = ErrorCodes.DATABASE_ERROR,
                                details = disableResult.error.message
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error disabling template", e)
            return errorResponse(
                message = "Failed to disable template",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}