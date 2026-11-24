package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.runs.RunsWithdrawRequest
import io.github.jpicklyk.mcptask.current.application.port.RunStoreProvider
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write withdraw: withdraw a pending decision. */
internal val runsWithdrawOp =
    OpSpec<RunToolServices, RunsWithdrawRequest, JsonObject, RunStoreProvider>(
        name = "withdraw",
        doc =
            "Withdraw an open or acknowledged decision: its raiser (the same principal and seat) or the run " +
                "token of its run. An answered or withdrawn decision is decision_closed. The dedupeKey is then free for a " +
                "new decision. Returns the decision.",
        profiles = orchestration() + director(),
        capability = ApiCapability.RUNS,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<RunsWithdrawRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT
            ),
        execute = { env, ctx, req -> withdrawDecision(env, ctx, req) }
    )

private suspend fun WriteScope<RunStoreProvider>.withdrawDecision(
    env: RunToolServices,
    ctx: CallContext,
    req: RunsWithdrawRequest
): Outcome<JsonObject> {
    val decision =
        when (val r = DecisionLoader.load(stores, ctx, req.decisionId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val caller = DecisionCallers.of(ctx)
    val stored =
        when (val r = env.decisionService.withdraw(decision.id, caller)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(DecisionView.full(stored, env.clock.now(), ctx.principalsVisible))
}
