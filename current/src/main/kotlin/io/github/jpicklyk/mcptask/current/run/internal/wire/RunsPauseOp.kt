package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.Run
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsPauseRequest
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write pause: a director or a human pauses an open run. */
internal val runsPauseOp =
    OpSpec<RunToolServices, RunsPauseRequest, JsonObject, RunStoreProvider>(
        name = "pause",
        doc =
            "Pause an open run (reason manual). Director or a human only; the opener of a run may not pause it. A run " +
                "that is not open is invalid_run_state. Returns the run.",
        profiles = director(),
        capability = ApiCapability.RUNS,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<RunsPauseRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.RUN_CLOSED,
                ErrorCode.INVALID_RUN_STATE
            ),
        execute = { env, ctx, req -> pauseRun(env, ctx, req) }
    )

private suspend fun WriteScope<RunStoreProvider>.pauseRun(
    env: RunToolServices,
    ctx: CallContext,
    req: RunsPauseRequest
): Outcome<JsonObject> {
    val run: Run =
        when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    if (!RunPermissions.mayPauseOrResume(ctx)) return Outcome.Err(RunPermissions.refuse(RunPermissions.PAUSE_REQUIRED))
    val paused =
        when (val r = RunsSupport.wire(env.runService.pause(run.id))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(RunsSupport.view(env, stores, paused, ctx.scope))
}
