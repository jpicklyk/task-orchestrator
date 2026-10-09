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
import io.ktor.server.application.pluginOrNull
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.PipelineResponse
import io.ktor.server.response.header
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RoutingRoot
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
    subscribeRouteCapture()
    val resources = DeclaredResources(this)
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
                submitRestCall(callLog, call, path, resources, telemetry, startedAt, started.elapsedNow().inWholeMilliseconds, threw)
            }
        }
    }
}

internal const val REQ_ID_HEADER = "X-Req-Id"
private const val API_ROOT = "/api/v1"
private const val EVENT_STREAM_PATH = "/api/v1/events"

private val STANDARD_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

/** The matched route template (`/api/v1/items/{id}/schema`) recorded from Ktor's routing event; absent if no route resolved. */
private val MATCHED_ROUTE_TEMPLATE = AttributeKey<String>("CallLogMatchedRoute")

/**
 * The path-segment text of a route selector, or null for selectors that are not path segments (HTTP method,
 * authenticate wrappers, trailing-slash markers).
 */
private fun pathSegmentOf(selector: RouteSelector?): String? {
    if (selector == null || selector is HttpMethodRouteSelector) return null
    val text = selector.toString()
    if (text.isEmpty() || text.startsWith("(") || text.startsWith("<")) return null
    return text.trim('/')
}

/** The declared path template of [route] (`/api/v1/items/{id}`), built from its selectors, without the HTTP method. */
internal fun routeTemplate(route: Route): String {
    val parts = ArrayList<String>()
    var node: Route? = route
    while (node != null) {
        pathSegmentOf(node.selector)?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        node = node.parent
    }
    return "/" + parts.reversed().joinToString("/")
}

/**
 * Records, for the call that Ktor routed to an endpoint (a route whose selector is an HTTP method), the declared
 * route template. A call that resolved no endpoint (404, 405, or a 401 on a path no route matches) records nothing.
 * The auth check does not stop routing, so a 401 on a declared route still records its template.
 */
private fun Application.subscribeRouteCapture() {
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { routingCall ->
        if (routingCall.route.selector is HttpMethodRouteSelector) {
            routingCall.attributes.put(MATCHED_ROUTE_TEMPLATE, routeTemplate(routingCall.route))
        }
    }
}

/**
 * The top-level resources (first literal segment under `/api/v1`) the declared routing tree serves, derived from the
 * routes themselves. Computed on first use (routing is installed after this plugin) and cached once non-empty.
 */
private class DeclaredResources(
    private val application: Application
) {
    @Volatile
    private var cached: Set<String> = emptySet()

    fun get(): Set<String> {
        cached.takeIf { it.isNotEmpty() }?.let { return it }
        val root = application.pluginOrNull(RoutingRoot) ?: return emptySet()
        val found = HashSet<String>()
        collect(root, emptyList(), found)
        cached = found
        return found
    }

    private fun collect(
        route: Route,
        prefix: List<String>,
        found: MutableSet<String>
    ) {
        val segment = pathSegmentOf(route.selector)
        val path = if (segment == null || segment.isEmpty()) prefix else prefix + segment.split('/').filter { it.isNotEmpty() }
        if (path.size > 2 && path[0] == "api" && path[1] == "v1" && !path[2].startsWith("{") && !path[2].startsWith("*")) {
            found.add(path[2])
        }
        for (child in route.children) collect(child, path, found)
    }
}

/**
 * The bounded `tool` value of a REST row. A call that resolved a route is `<METHOD> <declared route template>`, one
 * value per declared route. A call that resolved none (404, 405, or a 401 on a path no route matches) is `<METHOD> /api/v1/<first
 * segment>` when the first segment is a top-level resource the routing tree declares, else `<METHOD> unmatched`: never
 * more than the first segment. The raw request path is never stored.
 */
internal fun restToolName(
    method: String,
    matchedTemplate: String?,
    path: String,
    resources: Set<String>
): String {
    val verb = if (method in STANDARD_METHODS) method else "OTHER"
    if (matchedTemplate != null) return "$verb $matchedTemplate"
    val first = path.removePrefix(API_ROOT).split('/').firstOrNull { it.isNotEmpty() }
    return if (first != null && first in resources) "$verb $API_ROOT/$first" else "$verb unmatched"
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
    resources: DeclaredResources,
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
        val tool = restToolName(call.request.httpMethod.value, call.attributes.getOrNull(MATCHED_ROUTE_TEMPLATE), path, resources.get())
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
