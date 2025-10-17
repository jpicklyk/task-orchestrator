package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import javax.swing.UIManager.put

/**
 * MCP tool for creating a new template with all required and optional properties.
 *
 * This tool allows the creation of structured document templates that can be applied
 * to features or tasks, providing consistent documentation patterns.
 */
class CreateTemplateTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "create_template"

    override val title: String = "Create New Template"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The created template object"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "description" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "targetEntityType" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf("TASK", "FEATURE").map { JsonPrimitive(it) }))),
                                "isBuiltIn" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                                "isProtected" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                                "isEnabled" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                                "createdBy" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                                "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
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

    override val description: String = """Creates a new template with required and optional fields. Templates provide reusable patterns for structuring task and feature documentation with consistent section definitions.

        Parameters:
        - name (required): Template name (e.g., 'User Authentication', 'Data Export')
        - description (required): Template purpose and scope
        - targetEntityType (required): TASK or FEATURE
        - isBuiltIn (optional): Whether template is built-in (default: false)
        - isProtected (optional): Whether template is protected from modification (default: false)
        - isEnabled (optional): Whether template is enabled for use (default: true)
        - createdBy (optional): Creator identifier
        - tags (optional): Comma-separated tags for categorization

        Usage notes:
        - After creating template, use add_template_section to add section definitions
        - Protected templates cannot be updated or deleted
        - Use list_templates to discover existing templates before creating new ones
        - Tags help with filtering and discovery

        Related tools: add_template_section, list_templates, apply_template

        For detailed examples and patterns: task-orchestrator://docs/tools/create-template
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Required template name (e.g., 'User Authentication', 'Data Export')")
                    )
                ),
                "description" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Required template description explaining its purpose and scope")
                    )
                ),
                "targetEntityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of entity this template is for: 'TASK' or 'FEATURE'"),
                        "enum" to JsonArray(listOf("TASK", "FEATURE").map { JsonPrimitive(it) })
                    )
                ),
                "isBuiltIn" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether this is a built-in template (default: false)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "isProtected" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether this template is protected from modification (default: false)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "isEnabled" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether this template is enabled for use (default: true)"),
                        "default" to JsonPrimitive(true)
                    )
                ),
                "createdBy" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Optional creator identifier (default: null)")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of tags for categorization (e.g., 'frontend,ui,critical')")
                    )
                )
            )
        ),
        required = listOf("name", "description", "targetEntityType")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        requireString(params, "name")
        requireString(params, "description")

        // Validate targetEntityType
        val targetEntityTypeStr = requireString(params, "targetEntityType")
        try {
            EntityType.valueOf(targetEntityTypeStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid target entity type: $targetEntityTypeStr. Must be 'TASK' or 'FEATURE'")
        }

        // Optional parameters don't need validation as they have defaults
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing create_template tool")

        try {
            // Extract parameters
            val name = requireString(params, "name")
            val description = requireString(params, "description")
            val targetEntityTypeStr = requireString(params, "targetEntityType")
            val targetEntityType = EntityType.valueOf(targetEntityTypeStr)
            
            // Extract optional parameters with defaults
            val isBuiltIn = optionalBoolean(params, "isBuiltIn", false)
            val isProtected = optionalBoolean(params, "isProtected", false)
            val isEnabled = optionalBoolean(params, "isEnabled", true)
            val createdBy = optionalString(params, "createdBy")

            // Parse tags
            val tags = optionalString(params, "tags")?.let {
                if (it.isEmpty()) emptyList()
                else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
            } ?: emptyList()

            // Create a template entity
            val template = Template(
                id = UUID.randomUUID(),
                name = name,
                description = description,
                targetEntityType = targetEntityType,
                isBuiltIn = isBuiltIn,
                isProtected = isProtected,
                isEnabled = isEnabled,
                createdBy = createdBy,
                tags = tags,
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )

            // Perform validation before saving
            try {
                template.validate()
            } catch (e: IllegalArgumentException) {
                return errorResponse(
                    message = e.message ?: "Template validation failed",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }

            // Create template in repository
            val result = context.repositoryProvider.templateRepository().createTemplate(template)

            return when (result) {
                is Result.Success -> {
                    // Map template to JSON response
                    val templateJson = buildJsonObject {
                        put("id", result.data.id.toString())
                        put("name", result.data.name)
                        put("description", result.data.description)
                        put("targetEntityType", result.data.targetEntityType.name)
                        put("isBuiltIn", result.data.isBuiltIn)
                        put("isProtected", result.data.isProtected)
                        put("isEnabled", result.data.isEnabled)
                        put("createdBy", result.data.createdBy ?: JsonNull)
                        put("tags", buildJsonArray {
                            result.data.tags.forEach { add(it) }
                        })
                        put("createdAt", result.data.createdAt.toString())
                        put("modifiedAt", result.data.modifiedAt.toString())
                    }

                    successResponse(templateJson, "Template created successfully")
                }

                is Result.Error -> {
                    logger.error("Failed to create template: ${result.error}")

                    // Return the appropriate error response based on an error type
                    when (result.error) {
                        is io.github.jpicklyk.mcptask.domain.repository.RepositoryError.ConflictError -> {
                            errorResponse(
                                message = "Template name already exists",
                                code = ErrorCodes.CONFLICT_ERROR,
                                details = result.error.message
                            )
                        }

                        is io.github.jpicklyk.mcptask.domain.repository.RepositoryError.ValidationError -> {
                            errorResponse(
                                message = result.error.message,
                                code = ErrorCodes.VALIDATION_ERROR
                            )
                        }

                        else -> {
                            errorResponse(
                                message = "Failed to create template",
                                code = ErrorCodes.DATABASE_ERROR,
                                details = result.error.message
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error creating template", e)
            return errorResponse(
                message = "Failed to create template",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}
