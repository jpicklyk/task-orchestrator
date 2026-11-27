package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionsRaiseRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write raise: a deprecated alias of `decisions_write raise`. */
internal val runsRaiseOp =
    OpSpec<RunToolServices, DecisionsRaiseRequest, JsonObject, RunStoreProvider>(
        name = "raise",
        doc = writeAliasDoc("raise", RAISE_DOC),
        profiles = orchestration(),
        capability = ApiCapability.RUNS,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<DecisionsRaiseRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.IDEMPOTENCY_MISMATCH,
                ErrorCode.PAYLOAD_TOO_LARGE,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.SEAT_FORBIDDEN,
                ErrorCode.NOT_CLAIM_HOLDER
            ),
        execute = { env, ctx, req -> raiseDecision(env, ctx, req) }
    )
