package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEvent
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.ServerSentEvent
import io.ktor.server.sse.sse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private val sseLogger = LoggerFactory.getLogger("EventRoutes")

/** JSON encoder for SSE data payloads. */
private val sseJson =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }

/**
 * Registers the `GET /api/v1/events` SSE endpoint.
 *
 * ## Authentication (INLINE — performed in a pre-check GET handler)
 *
 * SSE in Ktor 3.x commits response headers (200, Content-Type: text/event-stream) before
 * the `sse { }` lambda runs. Auth cannot be done inside `sse { }` — we'd be unable to
 * respond with 401 after headers are committed. The solution is a two-step approach:
 *
 * 1. A `get("/events")` handler performs auth and resolves the principal. If auth fails,
 *    it responds with 401/403 immediately.
 * 2. If auth succeeds, it falls through to the `sse("/events")` handler (via Ktor's
 *    plugin pipeline) — actually the two can't coexist on the same path in the same route.
 *
 * The cleanest Ktor 3.x approach: use `sse("/events")` with the auth state stored in
 * call attributes BEFORE the sse block runs (via a route-scoped plugin that runs pre-call).
 *
 * Practical solution for Ktor 3.3.3: perform auth at the start of `sse { }` BEFORE any
 * `send()` call. Ktor 3.3.3 does NOT flush SSE headers until the first `send()`, so
 * `call.respond(401)` works correctly if called before any SSE data is sent.
 * (Verified: `sse { }` uses `respondBytesWriter` whose header flush is lazy — the actual
 * flush occurs on first write to the byte channel, not at lambda entry.)
 *
 * ## Resolution order
 * 1. `Authorization: Bearer <token>` header (always allowed)
 * 2. Else if `API_ALLOW_QUERY_TOKEN_FOR_SSE=true` AND `?token=` present → query-token auth
 * 3. Else → 401
 *
 * ## Scope filtering
 * `?root=<uuid>[&root=<uuid>...]` restricts events to those root subtrees.
 * Effective subscription = intersection(`?root=`, `principal.scope.rootIds`).
 *
 * ## Event-ID namespace separation
 * IDs from [ApiEventBus] are INDEPENDENT of `/mcp`'s `EventStore`. Do NOT mix
 * `Last-Event-ID` values across the two SSE channels.
 *
 * @param eventBus The shared [ApiEventBus] instance.
 * @param tokenEntries Pre-loaded bearer token entries with expiry metadata.
 * @param allowQueryToken Whether `?token=` query param is accepted.
 * @param authCheckIntervalSeconds Interval between token-expiry checks.
 */
fun Route.eventRoutes(
    eventBus: ApiEventBus,
    tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap(),
    allowQueryToken: Boolean = System.getenv("API_ALLOW_QUERY_TOKEN_FOR_SSE")?.lowercase() == "true",
    authCheckIntervalSeconds: Int =
        System.getenv("API_SSE_AUTH_CHECK_INTERVAL_SECONDS")?.toIntOrNull() ?: 30,
) {
    // -------------------------------------------------------------------------
    // Pre-auth GET handler — responds 401/403 BEFORE the SSE session starts.
    // This handler is reached when auth fails. On success, the `sse` handler below
    // takes over (Ktor selects the most-specific handler; the `sse` route is more
    // specific than `get` for the same path when the Accept header includes text/event-stream).
    //
    // Actually in Ktor 3.x routing, `sse` and `get` on the same path are registered as
    // separate routes. Ktor picks the `sse` route for SSE requests (Accept: text/event-stream).
    // Non-SSE GET requests (e.g., curl without the SSE Accept header) hit the `get` route.
    //
    // For auth errors, we perform the check in the SSE handler itself before any `send()`,
    // which works because Ktor 3.3.3 flushes SSE response headers lazily (on first write).
    // -------------------------------------------------------------------------

    sse("/events") {
        val requestCall = call

        // -------------------------------------------------------------------------
        // Inline auth — MUST complete before any `send()` call.
        // Ktor 3.3.3 flushes response headers lazily so `call.respond(401)` works
        // here as long as no `send()` has been called yet.
        // -------------------------------------------------------------------------

        val authHeader = requestCall.request.headers["Authorization"]
        val rawToken: String? =
            when {
                authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) ->
                    authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
                allowQueryToken ->
                    requestCall.request.queryParameters["token"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                else -> null
            }

        if (rawToken == null) {
            requestCall.response.headers.append("WWW-Authenticate", "Bearer error=\"invalid_request\"")
            requestCall.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "invalid_request", "error_description" to "Missing bearer token"),
            )
            return@sse
        }

        val digest = sha256Bytes(rawToken)
        val entry = tokenEntries[HashBytes(digest)]

        if (entry == null) {
            requestCall.response.headers.append("WWW-Authenticate", "Bearer error=\"invalid_token\"")
            requestCall.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "invalid_token", "error_description" to "Invalid or expired token"),
            )
            return@sse
        }

        val principal: ApiPrincipal = entry.principal
        val tokenExpiry: Instant? = entry.expiresAt

        if (!principal.capabilities.contains(ApiCapability.READ) &&
            !principal.capabilities.contains(ApiCapability.ADMIN)
        ) {
            requestCall.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "insufficient_scope", "error_description" to "Token does not have read capability"),
            )
            return@sse
        }

        if (tokenExpiry != null && Instant.now().isAfter(tokenExpiry)) {
            requestCall.response.headers.append("WWW-Authenticate", "Bearer error=\"invalid_token\"")
            requestCall.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "invalid_token", "error_description" to "Token has expired"),
            )
            return@sse
        }

        // -------------------------------------------------------------------------
        // Scope resolution
        // -------------------------------------------------------------------------

        val queriedRoots: Set<UUID> =
            requestCall.request.queryParameters
                .getAll("root")
                ?.mapNotNull {
                    try {
                        UUID.fromString(it)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }?.toSet()
                ?: emptySet()

        val principalRoots = principal.scope.rootIds

        val effectiveRoots: Set<UUID> =
            when {
                principalRoots == null && queriedRoots.isEmpty() -> emptySet()
                principalRoots == null -> queriedRoots
                queriedRoots.isEmpty() -> principalRoots
                else -> principalRoots.intersect(queriedRoots)
            }

        // -------------------------------------------------------------------------
        // Event-type filter
        // -------------------------------------------------------------------------

        val typeFilter: Set<String>? =
            requestCall.request.queryParameters["types"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }

        // -------------------------------------------------------------------------
        // Last-Event-ID replay
        // -------------------------------------------------------------------------

        val lastEventId: Long? = requestCall.request.headers["Last-Event-ID"]?.toLongOrNull()

        // -------------------------------------------------------------------------
        // Subscribe and stream
        // -------------------------------------------------------------------------

        val subscriberId = "sse-${UUID.randomUUID()}"
        sseLogger.debug(
            "SSE connection {} opened (principal={}, roots={}, types={})",
            subscriberId,
            principal.tokenId,
            effectiveRoots,
            typeFilter,
        )

        // Capture the SseServerSession so it can be used in nested coroutines
        val session = this

        val flow = eventBus.subscribe(subscriberId, effectiveRoots, lastEventId)

        try {
            coroutineScope {
                // Periodic token-expiry check
                if (tokenExpiry != null) {
                    launch {
                        try {
                            while (isActive) {
                                delay(authCheckIntervalSeconds.seconds)
                                if (Instant.now().isAfter(tokenExpiry)) {
                                    val expiredEvent =
                                        eventBus.buildEvent(
                                            ApiEventType.AUTH_EXPIRED,
                                            modifiedAt = Instant.now(),
                                        )
                                    session.send(expiredEvent.toServerSentEvent())
                                    this@coroutineScope.cancel("Token expired")
                                    break
                                }
                            }
                        } catch (_: CancellationException) {
                            // Normal teardown
                        }
                    }
                }

                // Streaming job
                launch {
                    try {
                        flow.collect { event ->
                            if (typeFilter != null && event.event !in typeFilter) return@collect
                            session.send(event.toServerSentEvent())
                        }
                    } catch (_: CancellationException) {
                        // Normal teardown
                    }
                }
            }
        } catch (_: CancellationException) {
            sseLogger.debug("SSE connection {} closed", subscriberId)
        } finally {
            eventBus.unsubscribe(subscriberId)
            sseLogger.debug("SSE connection {} cleaned up", subscriberId)
        }
    }
}

/** Converts an [ApiEvent] to a Ktor [ServerSentEvent]. */
private fun ApiEvent.toServerSentEvent(): ServerSentEvent =
    ServerSentEvent(
        data = sseJson.encodeToString(this),
        event = this.event,
        id = this.id.toString(),
    )

/** Computes SHA-256 of the UTF-8 encoding of [input]. */
private fun sha256Bytes(input: String): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(input.toByteArray(Charsets.UTF_8))
}
