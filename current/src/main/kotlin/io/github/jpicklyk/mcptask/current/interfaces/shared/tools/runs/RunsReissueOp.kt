package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.runs.RunsReissueRequest
import io.github.jpicklyk.mcptask.current.application.port.RunStoreProvider
import io.github.jpicklyk.mcptask.current.application.run.ReissuedOrchestrator
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.RunWireKeys.putWithAlias
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.Run
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

/** runs_write reissue: a director or a human replaces a lost run token; the old one is refused from then on. */
internal val runsReissueOp =
    OpSpec<RunToolServices, RunsReissueRequest, JsonObject, RunStoreProvider>(
        name = "reissue",
        doc =
            "Issue a new run token for an open or paused run; the old one is refused from then on (of concurrent " +
                "reissues the last wins). Director or a human only. Returns the run, orchestratorToken and " +
                "supersededSeenAt (when the old token was seen).",
        profiles = director(),
        capability = ApiCapability.RUNS,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<RunsReissueRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.RUN_CLOSED
            ),
        execute = { env, ctx, req -> reissueRunToken(env, ctx, req) }
    )

private suspend fun WriteScope<RunStoreProvider>.reissueRunToken(
    env: RunToolServices,
    ctx: CallContext,
    req: RunsReissueRequest
): Outcome<JsonObject> {
    val run: Run =
        when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    if (!RunPermissions.mayReissue(ctx)) return Outcome.Err(RunPermissions.refuse(RunPermissions.REISSUE_REQUIRED))
    val reissued: ReissuedOrchestrator =
        when (val r = env.seatService.reissueOrchestrator(run.id, RunPermissions.callerId(ctx))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val view = RunsSupport.view(env, stores, run, ctx.scope)
    return Outcome.Ok(
        buildJsonObject {
            for ((key, value) in view) put(key, value)
            putWithAlias(RunWireKeys.ORCHESTRATOR_TOKEN, JsonPrimitive(reissued.issued.token))
            reissued.supersededSeenAt?.let { put(SUPERSEDED_SEEN_AT, JsonPrimitive(it.toString())) }
        }
    )
}

private const val SUPERSEDED_SEEN_AT = "supersededSeenAt"
