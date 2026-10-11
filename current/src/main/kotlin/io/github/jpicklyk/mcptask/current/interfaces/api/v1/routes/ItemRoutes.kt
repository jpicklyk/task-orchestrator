package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.withConfigSession
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.TransitionPreview
import io.github.jpicklyk.mcptask.current.application.service.blockedByWire
import io.github.jpicklyk.mcptask.current.application.service.computeMissingBySeat
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.GateStatusDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemGateDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.DB_QUERY_FAILED
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagForVersion
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.REQ_ID_HEADER
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.SURFACE_REST
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EncodedEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemsReadTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.unknownParameters
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** Query parameters `GET /items/{id}/children` accepts; any other is `unknown_parameter`. */
private val CHILDREN_PARAMS = setOf("limit", "cursor")

/** The most characters of a rejected value echoed back as `received`. */
private const val MAX_RECEIVED = 100

/**
 * The item resource GETs (plan section 4.4, Q19): each is one `items_read` select dispatched through the SAME
 * [ToolDispatcher] and the SAME `CallAccess` builder as `POST /api/v1/ops/{tool}` ([toCallAccess]), so the body
 * equals the `/ops` envelope for the same principal and arguments (only `meta.reqId` differs).
 *
 * - `GET /items/{id}` == `{select: get, itemId: id}`. It takes no query parameter (any is `unknown_parameter`). On
 *   success it sets `ETag: "v<version>"` and answers `304 Not Modified` (no body) when `If-None-Match` equals it; an
 *   error never carries an ETag and is never a 304, so scope is decided before any conditional answer.
 * - `GET /items/{id}/children` == `{select: list, parentId: id, limit?, cursor?}`. Other query parameters are
 *   `unknown_parameter`; a non-integer `limit` is `invalid_request`. No ETag.
 *
 * Capability (READ) and scope are enforced by the dispatcher and the select, exactly as on `/ops`. A request with no
 * authenticated principal is `unauthenticated` (fail closed). The 3.x list, roots, tree, breadcrumbs and schema
 * routes are gone: `POST /api/v1/ops/items_read` serves them.
 */
fun Route.itemRoutes(
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    profile: ToolProfile = ToolProfile.ORCHESTRATION,
) {
    get("/items/{id}") {
        val reqId = call.response.headers[REQ_ID_HEADER] ?: ReqId.generate()
        val unknown =
            call.request.queryParameters
                .names()
                .map { "/$it" }
        if (unknown.isNotEmpty()) {
            call.respondEnvelope(EnvelopeEncoder.error(unknownParameters(unknown), reqId))
            return@get
        }
        val arguments =
            buildJsonObject {
                put("select", "get")
                put("itemId", call.parameters["id"].orEmpty())
            }
        val envelope = call.dispatchItemsRead(registry, dispatcher, profile, arguments, reqId)
        val version = if (envelope.httpStatus == HttpStatusCode.OK.value) versionOf(envelope) else null
        if (version != null) {
            val etag = etagForVersion(version)
            call.response.header(HttpHeaders.ETag, etag)
            if (call.request.headers[HttpHeaders.IfNoneMatch]?.trim() == etag) {
                call.respond(HttpStatusCode.NotModified)
                return@get
            }
        }
        call.respondEnvelope(envelope)
    }

    get("/items/{id}/children") {
        val reqId = call.response.headers[REQ_ID_HEADER] ?: ReqId.generate()
        val params = call.request.queryParameters
        val unknown = params.names().filter { it !in CHILDREN_PARAMS }.map { "/$it" }
        if (unknown.isNotEmpty()) {
            call.respondEnvelope(EnvelopeEncoder.error(unknownParameters(unknown), reqId))
            return@get
        }
        val rawLimit = params["limit"]
        val limit = rawLimit?.toIntOrNull()
        if (rawLimit != null && limit == null) {
            call.respondEnvelope(EnvelopeEncoder.error(invalidField("/limit", "must be an integer", rawLimit.take(MAX_RECEIVED)), reqId))
            return@get
        }
        val arguments =
            buildJsonObject {
                put("select", "list")
                put("parentId", call.parameters["id"].orEmpty())
                limit?.let { put("limit", it) }
                params["cursor"]?.let { put("cursor", it) }
            }
        call.respondEnvelope(call.dispatchItemsRead(registry, dispatcher, profile, arguments, reqId))
    }
}

/**
 * Dispatches [arguments] to `items_read` as the authenticated principal (the `/ops` access builder). The call log keeps
 * the route template as the REST tool name (not `items_read`); `unauthenticated` when there is no principal.
 */
private suspend fun ApplicationCall.dispatchItemsRead(
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    profile: ToolProfile,
    arguments: JsonObject,
    reqId: String,
): EncodedEnvelope {
    val principal =
        attributes.getOrNull(ApiPrincipalKey)
            ?: return EnvelopeEncoder.error(DomainError(ErrorCode.UNAUTHENTICATED, "No authenticated principal."), reqId)
    val spec = registry[ItemsReadTool.NAME] ?: error("the tool registry has no ${ItemsReadTool.NAME}")
    val dispatched = dispatcher.dispatch(spec, profile, arguments, reqId, SURFACE_REST, principal.toCallAccess())
    return dispatched.envelope
}

/** `data.version` of a successful `get` envelope; null for any other body. */
private fun versionOf(envelope: EncodedEnvelope): Long? =
    ((envelope.body["data"] as? JsonObject)?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/**
 * Registers the read-only gate-status sub-resource for a single item, under `/api/v1` (the
 * schema view is `items_read` `select: schema`).
 *
 * - `GET /items/{id}/gate` — the item's title, current role, and canonical gate status for that
 *   role: field-for-field identical to `get_context` item mode's `gateStatus` /
 *   `guidanceKey` / `skillPointer`, computed via the SAME [EffectiveConfigResolver.resolveSchema] +
 *   [computePhaseNoteContext] path get_context uses — no gate logic is reimplemented here.
 *   `gateStatus.missingBySeat` (A1c) is present only for a seat-aware, non-terminal item — see
 *   [io.github.jpicklyk.mcptask.current.application.service.computeMissingBySeat].
 *
 * Id handling: full UUID only (a hex prefix is rejected), malformed → 400 `bad_request`, unknown →
 * 404 `not_found`, out-of-scope → 403 `scope_forbidden`, checked in that order. No
 * dependency/blocker info and no dispatch field on `/gate` (out of scope for this route — see
 * task-scope). No ETag / `If-None-Match` handling: the gate depends on notes and config, neither of
 * which the item's version covers, so a conditional answer could be stale after a note fill or
 * config push.
 *
 * [configResolver] is the SAME [EffectiveConfigResolver] instance the MCP tool context and the
 * REST advance route share (via [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext.configResolver]),
 * so this route shares MCP's last-known-good per-root config cache instead of maintaining its own.
 * A [PerRootConfigUnavailableException] raised resolving that cache responds 503
 * `config_unavailable`, same envelope as the advance route's D6 handling.
 *
 * [transitionPreview] is the SAME preview `get_context` uses: `gateStatus.canAdvance` is
 * `inspect(item, start).decision is Allow` and `gateStatus.blockedBy` the rejecting gate; `missing`,
 * `missingBySeat` and `violations` come from the same read unit. That unit reads config with no
 * last-known-good fallback, so the gate route answers 503 on a per-root read fault even when warm.
 */
fun Route.itemGateRoutes(
    repositoryProvider: RepositoryProvider,
    configResolver: EffectiveConfigResolver,
    transitionPreview: TransitionPreview,
) {
    val workItemRepo = repositoryProvider.workItemRepository()

    requireCapability(ApiCapability.READ) {
        // ─── GET /items/{id}/gate ──────────────────────────────────────────────
        get("/items/{id}/gate") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Missing item id")
                    return@get
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Invalid UUID: $rawId")
                    return@get
                }

            val itemResult =
                legacyRead({
                    call.respondError(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    return@get
                }) { workItemRepo.getById(id) }
            if (itemResult == null) {
                call.respondError(LegacyRestCode.NOT_FOUND, "Item $id not found")
                return@get
            }
            val item = itemResult

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for item $id")
                return@get
            }

            // Per D6: a per-root config read failure resolving the gate's schema responds 503 with
            // a config_unavailable ErrorDto — no Retry-After header, same contract as the advance
            // route (RFC 9110 §15.6.4: 503 describes a temporary server-side inability). The preview
            // reads config inside its read unit (no last-known-good fallback there).
            // Schema, notes, violations and the start decision all come from ONE read unit.
            val startView =
                try {
                    withConfigSession { transitionPreview.inspect(item, Trigger.User.START) }
                } catch (e: PerRootConfigUnavailableException) {
                    call.respondError(LegacyRestCode.CONFIG_UNAVAILABLE, e.message)
                    return@get
                }

            val resolvedSchema = startView.schema
            val startDecision = startView.decision
            val notesByKey = startView.notes.associateBy { it.key }

            val phaseContext = computePhaseNoteContext(item.role, resolvedSchema?.notes, notesByKey)
            val missing = phaseContext?.missingKeys ?: emptyList()
            val isTerminal = item.role == Role.TERMINAL
            // Same rule A1b's get_context applies: computeMissingBySeat alone would still return
            // `{}` for a seat-aware TERMINAL item, so the terminal check is explicit here too.
            val missingBySeat = if (isTerminal) null else computeMissingBySeat(resolvedSchema, missing)

            // A2: independence-attestation violations for the item's CURRENT phase -- same
            // computation GetContextTool's item mode uses (null when TERMINAL, mode OFF, or the
            // resolved schema declares no `independent_of` anywhere).
            val violations = if (isTerminal) null else startView.violations
            val canAdvance = startDecision is Decision.Allow

            call.respond(
                HttpStatusCode.OK,
                ItemGateDto(
                    itemId = item.id.toString(),
                    title = item.title,
                    role = item.role.toJsonString(),
                    gateStatus =
                        GateStatusDto(
                            canAdvance = canAdvance,
                            phase = item.role.toJsonString(),
                            missing = missing,
                            missingBySeat = missingBySeat,
                            violations = violations?.map { it.toDto() },
                            blockedBy = if (canAdvance || isTerminal) null else startDecision.blockedByWire(),
                        ),
                    guidanceKey = phaseContext?.guidanceKey,
                    skillPointer = phaseContext?.skillPointer,
                ),
            )
        }
    }
}
