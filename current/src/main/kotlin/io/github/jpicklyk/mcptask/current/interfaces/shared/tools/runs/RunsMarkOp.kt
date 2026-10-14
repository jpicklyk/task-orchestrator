package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsMarkRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** runs_write mark: REST only; the first op on no MCP profile. */
internal val runsMarkOp =
    OpSpec<RunsMarkRequest, JsonObject>(
        name = "mark",
        doc =
            "Set a named mark on a run to now. REST only. Allowed in every status, including closed; the first write " +
                "of a key wins and a replay changes nothing (marked false). A run holds at most 32 marks. Returns the " +
                "run and marked.",
        profiles = emptySet(),
        capability = ApiCapability.RUNS,
        request = serializer<RunsMarkRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        execute = { env, ctx, req -> markRun(env, ctx, req) }
    )

private suspend fun WriteScope.markRun(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsMarkRequest
): Outcome<JsonObject> {
    val run: Run =
        when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    if (!RunsSupport.MARK_KEY.matches(req.key)) {
        return Outcome.Err(
            RunsSupport.invalid("/key", "must be lowercase letters, digits, - or _, starting with a letter, at most 64 characters", req.key)
        )
    }
    val result =
        when (val r = RunsSupport.wire(env.runService.mark(run.id, req.key))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val view = RunsSupport.view(env, stores, result.run)
    return Outcome.Ok(
        buildJsonObject {
            for ((key, value) in view) put(key, value)
            put("marked", result.marked)
        }
    )
}
