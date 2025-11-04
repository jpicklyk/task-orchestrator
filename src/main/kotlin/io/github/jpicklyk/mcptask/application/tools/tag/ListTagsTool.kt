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
 * MCP tool for listing all unique tags across all entities with usage counts.
 * Provides tag discovery and helps users understand tag usage patterns.
 */
class ListTagsTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "list_tags"

    override val title: String = "List All Tags"

    override val description: String = """Lists all unique tags across all entities with usage counts and entity type breakdown.

        Features:
        - Shows all tags used across projects, features, tasks, and templates
        - Usage counts per tag
        - Entity type breakdown (how many projects/features/tasks/templates use each tag)
        - Filter by specific entity types
        - Sort by usage count or alphabetically

        Parameters:
        - entityTypes (optional): Filter by entity types array (PROJECT, FEATURE, TASK, TEMPLATE)
        - sortBy (optional): Sort by 'count' (usage count) or 'name' (alphabetically). Default: count
        - sortDirection (optional): 'asc' (ascending) or 'desc' (descending). Default: desc

        Use Cases:
        - Tag discovery before searching
        - Tag cleanup (identify rarely used tags)
        - Tag standardization (detect variations like "bug" vs "bugs")
        - Usage analysis (understand common categorization patterns)

        Usage notes:
        - Default returns all tags sorted by usage count (descending)
        - Use sortBy="name" for alphabetical view
        - Filter by entityTypes to see tags for specific entity types only
        - Use before search_tasks/search_features to discover available tags

        Related tools: get_tag_usage, rename_tag, search_tasks, search_features

        For detailed examples and patterns: task-orchestrator://docs/tools/list-tags
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "entityTypes" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("Filter by entity types (PROJECT, FEATURE, TASK, TEMPLATE). If not specified, includes all types."),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "enum" to JsonArray(
                                    listOf(
                                        "PROJECT",
                                        "FEATURE",
                                        "TASK",
                                        "TEMPLATE"
                                    ).map { JsonPrimitive(it) })
                            )
                        )
                    )
                ),
                "sortBy" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Sort by 'count' (usage count) or 'name' (alphabetically). Default: count"),
                        "enum" to JsonArray(listOf("count", "name").map { JsonPrimitive(it) }),
                        "default" to JsonPrimitive("count")
                    )
                ),
                "sortDirection" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Sort direction: 'asc' (ascending) or 'desc' (descending). Default: desc"),
                        "enum" to JsonArray(listOf("asc", "desc").map { JsonPrimitive(it) }),
                        "default" to JsonPrimitive("desc")
                    )
                )
            )
        ),
        required = listOf()
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
                        "description" to JsonPrimitive("Tag listing results"),
                        "properties" to JsonObject(
                            mapOf(
                                "tags" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("List of tags with usage counts"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "tag" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                        "totalCount" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                        "byEntityType" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("object"),
                                                                "description" to JsonPrimitive("Usage count breakdown by entity type")
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "totalTags" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Total number of unique tags")
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
        // Validate entityTypes if provided
        if (params is JsonObject && params.containsKey("entityTypes")) {
            val entityTypesElement = params["entityTypes"]
            if (entityTypesElement is JsonArray) {
                entityTypesElement.forEach { typeElement ->
                    val typeStr = typeElement.jsonPrimitive.content
                    if (typeStr !in listOf("PROJECT", "FEATURE", "TASK", "TEMPLATE")) {
                        throw ToolValidationException("Invalid entity type: $typeStr. Must be PROJECT, FEATURE, TASK, or TEMPLATE")
                    }
                }
            }
        }

        // Validate sortBy if provided
        if (params is JsonObject && params.containsKey("sortBy")) {
            val sortBy = params["sortBy"]?.jsonPrimitive?.content
            if (sortBy != null && sortBy !in listOf("count", "name")) {
                throw ToolValidationException("Invalid sortBy: $sortBy. Must be 'count' or 'name'")
            }
        }

        // Validate sortDirection if provided
        if (params is JsonObject && params.containsKey("sortDirection")) {
            val sortDirection = params["sortDirection"]?.jsonPrimitive?.content
            if (sortDirection != null && sortDirection !in listOf("asc", "desc")) {
                throw ToolValidationException("Invalid sortDirection: $sortDirection. Must be 'asc' or 'desc'")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing list_tags tool")

        return try {
            // Parse parameters
            val entityTypes = parseEntityTypes(params)
            val sortBy = if (params is JsonObject) params["sortBy"]?.jsonPrimitive?.content ?: "count" else "count"
            val sortDirection = if (params is JsonObject) params["sortDirection"]?.jsonPrimitive?.content ?: "desc" else "desc"

            // Collect tags from each entity type
            val tagCounts = mutableMapOf<String, MutableMap<String, Int>>()

            // Tasks
            if (entityTypes.contains(EntityType.TASK)) {
                when (val tagsResult = context.taskRepository().getAllTags()) {
                    is Result.Success -> {
                        tagsResult.data.forEach { tag ->
                            when (val countResult = context.taskRepository().countByTag(tag)) {
                                is Result.Success -> {
                                    tagCounts.getOrPut(tag) { mutableMapOf() }["TASK"] = countResult.data.toInt()
                                }
                                is Result.Error -> {
                                    logger.warn("Failed to count task tag '$tag': ${countResult.error.message}")
                                }
                            }
                        }
                    }
                    is Result.Error -> {
                        logger.warn("Failed to get task tags: ${tagsResult.error.message}")
                    }
                }
            }

            // Features
            if (entityTypes.contains(EntityType.FEATURE)) {
                when (val tagsResult = context.repositoryProvider.featureRepository().getAllTags()) {
                    is Result.Success -> {
                        tagsResult.data.forEach { tag ->
                            when (val countResult = context.repositoryProvider.featureRepository().countByTag(tag)) {
                                is Result.Success -> {
                                    tagCounts.getOrPut(tag) { mutableMapOf() }["FEATURE"] = countResult.data.toInt()
                                }
                                is Result.Error -> {
                                    logger.warn("Failed to count feature tag '$tag': ${countResult.error.message}")
                                }
                            }
                        }
                    }
                    is Result.Error -> {
                        logger.warn("Failed to get feature tags: ${tagsResult.error.message}")
                    }
                }
            }

            // Projects
            if (entityTypes.contains(EntityType.PROJECT)) {
                when (val tagsResult = context.repositoryProvider.projectRepository().getAllTags()) {
                    is Result.Success -> {
                        tagsResult.data.forEach { tag ->
                            when (val countResult = context.repositoryProvider.projectRepository().countByTag(tag)) {
                                is Result.Success -> {
                                    tagCounts.getOrPut(tag) { mutableMapOf() }["PROJECT"] = countResult.data.toInt()
                                }
                                is Result.Error -> {
                                    logger.warn("Failed to count project tag '$tag': ${countResult.error.message}")
                                }
                            }
                        }
                    }
                    is Result.Error -> {
                        logger.warn("Failed to get project tags: ${tagsResult.error.message}")
                    }
                }
            }

            // Templates (need to get all templates and extract tags)
            if (entityTypes.contains(EntityType.TEMPLATE)) {
                when (val result = context.repositoryProvider.templateRepository().getAllTemplates()) {
                    is Result.Success -> {
                        result.data.forEach { template ->
                            template.tags.forEach { tag ->
                                tagCounts.getOrPut(tag) { mutableMapOf() }["TEMPLATE"] =
                                    (tagCounts[tag]?.get("TEMPLATE") ?: 0) + 1
                            }
                        }
                    }
                    is Result.Error -> {
                        logger.warn("Failed to get templates for tag extraction: ${result.error.message}")
                    }
                }
            }

            // Build result list
            var tagList = tagCounts.map { (tag, counts) ->
                buildJsonObject {
                    put("tag", tag)
                    put("totalCount", counts.values.sum())
                    put("byEntityType", buildJsonObject {
                        counts.forEach { (entityType, count) ->
                            put(entityType, count)
                        }
                    })
                }
            }

            // Sort the results
            tagList = when (sortBy) {
                "count" -> {
                    if (sortDirection == "asc") {
                        tagList.sortedBy { it["totalCount"]?.jsonPrimitive?.int ?: 0 }
                    } else {
                        tagList.sortedByDescending { it["totalCount"]?.jsonPrimitive?.int ?: 0 }
                    }
                }
                "name" -> {
                    if (sortDirection == "asc") {
                        tagList.sortedBy { it["tag"]?.jsonPrimitive?.content ?: "" }
                    } else {
                        tagList.sortedByDescending { it["tag"]?.jsonPrimitive?.content ?: "" }
                    }
                }
                else -> tagList
            }

            successResponse(
                data = buildJsonObject {
                    put("tags", JsonArray(tagList))
                    put("totalTags", tagList.size)
                },
                message = "Found ${tagList.size} unique tag(s)"
            )
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in list_tags: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in list_tags", e)
            errorResponse(
                message = "Failed to list tags",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Parses entity types from parameters.
     * If not specified, returns all entity types.
     */
    private fun parseEntityTypes(params: JsonElement): List<EntityType> {
        if (params !is JsonObject || !params.containsKey("entityTypes")) {
            // Default: all entity types
            return listOf(EntityType.PROJECT, EntityType.FEATURE, EntityType.TASK, EntityType.TEMPLATE)
        }

        val entityTypesElement = params["entityTypes"]
        if (entityTypesElement !is JsonArray) {
            return listOf(EntityType.PROJECT, EntityType.FEATURE, EntityType.TASK, EntityType.TEMPLATE)
        }

        return entityTypesElement.map { typeElement ->
            when (typeElement.jsonPrimitive.content) {
                "PROJECT" -> EntityType.PROJECT
                "FEATURE" -> EntityType.FEATURE
                "TASK" -> EntityType.TASK
                "TEMPLATE" -> EntityType.TEMPLATE
                else -> throw ToolValidationException("Invalid entity type")
            }
        }
    }
}