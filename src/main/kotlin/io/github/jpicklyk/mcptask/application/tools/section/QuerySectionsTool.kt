package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * Unified read-only MCP tool for querying sections across all entity types.
 *
 * This tool provides comprehensive filtering capabilities for sections including:
 * - Filter by entityType and entityId
 * - Filter by specific section IDs
 * - Filter by tags (returns sections with ANY tag match)
 * - Optional content inclusion (significant token savings when false)
 *
 * Part of v2.0's read/write permission separation strategy.
 * Consolidates read-only section operations into a single, efficient tool.
 */
class QuerySectionsTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.SECTION_MANAGEMENT

    override val name: String = "query_sections"

    override val title: String = "Query Sections"

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

    override val description: String = """Unified read-only section queries with comprehensive filtering.

Parameters:
| Field | Type | Required | Description |
| entityType | enum | Yes | PROJECT, FEATURE, or TASK |
| entityId | UUID | Yes | Entity identifier |
| includeContent | boolean | No | Include content (default: true, false saves 85-99% tokens) |
| sectionIds | array | No | Filter to specific section IDs |
| tags | string | No | Comma-separated tags (returns sections with ANY tag) |

Returns sections ordered by ordinal. Each section includes: id, title, usageDescription,
contentFormat, ordinal, tags, timestamps. Content included only if includeContent=true.

Usage patterns:
- Browse structure: includeContent=false (metadata only, huge token savings)
- Selective loading: Get metadata first, then fetch specific sections with sectionIds
- Tag filtering: Agent-specific content queries (e.g., tags="requirements,api")
- Full content: includeContent=true for complete section data

Related: manage_sections (write operations)
Docs: task-orchestrator://docs/tools/query-sections
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "entityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of entity: PROJECT, TASK, or FEATURE"),
                        "enum" to JsonArray(listOf("PROJECT", "FEATURE", "TASK").map { JsonPrimitive(it) })
                    )
                ),
                "entityId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Entity identifier (UUID)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "includeContent" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include section content (false for metadata only, saves 85-99% tokens)"),
                        "default" to JsonPrimitive(true)
                    )
                ),
                "sectionIds" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Optional specific section IDs to retrieve"),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "format" to JsonPrimitive("uuid")
                            )
                        )
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated tags filter (returns sections with ANY tag)")
                    )
                )
            )
        ),
        required = listOf("entityType", "entityId")
    )

    override fun validateParams(params: JsonElement) {
        val entityType = requireString(params, "entityType")

        // Validate entity type
        if (entityType !in listOf("PROJECT", "FEATURE", "TASK")) {
            throw ToolValidationException("Invalid entityType: $entityType. Must be one of: PROJECT, FEATURE, TASK")
        }

        // Validate entityId format
        val entityIdStr = requireString(params, "entityId")
        try {
            UUID.fromString(entityIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entityId format. Must be a valid UUID")
        }

        // Validate optional sectionIds array
        optionalJsonArray(params, "sectionIds")?.let { array ->
            array.forEach { element ->
                try {
                    UUID.fromString(element.jsonPrimitive.content)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid section ID format in sectionIds array. All IDs must be valid UUIDs")
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing query_sections tool")

        return try {
            val entityTypeStr = requireString(params, "entityType")
            val entityType = EntityType.valueOf(entityTypeStr)
            val entityId = extractEntityId(params, "entityId")

            val includeContent = optionalBoolean(params, "includeContent", true)

            val sectionIds = optionalJsonArray(params, "sectionIds")?.map {
                UUID.fromString(it.jsonPrimitive.content)
            }

            val filterTags = optionalString(params, "tags")?.let { tagsString ->
                tagsString.split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            }

            // Verify entity exists before getting sections
            verifyEntityExists(context, entityType, entityId)

            // Retrieve sections
            val result = context.sectionRepository().getSectionsForEntity(entityType, entityId)

            when (result) {
                is Result.Success -> {
                    val allSections = result.data

                    // Apply sectionIds filter if provided
                    val filteredBySectionIds = if (sectionIds != null) {
                        allSections.filter { section -> section.id in sectionIds }
                    } else {
                        allSections
                    }

                    // Apply tags filter if provided
                    val filteredSections = if (filterTags != null && filterTags.isNotEmpty()) {
                        filteredBySectionIds.filter { section ->
                            // Return sections that contain ANY of the filter tags (OR logic)
                            val sectionTags = section.tags.map { it.lowercase() }
                            filterTags.any { filterTag -> sectionTags.contains(filterTag) }
                        }
                    } else {
                        filteredBySectionIds
                    }

                    val sectionsArray = JsonArray(
                        filteredSections.map { section ->
                            buildSectionJson(section, includeContent)
                        }
                    )

                    successResponse(
                        buildJsonObject {
                            put("sections", sectionsArray)
                            put("entityType", entityType.name)
                            put("entityId", entityId.toString())
                            put("count", filteredSections.size)
                        },
                        when {
                            filteredSections.isEmpty() -> "No sections found for ${entityType.name.lowercase()}"
                            filteredSections.size == 1 -> "Retrieved 1 section"
                            else -> "Retrieved ${filteredSections.size} sections"
                        }
                    )
                }

                is Result.Error -> handleRepositoryError(result.error, "sections")
            }
        } catch (e: EntityNotFoundException) {
            logger.warn("Entity not found in query_sections: ${e.message}")
            errorResponse(
                message = e.message ?: "Entity not found",
                code = ErrorCodes.RESOURCE_NOT_FOUND
            )
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in query_sections: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in query_sections", e)
            errorResponse(
                message = "Failed to query sections",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== HELPER METHODS ==========

    private suspend fun verifyEntityExists(
        context: ToolExecutionContext,
        entityType: EntityType,
        entityId: UUID
    ) {
        val result = when (entityType) {
            EntityType.TASK -> context.taskRepository().getById(entityId)
            EntityType.FEATURE -> context.featureRepository().getById(entityId)
            EntityType.PROJECT -> context.projectRepository().getById(entityId)
            else -> return // For TEMPLATE and SECTION types, we skip validation
        }

        if (result is Result.Error) {
            throw EntityNotFoundException("${entityType.name} not found with ID: $entityId")
        }
    }

    private fun buildSectionJson(section: io.github.jpicklyk.mcptask.domain.model.Section, includeContent: Boolean): JsonObject {
        val baseFields = mutableMapOf(
            "id" to JsonPrimitive(section.id.toString()),
            "title" to JsonPrimitive(section.title),
            "usageDescription" to JsonPrimitive(section.usageDescription),
            "contentFormat" to JsonPrimitive(section.contentFormat.name.lowercase()),
            "ordinal" to JsonPrimitive(section.ordinal),
            "tags" to JsonArray(section.tags.map { JsonPrimitive(it) })
        )

        // Only include content if requested
        if (includeContent) {
            baseFields["content"] = JsonPrimitive(section.content)
        }

        return JsonObject(baseFields)
    }

    private fun handleRepositoryError(error: RepositoryError, entityType: String): JsonElement {
        return when (error) {
            is RepositoryError.NotFound -> errorResponse(
                message = "The specified $entityType was not found",
                code = ErrorCodes.RESOURCE_NOT_FOUND
            )
            is RepositoryError.ValidationError -> errorResponse(
                message = "Validation error: ${error.message}",
                code = ErrorCodes.VALIDATION_ERROR,
                details = error.message
            )
            is RepositoryError.DatabaseError -> errorResponse(
                message = "Database error: ${error.message}",
                code = ErrorCodes.DATABASE_ERROR,
                details = error.message
            )
            is RepositoryError.ConflictError -> errorResponse(
                message = "Conflict error: ${error.message}",
                code = ErrorCodes.DUPLICATE_RESOURCE,
                details = error.message
            )
            is RepositoryError.UnknownError -> errorResponse(
                message = "Unknown error: ${error.message}",
                code = ErrorCodes.INTERNAL_ERROR,
                details = error.message
            )
        }
    }

    // Custom exception for entity not found during verification
    private class EntityNotFoundException(message: String) : Exception(message)
}
