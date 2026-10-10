package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.BlockerInfo
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.GateId
import io.github.jpicklyk.mcptask.current.domain.lifecycle.RejectContext
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Read-only MCP tool that recommends the next status progression for a WorkItem.
 *
 * Evaluates a `start` with the same policy the advance runs ([io.github.jpicklyk.mcptask.current.application.service.TransitionPreview],
 * claim ownership excluded) to provide one of three recommendations:
 * - **Ready**: The item can progress to the next role via the "start" trigger
 * - **Blocked**: The item cannot progress: unsatisfied dependencies (`blockers`), unfilled required
 *   notes (`reason` "gate_blocked"), a held exclusive resource (`reason` "resource_unavailable"), or an
 *   explicit BLOCKED role
 * - **Terminal**: The item has already completed its workflow and cannot progress further
 */
class GetNextStatusTool : BaseToolDefinition() {
    override val name = "get_next_status"

    override val description =
        """
Read-only status progression recommendation for a WorkItem: "Ready" (can advance via the "start"
trigger), "Blocked" (unsatisfied dependencies, missing required notes, a held resource, or explicit
BLOCKED role — use "resume" to return to its previous role), or "Terminal" (workflow already complete).

Call to check one item's advance-readiness when a full context snapshot is not needed.
        """.trimIndent()

    override val category = ToolCategory.WORKFLOW

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )

    override val parameterSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("WorkItem UUID or hex prefix (4+ chars)"))
                        }
                    )
                },
            required = listOf("itemId")
        )

    override fun validateParams(params: JsonElement) {
        validateIdOrPrefix(params, "itemId", required = true)
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (resolvedItemId, idError) = resolveItemId(params, "itemId", context)
        if (idError != null) return idError
        val itemId = resolvedItemId!!

        // Fetch the WorkItem
        val itemResult = context.workItemRepository().getById(itemId)
        val item =
            itemResult ?: return errorResponse(
                "WorkItem not found: $itemId",
                LegacyMcpCode.RESOURCE_NOT_FOUND
            )

        return when (item.role) {
            Role.TERMINAL -> {
                // Terminal recommendation
                successResponse(
                    buildJsonObject {
                        put("recommendation", JsonPrimitive("Terminal"))
                        put("currentRole", JsonPrimitive("terminal"))
                        put(
                            "reason",
                            JsonPrimitive("Item is terminal. Use 'reopen' trigger to move back to queue, or 'cancel' if already cancelled.")
                        )
                    }
                )
            }

            Role.BLOCKED -> {
                // Blocked recommendation with resume suggestion
                successResponse(
                    buildJsonObject {
                        put("recommendation", JsonPrimitive("Blocked"))
                        put("currentRole", JsonPrimitive("blocked"))
                        put("suggestion", JsonPrimitive("Use 'resume' trigger to return to previous role"))
                    }
                )
            }

            Role.QUEUE, Role.WORK, Role.REVIEW -> {
                // The advance's own policy evaluation of "start" (ownership excluded), so Ready/Blocked
                // never disagrees with advance_item on the same state.
                val hasReviewPhase = context.resolveHasReviewPhase(item)
                when (val decision = context.transitionPreview().evaluate(item, Trigger.User.START)) {
                    is Decision.Allow -> {
                        val position = Role.PROGRESSION.indexOf(item.role)
                        val effectiveTotal = if (hasReviewPhase) Role.PROGRESSION.size else Role.PROGRESSION.size - 1
                        successResponse(
                            buildJsonObject {
                                put("recommendation", JsonPrimitive("Ready"))
                                put("currentRole", JsonPrimitive(item.role.toJsonString()))
                                put("nextRole", JsonPrimitive(decision.target.toJsonString()))
                                put("trigger", JsonPrimitive("start"))
                                put("progressionPosition", JsonPrimitive("${position + 1}/$effectiveTotal"))
                            }
                        )
                    }
                    is Decision.Reject -> blockedResponse(item.role, decision)
                    is Decision.NotApplicable -> errorResponse("Failed to resolve next status", LegacyMcpCode.OPERATION_FAILED)
                }
            }
        }
    }

    /** The "Blocked" recommendation for a rejected `start` preview. */
    private fun blockedResponse(
        role: Role,
        decision: Decision.Reject
    ): JsonElement =
        when (decision.gate) {
            GateId.DEPENDENCY ->
                successResponse(
                    buildJsonObject {
                        put("recommendation", JsonPrimitive("Blocked"))
                        put("currentRole", JsonPrimitive(role.toJsonString()))
                        put(
                            "blockers",
                            JsonArray(
                                (decision.context as? RejectContext.Dependency)?.unsatisfied.orEmpty().map { blocker ->
                                    buildJsonObject {
                                        put("fromItemId", JsonPrimitive(blocker.blockerId.toString()))
                                        put("currentRole", JsonPrimitive(blocker.role?.toJsonString() ?: BlockerInfo.UNKNOWN_ROLE))
                                        put("requiredRole", JsonPrimitive(blocker.threshold.toJsonString()))
                                    }
                                }
                            )
                        )
                    }
                )
            GateId.NOTE ->
                successResponse(
                    buildJsonObject {
                        put("recommendation", JsonPrimitive("Blocked"))
                        put("currentRole", JsonPrimitive(role.toJsonString()))
                        put("reason", JsonPrimitive("gate_blocked"))
                        put(
                            "missingNotes",
                            JsonArray((decision.context as? RejectContext.Notes)?.missing.orEmpty().map { JsonPrimitive(it.key) })
                        )
                    }
                )
            GateId.LEASE ->
                successResponse(
                    buildJsonObject {
                        put("recommendation", JsonPrimitive("Blocked"))
                        put("currentRole", JsonPrimitive(role.toJsonString()))
                        put("reason", JsonPrimitive("resource_unavailable"))
                        put(
                            "contendedResources",
                            JsonArray((decision.context as? RejectContext.Lease)?.contended.orEmpty().map { JsonPrimitive(it) })
                        )
                    }
                )
            else -> errorResponse(decision.error.message, LegacyMcpCode.OPERATION_FAILED, cause = decision.error)
        }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) return "get_next_status failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val rec = data?.get("recommendation")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        val role = data?.get("currentRole")?.let { (it as? JsonPrimitive)?.content } ?: ""
        return "Recommendation: $rec (current: $role)"
    }
}
