package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.Fingerprint
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.IdempotentRequest
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestErrorMapper
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * A fully-materialized HTTP response, captured so it can be sent once and stored for replay.
 *
 * A replay with the same `Idempotency-Key` returns the **serialized HTTP response (status + body)**
 * verbatim. We capture:
 * - [statusCode]: the numeric HTTP status
 * - [bodyJson]: the already-serialized JSON body (or null for no-body responses such as 204)
 * - [etag]: the `ETag` header value to re-emit on replay (null when none was set)
 * - [extraHeaders]: further headers to re-emit (for example `Retry-After` on a failed write)
 * - [rejection]: set only on a `400 validation_error` that is a pure function of the request
 *   payload (a malformed body or value); such a rejection is recorded and replayed, while every
 *   other non-2xx response depends on state and is returned without being recorded
 *
 * On both the fresh-compute path and the replay path, the captured response is sent via
 * [ApplicationCall.sendCaptured], so the client always receives an identical response.
 */
data class CachedHttpResponse(
    val statusCode: Int,
    val bodyJson: String?,
    val etag: String? = null,
    val rejection: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** Header set on a response served from a stored idempotency record. */
const val IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed"

private val idempotencyJson =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }

/** A `400 validation_error` that depends on the payload alone: recorded, and replayed as the same 400. */
fun payloadRejection(message: String): CachedHttpResponse = validationRejectionResponse(message)

private fun validationRejectionResponse(message: String): CachedHttpResponse =
    LegacyRestErrorMapper.captured(LegacyRestCode.VALIDATION_ERROR, message).copy(rejection = message)

/** Sends a [CachedHttpResponse] to the client, re-emitting the ETag header when present. */
suspend fun ApplicationCall.sendCaptured(captured: CachedHttpResponse) {
    captured.etag?.let { response.header(HttpHeaders.ETag, it) }
    captured.extraHeaders.forEach { (name, value) -> response.header(name, value) }
    val status = HttpStatusCode.fromValue(captured.statusCode)
    if (captured.bodyJson == null) {
        respond(status)
    } else {
        respondText(captured.bodyJson, ContentType.Application.Json, status)
    }
}

/** Stores a [CachedHttpResponse] as `{status, body, etag}`. */
private object CachedHttpResponseCodec : IdempotencyCodec<CachedHttpResponse> {
    override fun encode(value: CachedHttpResponse): JsonElement =
        buildJsonObject {
            put("status", value.statusCode)
            put("body", value.bodyJson?.let { JsonPrimitive(it) } ?: JsonNull)
            put("etag", value.etag?.let { JsonPrimitive(it) } ?: JsonNull)
        }

    override fun decode(json: JsonElement): CachedHttpResponse {
        val obj = json as JsonObject
        return CachedHttpResponse(
            statusCode = (obj["status"] as JsonPrimitive).intOrNull ?: HttpStatusCode.InternalServerError.value,
            bodyJson = (obj["body"] as? JsonPrimitive)?.contentOrNull,
            etag = (obj["etag"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}

/**
 * Runs a write [compute] block that produces a [CachedHttpResponse], honoring the optional
 * `Idempotency-Key`. With a key, the write runs in one unit together with its record, keyed
 * `"<key>:0"` under `rest.<METHOD> <routeTemplate>` for [trustedActorId]:
 * - a stored response with the same fingerprint is sent verbatim with `Idempotent-Replayed: true`
 *   WITHOUT re-running [compute]; the key reused with another request is `409 idempotency_mismatch`;
 * - a 2xx response commits with its record; a payload rejection ([payloadRejection]) is recorded after
 *   the rollback; any other non-2xx response rolls the write back, is sent, and is NOT recorded, so a
 *   retry with the same key runs it again.
 *
 * **[compute] must not read the request body**: every caller reads the raw body BEFORE this function
 * and passes it as [bodyText]; parsing and all state-dependent checks stay inside [compute], so status
 * precedence is unaffected.
 *
 * @param idempotencyKeyResult result of parsing the `Idempotency-Key` header (Absent/Present/Invalid).
 *   When [IdempotencyKeyResult.Invalid] the caller must have already responded 400 and must NOT call
 *   this function.
 * @param routeTemplate the route path template, e.g. `/items/{id}`
 * @param bodyText the raw request body, or null for a route without one
 */
suspend fun ApplicationCall.runWithIdempotency(
    service: IdempotencyService,
    trustedActorId: String,
    idempotencyKeyResult: IdempotencyKeyResult,
    routeTemplate: String,
    bodyText: String?,
    compute: suspend () -> CachedHttpResponse,
) {
    when (idempotencyKeyResult) {
        is IdempotencyKeyResult.Present -> {
            val op = "rest.${request.httpMethod.value} $routeTemplate"
            val fingerprintInput =
                buildJsonObject {
                    put("op", op)
                    put("path", request.uri)
                    put("ifMatch", request.headers[HttpHeaders.IfMatch]?.trim()?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("body", canonicalBody(bodyText))
                }
            val req = IdempotentRequest(trustedActorId, "${idempotencyKeyResult.key}:0", Fingerprint.of(fingerprintInput))
            var unrecorded: CachedHttpResponse? = null
            val result =
                service.execute(op, req, CachedHttpResponseCodec) {
                    unrecorded = null
                    val response = compute()
                    when {
                        response.statusCode in 200..299 -> Outcome.Ok(response)
                        response.rejection != null ->
                            Outcome.Err(
                                DomainError(
                                    ErrorCode.INVALID_REQUEST,
                                    response.rejection,
                                    ErrorDetail.InvalidRequest(listOf(FieldViolation("body", response.rejection))),
                                ),
                            )
                        else -> {
                            unrecorded = response
                            Outcome.Err(DomainError(ErrorCode.INTERNAL, "The write failed and was not recorded"))
                        }
                    }
                }
            if (result.replayed) response.header(IDEMPOTENT_REPLAYED_HEADER, "true")
            val captured =
                when (val outcome = result.outcome) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err ->
                        unrecorded ?: when (outcome.error.code) {
                            ErrorCode.IDEMPOTENCY_MISMATCH ->
                                LegacyRestErrorMapper.captured(LegacyRestCode.IDEMPOTENCY_MISMATCH, outcome.error.message)
                            ErrorCode.INVALID_REQUEST -> validationRejectionResponse(outcome.error.message)
                            else -> LegacyRestErrorMapper.captured(LegacyRestCode.DB_ERROR, "Failed to complete the write")
                        }
                }
            sendCaptured(captured)
        }
        // Absent -> no idempotency; just compute once.
        IdempotencyKeyResult.Absent -> sendCaptured(compute())
        // Invalid should never reach here (caller responded 400). Defensive 400.
        IdempotencyKeyResult.Invalid ->
            sendCaptured(
                CachedHttpResponse(
                    HttpStatusCode.BadRequest.value,
                    """{"error":"validation_error","message":"Idempotency-Key must be a valid UUID"}""",
                ),
            )
    }
}

/** The body as canonical JSON, or `{"rawSha256": hex}` when it does not parse. */
private fun canonicalBody(bodyText: String?): JsonElement {
    if (bodyText == null) return JsonNull
    return try {
        Json.parseToJsonElement(bodyText)
    } catch (e: SerializationException) {
        buildJsonObject {
            put(
                "rawSha256",
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(bodyText.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) },
            )
        }
    }
}

/**
 * Sealed result for `Idempotency-Key` header parsing.
 * - [Absent]: no `Idempotency-Key` header present
 * - [Present]: valid UUID parsed
 * - [Invalid]: malformed UUID (caller must respond 400 and skip the write)
 */
sealed class IdempotencyKeyResult {
    object Absent : IdempotencyKeyResult()

    data class Present(
        val key: UUID,
    ) : IdempotencyKeyResult()

    object Invalid : IdempotencyKeyResult()
}

/**
 * Parses the `Idempotency-Key` header from the request.
 *
 * - [IdempotencyKeyResult.Absent] when no header is present
 * - [IdempotencyKeyResult.Present] when the header contains a valid UUID
 * - [IdempotencyKeyResult.Invalid] (and responds 400) when present but malformed
 */
suspend fun ApplicationCall.parseIdempotencyKey(): IdempotencyKeyResult {
    val keyHeader = request.headers["Idempotency-Key"] ?: return IdempotencyKeyResult.Absent
    return try {
        IdempotencyKeyResult.Present(UUID.fromString(keyHeader.trim()))
    } catch (e: IllegalArgumentException) {
        respondError(LegacyRestCode.VALIDATION_ERROR, "Idempotency-Key must be a valid UUID")
        IdempotencyKeyResult.Invalid
    }
}
