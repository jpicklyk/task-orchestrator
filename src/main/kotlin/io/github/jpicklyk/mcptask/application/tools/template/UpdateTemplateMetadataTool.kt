package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * Tool for updating a template's metadata without affecting its sections.
 * This allows efficient context usage by targeting only specific fields.
 */
class UpdateTemplateMetadataTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT
    override val name: String = "update_template_metadata"

    override val description: String = """Updates a template's metadata (name, description, tags, etc.) 
        without affecting its sections. Protected templates cannot be updated.
        
        This tool allows you to update specific metadata fields without having to provide
        the entire template content, which is more efficient for context usage.
        
        Parameters:
        - id (required): UUID of the template to update
        - name (optional): New template name
        - description (optional): New template description
        - targetEntityType (optional): New target entity type (TASK, FEATURE)
        - isEnabled (optional): Whether the template is enabled
        - tags (optional): Comma-separated list of new tags
        
        Validation Rules:
        - Template must exist
        - Protected templates cannot be updated
        - Name must not be empty if provided
        - No name conflict with existing templates
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the template to update"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New template name")
                    )
                ),
                "description" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New template description")
                    )
                ),
                "targetEntityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("New target entity type (TASK, FEATURE)"),
                        "enum" to JsonArray(listOf("TASK", "FEATURE").map { JsonPrimitive(it) })
                    )
                ),
                "isEnabled" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether the template is enabled")
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
            throw ToolValidationException("Invalid template ID format. Must be a valid UUID.")
        }

        // Validate optional targetEntityType if provided
        if (params is JsonObject && params.containsKey("targetEntityType")) {
            val targetEntityTypeStr = optionalString(params, "targetEntityType", "")
            if (targetEntityTypeStr.isNotEmpty()) {
                try {
                    EntityType.valueOf(targetEntityTypeStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException(
                        "Invalid target entity type: $targetEntityTypeStr. Must be one of: TASK, FEATURE"
                    )
                }
            }
        }

        // Other optional parameters don't need special validation
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing update_template_metadata tool")

        try {
            // Extract ID parameter
            val idStr = requireString(params, "id")
            val templateId = UUID.fromString(idStr)

            // Get the existing template
            val getTemplateResult = context.templateRepository().getTemplate(templateId)
            if (getTemplateResult is Result.Error) {
                when (getTemplateResult.error) {
                    is RepositoryError.NotFound -> {
                        return errorResponse(
                            message = "Template not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No template exists with ID $templateId"
                        )
                    }

                    else -> {
                        return errorResponse(
                            message = "Failed to retrieve template",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = getTemplateResult.error.toString()
                        )
                    }
                }
            }

            val existingTemplate = (getTemplateResult as Result.Success).data

            // Check if the template is protected
            if (existingTemplate.isProtected) {
                return errorResponse(
                    message = "Cannot update protected template",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "Template with ID $templateId is protected and cannot be updated"
                )
            }

            // Prepare updated values
            val name = optionalString(params, "name", existingTemplate.name)
            if (name.isBlank()) {
                return errorResponse(
                    message = "Template name cannot be empty",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            val description = optionalString(params, "description", existingTemplate.description)
            if (description.isBlank()) {
                return errorResponse(
                    message = "Template description cannot be empty",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            val targetEntityTypeStr = optionalString(params, "targetEntityType", existingTemplate.targetEntityType.name)
            val targetEntityType = try {
                EntityType.valueOf(targetEntityTypeStr)
            } catch (_: IllegalArgumentException) {
                return errorResponse(
                    message = "Invalid target entity type",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "Target entity type must be one of: TASK, FEATURE"
                )
            }

            val isEnabled = optionalBoolean(params, "isEnabled", existingTemplate.isEnabled)

            val tagsStr = optionalString(params, "tags", "")
            val tags = if (tagsStr.isNotEmpty()) {
                tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                existingTemplate.tags
            }

            // Create the updated template
            val updatedTemplate = existingTemplate.copy(
                name = name,
                description = description,
                targetEntityType = targetEntityType,
                isEnabled = isEnabled,
                tags = tags
            ).withUpdatedModificationTime()

            // Update the template in the repository
            val updateResult = context.templateRepository().updateTemplate(updatedTemplate)

            return when (updateResult) {
                is Result.Success -> {
                    val data = buildJsonObject {
                        put("template", serializeTemplate(updateResult.data))
                    }
                    successResponse(data, "Template metadata updated successfully")
                }

                is Result.Error -> {
                    when (updateResult.error) {
                        is RepositoryError.ConflictError -> {
                            errorResponse(
                                message = "Template name already exists",
                                code = ErrorCodes.CONFLICT_ERROR,
                                details = "A template with the name '$name' already exists"
                            )
                        }

                        is RepositoryError.ValidationError -> {
                            errorResponse(
                                message = updateResult.error.message,
                                code = ErrorCodes.VALIDATION_ERROR
                            )
                        }

                        else -> {
                            errorResponse(
                                message = "Failed to update template",
                                code = ErrorCodes.DATABASE_ERROR,
                                details = updateResult.error.toString()
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error updating template metadata", e)
            return errorResponse(
                message = "Failed to update template metadata",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Helper method to serialize a Template to JsonObject
     */
    private fun serializeTemplate(template: io.github.jpicklyk.mcptask.domain.model.Template): JsonObject {
        return buildJsonObject {
            put("id", template.id.toString())
            put("name", template.name)
            put("description", template.description)
            put("targetEntityType", template.targetEntityType.name)
            put("isBuiltIn", template.isBuiltIn)
            put("isProtected", template.isProtected)
            put("isEnabled", template.isEnabled)
            put("createdBy", template.createdBy)
            put("tags", JsonArray(template.tags.map { JsonPrimitive(it) }))
            put("createdAt", template.createdAt.toString())
            put("modifiedAt", template.modifiedAt.toString())
        }
    }
}