package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.UpdateEfficiencyMetrics
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Tool for updating an existing section.
 */
class UpdateSectionTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category = ToolCategory.TASK_MANAGEMENT

    override val name = "update_section"

    override val title: String = "Update Section"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The updated section object"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "usageDescription" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "content" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "contentFormat" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "ordinal" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("array"))),
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

    override val description = """Updates section properties. Only send fields you want to change. For content-only changes, use update_section_text.

        Parameters:
        | Field | Type | Required | Description |
        | id | UUID | Yes | Section identifier |
        | title | string | No | New section title |
        | usageDescription | string | No | New usage description |
        | content | string | No | New section content |
        | contentFormat | enum | No | MARKDOWN, PLAIN_TEXT, JSON, CODE |
        | ordinal | integer | No | Display order position (0-based) |
        | tags | string | No | Comma-separated tags |

        Related: update_section_text, update_section_metadata, get_sections
        Docs: task-orchestrator://docs/tools/update-section
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the section to update"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "title" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New section title")
                    )
                ),
                "usageDescription" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New usage description for the section")
                    )
                ),
                "content" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New section content")
                    )
                ),
                "contentFormat" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New format of the content (MARKDOWN, PLAIN_TEXT, JSON, CODE)"),
                        "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) })
                    )
                ),
                "ordinal" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("(optional) New display order position (0-based)"),
                        "minimum" to JsonPrimitive(0)
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) Comma-separated list of new tags")
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
            throw ToolValidationException("Invalid section ID format. Must be a valid UUID.")
        }

        // Validate optional parameters
        // Validate content format if provided
        if (params is JsonObject && params.containsKey("contentFormat")) {
            val contentFormatStr = optionalString(params, "contentFormat", "")
            if (contentFormatStr.isNotEmpty()) {
                try {
                    ContentFormat.valueOf(contentFormatStr.uppercase())
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException(
                        "Invalid content format. Must be one of: ${
                            ContentFormat.entries.joinToString(
                                ", "
                            )
                        }"
                    )
                }
            }
        }

        // Validate ordinal if provided
        if (params is JsonObject && params.containsKey("ordinal")) {
            val ordinalValue = params["ordinal"]
            if (ordinalValue is JsonPrimitive) {
                val ordinal = when {
                    ordinalValue.isString -> ordinalValue.content.toIntOrNull() ?: -1
                    ordinalValue.content.toString().toIntOrNull() != null -> ordinalValue.content.toString().toInt()
                    else -> -1
                }
                if (ordinal < 0) {
                    throw ToolValidationException("Ordinal must be a non-negative integer")
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing update_section tool")

        try {
            // Analyze update efficiency
            val efficiencyMetrics = UpdateEfficiencyMetrics.analyzeUpdate("update_section", params)

            // Extract ID parameter
            val idStr = requireString(params, "id")
            val sectionId = UUID.fromString(idStr)

            // Get the existing section
            val getSectionResult = context.sectionRepository().getSection(sectionId)
            if (getSectionResult is Result.Error) {
                when (getSectionResult.error) {
                    is RepositoryError.NotFound -> {
                        return errorResponse(
                            message = "Section not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No section exists with ID $sectionId"
                        )
                    }

                    else -> {
                        return errorResponse(
                            message = "Failed to retrieve section",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = getSectionResult.error.toString()
                        )
                    }
                }
            }

            val existingSection = (getSectionResult as Result.Success).data

            // Prepare updated values
            val title = optionalString(params, "title", existingSection.title)
            val usageDescription = optionalString(params, "usageDescription", existingSection.usageDescription)
            val content = optionalString(params, "content", existingSection.content)

            val contentFormatStr = optionalString(params, "contentFormat", "")
            val contentFormat = if (contentFormatStr.isNotEmpty()) {
                try {
                    ContentFormat.valueOf(contentFormatStr.uppercase())
                } catch (e: IllegalArgumentException) {
                    existingSection.contentFormat
                }
            } else {
                existingSection.contentFormat
            }

            val ordinal = if (params is JsonObject && params.containsKey("ordinal")) {
                val ordinalValue = params["ordinal"]
                if (ordinalValue is JsonPrimitive) {
                    when {
                        ordinalValue.isString -> ordinalValue.content.toIntOrNull() ?: existingSection.ordinal
                        ordinalValue.content.toString().toIntOrNull() != null -> ordinalValue.content.toString().toInt()
                        else -> existingSection.ordinal
                    }
                } else {
                    existingSection.ordinal
                }
            } else {
                existingSection.ordinal
            }

            val tagsStr = optionalString(params, "tags", "")
            val tags = if (tagsStr.isNotEmpty()) {
                tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                existingSection.tags
            }

            // Create the updated section
            val updatedSection = existingSection.copy(
                title = title,
                usageDescription = usageDescription,
                content = content,
                contentFormat = contentFormat,
                ordinal = ordinal,
                tags = tags,
                modifiedAt = Instant.now()
            )

            // Update the section in the repository
            val updateResult = context.sectionRepository().updateSection(updatedSection)

            return when (updateResult) {
                is Result.Success -> {
                    val updatedSection = updateResult.data

                    // Build response message with efficiency guidance
                    val efficiencyLevel = efficiencyMetrics["efficiencyLevel"]?.jsonPrimitive?.content
                    val efficiencyGuidance = efficiencyMetrics["guidance"]?.jsonPrimitive?.content ?: ""
                    val baseMessage = "Section updated successfully"
                    val message = if (efficiencyLevel == "inefficient") {
                        "$baseMessage. ⚠️ $efficiencyGuidance"
                    } else {
                        baseMessage
                    }

                    // Return minimal response to optimize bandwidth and performance
                    // Sections don't have a status field, so only return id and modifiedAt
                    val data = buildJsonObject {
                        put("id", updatedSection.id.toString())
                        put("modifiedAt", updatedSection.modifiedAt.toString())
                    }
                    successResponse(data, message)
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to update section",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = updateResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error updating section", e)
            return errorResponse(
                message = "Failed to update section",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Helper method to serialize a Section to JsonObject
     */
    private fun serializeSection(section: io.github.jpicklyk.mcptask.domain.model.Section): JsonObject {
        return buildJsonObject {
            put("id", section.id.toString())
            put("entityType", section.entityType.name.lowercase())
            put("entityId", section.entityId.toString())
            put("title", section.title)
            put("usageDescription", section.usageDescription)
            put("content", section.content)
            put("contentFormat", section.contentFormat.name.lowercase())
            put("ordinal", section.ordinal)
            put("tags", JsonArray(section.tags.map { JsonPrimitive(it) }))
            put("createdAt", section.createdAt.toString())
            put("modifiedAt", section.modifiedAt.toString())
        }
    }
}