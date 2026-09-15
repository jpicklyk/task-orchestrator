package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `3a6c0e5a` — SSE `?token=` gating and unauthenticated
 * mode, wired through the REAL production functions ([installMcpStreamableHttp] +
 * [installRestApiRoutes]), exactly as [io.github.jpicklyk.mcptask.current.interfaces.mcp.McpRestAuthBypassTest]
 * does. Unlike `EventRoutesTest` (which calls `eventRoutes(...)` directly), this suite proves the
 * FULL wiring: [ApiBearerAuth][io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth]
 * is an application-level plugin that intercepts every request, and the SSE route is only reachable
 * because `installRestApiRoutes` adds `"/api/v1/events"` to its `publicPaths` -- a set matched
 * against the DECODED PATH ONLY (no query string). Before the fix, that set was matched against
 * `call.request.local.uri` (which DOES include the query string), so `/api/v1/events?token=...`
 * never matched the exact `/api/v1/events` entry and `ApiBearerAuth` 401'd every query-token SSE
 * connection before the SSE route's own inline auth (which understands `?token=`) ever ran. S1 and
 * S9 below are the scenarios that most directly exercise that fix under the full app-level plugin.
 *
 * Oracles: `current/docs/api-rest.md` §1 (auth modes), §6 (error codes), §21 (SSE); `docs/api/openapi.yaml`
 * `/events`; `CLAUDE.md` env var table (`API_ALLOW_QUERY_TOKEN_FOR_SSE` default `false`).
 *
 * Per the frozen `test-plan` note, S-ids are stable identifiers -- do not renumber.
 */
class SseAuthFullWiringTest {
    companion object {
        private const val VALID_TOKEN = "sse-full-wiring-valid-token"
        private const val NO_CAP_TOKEN = "sse-full-wiring-nocap-token"
        private const val GARBAGE_TOKEN = "sse-full-wiring-not-a-real-token"

        private val replayJson = Json { ignoreUnknownKeys = true }
    }

    /** A READ-capable principal (token entry keyed by [VALID_TOKEN]) plus a capability-less
     * principal (keyed by [NO_CAP_TOKEN], WRITE_ITEMS only -- no READ/ADMIN) for the S8 403 case.
     * [GARBAGE_TOKEN] is deliberately absent from both maps for the S5 401 case. */
    private fun bearerConfig(): Pair<ApiAuthConfig.Bearer, Map<HashBytes, BearerTokenStore.TokenEntry>> {
        val readPrincipal =
            ApiPrincipal(
                tokenId = "sse-full-wiring-read",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER,
            )
        val noCapPrincipal =
            ApiPrincipal(
                tokenId = "sse-full-wiring-nocap",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.WRITE_ITEMS),
                authMode = ApiAuthMode.BEARER,
            )
        val readKey = HashBytes(sha256(VALID_TOKEN))
        val noCapKey = HashBytes(sha256(NO_CAP_TOKEN))
        val config = ApiAuthConfig.Bearer(tokens = mapOf(readKey to readPrincipal, noCapKey to noCapPrincipal))
        val entries =
            mapOf(
                readKey to BearerTokenStore.TokenEntry(readPrincipal, expiresAt = null),
                noCapKey to BearerTokenStore.TokenEntry(noCapPrincipal, expiresAt = null),
            )
        return config to entries
    }

    private fun emptyServer(): Server =
        Server(
            serverInfo = Implementation(name = "sse-full-wiring-test", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
        )

    /** Mirrors CurrentMcpServer's production HTTP-mode wiring: MCP transport first, then the REST
     * API (which registers `/api/v1/events` only when [bus] is non-null). Same two production
     * functions [McpRestAuthBypassTest][io.github.jpicklyk.mcptask.current.interfaces.mcp.McpRestAuthBypassTest]
     * uses. */
    private fun Application.wireProd(
        apiConfig: ApiAuthConfig,
        bus: ApiEventBus?,
        provider: DefaultRepositoryProvider,
        tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
        allowQueryToken: Boolean,
    ) {
        installMcpStreamableHttp(emptyServer())
        installRestApiRoutes(
            apiConfig = apiConfig,
            eventBus = bus,
            effectiveProvider = provider,
            apiTokenEntries = tokenEntries,
            allowQueryToken = allowQueryToken,
            serverName = "sse-full-wiring-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = false,
            noteSchemaService = NoOpNoteSchemaService,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            idempotencyCache = IdempotencyCache(),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S1 - allowQueryToken enabled, valid token in query, no Authorization header, connects and delivers event`(): Unit =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            application { wireProd(apiConfig, bus, provider, entries, allowQueryToken = true) }

            val itemId = UUID.randomUUID()
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now()), emptySet())

            val sseClient = createClient { install(ClientSSE) }
            val collected = mutableListOf<String>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/api/v1/events?token=$VALID_TOKEN",
                    request = { header("Last-Event-ID", "0") },
                ) {
                    incoming.take(1).toList().forEach { collected.add(it.event ?: "") }
                }
            }
            assertTrue(
                collected.contains(ApiEventType.ITEM_CREATED),
                "S1: query-token-only SSE connection (no header, allowQueryToken=true) must stream. Got: $collected",
            )
        }

    @Test
    fun `S2 - bearer mode with Authorization header and no query token connects and delivers event`(): Unit =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            application { wireProd(apiConfig, bus, provider, entries, allowQueryToken = false) }

            val itemId = UUID.randomUUID()
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now()), emptySet())

            val sseClient = createClient { install(ClientSSE) }
            val collected = mutableListOf<String>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/api/v1/events",
                    request = {
                        header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                        header("Last-Event-ID", "0")
                    },
                ) {
                    incoming.take(1).toList().forEach { collected.add(it.event ?: "") }
                }
            }
            assertTrue(
                collected.contains(ApiEventType.ITEM_CREATED),
                "S2: Authorization-header SSE must keep working when allowQueryToken=false. Got: $collected",
            )
        }

    @Test
    fun `S3 - unauthenticated mode wiring connects to SSE with no credentials at all`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            application { wireProd(ApiAuthConfig.Unauthenticated, bus, provider, emptyMap(), allowQueryToken = false) }

            val itemId = UUID.randomUUID()
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now()), emptySet())

            val sseClient = createClient { install(ClientSSE) }
            val collected = mutableListOf<String>()
            withTimeout(10.seconds) {
                sseClient.sse(urlString = "/api/v1/events", request = { header("Last-Event-ID", "0") }) {
                    incoming.take(1).toList().forEach { collected.add(it.event ?: "") }
                }
            }
            assertTrue(
                collected.contains(ApiEventType.ITEM_CREATED),
                "S3: ApiAuthConfig.Unauthenticated must stream with zero credentials (synthetic ADMIN principal). Got: $collected",
            )
        }

    // -------------------------------------------------------------------------------------------
    // Failure path
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S4 - allowQueryToken false rejects a query-token-only request with 401`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = false) }
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/events?token=$VALID_TOKEN").status,
                "S4: with the flag off, ?token= must not authenticate (CLAUDE.md default is false)",
            )
        }

    @Test
    fun `S5 - allowQueryToken true with an unknown garbage token returns 401`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = true) }
            val response = client.get("/api/v1/events?token=$GARBAGE_TOKEN")
            assertEquals(HttpStatusCode.Unauthorized, response.status, "S5: an unrecognized query token must 401, not 200")
        }

    @Test
    fun `S6 - a query token must not leak authentication onto a non-SSE route`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = true) }
            val response = client.get("/api/v1/items?token=$VALID_TOKEN")
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "S6: ?token= is an SSE-route-only exception; /api/v1/items must still require a header",
            )
        }

    @Test
    fun `S7 - no credential - items 401, health 200, mcp initialize is not gated by the REST bearer check`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = false) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/items").status, "S7: /api/v1/items requires auth")
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/health").status, "S7: /api/v1/health stays public")

            val mcp =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                            """"clientInfo":{"name":"t","version":"1.0"}}}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, mcp.status, "S7: /mcp must stay open without a REST bearer token")
        }

    // -------------------------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S8 - a query token lacking READ or ADMIN returns 403`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = true) }
            val response = client.get("/api/v1/events?token=$NO_CAP_TOKEN")
            assertEquals(HttpStatusCode.Forbidden, response.status, "S8: sec.21 'Requires READ or ADMIN capability'")
        }

    @Test
    fun `S9 - valid query token with root and types params still authenticates - path matched, query ignored`(): Unit =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            application { wireProd(apiConfig, bus, provider, entries, allowQueryToken = true) }

            val root = UUID.randomUUID()
            val itemId = UUID.randomUUID()
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now()), setOf(root))

            val sseClient = createClient { install(ClientSSE) }
            val collected = mutableListOf<String>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/api/v1/events?token=$VALID_TOKEN&root=$root&types=item.created",
                    request = { header("Last-Event-ID", "0") },
                ) {
                    incoming.take(1).toList().forEach { collected.add(it.event ?: "") }
                }
            }
            assertTrue(
                collected.contains(ApiEventType.ITEM_CREATED),
                "S9: extra query params (root, types) alongside token must not break the exact-path " +
                    "publicPaths exemption at the ApiBearerAuth (app-level) layer. Got: $collected",
            )
        }

    @Test
    fun `S10 - empty, absent, and whitespace token values all 401 when the flag is on`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = true) }

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/events?token=").status,
                "S10: empty ?token= value must 401",
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/events").status,
                "S10: absent ?token= (and no header) must 401",
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/events?token=%20").status,
                "S10: whitespace-only ?token= must 401",
            )
        }

    // -------------------------------------------------------------------------------------------
    // Adversarial probes (recorded in test-manifest regardless of outcome)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `probe - eventsfoo and events2 do not inherit the events publicPaths exemption`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = false) }
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/eventsfoo").status,
                "probe: /api/v1/eventsfoo must not match the exact /api/v1/events publicPaths entry",
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/events2").status,
                "probe: /api/v1/events2 must not match the exact /api/v1/events publicPaths entry",
            )
        }

    @Test
    fun `probe - trailing slash on the events path is not silently exempted`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = false) }
            val response = client.get("/api/v1/events/")
            assertTrue(
                response.status != HttpStatusCode.OK,
                "probe: /api/v1/events/ (trailing slash) must not stream without credentials. Got: ${response.status}",
            )
        }

    @Test
    fun `probe - BEARER uppercase scheme is accepted - RFC 7235 auth-scheme tokens are case-insensitive`(): Unit =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            application { wireProd(apiConfig, bus, provider, entries, allowQueryToken = false) }

            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now()), emptySet())

            val sseClient = createClient { install(ClientSSE) }
            val collected = mutableListOf<String>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/api/v1/events",
                    request = {
                        header(HttpHeaders.Authorization, "BEARER $VALID_TOKEN")
                        header("Last-Event-ID", "0")
                    },
                ) {
                    incoming.take(1).toList().forEach { collected.add(it.event ?: "") }
                }
            }
            assertTrue(
                collected.contains(ApiEventType.ITEM_CREATED),
                "probe: per RFC 7235 §2.1, auth-scheme tokens are case-insensitive -- 'BEARER' must work like 'Bearer'",
            )
        }

    @Test
    fun `probe - percent-encoded events path still enforces auth - no path-decoding bypass`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = false) }
            // %65 decodes to 'e' -- this path is byte-for-byte "/api/v1/events" once decoded.
            val response = client.get("/api/v1/%65vents")
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "probe: a percent-encoded /events path must still require auth like the literal path, not bypass to 200",
            )
        }

    @Test
    fun `probe - duplicate token query params resolve deterministically without hanging or crashing`(): Unit =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = true) }
            // sec.21 does not document a resolution order for repeated ?token= params, so this probe
            // only guards against a crash or an indefinite hang -- not a specific winner. If the
            // garbage token wins, the call returns a fast 401. If the valid token wins, the request
            // opens a real SSE stream and the plain (non-SSE) client blocks reading its body forever;
            // withTimeoutOrNull bounds that case instead of hanging the suite.
            val result = withTimeoutOrNull(3.seconds) { client.get("/api/v1/events?token=$GARBAGE_TOKEN&token=$VALID_TOKEN") }
            if (result != null) {
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    result.status,
                    "probe: duplicate ?token= resolving to a non-streaming response must be 401, never a server error",
                )
            }
        }

    @Test
    fun `probe - health with a query string stays public`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            application { wireProd(apiConfig, ApiEventBus(), buildH2RepositoryProvider(), entries, allowQueryToken = false) }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/health?x=1").status,
                "probe: /api/v1/health?x=1 must stay public despite the query string (same fix, sibling publicPaths entry)",
            )
        }

    @Test
    fun `probe - Last-Event-ID reconnect replays correctly over the query-token SSE path`(): Unit =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            application { wireProd(apiConfig, bus, provider, entries, allowQueryToken = true) }

            val e1 = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
            bus.publish(e1, emptySet())
            val e2 = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
            bus.publish(e2, emptySet())

            val sseClient = createClient { install(ClientSSE) }
            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/api/v1/events?token=$VALID_TOKEN",
                    request = { header("Last-Event-ID", e1.id.toString()) },
                ) {
                    incoming.take(1).toList().forEach { sse ->
                        collected.add(replayJson.decodeFromString(ApiEvent.serializer(), sse.data.orEmpty()))
                    }
                }
            }
            assertEquals(1, collected.size, "probe: exactly one event (e2) should replay after Last-Event-ID=e1.id")
            assertEquals(
                e2.itemId,
                collected[0].itemId,
                "probe: reconnect over ?token= must replay only events after Last-Event-ID, same as header auth",
            )
        }
}
