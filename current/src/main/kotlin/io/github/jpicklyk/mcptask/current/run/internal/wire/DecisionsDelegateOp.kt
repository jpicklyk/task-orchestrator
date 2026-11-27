package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionCallers
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionLoader
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionsDelegateRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** decisions_write delegate: route a pending decision to the director. */
internal val decisionsDelegateOp =
    OpSpec<RunToolServices, DecisionsDelegateRequest, JsonObject, RunStoreProvider>(
        name = "delegate",
        doc =
            "Route a pending decision to the director (waitingOn director): the answerers of its class only. A " +
                "human-only decision cannot be delegated. A decision already delegated is returned unchanged. Returns the " +
                "decision.",
        profiles = orchestration() + director(),
        capability = ApiCapability.DECIDE,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<DecisionsDelegateRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT
            ),
        execute = { env, ctx, req -> delegateDecision(env, ctx, req) }
    )

private suspend fun WriteScope<RunStoreProvider>.delegateDecision(
    env: RunToolServices,
    ctx: CallContext,
    req: DecisionsDelegateRequest
): Outcome<JsonObject> {
    val decision =
        when (val r = DecisionLoader.load(stores, ctx, req.decisionId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val caller = DecisionCallers.of(ctx)
    val stored =
        when (val r = env.decisionService.delegate(decision.id, caller)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(DecisionView.full(stored, env.clock.now(), ctx.principalsVisible))
}
