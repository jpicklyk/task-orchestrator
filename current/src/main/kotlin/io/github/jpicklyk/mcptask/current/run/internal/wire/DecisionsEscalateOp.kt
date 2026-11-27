package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionCallers
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionLoader
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionsEscalateRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** decisions_write escalate: route a pending decision to the user. */
internal val decisionsEscalateOp =
    OpSpec<RunToolServices, DecisionsEscalateRequest, JsonObject, RunStoreProvider>(
        name = "escalate",
        doc =
            "Route a pending decision to the user (waitingOn user) with a reason: the answerers of its class, or the " +
                "director, which may hand a human-only decision to the user. The same routing with the same reason is " +
                "returned unchanged; a different reason updates it. Returns the decision.",
        profiles = director(),
        capability = ApiCapability.DECIDE,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<DecisionsEscalateRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT
            ),
        execute = { env, ctx, req -> escalateDecision(env, ctx, req) }
    )

private suspend fun WriteScope<RunStoreProvider>.escalateDecision(
    env: RunToolServices,
    ctx: CallContext,
    req: DecisionsEscalateRequest
): Outcome<JsonObject> {
    val decision =
        when (val r = DecisionLoader.load(stores, ctx, req.decisionId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val caller = DecisionCallers.of(ctx)
    val stored =
        when (val r = env.decisionService.escalate(decision.id, req.reason, caller)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(DecisionView.full(stored, env.clock.now(), ctx.principalsVisible))
}
