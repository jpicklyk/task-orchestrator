package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

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
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.Security
import java.time.Instant
import java.util.Date

/**
 * Independent test-author coverage for item 708063fa — JWKS API tokens without an `exp` claim
 * must be rejected (regardless of `nbf`), and `verifyWithExpiry` must expose a non-null,
 * whole-second-truncated `expiresAt` for every successfully verified token.
 *
 * Oracles: `current/docs/api-rest.md:106` (exp now REQUIRED in jwks mode, rejected -> 401
 * `invalid_token`), RFC 7519 §4.1.4 (`exp` is a NumericDate — whole seconds), and
 * `JwksApiVerifier.kt:32` KDoc (60s clock skew, unchanged by this fix).
 *
 * Probe recorded, not automated: an explicit JSON `"exp": null` claim was considered as a
 * distinct case from an absent claim. The DECLARATIONS for this item state both are rejected by
 * the SAME "MISSING exp" branch ("absent or JSON null"), and `JWTClaimsSet.Builder.claim(name,
 * null)` in the nimbus-jose-jwt library used here removes the claim from the map rather than
 * encoding a literal JSON null (so it degenerates to the absent case at the wire level) — a
 * dedicated test would be indistinguishable from S1/S6 below, so it is recorded here rather than
 * duplicated as a flaky assertion on library-internal serialization behaviour.
 */
class JwksApiVerifierExpiryTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("rsa-expiry-key").generate()
    }

    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"

    private fun jwksAuthConfig(algorithms: List<String> = listOf("RS256")): ApiAuthConfig.Jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = testIssuer,
            audience = testAudience,
            algorithms = algorithms,
            cacheTtlSeconds = 300,
        )

    private val freshCacheState = CacheState(fromStaleCache = false, ageSeconds = null)

    private fun rsaMockCache(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), freshCacheState)
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        coEvery { provider.getKeySetForIssuer(any()) } returns JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), freshCacheState)
        return provider
    }

    private fun verifier(): JwksApiVerifier = JwksApiVerifier(jwksAuthConfig(), rsaMockCache())

    /**
     * LOCAL claims builder: `expiry` is nullable, unlike the sibling `JwksApiVerifierTest.buildClaims`
     * (interfaces/api/v1/auth/JwksApiVerifierTest.kt:79-96) which always sets `expirationTime` — needed
     * here to mint exp-less JWTs (S1/S6/S10/S11).
     */
    private fun buildClaims(
        subject: String = "api-caller-1",
        issuer: String = testIssuer,
        audience: String = testAudience,
        expiry: Instant? = Instant.now().plusSeconds(300),
        notBefore: Instant? = null,
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
        if (expiry != null) builder.expirationTime(Date.from(expiry))
        if (notBefore != null) builder.notBeforeTime(Date.from(notBefore))
        return builder.build()
    }

    private fun signRsa(claims: JWTClaimsSet = buildClaims()): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-expiry-key").build(), claims)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    // -------------------------------------------------------------------------
    // S1 / S6 — exp-less JWT rejected by both verify() and verifyWithExpiry()
    // -------------------------------------------------------------------------

    @Test
    fun `verify returns null for a JWT with no exp claim`() =
        runTest {
            val claims = buildClaims(expiry = null)
            val result = verifier().verify(signRsa(claims))
            assertNull(result, "A JWT without exp must be rejected (deployment-contract tightening, not a nonconformance)")
        }

    @Test
    fun `verifyWithExpiry returns null for a JWT with no exp claim`() =
        runTest {
            val claims = buildClaims(expiry = null)
            val result = verifier().verifyWithExpiry(signRsa(claims))
            assertNull(result, "verifyWithExpiry must reject an exp-less JWT the same way verify() does")
        }

    // -------------------------------------------------------------------------
    // S2 / S3 / S4 — regression: existing exp-present behaviour unchanged
    // -------------------------------------------------------------------------

    @Test
    fun `verify returns a principal for a JWT with a future exp`() =
        runTest {
            val result = verifier().verify(signRsa(buildClaims(expiry = Instant.now().plusSeconds(300))))
            assertNotNull(result, "A JWT with a future exp must still be accepted")
            assertEquals("api-caller-1", result!!.tokenId)
        }

    @Test
    fun `verify returns null for a JWT with exp 120s in the past`() =
        runTest {
            val result = verifier().verify(signRsa(buildClaims(expiry = Instant.now().minusSeconds(120))))
            assertNull(result, "An exp 120s in the past is outside the 60s skew window and must be rejected")
        }

    @Test
    fun `verify accepts a JWT with exp 30s in the past, inside the 60s skew window`() =
        runTest {
            val result = verifier().verify(signRsa(buildClaims(expiry = Instant.now().minusSeconds(30))))
            assertNotNull(result, "An exp 30s in the past is inside the 60s skew window and must still be accepted")
        }

    // -------------------------------------------------------------------------
    // Boundary probes on the 60s skew cutoff (sharper than the +-30/90s cases above)
    // -------------------------------------------------------------------------

    @Test
    fun `verify accepts a JWT whose exp equals now`() =
        runTest {
            val result = verifier().verify(signRsa(buildClaims(expiry = Instant.now())))
            assertNotNull(result, "exp exactly at now is 0s past - well inside the 60s skew window")
        }

    @Test
    fun `verify accepts a JWT whose exp is 59s in the past`() =
        runTest {
            val result = verifier().verify(signRsa(buildClaims(expiry = Instant.now().minusSeconds(59))))
            assertNotNull(result, "exp 59s in the past is inside the 60s skew window")
        }

    @Test
    fun `verify rejects a JWT whose exp is 61s in the past`() =
        runTest {
            val result = verifier().verify(signRsa(buildClaims(expiry = Instant.now().minusSeconds(61))))
            assertNull(result, "exp 61s in the past is outside the 60s skew window")
        }

    // -------------------------------------------------------------------------
    // S5 — verifyWithExpiry exposes a whole-second-truncated expiresAt on success
    // -------------------------------------------------------------------------

    @Test
    fun `verifyWithExpiry returns VerifiedApiToken with expiresAt truncated to whole seconds`() =
        runTest {
            val mintedExpiry = Instant.now().plusSeconds(300)
            val result = verifier().verifyWithExpiry(signRsa(buildClaims(expiry = mintedExpiry)))
            assertNotNull(result, "Valid JWT must verify")
            assertEquals("api-caller-1", result!!.principal.tokenId)
            assertEquals(
                Instant.ofEpochSecond(mintedExpiry.epochSecond),
                result.expiresAt,
                "expiresAt must be the exp NumericDate truncated to whole seconds, not the raw minted Instant",
            )
        }

    // -------------------------------------------------------------------------
    // S11 — nbf present, exp absent: exp-less rejection takes priority
    // -------------------------------------------------------------------------

    @Test
    fun `verify rejects a JWT with a satisfied nbf but no exp`() =
        runTest {
            val claims = buildClaims(expiry = null, notBefore = Instant.now().minusSeconds(10))
            val result = verifier().verify(signRsa(claims))
            assertNull(result, "A satisfied nbf must not compensate for a missing exp")
        }

    // -------------------------------------------------------------------------
    // S10 — exp-less JWT rejected on a normal REST route (AuthenticationPlugin.kt:174), proving
    // the rejection is reached through production wiring, not only the unit-level verifier.
    // Harness mirrors McpJwksRestAuthTest.kt's installJwksApp (interfaces/mcp/McpJwksRestAuthTest.kt:150-169).
    // -------------------------------------------------------------------------

    private fun emptyMcpServer(): Server =
        Server(
            serverInfo = Implementation(name = "jwks-expiry-rest-test", version = "1.0.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
                ),
        )

    private fun inMemoryRepositoryProvider(): DefaultRepositoryProvider =
        DefaultRepositoryProvider(
            DatabaseManager(
                Database.connect(
                    "jdbc:h2:mem:jwksexpiryrest_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                ),
            ).also { DirectDatabaseSchemaManager().updateSchema() },
        )

    private fun io.ktor.server.application.Application.installJwksRestApp(verifier: JwksApiVerifier) {
        installMcpStreamableHttp(emptyMcpServer())
        installRestApiRoutes(
            apiConfig = jwksAuthConfig(),
            eventBus = null,
            effectiveProvider = inMemoryRepositoryProvider(),
            apiTokenEntries = emptyMap(),
            allowQueryToken = false,
            serverName = "jwks-expiry-rest-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = false,
            noteSchemaService = NoOpNoteSchemaService,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            idempotencyCache = IdempotencyCache(),
            jwksVerifier = verifier,
        )
    }

    @Test
    fun `exp-less JWT is rejected on a normal REST route in jwks mode`() =
        testApplication {
            application { installJwksRestApp(verifier()) }

            val expLessToken = signRsa(buildClaims(expiry = null))
            val response =
                client.get("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer $expLessToken")
                }

            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "An exp-less JWT must 401 on a normal REST route (red without the exp requirement, " +
                    "since it previously authenticated indefinitely)",
            )
        }
}
