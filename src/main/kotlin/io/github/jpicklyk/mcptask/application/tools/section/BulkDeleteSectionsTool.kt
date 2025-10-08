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
 * Tool for deleting multiple sections in a single operation.
 */
class BulkDeleteSectionsTool : BaseToolDefinition() {
    override val category = ToolCategory.TASK_MANAGEMENT

    override val name = "bulk_delete_sections"

    override val title: String = "Bulk Delete Sections"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Bulk deletion results"),
                        "properties" to JsonObject(
                            mapOf(
                                "deleted" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Number of sections successfully deleted"))),
                                "failed" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Number of sections that failed to delete")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description = "Deletes multiple sections in a single operation"

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "ids" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Array of section IDs to delete"),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Section ID"),
                                "format" to JsonPrimitive("uuid")
                            )
                        )
                    )
                ),
                "hardDelete" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether to permanently delete the sections (true) or soft delete them (false)"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("ids")
    )

    override fun validateParams(params: JsonElement) {
        // Make sure ids is present
        val idsArray = params.jsonObject["ids"]
            ?: throw ToolValidationException("Missing required parameter: ids")

        if (idsArray !is JsonArray) {
            throw ToolValidationException("Parameter 'ids' must be an array")
        }

        if (idsArray.isEmpty()) {
            throw ToolValidationException("At least one section ID must be provided")
        }

        // Validate each ID
        idsArray.forEachIndexed { index, idElement ->
            if (idElement !is JsonPrimitive || !idElement.isString) {
                throw ToolValidationException("Section ID at index $index must be a string")
            }

            val idStr = idElement.content
            try {
                UUID.fromString(idStr)
            } catch (e: IllegalArgumentException) {
                throw ToolValidationException("Invalid section ID at index $index: $idStr. Must be a valid UUID.")
            }
        }

        // Validate hardDelete if provided
        val hardDelete = params.jsonObject["hardDelete"]
        if (hardDelete != null && hardDelete !is JsonPrimitive) {
            throw ToolValidationException("Parameter 'hardDelete' must be a boolean")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing bulk_delete_sections tool")

        try {
            val idsArray = params.jsonObject["ids"] as JsonArray
            val hardDelete = params.jsonObject["hardDelete"]?.jsonPrimitive?.boolean ?: false

            val successfulDeletes = mutableListOf<String>()
            val failedDeletes = mutableListOf<JsonObject>()

            for (index in 0 until idsArray.size) {
                val idElement = idsArray[index]
                val idStr = idElement.jsonPrimitive.content
                val sectionId = UUID.fromString(idStr)

                // Delete the section
                val deleteResult = context.sectionRepository().deleteSection(sectionId)

                when (deleteResult) {
                    is Result.Success -> {
                        if (deleteResult.data) {
                            successfulDeletes.add(idStr)
                        } else {
                            failedDeletes.add(buildJsonObject {
                                put("id", idStr)
                                put("index", index)
                                put("error", buildJsonObject {
                                    put("code", ErrorCodes.DATABASE_ERROR)
                                    put("details", "Section deletion returned false but didn't throw an error")
                                })
                            })
                        }
                    }

                    is Result.Error -> {
                        failedDeletes.add(buildJsonObject {
                            put("id", idStr)
                            put("index", index)
                            put("error", buildJsonObject {
                                put(
                                    "code", when (deleteResult.error) {
                                        is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                        else -> ErrorCodes.DATABASE_ERROR
                                    }
                                )
                                put("details", deleteResult.error.toString())
                            })
                        })
                    }
                }
            }

            // Build the response
            val totalRequested = idsArray.size
            val successCount = successfulDeletes.size
            val failedCount = failedDeletes.size

            return if (failedCount == 0) {
                // All sections deleted successfully
                successResponse(
                    data = buildJsonObject {
                        put("ids", JsonArray(successfulDeletes.map { JsonPrimitive(it) }))
                        put("count", successCount)
                        put("failed", 0)
                        put("hardDelete", hardDelete)
                    },
                    message = "$successCount sections deleted successfully"
                )
            } else if (successCount == 0) {
                // All sections failed to delete
                errorResponse(
                    message = "Failed to delete any sections",
                    code = ErrorCodes.OPERATION_FAILED,
                    details = "All $totalRequested sections failed to delete",
                    additionalData = buildJsonObject {
                        put("failures", JsonArray(failedDeletes))
                    }
                )
            } else {
                // Partial success
                successResponse(
                    data = buildJsonObject {
                        put("ids", JsonArray(successfulDeletes.map { JsonPrimitive(it) }))
                        put("count", successCount)
                        put("failed", failedCount)
                        put("hardDelete", hardDelete)
                        put("failures", JsonArray(failedDeletes))
                    },
                    message = "$successCount sections deleted successfully, $failedCount failed"
                )
            }
        } catch (e: Exception) {
            logger.error("Error deleting sections in bulk", e)
            return errorResponse(
                message = "Failed to delete sections",
                code = ErrorCodes.OPERATION_FAILED,
                details = e.message ?: "Unknown error"
            )
        }
    }
}