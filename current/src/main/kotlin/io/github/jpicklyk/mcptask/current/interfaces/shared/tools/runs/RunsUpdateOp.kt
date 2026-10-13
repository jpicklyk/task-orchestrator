package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsUpdateRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.run.RunUpdateCommand
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write update: the coordinator adds or removes items, lowers the budget or replaces the hints. */
internal val runsUpdateOp =
    OpSpec<RunsUpdateRequest, JsonObject>(
        name = "update",
        doc =
            "Change an open run: add or remove items, lower the budget (a field can only go down or be newly set), " +
                "replace the hints. Coordinator only. A paused run is run_paused; a closed run is run_closed. " +
                "expectedVersion guards against a concurrent change. Returns the run.",
        profiles = orchestration(),
        capability = ApiCapability.RUNS,
        request = serializer<RunsUpdateRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DUPLICATE,
                ErrorCode.RUN_CLOSED,
                ErrorCode.RUN_PAUSED,
                ErrorCode.VERSION_CONFLICT
            ),
        execute = { env, ctx, req -> updateRun(env, ctx, req) }
    )

private suspend fun WriteScope.updateRun(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsUpdateRequest
): Outcome<JsonObject> {
    val run: Run =
        when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    if (!RunPermissions.mayUpdate(ctx, run)) return Outcome.Err(RunPermissions.refuse(RunPermissions.UPDATE_REQUIRED))
    val add =
        when (val r = resolveItems(this, ctx, req.addItems, "/addItems")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val remove =
        when (val r = resolveItems(this, ctx, req.removeItems, "/removeItems")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val budget =
        when (val r = RunsSupport.budget(req.budget, "/budget")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val command = RunUpdateCommand(add, remove, budget, req.hints, req.expectedVersion)
    val updated =
        when (val r = RunsSupport.wire(env.runService.update(run.id, command))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(RunsSupport.view(env, stores, updated))
}
