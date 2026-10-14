package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsAssignRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.run.SeatAssignCommand
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** runs_write assign: the coordinator assigns a seat of a member item. */
internal val runsAssignOp =
    OpSpec<RunsAssignRequest, JsonObject>(
        name = "assign",
        doc =
            "Assign a seat of a member item: a seat of the item's schema, or implementer or planner for an item with " +
                "none. Coordinator token only. Re-assigning an active seat supersedes it (its token dies and its claims " +
                "are released) and counts a new attempt. Returns assignmentId, seatToken and attempt; the token is " +
                "shown once and every call made under the seat passes it as the top-level seatToken.",
        profiles = orchestration(),
        capability = ApiCapability.RUNS,
        request = serializer<RunsAssignRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.RUN_CLOSED,
                ErrorCode.RUN_PAUSED,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> assignSeat(env, ctx, req) }
    )

/** How long a caller waits before retrying when the project config could not be read. */
private const val CONFIG_RETRY_MS = 1000L

private suspend fun WriteScope.assignSeat(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsAssignRequest
): Outcome<JsonObject> {
    val run: Run =
        when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    if (!RunPermissions.mayAssign(ctx, run)) return Outcome.Err(RunPermissions.refuse(RunPermissions.ASSIGN_REQUIRED))
    val itemId =
        when (val r = ScopedIds.resolve(stores.workItemRepository(), ctx.scope, req.itemId, "/itemId")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val command =
        SeatAssignCommand(
            runId = run.id,
            itemId = itemId,
            seat = req.seat,
            hints = req.hints.orEmpty(),
            principalId = RunPermissions.callerId(ctx)
        )
    val issued =
        try {
            when (val r = env.seatService.assign(command)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        } catch (e: PerRootConfigUnavailableException) {
            return Outcome.Err(
                DomainError(
                    code = ErrorCode.UNAVAILABLE,
                    message = "The project config could not be read; retry shortly.",
                    detail = ErrorDetail.Unavailable(CONFIG_RETRY_MS)
                )
            )
        }
    return Outcome.Ok(
        buildJsonObject {
            put("assignmentId", issued.seat.id.toString())
            put("seatToken", issued.token)
            put("attempt", issued.seat.attempt)
        }
    )
}
