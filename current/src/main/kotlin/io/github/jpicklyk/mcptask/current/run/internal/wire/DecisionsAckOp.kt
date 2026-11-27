package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionCallers
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionLoader
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionsAckRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** decisions_write ack: acknowledge an open decision. */
internal val decisionsAckOp =
    OpSpec<RunToolServices, DecisionsAckRequest, JsonObject, RunStoreProvider>(
        name = "ack",
        doc = ACK_DOC,
        profiles = director(),
        capability = ApiCapability.DECIDE,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<DecisionsAckRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT
            ),
        execute = { env, ctx, req -> ackDecision(env, ctx, req.decisionId) }
    )

/** The `ack` op text, shared by `decisions_write` and its deprecated `runs_write` alias. */
internal const val ACK_DOC =
    "Acknowledge an open decision: the answerers of its class only. An acknowledged decision is returned " +
        "unchanged; an answered or withdrawn one is decision_closed. Returns the decision."

/** Runs `decisions_write ack` for both tools: the one body of the op. */
internal suspend fun WriteScope<RunStoreProvider>.ackDecision(
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
        when (val r = env.decisionService.acknowledge(decision.id, caller)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(DecisionView.full(stored, env.clock.now(), ctx.principalsVisible))
}
