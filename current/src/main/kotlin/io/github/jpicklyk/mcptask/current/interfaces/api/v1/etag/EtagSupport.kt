package io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.time.Instant

/**
 * Derives a stable ETag string from a resource's last-modified timestamp.
 *
 * Format: `"v1-<epochMillis>"` (quoted per RFC 7232).
 * The ETag is deterministic: the same [modifiedAt] always produces the same string.
 *
 * @param modifiedAt The last-modified timestamp of the resource.
 * @return ETag string including surrounding quotes, e.g. `"v1-1716910000000"`.
 */
fun etagFor(modifiedAt: Instant): String = "\"v1-${modifiedAt.toEpochMilli()}\""

/**
 * Sets the `ETag` response header and, when the `If-None-Match` request header matches the
 * current ETag, responds with `304 Not Modified` and returns `true`.
 *
 * Returns `false` when the content has changed (or no `If-None-Match` was sent), signalling
 * that the route handler should proceed with building the full response.
 *
 * Usage:
 * ```kotlin
 * if (call.respondWithEtag(item.modifiedAt)) return@get
 * // ... build and send the full response
 * ```
 *
 * @param modifiedAt The last-modified timestamp used to derive the ETag.
 * @return `true` when `304 Not Modified` was sent (caller should return immediately),
 *   `false` when the full response must be generated.
 */
suspend fun ApplicationCall.respondWithEtagCheck(modifiedAt: Instant): Boolean {
    val etag = etagFor(modifiedAt)
    val clientEtag = request.headers[HttpHeaders.IfNoneMatch]?.trim()
    response.header(HttpHeaders.ETag, etag)
    if (clientEtag != null && clientEtag == etag) {
        respond(HttpStatusCode.NotModified)
        return true
    }
    return false
}
