package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.Run
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsCloseRequest
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** runs_write close: ends a run and releases what it held. */
internal val runsCloseOp =
    OpSpec<RunToolServices, RunsCloseRequest, JsonObject, RunStoreProvider>(
        name = "close",
        doc =
            "End a run with a reason: completed, cancelled, failed or abandoned (budget_exhausted is server-only). " +
                "Run token, director or a human. Any reason but completed ends active seats and releases claims and " +
                "leases of the members; completed is refused while a seat is active. Returns the run and a snapshot of " +
                "what the close recorded.",
        destructive = true,
        profiles = orchestration() + director(),
        capability = ApiCapability.RUNS,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
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

private suspend fun WriteScope<RunStoreProvider>.closeRun(
    env: RunToolServices,
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
