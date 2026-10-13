package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.configName
import io.github.jpicklyk.mcptask.current.application.api.grants
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_UNAUTH_PRINCIPAL
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.credentialResolverFor
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.directorOnlyForbidden
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.extractBearerToken
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.isDirectorOnly
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.unauthenticated
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.withHostOf
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelopeError
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEvent
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventType
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.header
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
private val SsePrincipalKey: AttributeKey<Principal> = AttributeKey("SseResolvedPrincipal")

/** The connection's token expiry (nullable), stashed by the pre-flight auth plugin. */
private val SseTokenExpiryKey: AttributeKey<Instant> = AttributeKey("SseTokenExpiry")

/**
 * The parsed `?root=` query values of a `GET /api/v1/events` request.
 *
 * Three states must stay distinguishable, because they lead to three different outcomes and the
 * `Set<UUID>` alone collapses two of them:
 *
 * - **absent** — no narrowing requested; the principal's own scope applies. ([roots] empty,
 *   [malformed] false.)
 * - **malformed** ([malformed] true) — at least one supplied value is not a UUID (`?root=`, `?root=garbage`,
 *   `?root=<uuid>&root=garbage`). Rejected with 400 `invalid_request`: a value is never silently dropped (Q32 ended
 *   the AR-59 leniency that accepted a mix of valid and invalid values).
 * - **narrowing** ([roots] non-empty) — intersected with the principal's scope.
 */
private class RootQueryParams(
    /** The values that parsed as UUIDs; empty when the parameter was absent or [malformed]. */
    val roots: Set<UUID>,
    /** True when the parameter was present and at least one of its values is not a UUID. */
    val malformed: Boolean,
)

/**
 * Parses the repeated `root` query parameter into a [RootQueryParams].
 *
 * Shared by the pre-flight [sseInlineAuthPlugin] (which turns malformed/out-of-scope requests into
 * 400/403 before the SSE response is committed) and the `sse { }` body (which computes the
 * effective subscription). Both MUST see the same parse, or the pre-flight check would validate
 * something other than what is subscribed to.
 *
 * @param raw The result of `queryParameters.getAll("root")` — null when the parameter is absent.
 */
private fun parseRootQueryParams(raw: List<String>?): RootQueryParams {
    if (raw == null) return RootQueryParams(roots = emptySet(), malformed = false)
    val parsed =
        raw.map {
            try {
                UUID.fromString(it.trim())
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    if (parsed.isEmpty() || parsed.any { it == null }) return RootQueryParams(roots = emptySet(), malformed = true)
    return RootQueryParams(roots = parsed.filterNotNull().toSet(), malformed = false)
}

/**
 * Root-scope pre-flight for `GET /api/v1/events`. Responds and returns `true` when the request
 * must be rejected; returns `false` (having responded to nothing) when it may proceed.
 *
 * Two rejections, both fail-closed:
 * - **400 `invalid_request`** — `?root=` present with a value that is not a UUID. Dropping it could
 *   WIDEN the subscription to [principal]'s whole scope.
 * - **403 `forbidden` {scope: root}** — [principal] is root-scoped and its scope does not intersect
 *   the requested roots. [ApiEventBus.subscribe] treats an empty root set as "all roots", so an
 *   empty intersection must never reach it: a token scoped to one root would otherwise obtain the
 *   full cross-root stream by asking for a root it cannot see. 403 rather than 404 mirrors
 *   [ScopeGuard], which deliberately does not make scope a fuzzing oracle for holders of
 *   valid tokens.
 *
 * Both bodies are the 4.0 error envelope. This MUST run in the pre-flight `onCall` handler rather than inside `sse { }`: the SSE handler
 * executes in the response-body phase, after the 200 status is committed, where neither status
 * can still be sent.
 */
private suspend fun respondIfRootQueryRejected(
    call: ApplicationCall,
    principal: Principal,
): Boolean {
    val rootQuery = parseRootQueryParams(call.request.queryParameters.getAll("root"))
    if (rootQuery.malformed) {
        call.respondEnvelopeError(invalidField("root", "must be a UUID; every value is checked"))
        return true
    }
    val principalRoots = principal.scope.rootIds
    if (principalRoots != null &&
        rootQuery.roots.isNotEmpty() &&
        principalRoots.intersect(rootQuery.roots).isEmpty()
    ) {
        sseLogger.debug(
            "SSE connection rejected for principal {}: requested roots {} outside scope",
            principal.id,
            rootQuery.roots,
        )
        call.respondEnvelopeError(ScopeGuard.forbidden(ForbiddenScope.ROOT, principalRoots.map { it.toString() }))
        return true
    }
    return false
}

/**
 * Configuration for [sseInlineAuthPlugin].
 */
private class SseInlineAuthConfig {
    var tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap()
    var allowQueryToken: Boolean = false

    /**
     * REST JWT verifier for jwks mode. When set, a presented token that is not found among
     * [tokenEntries] (bearer mode is empty in jwks mode) is validated as a JWT. Null in bearer
     * and disabled modes.
     */
    var jwksVerifier: JwksApiVerifier? = null

    /**
     * The resolved REST auth mode. When [ApiAuthConfig.Unauthenticated] (opt-in:
     * `API_AUTH_MODE=none` + `API_ALLOW_UNAUTHENTICATED=true`), every SSE connection is attached
     * [LOCAL_UNAUTH_PRINCIPAL] with no token check at all -- mirrors [ApiBearerAuth]'s
     * Unauthenticated branch so unauthenticated mode does not 401 every SSE connection.
     */
    var authConfig: ApiAuthConfig = ApiAuthConfig.Disabled

    /**
     * Repository used to look up item tags for tag-scoped principals (a non-empty `scope.tagsInclude`), both
     * here (pre-flight 403 when unavailable) and in the `sse { }` collect body (per-event drop).
     * Null when the caller wired [eventRoutes] without one -- tag-scoped connections are then
     * rejected rather than streamed unfiltered.
     */
    var workItemRepository: WorkItemRepository? = null
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
 * The credential is resolved through the one bearer pipeline (`CredentialResolver`), like [ApiBearerAuth]; a
 * director-profile credential is refused here (the stream is not the director path).
 *
 * On success it stashes the resolved [Principal] (and token expiry) into `call.attributes` for
 * the SSE handler to read.
 */
private val sseInlineAuthPlugin =
    createRouteScopedPlugin(
        name = "SseInlineAuth",
        createConfiguration = ::SseInlineAuthConfig,
    ) {
        val allowQueryToken = pluginConfig.allowQueryToken
        val authConfig = pluginConfig.authConfig
        val workItemRepository = pluginConfig.workItemRepository
        val resolver =
            credentialResolverFor(authConfig, pluginConfig.tokenEntries, pluginConfig.jwksVerifier) { Instant.now() }

        onCall { call ->
            // Opt-in unauthenticated mode (API_AUTH_MODE=none + API_ALLOW_UNAUTHENTICATED=true):
            // attach the synthetic ADMIN/unrestricted principal and skip the token check entirely --
            // mirrors ApiBearerAuth's Unauthenticated branch. Must run before the header/?token=
            // resolution below, or a credential-less SSE connection is 401'd in this mode too.
            if (authConfig is ApiAuthConfig.Unauthenticated) {
                if (respondIfRootQueryRejected(call, LOCAL_UNAUTH_PRINCIPAL)) return@onCall
                call.attributes.put(SsePrincipalKey, LOCAL_UNAUTH_PRINCIPAL.withHostOf(call))
                return@onCall
            }

            // Resolution order: Authorization header → (opt-in) ?token= query param → 401.
            //
            // extractBearerToken returns null when the header is absent OR does not use the
            // Bearer scheme at all (wrong scheme, missing separator) — that case falls through to
            // the opt-in ?token= path below. A present-but-empty Bearer credential ("Bearer   ")
            // returns "" (not null), which is NOT treated as "no header" — it proceeds to the
            // digest/JWKS checks below and fails there with 401 invalid_token, same as before this
            // fix.
            val authHeader = call.request.headers["Authorization"]
            val bearerToken = authHeader?.let { extractBearerToken(it) }
            val rawToken: String? =
                when {
                    bearerToken != null -> bearerToken
                    allowQueryToken ->
                        call.request.queryParameters["token"]
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    else -> null
                }

            if (rawToken == null) {
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_request\"")
                call.respondEnvelopeError(unauthenticated("Missing bearer token."))
                return@onCall
            }

            // Resolve through the one bearer pipeline. A token-file entry carries its own expiry (null: none); a
            // JWT always carries its `exp`. The check happens ONCE, at connect; an SSE stream outlives it, so the
            // expiry is carried through to the periodic watchdog below.
            val resolved = resolver?.resolve(rawToken)
            if (resolved == null) {
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                call.respondEnvelopeError(unauthenticated("Invalid or expired token."))
                return@onCall
            }
            if (resolved.isDirectorOnly()) {
                call.respondEnvelopeError(directorOnlyForbidden())
                return@onCall
            }
            val principal = resolved.principal.withHostOf(call)
            val expiry = resolved.expiresAt

            if (!principal.capabilities.grants(ApiCapability.READ)) {
                call.respondEnvelopeError(ScopeGuard.forbidden(ForbiddenScope.CAPABILITY, ApiCapability.READ.configName()))
                return@onCall
            }

            // Fail closed: a tag-scoped principal can only be safely streamed to when we can
            // look up item tags per event (the collect-site filter below). Without a repository
            // wired, reject rather than fall back to the pre-fix unfiltered stream.
            if (principal.scope.tagsInclude.isNotEmpty() && workItemRepository == null) {
                // Tag-scoped streaming needs per-event filtering, which is unavailable here: fail closed.
                call.respondEnvelopeError(ScopeGuard.forbidden(ForbiddenScope.TAG, principal.scope.tagsInclude))
                return@onCall
            }

            if (respondIfRootQueryRejected(call, principal)) return@onCall

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
 * 0. If [authConfig] is [ApiAuthConfig.Unauthenticated] → every connection is attached the
 *    synthetic ADMIN principal, no credential required (opt-in local dev/test mode).
 * 1. `Authorization: Bearer <token>` header (always allowed)
 * 2. Else if `API_ALLOW_QUERY_TOKEN_FOR_SSE=true` AND `?token=` present → query-token auth
 * 3. Else → 401
 *
 * ## Scope filtering
 * `?root=<uuid>[&root=<uuid>...]` restricts events to those root subtrees.
 * Effective subscription = intersection(`?root=`, `principal.scope.rootIds`).
 *
 * That intersection is enforced FAIL-CLOSED, in the pre-flight plugin (see the note above on why
 * a rejection cannot come from inside `sse { }`):
 * - A root-scoped principal (`scope.rootIds` non-null) whose `?root=` values do not intersect its
 *   scope gets **403 `insufficient_scope`** — an empty intersection is an empty subscription, not
 *   a universal one. [ApiEventBus.subscribe] reads an empty root set as "all roots", so admitting
 *   this request would hand a token scoped to one root the entire cross-root stream.
 * - A `?root=` parameter that yields no valid UUID at all (`?root=`, `?root=garbage`) gets
 *   **400 `validation_error`**, because dropping every value would widen the subscription to the
 *   principal's full scope instead of narrowing it. Mixed valid/invalid values still narrow and
 *   are accepted.
 *
 * Every projected row carries its root, so the bus filters live delivery and replay by root alike;
 * only control events (`sync.lost`, `auth.expired`) reach every subscriber.
 *
 * The `tags_include` half of scope is NOT applied at the bus-subscription level ([ApiEventBus]
 * has no tag dimension) -- it is enforced per event, in the `sse { }` collect body below, using
 * the same [ScopeGuard] predicate every tool and read route uses, via [workItemRepository]. This applies identically to live events
 * and to `Last-Event-ID` replay, since both flow through the one collect site. A tag-scoped
 * principal connecting when [workItemRepository] is null is rejected with 403 in the pre-flight
 * plugin -- fail closed rather than stream unfiltered. Non-tag-scoped principals take exactly
 * the pre-fix code path (no lookups, no behavior change).
 *
 * ## Event-type filter
 * `?types=a,b` restricts the stream to those event types — EXCEPT [ApiEventType.CONTROL_EVENTS]
 * (`sync.lost`, `auth.expired`), which are always delivered. These report the state of the stream
 * itself, so a filtered client would otherwise keep operating on incomplete state (or an expired
 * credential) with no signal.
 *
 * ## Resume and gap reporting
 * Event ids are `seq` values of the durable `events` table. `Last-Event-ID` replays the stored
 * events with a higher seq (within the replay window). When the requested id is not replayable --
 * older than the replay window (`API_SSE_BUFFER_SIZE`), above the newest seq, below the seq
 * floor (every id the pre-4.0 in-memory ring buffer issued; the floor itself is a valid cursor), or present but unparsable -- the bus
 * emits a `sync.lost` event carrying a
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.SyncLostReason] as the first frame
 * of the connection, before any replayed event. A blank header counts as no resume attempt.
 *
 * ## Event-ID namespace separation
 * IDs from [ApiEventBus] (the events table's seq) are INDEPENDENT of `/mcp`'s `EventStore`. Do NOT
 * mix `Last-Event-ID` values across the two SSE channels.
 *
 * @param eventBus The shared [ApiEventBus] instance.
 * @param tokenEntries Pre-loaded bearer token entries with expiry metadata.
 * @param allowQueryToken Whether `?token=` query param is accepted.
 * @param jwksVerifier REST JWT verifier for jwks mode; null in bearer/disabled modes. When set,
 *   a token not found among [tokenEntries] is validated as a JWT.
 * @param authCheckIntervalSeconds Interval between token-expiry checks.
 * @param authConfig The resolved REST auth mode; only [ApiAuthConfig.Unauthenticated] changes
 *   behavior here (bypasses the token check entirely). Defaults to [ApiAuthConfig.Disabled],
 *   which falls through to the normal header/`?token=` resolution above.
 * @param workItemRepository Used to resolve item tags for the `tags_include` scope filter
 *   described above. Null (the default) preserves prior behavior for callers that do not pass
 *   it, EXCEPT that a tag-scoped principal is now rejected with 403 rather than streamed
 *   unfiltered.
 * @param redactAttribution `AppConfig.apiRedactNoteAttribution`: hide the event actor from non-ADMIN
 *   principals. Required (no default): the caller passes the typed config value instead of this route reading the environment.
 */
fun Route.eventRoutes(
    eventBus: ApiEventBus,
    tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap(),
    allowQueryToken: Boolean = false,
    jwksVerifier: JwksApiVerifier? = null,
    authCheckIntervalSeconds: Int = 30,
    authConfig: ApiAuthConfig = ApiAuthConfig.Disabled,
    workItemRepository: WorkItemRepository? = null,
    redactAttribution: Boolean,
) {
    // Wrap the SSE handler in a dedicated child route so the pre-flight auth plugin is scoped
    // ONLY to /events and does not affect sibling routes.
    route("/events") {
        install(sseInlineAuthPlugin) {
            this.tokenEntries = tokenEntries
            this.allowQueryToken = allowQueryToken
            this.jwksVerifier = jwksVerifier
            this.authConfig = authConfig
            this.workItemRepository = workItemRepository
        }

        sse {
            // Auth already ran in the pre-flight plugin. If the principal is absent, the plugin
            // already responded 401/403 and finished the call — but guard defensively.
            val principal = call.attributes.getOrNull(SsePrincipalKey) ?: return@sse
            val tokenExpiry: Instant? = call.attributes.getOrNull(SseTokenExpiryKey)

            // -----------------------------------------------------------------
            // Scope resolution: intersection(?root=, principal.scope.rootIds)
            // -----------------------------------------------------------------
            val queriedRoots: Set<UUID> = parseRootQueryParams(call.request.queryParameters.getAll("root")).roots

            val principalRoots = principal.scope.rootIds
            val effectiveRoots: Set<UUID> =
                when {
                    principalRoots == null && queriedRoots.isEmpty() -> emptySet()
                    principalRoots == null -> queriedRoots
                    queriedRoots.isEmpty() -> principalRoots
                    else -> principalRoots.intersect(queriedRoots)
                }

            // Defensive belt for the pre-flight check above. An empty effectiveRoots for a
            // root-SCOPED principal is a denial, but ApiEventBus.subscribe reads an empty set as
            // "all roots" — so subscribing here would hand out the full cross-root stream. The
            // pre-flight plugin already rejected the reachable path with 403; if anything else
            // ever produces this state (e.g. a principal carrying a non-null but empty rootIds),
            // close the connection rather than open an unrestricted one.
            if (principalRoots != null && effectiveRoots.isEmpty()) {
                sseLogger.warn(
                    "SSE connection refused for principal {}: empty effective root scope (principalRoots={}, queriedRoots={})",
                    principal.id,
                    principalRoots,
                    queriedRoots,
                )
                return@sse
            }

            // -----------------------------------------------------------------
            // Optional event-type filter
            //
            // Control events (ApiEventType.CONTROL_EVENTS) are NOT filterable — see the
            // collect site below. A client that suppressed sync.lost would go on believing it
            // had a contiguous stream.
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
            //
            // `resumeRequested` distinguishes "no resume attempted" from "resume attempted with a
            // cursor we cannot parse". A present, non-blank but non-numeric header is a resume
            // attempt: the bus reports it as sync.lost/unknown_event_id rather than silently
            // treating the connection as fresh. A blank header counts as absent (per the SSE
            // spec, a client that has no last id sends nothing meaningful).
            // -----------------------------------------------------------------
            val lastEventIdHeader: String? = call.request.headers["Last-Event-ID"]?.trim()
            val resumeRequested: Boolean = !lastEventIdHeader.isNullOrEmpty()
            val lastEventId: Long? = lastEventIdHeader?.toLongOrNull()

            val subscriberId = "sse-${UUID.randomUUID()}"
            sseLogger.debug(
                "SSE connection {} opened (principal={}, roots={}, types={})",
                subscriberId,
                principal.id,
                effectiveRoots,
                typeFilter,
            )

            // Capture the SseServerSession so it can be used in nested coroutines.
            val session = this
            val flow = eventBus.connect(subscriberId, effectiveRoots, lastEventId, resumeRequested)

            // -----------------------------------------------------------------
            // Tag-scope filter (tags_include half of scope; see "Scope filtering" above)
            // -----------------------------------------------------------------
            // Per-connection cache of item id -> allowed, so a tag-scoped connection does not
            // re-query the repository for every event about an item it has already resolved.
            // The collect loop below runs on a single coroutine, so no synchronization is needed.
            val tagScopeCache = mutableMapOf<UUID, Boolean>()

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
                                // Control events bypass the ?types= filter: they carry stream
                                // state (a gap, an expired credential), not a data change, and a
                                // filtered client needs them more than an unfiltered one does.
                                if (typeFilter != null &&
                                    event.event !in typeFilter &&
                                    event.event !in ApiEventType.CONTROL_EVENTS
                                ) {
                                    return@collect
                                }
                                if (!isEventVisibleToTagScope(
                                        event = event,
                                        principal = principal,
                                        repository = workItemRepository,
                                        cache = tagScopeCache,
                                    )
                                ) {
                                    return@collect
                                }
                                // Attribution redaction, on egress only (the events table keeps the
                                // unredacted principal), so live and Last-Event-ID replay are identical.
                                // Same rule as the tools note redaction: hidden only when the flag is on
                                // AND the caller is not ADMIN. rootId is never redacted.
                                val outbound =
                                    if (redactAttribution &&
                                        event.actor != null &&
                                        !principal.capabilities.contains(ApiCapability.ADMIN)
                                    ) {
                                        event.copy(actor = null)
                                    } else {
                                        event
                                    }
                                session.send(outbound.toServerSentEvent())
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

/**
 * Applies the `tags_include` half of scope filtering to a single event, for the `flow.collect`
 * site in [eventRoutes] -- the one place that sees both replayed and live events.
 *
 * Returns `true` immediately (no lookup) when [principal] has no tag scope, so non-tag-scoped
 * and unscoped connections are byte-identical to the pre-fix behavior.
 *
 * Bus-level events ([ApiEvent.itemId] null, e.g. `sync.lost`/`auth.expired`) always pass --
 * there is no item to check tags against.
 *
 * Fails CLOSED: an unparsable [ApiEvent.itemId], a missing [repository] (should not happen --
 * the pre-flight plugin already rejected this connection in that case), or a repository lookup
 * error (via [ScopeGuard.admitted]) all result in the event being dropped.
 *
 * [cache] holds the per-connection id -> allowed decision. `item.*` and `scope.*` events bypass
 * and refresh the cache so a mid-stream tag change (add/remove) is honoured starting with the
 * very next event about that item; `note.*`/`dependency.*` events reuse the cached decision.
 */
private suspend fun isEventVisibleToTagScope(
    event: ApiEvent,
    principal: Principal,
    repository: WorkItemRepository?,
    cache: MutableMap<UUID, Boolean>,
): Boolean {
    if (principal.scope.tagsInclude.isEmpty()) return true
    val itemId = event.itemId ?: return true
    val uuid =
        try {
            UUID.fromString(itemId)
        } catch (_: IllegalArgumentException) {
            return false
        }

    val bypassCache = event.event.startsWith("item.") || event.event.startsWith("scope.")
    if (!bypassCache) {
        cache[uuid]?.let { return it }
    }

    val allowed =
        if (repository == null) {
            false
        } else {
            // The tag half only: the root half is applied at the bus subscription (every projected row carries its root).
            val tagScope = AccessScope.principal(rootIds = null, tagsInclude = principal.scope.tagsInclude)
            ScopeGuard.admitted(tagScope, setOf(uuid), repository).contains(uuid)
        }
    cache[uuid] = allowed
    return allowed
}

/** Converts an [ApiEvent] to a Ktor [ServerSentEvent]. */
private fun ApiEvent.toServerSentEvent(): ServerSentEvent =
    ServerSentEvent(
        data = sseJson.encodeToString(this),
        event = this.event,
        id = this.id.toString(),
    )
