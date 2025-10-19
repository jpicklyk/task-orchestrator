package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.Dependency
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * Consolidated MCP tool for read-only dependency queries.
 * Supports filtering by direction (incoming, outgoing, all), type, and optional task info enrichment.
 *
 * Part of v2.0's container-based tool consolidation to reduce token overhead.
 */
class QueryDependenciesTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.DEPENDENCY_MANAGEMENT

    override val name: String = "query_dependencies"

    override val title: String = "Query Dependencies"

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

    override val description: String = """Read-only dependency queries with filtering support.

Parameters:
| Field | Type | Required | Description |
| taskId | UUID | Yes | Task to query dependencies for |
| direction | enum | No | incoming, outgoing, all (default: all) |
| type | enum | No | BLOCKS, IS_BLOCKED_BY, RELATES_TO, all (default: all) |
| includeTaskInfo | boolean | No | Include task details (title, status) (default: false) |

Directions:
- incoming: Dependencies TO this task (tasks that block this task)
- outgoing: Dependencies FROM this task (tasks blocked by this task)
- all: Both directions

Returns dependency objects with counts breakdown and applied filters.

Usage: Consolidates dependency queries with filtering (read-only).
Related: manage_dependency
Docs: task-orchestrator://docs/tools/query-dependencies
"""

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "taskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Task ID to query dependencies for"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "direction" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by direction"),
                        "enum" to JsonArray(listOf("incoming", "outgoing", "all").map { JsonPrimitive(it) }),
                        "default" to JsonPrimitive("all")
                    )
                ),
                "type" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by dependency type"),
                        "enum" to JsonArray(listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO", "all").map { JsonPrimitive(it) }),
                        "default" to JsonPrimitive("all")
                    )
                ),
                "includeTaskInfo" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Include task details for related tasks"),
                        "default" to JsonPrimitive(false)
                    )
                )
            )
        ),
        required = listOf("taskId")
    )

    override fun validateParams(params: JsonElement) {
        // Validate taskId
        val taskIdStr = requireString(params, "taskId")
        try {
            UUID.fromString(taskIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid taskId format. Must be a valid UUID.")
        }

        // Validate direction
        optionalString(params, "direction")?.let { direction ->
            if (direction !in listOf("incoming", "outgoing", "all")) {
                throw ToolValidationException("Invalid direction: $direction. Must be one of: incoming, outgoing, all")
            }
        }

        // Validate type
        optionalString(params, "type")?.let { type ->
            if (type != "all") {
                try {
                    DependencyType.fromString(type)
                } catch (e: Exception) {
                    throw ToolValidationException("Invalid type: $type. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO, all")
                }
            }
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing query_dependencies tool")

        return try {
            val taskId = extractEntityId(params, "taskId")
            val direction = optionalString(params, "direction") ?: "all"
            val typeFilter = optionalString(params, "type") ?: "all"
            val includeTaskInfo = optionalBoolean(params, "includeTaskInfo", false)

            // Validate task exists
            val taskResult = context.taskRepository().getById(taskId)
            if (taskResult is Result.Error) {
                return if (taskResult.error is RepositoryError.NotFound) {
                    errorResponse(
                        message = "Task not found",
                        code = ErrorCodes.RESOURCE_NOT_FOUND,
                        details = "No task exists with ID $taskId"
                    )
                } else {
                    errorResponse(
                        message = "Error retrieving task",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = taskResult.error.message
                    )
                }
            }

            // Get dependencies based on direction
            val allDependencies = when (direction) {
                "incoming" -> context.dependencyRepository().findByToTaskId(taskId)
                "outgoing" -> context.dependencyRepository().findByFromTaskId(taskId)
                "all" -> context.dependencyRepository().findByTaskId(taskId)
                else -> context.dependencyRepository().findByTaskId(taskId)
            }

            // Apply type filter
            val filteredDependencies = if (typeFilter != "all") {
                val targetType = DependencyType.fromString(typeFilter)
                allDependencies.filter { it.type == targetType }
            } else {
                allDependencies
            }

            // Build dependency objects with optional task info
            val dependencyObjects = filteredDependencies.map { dependency ->
                buildDependencyJson(dependency, includeTaskInfo, taskId, context)
            }

            // Separate for counts (use unfiltered for direction="all")
            val incomingDependencies = if (direction == "all") {
                context.dependencyRepository().findByToTaskId(taskId).let { deps ->
                    if (typeFilter != "all") {
                        val targetType = DependencyType.fromString(typeFilter)
                        deps.filter { it.type == targetType }
                    } else deps
                }
            } else {
                if (direction == "incoming") filteredDependencies else emptyList()
            }

            val outgoingDependencies = if (direction == "all") {
                context.dependencyRepository().findByFromTaskId(taskId).let { deps ->
                    if (typeFilter != "all") {
                        val targetType = DependencyType.fromString(typeFilter)
                        deps.filter { it.type == targetType }
                    } else deps
                }
            } else {
                if (direction == "outgoing") filteredDependencies else emptyList()
            }

            // Build counts
            val counts = buildJsonObject {
                put("total", filteredDependencies.size)
                put("incoming", incomingDependencies.size)
                put("outgoing", outgoingDependencies.size)
                put("byType", buildJsonObject {
                    put("BLOCKS", filteredDependencies.count { it.type == DependencyType.BLOCKS })
                    put("IS_BLOCKED_BY", filteredDependencies.count { it.type == DependencyType.IS_BLOCKED_BY })
                    put("RELATES_TO", filteredDependencies.count { it.type == DependencyType.RELATES_TO })
                })
            }

            // Organize dependencies based on direction
            val dependenciesData = if (direction == "all") {
                buildJsonObject {
                    put("incoming", buildJsonArray {
                        incomingDependencies.forEach { dep ->
                            add(buildDependencyJson(dep, includeTaskInfo, taskId, context))
                        }
                    })
                    put("outgoing", buildJsonArray {
                        outgoingDependencies.forEach { dep ->
                            add(buildDependencyJson(dep, includeTaskInfo, taskId, context))
                        }
                    })
                }
            } else {
                buildJsonArray {
                    dependencyObjects.forEach { add(it) }
                }
            }

            successResponse(
                buildJsonObject {
                    put("taskId", taskId.toString())
                    put("dependencies", dependenciesData)
                    put("counts", counts)
                    put("filters", buildJsonObject {
                        put("direction", direction)
                        put("type", typeFilter)
                        put("includeTaskInfo", includeTaskInfo)
                    })
                },
                "Dependencies retrieved successfully"
            )

        } catch (e: ToolValidationException) {
            logger.warn("Validation error in query_dependencies: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in query_dependencies", e)
            errorResponse(
                message = "Failed to query dependencies",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    // ========== HELPER METHODS ==========

    private suspend fun buildDependencyJson(
        dependency: Dependency,
        includeTaskInfo: Boolean,
        currentTaskId: UUID,
        context: ToolExecutionContext
    ): JsonObject {
        return buildJsonObject {
            put("id", dependency.id.toString())
            put("fromTaskId", dependency.fromTaskId.toString())
            put("toTaskId", dependency.toTaskId.toString())
            put("type", dependency.type.name)
            put("createdAt", dependency.createdAt.toString())

            if (includeTaskInfo) {
                val relatedTaskId = if (dependency.fromTaskId == currentTaskId) {
                    dependency.toTaskId
                } else {
                    dependency.fromTaskId
                }

                val relatedTaskResult = context.taskRepository().getById(relatedTaskId)
                if (relatedTaskResult is Result.Success) {
                    val relatedTask = relatedTaskResult.data
                    put("relatedTask", buildJsonObject {
                        put("id", relatedTask.id.toString())
                        put("title", relatedTask.title)
                        put("status", relatedTask.status.name.lowercase().replace('_', '-'))
                        put("priority", relatedTask.priority.name.lowercase())
                    })
                }
            }
        }
    }
}
