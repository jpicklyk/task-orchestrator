package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** A `config` `get` element: the head of a root's config, or one stored version of it. */
@Serializable
data class ConfigGetRequest(
    @Doc(ConfigDocs.ROOT_ID) val rootId: IdRef,
    @Doc(ConfigDocs.VERSION) val version: Int? = null,
    @Doc("Content fingerprint to classify against the root's history; adds relation: current, superseded or unknown.")
    val fingerprint: String? = null
)

internal val configGetOp =
    OpSpec<ConfigGetRequest, JsonElement>(
        name = "get",
        doc =
            "Read a root's config document: the head, or the given version (a reset version reads as an empty document). " +
                "A root with no config, or a reset head, is not_found.",
        profiles = orchestration(),
        capability = ApiCapability.READ,
        request = serializer<ConfigGetRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        execute = { env, ctx, req ->
            if (req.version != null && req.version < 1) {
                return@OpSpec Outcome.Err(invalidField("/version", "must be 1 or greater", req.version.toString()))
            }
            val rootId =
                when (val r = ConfigSupport.resolveRoot(env, ctx, req.rootId)) {
                    is Outcome.Err -> return@OpSpec r
                    is Outcome.Ok -> r.value
                }
            val service = ConfigSupport.service(env)
            ConfigSupport.guarded {
                val version = service.get(rootId, req.version) ?: return@guarded Outcome.Err(ConfigSupport.configNotFound(rootId))
                val relation = req.fingerprint?.let { service.classifyRelation(rootId, it) }
                Outcome.Ok(
                    buildJsonObject {
                        put("rootId", rootId.toString())
                        put("version", version.version)
                        put("fingerprint", version.fingerprint)
                        put("source", version.source.wire)
                        put("createdAt", version.createdAt.toString())
                        put("configYaml", version.yaml)
                        relation?.let { put("relation", it) }
                    }
                )
            }
        }
    )
