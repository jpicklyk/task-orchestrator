package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsGetRequest
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** `runs_read get`: one run. */
internal val runsGetSelect =
    SelectSpec.Single<RunsGetRequest, JsonObject>(
        name = "get",
        doc =
            "Read one run by id (a UUID or a hex prefix of 8 to 32 characters). A run whose root is outside the call " +
                "scope is forbidden, not not_found.",
        profiles = orchestration() + director(),
        request = serializer<RunsGetRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> getRun(env, ctx, req) },
        data = { it }
    )

private suspend fun getRun(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsGetRequest
): Outcome<JsonObject> =
    env.unitOfWork.read {
        when (val run = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Err -> run
            is Outcome.Ok -> Outcome.Ok(RunsSupport.view(env, stores, run.value))
        }
    }
