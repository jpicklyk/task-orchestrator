package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

private const val MAX_HISTORY_LIMIT = 200

/** A `config` `history` element: version metadata of a root's config, newest first. */
@Serializable
data class ConfigHistoryRequest(
    @Doc(ConfigDocs.ROOT_ID) val rootId: IdRef,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc("Page back: only versions below this number (exclusive); pass the last version of the previous page.")
    val before: Int? = null
)

internal val configHistoryOp =
    OpSpec<ConfigHistoryRequest, JsonElement>(
        name = "history",
        doc = "List a root's config versions newest first, with metadata and no YAML. A root with no config returns no versions.",
        profile = ToolProfile.ORCHESTRATION,
        capability = ApiCapability.READ,
        request = serializer<ConfigHistoryRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        execute = { env, ctx, req ->
            if (req.limit != null && req.limit !in 1..MAX_HISTORY_LIMIT) {
                return@OpSpec Outcome.Err(invalidField("/limit", "must be between 1 and $MAX_HISTORY_LIMIT", req.limit.toString()))
            }
            val rootId =
                when (val r = ConfigSupport.resolveRoot(env, ctx, req.rootId)) {
                    is Outcome.Err -> return@OpSpec r
                    is Outcome.Ok -> r.value
                }
            val service = ConfigSupport.service(env)
            val (versions, head) =
                when (
                    val read =
                        ConfigSupport.guarded {
                            Outcome.Ok(
                                service.history(rootId, req.limit ?: ConfigService.DEFAULT_HISTORY_LIMIT, req.before) to
                                    service.headVersion(rootId)
                            )
                        }
                ) {
                    is Outcome.Err -> return@OpSpec read
                    is Outcome.Ok -> read.value
                }
            Outcome.Ok(
                buildJsonObject {
                    put("rootId", rootId.toString())
                    head?.let { put("headVersion", it) }
                    put(
                        "versions",
                        JsonArray(
                            versions.map { v ->
                                buildJsonObject {
                                    put("version", v.version)
                                    put("fingerprint", v.fingerprint)
                                    put("source", v.source.wire)
                                    put("createdAt", v.createdAt.toString())
                                    v.principalId?.let { put("principalId", it) }
                                    put("warningsCount", v.warnings.size)
                                    put("canonical", v.canonicalJson != null)
                                }
                            }
                        )
                    )
                }
            )
        }
    )
