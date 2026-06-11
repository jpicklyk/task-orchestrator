package io.github.jpicklyk.mcptask.current.interfaces.mcp

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEvent
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventType
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Security
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Integration coverage for the REST API in **jwks mode** — the path that shipped broken because
 * `install(ApiBearerAuth)` never set `jwksVerifier`, so the plugin's `ApiAuthConfig.Jwks` branch
 * found a null verifier and 401'd every request (and SSE).
 *
 * This wires the SAME production functions ([installMcpStreamableHttp] + [installRestApiRoutes])
 * with the API enabled in jwks mode, passing a [JwksApiVerifier] backed by an in-test RSA keypair
 * (served via a mocked [JwksKeySetProvider]). The valid-JWT cases are regression tests that would
 * have caught the original bug; the bad-alg / expired cases prove the verifier's checks are not
 * weakened by the wiring.
 */
class McpJwksRestAuthTest {
    companion object {
        init {
            // RSA signing/verification needs no BouncyCastle, but match the sibling test's setup.
            if (Security.getProvider("BC") == null) {
                Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("rest-jwks-key").generate()
    }

    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"

    private fun jwksConfig(algorithms: List<String> = listOf("RS256")): ApiAuthConfig.Jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = testIssuer,
            audience = testAudience,
            algorithms = algorithms,
            cacheTtlSeconds = 300,
        )

    /** Mock key provider that always serves the in-test RSA public key. */
    private fun rsaKeyProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        coEvery { provider.getKeySet() } returns result
        coEvery { provider.getKeySetForIssuer(any()) } returns result
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun verifier(algorithms: List<String> = listOf("RS256")): JwksApiVerifier =
        JwksApiVerifier(jwksConfig(algorithms), rsaKeyProvider())

    private fun buildClaims(
        subject: String = "jwks-api-caller",
        issuer: String = testIssuer,
        audience: String = testAudience,
        expiry: Instant = Instant.now().plusSeconds(300),
    ): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .expirationTime(Date.from(expiry))
            .build()

    private fun signRs256(claims: JWTClaimsSet = buildClaims()): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rest-jwks-key").build(), claims)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun emptyServer(): Server =
        Server(
            serverInfo = Implementation(name = "jwks-rest-test", version = "1.0.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
                ),
        )

    private fun inMemoryProvider(): DefaultRepositoryProvider =
        DefaultRepositoryProvider(
            DatabaseManager(
                Database.connect(
                    "jdbc:h2:mem:jwksrest_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                ),
            ).also { DirectDatabaseSchemaManager().updateSchema() },
        )

    /**
     * Installs the production wiring with the API enabled in jwks mode. The [eventBus] is wired so
     * the SSE route is also registered (and given the jwks verifier).
     */
    private fun io.ktor.server.application.Application.installJwksApp(
        verifier: JwksApiVerifier,
        eventBus: ApiEventBus? = null,
    ) {
        installMcpStreamableHttp(emptyServer())
        installRestApiRoutes(
            apiConfig = jwksConfig(),
            eventBus = eventBus,
            effectiveProvider = inMemoryProvider(),
            apiTokenEntries = emptyMap(), // jwks mode loads no bearer entries
            allowQueryToken = false,
            serverName = "jwks-rest-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = false,
            noteSchemaService = NoOpNoteSchemaService,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            idempotencyCache = IdempotencyCache(),
            jwksVerifier = verifier,
        )
    }

    // -------------------------------------------------------------------------
    // REST routes — jwks mode
    // -------------------------------------------------------------------------

    @Test
    fun `valid JWT authorizes a REST request`() =
        testApplication {
            application { installJwksApp(verifier()) }

            val response =
                client.get("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer ${signRs256()}")
                }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "A valid JWT must authorize /api/v1 in jwks mode (regression for the missing jwksVerifier wiring)",
            )
        }

    @Test
    fun `missing token returns 401 in jwks mode`() =
        testApplication {
            application { installJwksApp(verifier()) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/items").status)
        }

    @Test
    fun `JWT signed with disallowed algorithm returns 401`() =
        testApplication {
            // Verifier only allows RS512; the token is RS256 → rejected by the allowlist check.
            application { installJwksApp(verifier(algorithms = listOf("RS512"))) }

            val response =
                client.get("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer ${signRs256()}")
                }
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "A JWT whose alg is not in the allowlist must 401 (verifier checks must not be weakened)",
            )
        }

    @Test
    fun `expired JWT returns 401`() =
        testApplication {
            application { installJwksApp(verifier()) }

            val expired = signRs256(buildClaims(expiry = Instant.now().minusSeconds(3600)))
            val response =
                client.get("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer $expired")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "An expired JWT must 401")
        }

    @Test
    fun `mcp endpoint stays open in jwks mode`() =
        testApplication {
            application { installJwksApp(verifier()) }
            // /mcp must remain reachable without any REST credential, even in jwks mode (the public-path
            // bypass is auth-mode independent). Initialize succeeds with no Authorization header.
            val mcp =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                            """"clientInfo":{"name":"t","version":"1.0"}}}""",
                    )
                }
            assertEquals(
                HttpStatusCode.OK,
                mcp.status,
                "/mcp must stay open in jwks mode (no bearer token sent)",
            )
        }

    // -------------------------------------------------------------------------
    // SSE — jwks mode
    // -------------------------------------------------------------------------

    @Test
    fun `SSE missing token returns 401 in jwks mode`() =
        testApplication {
            val bus = ApiEventBus()
            application { installJwksApp(verifier(), eventBus = bus) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/events").status)
        }

    @Test
    fun `SSE invalid JWT returns 401 in jwks mode`() =
        testApplication {
            val bus = ApiEventBus()
            application { installJwksApp(verifier(), eventBus = bus) }
            val response =
                client.get("/api/v1/events") {
                    header(HttpHeaders.Authorization, "Bearer not-a-valid-jwt")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `SSE streams a replayed event to a JWT-authenticated client in jwks mode`(): Unit =
        testApplication {
            val bus = ApiEventBus()

            // Publish an event to the ring buffer BEFORE subscribing so replay can deliver it.
            val itemId = UUID.randomUUID()
            val prePublished = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now())
            bus.publish(prePublished, emptySet())

            application { installJwksApp(verifier(), eventBus = bus) }

            val sseClient = createClient { install(ClientSSE) }
            val testJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/api/v1/events",
                    request = {
                        header(HttpHeaders.Authorization, "Bearer ${signRs256()}")
                        header("Last-Event-ID", "0")
                    },
                ) {
                    incoming.take(1).toList().forEach { sse ->
                        collected.add(testJson.decodeFromString(ApiEvent.serializer(), sse.data.orEmpty()))
                    }
                }
            }

            assertEquals(1, collected.size, "Expected exactly one replayed event over the JWT-authed SSE stream")
            assertEquals(ApiEventType.ITEM_CREATED, collected[0].event)
            assertTrue(
                collected[0].itemId == itemId.toString(),
                "Replayed event should carry the published item id. Got: ${collected[0].itemId}",
            )
        }
}
