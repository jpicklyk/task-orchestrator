package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagForVersion
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.REQ_ID_HEADER
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.SURFACE_REST
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EncodedEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolView
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
 *   success it sets `ETag: "v<version>"` and answers `304 Not Modified` (no body) when `If-None-Match` names it (the
 *   note GET convention, [matchesIfNoneMatch]: a tag list, a weak validator or `*`); an error never carries an ETag and
 *   is never a 304, so scope is decided before any conditional answer.
 * - `GET /items/{id}/children` == `{select: list, parentId: id, limit?, cursor?}`. Other query parameters are
 *   `unknown_parameter`; a non-integer `limit` is `invalid_request`. No ETag. [redactNoteAttribution] is the `/ops`
 *   flag, handed to the same builder so the access is identical.
 *
 * Capability (READ) and scope are enforced by the dispatcher and the select, exactly as on `/ops`. A request with no
 * authenticated principal is `unauthenticated` (fail closed). The 3.x list, roots, tree, breadcrumbs and schema
 * routes are gone: `POST /api/v1/ops/items_read` serves them.
 */
fun Route.itemRoutes(
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    profile: ToolView = ToolView.Rest,
    redactNoteAttribution: Boolean = true,
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
        val envelope = call.dispatchItemsRead(registry, dispatcher, profile, redactNoteAttribution, arguments, reqId)
        val version = if (envelope.httpStatus == HttpStatusCode.OK.value) versionOf(envelope) else null
        if (version != null) {
            val etag = etagForVersion(version)
            call.response.header(HttpHeaders.ETag, etag)
            if (matchesIfNoneMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
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
        call.respondEnvelope(call.dispatchItemsRead(registry, dispatcher, profile, redactNoteAttribution, arguments, reqId))
    }
}

/**
 * Dispatches [arguments] to `items_read` as the authenticated principal (the `/ops` access builder). The call log keeps
 * the route template as the REST tool name (not `items_read`); `unauthenticated` when there is no principal.
 */
private suspend fun ApplicationCall.dispatchItemsRead(
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    profile: ToolView,
    redactNoteAttribution: Boolean,
    arguments: JsonObject,
    reqId: String,
): EncodedEnvelope {
    val principal =
        attributes.getOrNull(ApiPrincipalKey)
            ?: return EnvelopeEncoder.error(DomainError(ErrorCode.UNAUTHENTICATED, "No authenticated principal."), reqId)
    val spec = registry[ItemsReadTool.NAME] ?: error("the tool registry has no ${ItemsReadTool.NAME}")
    val dispatched = dispatcher.dispatch(spec, profile, arguments, reqId, SURFACE_REST, principal.toCallAccess(redactNoteAttribution))
    return dispatched.envelope
}

/** `data.version` of a successful `get` envelope; null for any other body. */
private fun versionOf(envelope: EncodedEnvelope): Long? =
    ((envelope.body["data"] as? JsonObject)?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
