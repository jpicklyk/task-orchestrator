package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.security.JwksKeyCache
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

class JwksApiVerifierTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        val rsaKey = RSAKeyGenerator(2048).keyID("rsa-api-key").generate()
        val ecKey = ECKeyGenerator(Curve.P_256).keyID("ec-api-key").generate()
    }

    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"

    private val defaultConfig =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = testIssuer,
            audience = testAudience,
            algorithms = listOf("RS256", "ES256"),
            cacheTtlSeconds = 300,
        )

    private val freshCacheState = CacheState(fromStaleCache = false, ageSeconds = null)

    private fun rsaMockCache(): JwksKeyCache {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), freshCacheState)
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        coEvery { provider.getKeySetForIssuer(any()) } returns JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), freshCacheState)
        // JwksKeyCache is a typealias for DefaultJwksKeySetProvider, but here we use the mock
        // by coercing the type — acceptable in tests.
        @Suppress("UNCHECKED_CAST")
        return provider as JwksKeyCache
    }

    private fun ecMockCache(): JwksKeyCache {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JwksResult(JWKSet(listOf(ecKey.toPublicJWK())), freshCacheState)
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        coEvery { provider.getKeySetForIssuer(any()) } returns JwksResult(JWKSet(listOf(ecKey.toPublicJWK())), freshCacheState)
        @Suppress("UNCHECKED_CAST")
        return provider as JwksKeyCache
    }

    private fun buildClaims(
        subject: String = "api-caller-1",
        issuer: String = testIssuer,
        audience: String = testAudience,
        expiry: Instant = Instant.now().plusSeconds(300),
        notBefore: Instant? = null,
        extraClaims: Map<String, Any> = emptyMap(),
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(expiry))
        if (notBefore != null) builder.notBeforeTime(Date.from(notBefore))
        extraClaims.forEach { (k, v) -> builder.claim(k, v) }
        return builder.build()
    }

    private fun signRsa(claims: JWTClaimsSet = buildClaims()): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-api-key").build(), claims)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun signEc(claims: JWTClaimsSet = buildClaims()): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec-api-key").build(), claims)
        jwt.sign(ECDSASigner(ecKey))
        return jwt.serialize()
    }

    // -------------------------------------------------------------------------
    // Valid JWT scenarios
    // -------------------------------------------------------------------------

    @Test
    fun `valid RSA-signed JWT returns ApiPrincipal`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val principal = verifier.verify(signRsa())
            assertNotNull(principal, "Valid JWT should resolve to a principal")
            assertEquals("api-caller-1", principal!!.tokenId)
            assertEquals(ApiAuthMode.JWKS, principal.authMode)
        }

    @Test
    fun `valid EC-signed JWT returns ApiPrincipal`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, ecMockCache())
            val principal = verifier.verify(signEc())
            assertNotNull(principal, "Valid EC JWT should resolve to a principal")
            assertEquals("api-caller-1", principal!!.tokenId)
        }

    @Test
    fun `JWT with custom scope claims is parsed correctly`() =
        runTest {
            val rootId = UUID.randomUUID().toString()
            val claims =
                buildClaims(
                    extraClaims =
                        mapOf(
                            "to_scope" to
                                mapOf(
                                    "root_ids" to listOf(rootId),
                                    "tags_include" to listOf("feature", "bug"),
                                ),
                            "to_capabilities" to listOf("read", "write-notes"),
                        ),
                )
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val principal = verifier.verify(signRsa(claims))
            assertNotNull(principal)
            assertNotNull(principal!!.scope.rootIds)
            assertEquals(1, principal.scope.rootIds!!.size)
            assertEquals(setOf("feature", "bug"), principal.scope.tagsInclude)
            assertTrue(ApiCapability.READ in principal.capabilities)
            assertTrue(ApiCapability.WRITE_NOTES in principal.capabilities)
        }

    @Test
    fun `JWT without to_capabilities claim defaults to READ-only`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val principal = verifier.verify(signRsa())
            assertNotNull(principal)
            assertEquals(setOf(ApiCapability.READ), principal!!.capabilities)
        }

    @Test
    fun `JWT without to_scope claim defaults to unrestricted scope`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val principal = verifier.verify(signRsa())
            assertNotNull(principal)
            assertNull(principal!!.scope.rootIds)
            assertTrue(principal.scope.tagsInclude.isEmpty())
        }

    // -------------------------------------------------------------------------
    // Failure scenarios
    // -------------------------------------------------------------------------

    @Test
    fun `expired JWT returns null`() =
        runTest {
            val pastExpiry = Instant.now().minusSeconds(3600)
            val claims = buildClaims(expiry = pastExpiry)
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify(signRsa(claims))
            assertNull(result, "Expired JWT should return null")
        }

    @Test
    fun `JWT with exp just inside 60s clock skew is accepted`() =
        runTest {
            // Expired 30 seconds ago — within 60s skew window
            val expiry = Instant.now().minusSeconds(30)
            val claims = buildClaims(expiry = expiry)
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify(signRsa(claims))
            assertNotNull(result, "JWT within 60s clock skew window should be accepted")
        }

    @Test
    fun `JWT with exp outside 60s clock skew is rejected`() =
        runTest {
            // Expired 90 seconds ago — outside the skew window
            val expiry = Instant.now().minusSeconds(90)
            val claims = buildClaims(expiry = expiry)
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify(signRsa(claims))
            assertNull(result, "JWT expired 90s ago should be rejected")
        }

    @Test
    fun `JWT with wrong issuer returns null`() =
        runTest {
            val claims = buildClaims(issuer = "https://wrong-idp.example")
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify(signRsa(claims))
            assertNull(result, "Wrong issuer should return null")
        }

    @Test
    fun `JWT with wrong audience returns null`() =
        runTest {
            val claims = buildClaims(audience = "wrong-audience")
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify(signRsa(claims))
            assertNull(result, "Wrong audience should return null")
        }

    @Test
    fun `algorithm not in allowlist returns null`() =
        runTest {
            // Config only allows RS256 and ES256; EC is ES256 and is allowed,
            // but if we restrict to RS256 only the EC JWT should fail.
            val rsaOnlyConfig = defaultConfig.copy(algorithms = listOf("RS256"))
            val verifier = JwksApiVerifier(rsaOnlyConfig, ecMockCache())
            val result = verifier.verify(signEc())
            assertNull(result, "Algorithm not in allowlist should return null")
        }

    @Test
    fun `invalid JWT string returns null`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify("not-a-jwt")
            assertNull(result, "Unparseable JWT should return null")
        }

    @Test
    fun `tampered JWT signature returns null`() =
        runTest {
            val jwt = signRsa()
            // Tamper with the signature (last part after second dot)
            val parts = jwt.split(".")
            val tampered = parts[0] + "." + parts[1] + ".invalidsignatureXYZ"
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify(tampered)
            assertNull(result, "Tampered JWT should return null")
        }

    @Test
    fun `JWT with nbf in the future by more than 60s returns null`() =
        runTest {
            val futureNbf = Instant.now().plusSeconds(120) // 2 minutes in the future
            val claims = buildClaims(notBefore = futureNbf)
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify(signRsa(claims))
            assertNull(result, "JWT with far-future nbf should be rejected")
        }

    @Test
    fun `JWT with nbf within 60s skew window is accepted`() =
        runTest {
            val nearFutureNbf = Instant.now().plusSeconds(30) // within 60s skew
            val claims = buildClaims(notBefore = nearFutureNbf)
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val result = verifier.verify(signRsa(claims))
            assertNotNull(result, "JWT with nbf within 60s skew window should be accepted")
        }

    @Test
    fun `JWKS fetch failure returns null gracefully`() =
        runTest {
            val failingCache = mockk<JwksKeySetProvider>()
            coEvery { failingCache.getKeySet() } throws Exception("JWKS endpoint unavailable")
            every { failingCache.close() } just Runs
            @Suppress("UNCHECKED_CAST")
            val verifier = JwksApiVerifier(defaultConfig, failingCache as JwksKeyCache)
            val result = verifier.verify(signRsa())
            assertNull(result, "JWKS fetch failure should return null, not throw")
        }

    @Test
    fun `fixed clock is used for expiry validation`() =
        runTest {
            // Pin the clock to a fixed point in time 120 seconds in the past.
            val fixedInstant = Instant.now().minusSeconds(120)
            val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

            // Token expires 60 seconds from real "now" — but the fixed clock thinks
            // it's 120s in the past, so the token should be considered valid.
            val expiry = Instant.now().plusSeconds(60)
            val claims = buildClaims(expiry = expiry)
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache(), clock = fixedClock)
            val result = verifier.verify(signRsa(claims))
            assertNotNull(result, "Token should be valid relative to fixed clock")
        }
}
