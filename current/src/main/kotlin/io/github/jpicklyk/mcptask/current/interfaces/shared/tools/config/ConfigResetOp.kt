package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.config.evictConfigSession
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** A `config` `reset` element: drop a root's config back to the server defaults, keeping history. */
@Serializable
data class ConfigResetRequest(
    @Doc(ConfigDocs.ROOT_ID) val rootId: IdRef
)

internal val configResetOp =
    OpSpec<ConfigResetRequest, JsonElement>(
        name = "reset",
        doc =
            "Reset a root's config: append an empty reset version so the root falls back to the server config; history is " +
                "kept. A root with no config, or one already reset, appends nothing (appended false).",
        profile = ToolProfile.ORCHESTRATION,
        destructive = true,
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<ConfigResetRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        execute = { env, ctx, req ->
            val rootId =
                when (val r = ConfigSupport.resolveRoot(env, ctx, req.rootId)) {
                    is Outcome.Err -> return@OpSpec r
                    is Outcome.Ok -> r.value
                }
            val service = ConfigSupport.service(env)
            ConfigSupport.guarded {
                when (val outcome = service.reset(rootId)) {
                    is Outcome.Err -> outcome
                    is Outcome.Ok -> {
                        if (outcome.value) evictConfigSession(rootId)
                        Outcome.Ok(
                            buildJsonObject {
                                put("rootId", rootId.toString())
                                put("appended", outcome.value)
                            }
                        )
                    }
                }
            }
        }
    )
