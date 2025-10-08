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
 * MCP tool for deleting user-created templates.
 *
 * This tool allows AI agents to delete templates that are not system built-in templates.
 * It provides validation to prevent accidental deletion of important templates.
 */
class DeleteTemplateTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "delete_template"

    override val title: String = "Delete Template"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Deletion result"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "deleted" to JsonObject(mapOf("type" to JsonPrimitive("boolean")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """Deletes a user-created template. Built-in templates cannot be deleted.
        
        Parameters:
        - id (required): UUID of the template to delete
        - force (optional): Boolean flag to override protection, defaults to false
        
        Validation Rules:
        - Template must exist
        - Built-in templates cannot be deleted
        - If a user attempts to delete a built-in template, they will get back an error message
          instructing them to use 'disable_template' instead
        
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
                        "description" to JsonPrimitive("The unique ID of the template to delete"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "force" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to override protection (use with caution)"),
                        "default" to JsonPrimitive(false)
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

        // Optional force parameter doesn't need validation as it defaults to false
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing delete_template tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val templateId = UUID.fromString(idStr)
            val force = optionalBoolean(params, "force", false)

            // First, verify that the template exists and get its details
            val templateResult = context.repositoryProvider.templateRepository().getTemplate(templateId)

            when (templateResult) {
                is Result.Success -> {
                    val template = templateResult.data

                    // Check if it's a built-in template
                    if (template.isBuiltIn && !force) {
                        return errorResponse(
                            message = "Built-in templates cannot be deleted. Use 'disable_template' instead to make the template unavailable for use.",
                            code = ErrorCodes.VALIDATION_ERROR,
                            details = "Template '${template.name}' (id: ${template.id}) is a built-in template and cannot be deleted."
                        )
                    }

                    // Proceed with deletion
                    val deleteResult = context.repositoryProvider.templateRepository().deleteTemplate(templateId, force)

                    return when (deleteResult) {
                        is Result.Success -> {
                            val data = buildJsonObject {
                                put("id", template.id.toString())
                                put("name", template.name)
                            }
                            successResponse(data, "Template deleted successfully")
                        }

                        is Result.Error -> {
                            when (deleteResult.error) {
                                is RepositoryError.ValidationError -> {
                                    if (deleteResult.error.message.contains("protected")) {
                                        errorResponse(
                                            message = "Protected templates cannot be deleted without the force parameter.",
                                            code = ErrorCodes.VALIDATION_ERROR,
                                            details = "Template '${template.name}' is protected. Use force=true to override protection or use 'disable_template' instead."
                                        )
                                    } else {
                                        errorResponse(
                                            message = "Validation error",
                                            code = ErrorCodes.VALIDATION_ERROR,
                                            details = deleteResult.error.message
                                        )
                                    }
                                }

                                else -> {
                                    errorResponse(
                                        message = "Failed to delete template",
                                        code = ErrorCodes.DATABASE_ERROR,
                                        details = deleteResult.error.message
                                    )
                                }
                            }
                        }
                    }
                }

                is Result.Error -> {
                    if (templateResult.error is RepositoryError.NotFound) {
                        return errorResponse(
                            message = "Template not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No template exists with ID $templateId"
                        )
                    } else {
                        return errorResponse(
                            message = "Failed to retrieve template",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = templateResult.error.message
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error deleting template", e)
            return errorResponse(
                message = "Failed to delete template",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}