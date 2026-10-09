package io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.telemetry.CallLogFields
import io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.infrastructure.logging.MdcValues
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.PipelineResponse
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID
import kotlin.time.TimeSource

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
internal fun Application.installRequestCorrelation(
    callLog: CallLogSink = CallLogSink.NONE,
    clock: Clock = Clock.SYSTEM
) {
    intercept(ApplicationCallPipeline.Monitoring) {
        val path = call.request.path()
        if (path != API_ROOT && !path.startsWith("$API_ROOT/")) {
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

        // The SSE stream is long-lived and authenticates itself: it gets no reqId, header or call_log row.
        if (call.request.httpMethod.value == "GET" && path == EVENT_STREAM_PATH) {
            withContext(MDCContext((MDC.getCopyOfContextMap() ?: emptyMap()) + fields)) {
                proceed()
            }
            return@intercept
        }

        // The reqId is always server-generated (the inbound X-Request-Id keeps feeding MDC requestId only).
        val reqId = ReqId.generate()
        val startedAt = clock.now()
        val started = TimeSource.Monotonic.markNow()
        val telemetry = CallTelemetry(reqId, CallLogRecord.SURFACE_REST, null)
        call.response.header(REQ_ID_HEADER, reqId)
        captureResponse(call)

        var cancelled = false
        var threw = false
        try {
            withContext(MDCContext((MDC.getCopyOfContextMap() ?: emptyMap()) + fields + ("reqId" to reqId)) + telemetry) {
                proceed()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled call writes no row.
            cancelled = true
            throw e
        } catch (e: Throwable) {
            threw = true
            throw e
        } finally {
            if (!cancelled) {
                submitRestCall(callLog, call, path, telemetry, startedAt, started.elapsedNow().inWholeMilliseconds, threw)
            }
        }
    }
}

internal const val REQ_ID_HEADER = "X-Req-Id"
private const val API_ROOT = "/api/v1"
private const val EVENT_STREAM_PATH = "/api/v1/events"
private const val MAX_TOOL_SEGMENTS = 6

/** The top-level REST resources the API serves; any other first segment is recorded as unmatched. */
private val REST_RESOURCES =
    setOf("config", "dependencies", "events", "health", "info", "items", "notes", "resources", "roots", "search", "transitions")

/** Literal (non-parameter) path segments the REST routes declare below the first segment. */
private val REST_STATIC_SEGMENTS =
    setOf(
        "advance",
        "backlinks",
        "breadcrumbs",
        "children",
        "config",
        "dependencies",
        "effective",
        "events",
        "gate",
        "history",
        "items",
        "leases",
        "notes",
        "plans",
        "resources",
        "roots",
        "rules",
        "schemas",
        "search",
        "status-graph",
        "traits",
        "transitions",
        "tree",
        "types"
    )

private val STANDARD_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

/**
 * The bounded `tool` value of a REST row: `<METHOD> /api/v1/<segments>` where every UUID is `{id}`, every literal
 * route word is kept and every other segment is `{param}`, at most [MAX_TOOL_SEGMENTS] segments. A path whose first
 * segment is not a served top-level resource (a 404, or a 401 before routing) is `<METHOD> unmatched`. The raw
 * request path is never stored.
 */
internal fun restToolName(
    method: String,
    path: String
): String {
    val verb = if (method in STANDARD_METHODS) method else "OTHER"
    val segments = path.removePrefix(API_ROOT).split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments[0] !in REST_RESOURCES) return "$verb unmatched"
    val template =
        segments.take(MAX_TOOL_SEGMENTS).mapIndexed { index, segment ->
            when {
                index == 0 -> segment
                UUID_SEGMENT.matches(segment) -> "{id}"
                segment in REST_STATIC_SEGMENTS -> segment
                else -> "{param}"
            }
        }
    return "$verb $API_ROOT/" + template.joinToString("/")
}

private val CAPTURED_ERROR_CODE = AttributeKey<String>("CallLogErrorCode")
private val CAPTURED_RESPONSE_BYTES = AttributeKey<Long>("CallLogResponseBytes")
private val UUID_SEGMENT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Hooks the response send pipeline of [call] to remember (a) the typed [ErrorDto]'s `error` code before it is
 * serialized and (b) the final [OutgoingContent] length. No body is ever re-parsed.
 */
private fun captureResponse(call: io.ktor.server.application.ApplicationCall) {
    val pipeline = (call.response as? PipelineResponse)?.pipeline ?: return
    pipeline.intercept(ApplicationSendPipeline.Before) { body ->
        if (body is ErrorDto) {
            if (!call.attributes.contains(CAPTURED_ERROR_CODE)) call.attributes.put(CAPTURED_ERROR_CODE, body.error)
        } else if (body is Map<*, *>) {
            // The auth plugin answers 401 with a plain map carrying the same `error` code (invalid_token, ...).
            (body["error"] as? String)?.let {
                if (!call.attributes.contains(
                        CAPTURED_ERROR_CODE
                    )
                ) {
                    call.attributes.put(CAPTURED_ERROR_CODE, it)
                }
            }
        }
    }
    pipeline.intercept(ApplicationSendPipeline.After) { body ->
        if (body is OutgoingContent) body.contentLength?.let { call.attributes.put(CAPTURED_RESPONSE_BYTES, it) }
    }
}

/** Builds and submits the REST `call_log` record. A failure here is swallowed: it must not change the response. */
private fun submitRestCall(
    callLog: CallLogSink,
    call: io.ktor.server.application.ApplicationCall,
    path: String,
    telemetry: CallTelemetry,
    startedAt: java.time.Instant,
    latencyMs: Long,
    threw: Boolean
) {
    try {
        call.attributes.getOrNull(ApiPrincipalKey)?.let { principal ->
            telemetry.setPrincipal(
                CallTelemetry.Principal(
                    ApiAuditBridge.toActorClaim(principal).id,
                    ApiAuditBridge.toActorClaim(principal).kind.toJsonString(),
                    ApiAuditBridge.toVerificationResult(principal).status.toJsonString()
                )
            )
        }
        val status = call.response.status()?.value ?: if (threw) 500 else 200
        val isError = threw || status >= 400
        val errorCode = if (isError) call.attributes.getOrNull(CAPTURED_ERROR_CODE) ?: "http_$status" else null
        val segments = path.split('/').filter { it.isNotEmpty() }
        val uuidSegments = segments.filter { UUID_SEGMENT.matches(it) }
        val tool = restToolName(call.request.httpMethod.value, path)
        val requestBytes =
            call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                ?: if (call.request.header(HttpHeaders.TransferEncoding) == null) 0L else null
        callLog.submit(
            CallLogFields.record(
                telemetry = telemetry,
                at = startedAt,
                tool = tool,
                operation = null,
                targetIds = CallLogFields.targetIdsJson(null, uuidSegments),
                requestShape = restRequestShape(call.request.queryString()),
                isError = isError,
                errorCode = errorCode,
                latencyMs = latencyMs,
                requestBytes = requestBytes,
                responseBytes = call.attributes.getOrNull(CAPTURED_RESPONSE_BYTES),
                batchSize = null,
                failedCount = null,
                resultCount = null,
                eligibleCount = null
            )
        )
    } catch (e: Exception) {
        e.rethrowIfCancellation()
    }
}

/** Boolean query params plus a numeric `limit`, as the MCP request shape; null when none. */
private fun restRequestShape(queryString: String): String? {
    if (queryString.isEmpty()) return null
    val args = HashMap<String, kotlinx.serialization.json.JsonElement>()
    for (pair in queryString.split('&')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        val key = pair.substring(0, eq)
        val value = pair.substring(eq + 1)
        when {
            value == "true" || value == "false" -> args[key] = kotlinx.serialization.json.JsonPrimitive(value == "true")
            key == "limit" -> value.toLongOrNull()?.let { args[key] = kotlinx.serialization.json.JsonPrimitive(it) }
        }
    }
    return CallLogFields.requestShapeJson(kotlinx.serialization.json.JsonObject(args))
}
