package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventType
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `59989657` (test-plan S1, S2, S3, S7, S8).
 *
 * Exercises `AuthenticationPlugin.kt`'s `extractBearerToken` behavior through the REAL wired
 * surfaces -- [ApiBearerAuth] (REST) and `eventRoutes` (SSE pre-flight auth) -- never by calling
 * the (`internal`) helper directly. The point is to prove the wired outcome the KDoc oracle
 * documents, not the private helper in isolation.
 *
 * Oracles (frozen at queue phase, supplied verbatim in the dispatch prompt's declarations block):
 * RFC 7235 §2.1 (auth-scheme names are case-insensitive), RFC 6750 §2.1 (`1*SP` before the
 * credential) and §3.1 (`WWW-Authenticate` error codes), `current/docs/api-rest.md` :118
 * (`invalid_request` for missing/empty; `invalid_token` for a non-matching credential), and the
 * frozen `diagnosis` note's D-a decision: exactly ONE `Bearer`/`bearer` prefix is ever stripped,
 * so `"Bearer bearer T"` must resolve to the literal credential `"bearer T"`, which cannot be a
 * valid `b64token` (RFC 6750 has no internal whitespace) and must therefore fail as
 * `invalid_token`, never silently authenticate as `T`.
 */
class BearerSchemeParsingTest {
    companion object {
        private const val VALID_TOKEN = "bearer-scheme-valid-token"
    }

    private fun bearerConfig(): ApiAuthConfig.Bearer {
        val principal =
            ApiPrincipal(
                tokenId = "bearer-scheme-principal",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER,
            )
        return ApiAuthConfig.Bearer(tokens = mapOf(HashBytes(sha256(VALID_TOKEN)) to principal))
    }

    private fun jwksAuthConfig(): ApiAuthConfig.Jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.example/.well-known/jwks.json",
            issuer = "https://idp.example",
            audience = "task-orchestrator-api",
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    private fun jwksPrincipal(): ApiPrincipal =
        ApiPrincipal(
            tokenId = "jwks-caller",
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = setOf(ApiCapability.READ),
            authMode = ApiAuthMode.JWKS,
        )

    /** Installs [ApiBearerAuth] in bearer mode with a single probe route -- isolated from the full
     * item-route stack, since only the auth plugin's scheme-parsing outcome is under test. */
    private fun Application.wireBearerProbe() {
        val config = bearerConfig()
        install(ContentNegotiation) { json(McpJson) }
        install(ApiBearerAuth) {
            authConfig = config
            tokenEntries = config.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
        }
        routing {
            get("/api/v1/items") { call.respondText("ok") }
        }
    }

    /** Installs [ApiBearerAuth] in jwks mode with [verifier] wired directly, same probe route. */
    private fun Application.wireJwksProbe(verifier: JwksApiVerifier) {
        install(ContentNegotiation) { json(McpJson) }
        install(ApiBearerAuth) {
            authConfig = jwksAuthConfig()
            jwksVerifier = verifier
        }
        routing {
            get("/api/v1/items") { call.respondText("ok") }
        }
    }

    // -------------------------------------------------------------------------------------------
    // S1 -- bearer mode, case-insensitive scheme + 1*SP, happy path (RFC 7235 §2.1; RFC 6750 1*SP)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S1 - fully-uppercase BEARER scheme authorizes`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "BEARER $VALID_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status, "S1: fully-uppercase BEARER must authorize (RFC 7235 §2.1)")
        }

    @Test
    fun `S1 - mixed-case bEaReR scheme authorizes`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "bEaReR $VALID_TOKEN") }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "S1: mixed-case bEaReR must authorize (RFC 7235 §2.1 scheme case-insensitivity)",
            )
        }

    @Test
    fun `S1 - multiple spaces before the credential still authorizes after trim`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "Bearer   $VALID_TOKEN") }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "S1: 'Bearer   T' (extra SP) must still resolve to T once trimmed (D-a: remainder trimmed)",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S2 -- jwks mode: the exact string handed to JwksApiVerifier.verify must be the token only
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S2 - jwks mode hands verify exactly the credential after a single scheme strip`() =
        testApplication {
            val verifier = mockk<JwksApiVerifier>()
            coEvery { verifier.verify("valid.jwt.token") } returns jwksPrincipal()
            application { wireJwksProbe(verifier) }

            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "BEARER valid.jwt.token") }

            assertEquals(HttpStatusCode.OK, response.status, "S2: a mixed-case scheme in jwks mode must still authorize")
            coVerify(exactly = 1) { verifier.verify("valid.jwt.token") }
        }

    // -------------------------------------------------------------------------------------------
    // S3 -- SSE pre-flight auth (EventRoutes.kt), same case/space tolerance as the REST plugin
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S3 - SSE accepts mixed-case bEaReR scheme and streams`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val config = bearerConfig()
            val entries = config.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing { eventRoutes(bus, entries) }
            }

            val itemId = UUID.randomUUID()
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now()), emptySet())

            val sseClient = createClient { install(ClientSSE) }
            val collected = mutableListOf<String>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/events",
                    request = {
                        header(HttpHeaders.Authorization, "bEaReR $VALID_TOKEN")
                        header("Last-Event-ID", "0")
                    },
                ) {
                    incoming.take(1).toList().forEach { collected.add(it.event ?: "") }
                }
            }
            assertTrue(
                collected.contains(ApiEventType.ITEM_CREATED),
                "S3: mixed-case bEaReR must open the SSE stream. Got: $collected",
            )
        }

    @Test
    fun `S3 - SSE accepts multiple spaces before the credential and streams`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val config = bearerConfig()
            val entries = config.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing { eventRoutes(bus, entries) }
            }

            val itemId = UUID.randomUUID()
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now()), emptySet())

            val sseClient = createClient { install(ClientSSE) }
            val collected = mutableListOf<String>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/events",
                    request = {
                        header(HttpHeaders.Authorization, "Bearer   $VALID_TOKEN")
                        header("Last-Event-ID", "0")
                    },
                ) {
                    incoming.take(1).toList().forEach { collected.add(it.event ?: "") }
                }
            }
            assertTrue(
                collected.contains(ApiEventType.ITEM_CREATED),
                "S3: 'Bearer   T' (extra SP) must open the SSE stream once trimmed. Got: $collected",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S7 -- malformed / absent scheme -> 401 invalid_request (api-rest.md :118; RFC 6750 §3.1)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S7 - bare Bearer with no separator or credential returns 401 invalid_request`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "Bearer") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                response.headers[HttpHeaders.WWWAuthenticate]?.contains("error=\"invalid_request\"") == true,
                "S7: bare 'Bearer' (too short to even carry a SP) must 401 invalid_request. " +
                    "Got: ${response.headers[HttpHeaders.WWWAuthenticate]}",
            )
        }

    @Test
    fun `S7 - Bearer with trailing space and empty credential returns 401 invalid_request`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "Bearer ") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                response.headers[HttpHeaders.WWWAuthenticate]?.contains("error=\"invalid_request\"") == true,
                "S7: 'Bearer ' resolves to an empty credential -> 401 invalid_request (api-rest.md :118)",
            )
            val body = response.bodyAsText()
            assertTrue(body.contains("Empty token"), "S7: expected the documented 'Empty token' error_description. Got: $body")
        }

    @Test
    fun `S7 - BearerT with no separator at all returns 401 invalid_request`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "Bearer$VALID_TOKEN") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                response.headers[HttpHeaders.WWWAuthenticate]?.contains("error=\"invalid_request\"") == true,
                "S7: 'Bearer<token>' with no SP at all must not match the scheme. " +
                    "Got: ${response.headers[HttpHeaders.WWWAuthenticate]}",
            )
        }

    @Test
    fun `S7 - tab instead of space separator returns 401 invalid_request`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "Bearer\t$VALID_TOKEN") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                response.headers[HttpHeaders.WWWAuthenticate]?.contains("error=\"invalid_request\"") == true,
                "S7: a TAB is not SP (0x20) -- must not satisfy 1*SP. Got: ${response.headers[HttpHeaders.WWWAuthenticate]}",
            )
        }

    @Test
    fun `S7 - mismatched Basic scheme returns 401 invalid_request`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items") { header(HttpHeaders.Authorization, "Basic $VALID_TOKEN") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                response.headers[HttpHeaders.WWWAuthenticate]?.contains("error=\"invalid_request\"") == true,
                "S7: a non-Bearer scheme must 401 invalid_request, not invalid_token. " +
                    "Got: ${response.headers[HttpHeaders.WWWAuthenticate]}",
            )
        }

    @Test
    fun `S7 - no Authorization header at all returns 401 invalid_request`() =
        testApplication {
            application { wireBearerProbe() }
            val response = client.get("/api/v1/items")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                response.headers[HttpHeaders.WWWAuthenticate]?.contains("error=\"invalid_request\"") == true,
                "S7: a missing Authorization header must 401 invalid_request. Got: ${response.headers[HttpHeaders.WWWAuthenticate]}",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S8 -- duplicate scheme prefix: only one strip; the doubled remainder must not authenticate
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S8 - Bearer bearer T strips only once and 401s invalid_token, never authenticating as T`() =
        testApplication {
            val verifier = mockk<JwksApiVerifier>()
            coEvery { verifier.verify(any()) } returns null
            application { wireJwksProbe(verifier) }

            val response =
                client.get("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer bearer $VALID_TOKEN")
                }

            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "S8: 'Bearer bearer T' must never authenticate as T -- only one scheme is ever stripped",
            )
            assertTrue(
                response.headers[HttpHeaders.WWWAuthenticate]?.contains("error=\"invalid_token\"") == true,
                "S8: the doubled remainder is a non-null credential that fails verification -> invalid_token, " +
                    "not invalid_request. Got: ${response.headers[HttpHeaders.WWWAuthenticate]}",
            )
            // Proves the exact string that reached the verifier: only ONE "bearer " prefix was
            // stripped, so the credential handed downstream still carries the inner "bearer " token.
            coVerify(exactly = 1) { verifier.verify("bearer $VALID_TOKEN") }
        }
}
