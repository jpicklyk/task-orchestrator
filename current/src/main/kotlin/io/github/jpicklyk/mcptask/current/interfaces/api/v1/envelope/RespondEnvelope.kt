package io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope

import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EncodedEnvelope
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/**
 * Answers the call with [envelope]: its body bytes as `application/json`, with the status the encoder
 * derived from the error code (envelope design section 3). No route picks a status by hand.
 */
suspend fun ApplicationCall.respondEnvelope(envelope: EncodedEnvelope) {
    respondText(envelope.body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(envelope.httpStatus))
}
