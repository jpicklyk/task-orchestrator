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

    override val description: String = """Retrieve a list of templates with optional filtering.
        
        This tool allows you to list all available templates with filtering options to narrow down results.
        Returns a context-efficient summary of templates for browsing.
        
        Example successful response:
        {
          "success": true,
          "message": "Retrieved 3 templates",
          "data": {
            "templates": [
              {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "name": "User Authentication",
                "description": "Template for authentication features",
                "targetEntityType": "FEATURE",
                "isBuiltIn": true,
                "isProtected": true,
                "isEnabled": true,
                "tags": ["auth", "security"]
              },
              {
                "id": "661e8511-f30c-41d4-a716-557788990000",
                "name": "API Endpoint",
                "description": "Template for API implementation tasks",
                "targetEntityType": "TASK",
                "isBuiltIn": true,
                "isProtected": false,
                "isEnabled": true,
                "tags": ["api", "backend"]
              },
              {
                "id": "772f9622-g41d-52e5-b827-668899101111",
                "name": "Database Schema",
                "description": "Template for database design tasks",
                "targetEntityType": "TASK",
                "isBuiltIn": false,
                "isProtected": false,
                "isEnabled": true,
                "tags": ["database", "schema"]
              }
            ],
            "count": 3,
            "filters": {
              "targetEntityType": "Any",
              "isBuiltIn": "Any",
              "isEnabled": "true",
              "tags": "Any"
            }
          }
        }
        
        Common error responses:
        - VALIDATION_ERROR: When provided parameters fail validation
        - DATABASE_ERROR: When there's an issue retrieving templates
        - INTERNAL_ERROR: For unexpected system errors
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
