package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.rendering.MarkdownRenderer
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * Consolidated MCP tool for read-only operations on all container types (project, feature, task).
 * Includes 4 operations: get, search, export, and overview.
 *
 * This tool unifies read operations across all entity types, reducing token overhead
 * and providing a consistent interface for AI agents to query container data.
 *
 * Part of v2.0's read/write permission separation strategy.
 */
class QueryContainerTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "query_container"

    override val title: String = "Query Container"

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

    override val description: String = """Unified read operations for containers (project, feature, task).

Operations: get, search, export, overview

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | get, search, export, overview |
| containerType | enum | Yes | project, feature, task |
| id | UUID | Varies | Container ID (required for: get, export) |
| query | string | No | Search text query (search only) |
| status | string | No | Filter by status |
| priority | string | No | Filter by priority (feature/task only) |
| tags | string | No | Comma-separated tags filter |
| projectId | UUID | No | Filter by project (feature/task) |
| featureId | UUID | No | Filter by feature (task only) |
| limit | integer | No | Max results (default: 20) |
| includeSections | boolean | No | Include sections (get only, default: false) |
| summaryLength | integer | No | Summary truncation (overview only, 0-200) |

Usage: Consolidates get/search/export/overview for all container types (read-only).
Related: manage_container
Docs: task-orchestrator://docs/tools/query-container
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "operation" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Operation to perform"),
                        "enum" to JsonArray(listOf("get", "search", "export", "overview").map { JsonPrimitive(it) })
                    )
                ),
                "containerType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of container"),
                        "enum" to JsonArray(listOf("project", "feature", "task").map { JsonPrimitive(it) })
                    )
                ),
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Container ID (required for: get, export)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "query" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Search text query (search operation)")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by status")
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by priority (feature/task)")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Comma-separated tags filter")
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by project ID (feature/task)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by feature ID (task only)"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "limit" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum results (search)"),
                        "default" to JsonPrimitive(20)
                    )
                ),
                "includeSections" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include sections (get)"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "summaryLength" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Summary max length (overview, 0-200)"),
                        "default" to JsonPrimitive(100)
                    )
                )
            )
        ),
        required = listOf("operation", "containerType")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        val containerType = requireString(params, "containerType")

        // Validate operation
        if (operation !in listOf("get", "search", "export", "overview")) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: get, search, export, overview")
        }

        // Validate container type
        if (containerType !in listOf("project", "feature", "task")) {
            throw ToolValidationException("Invalid containerType: $containerType. Must be one of: project, feature, task")
        }

        // Validate ID for operations that require it
        if (operation in listOf("get", "export")) {
            val idStr = requireString(params, "id")
            try {
                UUID.fromString(idStr)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid $containerType ID format. Must be a valid UUID.")
            }
        }

        // Validate optional parameters
        validateOptionalParams(params, containerType, operation)
    }

    private fun validateOptionalParams(params: JsonElement, containerType: String, operation: String) {
        // Validate status if present
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status, containerType)) {
                throw ToolValidationException("Invalid status for $containerType: $status")
            }
        }

        // Validate priority if present (feature/task only)
        if (containerType in listOf("feature", "task")) {
            optionalString(params, "priority")?.let { priority ->
                if (!isValidPriority(priority)) {
                    throw ToolValidationException("Invalid priority: $priority. Must be one of: high, medium, low")
                }
            }
        }

        // Validate UUID parameters
        optionalString(params, "projectId")?.let { projectId ->
            if (projectId.isNotEmpty()) {
                try {
                    UUID.fromString(projectId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid projectId format. Must be a valid UUID")
                }
            }
        }

        optionalString(params, "featureId")?.let { featureId ->
            if (featureId.isNotEmpty()) {
                try {
                    UUID.fromString(featureId)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Invalid featureId format. Must be a valid UUID")
                }
            }
        }

        // Validate summaryLength for overview
        if (operation == "overview") {
            optionalInt(params, "summaryLength")?.let { length ->
                if (length < 0 || length > 200) {
                    throw ToolValidationException("Summary length must be between 0 and 200")
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing query_container tool")

        return try {
            val operation = requireString(params, "operation")
            val containerType = requireString(params, "containerType")

            when (operation) {
                "get" -> executeGet(params, context, containerType)
                "search" -> executeSearch(params, context, containerType)
                "export" -> executeExport(params, context, containerType)
                "overview" -> executeOverview(params, context, containerType)
                else -> errorResponse(
                    message = "Invalid operation: $operation",
                    code = ErrorCodes.VALIDATION_ERROR
                )
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in query_container: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in query_container", e)
            errorResponse(
                message = "Failed to execute container query",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== GET OPERATION ==========

    private suspend fun executeGet(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String
    ): JsonElement {
        logger.info("Executing get operation for $containerType")

        val id = extractEntityId(params, "id")
        val includeSections = optionalBoolean(params, "includeSections", false)

        return when (containerType) {
            "project" -> getProject(context, id, includeSections)
            "feature" -> getFeature(context, id, includeSections)
            "task" -> getTask(context, id, includeSections)
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    private suspend fun getProject(context: ToolExecutionContext, id: UUID, includeSections: Boolean): JsonElement {
        return when (val result = context.projectRepository().getById(id)) {
            is Result.Success -> {
                val project = result.data
                val sections = if (includeSections) {
                    when (val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.PROJECT, id)) {
                        is Result.Success -> sectionsResult.data
                        is Result.Error -> emptyList()
                    }
                } else emptyList()

                successResponse(
                    buildProjectJson(project, sections, includeSections),
                    "Project retrieved successfully"
                )
            }
            is Result.Error -> handleNotFoundError(result, "Project", id)
        }
    }

    private suspend fun getFeature(context: ToolExecutionContext, id: UUID, includeSections: Boolean): JsonElement {
        return when (val result = context.featureRepository().getById(id)) {
            is Result.Success -> {
                val feature = result.data
                val sections = if (includeSections) {
                    when (val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, id)) {
                        is Result.Success -> sectionsResult.data
                        is Result.Error -> emptyList()
                    }
                } else emptyList()

                successResponse(
                    buildFeatureJson(feature, sections, includeSections),
                    "Feature retrieved successfully"
                )
            }
            is Result.Error -> handleNotFoundError(result, "Feature", id)
        }
    }

    private suspend fun getTask(context: ToolExecutionContext, id: UUID, includeSections: Boolean): JsonElement {
        return when (val result = context.taskRepository().getById(id)) {
            is Result.Success -> {
                val task = result.data
                val sections = if (includeSections) {
                    when (val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.TASK, id)) {
                        is Result.Success -> sectionsResult.data
                        is Result.Error -> emptyList()
                    }
                } else emptyList()

                successResponse(
                    buildTaskJson(task, sections, includeSections),
                    "Task retrieved successfully"
                )
            }
            is Result.Error -> handleNotFoundError(result, "Task", id)
        }
    }

    // ========== SEARCH OPERATION ==========

    private suspend fun executeSearch(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String
    ): JsonElement {
        logger.info("Executing search operation for $containerType")

        return when (containerType) {
            "project" -> searchProjects(params, context)
            "feature" -> searchFeatures(params, context)
            "task" -> searchTasks(params, context)
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    private suspend fun searchProjects(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val query = optionalString(params, "query")
        val statusStr = optionalString(params, "status")
        val status = statusStr?.let { parseProjectStatus(it) }
        val tagsStr = optionalString(params, "tags")
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val limit = optionalInt(params, "limit") ?: 20

        val result = if (query != null || status != null || tags != null) {
            context.projectRepository().findByFilters(
                projectId = null,
                status = status,
                priority = null,
                tags = tags,
                textQuery = query,
                limit = limit
            )
        } else {
            context.projectRepository().findAll(limit)
        }

        return when (result) {
            is Result.Success -> {
                val projects = result.data
                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            projects.forEach { project ->
                                add(buildProjectJson(project, emptyList(), false))
                            }
                        })
                        put("count", projects.size)
                    },
                    "${projects.size} project(s) found"
                )
            }
            is Result.Error -> errorResponse(
                "Failed to search projects: ${result.error}",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }

    private suspend fun searchFeatures(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val query = optionalString(params, "query")
        val statusStr = optionalString(params, "status")
        val status = statusStr?.let { parseFeatureStatus(it) }
        val priorityStr = optionalString(params, "priority")
        val priority = priorityStr?.let { parsePriority(it) }
        val tagsStr = optionalString(params, "tags")
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val limit = optionalInt(params, "limit") ?: 20

        val result = if (query != null || status != null || priority != null || tags != null || projectId != null) {
            context.featureRepository().findByFilters(
                projectId = projectId,
                status = status,
                priority = priority,
                tags = tags,
                textQuery = query,
                limit = limit
            )
        } else {
            context.featureRepository().findAll(limit)
        }

        return when (result) {
            is Result.Success -> {
                val features = result.data
                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            features.forEach { feature ->
                                add(buildFeatureJson(feature, emptyList(), false))
                            }
                        })
                        put("count", features.size)
                    },
                    "${features.size} feature(s) found"
                )
            }
            is Result.Error -> errorResponse(
                "Failed to search features: ${result.error}",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }

    private suspend fun searchTasks(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val query = optionalString(params, "query")
        val statusStr = optionalString(params, "status")
        val status = statusStr?.let { parseTaskStatus(it) }
        val priorityStr = optionalString(params, "priority")
        val priority = priorityStr?.let { parsePriority(it) }
        val tagsStr = optionalString(params, "tags")
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val featureId = optionalString(params, "featureId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val limit = optionalInt(params, "limit") ?: 20

        val result = if (query != null || status != null || priority != null || tags != null || projectId != null) {
            context.taskRepository().findByFilters(
                projectId = projectId,
                status = status,
                priority = priority,
                tags = tags,
                textQuery = query,
                limit = limit
            )
        } else if (featureId != null) {
            context.taskRepository().findByFeature(featureId, status, priority, limit)
        } else {
            context.taskRepository().findAll(limit)
        }

        return when (result) {
            is Result.Success -> {
                val tasks = result.data
                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            tasks.forEach { task ->
                                add(buildTaskJson(task, emptyList(), false))
                            }
                        })
                        put("count", tasks.size)
                    },
                    "${tasks.size} task(s) found"
                )
            }
            is Result.Error -> errorResponse(
                "Failed to search tasks: ${result.error}",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }

    // ========== EXPORT OPERATION ==========

    private suspend fun executeExport(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String
    ): JsonElement {
        logger.info("Executing export operation for $containerType")

        val id = extractEntityId(params, "id")

        return when (containerType) {
            "project" -> exportProject(context, id)
            "feature" -> exportFeature(context, id)
            "task" -> exportTask(context, id)
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    private suspend fun exportProject(context: ToolExecutionContext, id: UUID): JsonElement {
        return when (val result = context.projectRepository().getById(id)) {
            is Result.Success -> {
                val project = result.data
                val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.PROJECT, id)
                val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                val renderer = MarkdownRenderer()
                val markdown = renderer.renderProject(project, sections)

                successResponse(
                    buildJsonObject {
                        put("markdown", markdown)
                        put("id", id.toString())
                        put("type", "project")
                    },
                    "Project exported to markdown successfully"
                )
            }
            is Result.Error -> handleNotFoundError(result, "Project", id)
        }
    }

    private suspend fun exportFeature(context: ToolExecutionContext, id: UUID): JsonElement {
        return when (val result = context.featureRepository().getById(id)) {
            is Result.Success -> {
                val feature = result.data
                val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, id)
                val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                val renderer = MarkdownRenderer()
                val markdown = renderer.renderFeature(feature, sections)

                successResponse(
                    buildJsonObject {
                        put("markdown", markdown)
                        put("id", id.toString())
                        put("type", "feature")
                    },
                    "Feature exported to markdown successfully"
                )
            }
            is Result.Error -> handleNotFoundError(result, "Feature", id)
        }
    }

    private suspend fun exportTask(context: ToolExecutionContext, id: UUID): JsonElement {
        return when (val result = context.taskRepository().getById(id)) {
            is Result.Success -> {
                val task = result.data
                val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.TASK, id)
                val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                val renderer = MarkdownRenderer()
                val markdown = renderer.renderTask(task, sections)

                successResponse(
                    buildJsonObject {
                        put("markdown", markdown)
                        put("id", id.toString())
                        put("type", "task")
                    },
                    "Task exported to markdown successfully"
                )
            }
            is Result.Error -> handleNotFoundError(result, "Task", id)
        }
    }

    // ========== OVERVIEW OPERATION ==========

    private suspend fun executeOverview(
        params: JsonElement,
        context: ToolExecutionContext,
        containerType: String
    ): JsonElement {
        logger.info("Executing overview operation for $containerType")

        val summaryLength = optionalInt(params, "summaryLength") ?: 100

        return when (containerType) {
            "project" -> overviewProjects(context, summaryLength)
            "feature" -> overviewFeatures(context, summaryLength)
            "task" -> overviewTasks(context, summaryLength)
            else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
        }
    }

    private suspend fun overviewProjects(context: ToolExecutionContext, summaryLength: Int): JsonElement {
        val projectsResult = context.projectRepository().findAll(limit = 100)

        return when (projectsResult) {
            is Result.Success -> {
                val projects = projectsResult.data
                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            projects.forEach { project ->
                                add(buildProjectOverviewJson(project, summaryLength))
                            }
                        })
                        put("count", projects.size)
                    },
                    "${projects.size} project(s) retrieved"
                )
            }
            is Result.Error -> errorResponse(
                "Failed to retrieve projects: ${projectsResult.error}",
                ErrorCodes.DATABASE_ERROR,
                projectsResult.error.toString()
            )
        }
    }

    private suspend fun overviewFeatures(context: ToolExecutionContext, summaryLength: Int): JsonElement {
        val featuresResult = context.featureRepository().findAll(limit = 100)

        return when (featuresResult) {
            is Result.Success -> {
                val features = featuresResult.data
                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            features.forEach { feature ->
                                add(buildFeatureOverviewJson(feature, summaryLength))
                            }
                        })
                        put("count", features.size)
                    },
                    "${features.size} feature(s) retrieved"
                )
            }
            is Result.Error -> errorResponse(
                "Failed to retrieve features: ${featuresResult.error}",
                ErrorCodes.DATABASE_ERROR,
                featuresResult.error.toString()
            )
        }
    }

    private suspend fun overviewTasks(context: ToolExecutionContext, summaryLength: Int): JsonElement {
        val tasksResult = context.taskRepository().findAll(limit = 100)

        return when (tasksResult) {
            is Result.Success -> {
                val tasks = tasksResult.data
                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            tasks.forEach { task ->
                                add(buildTaskOverviewJson(task, summaryLength))
                            }
                        })
                        put("count", tasks.size)
                    },
                    "${tasks.size} task(s) retrieved"
                )
            }
            is Result.Error -> errorResponse(
                "Failed to retrieve tasks: ${tasksResult.error}",
                ErrorCodes.DATABASE_ERROR,
                tasksResult.error.toString()
            )
        }
    }

    // ========== JSON BUILDING HELPERS ==========

    private fun buildProjectJson(project: Project, sections: List<Section>, includeSections: Boolean): JsonObject {
        return buildJsonObject {
            put("id", project.id.toString())
            put("name", project.name)
            put("summary", project.summary)
            project.description?.let { put("description", it) }
            put("status", project.status.name.lowercase().replace('_', '-'))
            put("tags", JsonArray(project.tags.map { JsonPrimitive(it) }))
            put("createdAt", project.createdAt.toString())
            put("modifiedAt", project.modifiedAt.toString())

            if (includeSections && sections.isNotEmpty()) {
                put("sections", buildJsonArray {
                    sections.sortedBy { it.ordinal }.forEach { section ->
                        add(buildSectionJson(section))
                    }
                })
            }
        }
    }

    private fun buildFeatureJson(feature: Feature, sections: List<Section>, includeSections: Boolean): JsonObject {
        return buildJsonObject {
            put("id", feature.id.toString())
            put("name", feature.name)
            put("summary", feature.summary)
            feature.description?.let { put("description", it) }
            put("status", feature.status.name.lowercase().replace('_', '-'))
            put("priority", feature.priority.name.lowercase())
            feature.projectId?.let { put("projectId", it.toString()) }
            put("tags", JsonArray(feature.tags.map { JsonPrimitive(it) }))
            put("createdAt", feature.createdAt.toString())
            put("modifiedAt", feature.modifiedAt.toString())

            if (includeSections && sections.isNotEmpty()) {
                put("sections", buildJsonArray {
                    sections.sortedBy { it.ordinal }.forEach { section ->
                        add(buildSectionJson(section))
                    }
                })
            }
        }
    }

    private fun buildTaskJson(task: Task, sections: List<Section>, includeSections: Boolean): JsonObject {
        return buildJsonObject {
            put("id", task.id.toString())
            put("title", task.title)
            put("summary", task.summary)
            task.description?.let { put("description", it) }
            put("status", task.status.name.lowercase().replace('_', '-'))
            put("priority", task.priority.name.lowercase())
            put("complexity", task.complexity)
            task.projectId?.let { put("projectId", it.toString()) }
            task.featureId?.let { put("featureId", it.toString()) }
            put("tags", JsonArray(task.tags.map { JsonPrimitive(it) }))
            put("createdAt", task.createdAt.toString())
            put("modifiedAt", task.modifiedAt.toString())

            if (includeSections && sections.isNotEmpty()) {
                put("sections", buildJsonArray {
                    sections.sortedBy { it.ordinal }.forEach { section ->
                        add(buildSectionJson(section))
                    }
                })
            }
        }
    }

    private fun buildSectionJson(section: Section): JsonObject {
        return buildJsonObject {
            put("id", section.id.toString())
            put("title", section.title)
            put("content", section.content)
            put("contentFormat", section.contentFormat.name.lowercase())
            put("ordinal", section.ordinal)
            put("tags", JsonArray(section.tags.map { JsonPrimitive(it) }))
            put("usageDescription", section.usageDescription)
        }
    }

    private fun buildProjectOverviewJson(project: Project, summaryLength: Int): JsonObject {
        return buildJsonObject {
            put("id", project.id.toString())
            put("name", project.name)
            put("status", project.status.name.lowercase().replace('_', '-'))

            if (summaryLength > 0) {
                val summary = project.summary
                put("summary", if (summary.length > summaryLength) {
                    summary.take(summaryLength - 3) + "..."
                } else {
                    summary
                })
            }

            if (project.tags.isNotEmpty()) {
                put("tags", project.tags.joinToString(", "))
            }
        }
    }

    private fun buildFeatureOverviewJson(feature: Feature, summaryLength: Int): JsonObject {
        return buildJsonObject {
            put("id", feature.id.toString())
            put("name", feature.name)
            put("status", feature.status.name.lowercase().replace('_', '-'))
            put("priority", feature.priority.name.lowercase())
            feature.projectId?.let { put("projectId", it.toString()) }

            if (summaryLength > 0) {
                val summary = feature.summary
                put("summary", if (summary.length > summaryLength) {
                    summary.take(summaryLength - 3) + "..."
                } else {
                    summary
                })
            }

            if (feature.tags.isNotEmpty()) {
                put("tags", feature.tags.joinToString(", "))
            }
        }
    }

    private fun buildTaskOverviewJson(task: Task, summaryLength: Int): JsonObject {
        return buildJsonObject {
            put("id", task.id.toString())
            put("title", task.title)
            put("status", task.status.name.lowercase().replace('_', '-'))
            put("priority", task.priority.name.lowercase())
            put("complexity", task.complexity)
            task.featureId?.let { put("featureId", it.toString()) }

            if (summaryLength > 0) {
                val summary = task.summary
                put("summary", if (summary.length > summaryLength) {
                    summary.take(summaryLength - 3) + "..."
                } else {
                    summary
                })
            }

            if (task.tags.isNotEmpty()) {
                put("tags", task.tags.joinToString(", "))
            }
        }
    }

    // ========== HELPER METHODS ==========

    private fun handleNotFoundError(result: Result.Error, entityType: String, id: UUID): JsonElement {
        return if (result.error is RepositoryError.NotFound) {
            errorResponse(
                "$entityType not found",
                ErrorCodes.RESOURCE_NOT_FOUND,
                "No $entityType exists with ID $id"
            )
        } else {
            errorResponse(
                "Failed to retrieve $entityType",
                ErrorCodes.DATABASE_ERROR,
                result.error.toString()
            )
        }
    }

    // Status validation and parsing methods

    private fun isValidStatus(status: String, containerType: String): Boolean {
        return try {
            when (containerType) {
                "project" -> parseProjectStatus(status)
                "feature" -> parseFeatureStatus(status)
                "task" -> parseTaskStatus(status)
                else -> return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseProjectStatus(status: String): ProjectStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> ProjectStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> ProjectStatus.IN_DEVELOPMENT
            "completed" -> ProjectStatus.COMPLETED
            "archived" -> ProjectStatus.ARCHIVED
            else -> throw IllegalArgumentException("Invalid project status: $status")
        }
    }

    private fun parseFeatureStatus(status: String): FeatureStatus {
        return when (status.lowercase().replace('-', '_')) {
            "planning" -> FeatureStatus.PLANNING
            "in_development", "indevelopment", "in-development" -> FeatureStatus.IN_DEVELOPMENT
            "completed" -> FeatureStatus.COMPLETED
            "archived" -> FeatureStatus.ARCHIVED
            else -> throw IllegalArgumentException("Invalid feature status: $status")
        }
    }

    private fun parseTaskStatus(status: String): TaskStatus {
        return when (status.lowercase().replace('-', '_')) {
            "pending" -> TaskStatus.PENDING
            "in_progress", "inprogress", "in-progress" -> TaskStatus.IN_PROGRESS
            "completed" -> TaskStatus.COMPLETED
            "cancelled", "canceled" -> TaskStatus.CANCELLED
            "deferred" -> TaskStatus.DEFERRED
            else -> throw IllegalArgumentException("Invalid task status: $status")
        }
    }

    private fun isValidPriority(priority: String): Boolean {
        return try {
            parsePriority(priority)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parsePriority(priority: String): Priority {
        return when (priority.lowercase()) {
            "high" -> Priority.HIGH
            "medium", "med" -> Priority.MEDIUM
            "low" -> Priority.LOW
            else -> throw IllegalArgumentException("Invalid priority: $priority")
        }
    }
}
