package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.SchemaMatch
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.security.sha256Hex
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.EffectiveConfigDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.EffectiveSchemaDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.TraitDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.StatusGraphBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.UUID

/**
 * Registers the additive per-root **effective** config view under
 * `GET /api/v1/roots/{rootId}/config/effective` — the same per-root/global LAYERED view
 * [EffectiveConfigResolver] and MCP `query_items(schema, type=K, rootId=R)` already compute, surfaced
 * as one REST resource instead of requiring a dashboard to probe per type.
 *
 * Unlike `/config*` (`configRoutes`, GLOBAL-only) and `/roots/{rootId}/config` (`projectConfigRoutes`,
 * the RAW stored YAML), this route resolves every registered type against `rootId`'s layered config
 * and reports the RESOLVED base schema per type (no trait merging — same as `/config`'s `SchemaDto`
 * and the MCP type-path). It is purely additive: no existing `/config*` or
 * `/roots/{rootId}/config` route, body, or ETag changes.
 *
 * **Authorization/validation order:** [ApiCapability.READ] -> parse `{rootId}` (400) ->
 * [enforceScopeForItem] (403) -> root exists (404) -> root is depth-0 (422) -> exactly one
 * [EffectiveConfigResolver.layered] read, wrapped in the same `config_unavailable` 503 envelope
 * `ItemRoutes` uses for [PerRootConfigUnavailableException].
 *
 * [schemaService] is used ONLY to enumerate global type keys (`getAllSchemas().keys`) —
 * `GlobalConfigLookup` has no key-listing method — and to build the status graph via
 * [StatusGraphBuilder.buildStatusGraph]. Every resolved value comes from [configResolver]'s single
 * [EffectiveConfigResolver.layered] read; no separate default-schema probing or per-root-vs-global
 * choice happens in this route.
 */
fun Route.effectiveConfigRoutes(
    repositoryProvider: RepositoryProvider,
    configResolver: EffectiveConfigResolver,
    schemaService: WorkItemSchemaService,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val graphBuilder = StatusGraphBuilder(schemaService)

    requireCapability(ApiCapability.READ) {
        // ─── GET /roots/{rootId}/config/effective ────────────────────────────
        get("/roots/{rootId}/config/effective") {
            val rootId = call.parseEffectiveConfigRootId() ?: return@get

            if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for root $rootId"))
                return@get
            }

            val itemResult = workItemRepo.getById(rootId)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Root WorkItem not found: $rootId"))
                return@get
            }
            val item = (itemResult as Result.Success).data
            if (item.depth != 0) {
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ErrorDto(
                        "validation_error",
                        "rootId must reference a depth-0 (root) WorkItem; '$rootId' has depth ${item.depth}",
                    ),
                )
                return@get
            }

            val layered =
                try {
                    configResolver.layered(rootId)
                } catch (e: PerRootConfigUnavailableException) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorDto(PerRootConfigUnavailableException.CODE, e.message),
                    )
                    return@get
                }

            val globalFingerprint = layered.global.fingerprint()
            val perRootFingerprint = layered.perRoot?.fingerprint
            val etag = effectiveConfigEtag(globalFingerprint, perRootFingerprint)

            val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]?.trim()
            if (ifNoneMatch != null && ifNoneMatch == etag) {
                call.response.header(HttpHeaders.ETag, etag)
                call.respond(HttpStatusCode.NotModified)
                return@get
            }

            val keys =
                (
                    layered.perRoot
                        ?.document
                        ?.workItemSchemas
                        ?.keys
                        .orEmpty() + schemaService.getAllSchemas().keys
                ).toSortedSet()
            val entries = keys.mapNotNull { key -> layered.resolveTypeSchema(key)?.let { key to it } }

            val traits =
                layered.traitNames().mapNotNull { name ->
                    val notes = layered.traitNotes(name) ?: return@mapNotNull null
                    buildTraitDto(name, notes, layered.traitDispatch(name), layered.traitResources(name))
                }

            val schemaDtos = entries.map { (key, match) -> match.toEffectiveSchemaDto(key) }.sortedBy { it.type }
            val statusGraph =
                graphBuilder.buildStatusGraph(
                    LinkedHashMap(entries.associate { (key, match) -> key to match.schema }),
                )

            call.response.header(HttpHeaders.ETag, etag)
            call.respond(
                HttpStatusCode.OK,
                EffectiveConfigDto(
                    rootId = rootId.toString(),
                    schemas = schemaDtos,
                    traits = traits,
                    types = keys.toList(),
                    statusGraph = statusGraph,
                    defaultSchema =
                        entries.firstOrNull { (key, _) -> key == "default" }?.second?.toEffectiveSchemaDto("default"),
                    globalFingerprint = globalFingerprint,
                    perRootFingerprint = perRootFingerprint,
                ),
            )
        }
    }
}

/**
 * Composite ETag over both config layers:
 * `"eff-" + sha256Hex("global:" + (g ?: "-") + "\nper-root:" + (p ?: "-")) + "\""`.
 *
 * Distinct from `/config*` and `/roots/{rootId}/config`'s `"cfg-"` prefix so an effective ETag can
 * never be mistaken for a per-root-config `If-Match` fingerprint value.
 */
internal fun effectiveConfigEtag(
    globalFingerprint: String?,
    perRootFingerprint: String?,
): String {
    val material = "global:${globalFingerprint ?: "-"}\nper-root:${perRootFingerprint ?: "-"}"
    return "\"eff-${sha256Hex(material.toByteArray(Charsets.UTF_8))}\""
}

/**
 * Builds a [TraitDto] from the LAYERED per-trait facets (notes/dispatch/resources already resolved
 * against [rootId]'s config via [io.github.jpicklyk.mcptask.current.application.config.LayeredConfig]),
 * reusing [NoteSchemaEntry.toDto]/[DispatchProfile.toDto]/[ResourceRequirement.toDto] from
 * `ConfigRoutes.kt` (widened to `internal` for this purpose) for byte-identical field mapping.
 * `dispatch`/`resources` are omitted (null) rather than emitted empty — the same rule
 * `ConfigRoutes.buildTraitDto` applies for the GLOBAL-only view.
 */
private fun buildTraitDto(
    name: String,
    notes: List<NoteSchemaEntry>,
    dispatch: Map<Role, DispatchProfile>,
    resources: List<ResourceRequirement>,
): TraitDto =
    TraitDto(
        name = name,
        notes = notes.map { it.toDto() },
        dispatch =
            if (dispatch.isEmpty()) {
                null
            } else {
                dispatch.entries.associate { (role, profile) -> role.name.lowercase() to profile.toDto() }
            },
        resources = if (resources.isEmpty()) null else resources.map { it.toDto() },
    )

private fun SchemaMatch.toEffectiveSchemaDto(queriedType: String): EffectiveSchemaDto =
    EffectiveSchemaDto(
        type = queriedType,
        matchedType = schema.type,
        configSource = if (source == ConfigSource.PER_ROOT) "per-root" else "global",
        configFingerprint = fingerprint,
        lifecycleMode = schema.lifecycleMode.name.lowercase(),
        hasReviewPhase = schema.hasReviewPhase(),
        notes = schema.notes.map { it.toDto() },
        defaultTraits = schema.defaultTraits,
    )

/** Parses the `{rootId}` path parameter as a UUID; responds 400 and returns null on failure. */
private suspend fun ApplicationCall.parseEffectiveConfigRootId(): UUID? {
    val rawId =
        parameters["rootId"] ?: run {
            respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing rootId"))
            return null
        }
    return runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
        null
    }
}
