package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.SchemaMatch
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.DispatchProfileDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.EffectiveConfigDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.EffectiveSchemaDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteSchemaEntryDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ResourceRequirementDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.TraitDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.StatusGraphBuilder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.util.UUID

private val effectiveJson = Json
private val graphBuilder = StatusGraphBuilder()

/** A `config` `effective` element: the resolved config view of one root, or of the server set. */
@Serializable
data class ConfigEffectiveRequest(
    @Doc(ConfigDocs.ROOT_ID) val rootId: IdRef? = null
)

internal val configEffectiveOp =
    OpSpec<ConfigEffectiveRequest, JsonElement>(
        name = "effective",
        doc =
            "The resolved config view: every type's schema, the traits, the status graph and the layer fingerprints. With " +
                "rootId it is that root's config layered over the server's; without it, the server config alone.",
        profile = ToolProfile.ORCHESTRATION,
        capability = ApiCapability.READ,
        request = serializer<ConfigEffectiveRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.CONFIG_INVALID),
        execute = { env, ctx, req ->
            val rootId: UUID? =
                if (req.rootId == null) {
                    null
                } else {
                    when (val r = ConfigSupport.resolveRoot(env, ctx, req.rootId)) {
                        is Outcome.Err -> return@OpSpec r
                        is Outcome.Ok -> r.value
                    }
                }
            ConfigSupport.guarded {
                val layered = env.configResolver.layered(rootId)
                val entries = layered.typeKeys().mapNotNull { key -> layered.resolveTypeSchema(key)?.let { key to it } }
                val traits =
                    layered.traitNames().mapNotNull { name ->
                        val notes = layered.traitNotes(name) ?: return@mapNotNull null
                        traitDto(name, notes, layered.traitDispatch(name), layered.traitResources(name))
                    }
                val dto =
                    EffectiveConfigDto(
                        rootId = rootId?.toString(),
                        schemas = entries.map { (key, match) -> match.toDto(key) },
                        traits = traits,
                        types = entries.map { (key, _) -> key },
                        statusGraph =
                            graphBuilder.buildStatusGraph(
                                LinkedHashMap(
                                    entries.associate { (key, match) ->
                                        key to match.schema
                                    }
                                )
                            ),
                        defaultSchema = entries.firstOrNull { (key, _) -> key == "default" }?.second?.toDto("default"),
                        globalFingerprint = layered.global.fingerprint(),
                        perRootFingerprint = layered.perRoot?.fingerprint
                    )
                Outcome.Ok(effectiveJson.encodeToJsonElement(EffectiveConfigDto.serializer(), dto))
            }
        }
    )

private fun NoteSchemaEntry.toDto(): NoteSchemaEntryDto =
    NoteSchemaEntryDto(
        key = key,
        role = role.name.lowercase(),
        required = required,
        description = description,
        guidance = guidance,
        skill = skill,
        maxLength = maxLength
    )

private fun DispatchProfile.toDto(): DispatchProfileDto = DispatchProfileDto(agent = agent, model = model, effort = effort)

private fun ResourceRequirement.toDto(): ResourceRequirementDto =
    ResourceRequirementDto(key = key, mode = mode.name.lowercase(), ttlSeconds = ttlSeconds)

/** `dispatch` and `resources` are omitted (null), never empty, when the trait declares neither. */
private fun traitDto(
    name: String,
    notes: List<NoteSchemaEntry>,
    dispatch: Map<Role, DispatchProfile>,
    resources: List<ResourceRequirement>
): TraitDto =
    TraitDto(
        name = name,
        notes = notes.map { it.toDto() },
        dispatch =
            if (dispatch.isEmpty()) {
                null
            } else {
                dispatch.entries.associate { (role, profile) ->
                    role.name.lowercase() to
                        profile.toDto()
                }
            },
        resources = if (resources.isEmpty()) null else resources.map { it.toDto() }
    )

private fun SchemaMatch.toDto(queriedType: String): EffectiveSchemaDto =
    EffectiveSchemaDto(
        type = queriedType,
        matchedType = schema.type,
        configSource = if (source == ConfigSource.PER_ROOT) "per-root" else "global",
        configFingerprint = fingerprint,
        lifecycleMode = schema.lifecycleMode.name.lowercase(),
        hasReviewPhase = schema.hasReviewPhase(),
        notes = schema.notes.map { it.toDto() },
        defaultTraits = schema.defaultTraits
    )
