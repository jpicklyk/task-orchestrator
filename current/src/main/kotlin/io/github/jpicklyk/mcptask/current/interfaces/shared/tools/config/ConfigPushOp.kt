package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.config.evictConfigSession
import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** A `config` `push` element: validate a document and append it as the root's next config version. */
@Serializable
data class ConfigPushRequest(
    @Doc(ConfigDocs.ROOT_ID) val rootId: IdRef,
    @Doc(ConfigDocs.CONFIG_YAML) val configYaml: String,
    @Doc("Skip the project.rootId mismatch and older-content guards (default false). Never skips validation or expectedVersion.")
    val force: Boolean = false,
    @Doc(ConfigDocs.EXPECTED_VERSION) val expectedVersion: Int? = null
)

internal val configPushOp =
    OpSpec<ConfigPushRequest, JsonElement>(
        name = "push",
        doc =
            "Validate a config document and append it as the root's next version (an identical re-push appends nothing, " +
                "appended false). Content an earlier version held is version_conflict unless forced; a document that " +
                "fails parsing or validation is config_invalid and nothing is stored.",
        profile = ToolProfile.ORCHESTRATION,
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<ConfigPushRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.CONFIG_INVALID,
                ErrorCode.PAYLOAD_TOO_LARGE,
                ErrorCode.VERSION_CONFLICT
            ),
        execute = { env, ctx, req ->
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
                    service.push(rootId, req.configYaml, req.force, expectedVersion = req.expectedVersion)
                when (result) {
                    is ConfigPushResult.Success -> {
                        if (result.appended) evictConfigSession(rootId)
                        val warnings = listOfNotNull(result.warning) + result.schemaWarnings
                        Outcome.Ok(
                            buildJsonObject {
                                put("rootId", rootId.toString())
                                put("version", result.version)
                                put("fingerprint", result.fingerprint)
                                put("appended", result.appended)
                                put("createdAt", result.updatedAt.toString())
                                if (warnings.isNotEmpty()) put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
                            }
                        )
                    }
                    is ConfigPushResult.NotFound -> Outcome.Err(ConfigSupport.notFound(EntityKind.ROOT, result.rootItemId.toString()))
                    is ConfigPushResult.NotDepthZero ->
                        Outcome.Err(invalidField("/rootId", "must reference a depth-0 (root) item; it has depth ${result.depth}"))
                    is ConfigPushResult.TooLarge -> Outcome.Err(ConfigSupport.payloadTooLarge(result.maxBytes, result.sizeBytes))
                    is ConfigPushResult.ParseError ->
                        Outcome.Err(ConfigSupport.configInvalid(ConfigSupport.parseViolations(result.detail, result.violations)))
                    is ConfigPushResult.Invalid -> Outcome.Err(ConfigSupport.configInvalid(result.errors))
                    is ConfigPushResult.RootIdMismatch ->
                        Outcome.Err(
                            ConfigSupport.configInvalid(listOf(ConfigSupport.rootIdMismatch(result.targetRootId, result.embeddedRootId)))
                        )
                    is ConfigPushResult.Superseded ->
                        Outcome.Err(ConfigSupport.versionConflict(rootId, result.matchedVersion, result.currentVersion))
                    is ConfigPushResult.PreconditionFailed ->
                        Outcome.Err(ConfigSupport.versionConflict(rootId, req.expectedVersion ?: 0, result.currentVersion))
                    is ConfigPushResult.RepositoryError -> Outcome.Err(result.error)
                }
            }
        }
    )
