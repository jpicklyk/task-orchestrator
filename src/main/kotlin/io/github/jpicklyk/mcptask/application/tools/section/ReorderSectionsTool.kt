package io.github.jpicklyk.mcptask.application.tools.section

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
 * Tool for changing the display order of sections.
 * This allows reordering sections without having to update each section individually.
 */
class ReorderSectionsTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "reorder_sections"

    override val title: String = "Reorder Sections"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Reordering results"),
                        "properties" to JsonObject(
                            mapOf(
                                "entityType" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "entityId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "sectionsReordered" to JsonObject(mapOf("type" to JsonPrimitive("integer")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """Reorders sections within a template or other entity.
        This tool changes the display order of sections by updating their ordinal values.
        
        This is useful for reorganizing content without having to send the content itself,
        which is more efficient for context usage.
        
        Parameters:
        - entityType (required): Type of entity (TEMPLATE, TASK, FEATURE)
        - entityId (required): ID of the entity
        - sectionOrder (required): New order of section IDs
        
        Validation Rules:
        - Entity must exist
        - All sections must exist and belong to the entity
        - All sections of the entity must be included
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "entityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of entity (TEMPLATE, TASK, FEATURE)"),
                        "enum" to JsonArray(listOf("TEMPLATE", "TASK", "FEATURE").map { JsonPrimitive(it) })
                    )
                ),
                "entityId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the entity"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "sectionOrder" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of section IDs in the desired order")
                    )
                )
            )
        ),
        required = listOf("entityType", "entityId", "sectionOrder")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val entityTypeStr = requireString(params, "entityType")
        val entityIdStr = requireString(params, "entityId")
        val sectionOrderStr = requireString(params, "sectionOrder")

        // Validate entity type
        try {
            EntityType.valueOf(entityTypeStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entity type: $entityTypeStr. Must be one of: TEMPLATE, TASK, FEATURE")
        }

        // Validate entity ID format
        try {
            UUID.fromString(entityIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entity ID format. Must be a valid UUID.")
        }

        // Validate section order is not empty
        if (sectionOrderStr.isBlank()) {
            throw ToolValidationException("Section order cannot be empty")
        }

        // Validate each section ID format
        val sectionIds = sectionOrderStr.split(",").map { it.trim() }
        for (sectionIdStr in sectionIds) {
            try {
                UUID.fromString(sectionIdStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid section ID format: $sectionIdStr. Must be a valid UUID.")
            }
        }

        // Validate there are no duplicate section IDs
        val uniqueSectionIds = sectionIds.toSet()
        if (uniqueSectionIds.size != sectionIds.size) {
            throw ToolValidationException("Duplicate section IDs found in section order")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing reorder_sections tool")

        try {
            // Extract parameters
            val entityTypeStr = requireString(params, "entityType")
            val entityIdStr = requireString(params, "entityId")
            val sectionOrderStr = requireString(params, "sectionOrder")

            val entityType = EntityType.valueOf(entityTypeStr)
            val entityId = UUID.fromString(entityIdStr)
            val sectionIds = sectionOrderStr.split(",").map { it.trim() }.map { UUID.fromString(it) }

            // First, get all sections for the entity to verify all sections are included
            val getSectionsResult = context.sectionRepository().getSectionsForEntity(entityType, entityId)
            if (getSectionsResult is Result.Error) {
                when (getSectionsResult.error) {
                    is RepositoryError.NotFound -> {
                        return errorResponse(
                            message = "Entity not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No $entityType exists with ID $entityId"
                        )
                    }

                    else -> {
                        return errorResponse(
                            message = "Failed to retrieve sections",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = getSectionsResult.error.toString()
                        )
                    }
                }
            }

            val existingSections = (getSectionsResult as Result.Success).data
            val existingIds = existingSections.map { it.id }

            // Verify all sections in the order belong to the entity
            val invalidSectionIds = sectionIds.filter { !existingIds.contains(it) }
            if (invalidSectionIds.isNotEmpty()) {
                return errorResponse(
                    message = "Invalid section IDs",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "The following section IDs do not belong to the entity: ${invalidSectionIds.joinToString(", ")}"
                )
            }

            // Verify all sections of the entity are included in the order
            val missingSectionIds = existingIds.filter { !sectionIds.contains(it) }
            if (missingSectionIds.isNotEmpty()) {
                return errorResponse(
                    message = "Missing section IDs",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "The following section IDs are missing from the order: ${missingSectionIds.joinToString(", ")}"
                )
            }

            // Reorder sections
            val reorderResult = context.sectionRepository().reorderSections(entityType, entityId, sectionIds)

            return when (reorderResult) {
                is Result.Success -> {
                    val data = buildJsonObject {
                        put("entityType", entityType.name)
                        put("entityId", entityId.toString())
                        put("sectionCount", sectionIds.size)
                        put("sectionOrder", JsonArray(sectionIds.map { JsonPrimitive(it.toString()) }))
                    }
                    successResponse(data, "Sections reordered successfully")
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to reorder sections",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = reorderResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error reordering sections", e)
            return errorResponse(
                message = "Failed to reorder sections",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}