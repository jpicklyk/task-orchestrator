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
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
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

/** The resolved SSE principal, stashed by the pre-flight auth plugin for the SSE handler to read. */
private val SsePrincipalKey: AttributeKey<ApiPrincipal> = AttributeKey("SseResolvedPrincipal")

/** The connection's token expiry (nullable), stashed by the pre-flight auth plugin. */
private val SseTokenExpiryKey: AttributeKey<Instant> = AttributeKey("SseTokenExpiry")

/**
 * Configuration for [sseInlineAuthPlugin].
 */
private class SseInlineAuthConfig {
    var tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap()
    var allowQueryToken: Boolean = false
}

/**
 * Route-scoped plugin that authenticates the SSE request in the `Plugins` phase — BEFORE the
 * `sse { }` handler sets up the streaming response.
 *
 * This is the correct place for SSE auth: Ktor's `sse { }` invokes its handler inside
 * `SSEServerContent.writeTo`, which runs during the response-body phase, AFTER the 200 status is
 * committed. An auth check inside `sse { }` could therefore no longer send a 401. By running here
 * (an `onCall` handler), the 401/403 is sent before the SSE response is ever constructed.
 *
 * On success it stashes the resolved [ApiPrincipal] (and token expiry) into `call.attributes` for
 * the SSE handler to read.
 */
private val sseInlineAuthPlugin =
    createRouteScopedPlugin(
        name = "SseInlineAuth",
        createConfiguration = ::SseInlineAuthConfig,
    ) {
        val tokenEntries = pluginConfig.tokenEntries
        val allowQueryToken = pluginConfig.allowQueryToken

        onCall { call ->
            // Resolution order: Authorization header → (opt-in) ?token= query param → 401.
            val authHeader = call.request.headers["Authorization"]
            val rawToken: String? =
                when {
                    authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) ->
                        // The matched prefix is always 7 chars ("Bearer ") regardless of case;
                        // strip by length so non-standard casing (e.g. "BEARER ") isn't left in the token.
                        authHeader.substring("Bearer ".length).trim()
                    allowQueryToken ->
                        call.request.queryParameters["token"]
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    else -> null
                }

            if (rawToken == null) {
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_request\"")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "invalid_request", "error_description" to "Missing bearer token"),
                )
                return@onCall
            }

            val digest = sha256Bytes(rawToken)
            val entry = tokenEntries[HashBytes(digest)]
            if (entry == null) {
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "invalid_token", "error_description" to "Invalid or expired token"),
                )
                return@onCall
            }

            val principal = entry.principal
            if (!principal.capabilities.contains(ApiCapability.READ) &&
                !principal.capabilities.contains(ApiCapability.ADMIN)
            ) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf(
                        "error" to "insufficient_scope",
                        "error_description" to "Token does not have read capability",
                    ),
                )
                return@onCall
            }

            val expiry = entry.expiresAt
            if (expiry != null && Instant.now().isAfter(expiry)) {
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "invalid_token", "error_description" to "Token has expired"),
                )
                return@onCall
            }

            // Auth OK — stash for the SSE handler.
            call.attributes.put(SsePrincipalKey, principal)
            if (expiry != null) call.attributes.put(SseTokenExpiryKey, expiry)
        }
    }

/**
 * Registers the `GET /api/v1/events` SSE endpoint.
 *
 * ## Authentication (INLINE — performed in a pre-flight route plugin, NOT the route-plugin chain)
 *
 * Auth runs in [sseInlineAuthPlugin] (`onCall`, `Plugins` phase) — before the `sse { }` handler
 * constructs the streaming response. This is required because Ktor's `sse { }` invokes its handler
 * inside the response-body phase (`SSEServerContent.writeTo`), after the 200 status is committed;
 * a 401 cannot be sent from inside `sse { }`. We deliberately do NOT reuse the `ApiBearerAuth`
 * route plugin (§5.8.1) — this SSE-specific plugin additionally supports the opt-in `?token=`
 * query-param path and a `read`/`admin` capability gate.
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
    // Wrap the SSE handler in a dedicated child route so the pre-flight auth plugin is scoped
    // ONLY to /events and does not affect sibling routes.
    route("/events") {
        install(sseInlineAuthPlugin) {
            this.tokenEntries = tokenEntries
            this.allowQueryToken = allowQueryToken
        }

        sse {
            // Auth already ran in the pre-flight plugin. If the principal is absent, the plugin
            // already responded 401/403 and finished the call — but guard defensively.
            val principal = call.attributes.getOrNull(SsePrincipalKey) ?: return@sse
            val tokenExpiry: Instant? = call.attributes.getOrNull(SseTokenExpiryKey)

            // -----------------------------------------------------------------
            // Scope resolution: intersection(?root=, principal.scope.rootIds)
            // -----------------------------------------------------------------
            val queriedRoots: Set<UUID> =
                call.request.queryParameters
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

            // -----------------------------------------------------------------
            // Optional event-type filter
            // -----------------------------------------------------------------
            val typeFilter: Set<String>? =
                call.request.queryParameters["types"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }

            // -----------------------------------------------------------------
            // Last-Event-ID replay
            // -----------------------------------------------------------------
            val lastEventId: Long? = call.request.headers["Last-Event-ID"]?.toLongOrNull()

            val subscriberId = "sse-${UUID.randomUUID()}"
            sseLogger.debug(
                "SSE connection {} opened (principal={}, roots={}, types={})",
                subscriberId,
                principal.tokenId,
                effectiveRoots,
                typeFilter,
            )

            // Capture the SseServerSession so it can be used in nested coroutines.
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
