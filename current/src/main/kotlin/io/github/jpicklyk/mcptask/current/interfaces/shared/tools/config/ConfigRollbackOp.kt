package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.config.evictConfigSession
import io.github.jpicklyk.mcptask.current.application.service.ConfigRollbackResult
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/** A `config` `rollback` element: append a copy of an earlier version as the new head. */
@Serializable
data class ConfigRollbackRequest(
    @Doc(ConfigDocs.ROOT_ID) val rootId: IdRef,
    @Doc(ConfigDocs.VERSION) val version: Int,
    @Doc(ConfigDocs.EXPECTED_VERSION) val expectedVersion: Int? = null
)

internal val configRollbackOp =
    OpSpec<ConfigRollbackRequest, JsonElement>(
        name = "rollback",
        doc =
            "Append a copy of an earlier version as the new head (source rollback, history kept). Rolling back to the " +
                "head's own content appends nothing (appended false). An absent version, or a root with no config, is not_found.",
        profiles = orchestration(),
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<ConfigRollbackRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.VERSION_CONFLICT),
        execute = { env, ctx, req ->
            if (req.version < 1) {
                return@OpSpec Outcome.Err(invalidField("/version", "must be 1 or greater", req.version.toString()))
            }
            if (req.expectedVersion != null && req.expectedVersion < 1) {
                return@OpSpec Outcome.Err(invalidField("/expectedVersion", "must be 1 or greater", req.expectedVersion.toString()))
            }
            val rootId =
                when (val r = ConfigSupport.resolveRoot(env, ctx, req.rootId)) {
                    is Outcome.Err -> return@OpSpec r
                    is Outcome.Ok -> r.value
                }
            val service = ConfigSupport.service(env)
            ConfigSupport.guarded {
                val result =
                    service.rollback(rootId, req.version, expectedVersion = req.expectedVersion)
                when (result) {
                    is ConfigRollbackResult.Success -> {
                        if (result.appended) evictConfigSession(rootId)
                        Outcome.Ok(ConfigSupport.versionJson(rootId, result.version, result.appended))
                    }
                    is ConfigRollbackResult.NotFound -> Outcome.Err(ConfigSupport.configNotFound(rootId))
                    is ConfigRollbackResult.PreconditionFailed ->
                        Outcome.Err(ConfigSupport.versionConflict(rootId, req.expectedVersion ?: 0, result.currentVersion))
                    is ConfigRollbackResult.RepositoryError -> Outcome.Err(result.error)
                }
            }
        }
    )
