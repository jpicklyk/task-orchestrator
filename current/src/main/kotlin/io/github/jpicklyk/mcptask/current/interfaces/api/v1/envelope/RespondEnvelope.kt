package io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope

import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.application.telemetry.currentReqId
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.ENVELOPE_ERROR_CODE
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.REQ_ID_HEADER
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EncodedEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Answers the call with [envelope]: its body bytes as `application/json`, with the status the encoder
 * derived from the error code (envelope design section 3). No route picks a status by hand.
 *
 * The `X-Req-Id` header carries `meta.reqId` (set here when the correlation plugin has not already set it, as on
 * `/mcp` and the SSE pre-flight), and an error envelope's `error.code` is recorded for the REST call log.
 */
suspend fun ApplicationCall.respondEnvelope(envelope: EncodedEnvelope) {
    val meta = envelope.body["meta"] as? JsonObject
    val reqId = (meta?.get("reqId") as? JsonPrimitive)?.content
    if (reqId != null && response.headers[REQ_ID_HEADER] == null) response.header(REQ_ID_HEADER, reqId)
    val code = ((envelope.body["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content
    if (code != null && !attributes.contains(ENVELOPE_ERROR_CODE)) attributes.put(ENVELOPE_ERROR_CODE, code)
    respondText(envelope.body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(envelope.httpStatus))
}

/** Answers the call with the error envelope of [error] under this call's reqId ([envelopeReqId]). */
suspend fun ApplicationCall.respondEnvelopeError(error: DomainError) {
    respondEnvelope(EnvelopeEncoder.error(error, envelopeReqId()))
}

/**
 * The reqId of this call: the one the correlation plugin installed (its telemetry wraps the handler), else the
 * `X-Req-Id` it already set on the response, else a fresh one when no correlation ran (`/mcp`, the SSE pre-flight).
 */
suspend fun ApplicationCall.envelopeReqId(): String = currentReqId() ?: response.headers[REQ_ID_HEADER] ?: ReqId.generate()
