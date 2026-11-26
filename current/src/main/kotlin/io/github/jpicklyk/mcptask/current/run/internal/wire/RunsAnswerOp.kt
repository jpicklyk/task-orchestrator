package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.run.DecisionAnswerCommand
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionCallers
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionLoader
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsAnswerRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write answer: answer a pending decision. */
internal val runsAnswerOp =
    OpSpec<RunToolServices, RunsAnswerRequest, JsonObject, RunStoreProvider>(
        name = "answer",
        doc =
            "Answer an open or acknowledged decision with an option key (text only for a freeform class) and an " +
                "optional rationale: the answerers of its class only, as for ack. The answer is final and records the " +
                "caller's kind, proof, seat and profile; a second answer is decision_closed. Returns the decision. " +
                "Answering an attempt-cap decision acts on its paused run (allow_one, skip_item, stop); a run-budget decision left by 4.0.0 resumes on extend and closes on stop.",
        profiles = director(),
        capability = ApiCapability.DECIDE,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<RunsAnswerRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> answerDecision(env, ctx, req) }
    )

private suspend fun WriteScope<RunStoreProvider>.answerDecision(
    env: RunToolServices,
    ctx: CallContext,
    req: RunsAnswerRequest
): Outcome<JsonObject> {
    val decision =
        when (val r = DecisionLoader.load(stores, ctx, req.decisionId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val caller = DecisionCallers.of(ctx)
    val stored =
        when (
            val r =
                env.decisionService.answer(
                    decision.id,
                    DecisionAnswerCommand(req.option, req.text, req.rationale),
                    caller
                )
        ) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(DecisionView.full(stored, env.clock.now(), ctx.principalsVisible))
}
