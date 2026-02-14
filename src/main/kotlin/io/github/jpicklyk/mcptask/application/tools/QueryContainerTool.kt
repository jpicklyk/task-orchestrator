package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.rendering.MarkdownRenderer
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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

    private data class OverviewFilters(
        val rawStatus: String?,
        val rawPriority: String?,
        val tags: List<String>?
    ) {
        val isActive: Boolean get() = rawStatus != null || rawPriority != null || tags != null
    }

    override val toolAnnotations: ToolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val name: String = "query_container"

    override val title: String = "Query Container"

    override val outputSchema: ToolSchema = ToolSchema(
        buildJsonObject {
            put("type", "object")
            put("description", "Data payload shape varies by operation. See tool description for per-operation response details.")
        }
    )

    override fun shouldUseLocking(): Boolean = false // Read-only operations don't need locking

    override val description: String = """Unified read operations for containers (project, feature, task).

Operations: get, search, export, overview (global or scoped)

The overview operation supports both global (all entities) and scoped (specific entity with hierarchy) modes.
Scoped overview provides 85-90% token reduction vs get with sections, ideal for "show details" queries.

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | get, search, export, overview |
| containerType | enum | Yes | project, feature, task |
| id | UUID | Varies | Container ID (required for: get, export; optional for: overview) |
| query | string | No | Search text query (search only) |
| status | string | No | Filter by status (search & overview; supports multi-value: "pending,in-progress" or negation: "!completed") |
| priority | string | No | Filter by priority (search & overview; feature/task only, supports multi-value: "high,medium" or negation: "!low") |
| tags | string | No | Comma-separated tags filter (search & overview) |
| projectId | UUID | No | Filter by project (feature/task) |
| featureId | UUID | No | Filter by feature (task only) |
| limit | integer | No | Max results (default: 20) |
| includeSections | boolean | No | Include sections (get only, default: false) |
| summaryLength | integer | No | Summary truncation (overview only, 0-200) |

Overview Operation:
- Without id: Global overview (all containers of specified type)
  - Returns array of all entities with minimal fields
  - Useful for: "Show me all features", "List all projects"

- With id: Scoped overview (hierarchical view of specific container)
  - Project: Returns project metadata + standalone tasks array + features array + task counts
  - Feature: Returns feature metadata + tasks array + task counts
  - Task: Returns task metadata + dependencies (blocking/blockedBy)
  - Token efficient: No section content, minimal child fields
  - Useful for: "Show me details on Feature X", "What's the status of Project Y?"

Overview Filtering:
- status, priority, and tags parameters filter child collections in overview responses
- Scoped project overview: filters features[] and tasks[] arrays; parent taskCounts stay unfiltered
- Scoped feature overview: filters tasks[] array; parent taskCounts stay unfiltered
- Global overview: filters the items[] array
- When filters are active, response includes *Meta objects (e.g., featureMeta: {returned: 14, total: 35})
- Scoped task overview is not affected by filters (single entity + deps, no children)

Token Efficiency:
- Scoped overview provides 85-90% token reduction vs get with includeSections=true
- Example: Feature with 10 sections + 20 tasks: 18,500 → 1,200 tokens (91% reduction)
- Best for "show details" queries without needing full section content

Filter Syntax Examples:
- Single value: status="pending"
- Multi-value (OR): status="pending,in-progress" (matches pending OR in-progress)
- Negation: status="!completed" (matches anything EXCEPT completed)
- Multi-negation: status="!completed,!cancelled" (matches anything EXCEPT completed or cancelled)
- Priority filters: priority="high,medium" or priority="!low"

Search results return minimal data (89% token reduction):
- Tasks: id, title, status, priority, complexity, featureId (~30 tokens vs ~280 full object)
- Features: id, name, status, priority, projectId
- Projects: id, name, status

Get operations (feature/project) ALWAYS include taskCounts (99% token reduction):
- taskCounts.total: Total number of tasks
- taskCounts.byStatus: Status breakdown (e.g., {"completed": 25, "in-progress": 15, "pending": 10})
- This avoids fetching all tasks just to count statuses (14,400 → 100 tokens for 50 tasks)

Usage Examples:

# Global overview - all features
query_container(operation="overview", containerType="feature")
→ Returns array of all features with minimal fields

# Scoped overview - specific feature with tasks
query_container(operation="overview", containerType="feature", id="uuid")
→ Returns feature metadata + tasks array + task counts (no sections)

# Filtered overview - exclude completed features
query_container(operation="overview", containerType="project", id="uuid", status="!completed,!cancelled")
→ Returns only non-terminal features with featureMeta showing returned vs total counts

# Compare with get operation:
query_container(operation="get", containerType="feature", id="uuid", includeSections=true)
→ Returns feature + full section content (18k+ tokens)

query_container(operation="overview", containerType="feature", id="uuid")
→ Returns feature + task list (1.2k tokens, 91% reduction)

Usage: Consolidates get/search/export/overview for all container types (read-only).
Related: manage_container
Docs: task-orchestrator://docs/tools/query-container
"""

    override val parameterSchema: ToolSchema = ToolSchema(
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
                        "description" to JsonPrimitive("Container ID (required for: get, export; optional for: overview - enables scoping)"),
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
        // Validate status if present (supports multi-value and negation)
        optionalString(params, "status")?.let { statusParam ->
            if (!isValidStatusFilter(statusParam, containerType)) {
                throw ToolValidationException("Invalid status filter for $containerType: $statusParam")
            }
        }

        // Validate priority if present (feature/task only, supports multi-value and negation)
        if (containerType in listOf("feature", "task")) {
            optionalString(params, "priority")?.let { priorityParam ->
                if (!isValidPriorityFilter(priorityParam)) {
                    throw ToolValidationException("Invalid priority filter: $priorityParam")
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

    private fun parseOverviewFilters(params: JsonElement): OverviewFilters {
        val statusStr = optionalString(params, "status")
        val priorityStr = optionalString(params, "priority")
        val tagsStr = optionalString(params, "tags")
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        return OverviewFilters(statusStr, priorityStr, tags)
    }

    private fun matchesOverviewFilters(
        status: Any,
        priority: Priority?,
        tags: List<String>,
        filters: OverviewFilters,
        containerType: String
    ): Boolean {
        // Parse status filter for the child's actual type, not the parent's type
        filters.rawStatus?.let { rawStatus ->
            val statusFilter = parseStatusFilter(rawStatus, containerType)
            if (statusFilter != null) {
                @Suppress("UNCHECKED_CAST")
                val typedFilter = statusFilter as StatusFilter<Any>
                if (!typedFilter.matches(status)) return false
            }
        }

        // Check priority filter
        if (priority != null && filters.rawPriority != null) {
            val priorityFilter = parsePriorityFilter(filters.rawPriority)
            if (priorityFilter != null && !priorityFilter.matches(priority)) return false
        } else if (filters.rawPriority != null && priority == null) {
            // Entity has no priority (project) but filter requires it — exclude
            return false
        }

        // Check tags filter (ANY match — at least one tag must match)
        filters.tags?.let { filterTags ->
            if (tags.none { it in filterTags }) return false
        }

        return true
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

                // Always fetch task counts (very cheap - single query + groupBy)
                val taskCounts = buildTaskCounts(context, projectId = id)

                val projectJson = buildProjectJson(project, sections, includeSections)
                // Add taskCounts to response
                val jsonWithCounts = buildJsonObject {
                    projectJson.forEach { key, value -> put(key, value) }
                    put("taskCounts", taskCounts)
                }

                successResponse(
                    jsonWithCounts,
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

                // Always fetch task counts (very cheap - single query + groupBy)
                val taskCounts = buildTaskCounts(context, featureId = id)

                val featureJson = buildFeatureJson(feature, sections, includeSections)
                // Add taskCounts to response
                val jsonWithCounts = buildJsonObject {
                    featureJson.forEach { key, value -> put(key, value) }
                    put("taskCounts", taskCounts)
                }

                successResponse(
                    jsonWithCounts,
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

        // Parse multi-value status filter (projects don't have priority)
        @Suppress("UNCHECKED_CAST")
        val statusFilter = parseStatusFilter(statusStr, "project") as StatusFilter<ProjectStatus>?

        val tagsStr = optionalString(params, "tags")
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val limit = optionalInt(params, "limit") ?: 20

        val result = if (query != null || statusFilter != null || tags != null) {
            context.projectRepository().findByFilters(
                projectId = null,
                statusFilter = statusFilter,
                priorityFilter = null,
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
                                add(buildProjectSearchResult(project))  // Use minimal result builder
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
        val priorityStr = optionalString(params, "priority")

        // Parse multi-value filters
        @Suppress("UNCHECKED_CAST")
        val statusFilter = parseStatusFilter(statusStr, "feature") as StatusFilter<FeatureStatus>?
        val priorityFilter = parsePriorityFilter(priorityStr)

        val tagsStr = optionalString(params, "tags")
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val limit = optionalInt(params, "limit") ?: 20

        val result = if (query != null || statusFilter != null || priorityFilter != null || tags != null || projectId != null) {
            context.featureRepository().findByFilters(
                projectId = projectId,
                statusFilter = statusFilter,
                priorityFilter = priorityFilter,
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
                                add(buildFeatureSearchResult(feature))  // Use minimal result builder
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
        val priorityStr = optionalString(params, "priority")

        // Parse multi-value filters
        @Suppress("UNCHECKED_CAST")
        val statusFilter = parseStatusFilter(statusStr, "task") as StatusFilter<TaskStatus>?
        val priorityFilter = parsePriorityFilter(priorityStr)

        val tagsStr = optionalString(params, "tags")
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val projectId = optionalString(params, "projectId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val featureId = optionalString(params, "featureId")?.let {
            if (it.isEmpty()) null else UUID.fromString(it)
        }
        val limit = optionalInt(params, "limit") ?: 20

        // Call repository with StatusFilter objects
        val result = if (featureId != null) {
            context.taskRepository().findByFeatureAndFilters(
                featureId = featureId,
                statusFilter = statusFilter,
                priorityFilter = priorityFilter,
                tags = tags,
                textQuery = query,
                limit = limit
            )
        } else if (query != null || statusFilter != null || priorityFilter != null || tags != null || projectId != null) {
            context.taskRepository().findByFilters(
                projectId = projectId,
                statusFilter = statusFilter,
                priorityFilter = priorityFilter,
                tags = tags,
                textQuery = query,
                limit = limit
            )
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
                                add(buildTaskSearchResult(task))  // Use minimal result builder
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
        val summaryLength = optionalInt(params, "summaryLength") ?: 100
        val filters = parseOverviewFilters(params)
        val idString = optionalString(params, "id")

        // Check if scoped overview (id provided) or global overview (no id)
        return if (idString != null && idString.isNotEmpty()) {
            // Scoped overview - show specific container with hierarchy
            logger.info("Executing scoped overview operation for $containerType with id=$idString")

            val id = try {
                UUID.fromString(idString)
            } catch (e: IllegalArgumentException) {
                return errorResponse(
                    "Invalid $containerType ID format. Must be a valid UUID.",
                    ErrorCodes.VALIDATION_ERROR
                )
            }

            when (containerType) {
                "project" -> scopedProjectOverview(context, id, summaryLength, filters)
                "feature" -> scopedFeatureOverview(context, id, summaryLength, filters)
                "task" -> scopedTaskOverview(context, id, summaryLength)
                else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
            }
        } else {
            // Global overview - show all containers of specified type
            logger.info("Executing global overview operation for $containerType")

            when (containerType) {
                "project" -> overviewProjects(context, summaryLength, filters)
                "feature" -> overviewFeatures(context, summaryLength, filters)
                "task" -> overviewTasks(context, summaryLength, filters)
                else -> errorResponse("Unsupported container type: $containerType", ErrorCodes.VALIDATION_ERROR)
            }
        }
    }

    private suspend fun overviewProjects(context: ToolExecutionContext, summaryLength: Int, filters: OverviewFilters): JsonElement {
        val projectsResult = context.projectRepository().findAll(limit = 100)

        return when (projectsResult) {
            is Result.Success -> {
                val allProjects = projectsResult.data
                val projects = if (filters.isActive) {
                    allProjects.filter { project ->
                        matchesOverviewFilters(project.status, null, project.tags, filters, "project")
                    }
                } else allProjects

                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            projects.forEach { project ->
                                add(buildProjectOverviewJson(project, summaryLength))
                            }
                        })
                        put("count", projects.size)
                        if (filters.isActive) {
                            put("meta", buildJsonObject {
                                put("returned", projects.size)
                                put("total", allProjects.size)
                            })
                        }
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

    private suspend fun overviewFeatures(context: ToolExecutionContext, summaryLength: Int, filters: OverviewFilters): JsonElement {
        val featuresResult = context.featureRepository().findAll(limit = 100)

        return when (featuresResult) {
            is Result.Success -> {
                val allFeatures = featuresResult.data
                val features = if (filters.isActive) {
                    allFeatures.filter { feature ->
                        matchesOverviewFilters(feature.status, feature.priority, feature.tags, filters, "feature")
                    }
                } else allFeatures

                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            features.forEach { feature ->
                                add(buildFeatureOverviewJson(feature, summaryLength))
                            }
                        })
                        put("count", features.size)
                        if (filters.isActive) {
                            put("meta", buildJsonObject {
                                put("returned", features.size)
                                put("total", allFeatures.size)
                            })
                        }
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

    private suspend fun overviewTasks(context: ToolExecutionContext, summaryLength: Int, filters: OverviewFilters): JsonElement {
        val tasksResult = context.taskRepository().findAll(limit = 100)

        return when (tasksResult) {
            is Result.Success -> {
                val allTasks = tasksResult.data
                val tasks = if (filters.isActive) {
                    allTasks.filter { task ->
                        matchesOverviewFilters(task.status, task.priority, task.tags, filters, "task")
                    }
                } else allTasks

                successResponse(
                    buildJsonObject {
                        put("items", buildJsonArray {
                            tasks.forEach { task ->
                                add(buildTaskOverviewJson(task, summaryLength))
                            }
                        })
                        put("count", tasks.size)
                        if (filters.isActive) {
                            put("meta", buildJsonObject {
                                put("returned", tasks.size)
                                put("total", allTasks.size)
                            })
                        }
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

    // ========== SCOPED OVERVIEW METHODS ==========

    /**
     * Returns scoped overview for a specific project.
     * Includes project metadata, standalone tasks (no feature), list of features, and task counts.
     * No section content included (token efficient).
     *
     * @param context Tool execution context with repository access
     * @param id The project ID
     * @param summaryLength Max length for summary truncation (0 = no summary)
     * @param filters Optional filters for child entities (features and standalone tasks)
     * @return JsonElement with project overview data
     */
    private suspend fun scopedProjectOverview(
        context: ToolExecutionContext,
        id: UUID,
        summaryLength: Int,
        filters: OverviewFilters
    ): JsonElement {
        // Fetch project by ID
        val projectResult = context.projectRepository().getById(id)
        return when (projectResult) {
            is Result.Error -> handleNotFoundError(projectResult, "Project", id)
            is Result.Success -> {
                val project = projectResult.data

                // Fetch ALL features for total count, then filtered set for display
                val allFeaturesResult = context.featureRepository().findByProject(id, limit = 100)
                val allFeatures = when (allFeaturesResult) {
                    is Result.Success -> allFeaturesResult.data
                    is Result.Error -> emptyList()
                }

                val features = if (filters.isActive) {
                    allFeatures.filter { feature ->
                        matchesOverviewFilters(feature.status, feature.priority, feature.tags, filters, "feature")
                    }
                } else allFeatures

                // Build task counts UNFILTERED (true project health)
                val taskCountsResult = buildTaskCountsWithTasks(context, projectId = id)
                val taskCounts = taskCountsResult.counts

                // Filter standalone tasks for display
                val allStandaloneTasks = taskCountsResult.tasks.filter { it.featureId == null }.take(100)
                val standaloneTasks = if (filters.isActive) {
                    allStandaloneTasks.filter { task ->
                        matchesOverviewFilters(task.status, task.priority, task.tags, filters, "task")
                    }
                } else allStandaloneTasks

                successResponse(
                    buildJsonObject {
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

                        put("taskCounts", taskCounts)

                        put("tasks", buildJsonArray {
                            standaloneTasks.forEach { task ->
                                add(buildTaskSearchResult(task))
                            }
                        })

                        if (filters.isActive) {
                            put("taskMeta", buildJsonObject {
                                put("returned", standaloneTasks.size)
                                put("total", allStandaloneTasks.size)
                            })
                        }

                        put("features", buildJsonArray {
                            features.forEach { feature ->
                                // Merge minimal feature fields with per-feature task counts
                                val featureBase = buildFeatureSearchResult(feature)
                                val featureTaskCounts = buildTaskCounts(context, featureId = feature.id)
                                add(buildJsonObject {
                                    featureBase.forEach { (key, value) -> put(key, value) }
                                    put("taskCounts", featureTaskCounts)
                                })
                            }
                        })

                        if (filters.isActive) {
                            put("featureMeta", buildJsonObject {
                                put("returned", features.size)
                                put("total", allFeatures.size)
                            })
                        }
                    },
                    "Project overview retrieved"
                )
            }
        }
    }

    /**
     * Returns scoped overview for a specific feature.
     * Includes feature metadata, list of tasks, and task counts.
     * No section content included (token efficient).
     *
     * @param context Tool execution context with repository access
     * @param id The feature ID
     * @param summaryLength Max length for summary truncation (0 = no summary)
     * @param filters Optional filters for child tasks
     * @return JsonElement with feature overview data
     */
    private suspend fun scopedFeatureOverview(
        context: ToolExecutionContext,
        id: UUID,
        summaryLength: Int,
        filters: OverviewFilters
    ): JsonElement {
        // Fetch feature by ID
        val featureResult = context.featureRepository().getById(id)
        return when (featureResult) {
            is Result.Error -> handleNotFoundError(featureResult, "Feature", id)
            is Result.Success -> {
                val feature = featureResult.data

                // Fetch ALL tasks (for unfiltered counts)
                val tasksResult = context.taskRepository().findByFeature(id, limit = 100)
                val allTasks = when (tasksResult) {
                    is Result.Success -> tasksResult.data
                    is Result.Error -> emptyList()
                }

                val tasks = if (filters.isActive) {
                    allTasks.filter { task ->
                        matchesOverviewFilters(task.status, task.priority, task.tags, filters, "task")
                    }
                } else allTasks

                // Build task counts UNFILTERED
                val taskCounts = buildTaskCounts(context, featureId = id)

                successResponse(
                    buildJsonObject {
                        put("id", feature.id.toString())
                        put("name", feature.name)
                        put("status", feature.status.name.lowercase().replace('_', '-'))
                        put("priority", feature.priority.name.lowercase())

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

                        feature.projectId?.let { put("projectId", it.toString()) }

                        put("taskCounts", taskCounts)

                        put("tasks", buildJsonArray {
                            tasks.forEach { task ->
                                add(buildTaskSearchResult(task))
                            }
                        })

                        if (filters.isActive) {
                            put("taskMeta", buildJsonObject {
                                put("returned", tasks.size)
                                put("total", allTasks.size)
                            })
                        }
                    },
                    "Feature overview retrieved"
                )
            }
        }
    }

    /**
     * Returns scoped overview for a specific task.
     * Includes task metadata and dependencies (blocking and blocked-by).
     * No section content included (token efficient).
     *
     * @param context Tool execution context with repository access
     * @param id The task ID
     * @param summaryLength Max length for summary truncation (0 = no summary)
     * @return JsonElement with task overview data
     */
    private suspend fun scopedTaskOverview(
        context: ToolExecutionContext,
        id: UUID,
        summaryLength: Int
    ): JsonElement {
        // Fetch task by ID
        val taskResult = context.taskRepository().getById(id)
        return when (taskResult) {
            is Result.Error -> handleNotFoundError(taskResult, "Task", id)
            is Result.Success -> {
                val task = taskResult.data

                // Fetch dependencies for this task
                val blockingDeps = context.dependencyRepository().findByFromTaskId(id)
                val blockedByDeps = context.dependencyRepository().findByToTaskId(id)

                // Build maps of dependency task IDs to fetch their data
                val allDepTaskIds = (blockingDeps.map { it.toTaskId } + blockedByDeps.map { it.fromTaskId }).toSet()

                // Fetch all dependency tasks
                val depTasks = mutableMapOf<UUID, Task>()
                for (depTaskId in allDepTaskIds) {
                    val depTaskResult = context.taskRepository().getById(depTaskId)
                    if (depTaskResult is Result.Success) {
                        depTasks[depTaskId] = depTaskResult.data
                    }
                }

                successResponse(
                    buildJsonObject {
                        put("id", task.id.toString())
                        put("title", task.title)
                        put("status", task.status.name.lowercase().replace('_', '-'))
                        put("priority", task.priority.name.lowercase())
                        put("complexity", task.complexity)

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

                        task.featureId?.let { put("featureId", it.toString()) }
                        task.projectId?.let { put("projectId", it.toString()) }

                        // Build dependencies object
                        put("dependencies", buildJsonObject {
                            put("blocking", buildJsonArray {
                                blockingDeps.forEach { dep ->
                                    val depTask = depTasks[dep.toTaskId]
                                    if (depTask != null) {
                                        add(buildJsonObject {
                                            put("id", depTask.id.toString())
                                            put("title", depTask.title)
                                            put("status", depTask.status.name.lowercase().replace('_', '-'))
                                        })
                                    }
                                }
                            })
                            put("blockedBy", buildJsonArray {
                                blockedByDeps.forEach { dep ->
                                    val depTask = depTasks[dep.fromTaskId]
                                    if (depTask != null) {
                                        add(buildJsonObject {
                                            put("id", depTask.id.toString())
                                            put("title", depTask.title)
                                            put("status", depTask.status.name.lowercase().replace('_', '-'))
                                        })
                                    }
                                }
                            })
                        })
                    },
                    "Task overview retrieved"
                )
            }
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
            put("requiresVerification", feature.requiresVerification)
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
            put("requiresVerification", task.requiresVerification)
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
            put("requiresVerification", feature.requiresVerification)

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
            put("requiresVerification", task.requiresVerification)

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

    /**
     * Data class holding task counts and the raw task list.
     * Used internally to avoid redundant repository calls when both counts and task data are needed.
     */
    private data class TaskCountsResult(val counts: JsonObject, val tasks: List<Task>)

    /**
     * Builds task counts for a feature or project.
     * Fetches all tasks and groups by status to provide:
     * - total: Total number of tasks
     * - byStatus: Map of status -> count
     *
     * This is very cheap (single query + in-memory groupBy) and provides
     * 99% token reduction vs fetching all tasks (14,400 → 100 tokens for 50 tasks).
     *
     * When a StatusProgressionService is available via the context, each byStatus entry
     * includes a role annotation and a byRole aggregate is added:
     * ```json
     * {
     *   "total": 8,
     *   "byStatus": {
     *     "completed": { "count": 5, "role": "terminal" },
     *     "in-progress": { "count": 3, "role": "work" }
     *   },
     *   "byRole": { "queue": 0, "work": 3, "review": 0, "blocked": 0, "terminal": 5 }
     * }
     * ```
     *
     * Without the service, byStatus entries contain `{ "count": N }` (no role) and byRole is omitted.
     *
     * @param context Tool execution context
     * @param featureId Optional feature ID to count tasks for
     * @param projectId Optional project ID to count tasks for
     * @return JsonObject with total, byStatus (role-annotated), and optional byRole counts
     */
    private suspend fun buildTaskCounts(
        context: ToolExecutionContext,
        featureId: UUID? = null,
        projectId: UUID? = null
    ): JsonObject = buildTaskCountsWithTasks(context, featureId, projectId).counts

    /**
     * Builds task counts and returns both the counts JSON and the raw task list.
     * This is used when the caller needs both the counts and the tasks to avoid redundant repository calls.
     *
     * @param context Tool execution context
     * @param featureId Optional feature ID to count tasks for
     * @param projectId Optional project ID to count tasks for
     * @return TaskCountsResult with counts JsonObject and tasks List
     */
    private suspend fun buildTaskCountsWithTasks(
        context: ToolExecutionContext,
        featureId: UUID? = null,
        projectId: UUID? = null
    ): TaskCountsResult {
        val progressionService = context.statusProgressionService()

        val tasksResult = if (featureId != null) {
            context.taskRepository().findByFeature(featureId)
        } else if (projectId != null) {
            // Use findByFilters to include tasks through feature relationships
            context.taskRepository().findByFilters(
                projectId = projectId,
                statusFilter = null,
                priorityFilter = null,
                tags = null,
                textQuery = null,
                limit = 1000  // High limit to get all tasks for counting
            )
        } else {
            return TaskCountsResult(
                counts = buildJsonObject {
                    put("total", 0)
                    put("byStatus", buildJsonObject {})
                    if (progressionService != null) {
                        put("byRole", buildJsonObject {
                            put("queue", 0); put("work", 0); put("review", 0)
                            put("blocked", 0); put("terminal", 0)
                        })
                    }
                },
                tasks = emptyList()
            )
        }

        return when (tasksResult) {
            is Result.Success -> {
                val tasks = tasksResult.data
                val statusGroups = tasks.groupBy { it.status }

                // Pre-compute role counts if service is available
                val roleCounts = if (progressionService != null) {
                    mutableMapOf("queue" to 0, "work" to 0, "review" to 0, "blocked" to 0, "terminal" to 0)
                } else null

                TaskCountsResult(
                    counts = buildJsonObject {
                        put("total", tasks.size)
                        put("byStatus", buildJsonObject {
                            statusGroups.forEach { (status, taskList) ->
                                val statusName = status.name.lowercase().replace('_', '-')
                                val count = taskList.size
                                val role = progressionService?.getRoleForStatus(statusName, "task")

                                if (role != null && roleCounts != null) {
                                    roleCounts[role] = (roleCounts[role] ?: 0) + count
                                }

                                put(statusName, buildJsonObject {
                                    put("count", count)
                                    if (role != null) {
                                        put("role", role)
                                    }
                                })
                            }
                        })
                        if (roleCounts != null) {
                            put("byRole", buildJsonObject {
                                roleCounts.forEach { (role, count) -> put(role, count) }
                            })
                        }
                    },
                    tasks = tasks
                )
            }
            is Result.Error -> {
                TaskCountsResult(
                    counts = buildJsonObject {
                        put("total", 0)
                        put("byStatus", buildJsonObject {})
                        if (progressionService != null) {
                            put("byRole", buildJsonObject {
                                put("queue", 0); put("work", 0); put("review", 0)
                                put("blocked", 0); put("terminal", 0)
                            })
                        }
                    },
                    tasks = emptyList()
                )
            }
        }
    }

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

    // ========== FILTER PARSING HELPERS ==========

    /**
     * Parses a status filter string into a StatusFilter object.
     * Supports comma-separated values with optional ! prefix for negation.
     *
     * Examples:
     * - "pending" → StatusFilter(include=[PENDING], exclude=[])
     * - "pending,in-progress" → StatusFilter(include=[PENDING, IN_PROGRESS], exclude=[])
     * - "!completed" → StatusFilter(include=[], exclude=[COMPLETED])
     * - "!completed,!cancelled" → StatusFilter(include=[], exclude=[COMPLETED, CANCELLED])
     *
     * @param statusParam The status filter string (e.g., "pending,in-progress" or "!completed")
     * @param containerType The container type ("task", "feature", or "project")
     * @return StatusFilter object or null if statusParam is null/blank
     */
    private fun parseStatusFilter(statusParam: String?, containerType: String): StatusFilter<*>? {
        if (statusParam.isNullOrBlank()) return null

        val parts = statusParam.split(",").map { it.trim() }
        val include = mutableListOf<Any>()
        val exclude = mutableListOf<Any>()

        parts.forEach { part ->
            if (part.startsWith("!")) {
                // Negation: remove ! and parse
                val status = parseStatus(part.substring(1), containerType)
                exclude.add(status)
            } else {
                val status = parseStatus(part, containerType)
                include.add(status)
            }
        }

        return when (containerType) {
            "task" -> StatusFilter(
                include = include.filterIsInstance<TaskStatus>(),
                exclude = exclude.filterIsInstance<TaskStatus>()
            )
            "feature" -> StatusFilter(
                include = include.filterIsInstance<FeatureStatus>(),
                exclude = exclude.filterIsInstance<FeatureStatus>()
            )
            "project" -> StatusFilter(
                include = include.filterIsInstance<ProjectStatus>(),
                exclude = exclude.filterIsInstance<ProjectStatus>()
            )
            else -> null
        }
    }

    /**
     * Parses a priority filter string into a StatusFilter<Priority> object.
     * Supports comma-separated values with optional ! prefix for negation.
     *
     * Examples:
     * - "high" → StatusFilter(include=[HIGH], exclude=[])
     * - "high,medium" → StatusFilter(include=[HIGH, MEDIUM], exclude=[])
     * - "!low" → StatusFilter(include=[], exclude=[LOW])
     *
     * @param priorityParam The priority filter string (e.g., "high,medium" or "!low")
     * @return StatusFilter<Priority> object or null if priorityParam is null/blank
     */
    private fun parsePriorityFilter(priorityParam: String?): StatusFilter<Priority>? {
        if (priorityParam.isNullOrBlank()) return null

        val parts = priorityParam.split(",").map { it.trim() }
        val include = mutableListOf<Priority>()
        val exclude = mutableListOf<Priority>()

        parts.forEach { part ->
            if (part.startsWith("!")) {
                exclude.add(parsePriority(part.substring(1)))
            } else {
                include.add(parsePriority(part))
            }
        }

        return StatusFilter(include, exclude)
    }

    /**
     * Parses a single status value based on container type.
     *
     * @param value The status string to parse
     * @param containerType The container type ("task", "feature", or "project")
     * @return The parsed status enum value
     * @throws IllegalArgumentException if the status value is invalid
     */
    private fun parseStatus(value: String, containerType: String): Any {
        return when (containerType) {
            "task" -> parseTaskStatus(value)
            "feature" -> parseFeatureStatus(value)
            "project" -> parseProjectStatus(value)
            else -> throw IllegalArgumentException("Unknown container type: $containerType")
        }
    }

    // ========== MINIMAL RESULT BUILDERS ==========

    /**
     * Builds a minimal task result for search operations (89% token reduction).
     * Only includes essential fields: id, title, status, priority, complexity, featureId.
     * Excludes: summary, description, tags, timestamps, projectId.
     *
     * @param task The task entity
     * @return Minimal JSON representation (~30 tokens vs ~280 tokens for full object)
     */
    private fun buildTaskSearchResult(task: Task): JsonObject {
        return buildJsonObject {
            put("id", task.id.toString())
            put("title", task.title)
            put("status", task.status.name.lowercase().replace('_', '-'))
            put("priority", task.priority.name.lowercase())
            put("complexity", task.complexity)
            task.featureId?.let { put("featureId", it.toString()) }
            put("requiresVerification", task.requiresVerification)
        }
    }

    /**
     * Builds a minimal feature result for search operations.
     * Only includes: id, name, status, priority, projectId.
     * Excludes: summary, description, tags, timestamps.
     *
     * @param feature The feature entity
     * @return Minimal JSON representation
     */
    private fun buildFeatureSearchResult(feature: Feature): JsonObject {
        return buildJsonObject {
            put("id", feature.id.toString())
            put("name", feature.name)
            put("status", feature.status.name.lowercase().replace('_', '-'))
            put("priority", feature.priority.name.lowercase())
            feature.projectId?.let { put("projectId", it.toString()) }
            put("requiresVerification", feature.requiresVerification)
        }
    }

    /**
     * Builds a minimal project result for search operations.
     * Only includes: id, name, status.
     * Excludes: summary, description, tags, timestamps.
     *
     * @param project The project entity
     * @return Minimal JSON representation
     */
    private fun buildProjectSearchResult(project: Project): JsonObject {
        return buildJsonObject {
            put("id", project.id.toString())
            put("name", project.name)
            put("status", project.status.name.lowercase().replace('_', '-'))
        }
    }

    // Status validation and parsing methods

    /**
     * Validates a status filter string supporting multi-value and negation.
     * Accepts formats: "status", "status1,status2", "!status", "!status1,!status2"
     */
    private fun isValidStatusFilter(statusParam: String, containerType: String): Boolean {
        return try {
            val parts = statusParam.split(",").map { it.trim() }
            parts.all { part ->
                val statusValue = if (part.startsWith("!")) part.substring(1) else part
                isValidStatus(statusValue, containerType)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates a single status value for the given container type.
     */
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

    /**
     * Validates a priority filter string supporting multi-value and negation.
     * Accepts formats: "high", "high,medium", "!low", "!low,!medium"
     */
    private fun isValidPriorityFilter(priorityParam: String): Boolean {
        return try {
            val parts = priorityParam.split(",").map { it.trim() }
            parts.all { part ->
                val priorityValue = if (part.startsWith("!")) part.substring(1) else part
                isValidPriority(priorityValue)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses a project status string using the enum's fromString() method.
     * This delegates to ProjectStatus.fromString(), which automatically supports
     * all current and future status values defined in the enum.
     */
    private fun parseProjectStatus(status: String): ProjectStatus =
        ProjectStatus.fromString(status) ?: throw IllegalArgumentException("Invalid project status: $status")

    /**
     * Parses a feature status string using the enum's fromString() method.
     * This delegates to FeatureStatus.fromString(), which automatically supports
     * all current and future status values defined in the enum.
     */
    private fun parseFeatureStatus(status: String): FeatureStatus =
        FeatureStatus.fromString(status) ?: throw IllegalArgumentException("Invalid feature status: $status")

    /**
     * Parses a task status string using the enum's fromString() method.
     * This delegates to TaskStatus.fromString(), which automatically supports
     * all current and future status values defined in the enum.
     */
    private fun parseTaskStatus(status: String): TaskStatus =
        TaskStatus.fromString(status) ?: throw IllegalArgumentException("Invalid task status: $status")

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

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return super.userSummary(params, result, true)
        val p = params as? JsonObject ?: return "Query completed"
        val operation = p["operation"]?.jsonPrimitive?.content ?: "unknown"
        val data = (result as? JsonObject)?.get("data")?.jsonObject
        return when (operation) {
            "get" -> {
                val name = data?.get("name")?.jsonPrimitive?.content
                    ?: data?.get("title")?.jsonPrimitive?.content ?: ""
                "Retrieved '$name'"
            }
            "search" -> {
                val count = data?.get("count")?.jsonPrimitive?.content ?: "0"
                "Found $count result(s)"
            }
            "overview" -> {
                val name = data?.get("name")?.jsonPrimitive?.content
                if (name != null) {
                    val taskCounts = data?.get("taskCounts")?.jsonObject
                    val total = taskCounts?.get("total")?.jsonPrimitive?.content ?: "0"
                    "$name — $total tasks"
                } else {
                    val count = data?.get("count")?.jsonPrimitive?.content ?: "0"
                    "$count item(s) retrieved"
                }
            }
            "export" -> "Exported to markdown"
            else -> "Query completed"
        }
    }
}
