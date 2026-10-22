package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsAckRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write ack: acknowledge an open decision. */
internal val runsAckOp =
    OpSpec<RunsAckRequest, JsonObject>(
        name = "ack",
        doc =
            "Acknowledge an open decision: the answerers of its class only (human-only: a human credential with a real " +
                "proof and no seat token; director-or-human: also the director profile without a seat token). An " +
                "acknowledged decision is returned unchanged; an answered or withdrawn one is decision_closed. Returns the " +
                "decision.",
        profiles = director(),
        capability = ApiCapability.DECIDE,
        request = serializer<RunsAckRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT
            ),
        execute = { env, ctx, req -> ackDecision(env, ctx, req) }
    )

private suspend fun WriteScope.ackDecision(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsAckRequest
): Outcome<JsonObject> {
    val decision =
        when (val r = DecisionLoader.load(stores, ctx, req.decisionId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val caller = DecisionCallers.of(ctx)
    val stored =
        when (val r = env.decisionService.acknowledge(decision.id, caller)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(DecisionView.full(stored, env.clock.now(), ctx.principalsVisible))
}
