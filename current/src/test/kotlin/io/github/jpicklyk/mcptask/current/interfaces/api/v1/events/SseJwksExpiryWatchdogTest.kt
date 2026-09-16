package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.bouncycastle.jce.provider.BouncyCastleProvider
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
 * Independent test-author coverage for item 708063fa — the SSE expiry watchdog
 * (`EventRoutes.kt:511-534`) must run for every jwks-authenticated SSE session, not only bearer
 * sessions. Before the fix, the jwks branch hardcoded `expiry = null`, so no jwks SSE connection
 * ever scheduled the watchdog regardless of how long the underlying JWT had been expired.
 *
 * Harness for S7/S9 mirrors the existing bearer-mode watchdog test
 * `EventRoutesTest.kt:543-577` (`expired token during connection emits auth_expired and closes
 * within check interval`) — same 1s check-interval / generous-margin timing discipline, applied to
 * a jwks-authenticated session instead of a bearer one. S8 mirrors the 401 assertions in
 * `EventRoutesTest.kt:143-159` and the raw-JSON-map body shape documented for this item.
 *
 * Oracle: `current/docs/api-rest.md:1477` — the SSE handler periodically checks expiry and closes
 * the stream on `auth.expired`, stated with no bearer-mode qualifier.
 */
class SseJwksExpiryWatchdogTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("sse-jwks-expiry-key").generate()
    }

    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"

    private val jwksConfig =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = testIssuer,
            audience = testAudience,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    private fun rsaMockCache(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        coEvery { provider.getKeySet() } returns result
        coEvery { provider.getKeySetForIssuer(any()) } returns result
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun verifier(): JwksApiVerifier = JwksApiVerifier(jwksConfig, rsaMockCache())

    /**
     * LOCAL claims builder: `expiry` is nullable, unlike the sibling `JwksApiVerifierTest`/
     * `McpJwksRestAuthTest` builders which always set `expirationTime` — needed to mint exp-less
     * JWTs (S8).
     */
    private fun buildClaims(
        subject: String = "sse-jwks-caller",
        issuer: String = testIssuer,
        audience: String = testAudience,
        expiry: Instant? = Instant.now().plusSeconds(300),
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
        if (expiry != null) builder.expirationTime(Date.from(expiry))
        return builder.build()
    }

    private fun signRsa(claims: JWTClaimsSet = buildClaims()): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("sse-jwks-expiry-key").build(), claims)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    // -------------------------------------------------------------------------
    // S7 — watchdog closes a jwks SSE session once its JWT's exp passes
    // -------------------------------------------------------------------------

    @Test
    fun `jwks SSE session closes with auth_expired once the JWT exp passes`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            // Token expires shortly after connect; the periodic check (1s cadence) then fires -
            // same shape as the bearer-mode regression test, but the credential is jwks.
            val token = signRsa(buildClaims(expiry = Instant.now().plusMillis(1200)))

            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(
                        bus,
                        tokenEntries = emptyMap(),
                        jwksVerifier = verifier(),
                        authCheckIntervalSeconds = 1,
                        authConfig = jwksConfig,
                    )
                }
            }
            val sseClient = createClient { install(ClientSSE) }

            // Collect every event the server sends until it closes the stream after auth.expired.
            val eventTypes = mutableListOf<String>()
            withTimeout(15.seconds) {
                sseClient.sse(
                    urlString = "/events",
                    request = { header(HttpHeaders.Authorization, "Bearer $token") },
                ) {
                    incoming.toList().forEach { eventTypes.add(it.event ?: "") }
                }
            }

            assertTrue(
                eventTypes.contains(ApiEventType.AUTH_EXPIRED),
                "Expected auth.expired once the jwks JWT's exp passed while the SSE session was open. Got: $eventTypes",
            )
        }

    // -------------------------------------------------------------------------
    // S8 — exp-less JWT rejected at SSE connect (401 invalid_token), no stream established
    // -------------------------------------------------------------------------

    @Test
    fun `exp-less JWT is rejected at jwks SSE connect with 401 invalid_token`() =
        testApplication {
            val bus = ApiEventBus()
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(
                        bus,
                        tokenEntries = emptyMap(),
                        jwksVerifier = verifier(),
                        authCheckIntervalSeconds = 30,
                        authConfig = jwksConfig,
                    )
                }
            }
            val expLessToken = signRsa(buildClaims(expiry = null))

            val response =
                client.get("/events") {
                    header(HttpHeaders.Authorization, "Bearer $expLessToken")
                }

            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "An exp-less JWT must be rejected at connect, not stream indefinitely",
            )
            val wwwAuthenticate = response.headers[HttpHeaders.WWWAuthenticate]
            assertTrue(
                wwwAuthenticate?.contains("error=\"invalid_token\"") == true,
                "Expected WWW-Authenticate to name invalid_token. Got: $wwwAuthenticate",
            )
            val body = response.bodyAsText()
            assertTrue(body.contains("\"invalid_token\""), "Expected invalid_token error code in body. Got: $body")
            assertTrue(
                body.contains("Invalid or expired token"),
                "Expected the exp-less outcome to share the same 401 body as any other verifyWithExpiry-null case. Got: $body",
            )
        }

    // -------------------------------------------------------------------------
    // S9 — a session with a distant exp survives past two check intervals, no spurious auth.expired
    // -------------------------------------------------------------------------

    @Test
    fun `jwks SSE session with distant exp survives two check intervals and still delivers events`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val token = signRsa(buildClaims(expiry = Instant.now().plusSeconds(300)))

            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(
                        bus,
                        tokenEntries = emptyMap(),
                        jwksVerifier = verifier(),
                        authCheckIntervalSeconds = 1,
                        authConfig = jwksConfig,
                    )
                }
            }
            val sseClient = createClient { install(ClientSSE) }

            val eventTypes = mutableListOf<String>()
            withTimeout(15.seconds) {
                sseClient.sse(
                    urlString = "/events",
                    request = { header(HttpHeaders.Authorization, "Bearer $token") },
                ) {
                    // Wait past two 1s check intervals before publishing, proving a session with a
                    // distant exp is not spuriously closed by the watchdog: if it had been closed
                    // (with auth.expired), the connection would already be gone by the time the
                    // publish below happens, and the subsequent collect would not observe item.created.
                    delay(2500)
                    bus.publish(
                        bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now()),
                        emptySet(),
                    )
                    incoming.take(1).toList().forEach { eventTypes.add(it.event ?: "") }
                }
            }

            assertEquals(
                listOf(ApiEventType.ITEM_CREATED),
                eventTypes,
                "Expected only the published item event, delivered after surviving two check intervals with no auth.expired",
            )
        }
}
