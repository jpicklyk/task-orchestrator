package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.run.DecisionAnswerCommand
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionCallers
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionLoader
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionsAnswerRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** decisions_write answer: answer a pending decision. */
internal val decisionsAnswerOp =
    OpSpec<RunToolServices, DecisionsAnswerRequest, JsonObject, RunStoreProvider>(
        name = "answer",
        doc = ANSWER_DOC,
        profiles = director(),
        capability = ApiCapability.DECIDE,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<DecisionsAnswerRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req ->
            when (val links = links(req.links)) {
                is Outcome.Err -> links
                is Outcome.Ok ->
                    answerDecision(
                        env,
                        ctx,
                        req.decisionId,
                        DecisionAnswerCommand(req.option, req.text, req.rationale, req.decidedBy?.domain, links.value)
                    )
            }
        }
    )

/** The `answer` op text, shared by `decisions_write` and its deprecated `runs_write` alias. */
internal const val ANSWER_DOC =
    "Answer an open or acknowledged decision with an option key (text only for a freeform class) and an " +
        "optional rationale: the answerers of its class only, as for ack. The answer is final and records the " +
        "caller's kind, proof, seat and profile; a second answer is decision_closed. Returns the decision. " +
        "Answering an attempt-cap decision acts on its paused run (allow_one, skip_item, stop); a run-budget decision left by 4.0.0 resumes on extend and closes on stop."

/** Answers decision [decisionId] with [command] for both tools: the one body of the op. */
internal suspend fun WriteScope<RunStoreProvider>.answerDecision(
    env: RunToolServices,
    ctx: CallContext,
    decisionId: IdRef,
    command: DecisionAnswerCommand
): Outcome<JsonObject> {
    val decision =
        when (val r = DecisionLoader.load(stores, ctx, decisionId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val caller = DecisionCallers.of(ctx)
    val stored =
        when (val r = env.decisionService.answer(decision.id, command, caller)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(DecisionView.full(stored, env.clock.now(), ctx.principalsVisible))
}
