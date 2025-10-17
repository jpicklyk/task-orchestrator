package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for listing templates with various filtering options.
 *
 * This tool supports filtering templates by target entity type, built-in status,
 * enabled status, and tags, allowing for efficient template discovery.
 */
class ListTemplatesTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "list_templates"

    override val title: String = "List Available Templates"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("List of templates with filtering info"),
                        "properties" to JsonObject(
                            mapOf(
                                "templates" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                        "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "description" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "targetEntityType" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "isBuiltIn" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                                                        "isProtected" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                                                        "isEnabled" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                                                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("array")))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "count" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                "filters" to JsonObject(mapOf("type" to JsonPrimitive("object")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """Lists available templates with optional filtering. CRITICAL: Always check available templates before creating tasks/features for consistent documentation.

        Template Categories:
        - AI Workflow Instructions: Local Git Branching, GitHub PR, Task Implementation, Bug Investigation workflows
        - Documentation Properties: Technical Approach, Requirements Specification, Context & Background
        - Process & Quality: Testing Strategy, Definition of Done

        Parameters:
        - targetEntityType (optional): Filter by TASK or FEATURE
        - isBuiltIn (optional): Filter for built-in templates
        - isEnabled (optional): Filter for enabled templates
        - tags (optional): Comma-separated tag filters

        Recommended Combinations:
        - Implementation: Technical Approach + Task Implementation + Testing Strategy
        - Bug Fixes: Bug Investigation + Technical Approach + Definition of Done
        - Feature Planning: Requirements + Context & Background + Testing Strategy

        Usage notes:
        - Filter by targetEntityType to match creation needs
        - Use isEnabled=true to see only available templates
        - Built-in templates provide proven workflow patterns
        - Templates are composable - mix and match as needed

        Related tools: create_task, create_feature, apply_template, get_template

For detailed examples and patterns: task-orchestrator://docs/tools/list-templates
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "targetEntityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by entity type (TASK or FEATURE)"),
                        "enum" to JsonArray(listOf("TASK", "FEATURE").map { JsonPrimitive(it) })
                    )
                ),
                "isBuiltIn" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Filter for built-in templates only")
                    )
                ),
                "isEnabled" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Filter for enabled templates only")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by tags (comma-separated)")
                    )
                )
            )
        ),
        required = listOf()
    )

    override fun validateParams(params: JsonElement) {
        // Validate targetEntityType if present
        optionalString(params, "targetEntityType")?.let { entityType ->
            try {
                EntityType.valueOf(entityType)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid target entity type: $entityType. Must be 'TASK' or 'FEATURE'")
            }
        }

        // Boolean parameters (isBuiltIn, isEnabled) don't need special validation

        // Tags parameter doesn't need special validation
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing list_templates tool")

        try {
            // Extract filter parameters
            val targetEntityTypeStr = optionalString(params, "targetEntityType")
            val targetEntityType = targetEntityTypeStr?.let {
                try {
                    EntityType.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    return errorResponse(
                        message = "Invalid target entity type: $it",
                        code = ErrorCodes.VALIDATION_ERROR
                    )
                }
            }

            val isBuiltIn = if (params.jsonObject.containsKey("isBuiltIn")) {
                optionalBoolean(params, "isBuiltIn")
            } else {
                null
            }

            val isEnabled = if (params.jsonObject.containsKey("isEnabled")) {
                optionalBoolean(params, "isEnabled")
            } else {
                null
            }

            val tagsStr = optionalString(params, "tags")
            val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

            // Retrieve templates from repository
            val result = context.repositoryProvider.templateRepository()
                .getAllTemplates(targetEntityType, isBuiltIn, isEnabled, tags)

            return when (result) {
                is Result.Success -> {
                    val templates = result.data

                    // Build the response
                    val responseData = buildJsonObject {
                        put("templates", buildJsonArray {
                            templates.forEach { template ->
                                add(buildJsonObject {
                                    put("id", template.id.toString())
                                    put("name", template.name)
                                    put("description", template.description)
                                    put("targetEntityType", template.targetEntityType.name)
                                    put("isBuiltIn", template.isBuiltIn)
                                    put("isProtected", template.isProtected)
                                    put("isEnabled", template.isEnabled)
                                    put("tags", buildJsonArray {
                                        template.tags.forEach { add(it) }
                                    })
                                })
                            }
                        })

                        put("count", templates.size)

                        // Include the filters that were applied
                        put("filters", buildJsonObject {
                            put("targetEntityType", targetEntityTypeStr ?: "Any")
                            put("isBuiltIn", isBuiltIn?.toString() ?: "Any")
                            put("isEnabled", isEnabled?.toString() ?: "Any")
                            put("tags", tagsStr ?: "Any")
                        })
                    }

                    // Create an appropriate message based on the number of templates found
                    val message = when {
                        templates.isEmpty() -> "No templates found matching criteria"
                        templates.size == 1 -> "Retrieved 1 template"
                        else -> "Retrieved ${templates.size} templates"
                    }

                    successResponse(responseData, message)
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to retrieve templates",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = result.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error listing templates", e)
            return errorResponse(
                message = "Failed to list templates",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}
