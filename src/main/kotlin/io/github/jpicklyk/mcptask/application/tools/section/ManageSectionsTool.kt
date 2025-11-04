package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Consolidated MCP tool for write operations on sections.
 * Includes 9 operations: add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete.
 *
 * This tool unifies section management, reducing token overhead and providing a consistent interface for AI agents.
 */
class ManageSectionsTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.SECTION_MANAGEMENT

    override val name: String = "manage_sections"

    override val title: String = "Manage Sections"

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

    override val description: String = """Unified write operations for sections.

Operations: add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete |
| id | UUID | Varies | Section ID (required for: update, updateText, updateMetadata, delete) |
| ids | array | Varies | Section IDs (required for: bulkDelete) |
| entityType | enum | Varies | PROJECT, TASK, FEATURE (required for: add, reorder) |
| entityId | UUID | Varies | Parent entity ID (required for: add, reorder) |
| title | string | Varies | Section title (required for: add, bulkCreate) |
| usageDescription | string | Varies | Usage description (required for: add, bulkCreate) |
| content | string | Varies | Section content (required for: add, bulkCreate) |
| contentFormat | enum | No | MARKDOWN, PLAIN_TEXT, JSON, CODE |
| ordinal | integer | Varies | Display order 0-based (required for: add, bulkCreate) |
| tags | string | No | Comma-separated tags |
| oldText | string | Varies | Text to replace (required for: updateText) |
| newText | string | Varies | Replacement text (required for: updateText) |
| sectionOrder | string | Varies | Comma-separated section IDs (required for: reorder) |
| sections | array | Varies | Section objects (required for: bulkCreate, bulkUpdate) |

Usage: Consolidates all section write operations.
Related: get_sections
Docs: task-orchestrator://docs/tools/manage-sections
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(
                            listOf(
                                "add",
                                "update",
                                "updateText",
                                "updateMetadata",
                                "delete",
                                "reorder",
                                "bulkCreate",
                                "bulkUpdate",
                                "bulkDelete"
                            ).map { JsonPrimitive(it) }
                        )
                    )
                ),
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Section ID (required for: update, updateText, updateMetadata, delete)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "ids" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Section IDs (required for: bulkDelete)"),
                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid")))
                    )
                ),
                "entityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of entity (PROJECT, TASK, FEATURE)"),
                        "enum" to JsonArray(EntityType.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "entityId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Parent entity ID"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Section title")
                    )
                ),
                "usageDescription" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Usage description")
                    )
                ),
                "content" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Section content")
                    )
                ),
                "contentFormat" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Content format"),
                        "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "ordinal" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Display order (0-based)"),
                        "minimum" to JsonPrimitive(0)
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated tags")
                    )
                ),
                "oldText" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Text to replace (updateText only)")
                    )
                ),
                "newText" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Replacement text (updateText only)")
                    )
                ),
                "sectionOrder" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated section IDs (reorder only)")
                    )
                ),
                "sections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Section objects (bulkCreate, bulkUpdate)"),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object")
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("operation")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")

        // Validate operation
        val validOperations = listOf("add", "update", "updateText", "updateMetadata", "delete", "reorder", "bulkCreate", "bulkUpdate", "bulkDelete")
        if (operation !in validOperations) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: ${validOperations.joinToString()}")
        }

        // Validate operation-specific parameters
        when (operation) {
            "add" -> validateAddParams(params)
            "update" -> validateUpdateParams(params)
            "updateText" -> validateUpdateTextParams(params)
            "updateMetadata" -> validateUpdateMetadataParams(params)
            "delete" -> validateDeleteParams(params)
            "reorder" -> validateReorderParams(params)
            "bulkCreate" -> validateBulkCreateParams(params)
            "bulkUpdate" -> validateBulkUpdateParams(params)
            "bulkDelete" -> validateBulkDeleteParams(params)
        }
    }

    // ========== VALIDATION METHODS ==========

    private fun validateAddParams(params: JsonElement) {
        requireString(params, "entityType")
        val entityIdStr = requireString(params, "entityId")
        val title = requireString(params, "title")
        val usageDescription = requireString(params, "usageDescription")
        val content = requireString(params, "content")

        try {
            UUID.fromString(entityIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entityId format. Must be a valid UUID.")
        }

        val ordinalValue = params.jsonObject["ordinal"]
        if (ordinalValue != null) {
            val ordinal = when {
                ordinalValue is JsonPrimitive && ordinalValue.isString -> ordinalValue.content.toIntOrNull()
                ordinalValue is JsonPrimitive -> ordinalValue.intOrNull
                else -> null
            }
            if (ordinal == null || ordinal < 0) {
                throw ToolValidationException("Ordinal must be a non-negative integer")
            }
        } else {
            throw ToolValidationException("Ordinal is required for add operation")
        }

        optionalString(params, "contentFormat")?.let { formatStr ->
            try {
                ContentFormat.valueOf(formatStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid contentFormat: $formatStr. Must be one of: ${ContentFormat.entries.joinToString()}")
            }
        }
    }

    private fun validateUpdateParams(params: JsonElement) {
        validateIdParam(params)
        validateOptionalSectionFields(params)
    }

    private fun validateUpdateTextParams(params: JsonElement) {
        validateIdParam(params)
        requireString(params, "oldText")
        requireString(params, "newText")
    }

    private fun validateUpdateMetadataParams(params: JsonElement) {
        validateIdParam(params)
        validateOptionalSectionFields(params)
    }

    private fun validateDeleteParams(params: JsonElement) {
        validateIdParam(params)
    }

    private fun validateReorderParams(params: JsonElement) {
        val entityTypeStr = requireString(params, "entityType")
        val entityIdStr = requireString(params, "entityId")
        val sectionOrderStr = requireString(params, "sectionOrder")

        try {
            EntityType.valueOf(entityTypeStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entityType: $entityTypeStr")
        }

        try {
            UUID.fromString(entityIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entityId format. Must be a valid UUID.")
        }

        if (sectionOrderStr.isBlank()) {
            throw ToolValidationException("sectionOrder cannot be empty")
        }

        val sectionIds = sectionOrderStr.split(",").map { it.trim() }
        sectionIds.forEach { idStr ->
            try {
                UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid section ID in sectionOrder: $idStr")
            }
        }

        if (sectionIds.toSet().size != sectionIds.size) {
            throw ToolValidationException("Duplicate section IDs found in sectionOrder")
        }
    }

    private fun validateBulkCreateParams(params: JsonElement) {
        val sectionsArray = params.jsonObject["sections"]
            ?: throw ToolValidationException("Missing required parameter: sections")

        if (sectionsArray !is JsonArray) {
            throw ToolValidationException("Parameter 'sections' must be an array")
        }

        if (sectionsArray.isEmpty()) {
            throw ToolValidationException("At least one section must be provided")
        }

        sectionsArray.forEachIndexed { index, sectionElement ->
            if (sectionElement !is JsonObject) {
                throw ToolValidationException("Section at index $index must be an object")
            }

            val sectionObj = sectionElement.jsonObject
            val requiredFields = listOf("entityType", "entityId", "title", "usageDescription", "content", "ordinal")
            requiredFields.forEach { field ->
                if (!sectionObj.containsKey(field)) {
                    throw ToolValidationException("Section at index $index missing required field: $field")
                }
            }

            sectionObj["entityId"]?.jsonPrimitive?.content?.let { idStr ->
                try {
                    UUID.fromString(idStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid entityId at index $index")
                }
            }
        }
    }

    private fun validateBulkUpdateParams(params: JsonElement) {
        val sectionsArray = params.jsonObject["sections"]
            ?: throw ToolValidationException("Missing required parameter: sections")

        if (sectionsArray !is JsonArray) {
            throw ToolValidationException("Parameter 'sections' must be an array")
        }

        if (sectionsArray.isEmpty()) {
            throw ToolValidationException("At least one section must be provided")
        }

        sectionsArray.forEachIndexed { index, sectionElement ->
            if (sectionElement !is JsonObject) {
                throw ToolValidationException("Section at index $index must be an object")
            }

            val sectionObj = sectionElement.jsonObject
            if (!sectionObj.containsKey("id")) {
                throw ToolValidationException("Section at index $index missing required field: id")
            }

            sectionObj["id"]?.jsonPrimitive?.content?.let { idStr ->
                try {
                    UUID.fromString(idStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid id at index $index")
                }
            }

            val updateFields = listOf("title", "usageDescription", "content", "contentFormat", "ordinal", "tags")
            if (updateFields.none { sectionObj.containsKey(it) }) {
                throw ToolValidationException("Section at index $index has no fields to update")
            }
        }
    }

    private fun validateBulkDeleteParams(params: JsonElement) {
        val idsArray = params.jsonObject["ids"]
            ?: throw ToolValidationException("Missing required parameter: ids")

        if (idsArray !is JsonArray) {
            throw ToolValidationException("Parameter 'ids' must be an array")
        }

        if (idsArray.isEmpty()) {
            throw ToolValidationException("At least one section ID must be provided")
        }

        idsArray.forEachIndexed { index, idElement ->
            if (idElement !is JsonPrimitive || !idElement.isString) {
                throw ToolValidationException("Section ID at index $index must be a string")
            }

            try {
                UUID.fromString(idElement.content)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid section ID at index $index")
            }
        }
    }

    private fun validateIdParam(params: JsonElement) {
        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid id format. Must be a valid UUID.")
        }
    }

    private fun validateOptionalSectionFields(params: JsonElement) {
        optionalString(params, "contentFormat")?.let { formatStr ->
            try {
                ContentFormat.valueOf(formatStr.uppercase())
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid contentFormat: $formatStr. Must be one of: ${ContentFormat.entries.joinToString()}")
            }
        }

        params.jsonObject["ordinal"]?.let { ordinalValue ->
            if (ordinalValue is JsonPrimitive) {
                val ordinal = when {
                    ordinalValue.isString -> ordinalValue.content.toIntOrNull()
                    else -> ordinalValue.intOrNull
                }
                if (ordinal == null || ordinal < 0) {
                    throw ToolValidationException("Ordinal must be a non-negative integer")
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing manage_sections tool")

        return try {
            val operation = requireString(params, "operation")

            when (operation) {
                "add" -> executeAdd(params, context)
                "update" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("update_section", EntityType.SECTION, id) {
                        executeUpdate(params, context, id)
                    }
                }
                "updateText" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("update_section_text", EntityType.SECTION, id) {
                        executeUpdateText(params, context, id)
                    }
                }
                "updateMetadata" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("update_section_metadata", EntityType.SECTION, id) {
                        executeUpdateMetadata(params, context, id)
                    }
                }
                "delete" -> {
                    val id = extractEntityId(params, "id")
                    executeWithLocking("delete_section", EntityType.SECTION, id) {
                        executeDelete(params, context, id)
                    }
                }
                "reorder" -> executeReorder(params, context)
                "bulkCreate" -> executeBulkCreate(params, context)
                "bulkUpdate" -> executeBulkUpdate(params, context)
                "bulkDelete" -> executeBulkDelete(params, context)
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in manage_sections: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in manage_sections", e)
            errorResponse(
                message = "Failed to execute section operation",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== ADD OPERATION ==========

    private suspend fun executeAdd(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing add operation")

        val entityTypeStr = requireString(params, "entityType")
        val entityIdStr = requireString(params, "entityId")
        val title = requireString(params, "title")
        val usageDescription = requireString(params, "usageDescription")
        val content = requireString(params, "content")
        val contentFormatStr = optionalString(params, "contentFormat") ?: ContentFormat.MARKDOWN.name
        val ordinal = params.jsonObject["ordinal"]?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content.toInt()
                it is JsonPrimitive -> it.int
                else -> 0
            }
        } ?: 0

        val entityType = EntityType.valueOf(entityTypeStr)
        val entityId = UUID.fromString(entityIdStr)
        val contentFormat = ContentFormat.valueOf(contentFormatStr)

        val tags = optionalString(params, "tags")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        // Verify entity exists
        when (entityType) {
            EntityType.TASK -> {
                when (context.taskRepository().getById(entityId)) {
                    is Result.Error -> return errorResponse("Task not found", ErrorCodes.RESOURCE_NOT_FOUND)
                    is Result.Success -> {}
                }
            }
            EntityType.FEATURE -> {
                when (context.featureRepository().getById(entityId)) {
                    is Result.Error -> return errorResponse("Feature not found", ErrorCodes.RESOURCE_NOT_FOUND)
                    is Result.Success -> {}
                }
            }
            EntityType.PROJECT -> {
                when (context.projectRepository().getById(entityId)) {
                    is Result.Error -> return errorResponse("Project not found", ErrorCodes.RESOURCE_NOT_FOUND)
                    is Result.Success -> {}
                }
            }
            EntityType.TEMPLATE -> {
                when (context.templateRepository().getTemplate(entityId)) {
                    is Result.Error -> return errorResponse("Template not found", ErrorCodes.RESOURCE_NOT_FOUND)
                    is Result.Success -> {}
                }
            }
            else -> {}
        }

        val section = Section(
            entityType = entityType,
            entityId = entityId,
            title = title,
            usageDescription = usageDescription,
            content = content,
            contentFormat = contentFormat,
            ordinal = ordinal,
            tags = tags
        )

        return handleRepositoryResult(
            context.sectionRepository().addSection(entityType, entityId, section),
            "Section added successfully"
        ) { addedSection ->
            buildJsonObject {
                put("id", addedSection.id.toString())
                put("entityType", addedSection.entityType.name)
                put("entityId", addedSection.entityId.toString())
                put("title", addedSection.title)
                put("ordinal", addedSection.ordinal)
                put("createdAt", addedSection.createdAt.toString())
            }
        }
    }

    // ========== UPDATE OPERATION ==========

    private suspend fun executeUpdate(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        logger.info("Executing update operation for section: $id")

        val getSectionResult = context.sectionRepository().getSection(id)
        val existingSection = when (getSectionResult) {
            is Result.Success -> getSectionResult.data
            is Result.Error -> return handleRepositoryResult(getSectionResult, "Failed to retrieve section") { JsonNull }
        }

        val title = optionalString(params, "title") ?: existingSection.title
        val usageDescription = optionalString(params, "usageDescription") ?: existingSection.usageDescription
        val content = optionalString(params, "content") ?: existingSection.content
        val contentFormatStr = optionalString(params, "contentFormat")
        val contentFormat = if (contentFormatStr != null) {
            ContentFormat.valueOf(contentFormatStr.uppercase())
        } else {
            existingSection.contentFormat
        }
        val ordinal = params.jsonObject["ordinal"]?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content.toInt()
                it is JsonPrimitive -> it.int
                else -> existingSection.ordinal
            }
        } ?: existingSection.ordinal

        val tags = optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList() else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: existingSection.tags

        val updatedSection = existingSection.copy(
            title = title,
            usageDescription = usageDescription,
            content = content,
            contentFormat = contentFormat,
            ordinal = ordinal,
            tags = tags,
            modifiedAt = Instant.now()
        )

        return handleRepositoryResult(
            context.sectionRepository().updateSection(updatedSection),
            "Section updated successfully"
        ) { updated ->
            buildJsonObject {
                put("id", updated.id.toString())
                put("modifiedAt", updated.modifiedAt.toString())
            }
        }
    }

    // ========== UPDATE TEXT OPERATION ==========

    private suspend fun executeUpdateText(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        logger.info("Executing updateText operation for section: $id")

        val oldText = requireString(params, "oldText")
        val newText = requireString(params, "newText")

        val getSectionResult = context.sectionRepository().getSection(id)
        val existingSection = when (getSectionResult) {
            is Result.Success -> getSectionResult.data
            is Result.Error -> return handleRepositoryResult(getSectionResult, "Failed to retrieve section") { JsonNull }
        }

        if (!existingSection.content.contains(oldText)) {
            return errorResponse(
                message = "Text not found",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "The specified text to replace was not found in the section content"
            )
        }

        val updatedContent = existingSection.content.replace(oldText, newText)
        val updatedSection = existingSection.copy(
            content = updatedContent,
            modifiedAt = Instant.now()
        )

        return handleRepositoryResult(
            context.sectionRepository().updateSection(updatedSection),
            "Section text updated successfully"
        ) { updated ->
            buildJsonObject {
                put("id", updated.id.toString())
                put("replacedTextLength", oldText.length)
                put("newTextLength", newText.length)
                put("modifiedAt", updated.modifiedAt.toString())
            }
        }
    }

    // ========== UPDATE METADATA OPERATION ==========

    private suspend fun executeUpdateMetadata(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        logger.info("Executing updateMetadata operation for section: $id")

        val getSectionResult = context.sectionRepository().getSection(id)
        val existingSection = when (getSectionResult) {
            is Result.Success -> getSectionResult.data
            is Result.Error -> return handleRepositoryResult(getSectionResult, "Failed to retrieve section") { JsonNull }
        }

        // Check if title parameter exists and validate it before using elvis operator
        val paramsObj = params.jsonObject
        if (paramsObj.containsKey("title")) {
            val titleValue = paramsObj["title"]?.jsonPrimitive?.content
            if (titleValue != null && titleValue.isBlank()) {
                return errorResponse("Section title cannot be empty", ErrorCodes.VALIDATION_ERROR)
            }
        }
        val title = optionalString(params, "title") ?: existingSection.title

        // Check if usageDescription parameter exists and validate it before using elvis operator
        if (paramsObj.containsKey("usageDescription")) {
            val usageDescValue = paramsObj["usageDescription"]?.jsonPrimitive?.content
            if (usageDescValue != null && usageDescValue.isBlank()) {
                return errorResponse("Section usage description cannot be empty", ErrorCodes.VALIDATION_ERROR)
            }
        }
        val usageDescription = optionalString(params, "usageDescription") ?: existingSection.usageDescription

        val contentFormatStr = optionalString(params, "contentFormat")
        val contentFormat = if (contentFormatStr != null) {
            ContentFormat.valueOf(contentFormatStr.uppercase())
        } else {
            existingSection.contentFormat
        }

        val ordinal = params.jsonObject["ordinal"]?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content.toInt()
                it is JsonPrimitive -> it.int
                else -> existingSection.ordinal
            }
        } ?: existingSection.ordinal

        val tags = optionalString(params, "tags")?.let {
            if (it.isEmpty()) emptyList() else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
        } ?: existingSection.tags

        val updatedSection = existingSection.copy(
            title = title,
            usageDescription = usageDescription,
            contentFormat = contentFormat,
            ordinal = ordinal,
            tags = tags,
            modifiedAt = Instant.now()
        )

        return handleRepositoryResult(
            context.sectionRepository().updateSection(updatedSection),
            "Section metadata updated successfully"
        ) { updated ->
            buildJsonObject {
                put("id", updated.id.toString())
                put("modifiedAt", updated.modifiedAt.toString())
            }
        }
    }

    // ========== DELETE OPERATION ==========

    private suspend fun executeDelete(params: JsonElement, context: ToolExecutionContext, id: UUID): JsonElement {
        logger.info("Executing delete operation for section: $id")

        // Get section before deleting to return info
        val getSectionResult = context.sectionRepository().getSection(id)
        val section = when (getSectionResult) {
            is Result.Success -> getSectionResult.data
            is Result.Error -> return handleRepositoryResult(getSectionResult, "Failed to retrieve section") { JsonNull }
        }

        return handleRepositoryResult(
            context.sectionRepository().deleteSection(id),
            "Section deleted successfully"
        ) { _ ->
            buildJsonObject {
                put("id", id.toString())
                put("deleted", true)
                put("entityType", section.entityType.name)
                put("entityId", section.entityId.toString())
                put("title", section.title)
            }
        }
    }

    // ========== REORDER OPERATION ==========

    private suspend fun executeReorder(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing reorder operation")

        val entityTypeStr = requireString(params, "entityType")
        val entityIdStr = requireString(params, "entityId")
        val sectionOrderStr = requireString(params, "sectionOrder")

        val entityType = EntityType.valueOf(entityTypeStr)
        val entityId = UUID.fromString(entityIdStr)
        val sectionIds = sectionOrderStr.split(",").map { it.trim() }.map { UUID.fromString(it) }

        // Get all sections for the entity to verify
        val getSectionsResult = context.sectionRepository().getSectionsForEntity(entityType, entityId)
        val existingSections = when (getSectionsResult) {
            is Result.Success -> getSectionsResult.data
            is Result.Error -> return handleRepositoryResult(getSectionsResult, "Failed to retrieve sections") { JsonNull }
        }

        val existingIds = existingSections.map { it.id }

        // Verify all sections in the order belong to the entity
        val invalidSectionIds = sectionIds.filter { !existingIds.contains(it) }
        if (invalidSectionIds.isNotEmpty()) {
            return errorResponse(
                message = "Invalid section IDs",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "The following section IDs do not belong to the entity: ${invalidSectionIds.joinToString()}"
            )
        }

        // Verify all sections of the entity are included
        val missingSectionIds = existingIds.filter { !sectionIds.contains(it) }
        if (missingSectionIds.isNotEmpty()) {
            return errorResponse(
                message = "Missing section IDs",
                code = ErrorCodes.VALIDATION_ERROR,
                details = "The following section IDs are missing from the order: ${missingSectionIds.joinToString()}"
            )
        }

        return handleRepositoryResult(
            context.sectionRepository().reorderSections(entityType, entityId, sectionIds),
            "Sections reordered successfully"
        ) { _ ->
            buildJsonObject {
                put("entityType", entityType.name)
                put("entityId", entityId.toString())
                put("sectionCount", sectionIds.size)
            }
        }
    }

    // ========== BULK CREATE OPERATION ==========

    private suspend fun executeBulkCreate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing bulkCreate operation")

        val sectionsArray = params.jsonObject["sections"] as JsonArray
        val successfulSections = mutableListOf<JsonObject>()
        val failedSections = mutableListOf<JsonObject>()

        sectionsArray.forEachIndexed { index, sectionElement ->
            val sectionObj = sectionElement.jsonObject

            try {
                val entityTypeStr = sectionObj["entityType"]!!.jsonPrimitive.content
                val entityType = EntityType.valueOf(entityTypeStr)
                val entityIdStr = sectionObj["entityId"]!!.jsonPrimitive.content
                val entityId = UUID.fromString(entityIdStr)
                val title = sectionObj["title"]!!.jsonPrimitive.content
                val usageDescription = sectionObj["usageDescription"]!!.jsonPrimitive.content
                val content = sectionObj["content"]!!.jsonPrimitive.content
                val contentFormatStr = sectionObj["contentFormat"]?.jsonPrimitive?.content ?: ContentFormat.MARKDOWN.name
                val contentFormat = ContentFormat.valueOf(contentFormatStr)
                val ordinalElement = sectionObj["ordinal"]!!
                val ordinal = when {
                    ordinalElement.jsonPrimitive.isString -> ordinalElement.jsonPrimitive.content.toInt()
                    else -> ordinalElement.jsonPrimitive.int
                }
                val tagsStr = sectionObj["tags"]?.jsonPrimitive?.content ?: ""
                val tags = if (tagsStr.isNotEmpty()) tagsStr.split(",").map { it.trim() } else emptyList()

                // Verify entity exists
                val entityExistsResult = when (entityType) {
                    EntityType.TASK -> context.taskRepository().getById(entityId)
                    EntityType.FEATURE -> context.featureRepository().getById(entityId)
                    EntityType.PROJECT -> context.projectRepository().getById(entityId)
                    else -> Result.Error(RepositoryError.ValidationError("Unsupported entity type: $entityType"))
                }

                if (entityExistsResult is Result.Error) {
                    failedSections.add(buildJsonObject {
                        put("index", index)
                        put("error", buildJsonObject {
                            put("code", ErrorCodes.RESOURCE_NOT_FOUND)
                            put("details", "Entity not found: ${entityType.name} with ID $entityId")
                        })
                    })
                    return@forEachIndexed
                }

                val section = Section(
                    entityType = entityType,
                    entityId = entityId,
                    title = title,
                    usageDescription = usageDescription,
                    content = content,
                    contentFormat = contentFormat,
                    ordinal = ordinal,
                    tags = tags
                )

                when (val addResult = context.sectionRepository().addSection(entityType, entityId, section)) {
                    is Result.Success -> {
                        successfulSections.add(serializeSection(addResult.data))
                    }
                    is Result.Error -> {
                        failedSections.add(buildJsonObject {
                            put("index", index)
                            put("error", buildJsonObject {
                                put("code", when (addResult.error) {
                                    is RepositoryError.ValidationError -> ErrorCodes.VALIDATION_ERROR
                                    is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                    else -> ErrorCodes.DATABASE_ERROR
                                })
                                put("details", addResult.error.toString())
                            })
                        })
                    }
                }
            } catch (e: Exception) {
                logger.error("Error creating section at index $index", e)
                failedSections.add(buildJsonObject {
                    put("index", index)
                    put("error", buildJsonObject {
                        put("code", ErrorCodes.INTERNAL_ERROR)
                        put("details", e.message ?: "Unknown error")
                    })
                })
            }
        }

        val successCount = successfulSections.size
        val failedCount = failedSections.size

        return if (failedCount == 0) {
            successResponse(
                buildJsonObject {
                    put("items", JsonArray(successfulSections))
                    put("count", successCount)
                    put("failed", 0)
                },
                "$successCount sections created successfully"
            )
        } else if (successCount == 0) {
            errorResponse(
                "Failed to create any sections",
                ErrorCodes.OPERATION_FAILED,
                "All ${sectionsArray.size} sections failed to create",
                buildJsonObject { put("failures", JsonArray(failedSections)) }
            )
        } else {
            successResponse(
                buildJsonObject {
                    put("items", JsonArray(successfulSections))
                    put("count", successCount)
                    put("failed", failedCount)
                    put("failures", JsonArray(failedSections))
                },
                "$successCount sections created successfully, $failedCount failed"
            )
        }
    }

    // ========== BULK UPDATE OPERATION ==========

    private suspend fun executeBulkUpdate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing bulkUpdate operation")

        val sectionsArray = params.jsonObject["sections"] as JsonArray
        val successfulSections = mutableListOf<JsonObject>()
        val failedSections = mutableListOf<JsonObject>()

        sectionsArray.forEachIndexed { index, sectionElement ->
            val sectionParams = sectionElement.jsonObject
            val idStr = sectionParams["id"]!!.jsonPrimitive.content
            val sectionId = UUID.fromString(idStr)

            try {
                val getSectionResult = context.sectionRepository().getSection(sectionId)
                if (getSectionResult is Result.Error) {
                    failedSections.add(buildJsonObject {
                        put("index", index)
                        put("id", idStr)
                        put("error", buildJsonObject {
                            put("code", when (getSectionResult.error) {
                                is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                else -> ErrorCodes.DATABASE_ERROR
                            })
                            put("details", getSectionResult.error.toString())
                        })
                    })
                    return@forEachIndexed
                }

                val existingSection = (getSectionResult as Result.Success).data

                val title = optionalString(sectionParams, "title", existingSection.title)
                val usageDescription = optionalString(sectionParams, "usageDescription", existingSection.usageDescription)
                val content = optionalString(sectionParams, "content", existingSection.content)
                val contentFormatStr = optionalString(sectionParams, "contentFormat", "")
                val contentFormat = if (contentFormatStr.isNotEmpty()) {
                    ContentFormat.valueOf(contentFormatStr.uppercase())
                } else {
                    existingSection.contentFormat
                }
                val ordinal = if (sectionParams.containsKey("ordinal")) {
                    val ordinalValue = sectionParams["ordinal"]
                    if (ordinalValue is JsonPrimitive) {
                        when {
                            ordinalValue.isString -> ordinalValue.content.toInt()
                            else -> ordinalValue.int
                        }
                    } else {
                        existingSection.ordinal
                    }
                } else {
                    existingSection.ordinal
                }
                val tagsStr = optionalString(sectionParams, "tags", "")
                val tags = if (tagsStr.isNotEmpty()) {
                    tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    existingSection.tags
                }

                val updatedSection = existingSection.copy(
                    title = title,
                    usageDescription = usageDescription,
                    content = content,
                    contentFormat = contentFormat,
                    ordinal = ordinal,
                    tags = tags,
                    modifiedAt = Instant.now()
                )

                when (val updateResult = context.sectionRepository().updateSection(updatedSection)) {
                    is Result.Success -> {
                        successfulSections.add(serializeSection(updateResult.data))
                    }
                    is Result.Error -> {
                        failedSections.add(buildJsonObject {
                            put("index", index)
                            put("id", idStr)
                            put("error", buildJsonObject {
                                put("code", when (updateResult.error) {
                                    is RepositoryError.ValidationError -> ErrorCodes.VALIDATION_ERROR
                                    is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                    else -> ErrorCodes.DATABASE_ERROR
                                })
                                put("details", updateResult.error.toString())
                            })
                        })
                    }
                }
            } catch (e: Exception) {
                logger.error("Error updating section at index $index", e)
                failedSections.add(buildJsonObject {
                    put("index", index)
                    put("id", idStr)
                    put("error", buildJsonObject {
                        put("code", ErrorCodes.INTERNAL_ERROR)
                        put("details", e.message ?: "Unknown error")
                    })
                })
            }
        }

        val successCount = successfulSections.size
        val failedCount = failedSections.size

        return if (failedCount == 0) {
            successResponse(
                buildJsonObject {
                    put("items", JsonArray(successfulSections))
                    put("count", successCount)
                    put("failed", 0)
                },
                "$successCount sections updated successfully"
            )
        } else if (successCount == 0) {
            errorResponse(
                "Failed to update any sections",
                ErrorCodes.OPERATION_FAILED,
                "All ${sectionsArray.size} sections failed to update",
                buildJsonObject { put("failures", JsonArray(failedSections)) }
            )
        } else {
            successResponse(
                buildJsonObject {
                    put("items", JsonArray(successfulSections))
                    put("count", successCount)
                    put("failed", failedCount)
                    put("failures", JsonArray(failedSections))
                },
                "$successCount sections updated successfully, $failedCount failed"
            )
        }
    }

    // ========== BULK DELETE OPERATION ==========

    private suspend fun executeBulkDelete(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing bulkDelete operation")

        val idsArray = params.jsonObject["ids"] as JsonArray
        val successfulDeletes = mutableListOf<String>()
        val failedDeletes = mutableListOf<JsonObject>()

        idsArray.forEachIndexed { index, idElement ->
            val idStr = idElement.jsonPrimitive.content
            val sectionId = UUID.fromString(idStr)

            when (val deleteResult = context.sectionRepository().deleteSection(sectionId)) {
                is Result.Success -> {
                    if (deleteResult.data) {
                        successfulDeletes.add(idStr)
                    } else {
                        failedDeletes.add(buildJsonObject {
                            put("id", idStr)
                            put("index", index)
                            put("error", buildJsonObject {
                                put("code", ErrorCodes.DATABASE_ERROR)
                                put("details", "Section deletion returned false")
                            })
                        })
                    }
                }
                is Result.Error -> {
                    failedDeletes.add(buildJsonObject {
                        put("id", idStr)
                        put("index", index)
                        put("error", buildJsonObject {
                            put("code", when (deleteResult.error) {
                                is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                else -> ErrorCodes.DATABASE_ERROR
                            })
                            put("details", deleteResult.error.toString())
                        })
                    })
                }
            }
        }

        val successCount = successfulDeletes.size
        val failedCount = failedDeletes.size

        return if (failedCount == 0) {
            successResponse(
                buildJsonObject {
                    put("ids", JsonArray(successfulDeletes.map { JsonPrimitive(it) }))
                    put("count", successCount)
                    put("failed", 0)
                },
                "$successCount sections deleted successfully"
            )
        } else if (successCount == 0) {
            errorResponse(
                "Failed to delete any sections",
                ErrorCodes.OPERATION_FAILED,
                "All ${idsArray.size} sections failed to delete",
                buildJsonObject { put("failures", JsonArray(failedDeletes)) }
            )
        } else {
            successResponse(
                buildJsonObject {
                    put("ids", JsonArray(successfulDeletes.map { JsonPrimitive(it) }))
                    put("count", successCount)
                    put("failed", failedCount)
                    put("failures", JsonArray(failedDeletes))
                },
                "$successCount sections deleted successfully, $failedCount failed"
            )
        }
    }

    // ========== HELPER METHODS ==========

    private fun serializeSection(section: Section): JsonObject {
        return buildJsonObject {
            put("id", section.id.toString())
            put("entityType", section.entityType.name.lowercase())
            put("entityId", section.entityId.toString())
            put("title", section.title)
            put("usageDescription", section.usageDescription)
            put("contentFormat", section.contentFormat.name.lowercase())
            put("ordinal", section.ordinal)
            put("createdAt", section.createdAt.toString())
            put("modifiedAt", section.modifiedAt.toString())
        }
    }
}
