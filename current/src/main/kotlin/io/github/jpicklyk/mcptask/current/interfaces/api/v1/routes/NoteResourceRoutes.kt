package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.REQ_ID_HEADER
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EncodedEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolView
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The note resource reads (plan 4.4): `GET /items/{id}/notes` and `GET /items/{id}/notes/{key}` are thin
 * translations to `notes_read list` / `notes_read get` through the shared [ToolDispatcher], with the bearer
 * principal's access, and answer the envelope with the status its error code derives. Capability (READ) and scope are
 * enforced by the dispatcher and the handlers, exactly as for `POST /ops/notes_read`, so the body equals the `/ops` body
 * for the same arguments (minus `meta.reqId`).
 *
 * - `list` query parameters map one to one onto the request: `role`, `keys` (comma-separated), `limit`, `cursor`.
 * - `get` takes no query parameter (the ETag covers `ifVersion`) and answers `ETag: "v{version}"`; an `If-None-Match`
 *   naming that tag (or `*`) answers `304` with no body.
 * - Any other query parameter is `invalid_request`. Writes have no resource route: they go through `POST /ops/notes_write`.
 */
fun Route.noteResourceRoutes(
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    profile: ToolView = ToolView.Rest,
    redactNoteAttribution: Boolean = true
) {
    get("/items/{id}/notes") {
        val outcome = call.runNotesRead(registry, dispatcher, profile, redactNoteAttribution, "list", LIST_PARAMETERS) ?: return@get
        call.respondEnvelope(outcome)
    }

    get("/items/{id}/notes/{key}") {
        val envelope = call.runNotesRead(registry, dispatcher, profile, redactNoteAttribution, "get", emptySet()) ?: return@get
        val version = envelope.noteVersion()
        if (version == null) {
            call.respondEnvelope(envelope)
            return@get
        }
        val etag = "\"v$version\""
        call.response.header(HttpHeaders.ETag, etag)
        if (matchesIfNoneMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.respondEnvelope(envelope)
        }
    }
}

private val LIST_PARAMETERS = setOf("role", "keys", "limit", "cursor")

/** Dispatches `notes_read` [select] for [this] call, or answers the fault itself and returns null. */
private suspend fun ApplicationCall.runNotesRead(
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    profile: ToolView,
    redactNoteAttribution: Boolean,
    select: String,
    allowed: Set<String>
): EncodedEnvelope? {
    val reqId = response.headers[REQ_ID_HEADER] ?: ReqId.generate()
    val principal = attributes.getOrNull(ApiPrincipalKey)
    if (principal == null) {
        respondEnvelope(EnvelopeEncoder.error(DomainError(ErrorCode.UNAUTHENTICATED, "No authenticated principal."), reqId))
        return null
    }
    val unknown =
        request.queryParameters
            .names()
            .filter { it !in allowed }
            .sorted()
    if (unknown.isNotEmpty()) {
        val fields = unknown.map { FieldViolation(it, "is not a query parameter of this route") }
        respondEnvelope(
            EnvelopeEncoder.error(
                DomainError(
                    code = ErrorCode.INVALID_REQUEST,
                    message = "The request has ${fields.size} invalid field(s): ${unknown.joinToString(", ")}.",
                    detail = ErrorDetail.InvalidRequest(fields)
                ),
                reqId
            )
        )
        return null
    }
    val spec = registry["notes_read"]
    if (spec == null) {
        respondEnvelope(EnvelopeEncoder.internal(reqId))
        return null
    }
    val arguments = notesReadArguments(select, parameters["id"].orEmpty(), parameters["key"], request.queryParameters::get)
    return dispatcher
        .dispatch(spec, profile, arguments, reqId, "rest", principal.toCallAccess(redactNoteAttribution))
        .envelope
}

/**
 * The `notes_read` arguments of a resource GET. A `limit` that is not an integer is passed through as a string so the
 * decoder reports it as `invalid_request` on `/limit`.
 */
private fun notesReadArguments(
    select: String,
    itemId: String,
    key: String?,
    query: (String) -> String?
): JsonObject =
    buildJsonObject {
        put("select", select)
        put("itemId", itemId)
        key?.let { put("key", it) }
        query("role")?.let { put("role", it) }
        query("keys")?.let { raw ->
            put("keys", kotlinx.serialization.json.JsonArray(raw.split(',').map { JsonPrimitive(it) }))
        }
        query("limit")?.let { raw -> put("limit", raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)) }
        query("cursor")?.let { put("cursor", it) }
    }

/** The `data.version` of a successful `notes_read get` answer, else null. */
private fun EncodedEnvelope.noteVersion(): Long? {
    if ((body["ok"] as? JsonPrimitive)?.contentOrNull != "true") return null
    val data = body["data"]?.jsonObject ?: return null
    return data["version"]?.jsonPrimitive?.longOrNull
}

/** True when the `If-None-Match` [header] lists [etag] (weak validators compare equal) or is `*`. */
internal fun matchesIfNoneMatch(
    header: String?,
    etag: String
): Boolean {
    if (header == null) return false
    return header.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }
}
