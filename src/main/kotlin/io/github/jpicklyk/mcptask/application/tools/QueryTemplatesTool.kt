package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * Consolidated MCP tool for read-only operations on templates.
 * Includes 2 operations: get and list.
 *
 * This tool unifies read operations for templates, reducing token overhead
 * and providing a consistent interface for AI agents to query template data.
 *
 * Part of v2.0's read/write permission separation strategy.
 */
class QueryTemplatesTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "query_templates"

    override val title: String = "Query Templates"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        ),
        required = listOf("success", "message")
    )

    override fun shouldUseLocking(): Boolean = false // Read-only operations don't need locking

    override val description: String = """Unified read operations for templates.

Operations: get, list

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | get, list |
| id | UUID | get only | Template ID |
| includeSections | boolean | get only | Include sections (default: false) |
| targetEntityType | enum | list only | TASK or FEATURE |
| isBuiltIn | boolean | list only | Filter built-in templates |
| isEnabled | boolean | list only | Filter enabled templates |
| tags | string | list only | Comma-separated tags |

Operations:
- get: Retrieve single template by ID with optional sections
- list: List templates with optional filtering

Usage: Consolidates get/list for templates (read-only).
Related: manage_templates (write operations)
Docs: task-orchestrator://docs/tools/query-templates
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(listOf("get", "list").map { JsonPrimitive(it) })
                    )
                ),
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Template ID (required for get operation)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "includeSections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include sections (get operation, default: false)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "targetEntityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by entity type (list operation)"),
                        "enum" to JsonArray(listOf("TASK", "FEATURE").map { JsonPrimitive(it) })
                    )
                ),
                "isBuiltIn" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Filter for built-in templates (list operation)")
                    )
                ),
                "isEnabled" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Filter for enabled templates (list operation)")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by tags (list operation, comma-separated)")
                    )
                )
            )
        ),
        required = listOf("operation")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")

        // Validate operation
        if (operation !in listOf("get", "list")) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: get, list")
        }

        // Validate ID for get operation
        if (operation == "get") {
            val idStr = requireString(params, "id")
            try {
                UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid template ID format. Must be a valid UUID.")
            }
        }

        // Validate optional parameters for list operation
        if (operation == "list") {
            validateListParams(params)
        }
    }

    private fun validateListParams(params: JsonElement) {
        // Validate targetEntityType if present
        optionalString(params, "targetEntityType")?.let { entityType ->
            if (entityType !in listOf("TASK", "FEATURE")) {
                throw ToolValidationException("Invalid target entity type: $entityType. Must be 'TASK' or 'FEATURE'")
            }
        }

        // Boolean parameters (isBuiltIn, isEnabled) don't need validation
        // Tags parameter doesn't need validation
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing query_templates tool")

        return try {
            val operation = requireString(params, "operation")

            when (operation) {
                "get" -> executeGet(params, context)
                "list" -> executeList(params, context)
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in query_templates: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in query_templates", e)
            errorResponse(
                message = "Failed to execute template query",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== GET OPERATION ==========

    private suspend fun executeGet(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get operation for template")

        val id = extractEntityId(params, "id")
        val includeSections = optionalBoolean(params, "includeSections", false)

        return when (val result = context.templateRepository().getTemplate(id)) {
            is Result.Success -> {
                val template = result.data
                val sections = if (includeSections) {
                    when (val sectionsResult = context.templateRepository().getTemplateSections(id)) {
                        is Result.Success -> sectionsResult.data
                        is Result.Error -> {
                            logger.error("Failed to retrieve template sections: ${sectionsResult.error}")
                            emptyList()
                        }
                    }
                } else emptyList()

                successResponse(
                    buildTemplateJson(template, sections, includeSections),
                    "Template retrieved successfully"
                )
            }
            is Result.Error -> handleNotFoundError(result, "Template", id)
        }
    }

    // ========== LIST OPERATION ==========

    private suspend fun executeList(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing list operation for templates")

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
        val result = context.templateRepository()
            .getAllTemplates(targetEntityType, isBuiltIn, isEnabled, tags)

        return when (result) {
            is Result.Success -> {
                val templates = result.data

                // Build the response
                val responseData = buildJsonObject {
                    put("templates", buildJsonArray {
                        templates.forEach { template ->
                            add(buildTemplateJson(template, emptyList(), false))
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

            is Result.Error -> errorResponse(
                "Failed to retrieve templates: ${result.error}",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }

    // ========== JSON BUILDING HELPERS ==========

    private fun buildTemplateJson(template: Template, sections: List<TemplateSection>, includeSections: Boolean): JsonObject {
        return buildJsonObject {
            put("id", template.id.toString())
            put("name", template.name)
            put("description", template.description)
            put("targetEntityType", template.targetEntityType.name)
            put("isBuiltIn", template.isBuiltIn)
            put("isProtected", template.isProtected)
            put("isEnabled", template.isEnabled)
            template.createdBy?.let { put("createdBy", it) }
            put("tags", JsonArray(template.tags.map { JsonPrimitive(it) }))
            put("createdAt", template.createdAt.toString())
            put("modifiedAt", template.modifiedAt.toString())

            if (includeSections && sections.isNotEmpty()) {
                put("sections", buildJsonArray {
                    sections.sortedBy { it.ordinal }.forEach { section ->
                        add(buildTemplateSectionJson(section))
                    }
                })
            }
        }
    }

    private fun buildTemplateSectionJson(section: TemplateSection): JsonObject {
        return buildJsonObject {
            put("id", section.id.toString())
            put("title", section.title)
            put("usageDescription", section.usageDescription)
            put("contentSample", section.contentSample)
            put("contentFormat", section.contentFormat.name.lowercase())
            put("ordinal", section.ordinal)
            put("isRequired", section.isRequired)
            put("tags", JsonArray(section.tags.map { JsonPrimitive(it) }))
        }
    }

    // ========== HELPER METHODS ==========

    private fun handleNotFoundError(result: Result.Error, entityType: String, id: UUID): JsonElement {
        return if (result.error is RepositoryError.NotFound) {
            errorResponse(
                "$entityType not found",
                ErrorCodes.RESOURCE_NOT_FOUND,
                "No $entityType exists with ID $id"
            )
        } else {
            errorResponse(
                "Failed to retrieve $entityType",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }
}
