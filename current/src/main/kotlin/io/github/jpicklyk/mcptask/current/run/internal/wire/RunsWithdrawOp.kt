package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionsWithdrawRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write withdraw: a deprecated alias of `decisions_write withdraw`. */
internal val runsWithdrawOp =
    OpSpec<RunToolServices, DecisionsWithdrawRequest, JsonObject, RunStoreProvider>(
        name = "withdraw",
        doc = writeAliasDoc("withdraw", WITHDRAW_DOC),
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
