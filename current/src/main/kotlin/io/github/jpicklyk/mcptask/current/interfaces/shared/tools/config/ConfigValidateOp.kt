package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.service.ConfigImpactService
import io.github.jpicklyk.mcptask.current.application.service.ConfigValidateResult
import io.github.jpicklyk.mcptask.current.application.service.ImpactSide
import io.github.jpicklyk.mcptask.current.application.service.InFlightImpact
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import io.github.jpicklyk.mcptask.current.application.config.ConfigViolation as AppViolation

/** A `config` `validate` element: a dry run of `push`. */
@Serializable
data class ConfigValidateRequest(
    @Doc(ConfigDocs.ROOT_ID) val rootId: IdRef,
    @Doc(ConfigDocs.CONFIG_YAML) val configYaml: String
)

internal val configValidateOp =
    OpSpec<ConfigValidateRequest, JsonElement>(
        name = "validate",
        doc =
            "Dry-run push: parse and validate a document and diff it against the head; nothing is stored. Problems are " +
                "reported in the result (valid false, errors), not as an error.",
        profile = ToolProfile.ORCHESTRATION,
        capability = ApiCapability.READ,
        request = serializer<ConfigValidateRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.PAYLOAD_TOO_LARGE),
        execute = { env, ctx, req ->
            val rootId =
                when (val r = ConfigSupport.resolveRoot(env, ctx, req.rootId)) {
                    is Outcome.Err -> return@OpSpec r
                    is Outcome.Ok -> r.value
                }
            val service = ConfigSupport.service(env)

            fun report(
                errors: List<AppViolation>,
                warnings: List<AppViolation>,
                diff: JsonElement? = null,
                affected: JsonElement? = null
            ): Outcome<JsonElement> =
                Outcome.Ok(
                    buildJsonObject {
                        put("rootId", rootId.toString())
                        put("valid", errors.isEmpty())
                        put("errors", JsonArray(errors.map(ConfigSupport::violationJson)))
                        put("warnings", JsonArray(warnings.map(ConfigSupport::violationJson)))
                        diff?.let { put("diff", it) }
                        affected?.let { put("affectedInFlight", it) }
                    }
                )

            ConfigSupport.guarded {
                when (val result = service.validate(rootId, req.configYaml)) {
                    is ConfigValidateResult.Checked -> {
                        val impact =
                            result.candidate
                                ?.takeIf { result.isValid }
                                ?.let { ConfigImpactService(env.configResolver, env.unitOfWork).affectedInFlight(rootId, it, ctx.scope) }
                        report(result.errors, result.warnings, result.diff?.let(ConfigSupport::diffJson), impact?.let(::impactJson))
                    }
                    is ConfigValidateResult.ParseError ->
                        report(ConfigSupport.parseViolations(result.detail, result.violations), emptyList())
                    is ConfigValidateResult.RootIdMismatch ->
                        report(listOf(ConfigSupport.rootIdMismatch(result.targetRootId, result.embeddedRootId)), emptyList())
                    is ConfigValidateResult.TooLarge -> Outcome.Err(ConfigSupport.payloadTooLarge(result.maxBytes, result.sizeBytes))
                    is ConfigValidateResult.NotFound -> Outcome.Err(ConfigSupport.notFound(EntityKind.ROOT, result.rootItemId.toString()))
                    is ConfigValidateResult.NotDepthZero ->
                        Outcome.Err(invalidField("/rootId", "must reference a depth-0 (root) item; it has depth ${result.depth}"))
                    is ConfigValidateResult.RepositoryError -> Outcome.Err(result.error)
                }
            }
        }
    )

private fun layerWire(source: ConfigSource): String = if (source == ConfigSource.PER_ROOT) "root" else "server"

private fun sideJson(side: ImpactSide?): JsonElement =
    if (side?.key == null || side.layer == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("key", side.key)
            put("layer", layerWire(side.layer))
        }
    }

private fun impactJson(impact: InFlightImpact): JsonObject =
    buildJsonObject {
        put("count", impact.count)
        put(
            "sample",
            JsonArray(
                impact.sample.map { item ->
                    buildJsonObject {
                        put("itemId", item.itemId.toString())
                        put("changedNotes", JsonArray(item.changedNotes.map { JsonPrimitive(it) }))
                        if (item.schemaChanged) {
                            put(
                                "schema",
                                buildJsonObject {
                                    put("from", sideJson(item.schemaFrom))
                                    put("to", sideJson(item.schemaTo))
                                }
                            )
                        }
                        if (item.lifecycleChanged) {
                            put(
                                "lifecycle",
                                buildJsonObject {
                                    put("from", item.lifecycleFrom?.lowercase()?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("to", item.lifecycleTo?.lowercase()?.let { JsonPrimitive(it) } ?: JsonNull)
                                }
                            )
                        }
                    }
                }
            )
        )
    }
