package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.tool.SelectSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsGetRequest
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** `runs_read get`: one run. */
internal val runsGetSelect =
    SelectSpec.Single<RunToolServices, RunsGetRequest, JsonObject>(
        name = "get",
        doc =
            "Read one run by id (a UUID or a hex prefix of 8 to 32 characters). A run whose root is outside the call " +
                "scope is forbidden, not not_found.",
        profiles = orchestration() + director(),
        services = RunToolServices::class,
        request = serializer<RunsGetRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> getRun(env, ctx, req) },
        data = { it }
    )

private suspend fun getRun(
    env: RunToolServices,
    ctx: CallContext,
    req: RunsGetRequest
): Outcome<JsonObject> =
    env.unitOfWork.read {
        when (val run = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Err -> run
            is Outcome.Ok -> Outcome.Ok(RunsSupport.view(env, stores, run.value, ctx.scope))
        }
    }
