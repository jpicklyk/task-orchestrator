package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsWithdrawRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write withdraw: withdraw a pending decision. */
internal val runsWithdrawOp =
    OpSpec<RunsWithdrawRequest, JsonObject>(
        name = "withdraw",
        doc =
            "Withdraw an open or acknowledged decision: its raiser (the same principal and seat) or the coordinator " +
                "token of its run. An answered or withdrawn decision is decision_closed. The dedupeKey is then free for a " +
                "new decision. Returns the decision.",
        profiles = director(),
        capability = ApiCapability.RUNS,
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

private suspend fun WriteScope.withdrawDecision(
    env: ToolExecutionContext,
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
