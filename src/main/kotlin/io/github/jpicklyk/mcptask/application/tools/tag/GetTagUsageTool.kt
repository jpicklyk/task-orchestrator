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
 * MCP tool for finding all entities (tasks, features, projects, templates) that use a specific tag.
 * Useful for understanding tag usage and impact analysis before renaming or removing tags.
 */
class GetTagUsageTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "get_tag_usage"

    override val title: String = "Get Tag Usage"

    override val description: String = """Shows all entities (tasks, features, projects, templates) that use a specific tag. Essential for impact analysis before renaming/removing tags.

        Parameters:
        - tag (required): Tag to search for (case-insensitive)
        - entityTypes (optional): Comma-separated list (TASK, FEATURE, PROJECT, TEMPLATE). Default: all types

        Returns entities grouped by type with essential metadata:
        - TASK: id, title, status, priority, complexity
        - FEATURE: id, name, status, priority
        - PROJECT: id, name, status
        - TEMPLATE: id, name, targetEntityType, isEnabled

        Use Cases:
        - Impact analysis before renaming ("What would be affected if I rename tag 'api' to 'rest-api'?")
        - Tag cleanup ("Is this old tag still being used anywhere?")
        - Finding related work ("Show me everything tagged 'security'")
        - Understanding tag adoption patterns

        Usage notes:
        - Case-insensitive tag matching
        - Queries all entity types in parallel for efficiency
        - Returns lightweight summaries (no full content)
        - Use before rename_tag to understand impact

        Related tools: list_tags, rename_tag, search_tasks, search_features

        For detailed examples and patterns: task-orchestrator://docs/tools/get-tag-usage
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "tag" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The tag to search for (case-insensitive)")
                    )
                ),
                "entityTypes" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated list of entity types to search (TASK, FEATURE, PROJECT, TEMPLATE). Default: all types"),
                        "default" to JsonPrimitive("TASK,FEATURE,PROJECT,TEMPLATE")
                    )
                )
            )
        ),
        required = listOf("tag")
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
                        "description" to JsonPrimitive("Tag usage information"),
                        "properties" to JsonObject(
                            mapOf(
                                "tag" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("The tag that was searched")
                                    )
                                ),
                                "totalCount" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Total number of entities using this tag")
                                    )
                                ),
                                "entities" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Entities grouped by type"),
                                        "properties" to JsonObject(
                                            mapOf(
                                                "TASK" to JsonObject(
                                                    mapOf(
                                                        "type" to JsonPrimitive("array"),
                                                        "description" to JsonPrimitive("Tasks using this tag")
                                                    )
                                                ),
                                                "FEATURE" to JsonObject(
                                                    mapOf(
                                                        "type" to JsonPrimitive("array"),
                                                        "description" to JsonPrimitive("Features using this tag")
                                                    )
                                                ),
                                                "PROJECT" to JsonObject(
                                                    mapOf(
                                                        "type" to JsonPrimitive("array"),
                                                        "description" to JsonPrimitive("Projects using this tag")
                                                    )
                                                ),
                                                "TEMPLATE" to JsonObject(
                                                    mapOf(
                                                        "type" to JsonPrimitive("array"),
                                                        "description" to JsonPrimitive("Templates using this tag")
                                                    )
                                                )
                                            )
                                        )
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
        // Validate tag parameter
        if (params !is JsonObject || !params.containsKey("tag")) {
            throw ToolValidationException("Missing required parameter: tag")
        }

        val tag = params["tag"]?.jsonPrimitive?.content
        if (tag.isNullOrBlank()) {
            throw ToolValidationException("Tag cannot be empty")
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
        logger.info("Executing get_tag_usage tool")

        return try {
            // Parse parameters
            val tag = (params as JsonObject)["tag"]?.jsonPrimitive?.content!!
            val entityTypesStr = params["entityTypes"]?.jsonPrimitive?.content
                ?: "TASK,FEATURE,PROJECT,TEMPLATE"
            val requestedTypes = entityTypesStr.split(",")
                .map { it.trim().uppercase() }
                .mapNotNull {
                    try {
                        EntityType.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }

            // Collect entities by type
            val entitiesByType = mutableMapOf<String, JsonArray>()
            var totalCount = 0

            // Search tasks
            if (EntityType.TASK in requestedTypes) {
                val tasks = getTasksWithTag(tag, context)
                if (tasks.isNotEmpty()) {
                    entitiesByType["TASK"] = JsonArray(tasks)
                    totalCount += tasks.size
                }
            }

            // Search features
            if (EntityType.FEATURE in requestedTypes) {
                val features = getFeaturesWithTag(tag, context)
                if (features.isNotEmpty()) {
                    entitiesByType["FEATURE"] = JsonArray(features)
                    totalCount += features.size
                }
            }

            // Search projects
            if (EntityType.PROJECT in requestedTypes) {
                val projects = getProjectsWithTag(tag, context)
                if (projects.isNotEmpty()) {
                    entitiesByType["PROJECT"] = JsonArray(projects)
                    totalCount += projects.size
                }
            }

            // Search templates
            if (EntityType.TEMPLATE in requestedTypes) {
                val templates = getTemplatesWithTag(tag, context)
                if (templates.isNotEmpty()) {
                    entitiesByType["TEMPLATE"] = JsonArray(templates)
                    totalCount += templates.size
                }
            }

            successResponse(
                data = buildJsonObject {
                    put("tag", tag)
                    put("totalCount", totalCount)
                    put("entities", buildJsonObject {
                        entitiesByType.forEach { (type, entities) ->
                            put(type, entities)
                        }
                    })
                },
                message = if (totalCount == 0) {
                    "No entities found with tag '$tag'"
                } else {
                    "Found $totalCount entit${if (totalCount == 1) "y" else "ies"} with tag '$tag'"
                }
            )
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in get_tag_usage: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in get_tag_usage", e)
            errorResponse(
                message = "Failed to get tag usage",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Gets all tasks with the specified tag.
     */
    private suspend fun getTasksWithTag(tag: String, context: ToolExecutionContext): List<JsonObject> {
        return when (val result = context.taskRepository().findAll(limit = 10000)) {
            is Result.Success -> {
                result.data
                    .filter { task -> task.tags.any { it.equals(tag, ignoreCase = true) } }
                    .map { task ->
                        buildJsonObject {
                            put("id", task.id.toString())
                            put("title", task.title)
                            put("status", task.status.name.lowercase().replace('_', '-'))
                            put("priority", task.priority.name.lowercase())
                            put("complexity", task.complexity)
                        }
                    }
            }
            is Result.Error -> {
                logger.warn("Failed to get tasks: ${result.error.message}")
                emptyList()
            }
        }
    }

    /**
     * Gets all features with the specified tag.
     */
    private suspend fun getFeaturesWithTag(tag: String, context: ToolExecutionContext): List<JsonObject> {
        return when (val result = context.repositoryProvider.featureRepository().findAll(limit = 10000)) {
            is Result.Success -> {
                result.data
                    .filter { feature -> feature.tags.any { it.equals(tag, ignoreCase = true) } }
                    .map { feature ->
                        buildJsonObject {
                            put("id", feature.id.toString())
                            put("name", feature.name)
                            put("status", feature.status.name.lowercase().replace('_', '-'))
                            put("priority", feature.priority.name.lowercase())
                        }
                    }
            }
            is Result.Error -> {
                logger.warn("Failed to get features: ${result.error.message}")
                emptyList()
            }
        }
    }

    /**
     * Gets all projects with the specified tag.
     */
    private suspend fun getProjectsWithTag(tag: String, context: ToolExecutionContext): List<JsonObject> {
        return when (val result = context.repositoryProvider.projectRepository().findAll(limit = 10000)) {
            is Result.Success -> {
                result.data
                    .filter { project -> project.tags.any { it.equals(tag, ignoreCase = true) } }
                    .map { project ->
                        buildJsonObject {
                            put("id", project.id.toString())
                            put("name", project.name)
                            put("status", project.status.name.lowercase().replace('_', '-'))
                        }
                    }
            }
            is Result.Error -> {
                logger.warn("Failed to get projects: ${result.error.message}")
                emptyList()
            }
        }
    }

    /**
     * Gets all templates with the specified tag.
     */
    private suspend fun getTemplatesWithTag(tag: String, context: ToolExecutionContext): List<JsonObject> {
        return when (val result = context.repositoryProvider.templateRepository().getAllTemplates()) {
            is Result.Success -> {
                result.data
                    .filter { template -> template.tags.any { it.equals(tag, ignoreCase = true) } }
                    .map { template ->
                        buildJsonObject {
                            put("id", template.id.toString())
                            put("name", template.name)
                            put("targetEntityType", template.targetEntityType.name)
                            put("isEnabled", template.isEnabled)
                        }
                    }
            }
            is Result.Error -> {
                logger.warn("Failed to get templates: ${result.error.message}")
                emptyList()
            }
        }
    }
}
