package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.run.DecisionAnswerCommand
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsAnswerRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write answer: a deprecated alias of `decisions_write answer`. */
internal val runsAnswerOp =
    OpSpec<RunToolServices, RunsAnswerRequest, JsonObject, RunStoreProvider>(
        name = "answer",
        doc = writeAliasDoc("answer", ANSWER_DOC),
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
        execute = { env, ctx, req ->
            answerDecision(env, ctx, req.decisionId, DecisionAnswerCommand(req.option, req.text, req.rationale))
        }
    )
