package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.identity.CredentialResolver
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.KeySetUnavailableException
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEvent
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventType
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.FROM_START
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.emit
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthHttpFixture
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthMode
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.Security
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Independent tests (test-author seat, item a0a47509, R18) for what the three bearer entry points answer when the JWKS key set
 * cannot be had (never fetched, or older than `max_stale_seconds`, which the provider reports by throwing
 * [KeySetUnavailableException]): scenarios S10, S11 and S12 of the frozen test-plan, all over real HTTP through Ktor's test
 * engine, wired with the production functions ([installMcpStreamableHttp], [installRestApiRoutes]) and, for `/mcp`, the production
 * [McpAuthMode.Credential] seam behind the shared `McpAuthHttpFixture`. All three bind pre-existing entry points (the REST
 * plugin, the SSE route, the `/mcp` plugin), so red-proof is a plain revert.
 *
 * Oracles (written from the task-scope section C and the error catalog, never from a run): an unavailable key set answers
 * `503` with the `unavailable` envelope (error kind `shedding`, `error.detail.retryAfterMs` = 15 000, the provider's base backoff)
 * and NO `WWW-Authenticate` header, because RFC 6750 section 3 reserves that header's `invalid_token` for a token that was
 * checked and found bad; a rejected JWT stays `401 unauthenticated` with `WWW-Authenticate: Bearer error="invalid_token"`; a
 * missing or empty credential stays `401` with `error="invalid_request"`; the same on `/mcp` (no session is created) and on the
 * SSE route for both the header and the `?token=` spelling; the outage is not sticky, so the same request succeeds once the key
 * set is back; a log line may name the reason but never the token.
 *
 * Negative-assertion conditions: every 503 is paired with the same request answered 200 (or an event stream) after the outage
 * ends, and every 401 with a request that differs only by the signing key; a plugin that answered 401 for the outage (the
 * pre-change behaviour) fails the 503 assertions, and one that answered 503 for everything fails the 401 assertions.
 *
 * Domain invariants satisfied by construction: JWTs carry the configured issuer and audience and a whole-second `exp` inside the
 * 24 h lifetime cap; the rejected-signature fixture reuses the published key id with another private key; the outage is a single
 * switch on one mocked key provider, so a recovery test changes nothing else.
 *
 * Not covered here (inapplicable to a mocked provider): the age == `max_stale_seconds` boundary and the +1 s case, which live in
 * the provider's own clock arithmetic (`JwksKeySetProviderCacheTest` owns that seam).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class MaxStaleUnavailableTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private const val KEY_ID = "max-stale-key"
        private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID(KEY_ID).generate()
        private val otherKey: RSAKey = RSAKeyGenerator(2048).keyID(KEY_ID).generate()
        private const val ISSUER = "https://idp.test.example"
        private const val AUDIENCE = "task-orchestrator-api"
        private const val LEASES = "/api/v1/resources/leases"
        private const val EVENTS = "/api/v1/events"
    }

    private val outage = AtomicBoolean(false)

    private fun jwksConfig() =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = ISSUER,
            audience = AUDIENCE,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    /** One mocked key provider whose every fetch throws [KeySetUnavailableException] while [outage] is set. */
    private fun provider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        coEvery { provider.getKeySet() } answers { if (outage.get()) throw KeySetUnavailableException("key set outage") else result }
        coEvery { provider.getKeySetForIssuer(any()) } answers {
            if (outage.get()) throw KeySetUnavailableException("key set outage") else result
        }
        coEvery { provider.refreshForUnknownKid(any(), any()) } returns null
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun verifier() = JwksApiVerifier(jwksConfig(), provider())

    private fun jwt(signWith: RSAKey = rsaKey): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject("max-stale-caller")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.ofEpochSecond(Instant.now().epochSecond + 300)))
                .build()
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims)
        signed.sign(RSASSASigner(signWith))
        return signed.serialize()
    }

    // ------------------------------------------------------------------ REST + SSE wiring (production functions)

    private fun emptyServer(): Server =
        Server(
            serverInfo = Implementation(name = "max-stale-test", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
        )

    private fun Application.installJwksApp(
        verifier: JwksApiVerifier,
        eventBus: ApiEventBus? = null,
        allowQueryToken: Boolean = false,
    ) {
        installMcpStreamableHttp(emptyServer(), appConfig = AppConfig.fromEnv(), mcpAuth = McpAuthMode.Local)
        val repositoryProvider = db.repositoryProvider()
        installRestApiRoutes(
            apiConfig = jwksConfig(),
            eventBus = eventBus,
            effectiveProvider = repositoryProvider,
            apiTokenEntries = emptyMap(),
            allowQueryToken = allowQueryToken,
            serverName = "max-stale-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = false,
            serverConfig = ServerConfigSource { null },
            toolContext =
                ToolExecutionContext(
                    repositoryProvider,
                    statusLabelService = NoOpStatusLabelService,
                    perRootConfigService = PerRootConfigService(repositoryProvider.configStore()),
                    unitOfWork = db.unitOfWork(),
                ),
            jwksVerifier = verifier,
            appConfig = AppConfig.fromEnv(),
        )
    }

    private fun errorOf(body: String): JsonObject =
        Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("error")
            .jsonObject

    private suspend fun assertUnavailableAnswer(
        response: HttpResponse,
        label: String,
    ) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "$label: $body")
        assertNull(response.headers[HttpHeaders.WWWAuthenticate], "$label: an unverifiable token is not called invalid")
        val error = errorOf(body)
        assertEquals("unavailable", error.getValue("code").jsonPrimitive.content, "$label: $body")
        assertEquals("shedding", error.getValue("kind").jsonPrimitive.content, "$label: $body")
        assertEquals(
            15_000L,
            error
                .getValue("detail")
                .jsonObject
                .getValue("retryAfterMs")
                .jsonPrimitive.long,
            "$label: $body"
        )
    }

    private suspend fun assertInvalidTokenAnswer(
        response: HttpResponse,
        label: String,
    ) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Unauthorized, response.status, "$label: $body")
        assertTrue(
            response.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_token\""),
            "$label: a rejected token challenges invalid_token: ${response.headers[HttpHeaders.WWWAuthenticate]}",
        )
        assertEquals("unauthenticated", errorOf(body).getValue("code").jsonPrimitive.content, "$label: $body")
    }

    // ------------------------------------------------------------------ S10 REST

    @Test
    fun `S10 REST answers 503 unavailable with retryAfterMs and no WWW-Authenticate while the key set is unavailable`() =
        testApplication {
            application { installJwksApp(verifier()) }
            val token = jwt()

            outage.set(true)
            val down = client.get(LEASES) { header(HttpHeaders.Authorization, "Bearer $token") }
            assertUnavailableAnswer(down, "S10 REST during the outage")

            outage.set(false)
            val up =
                client.get(LEASES) { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.OK, up.status, "control: the same request after the outage is served: ${up.bodyAsText()}")
        }

    @Test
    fun `S10 probe REST during the outage still answers 401 invalid_request for an absent or empty credential`() =
        testApplication {
            application { installJwksApp(verifier()) }
            outage.set(true)

            val absent = client.get(LEASES)
            assertEquals(HttpStatusCode.Unauthorized, absent.status, "absent header: ${absent.bodyAsText()}")
            assertTrue(
                absent.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_request\""),
                "absent header challenges invalid_request: ${absent.headers[HttpHeaders.WWWAuthenticate]}",
            )

            val empty = client.get(LEASES) { header(HttpHeaders.Authorization, "Bearer ") }
            assertEquals(HttpStatusCode.Unauthorized, empty.status, "empty credential: ${empty.bodyAsText()}")
            assertTrue(
                empty.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_request\""),
                "empty credential challenges invalid_request: ${empty.headers[HttpHeaders.WWWAuthenticate]}",
            )
        }

    @Test
    fun `S10 probe the bearer scheme name is case-insensitive on the unavailable answer`() =
        testApplication {
            application { installJwksApp(verifier()) }
            outage.set(true)
            val token = jwt()
            for (scheme in listOf("Bearer", "bearer", "BEARER")) {
                val response = client.get(LEASES) { header(HttpHeaders.Authorization, "$scheme $token") }
                assertUnavailableAnswer(response, "scheme $scheme")
            }
        }

    @Test
    fun `S10 the outage log line may name the reason but never carries the token`() =
        testApplication {
            application { installJwksApp(verifier()) }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            val token = jwt()
            try {
                outage.set(true)
                val down = client.get(LEASES) { header(HttpHeaders.Authorization, "Bearer $token") }
                assertEquals(HttpStatusCode.ServiceUnavailable, down.status, down.bodyAsText())
                val warns = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(warns.isNotEmpty(), "an outage answer logs a WARN")
                assertTrue(warns.none { token in it }, "no log line carries the token: $warns")
                assertTrue(appender.list.none { token in it.formattedMessage }, "no log line at any level carries the token")
            } finally {
                root.detachAppender(appender)
            }
        }

    // ------------------------------------------------------------------ S12 REST

    @Test
    fun `S12 REST keeps 401 invalid_token for a JWT with a bad signature while the key set is available`() =
        testApplication {
            application { installJwksApp(verifier()) }
            val rejected = client.get(LEASES) { header(HttpHeaders.Authorization, "Bearer ${jwt(signWith = otherKey)}") }
            assertInvalidTokenAnswer(rejected, "S12 REST rejected JWT")

            val accepted = client.get(LEASES) { header(HttpHeaders.Authorization, "Bearer ${jwt()}") }
            assertEquals(HttpStatusCode.OK, accepted.status, "control: the properly signed token is served: ${accepted.bodyAsText()}")
        }

    // ------------------------------------------------------------------ S11 /mcp

    private fun fixture() = McpAuthHttpFixture(EventLogRig.build(db.db, dir), db.databaseManager)

    @Test
    fun `S11 mcp answers 503 unavailable with retryAfterMs and no WWW-Authenticate and creates no session`() {
        val f = fixture()
        testApplication {
            application { with(f) { mount(McpAuthMode.Credential(CredentialResolver(emptyMap(), verifier()))) } }
            with(f) {
                val token = jwt()
                outage.set(true)
                for (path in listOf("/mcp", "/mcp/knowledge")) {
                    val reply = client.rpc(path, initializeBody, "Bearer $token")
                    assertEquals(HttpStatusCode.ServiceUnavailable, reply.status, "$path: ${reply.body}")
                    assertEquals("unavailable", reply.errorCode, "$path: ${reply.body}")
                    assertEquals(
                        15_000L,
                        reply.errorDetail
                            ?.get("retryAfterMs")
                            ?.jsonPrimitive
                            ?.long,
                        "$path: ${reply.body}",
                    )
                    assertNull(reply.wwwAuthenticate, "$path: no challenge on an outage")
                    assertNull(reply.sessionId, "$path: no session is created")
                    assertNull(reply.json?.get("jsonrpc"), "$path: the refusal is an envelope, never JSON-RPC: ${reply.body}")
                }

                outage.set(false)
                val control = client.rpc("/mcp", initializeBody, "Bearer $token")
                assertEquals(HttpStatusCode.OK, control.status, "control: after the outage the same token initializes: ${control.body}")
                assertNotNull(control.sessionId)
            }
        }
    }

    // ------------------------------------------------------------------ S12 /mcp

    @Test
    fun `S12 mcp keeps 401 invalid_token for a JWT with a bad signature while the key set is available`() {
        val f = fixture()
        testApplication {
            application { with(f) { mount(McpAuthMode.Credential(CredentialResolver(emptyMap(), verifier()))) } }
            with(f) {
                for (path in listOf("/mcp", "/mcp/knowledge")) {
                    val rejected = client.rpc(path, initializeBody, "Bearer ${jwt(signWith = otherKey)}")
                    assertEquals(HttpStatusCode.Unauthorized, rejected.status, "$path: ${rejected.body}")
                    assertEquals("unauthenticated", rejected.errorCode, "$path: ${rejected.body}")
                    assertTrue(
                        rejected.wwwAuthenticate.orEmpty().contains("error=\"invalid_token\""),
                        "$path: ${rejected.wwwAuthenticate}",
                    )
                    assertNull(rejected.sessionId, "$path: no session is created")
                }
                val control = client.rpc("/mcp", initializeBody, "Bearer ${jwt()}")
                assertEquals(HttpStatusCode.OK, control.status, "control: ${control.body}")
            }
        }
    }

    // ------------------------------------------------------------------ S11 / S12 SSE

    private fun bus() = ApiEventBus(source = db.repositoryProvider().eventStore())

    @Test
    fun `S11 SSE answers 503 unavailable for the Authorization header and the token query while the key set is unavailable`() =
        testApplication {
            application { installJwksApp(verifier(), eventBus = bus(), allowQueryToken = true) }
            val token = jwt()
            outage.set(true)

            val viaHeader = client.get(EVENTS) { header(HttpHeaders.Authorization, "Bearer $token") }
            assertUnavailableAnswer(viaHeader, "S11 SSE header")

            val viaQuery = client.get("$EVENTS?token=$token")
            assertUnavailableAnswer(viaQuery, "S11 SSE ?token=")
        }

    @Test
    fun `S11 SSE streams again once the key set is back`(): Unit =
        testApplication {
            val bus = bus()
            val itemId = UUID.randomUUID()
            bus.emit(ApiEventType.ITEM_CREATED, itemId = itemId)
            application { installJwksApp(verifier(), eventBus = bus, allowQueryToken = true) }
            val token = jwt()

            outage.set(true)
            assertUnavailableAnswer(client.get("$EVENTS?token=$token"), "S11 SSE before recovery")

            outage.set(false)
            val sseClient = createClient { install(ClientSSE) }
            val testJson = Json { ignoreUnknownKeys = true }
            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "$EVENTS?token=$token",
                    request = { header("Last-Event-ID", FROM_START) },
                ) {
                    incoming.take(1).toList().forEach { sse ->
                        collected.add(testJson.decodeFromString(ApiEvent.serializer(), sse.data.orEmpty()))
                    }
                }
            }
            assertEquals(1, collected.size, "control: the recovered token streams the replayed event")
            assertEquals(itemId.toString(), collected[0].itemId)
        }

    @Test
    fun `S12 SSE keeps 401 invalid_token for a JWT with a bad signature on both spellings`() =
        testApplication {
            application { installJwksApp(verifier(), eventBus = bus(), allowQueryToken = true) }
            val rejected = jwt(signWith = otherKey)

            val viaHeader = client.get(EVENTS) { header(HttpHeaders.Authorization, "Bearer $rejected") }
            assertInvalidTokenAnswer(viaHeader, "S12 SSE header")

            val viaQuery = client.get("$EVENTS?token=$rejected")
            assertEquals(HttpStatusCode.Unauthorized, viaQuery.status, "S12 SSE ?token=: ${viaQuery.bodyAsText()}")
            assertFalse(viaQuery.bodyAsText().contains("\"unavailable\""), "S12 SSE ?token= body: ${viaQuery.bodyAsText()}")
        }
}
