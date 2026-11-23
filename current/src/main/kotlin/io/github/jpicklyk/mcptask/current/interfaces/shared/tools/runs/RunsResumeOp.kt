package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.runs.RunsResumeRequest
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.Run
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write resume: a director or a human resumes a manually paused run. */
internal val runsResumeOp =
    OpSpec<RunToolServices, RunsResumeRequest, JsonObject>(
        name = "resume",
        doc =
            "Resume a manually paused run. Director or a human only. An attempts pause, or a run-budget pause left by 4.0.0, " +
                "cannot be resumed, only closed (invalid_run_state, allowed close). Returns the run.",
        profiles = director(),
        capability = ApiCapability.RUNS,
        services = RunToolServices::class,
        request = serializer<RunsResumeRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.RUN_CLOSED,
                ErrorCode.INVALID_RUN_STATE
            ),
        execute = { env, ctx, req -> resumeRun(env, ctx, req) }
    )

private suspend fun WriteScope.resumeRun(
    env: RunToolServices,
    ctx: CallContext,
    req: RunsResumeRequest
): Outcome<JsonObject> {
    val run: Run =
        when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    if (!RunPermissions.mayPauseOrResume(ctx)) return Outcome.Err(RunPermissions.refuse(RunPermissions.PAUSE_REQUIRED))
    val resumed =
        when (val r = RunsSupport.wire(env.runService.resume(run.id))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(RunsSupport.view(env, stores, resumed, ctx.scope))
}
