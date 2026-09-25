package io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging

import io.github.jpicklyk.mcptask.current.infrastructure.logging.MdcValues
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID

/**
 * Matches a safe, loggable `X-Request-Id` value: ASCII alphanumerics, `.`, `_`, `-`, 1-64 chars.
 *
 * Deliberately reject-and-replace, NOT length-capped like `httpPath` below: `requestId` is a
 * correlation KEY used to tie related log lines together. Truncating an over-long or malformed
 * key would silently coalesce distinct requests under a shared, truncated prefix — a false
 * correlation that is worse than falling back to a fresh, unambiguous UUID.
 */
private val SAFE_REQUEST_ID = Regex("^[A-Za-z0-9._-]{1,64}$")

/** Max length of the `httpPath` MDC value (see [MdcValues.bounded]). `/api/v1` paths are a fixed
 * prefix plus at most two UUIDs, comfortably under 120 chars — 256 leaves generous headroom.
 */
private const val HTTP_PATH_MDC_MAX_LENGTH = 256

/**
 * Installs MDC correlation fields for every REST request under `/api/v1`, mirroring the MCP-path
 * correlation [io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter] adds around each
 * tool call — see AR-78 / item b5081c9b.
 *
 * A `createApplicationPlugin`/`onCall` hook cannot wrap `proceed()` (the hook runs and returns
 * before downstream handlers execute — there is no "after" to unwind an MDC scope from), so this
 * installs a raw pipeline interceptor at [ApplicationCallPipeline.Monitoring] instead, wrapping
 * the downstream `proceed()` call in [MDCContext] so every route handler's log lines — and
 * anything they call — carry the fields for the lifetime of the request, restored correctly across
 * suspension points on Ktor's coroutine-based pipeline.
 *
 * Fields: `transport`="rest", `requestId` (the inbound `X-Request-Id` header when it matches
 * [SAFE_REQUEST_ID], else a fresh UUID — this bounds what a caller-supplied header can inject into
 * a JSON log line), `httpMethod`, and `httpPath` ([io.ktor.server.request.path], which excludes
 * the query string — so no `?token=` bearer/SSE query param ever lands in MDC or a log line).
 * `httpPath` is length-capped via [MdcValues.bounded] (max [HTTP_PATH_MDC_MAX_LENGTH]) since it is
 * client-controlled (any path segment, however long, reaches routing before this interceptor runs).
 *
 * Scoped to `/api/v1` only: non-API paths (`/mcp`, `/.well-known/...`) proceed with no MDC change.
 */
internal fun Application.installRequestCorrelation() {
    intercept(ApplicationCallPipeline.Monitoring) {
        val path = call.request.path()
        if (!path.startsWith("/api/v1")) {
            proceed()
            return@intercept
        }

        val inboundRequestId = call.request.header("X-Request-Id")
        val requestId =
            if (inboundRequestId != null && SAFE_REQUEST_ID.matches(inboundRequestId)) {
                inboundRequestId
            } else {
                UUID.randomUUID().toString()
            }

        val fields =
            mapOf(
                "transport" to "rest",
                "requestId" to requestId,
                "httpMethod" to call.request.httpMethod.value,
                "httpPath" to MdcValues.bounded(path, max = HTTP_PATH_MDC_MAX_LENGTH),
            )
        withContext(MDCContext((MDC.getCopyOfContextMap() ?: emptyMap()) + fields)) {
            proceed()
        }
    }
}
