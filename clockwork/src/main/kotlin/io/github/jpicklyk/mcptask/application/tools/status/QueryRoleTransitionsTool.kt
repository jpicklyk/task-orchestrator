package io.github.jpicklyk.mcptask.application.tools.status

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool for querying role transition history for a task, feature, or project.
 *
 * Returns an audit trail of role changes (e.g., queue->work, work->review) with
 * timestamps, triggers, and status details. Useful for workflow analytics,
 * bottleneck detection, and understanding entity lifecycle.
 */
class QueryRoleTransitionsTool : BaseToolDefinition() {

    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val toolAnnotations: ToolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val name: String = "query_role_transitions"

    override val title: String = "Query Role Transitions"

    override val description: String = """Query role transition history for a task, feature, or project. Returns an audit trail of role changes (e.g., queue→work, work→review) with timestamps, triggers, and status details.

        Parameters:
        - entityId (required): UUID of the entity to query transitions for
        - entityType (optional): Filter by entity type ("task", "feature", or "project"). If omitted, returns all transitions for the entityId regardless of type.
        - limit (optional): Maximum transitions to return (default: 50, max: 100)
        - fromRole (optional): Filter to transitions FROM this role (e.g., "queue", "work", "review")
        - toRole (optional): Filter to transitions TO this role (e.g., "work", "review", "terminal")

        Returns:
        - entityId: The queried entity UUID
        - transitionCount: Number of transitions returned
        - transitions: Array of transition objects, each with:
          - id, entityId, entityType, fromRole, toRole, fromStatus, toStatus, transitionedAt, trigger, summary

        Use Cases:
        - Workflow analytics: time-in-role metrics
        - Audit trail: when did entity enter/exit each phase
        - Bottleneck detection: identify slow role transitions
        - Reporting: role-based velocity and throughput

        Related tools: request_transition, get_next_status, query_container"""

    override val parameterSchema: ToolSchema = ToolSchema(
        properties = JsonObject(
            mapOf(
                "entityId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("UUID of the entity to query transitions for"),
                        "format" to JsonPrimitive("uuid")
                    )
                ),
                "entityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by entity type. If omitted, returns all transitions for the entityId regardless of type."),
                        "enum" to JsonArray(listOf("task", "feature", "project").map { JsonPrimitive(it) })
                    )
                ),
                "limit" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Maximum transitions to return (default: 50, max: 100)"),
                        "default" to JsonPrimitive(50)
                    )
                ),
                "fromRole" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter to transitions FROM this role (e.g., queue, work, review, blocked, terminal)")
                    )
                ),
                "toRole" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter to transitions TO this role (e.g., queue, work, review, blocked, terminal)")
                    )
                )
            )
        ),
        required = listOf("entityId")
    )

    override val outputSchema: ToolSchema = ToolSchema(
        buildJsonObject {
            put("type", "object")
            put("description", "Role transition history for the queried entity")
            putJsonObject("properties") {
                putJsonObject("entityId") {
                    put("type", "string")
                    put("description", "The queried entity UUID")
                }
                putJsonObject("transitionCount") {
                    put("type", "integer")
                    put("description", "Number of transitions returned")
                }
                putJsonObject("transitions") {
                    put("type", "array")
                    put("description", "Array of role transition records")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "string") }
                            putJsonObject("entityId") { put("type", "string") }
                            putJsonObject("entityType") { put("type", "string") }
                            putJsonObject("fromRole") { put("type", "string") }
                            putJsonObject("toRole") { put("type", "string") }
                            putJsonObject("fromStatus") { put("type", "string") }
                            putJsonObject("toStatus") { put("type", "string") }
                            putJsonObject("transitionedAt") { put("type", "string") }
                            putJsonObject("trigger") { put("type", "string") }
                            putJsonObject("summary") { put("type", "string") }
                        }
                    }
                }
            }
        }
    )

    override fun validateParams(params: JsonElement) {
        if (params !is JsonObject) {
            throw ToolValidationException("Parameters must be a JSON object")
        }

        // Validate entityId (required)
        val entityIdStr = params["entityId"]?.jsonPrimitive?.content
            ?: throw ToolValidationException("Missing required parameter: entityId")

        try {
            UUID.fromString(entityIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entityId format. Must be a valid UUID.")
        }

        // Validate entityType if provided
        val entityType = params["entityType"]?.jsonPrimitive?.content
        if (entityType != null && entityType !in listOf("task", "feature", "project")) {
            throw ToolValidationException("Invalid entityType. Must be one of: task, feature, project")
        }

        // Validate limit if provided
        val limit = params["limit"]?.jsonPrimitive?.intOrNull
        if (limit != null && (limit < 1 || limit > 100)) {
            throw ToolValidationException("limit must be between 1 and 100")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing query_role_transitions tool")

        return try {
            val paramsObj = params as JsonObject

            // Parse parameters
            val entityId = UUID.fromString(paramsObj["entityId"]!!.jsonPrimitive.content)
            val entityType = paramsObj["entityType"]?.jsonPrimitive?.content
            val limit = paramsObj["limit"]?.jsonPrimitive?.intOrNull ?: 50
            val fromRole = paramsObj["fromRole"]?.jsonPrimitive?.content
            val toRole = paramsObj["toRole"]?.jsonPrimitive?.content

            // Query transitions from repository
            val result = context.roleTransitionRepository().findByEntityId(entityId, entityType)

            when (result) {
                is Result.Success -> {
                    // Apply post-query filters
                    var transitions = result.data

                    if (fromRole != null) {
                        transitions = transitions.filter { it.fromRole == fromRole }
                    }
                    if (toRole != null) {
                        transitions = transitions.filter { it.toRole == toRole }
                    }

                    // Apply limit
                    transitions = transitions.take(limit.coerceIn(1, 100))

                    val responseData = buildJsonObject {
                        put("entityId", entityId.toString())
                        put("transitionCount", transitions.size)
                        putJsonArray("transitions") {
                            transitions.forEach { transition ->
                                add(buildJsonObject {
                                    put("id", transition.id.toString())
                                    put("entityId", transition.entityId.toString())
                                    put("entityType", transition.entityType)
                                    put("fromRole", transition.fromRole)
                                    put("toRole", transition.toRole)
                                    put("fromStatus", transition.fromStatus)
                                    put("toStatus", transition.toStatus)
                                    put("transitionedAt", transition.transitionedAt.toString())
                                    put("trigger", transition.trigger ?: "")
                                    put("summary", transition.summary ?: "")
                                })
                            }
                        }
                    }

                    successResponse(
                        data = responseData,
                        message = "${transitions.size} role transition(s) found for entity ${shortId(entityId.toString())}"
                    )
                }
                is Result.Error -> {
                    errorResponse(
                        message = "Failed to query role transitions: ${result.error.message}",
                        code = ErrorCodes.INTERNAL_ERROR
                    )
                }
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in query_role_transitions: ${e.message}")
            errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error in query_role_transitions", e)
            errorResponse(
                message = "Failed to query role transitions",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return super.userSummary(params, result, true)
        val data = (result as? JsonObject)?.get("data")?.jsonObject ?: return super.userSummary(params, result, false)
        val count = data["transitionCount"]?.jsonPrimitive?.intOrNull ?: 0
        val entityId = data["entityId"]?.jsonPrimitive?.content?.let { shortId(it) } ?: ""
        return "$count transition${if (count == 1) "" else "s"} for $entityId"
    }
}
