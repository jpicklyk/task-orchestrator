package io.github.jpicklyk.mcptask.application.tools.tag

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for renaming a tag across all entities (tasks, features, projects, templates).
 * Performs bulk tag replacement with detailed statistics and rollback capability.
 */
class RenameTagTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "rename_tag"

    override val title: String = "Rename Tag"

    override val description: String = """Renames a tag across all entities (tasks, features, projects, templates) in a single operation. Essential for maintaining consistent tag taxonomy.

        Parameters:
        - oldTag (required): Tag to rename (case-insensitive matching)
        - newTag (required): New tag name
        - entityTypes (optional): Comma-separated list (TASK, FEATURE, PROJECT, TEMPLATE). Default: all types
        - dryRun (optional): Preview changes without modifying data. Default: false

        Behavior:
        - Case-insensitive search for oldTag
        - Exact replacement with newTag (preserves newTag case)
        - If entity already has newTag, oldTag is simply removed (prevents duplicates)
        - Tags remain in original order
        - Other tags unaffected

        Use Cases:
        - Typo correction ("authentcation" → "authentication")
        - Standardization ("API" → "api")
        - Tag consolidation (merge "rest-api" and "restapi" into "api")
        - Convention changes ("bug-fix" → "bugfix")

        Returns Statistics:
        - totalUpdated: Total entities modified
        - byEntityType: Breakdown by entity type
        - failedUpdates: Count of failures (if any)
        - dryRun: Whether this was a preview

        Usage notes:
        - Use get_tag_usage first to see impact
        - Use dryRun=true to preview changes
        - Continues even if individual updates fail
        - Reports detailed statistics

        Related tools: get_tag_usage, list_tags

        For detailed examples and patterns: task-orchestrator://docs/tools/rename-tag
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "oldTag" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The tag to rename (case-insensitive matching)")
                    )
                ),
                "newTag" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The new tag name to use")
                    )
                ),
                "entityTypes" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of entity types to update (TASK, FEATURE, PROJECT, TEMPLATE). Default: all types"),
                        "default" to JsonPrimitive("TASK,FEATURE,PROJECT,TEMPLATE")
                    )
                ),
                "dryRun" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("If true, shows what would be changed without actually updating. Default: false"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("oldTag", "newTag")
    )

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether the operation succeeded")
                    )
                ),
                "message" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Human-readable message describing the result")
                    )
                ),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Tag rename statistics"),
                        "properties" to JsonObject(
                            mapOf(
                                "oldTag" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("The tag that was renamed")
                                    )
                                ),
                                "newTag" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("The new tag name")
                                    )
                                ),
                                "totalUpdated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Total number of entities updated")
                                    )
                                ),
                                "byEntityType" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Update count by entity type")
                                    )
                                ),
                                "failedUpdates" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Number of entities that failed to update")
                                    )
                                ),
                                "dryRun" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether this was a dry run (preview only)")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("success", "message")
    )

    override fun validateParams(params: JsonElement) {
        if (params !is JsonObject) {
            throw ToolValidationException("Parameters must be a JSON object")
        }

        // Validate oldTag
        if (!params.containsKey("oldTag")) {
            throw ToolValidationException("Missing required parameter: oldTag")
        }
        val oldTag = params["oldTag"]?.jsonPrimitive?.content
        if (oldTag.isNullOrBlank()) {
            throw ToolValidationException("oldTag cannot be empty")
        }

        // Validate newTag
        if (!params.containsKey("newTag")) {
            throw ToolValidationException("Missing required parameter: newTag")
        }
        val newTag = params["newTag"]?.jsonPrimitive?.content
        if (newTag.isNullOrBlank()) {
            throw ToolValidationException("newTag cannot be empty")
        }

        // Validate tags are different
        if (oldTag.equals(newTag, ignoreCase = true)) {
            throw ToolValidationException("oldTag and newTag cannot be the same (case-insensitive)")
        }

        // Validate entityTypes if provided
        if (params.containsKey("entityTypes")) {
            val entityTypesStr = params["entityTypes"]?.jsonPrimitive?.content
            if (!entityTypesStr.isNullOrBlank()) {
                val types = entityTypesStr.split(",").map { it.trim().uppercase() }
                val validTypes = setOf("TASK", "FEATURE", "PROJECT", "TEMPLATE")
                val invalidTypes = types.filterNot { it in validTypes }
                if (invalidTypes.isNotEmpty()) {
                    throw ToolValidationException(
                        "Invalid entity types: ${invalidTypes.joinToString(", ")}. " +
                                "Valid types are: TASK, FEATURE, PROJECT, TEMPLATE"
                    )
                }
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing rename_tag tool")

        return try {
            // Parse parameters
            val paramsObj = params as JsonObject
            val oldTag = paramsObj["oldTag"]?.jsonPrimitive?.content!!
            val newTag = paramsObj["newTag"]?.jsonPrimitive?.content!!
            val entityTypesStr = paramsObj["entityTypes"]?.jsonPrimitive?.content
                ?: "TASK,FEATURE,PROJECT,TEMPLATE"
            val dryRun = paramsObj["dryRun"]?.jsonPrimitive?.boolean ?: false

            val requestedTypes = entityTypesStr.split(",")
                .map { it.trim().uppercase() }
                .mapNotNull {
                    try {
                        EntityType.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }

            // Track statistics
            val updateCounts = mutableMapOf<String, Int>()
            var totalUpdated = 0
            var failedUpdates = 0

            // Rename in tasks
            if (EntityType.TASK in requestedTypes) {
                val (updated, failed) = renameTagInTasks(oldTag, newTag, dryRun, context)
                if (updated > 0) {
                    updateCounts["TASK"] = updated
                    totalUpdated += updated
                }
                failedUpdates += failed
            }

            // Rename in features
            if (EntityType.FEATURE in requestedTypes) {
                val (updated, failed) = renameTagInFeatures(oldTag, newTag, dryRun, context)
                if (updated > 0) {
                    updateCounts["FEATURE"] = updated
                    totalUpdated += updated
                }
                failedUpdates += failed
            }

            // Rename in projects
            if (EntityType.PROJECT in requestedTypes) {
                val (updated, failed) = renameTagInProjects(oldTag, newTag, dryRun, context)
                if (updated > 0) {
                    updateCounts["PROJECT"] = updated
                    totalUpdated += updated
                }
                failedUpdates += failed
            }

            // Rename in templates
            if (EntityType.TEMPLATE in requestedTypes) {
                val (updated, failed) = renameTagInTemplates(oldTag, newTag, dryRun, context)
                if (updated > 0) {
                    updateCounts["TEMPLATE"] = updated
                    totalUpdated += updated
                }
                failedUpdates += failed
            }

            val message = when {
                dryRun && totalUpdated > 0 -> "Would update $totalUpdated entit${if (totalUpdated == 1) "y" else "ies"} (dry run - no changes made)"
                dryRun -> "No entities found with tag '$oldTag' (dry run)"
                totalUpdated > 0 && failedUpdates > 0 -> "Updated $totalUpdated entit${if (totalUpdated == 1) "y" else "ies"}, $failedUpdates failed"
                totalUpdated > 0 -> "Successfully renamed tag in $totalUpdated entit${if (totalUpdated == 1) "y" else "ies"}"
                else -> "No entities found with tag '$oldTag'"
            }

            successResponse(
                data = buildJsonObject {
                    put("oldTag", oldTag)
                    put("newTag", newTag)
                    put("totalUpdated", totalUpdated)
                    put("byEntityType", buildJsonObject {
                        updateCounts.forEach { (type, count) ->
                            put(type, count)
                        }
                    })
                    put("failedUpdates", failedUpdates)
                    put("dryRun", dryRun)
                },
                message = message
            )
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in rename_tag: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in rename_tag", e)
            errorResponse(
                message = "Failed to rename tag",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Renames tag in all tasks.
     * Returns (updatedCount, failedCount)
     */
    private suspend fun renameTagInTasks(
        oldTag: String,
        newTag: String,
        dryRun: Boolean,
        context: ToolExecutionContext
    ): Pair<Int, Int> {
        var updated = 0
        var failed = 0

        when (val result = context.taskRepository().findAll(limit = 10000)) {
            is Result.Success -> {
                result.data
                    .filter { task -> task.tags.any { it.equals(oldTag, ignoreCase = true) } }
                    .forEach { task ->
                        if (!dryRun) {
                            val updatedTags = renameTagInList(task.tags, oldTag, newTag)
                            val updateResult = context.taskRepository().update(
                                task.copy(tags = updatedTags)
                            )
                            when (updateResult) {
                                is Result.Success -> updated++
                                is Result.Error -> {
                                    logger.warn("Failed to update task ${task.id}: ${updateResult.error.message}")
                                    failed++
                                }
                            }
                        } else {
                            updated++
                        }
                    }
            }
            is Result.Error -> {
                logger.warn("Failed to get tasks: ${result.error.message}")
            }
        }

        return updated to failed
    }

    /**
     * Renames tag in all features.
     * Returns (updatedCount, failedCount)
     */
    private suspend fun renameTagInFeatures(
        oldTag: String,
        newTag: String,
        dryRun: Boolean,
        context: ToolExecutionContext
    ): Pair<Int, Int> {
        var updated = 0
        var failed = 0

        when (val result = context.repositoryProvider.featureRepository().findAll(limit = 10000)) {
            is Result.Success -> {
                result.data
                    .filter { feature -> feature.tags.any { it.equals(oldTag, ignoreCase = true) } }
                    .forEach { feature ->
                        if (!dryRun) {
                            val updatedTags = renameTagInList(feature.tags, oldTag, newTag)
                            val updateResult = context.repositoryProvider.featureRepository().update(
                                feature.copy(tags = updatedTags)
                            )
                            when (updateResult) {
                                is Result.Success -> updated++
                                is Result.Error -> {
                                    logger.warn("Failed to update feature ${feature.id}: ${updateResult.error.message}")
                                    failed++
                                }
                            }
                        } else {
                            updated++
                        }
                    }
            }
            is Result.Error -> {
                logger.warn("Failed to get features: ${result.error.message}")
            }
        }

        return updated to failed
    }

    /**
     * Renames tag in all projects.
     * Returns (updatedCount, failedCount)
     */
    private suspend fun renameTagInProjects(
        oldTag: String,
        newTag: String,
        dryRun: Boolean,
        context: ToolExecutionContext
    ): Pair<Int, Int> {
        var updated = 0
        var failed = 0

        when (val result = context.repositoryProvider.projectRepository().findAll(limit = 10000)) {
            is Result.Success -> {
                result.data
                    .filter { project -> project.tags.any { it.equals(oldTag, ignoreCase = true) } }
                    .forEach { project ->
                        if (!dryRun) {
                            val updatedTags = renameTagInList(project.tags, oldTag, newTag)
                            val updateResult = context.repositoryProvider.projectRepository().update(
                                project.copy(tags = updatedTags)
                            )
                            when (updateResult) {
                                is Result.Success -> updated++
                                is Result.Error -> {
                                    logger.warn("Failed to update project ${project.id}: ${updateResult.error.message}")
                                    failed++
                                }
                            }
                        } else {
                            updated++
                        }
                    }
            }
            is Result.Error -> {
                logger.warn("Failed to get projects: ${result.error.message}")
            }
        }

        return updated to failed
    }

    /**
     * Renames tag in all templates.
     * Returns (updatedCount, failedCount)
     */
    private suspend fun renameTagInTemplates(
        oldTag: String,
        newTag: String,
        dryRun: Boolean,
        context: ToolExecutionContext
    ): Pair<Int, Int> {
        var updated = 0
        var failed = 0

        when (val result = context.repositoryProvider.templateRepository().getAllTemplates()) {
            is Result.Success -> {
                result.data
                    .filter { template -> template.tags.any { it.equals(oldTag, ignoreCase = true) } }
                    .forEach { template ->
                        if (!dryRun) {
                            val updatedTags = renameTagInList(template.tags, oldTag, newTag)
                            val updateResult = context.repositoryProvider.templateRepository().updateTemplate(
                                template.copy(tags = updatedTags)
                            )
                            when (updateResult) {
                                is Result.Success -> updated++
                                is Result.Error -> {
                                    logger.warn("Failed to update template ${template.id}: ${updateResult.error.message}")
                                    failed++
                                }
                            }
                        } else {
                            updated++
                        }
                    }
            }
            is Result.Error -> {
                logger.warn("Failed to get templates: ${result.error.message}")
            }
        }

        return updated to failed
    }

    /**
     * Renames a tag in a list of tags.
     * - Case-insensitive matching for oldTag
     * - Replaces with newTag (preserves newTag case)
     * - Removes duplicates if newTag already exists
     * - Preserves order
     */
    private fun renameTagInList(tags: List<String>, oldTag: String, newTag: String): List<String> {
        val result = mutableListOf<String>()
        var hasNewTag = false

        for (tag in tags) {
            when {
                // Found the old tag - replace with new tag
                tag.equals(oldTag, ignoreCase = true) -> {
                    if (!hasNewTag) {
                        result.add(newTag)
                        hasNewTag = true
                    }
                    // Skip if newTag already added
                }
                // Already has the new tag - keep it, mark as found
                tag.equals(newTag, ignoreCase = true) -> {
                    if (!hasNewTag) {
                        result.add(tag) // Keep existing case
                        hasNewTag = true
                    }
                }
                // Other tags - keep unchanged
                else -> result.add(tag)
            }
        }

        return result
    }
}
