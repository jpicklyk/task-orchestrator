package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Tool for updating multiple sections in a single operation.
 */
class BulkUpdateSectionsTool : BaseToolDefinition() {
    override val category = ToolCategory.TASK_MANAGEMENT
    override val name = "bulk_update_sections"
    override val description = "Updates multiple sections in a single operation"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "sections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Array of sections to update"),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(
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
                                                "description" to JsonPrimitive("New section title")
                                            )
                                        ),
                                        "usageDescription" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("New usage description for the section")
                                            )
                                        ),
                                        "content" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("New section content")
                                            )
                                        ),
                                        "contentFormat" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("New format of the content (MARKDOWN, PLAIN_TEXT, JSON, CODE)"),
                                                "enum" to JsonArray(ContentFormat.entries.map { JsonPrimitive(it.name) })
                                            )
                                        ),
                                        "ordinal" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("integer"),
                                                "description" to JsonPrimitive("New display order position (0-based)"),
                                                "minimum" to JsonPrimitive(0)
                                            )
                                        ),
                                        "tags" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("string"),
                                                "description" to JsonPrimitive("Comma-separated list of new tags")
                                            )
                                        )
                                    )
                                ),
                                "required" to JsonArray(
                                    listOf(JsonPrimitive("id"))
                                )
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("sections")
    )

    override fun validateParams(params: JsonElement) {
        // Make sure sections is present
        val sectionsArray = params.jsonObject["sections"]
            ?: throw ToolValidationException("Missing required parameter: sections")

        if (sectionsArray !is JsonArray) {
            throw ToolValidationException("Parameter 'sections' must be an array")
        }

        if (sectionsArray.isEmpty()) {
            throw ToolValidationException("At least one section must be provided")
        }

        // Validate each section
        sectionsArray.forEachIndexed { index, sectionElement ->
            if (sectionElement !is JsonObject) {
                throw ToolValidationException("Section at index $index must be an object")
            }

            val sectionObj = sectionElement.jsonObject

            // Validate required ID field
            if (!sectionObj.containsKey("id")) {
                throw ToolValidationException("Section at index $index is missing required field: id")
            }

            // Validate id format
            val idStr = sectionObj["id"]?.jsonPrimitive?.content
            if (idStr != null) {
                try {
                    UUID.fromString(idStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid id at index $index: $idStr. Must be a valid UUID.")
                }
            }

            // Validate contentFormat if provided
            val contentFormatStr = sectionObj["contentFormat"]?.jsonPrimitive?.content
            if (contentFormatStr != null) {
                try {
                    ContentFormat.valueOf(contentFormatStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException(
                        "Invalid contentFormat at index $index: $contentFormatStr. " +
                                "Must be one of: ${ContentFormat.entries.joinToString()}"
                    )
                }
            }

            // Validate ordinal if provided
            val ordinalElement = sectionObj["ordinal"]
            if (ordinalElement is JsonPrimitive) {
                val ordinal = when {
                    ordinalElement.isString -> ordinalElement.content.toIntOrNull()
                    else -> ordinalElement.intOrNull
                }
                if (ordinal == null || ordinal < 0) {
                    throw ToolValidationException(
                        "Invalid ordinal at index $index: ${ordinalElement.content}. " +
                                "Must be a non-negative integer."
                    )
                }
            }

            // Ensure at least one field to update is provided
            val updateFields = listOf("title", "usageDescription", "content", "contentFormat", "ordinal", "tags")
            if (updateFields.none { sectionObj.containsKey(it) }) {
                throw ToolValidationException(
                    "Section at index $index has no fields to update. " +
                            "At least one of $updateFields must be provided."
                )
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing bulk_update_sections tool")

        try {
            val sectionsArray = params.jsonObject["sections"] as JsonArray
            val successfulSections = mutableListOf<JsonObject>()
            val failedSections = mutableListOf<JsonObject>()

            sectionsArray.forEachIndexed { index, sectionElement ->
                val sectionParams = sectionElement.jsonObject

                // Get the section ID (required)
                val idStr = sectionParams["id"]!!.jsonPrimitive.content
                val sectionId = UUID.fromString(idStr)

                // Get the existing section
                val getSectionResult = context.sectionRepository().getSection(sectionId)
                if (getSectionResult is Result.Error) {
                    failedSections.add(buildJsonObject {
                        put("index", index)
                        put("error", buildJsonObject {
                            put(
                                "code", when (getSectionResult.error) {
                                    is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                    else -> ErrorCodes.DATABASE_ERROR
                                }
                            )
                            put("details", getSectionResult.error.toString())
                        })
                    })
                    return@forEachIndexed
                }

                val existingSection = (getSectionResult as Result.Success).data

                // Parse update parameters
                val title = optionalString(sectionParams, "title", existingSection.title)
                val usageDescription =
                    optionalString(sectionParams, "usageDescription", existingSection.usageDescription)
                val content = optionalString(sectionParams, "content", existingSection.content)

                val contentFormatStr = optionalString(sectionParams, "contentFormat", "")
                val contentFormat = if (contentFormatStr.isNotEmpty()) {
                    try {
                        ContentFormat.valueOf(contentFormatStr.uppercase())
                    } catch (_: IllegalArgumentException) {
                        existingSection.contentFormat
                    }
                } else {
                    existingSection.contentFormat
                }

                val ordinal = if (sectionParams.containsKey("ordinal")) {
                    val ordinalValue = sectionParams["ordinal"]
                    if (ordinalValue is JsonPrimitive) {
                        when {
                            ordinalValue.isString -> ordinalValue.content.toIntOrNull() ?: existingSection.ordinal
                            ordinalValue.content.toString().toIntOrNull() != null -> ordinalValue.content.toString()
                                .toInt()

                            else -> existingSection.ordinal
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

                when (updateResult) {
                    is Result.Success -> {
                        successfulSections.add(serializeSection(updateResult.data))
                    }

                    is Result.Error -> {
                        failedSections.add(buildJsonObject {
                            put("index", index)
                            put("error", buildJsonObject {
                                put(
                                    "code", when (updateResult.error) {
                                        is RepositoryError.ValidationError -> ErrorCodes.VALIDATION_ERROR
                                        is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                        else -> ErrorCodes.DATABASE_ERROR
                                    }
                                )
                                put("details", updateResult.error.toString())
                            })
                        })
                    }
                }
            }

            // Build the response
            val totalRequested = sectionsArray.size
            val successCount = successfulSections.size
            val failedCount = failedSections.size

            return if (failedCount == 0) {
                // All sections updated successfully
                successResponse(
                    data = buildJsonObject {
                        put("items", JsonArray(successfulSections))
                        put("count", successCount)
                        put("failed", 0)
                    },
                    message = "$successCount sections updated successfully"
                )
            } else if (successCount == 0) {
                // All sections failed to update
                errorResponse(
                    message = "Failed to update any sections",
                    code = ErrorCodes.OPERATION_FAILED,
                    details = "All $totalRequested sections failed to update",
                    additionalData = buildJsonObject {
                        put("failures", JsonArray(failedSections))
                    }
                )
            } else {
                // Partial success
                successResponse(
                    data = buildJsonObject {
                        put("items", JsonArray(successfulSections))
                        put("count", successCount)
                        put("failed", failedCount)
                        put("failures", JsonArray(failedSections))
                    },
                    message = "$successCount sections updated successfully, $failedCount failed"
                )
            }
        } catch (e: Exception) {
            logger.error("Error updating sections in bulk", e)
            return errorResponse(
                message = "Failed to update sections",
                code = ErrorCodes.OPERATION_FAILED,
                details = e.message ?: "Unknown error",
                additionalData = buildJsonObject {
                    put("exception", e.javaClass.simpleName)
                }
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
            put("contentFormat", section.contentFormat.name.lowercase())
            put("ordinal", section.ordinal)
            put("modifiedAt", section.modifiedAt.toString())
        }
    }
}