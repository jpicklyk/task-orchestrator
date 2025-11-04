package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Consolidated MCP tool for write operations on templates.
 * Includes 6 operations: create, update, delete, enable, disable, and addSection.
 *
 * This tool unifies template management into a single interface, reducing token overhead
 * and providing a consistent interface for AI agents.
 */
class ManageTemplateTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "manage_template"

    override val title: String = "Manage Template"

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

    override fun shouldUseLocking(): Boolean = true

    override val description: String = """Unified write operations for templates.

Operations: create, update, delete, enable, disable, addSection

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | create, update, delete, enable, disable, addSection |
| id | UUID | Varies | Template ID (required for: update, delete, enable, disable, addSection) |
| name | string | Varies | Template name (required for: create) |
| description | string | Varies | Template description (required for: create) |
| targetEntityType | enum | Varies | TASK or FEATURE (required for: create) |
| isBuiltIn | boolean | No | Built-in template (default: false) |
| isProtected | boolean | No | Protected from modification (default: false) |
| isEnabled | boolean | No | Enabled for use (default: true) |
| createdBy | string | No | Creator identifier |
| tags | string | No | Comma-separated tags |
| force | boolean | No | Force delete (default: false) |
| title | string | Varies | Section title (required for: addSection) |
| usageDescription | string | Varies | Section usage (required for: addSection) |
| contentSample | string | Varies | Section content (required for: addSection) |
| contentFormat | enum | No | MARKDOWN, PLAIN_TEXT, JSON, CODE (default: MARKDOWN) |
| ordinal | integer | Varies | Section order (required for: addSection) |
| isRequired | boolean | No | Section required (default: false) |

Usage: Consolidates create/update/delete/enable/disable/addSection for templates.
Related: list_templates, get_template, apply_template
Docs: task-orchestrator://docs/tools/manage-template
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(listOf("create", "update", "delete", "enable", "disable", "addSection").map { JsonPrimitive(it) })
                    )
                ),
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Template ID (required for: update, delete, enable, disable, addSection)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Template name")
                    )
                ),
                "description" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Template description")
                    )
                ),
                "targetEntityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Target entity type (TASK, FEATURE)"),
                        "enum" to JsonArray(listOf("TASK", "FEATURE").map { JsonPrimitive(it) })
                    )
                ),
                "isBuiltIn" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether template is built-in (default: false)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "isProtected" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether template is protected (default: false)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "isEnabled" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether template is enabled (default: true)"),
                        "default" to JsonPrimitive(true)
                    )
                ),
                "createdBy" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Creator identifier")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated tags")
                    )
                ),
                "force" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Force delete with protection override"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Section title (addSection)")
                    )
                ),
                "usageDescription" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Section usage description (addSection)")
                    )
                ),
                "contentSample" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Section content sample (addSection)")
                    )
                ),
                "contentFormat" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Content format (default: MARKDOWN)"),
                        "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "ordinal" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Section order position (addSection)"),
                        "minimum" to JsonPrimitive(0)
                    )
                ),
                "isRequired" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether section is required (addSection)"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("operation")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")

        // Validate operation
        if (operation !in listOf("create", "update", "delete", "enable", "disable", "addSection")) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: create, update, delete, enable, disable, addSection")
        }

        // Validate ID for operations that require it
        if (operation in listOf("update", "delete", "enable", "disable", "addSection")) {
            val idStr = requireString(params, "id")
            try {
                UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid template ID format. Must be a valid UUID.")
            }
        }

        // Validate operation-specific requirements
        when (operation) {
            "create" -> {
                requireString(params, "name")
                requireString(params, "description")
                val targetEntityTypeStr = requireString(params, "targetEntityType")
                try {
                    EntityType.valueOf(targetEntityTypeStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid target entity type: $targetEntityTypeStr. Must be 'TASK' or 'FEATURE'")
                }
            }
            "update" -> {
                // At least one update field should be present
                val hasUpdateField = params.jsonObject.keys.any {
                    it in listOf("name", "description", "targetEntityType", "isEnabled", "tags")
                }
                if (!hasUpdateField) {
                    throw ToolValidationException("Update operation requires at least one field to update (name, description, targetEntityType, isEnabled, or tags)")
                }
                // Validate targetEntityType if present
                optionalString(params, "targetEntityType")?.let { targetEntityTypeStr ->
                    try {
                        EntityType.valueOf(targetEntityTypeStr)
                    } catch (_: IllegalArgumentException) {
                        throw ToolValidationException("Invalid target entity type: $targetEntityTypeStr. Must be 'TASK' or 'FEATURE'")
                    }
                }
            }
            "addSection" -> {
                requireString(params, "title")
                requireString(params, "usageDescription")
                requireString(params, "contentSample")
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
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing manage_template tool")

        return try {
            val operation = requireString(params, "operation")

            when (operation) {
                "create" -> executeCreate(params, context)
                "update" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("update_template", EntityType.TEMPLATE, id) {
                        executeUpdate(params, context, id)
                    }
                }
                "delete" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("delete_template", EntityType.TEMPLATE, id) {
                        executeDelete(params, context, id)
                    }
                }
                "enable" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("enable_template", EntityType.TEMPLATE, id) {
                        executeEnable(context, id)
                    }
                }
                "disable" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("disable_template", EntityType.TEMPLATE, id) {
                        executeDisable(context, id)
                    }
                }
                "addSection" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("add_template_section", EntityType.TEMPLATE, id) {
                        executeAddSection(params, context, id)
                    }
                }
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in manage_template: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in manage_template", e)
            errorResponse(
                message = "Failed to execute template operation",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== CREATE OPERATION ==========

    private suspend fun executeCreate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing create operation for template")

        val name = requireString(params, "name")
        val description = requireString(params, "description")
        val targetEntityTypeStr = requireString(params, "targetEntityType")
        val targetEntityType = EntityType.valueOf(targetEntityTypeStr)

        val isBuiltIn = optionalBoolean(params, "isBuiltIn", false)
        val isProtected = optionalBoolean(params, "isProtected", false)
        val isEnabled = optionalBoolean(params, "isEnabled", true)
        val createdBy = optionalString(params, "createdBy")
        val tags = parseTags(params)

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

        // Validate template
        try {
            template.validate()
        } catch (e: IllegalArgumentException) {
            return errorResponse(
                message = e.message ?: "Template validation failed",
                code = ErrorCodes.VALIDATION_ERROR
            )
        }

        return handleRepositoryResult(
            context.templateRepository().createTemplate(template),
            "Template created successfully"
        ) { createdTemplate ->
            serializeTemplate(createdTemplate)
        }
    }

    // ========== UPDATE OPERATION ==========

    private suspend fun executeUpdate(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        logger.info("Executing update operation for template: $id")

        val existingResult = context.templateRepository().getTemplate(id)
        val existing = when (existingResult) {
            is Result.Success -> existingResult.data
            is Result.Error -> return handleRepositoryResult(existingResult, "Failed to retrieve template") { JsonNull }
        }

        // Check if template is protected
        if (existing.isProtected) {
            return errorResponse(
                message = "Cannot update protected template",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Template with ID $id is protected and cannot be updated"
            )
        }

        val name = optionalString(params, "name") ?: existing.name
        if (name.isBlank()) {
            return errorResponse(
                message = "Template name cannot be empty",
                code = ErrorCodes.VALIDATION_ERROR
            )
        }

        val description = optionalString(params, "description") ?: existing.description
        if (description.isBlank()) {
            return errorResponse(
                message = "Template description cannot be empty",
                code = ErrorCodes.VALIDATION_ERROR
            )
        }

        val targetEntityTypeStr = optionalString(params, "targetEntityType") ?: existing.targetEntityType.name
        val targetEntityType = try {
            EntityType.valueOf(targetEntityTypeStr)
        } catch (_: IllegalArgumentException) {
            return errorResponse(
                message = "Invalid target entity type",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "Target entity type must be one of: TASK, FEATURE"
            )
        }

        val isEnabled = optionalBoolean(params, "isEnabled", existing.isEnabled)
        val tagsStr = optionalString(params, "tags")
        val tags = if (tagsStr != null) {
            if (tagsStr.isEmpty()) emptyList()
            else tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            existing.tags
        }

        val updated = existing.copy(
            name = name,
            description = description,
            targetEntityType = targetEntityType,
            isEnabled = isEnabled,
            tags = tags
        ).withUpdatedModificationTime()

        return handleRepositoryResult(
            context.templateRepository().updateTemplate(updated),
            "Template metadata updated successfully"
        ) { updatedTemplate ->
            buildJsonObject {
                put("template", serializeTemplate(updatedTemplate))
            }
        }
    }

    // ========== DELETE OPERATION ==========

    private suspend fun executeDelete(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        logger.info("Executing delete operation for template: $id")

        val force = optionalBoolean(params, "force", false)

        // Verify template exists and get its details
        val templateResult = context.templateRepository().getTemplate(id)
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
            }
            is Result.Error -> {
                return when (templateResult.error) {
                    is RepositoryError.NotFound -> errorResponse(
                        message = "Template not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No template exists with ID $id"
                    )
                    else -> errorResponse(
                        message = "Failed to retrieve template",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = templateResult.error.message
                    )
                }
            }
        }

        val template = (templateResult as Result.Success).data

        return handleRepositoryResult(
            context.templateRepository().deleteTemplate(id, force),
            "Template deleted successfully"
        ) { _ ->
            buildJsonObject {
                put("id", id.toString())
                put("name", template.name)
                put("deleted", true)
            }
        }
    }

    // ========== ENABLE OPERATION ==========

    private suspend fun executeEnable(context: ToolExecutionContext, id: UUID): JsonElement {
        logger.info("Executing enable operation for template: $id")

        return handleRepositoryResult(
            context.templateRepository().enableTemplate(id),
            "Template enabled successfully"
        ) { enabledTemplate ->
            serializeTemplate(enabledTemplate)
        }
    }

    // ========== DISABLE OPERATION ==========

    private suspend fun executeDisable(context: ToolExecutionContext, id: UUID): JsonElement {
        logger.info("Executing disable operation for template: $id")

        return handleRepositoryResult(
            context.templateRepository().disableTemplate(id),
            "Template disabled successfully"
        ) { disabledTemplate ->
            serializeTemplate(disabledTemplate)
        }
    }

    // ========== ADD SECTION OPERATION ==========

    private suspend fun executeAddSection(params: JsonElement, context: ToolExecutionContext, templateId: UUID): JsonElement {
        logger.info("Executing addSection operation for template: $templateId")

        // First check if template exists
        val templateResult = context.templateRepository().getTemplate(templateId)
        if (templateResult is Result.Error) {
            return when (templateResult.error) {
                is RepositoryError.NotFound -> errorResponse(
                    message = "Template not found",
                    code = ErrorCodes.RESOURCE_NOT_FOUND,
                    details = "No template exists with ID $templateId"
                )
                else -> errorResponse(
                    message = "Failed to retrieve template",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = templateResult.error.toString()
                )
            }
        }

        val title = requireString(params, "title")
        val usageDescription = requireString(params, "usageDescription")
        val contentSample = requireString(params, "contentSample")
        val ordinal = requireInt(params, "ordinal")

        val contentFormatStr = optionalString(params, "contentFormat") ?: ContentFormat.MARKDOWN.name
        val contentFormat = ContentFormat.valueOf(contentFormatStr)

        val isRequired = optionalBoolean(params, "isRequired", false)

        val tagsStr = optionalString(params, "tags")
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

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

        return handleRepositoryResult(
            context.templateRepository().addTemplateSection(templateId, templateSection),
            "Template section added successfully"
        ) { section ->
            buildJsonObject {
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
        }
    }

    // ========== HELPER METHODS ==========

    private fun parseTags(params: JsonElement): List<String> {
        return optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList()
            else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: emptyList()
    }

    private fun serializeTemplate(template: Template): JsonObject {
        return buildJsonObject {
            put("id", template.id.toString())
            put("name", template.name)
            put("description", template.description)
            put("targetEntityType", template.targetEntityType.name)
            put("isBuiltIn", template.isBuiltIn)
            put("isProtected", template.isProtected)
            put("isEnabled", template.isEnabled)
            put("createdBy", template.createdBy?.let { JsonPrimitive(it) } ?: JsonNull)
            put("tags", buildJsonArray {
                template.tags.forEach { add(it) }
            })
            put("createdAt", template.createdAt.toString())
            put("modifiedAt", template.modifiedAt.toString())
        }
    }
}
