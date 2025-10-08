package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * Tool for deleting a section by its ID.
 */
class DeleteSectionTool : BaseToolDefinition() {
    override val category = ToolCategory.TASK_MANAGEMENT

    override val name = "delete_section"

    override val title: String = "Delete Section"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Deletion result"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "deleted" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                                "entityType" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "entityId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "title" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description = "Deletes a section by its ID"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the section to delete"),
                        "format" to JsonPrimitive("uuid")
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
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing delete_section tool")

        try {
            // Extract ID parameter
            val idStr = requireString(params, "id")
            val sectionId = UUID.fromString(idStr)

            // Get the section before deleting (to return information about what was deleted)
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

            val section = (getSectionResult as Result.Success).data

            // Delete the section
            val deleteResult = context.sectionRepository().deleteSection(sectionId)

            return when (deleteResult) {
                is Result.Success -> {
                    val data = buildJsonObject {
                        put("id", sectionId.toString())
                        put("deleted", true)
                        put("entityType", section.entityType.name.lowercase())
                        put("entityId", section.entityId.toString())
                        put("title", section.title)
                    }
                    successResponse(data, "Section deleted successfully")
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to delete section",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = deleteResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error deleting section", e)
            return errorResponse(
                message = "Failed to delete section",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}