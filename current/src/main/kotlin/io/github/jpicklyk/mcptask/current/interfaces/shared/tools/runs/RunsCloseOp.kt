package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsCloseRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** runs_write close: ends a run and releases what it held. */
internal val runsCloseOp =
    OpSpec<RunsCloseRequest, JsonObject>(
        name = "close",
        doc =
            "End a run with a reason: completed, cancelled, failed or abandoned (budget_exhausted is server-only). " +
                "Coordinator, director or a human. Any reason but completed ends active seats and releases claims and " +
                "leases of the members; completed is refused while a seat is active. Returns the run and a snapshot of " +
                "what the close recorded.",
        destructive = true,
        profiles = orchestration() + director(),
        capability = ApiCapability.RUNS,
        request = serializer<RunsCloseRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.RUN_CLOSED,
                ErrorCode.INVALID_RUN_STATE
            ),
        execute = { env, ctx, req -> closeRun(env, ctx, req) }
    )

private suspend fun WriteScope.closeRun(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsCloseRequest
): Outcome<JsonObject> {
    val run: Run =
        when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    if (!RunPermissions.mayClose(ctx, run)) return Outcome.Err(RunPermissions.refuse(RunPermissions.CLOSE_REQUIRED))
    val result =
        when (val r = RunsSupport.wire(env.runService.close(run.id, req.reason.domain, RunPermissions.callerId(ctx)))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val view = RunsSupport.view(env, stores, result.run, ctx.scope)
    return Outcome.Ok(
        buildJsonObject {
            for ((key, value) in view) put(key, value)
            put("snapshot", RunView.snapshot(result.snapshot))
        }
    )
}
