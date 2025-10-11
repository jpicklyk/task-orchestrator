package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for updating existing features with partial updates.
 */
class UpdateFeatureTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.FEATURE_MANAGEMENT

    override val name: String = "update_feature"

    override val title: String = "Update Feature"

    override fun shouldUseLocking(): Boolean = true

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The updated feature object"),
                        "properties" to JsonObject(
                            mapOf(
                                "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "status" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("planning", "in-development", "completed", "archived").map { JsonPrimitive(it) })
                                    )
                                ),
                                "priority" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "enum" to JsonArray(listOf("high", "medium", "low").map { JsonPrimitive(it) })
                                    )
                                ),
                                "createdAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "modifiedAt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))),
                                "projectId" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), "format" to JsonPrimitive("uuid"))),
                                "tags" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Comma-separated list of tags")))
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """Update an existing feature's properties.

        ⚡ **EFFICIENCY TIP**: Only send fields you want to change! All fields except 'id' are optional.
        Sending unchanged fields wastes 90%+ tokens. Example: To update status, send only {"id": "uuid", "status": "in-development"}

        ## Efficient vs Inefficient Updates

        ❌ **INEFFICIENT** (wastes ~400+ characters):
        ```json
        {
          "id": "feature-uuid",
          "name": "Existing Feature Name",        // Unchanged - unnecessary
          "summary": "Long existing summary...",  // Unchanged - 400+ chars wasted
          "status": "in-development",             // ✓ Only this changed
          "priority": "high",                     // Unchanged - unnecessary
          "tags": "tag1,tag2,tag3"               // Unchanged - unnecessary
        }
        ```

        ✅ **EFFICIENT** (uses ~40 characters):
        ```json
        {
          "id": "feature-uuid",
          "status": "in-development"  // Only send what changed!
        }
        ```

        **Token Savings**: 90% reduction by only sending changed fields!

        ## Partial Updates
        Only specify fields you want to change. Unspecified fields remain unchanged.
        This tool supports partial updates for all fields except 'id' (which is required).
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID of the feature to update")
                    )
                ),
                "name" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New feature name")
                    )
                ),
                "summary" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New feature summary")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New status (planning, in-development, completed, archived)")
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New priority level (high, medium, low)")
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New project ID (UUID) to associate this feature with"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("(optional) New comma-separated list of tags")
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid id format. Must be a valid UUID")
        }

        // Validate name if present
        optionalString(params, "name")?.let {
            if (it.isBlank()) {
                throw ToolValidationException("Feature name cannot be empty")
            }
        }

        // Validate summary if present
        optionalString(params, "summary")?.let {
            if (it.isBlank()) {
                throw ToolValidationException("Feature summary cannot be empty")
            }
        }

        // Validate status if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status. Must be one of: planning, in-development, completed, archived")
            }
        }

        // Validate priority if present
        optionalString(params, "priority")?.let { priority ->
            if (!isValidPriority(priority)) {
                throw ToolValidationException("Invalid priority: $priority. Must be one of: high, medium, low")
            }
        }

        // Validate projectId if present
        optionalString(params, "projectId")?.let { projectId ->
            if (projectId.isNotEmpty()) {
                try {
                    UUID.fromString(projectId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid project ID format. Must be a valid UUID")
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing update_feature tool")

        return try {
            // Extract feature ID
            val featureId = extractEntityId(params, "id")

            // Execute with proper locking
            executeWithLocking("update_feature", EntityType.FEATURE, featureId) {
                executeFeatureUpdate(params, context, featureId)
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error updating feature: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error updating feature", e)
            errorResponse(
                message = "Failed to update feature",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Executes the actual feature update business logic.
     */
    private suspend fun executeFeatureUpdate(
        params: JsonElement,
        context: ToolExecutionContext,
        featureId: UUID
    ): JsonElement {
        // Get the existing feature
        val featureResult = context.featureRepository().getById(featureId)

        return when (featureResult) {
            is Result.Success -> {
                val feature = featureResult.data

                // Extract update parameters
                val name = optionalString(params, "name") ?: feature.name
                val summary = optionalString(params, "summary") ?: feature.summary
                val status = optionalString(params, "status")?.let { parseStatus(it) } ?: feature.status
                val priority = optionalString(params, "priority")?.let { parsePriority(it) } ?: feature.priority

                val projectId = optionalString(params, "projectId")?.let {
                    if (it.isEmpty()) null else UUID.fromString(it)
                } ?: feature.projectId

                // Validate that referenced project exists if projectId is being set/changed
                if (projectId != null && projectId != feature.projectId) {
                    when (val projectResult = context.repositoryProvider.projectRepository().getById(projectId)) {
                        is Result.Error -> {
                            return errorResponse(
                                message = "Project not found",
                                code = ErrorCodes.RESOURCE_NOT_FOUND,
                                details = "No project exists with ID $projectId"
                            )
                        }
                        is Result.Success -> { /* Project exists, continue */ }
                    }
                }

                val tags = optionalString(params, "tags")?.let {
                    it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotBlank() }
                } ?: feature.tags

                // Create an updated feature
                val updatedFeature = feature.update(
                    name = name,
                    projectId = projectId,
                    summary = summary,
                    status = status,
                    priority = priority,
                    tags = tags
                )

                // Save the updated feature
                when (val updateResult = context.featureRepository().update(updatedFeature)) {
                    is Result.Success -> {
                        val savedFeature = updateResult.data

                        // Return minimal response to optimize bandwidth and performance
                        // Only return essential fields: id, status, and modifiedAt
                        val responseData = buildJsonObject {
                            put("id", savedFeature.id.toString())
                            put("status", savedFeature.status.name.lowercase().replace('_', '-'))
                            put("modifiedAt", savedFeature.modifiedAt.toString())
                        }

                        successResponse(
                            data = responseData,
                            message = "Feature updated successfully"
                        )
                    }

                    is Result.Error -> {
                        // Determine appropriate error code and message based on error type
                        when (val error = updateResult.error) {
                            is RepositoryError.ValidationError -> {
                                errorResponse(
                                    message = "Validation error: ${error.message}",
                                    code = ErrorCodes.VALIDATION_ERROR,
                                    details = error.message
                                )
                            }

                            is RepositoryError.DatabaseError -> {
                                errorResponse(
                                    message = "Database error occurred",
                                    code = ErrorCodes.DATABASE_ERROR,
                                    details = error.message
                                )
                            }

                            is RepositoryError.ConflictError -> {
                                errorResponse(
                                    message = "Conflict error: ${error.message}",
                                    code = ErrorCodes.DUPLICATE_RESOURCE,
                                    details = error.message
                                )
                            }

                            else -> {
                                errorResponse(
                                    message = "Failed to update feature",
                                    code = ErrorCodes.INTERNAL_ERROR,
                                    details = error.toString()
                                )
                            }
                        }
                    }
                }
            }

            is Result.Error -> {
                // Handle errors when getting the feature
                when (val error = featureResult.error) {
                    is RepositoryError.NotFound -> {
                        errorResponse(
                            message = "Feature not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No feature exists with ID $featureId"
                        )
                    }

                    is RepositoryError.DatabaseError -> {
                        errorResponse(
                            message = "Database error occurred",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = error.message
                        )
                    }

                    else -> {
                        errorResponse(
                            message = "Failed to retrieve feature",
                            code = ErrorCodes.INTERNAL_ERROR,
                            details = error.toString()
                        )
                    }
                }
            }
        }
    }

    /**
     * Checks if a string is a valid feature status.
     *
     * @param status The status string to check
     * @return true if the status is valid, false otherwise
     */
    private fun isValidStatus(status: String): Boolean {
        return try {
            parseStatus(status)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses a string into a FeatureStatus enum.
     *
     * @param status The status string to parse
     * @return The corresponding FeatureStatus enum value
     * @throws IllegalArgumentException If the status string is invalid
     */
    private fun parseStatus(status: String): FeatureStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> FeatureStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> FeatureStatus.IN_DEVELOPMENT
            "completed" -> FeatureStatus.COMPLETED
            "archived" -> FeatureStatus.ARCHIVED
            else -> throw IllegalArgumentException("Invalid status: $status")
        }
    }

    /**
     * Checks if a string is a valid priority level.
     *
     * @param priority The priority string to check
     * @return true if the priority is valid, false otherwise
     */
    private fun isValidPriority(priority: String): Boolean {
        return try {
            parsePriority(priority)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses a string into a Priority enum.
     *
     * @param priority The priority string to parse
     * @return The corresponding Priority enum value
     * @throws IllegalArgumentException If the priority string is invalid
     */
    private fun parsePriority(priority: String): Priority {
        return when (priority.lowercase()) {
            "high" -> Priority.HIGH
            "medium", "med" -> Priority.MEDIUM
            "low" -> Priority.LOW
            else -> throw IllegalArgumentException("Invalid priority: $priority")
        }
    }
}