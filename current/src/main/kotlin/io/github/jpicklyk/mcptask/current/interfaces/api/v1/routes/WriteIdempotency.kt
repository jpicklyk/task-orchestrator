package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * A fully-materialized HTTP response captured for the idempotency cache.
 *
 * The plan §5.4.2 requires caching the **serialized HTTP response (status + body)** so a replay
 * with the same `Idempotency-Key` returns it verbatim, bypassing re-execution. We capture:
 * - [statusCode]: the numeric HTTP status
 * - [bodyJson]: the already-serialized JSON body (or null for no-body responses such as 204)
 * - [etag]: the `ETag` header value to re-emit on replay (null when none was set)
 *
 * On both the fresh-compute path and the cache-replay path, the captured response is sent via
 * [ApplicationCall.sendCaptured], so the client always receives an identical response.
 */
data class CachedHttpResponse(
    val statusCode: Int,
    val bodyJson: String?,
    val etag: String? = null,
)

/** Sends a [CachedHttpResponse] to the client, re-emitting the ETag header when present. */
suspend fun ApplicationCall.sendCaptured(captured: CachedHttpResponse) {
    captured.etag?.let { response.header(HttpHeaders.ETag, it) }
    val status = HttpStatusCode.fromValue(captured.statusCode)
    if (captured.bodyJson == null) {
        respond(status)
    } else {
        respondText(captured.bodyJson, ContentType.Application.Json, status)
    }
}

/**
 * Runs a write [compute] block that produces a [CachedHttpResponse], honoring the optional
 * `Idempotency-Key`. The captured response is cached (keyed on `(trustedActorId, key)`) and sent
 * to the client. A replay with the same key returns the cached response verbatim WITHOUT
 * re-running [compute] — so the side effect (DB write) executes at most once.
 *
 * The cache stores a [CachedHttpResponse]; [IdempotencyCache.getOrCompute] runs [compute] only on
 * a cache miss. The non-suspend cache lambda bridges to the suspend [compute] via [runBlocking],
 * matching the pattern used by the MCP tools.
 *
 * @param idempotencyKeyResult result of parsing the `Idempotency-Key` header (Absent/Present/Invalid).
 *   When [IdempotencyKeyResult.Invalid] the caller must have already responded 400 and must NOT call
 *   this function.
 */
suspend fun ApplicationCall.runWithIdempotency(
    cache: IdempotencyCache,
    trustedActorId: String,
    idempotencyKeyResult: IdempotencyKeyResult,
    compute: suspend () -> CachedHttpResponse,
) {
    val captured =
        when (idempotencyKeyResult) {
            is IdempotencyKeyResult.Present ->
                cache.getOrCompute(trustedActorId, idempotencyKeyResult.key) {
                    runBlocking { compute() }
                }
            // Absent → no idempotency; just compute once.
            IdempotencyKeyResult.Absent -> compute()
            // Invalid should never reach here (caller responded 400). Defensive 400.
            IdempotencyKeyResult.Invalid ->
                CachedHttpResponse(
                    HttpStatusCode.BadRequest.value,
                    """{"error":"validation_error","message":"Idempotency-Key must be a valid UUID"}""",
                )
        }
    sendCaptured(captured)
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
        respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Idempotency-Key must be a valid UUID"))
        IdempotencyKeyResult.Invalid
    }
}
