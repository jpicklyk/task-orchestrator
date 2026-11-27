package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionCallers
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionLoader
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionsWithdrawRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** decisions_write withdraw: withdraw a pending decision. */
internal val decisionsWithdrawOp =
    OpSpec<RunToolServices, DecisionsWithdrawRequest, JsonObject, RunStoreProvider>(
        name = "withdraw",
        doc = WITHDRAW_DOC,
        profiles = orchestration() + director(),
        capability = ApiCapability.RUNS,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<DecisionsWithdrawRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT
            ),
        execute = { env, ctx, req -> withdrawDecision(env, ctx, req.decisionId) }
    )

/** The `withdraw` op text, shared by `decisions_write` and its deprecated `runs_write` alias. */
internal const val WITHDRAW_DOC =
    "Withdraw an open or acknowledged decision: its raiser (the same principal and seat) or the run " +
        "token of its run. An answered or withdrawn decision is decision_closed. The dedupeKey is then free for a " +
        "new decision. Returns the decision."

/** Runs `decisions_write withdraw` for both tools: the one body of the op. */
internal suspend fun WriteScope<RunStoreProvider>.withdrawDecision(
    env: RunToolServices,
    ctx: CallContext,
    decisionId: IdRef
): Outcome<JsonObject> {
    val decision =
        when (val r = DecisionLoader.load(stores, ctx, decisionId)) {
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
