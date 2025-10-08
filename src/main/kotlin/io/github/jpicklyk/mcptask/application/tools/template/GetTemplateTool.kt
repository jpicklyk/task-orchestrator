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
 * MCP tool for retrieving a complete template by ID with options for including template sections.
 * 
 * This tool provides detailed information about a template and optionally includes its sections,
 * supporting progressive loading of template details.
 */
class GetTemplateTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "get_template"

    override val title: String = "Get Template Details"

    override val description: String = "Retrieve a complete template by ID with options for including sections"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the template to retrieve"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "includeSections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to include sections in the response"),
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

        // Optional boolean parameters don't need validation as they default to false
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_template tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val templateId = UUID.fromString(idStr)
            val includeSections = optionalBoolean(params, "includeSections", false)

            // Retrieve template from repository
            val templateResult = context.repositoryProvider.templateRepository().getTemplate(templateId)

            // Handle result
            return when (templateResult) {
                is Result.Success -> {
                    val template = templateResult.data
                    val dataBuilder = buildJsonObject {
                        // Basic template information
                        put("id", template.id.toString())
                        put("name", template.name)
                        put("description", template.description)
                        put("targetEntityType", template.targetEntityType.name)
                        put("isBuiltIn", template.isBuiltIn)
                        put("isProtected", template.isProtected)
                        put("isEnabled", template.isEnabled)
                        put("createdBy", template.createdBy)
                        put("tags", buildJsonArray {
                            template.tags.forEach { add(it) }
                        })
                        put("createdAt", template.createdAt.toString())
                        put("modifiedAt", template.modifiedAt.toString())
                    }

                    // Include sections if requested
                    if (includeSections) {
                        try {
                            val sectionsResult = context.repositoryProvider.templateRepository()
                                .getTemplateSections(templateId)

                            when (sectionsResult) {
                                is Result.Success -> {
                                    val sections = sectionsResult.data
                                    val sectionsArray = buildJsonArray {
                                        sections.forEach { section ->
                                            add(buildJsonObject {
                                                put("id", section.id.toString())
                                                put("title", section.title)
                                                put("usageDescription", section.usageDescription)
                                                put("contentSample", section.contentSample)
                                                put("contentFormat", section.contentFormat.name.lowercase())
                                                put("ordinal", section.ordinal)
                                                put("isRequired", section.isRequired)
                                                put("tags", buildJsonArray {
                                                    section.tags.forEach { add(it) }
                                                })
                                            })
                                        }
                                    }

                                    // Add sections array to the data object
                                    val fullDataBuilder = JsonObject(dataBuilder.toMutableMap().apply {
                                        put("sections", sectionsArray)
                                    })

                                    successResponse(fullDataBuilder, "Template retrieved successfully")
                                }

                                is Result.Error -> {
                                    // If we can't get sections, still return the template but log the error
                                    logger.error("Failed to retrieve template sections: ${sectionsResult.error}")
                                    successResponse(
                                        dataBuilder,
                                        "Template retrieved successfully (sections retrieval failed)"
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            // If we encounter an error getting sections, still return the template
                            logger.error("Error retrieving template sections", e)
                            successResponse(dataBuilder, "Template retrieved successfully (sections retrieval failed)")
                        }
                    } else {
                        // Return just the template without sections
                        successResponse(dataBuilder, "Template retrieved successfully")
                    }
                }

                is Result.Error -> {
                    if (templateResult.error is RepositoryError.NotFound) {
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
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error retrieving template", e)
            return errorResponse(
                message = "Failed to retrieve template",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}
