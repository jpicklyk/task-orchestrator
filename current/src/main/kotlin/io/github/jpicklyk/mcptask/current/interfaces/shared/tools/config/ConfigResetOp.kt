package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.config.evictConfigSession
import io.github.jpicklyk.mcptask.current.application.port.ConfigStoreProvider
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
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
    OpSpec<ConfigToolServices, ConfigResetRequest, JsonElement, ConfigStoreProvider>(
        name = "reset",
        doc =
            "Reset a root's config: append an empty reset version so the root falls back to the server config; history is " +
                "kept. A root with no config, or one already reset, appends nothing (appended false).",
        profiles = orchestration(),
        destructive = true,
        capability = ApiCapability.WRITE_CONFIG,
        services = ConfigToolServices::class,
        stores = ConfigStoreProvider::class,
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
