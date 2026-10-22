package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsDecisionRequest
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** `runs_read decision`: one decision request. */
internal val runsDecisionSelect =
    SelectSpec.Single<RunsDecisionRequest, JsonObject>(
        name = "decision",
        doc =
            "Read one decision by id (a UUID or a hex prefix of 8 to 32 characters): its question, options, " +
                "recommendation, links, context, who raised it (kind and seat), the acknowledgement and the answer " +
                "with its provenance (kind, proof status, seat, run, profile). A decision whose root is outside the " +
                "call scope is forbidden, not not_found.",
        profiles = director(),
        request = serializer<RunsDecisionRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> getDecision(env, ctx, req) },
        data = { it }
    )

private suspend fun getDecision(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsDecisionRequest
): Outcome<JsonObject> =
    env.unitOfWork.read {
        when (val decision = DecisionLoader.load(stores, ctx, req.decisionId)) {
            is Outcome.Err -> decision
            is Outcome.Ok -> Outcome.Ok(DecisionView.full(decision.value, env.clock.now(), ctx.principalsVisible))
        }
    }
