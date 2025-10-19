package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.StatusFilter
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.*

/**
 * Unified MCP tool for task query operations. Consolidates 4 separate tools:
 * search_tasks, get_blocked_tasks, get_next_task, bulk_update_tasks
 *
 * Reduces token overhead by ~5k tokens vs separate tools while preserving all functionality.
 */
class QueryTasksTool : SimpleLockAwareToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "query_tasks"

    override val title: String = "Query Tasks"

    override val description: String = """Multi-purpose task query tool. Consolidates search, blocked tasks, next task, and bulk update operations.

        Query Types:
        1. "search" - Find tasks by filters (status, priority, featureId, projectId, tag, text query)
        2. "blocked" - Identify tasks blocked by incomplete dependencies
        3. "next" - Get next task recommendation (priority + complexity based)
        4. "bulkUpdate" - Update multiple tasks atomically

        Parameters by Type:
        - search: query, status, priority, featureId, projectId, tag, limit, offset, sortBy, sortDirection
        - blocked: projectId, featureId, includeTaskDetails
        - next: limit, projectId, featureId, includeDetails
        - bulkUpdate: tasks (array of {id, ...fields to update})

        Related: get_task, update_task, create_task
        Docs: task-orchestrator://docs/tools/query-tasks
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "queryType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of query operation to perform"),
                        "enum" to JsonArray(listOf("search", "blocked", "next", "bulkUpdate").map { JsonPrimitive(it) })
                    )
                ),
                // Search parameters
                "query" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("[search] Text to search in task titles and descriptions")
                    )
                ),
                "status" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("[search] Filter by status"),
                        "enum" to JsonArray(listOf("pending", "in-progress", "completed", "cancelled", "deferred").map { JsonPrimitive(it) })
                    )
                ),
                "priority" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("[search] Filter by priority"),
                        "enum" to JsonArray(listOf("high", "medium", "low").map { JsonPrimitive(it) })
                    )
                ),
                "featureId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("[search/blocked/next] Filter by feature UUID"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("[search/blocked/next] Filter by project UUID"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "tag" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("[search] Filter by tag")
                    )
                ),
                "limit" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("[search/next] Max results (search: 1-100, default 20; next: 1-20, default 1)"),
                        "minimum" to JsonPrimitive(1)
                    )
                ),
                "offset" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("[search] Results to skip for pagination"),
                        "minimum" to JsonPrimitive(0),
                        "default" to JsonPrimitive(0)
                    )
                ),
                "sortBy" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("[search] Sort field"),
                        "enum" to JsonArray(listOf("createdAt", "modifiedAt", "priority", "status", "complexity").map { JsonPrimitive(it) })
                    )
                ),
                "sortDirection" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("[search] Sort direction"),
                        "enum" to JsonArray(listOf("asc", "desc").map { JsonPrimitive(it) })
                    )
                ),
                // Blocked/Next parameters
                "includeTaskDetails" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("[blocked] Include full task metadata"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                "includeDetails" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("[next] Include summary, tags, featureId"),
                        "default" to JsonPrimitive(false)
                    )
                ),
                // BulkUpdate parameters
                "tasks" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("[bulkUpdate] Array of task updates (max 100)"),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(
                                    mapOf(
                                        "id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "description" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "status" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "priority" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "complexity" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                        "featureId" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        "tags" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "required" to JsonArray(listOf(JsonPrimitive("id")))
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("queryType")
    )

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

    override fun validateParams(params: JsonElement) {
        val queryType = requireString(params, "queryType")

        if (queryType !in setOf("search", "blocked", "next", "bulkUpdate")) {
            throw ToolValidationException("Invalid queryType: $queryType. Must be one of: search, blocked, next, bulkUpdate")
        }

        when (queryType) {
            "search" -> validateSearchParams(params)
            "blocked" -> validateBlockedParams(params)
            "next" -> validateNextParams(params)
            "bulkUpdate" -> validateBulkUpdateParams(params)
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val queryType = requireString(params, "queryType")

        logger.info("Executing query_tasks tool with queryType=$queryType")

        return when (queryType) {
            "search" -> executeSearch(params, context)
            "blocked" -> executeBlocked(params, context)
            "next" -> executeNext(params, context)
            "bulkUpdate" -> executeBulkUpdate(params, context)
            else -> errorResponse(
                message = "Invalid queryType: $queryType",
                code = ErrorCodes.VALIDATION_ERROR
            )
        }
    }

    // ========== VALIDATION METHODS ==========

    private fun validateSearchParams(params: JsonElement) {
        optionalString(params, "status")?.let { status ->
            if (!isValidStatus(status)) {
                throw ToolValidationException("Invalid status: $status")
            }
        }

        optionalString(params, "priority")?.let { priority ->
            if (!isValidPriority(priority)) {
                throw ToolValidationException("Invalid priority: $priority")
            }
        }

        optionalString(params, "featureId")?.let {
            try { UUID.fromString(it) }
            catch (_: IllegalArgumentException) { throw ToolValidationException("Invalid featureId format") }
        }

        optionalString(params, "projectId")?.let {
            try { UUID.fromString(it) }
            catch (_: IllegalArgumentException) { throw ToolValidationException("Invalid projectId format") }
        }

        optionalInt(params, "limit")?.let {
            if (it < 1 || it > 100) throw ToolValidationException("Limit must be between 1 and 100")
        }

        optionalInt(params, "offset")?.let {
            if (it < 0) throw ToolValidationException("Offset must be non-negative")
        }

        optionalString(params, "sortBy")?.let {
            if (it !in setOf("createdAt", "modifiedAt", "priority", "status", "complexity")) {
                throw ToolValidationException("Invalid sortBy: $it")
            }
        }

        optionalString(params, "sortDirection")?.let {
            if (it !in setOf("asc", "desc")) {
                throw ToolValidationException("Invalid sortDirection: $it")
            }
        }
    }

    private fun validateBlockedParams(params: JsonElement) {
        optionalString(params, "projectId")?.let {
            try { UUID.fromString(it) }
            catch (_: IllegalArgumentException) { throw ToolValidationException("Invalid projectId format") }
        }

        optionalString(params, "featureId")?.let {
            try { UUID.fromString(it) }
            catch (_: IllegalArgumentException) { throw ToolValidationException("Invalid featureId format") }
        }
    }

    private fun validateNextParams(params: JsonElement) {
        optionalString(params, "projectId")?.let {
            try { UUID.fromString(it) }
            catch (_: IllegalArgumentException) { throw ToolValidationException("Invalid projectId format") }
        }

        optionalString(params, "featureId")?.let {
            try { UUID.fromString(it) }
            catch (_: IllegalArgumentException) { throw ToolValidationException("Invalid featureId format") }
        }

        optionalInt(params, "limit")?.let {
            if (it < 1 || it > 20) throw ToolValidationException("Limit must be between 1 and 20")
        }
    }

    private fun validateBulkUpdateParams(params: JsonElement) {
        val tasksArray = params.jsonObject["tasks"]
            ?: throw ToolValidationException("Missing required parameter: tasks")

        if (tasksArray !is JsonArray) {
            throw ToolValidationException("Parameter 'tasks' must be an array")
        }

        if (tasksArray.isEmpty()) {
            throw ToolValidationException("At least one task must be provided")
        }

        if (tasksArray.size > 100) {
            throw ToolValidationException("Maximum 100 tasks allowed (got ${tasksArray.size})")
        }

        tasksArray.forEachIndexed { index, taskElement ->
            if (taskElement !is JsonObject) {
                throw ToolValidationException("Task at index $index must be an object")
            }

            val taskObj = taskElement.jsonObject

            if (!taskObj.containsKey("id")) {
                throw ToolValidationException("Task at index $index missing required field: id")
            }

            try {
                UUID.fromString(taskObj["id"]?.jsonPrimitive?.content)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid id at index $index")
            }

            val updateFields = listOf("title", "description", "summary", "status", "priority", "complexity", "featureId", "tags")
            if (updateFields.none { taskObj.containsKey(it) }) {
                throw ToolValidationException("Task at index $index has no fields to update")
            }

            taskObj["status"]?.jsonPrimitive?.content?.let {
                if (!isValidStatus(it)) throw ToolValidationException("Invalid status at index $index: $it")
            }

            taskObj["priority"]?.jsonPrimitive?.content?.let {
                if (!isValidPriority(it)) throw ToolValidationException("Invalid priority at index $index: $it")
            }

            taskObj["complexity"]?.let {
                val complexity = if (it is JsonPrimitive && it.isString) it.content.toIntOrNull() else it.jsonPrimitive.intOrNull
                if (complexity == null || complexity < 1 || complexity > 10) {
                    throw ToolValidationException("Invalid complexity at index $index")
                }
            }

            taskObj["featureId"]?.jsonPrimitive?.content?.let {
                if (it.isNotEmpty()) {
                    try { UUID.fromString(it) }
                    catch (_: IllegalArgumentException) { throw ToolValidationException("Invalid featureId at index $index") }
                }
            }
        }
    }

    // ========== EXECUTION METHODS ==========

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun executeSearch(params: JsonElement, context: ToolExecutionContext): JsonElement {
        try {
            val query = optionalString(params, "query")
            val statusStr = optionalString(params, "status")
            val priorityStr = optionalString(params, "priority")
            val featureIdStr = optionalString(params, "featureId")
            val projectIdStr = optionalString(params, "projectId")
            val tag = optionalString(params, "tag")
            val limit = optionalInt(params, "limit", 20) ?: 20
            val offset = optionalInt(params, "offset", 0) ?: 0
            val sortBy = optionalString(params, "sortBy", "modifiedAt")
            val sortDirection = optionalString(params, "sortDirection", "desc")

            val status = statusStr?.let { parseStatus(it) }
            val priority = priorityStr?.let { parsePriority(it) }
            val featureId = featureIdStr?.let { UUID.fromString(it) }
            val projectId = projectIdStr?.let { UUID.fromString(it) }

            val allTasksResult = when {
                query != null || projectId != null || featureId != null || status != null || priority != null || tag != null -> {
                    val tags = tag?.let { listOf(it) }

                    when {
                        featureId != null -> context.taskRepository().findByFeatureAndFilters(
                            featureId = featureId,
                            statusFilter = status?.let { StatusFilter(include = listOf(it)) },
                            priorityFilter = priority?.let { StatusFilter(include = listOf(it)) },
                            tags = tags,
                            textQuery = query,
                            limit = 1000
                        )
                        projectId != null -> context.taskRepository().findByProjectAndFilters(
                            projectId = projectId,
                            statusFilter = status?.let { StatusFilter(include = listOf(it)) },
                            priorityFilter = priority?.let { StatusFilter(include = listOf(it)) },
                            tags = tags,
                            textQuery = query,
                            limit = 1000
                        )
                        else -> context.taskRepository().findByFilters(
                            projectId = projectId,
                            statusFilter = status?.let { StatusFilter(include = listOf(it)) },
                            priorityFilter = priority?.let { StatusFilter(include = listOf(it)) },
                            tags = tags,
                            textQuery = query,
                            limit = 1000
                        )
                    }
                }
                else -> context.taskRepository().findAll(limit = 1000)
            }

            when (allTasksResult) {
                is Result.Success -> {
                    val tasks = allTasksResult.data
                    val sortedTasks = sortResults(tasks, sortBy, sortDirection)
                    val paginatedTasks = sortedTasks.drop(offset).take(limit)

                    val totalItems = sortedTasks.size
                    val totalPages = if (limit > 0) (totalItems + limit - 1) / limit else 0
                    val page = if (limit > 0) (offset / limit) + 1 else 1
                    val hasNext = offset + paginatedTasks.size < totalItems
                    val hasPrevious = offset > 0

                    val tasksArray = buildJsonArray {
                        paginatedTasks.forEach { task ->
                            add(buildJsonObject {
                                put("id", task.id.toString())
                                put("title", task.title)
                                put("status", task.status.name.lowercase())
                                put("priority", task.priority.name.lowercase())
                                put("complexity", task.complexity)
                                put("createdAt", task.createdAt.toString())
                                put("modifiedAt", task.modifiedAt.toString())
                                put("featureId", task.featureId?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("projectId", task.projectId?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("tags", buildJsonArray { task.tags.forEach { add(JsonPrimitive(it)) } })
                            })
                        }
                    }

                    val pagination = buildJsonObject {
                        put("page", page)
                        put("pageSize", limit)
                        put("totalItems", totalItems)
                        put("totalPages", totalPages)
                        put("hasNext", hasNext)
                        put("hasPrevious", hasPrevious)
                    }

                    val data = buildJsonObject {
                        put("items", tasksArray)
                        put("pagination", pagination)
                    }

                    val message = when {
                        paginatedTasks.isEmpty() -> "No tasks found"
                        paginatedTasks.size == 1 -> "Found 1 task"
                        else -> "Found ${paginatedTasks.size} tasks"
                    }

                    return successResponse(data, message)
                }
                is Result.Error -> {
                    return errorResponse(
                        message = "Failed to search tasks: ${allTasksResult.error}",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = allTasksResult.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error in search query", e)
            return errorResponse(
                message = "Failed to search tasks",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    private suspend fun executeBlocked(params: JsonElement, context: ToolExecutionContext): JsonElement {
        try {
            val projectId = optionalString(params, "projectId")?.let { UUID.fromString(it) }
            val featureId = optionalString(params, "featureId")?.let { UUID.fromString(it) }
            val includeTaskDetails = if (params is JsonObject) {
                params["includeTaskDetails"]?.jsonPrimitive?.boolean ?: false
            } else false

            val activeTasks = getActiveTasks(projectId, featureId, context)
            val blockedTasksData = findBlockedTasks(activeTasks, includeTaskDetails, context)

            return successResponse(
                data = buildJsonObject {
                    put("blockedTasks", JsonArray(blockedTasksData))
                    put("totalBlocked", blockedTasksData.size)
                },
                message = "Found ${blockedTasksData.size} blocked task(s)"
            )
        } catch (e: Exception) {
            logger.error("Error in blocked query", e)
            return errorResponse(
                message = "Failed to get blocked tasks",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    private suspend fun executeNext(params: JsonElement, context: ToolExecutionContext): JsonElement {
        try {
            val projectId = optionalString(params, "projectId")?.let { UUID.fromString(it) }
            val featureId = optionalString(params, "featureId")?.let { UUID.fromString(it) }
            val limit = if (params is JsonObject) params["limit"]?.jsonPrimitive?.int ?: 1 else 1
            val includeDetails = if (params is JsonObject) {
                params["includeDetails"]?.jsonPrimitive?.boolean ?: false
            } else false

            val pendingTasks = getPendingTasks(projectId, featureId, context)
            val unblockedTasks = filterUnblockedTasks(pendingTasks, context)
            val sortedTasks = sortTasksByPriorityAndComplexity(unblockedTasks)

            val recommendations = sortedTasks.take(limit).map { task ->
                buildJsonObject {
                    put("taskId", task.id.toString())
                    put("title", task.title)
                    put("status", task.status.name.lowercase().replace('_', '-'))
                    put("priority", task.priority.name.lowercase())
                    put("complexity", task.complexity)
                    if (includeDetails) {
                        put("summary", task.summary)
                        put("featureId", task.featureId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("tags", JsonArray(task.tags.map { JsonPrimitive(it) }))
                    }
                }
            }

            return successResponse(
                data = buildJsonObject {
                    put("recommendations", JsonArray(recommendations))
                    put("totalCandidates", unblockedTasks.size)
                },
                message = if (recommendations.isEmpty()) {
                    "No unblocked tasks available"
                } else {
                    "Found ${recommendations.size} recommendation(s) from ${unblockedTasks.size} unblocked task(s)"
                }
            )
        } catch (e: Exception) {
            logger.error("Error in next query", e)
            return errorResponse(
                message = "Failed to get task recommendations",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    private suspend fun executeBulkUpdate(params: JsonElement, context: ToolExecutionContext): JsonElement {
        try {
            val paramsObj = params as? JsonObject ?: return errorResponse(
                message = "Parameters must be a JSON object",
                code = ErrorCodes.VALIDATION_ERROR
            )
            val tasksArray = paramsObj["tasks"] as? JsonArray ?: return errorResponse(
                message = "tasks parameter must be an array",
                code = ErrorCodes.VALIDATION_ERROR
            )
            val successfulTasks = mutableListOf<JsonObject>()
            val failedTasks = mutableListOf<JsonObject>()

            tasksArray.forEachIndexed { index, taskElement ->
                val taskParams = taskElement.jsonObject
                val idStr = taskParams["id"]!!.jsonPrimitive.content
                val taskId = UUID.fromString(idStr)

                val getTaskResult = context.taskRepository().getById(taskId)
                if (getTaskResult is Result.Error) {
                    failedTasks.add(buildJsonObject {
                        put("index", index)
                        put("id", idStr)
                        put("error", buildJsonObject {
                            put("code", when (getTaskResult.error) {
                                is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                else -> ErrorCodes.DATABASE_ERROR
                            })
                            put("details", getTaskResult.error.toString())
                        })
                    })
                    return@forEachIndexed
                }

                val existingTask = (getTaskResult as Result.Success).data

                val title = optionalString(taskParams, "title") ?: existingTask.title
                val description = optionalString(taskParams, "description") ?: existingTask.description
                val summary = optionalString(taskParams, "summary") ?: existingTask.summary

                val statusStr = optionalString(taskParams, "status")
                val status = if (statusStr != null) parseStatus(statusStr) else existingTask.status

                val priorityStr = optionalString(taskParams, "priority")
                val priority = if (priorityStr != null) parsePriority(priorityStr) else existingTask.priority

                val complexity = optionalInt(taskParams, "complexity") ?: existingTask.complexity

                val featureId = optionalString(taskParams, "featureId")?.let {
                    if (it.isEmpty()) null else UUID.fromString(it)
                } ?: existingTask.featureId

                if (featureId != null && featureId != existingTask.featureId) {
                    when (val featureResult = context.repositoryProvider.featureRepository().getById(featureId)) {
                        is Result.Error -> {
                            failedTasks.add(buildJsonObject {
                                put("index", index)
                                put("id", idStr)
                                put("error", buildJsonObject {
                                    put("code", ErrorCodes.RESOURCE_NOT_FOUND)
                                    put("details", "Feature not found: $featureId")
                                })
                            })
                            return@forEachIndexed
                        }
                        is Result.Success -> { /* Feature exists */ }
                    }
                }

                val tags = optionalString(taskParams, "tags")?.let {
                    if (it.isEmpty()) emptyList()
                    else it.split(",").map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }
                } ?: existingTask.tags

                val updatedTask = existingTask.copy(
                    title = title,
                    description = description,
                    summary = summary,
                    status = status,
                    priority = priority,
                    complexity = complexity,
                    featureId = featureId,
                    tags = tags,
                    modifiedAt = Instant.now()
                )

                val updateResult = context.taskRepository().update(updatedTask)

                when (updateResult) {
                    is Result.Success -> {
                        successfulTasks.add(buildJsonObject {
                            put("id", updateResult.data.id.toString())
                            put("status", updateResult.data.status.name.lowercase().replace('_', '-'))
                            put("modifiedAt", updateResult.data.modifiedAt.toString())
                        })
                    }
                    is Result.Error -> {
                        failedTasks.add(buildJsonObject {
                            put("index", index)
                            put("id", idStr)
                            put("error", buildJsonObject {
                                put("code", when (updateResult.error) {
                                    is RepositoryError.ValidationError -> ErrorCodes.VALIDATION_ERROR
                                    is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
                                    else -> ErrorCodes.DATABASE_ERROR
                                })
                                put("details", updateResult.error.toString())
                            })
                        })
                    }
                }
            }

            val totalRequested = tasksArray.size
            val successCount = successfulTasks.size
            val failedCount = failedTasks.size

            return if (failedCount == 0) {
                successResponse(
                    data = buildJsonObject {
                        put("items", JsonArray(successfulTasks))
                        put("updated", successCount)
                        put("failed", 0)
                    },
                    message = "$successCount tasks updated successfully"
                )
            } else if (successCount == 0) {
                errorResponse(
                    message = "Failed to update any tasks",
                    code = ErrorCodes.OPERATION_FAILED,
                    details = "All $totalRequested tasks failed to update",
                    additionalData = buildJsonObject {
                        put("failures", JsonArray(failedTasks))
                    }
                )
            } else {
                successResponse(
                    data = buildJsonObject {
                        put("items", JsonArray(successfulTasks))
                        put("updated", successCount)
                        put("failed", failedCount)
                        put("failures", JsonArray(failedTasks))
                    },
                    message = "$successCount tasks updated, $failedCount failed"
                )
            }
        } catch (e: Exception) {
            logger.error("Error in bulkUpdate query", e)
            return errorResponse(
                message = "Failed to update tasks",
                code = ErrorCodes.OPERATION_FAILED,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== HELPER METHODS ==========

    private fun sortResults(tasks: List<Task>, sortBy: String, sortDirection: String): List<Task> {
        val comparator = when (sortBy) {
            "createdAt" -> compareBy { it.createdAt }
            "modifiedAt" -> compareBy { it.modifiedAt }
            "priority" -> compareBy { it.priority.ordinal }
            "status" -> compareBy { it.status.ordinal }
            "complexity" -> compareBy { it.complexity }
            else -> compareBy<Task> { it.modifiedAt }
        }

        return if (sortDirection == "desc") {
            tasks.sortedWith(comparator.reversed())
        } else {
            tasks.sortedWith(comparator)
        }
    }

    private suspend fun getActiveTasks(
        projectId: UUID?,
        featureId: UUID?,
        context: ToolExecutionContext
    ): List<Task> {
        val tasksResult = when {
            featureId != null -> context.taskRepository().findByFeature(featureId, limit = 1000)
            projectId != null -> context.taskRepository().findByProject(projectId, limit = 1000)
            else -> context.taskRepository().findAll(limit = 1000)
        }

        return when (tasksResult) {
            is Result.Success -> tasksResult.data.filter { task ->
                task.status == TaskStatus.PENDING || task.status == TaskStatus.IN_PROGRESS
            }
            is Result.Error -> {
                logger.warn("Failed to get tasks: ${tasksResult.error.message}")
                emptyList()
            }
        }
    }

    private suspend fun getPendingTasks(
        projectId: UUID?,
        featureId: UUID?,
        context: ToolExecutionContext
    ): List<Task> {
        val tasksResult = when {
            featureId != null -> context.taskRepository().findByFeature(featureId, limit = 1000)
            projectId != null -> context.taskRepository().findByProject(projectId, limit = 1000)
            else -> context.taskRepository().findAll(limit = 1000)
        }

        return when (tasksResult) {
            is Result.Success -> tasksResult.data.filter { it.status == TaskStatus.PENDING }
            is Result.Error -> {
                logger.warn("Failed to get tasks: ${tasksResult.error.message}")
                emptyList()
            }
        }
    }

    private suspend fun findBlockedTasks(
        tasks: List<Task>,
        includeDetails: Boolean,
        context: ToolExecutionContext
    ): List<JsonObject> {
        val blockedTasks = mutableListOf<JsonObject>()

        for (task in tasks) {
            val incomingDeps = context.repositoryProvider.dependencyRepository().findByToTaskId(task.id)

            if (incomingDeps.isEmpty()) continue

            val incompleteBlockers = mutableListOf<JsonObject>()

            for (dep in incomingDeps) {
                when (val blockerResult = context.taskRepository().getById(dep.fromTaskId)) {
                    is Result.Success -> {
                        val blocker = blockerResult.data
                        if (blocker.status != TaskStatus.COMPLETED && blocker.status != TaskStatus.CANCELLED) {
                            incompleteBlockers.add(buildJsonObject {
                                put("taskId", blocker.id.toString())
                                put("title", blocker.title)
                                put("status", blocker.status.name.lowercase().replace('_', '-'))
                                put("priority", blocker.priority.name.lowercase())
                                if (includeDetails) {
                                    put("complexity", blocker.complexity)
                                    put("featureId", blocker.featureId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                }
                            })
                        }
                    }
                    is Result.Error -> {
                        logger.warn("Failed to get blocker task ${dep.fromTaskId}")
                    }
                }
            }

            if (incompleteBlockers.isNotEmpty()) {
                blockedTasks.add(buildJsonObject {
                    put("taskId", task.id.toString())
                    put("title", task.title)
                    put("status", task.status.name.lowercase().replace('_', '-'))
                    put("priority", task.priority.name.lowercase())
                    put("complexity", task.complexity)
                    if (includeDetails) {
                        put("summary", task.summary)
                        put("featureId", task.featureId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("tags", JsonArray(task.tags.map { JsonPrimitive(it) }))
                    }
                    put("blockedBy", JsonArray(incompleteBlockers))
                    put("blockerCount", incompleteBlockers.size)
                })
            }
        }

        return blockedTasks
    }

    private suspend fun filterUnblockedTasks(
        tasks: List<Task>,
        context: ToolExecutionContext
    ): List<Task> {
        return tasks.filter { task ->
            val incomingDeps = context.repositoryProvider.dependencyRepository().findByToTaskId(task.id)

            if (incomingDeps.isEmpty()) return@filter true

            val hasIncompleteBlockers = incomingDeps.any { dep ->
                when (val blockerResult = context.taskRepository().getById(dep.fromTaskId)) {
                    is Result.Success -> {
                        val blocker = blockerResult.data
                        blocker.status != TaskStatus.COMPLETED && blocker.status != TaskStatus.CANCELLED
                    }
                    is Result.Error -> {
                        logger.warn("Failed to get blocker task ${dep.fromTaskId}")
                        true
                    }
                }
            }

            !hasIncompleteBlockers
        }
    }

    private fun sortTasksByPriorityAndComplexity(tasks: List<Task>): List<Task> {
        return tasks.sortedWith(
            compareBy<Task> { task ->
                when (task.priority) {
                    Priority.HIGH -> 0
                    Priority.MEDIUM -> 1
                    Priority.LOW -> 2
                }
            }.thenBy { it.complexity }
        )
    }

    private fun isValidStatus(status: String): Boolean {
        return try {
            parseStatus(status)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseStatus(status: String): TaskStatus {
        return when (status.lowercase().replace('-', '_')) {
            "pending" -> TaskStatus.PENDING
            "in_progress", "inprogress", "in-progress" -> TaskStatus.IN_PROGRESS
            "completed" -> TaskStatus.COMPLETED
            "cancelled", "canceled" -> TaskStatus.CANCELLED
            "deferred" -> TaskStatus.DEFERRED
            else -> throw IllegalArgumentException("Invalid status: $status")
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
