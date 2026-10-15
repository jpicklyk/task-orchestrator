package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsAnswerRequest
import io.github.jpicklyk.mcptask.current.application.decision.DecisionAnswerCommand
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write answer: answer a pending decision. */
internal val runsAnswerOp =
    OpSpec<RunsAnswerRequest, JsonObject>(
        name = "answer",
        doc =
            "Answer an open or acknowledged decision with an option key (text only for a freeform class) and an " +
                "optional rationale: the answerers of its class only, as for ack. The answer is final and records the " +
                "caller's kind, proof, seat and profile; a second answer is decision_closed. Returns the decision.",
        profiles = director(),
        capability = ApiCapability.DECIDE,
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

private suspend fun WriteScope.answerDecision(
    env: ToolExecutionContext,
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
        when (val r = env.decisionService.answer(decision.id, DecisionAnswerCommand(req.option, req.text, req.rationale), caller)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(DecisionView.full(stored, env.clock.now(), ctx.principalsVisible))
}
