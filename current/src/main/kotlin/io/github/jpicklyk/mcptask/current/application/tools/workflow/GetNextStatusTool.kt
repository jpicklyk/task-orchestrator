package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Read-only MCP tool that recommends the next status progression for a WorkItem.
 *
 * Analyzes the item's current role, dependency constraints, and workflow position
 * to provide one of three recommendations:
 * - **Ready**: The item can progress to the next role via the "start" trigger
 * - **Blocked**: The item cannot progress due to unsatisfied dependencies or explicit BLOCKED role
 * - **Terminal**: The item has already completed its workflow and cannot progress further
 */
class GetNextStatusTool : BaseToolDefinition() {

    override val name = "get_next_status"

    override val description = """
Read-only status progression recommendation for a WorkItem.

**Parameters:**
- `itemId` (required, UUID): WorkItem to analyze

**Response:**
- recommendation: "Ready", "Blocked", or "Terminal"
- If Ready: currentRole, nextRole, trigger ("start"), progressionPosition
- If Blocked: currentRole, blockers[] (each with fromItemId, currentRole, requiredRole), or resume suggestion if BLOCKED role
- If Terminal: currentRole, reason
    """.trimIndent()

    override val category = ToolCategory.WORKFLOW

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("itemId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("UUID of the WorkItem to analyze"))
            })
        },
        required = listOf("itemId")
    )

    override fun validateParams(params: JsonElement) {
        extractUUID(params, "itemId", required = true)
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val itemId = extractUUID(params, "itemId", required = true)!!

        // Fetch the WorkItem
        val itemResult = context.workItemRepository().getById(itemId)
        val item = when (itemResult) {
            is Result.Success -> itemResult.data
            is Result.Error -> return errorResponse(
                "WorkItem not found: $itemId",
                ErrorCodes.RESOURCE_NOT_FOUND
            )
        }

        val handler = RoleTransitionHandler()

        return when (item.role) {
            Role.TERMINAL -> {
                // Terminal recommendation
                successResponse(buildJsonObject {
                    put("recommendation", JsonPrimitive("Terminal"))
                    put("currentRole", JsonPrimitive("terminal"))
                    put("reason", JsonPrimitive("Item is already terminal and cannot progress further"))
                })
            }

            Role.BLOCKED -> {
                // Blocked recommendation with resume suggestion
                successResponse(buildJsonObject {
                    put("recommendation", JsonPrimitive("Blocked"))
                    put("currentRole", JsonPrimitive("blocked"))
                    put("suggestion", JsonPrimitive("Use 'resume' trigger to return to previous role"))
                })
            }

            Role.QUEUE, Role.WORK, Role.REVIEW -> {
                // Resolve next role via "start" trigger
                val resolution = handler.resolveTransition(item.role, "start")

                if (!resolution.success || resolution.targetRole == null) {
                    return errorResponse(
                        resolution.error ?: "Failed to resolve next status",
                        ErrorCodes.OPERATION_FAILED
                    )
                }

                val targetRole = resolution.targetRole

                // Validate transition against dependency constraints
                val validation = handler.validateTransition(
                    item,
                    targetRole,
                    context.dependencyRepository(),
                    context.workItemRepository()
                )

                if (validation.valid) {
                    // Ready recommendation
                    val position = Role.PROGRESSION.indexOf(item.role)
                    val total = Role.PROGRESSION.size
                    successResponse(buildJsonObject {
                        put("recommendation", JsonPrimitive("Ready"))
                        put("currentRole", JsonPrimitive(item.role.name.lowercase()))
                        put("nextRole", JsonPrimitive(targetRole.name.lowercase()))
                        put("trigger", JsonPrimitive("start"))
                        put("progressionPosition", JsonPrimitive("${position + 1}/$total"))
                    })
                } else {
                    // Blocked by dependencies
                    successResponse(buildJsonObject {
                        put("recommendation", JsonPrimitive("Blocked"))
                        put("currentRole", JsonPrimitive(item.role.name.lowercase()))
                        put("blockers", JsonArray(validation.blockers.map { blocker ->
                            buildJsonObject {
                                put("fromItemId", JsonPrimitive(blocker.fromItemId.toString()))
                                put("currentRole", JsonPrimitive(blocker.currentRole.name.lowercase()))
                                put("requiredRole", JsonPrimitive(blocker.requiredRole))
                            }
                        }))
                    })
                }
            }
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "get_next_status failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val rec = data?.get("recommendation")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        val role = data?.get("currentRole")?.let { (it as? JsonPrimitive)?.content } ?: ""
        return "Recommendation: $rec (current: $role)"
    }
}
