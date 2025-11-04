package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.WorkflowConfigLoaderImpl
import io.github.jpicklyk.mcptask.application.service.WorkflowService
import io.github.jpicklyk.mcptask.application.service.WorkflowServiceImpl
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * Read-only MCP tool for querying complete workflow state.
 *
 * Returns comprehensive workflow information including:
 * - Current status and active flow
 * - Allowed transitions based on config
 * - Cascade events that have been detected
 * - Prerequisites for each allowed transition
 *
 * Use this tool to understand workflow state before making status changes.
 */
class QueryWorkflowStateTool : BaseToolDefinition() {

    private val statusValidator = StatusValidator()

    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT
    override val name: String = "query_workflow_state"
    override val title: String = "Query Workflow State"

    override val description: String = """Query complete workflow state for a container.

Returns comprehensive workflow information:
- currentStatus: Current status of the container
- activeFlow: Active workflow flow based on tags
- allowedTransitions: List of valid next statuses from config
- detectedEvents: Cascade events detected for this container
- prerequisites: Prerequisite validation for each allowed transition

Parameters:
| Field | Type | Required | Description |
| containerType | enum | Yes | project, feature, task |
| id | UUID | Yes | Container ID |

Use before status changes to understand:
- What transitions are valid
- What prerequisites need to be met
- What cascade events will trigger

Related: manage_container (setStatus), get_next_status
Docs: task-orchestrator://docs/tools/query-workflow-state
"""

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

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "containerType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "enum" to JsonArray(
                            listOf(
                                JsonPrimitive("project"),
                                JsonPrimitive("feature"),
                                JsonPrimitive("task")
                            )
                        ),
                        "description" to JsonPrimitive("Type of container")
                    )
                ),
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "format" to JsonPrimitive("uuid"),
                        "description" to JsonPrimitive("Container ID")
                    )
                )
            )
        ),
        required = listOf("containerType", "id")
    )

    override fun validateParams(params: JsonElement) {
        val containerType = requireString(params, "containerType")

        if (containerType !in listOf("project", "feature", "task")) {
            throw ToolValidationException("Invalid containerType: $containerType")
        }

        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid ID format. Must be a valid UUID.")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        return try {
            val containerType = requireString(params, "containerType")
            val id = extractEntityId(params, "id")

            val workflowService = createWorkflowService(context)
            val workflowState = workflowService.getWorkflowState(
                id,
                ContainerType.valueOf(containerType.uppercase())
            )

            successResponse(
                message = "Workflow state retrieved successfully",
                data = buildJsonObject {
                    put("id", workflowState.containerId.toString())
                    put("containerType", workflowState.containerType.name.lowercase())
                    put("currentStatus", workflowState.currentStatus)
                    put("activeFlow", workflowState.activeFlow)
                    put("allowedTransitions", JsonArray(
                        workflowState.allowedTransitions.map { JsonPrimitive(it) }
                    ))

                    // Detected cascade events
                    if (workflowState.detectedEvents.isNotEmpty()) {
                        put("detectedEvents", JsonArray(
                            workflowState.detectedEvents.map { event ->
                                buildJsonObject {
                                    put("event", event.event)
                                    put("targetType", event.targetType.name.lowercase())
                                    put("targetId", event.targetId.toString())
                                    put("targetName", event.targetName)
                                    put("currentStatus", event.currentStatus)
                                    put("suggestedStatus", event.suggestedStatus)
                                    put("flow", event.flow)
                                    put("automatic", event.automatic)
                                    put("reason", event.reason)
                                }
                            }
                        ))
                    }

                    // Prerequisites for each transition
                    if (workflowState.prerequisites.isNotEmpty()) {
                        put("prerequisites", buildJsonObject {
                            workflowState.prerequisites.forEach { (status, prereq) ->
                                put(status, buildJsonObject {
                                    put("met", prereq.met)
                                    put("requirements", JsonArray(
                                        prereq.requirements.map { JsonPrimitive(it) }
                                    ))
                                    if (prereq.blockingReasons.isNotEmpty()) {
                                        put("blockingReasons", JsonArray(
                                            prereq.blockingReasons.map { JsonPrimitive(it) }
                                        ))
                                    }
                                })
                            }
                        })
                    }
                }
            )
        } catch (e: ToolValidationException) {
            errorResponse(e.message ?: "Validation error", ErrorCodes.VALIDATION_ERROR)
        } catch (e: Exception) {
            errorResponse(
                "Failed to query workflow state",
                ErrorCodes.INTERNAL_ERROR,
                e.message ?: "Unknown error"
            )
        }
    }

    private fun createWorkflowService(context: ToolExecutionContext): WorkflowService {
        return WorkflowServiceImpl(
            workflowConfigLoader = WorkflowConfigLoaderImpl(),
            taskRepository = context.taskRepository(),
            featureRepository = context.featureRepository(),
            projectRepository = context.projectRepository(),
            statusValidator = statusValidator
        )
    }

    private fun extractEntityId(params: JsonElement, fieldName: String): UUID {
        val idStr = requireString(params, fieldName)
        return try {
            UUID.fromString(idStr)
        } catch (e: IllegalArgumentException) {
            throw ToolValidationException("Invalid $fieldName format. Must be a valid UUID.")
        }
    }
}
