package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsAckRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** runs_write ack: a deprecated alias of `decisions_write ack`. */
internal val runsAckOp =
    OpSpec<RunToolServices, RunsAckRequest, JsonObject, RunStoreProvider>(
        name = "ack",
        doc = writeAliasDoc("ack", ACK_DOC),
        profiles = director(),
        capability = ApiCapability.DECIDE,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<RunsAckRequest>(),
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
