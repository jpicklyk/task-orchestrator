package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * Independent test-author coverage for item 3dcfcbab, Part 1 (REST/API side): [JwksApiVerifier]
 * must cap accepted token lifetime at `ApiAuthConfig.Jwks.maxTokenLifetimeSeconds` (default
 * 86400s), mirroring the actor-side cap in [io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier]
 * with the same 60s clock-skew boundary semantics (task-scope Part 1: exactly `max+60` passes,
 * `max+61` rejects; the exp-iat span check applies whenever `iat` is present).
 *
 * `verify()` returns null on rejection (no failureKind surfaced on this path per the declared
 * [JwksApiVerifier] signature) - unlike the actor verifier, there is no VerificationResult to
 * inspect, so these tests assert on null vs non-null and, where useful, on
 * [JwksApiVerifier.verifyWithExpiry]'s `expiresAt`.
 *
 * Deliberately independent fixtures from the sibling `JwksApiVerifierExpiryTest`/`JwksApiVerifierTest`.
 */
class JwksApiVerifierLifetimeCapTest {
    companion object {
        private val rsaKey = RSAKeyGenerator(2048).keyID("api-lifetime-cap-test-key").generate()
        private val NOW: Instant = Instant.parse("2024-01-01T00:00:00Z")
        private val fixedClock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"

    private fun claims(
        subject: String = "api-caller-1",
        expiry: Instant?,
        issuedAt: Instant? = null
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(testIssuer)
                .audience(testAudience)
        expiry?.let { builder.expirationTime(Date.from(it)) }
        issuedAt?.let { builder.issueTime(Date.from(it)) }
        return builder.build()
    }

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("api-lifetime-cap-test-key").build(), claimsSet)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun mockProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns
            JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        coEvery { provider.getKeySetForIssuer(any()) } returns
            JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        return provider
    }

    private fun verifier(maxTokenLifetimeSeconds: Long): JwksApiVerifier =
        JwksApiVerifier(
            config =
                ApiAuthConfig.Jwks(
                    url = "https://idp.test.example/.well-known/jwks.json",
                    issuer = testIssuer,
                    audience = testAudience,
                    algorithms = listOf("RS256"),
                    cacheTtlSeconds = 300,
                    maxTokenLifetimeSeconds = maxTokenLifetimeSeconds
                ),
            keyProvider = mockProvider(),
            clock = fixedClock
        )

    // -------------------------------------------------------------------------
    // S1 / S2 - within cap, including the inclusive skew boundary
    // -------------------------------------------------------------------------

    @Test
    fun `S1 exp at exactly maxTokenLifetimeSeconds from now with a fresh iat verifies`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(m), issuedAt = NOW))

            val result = verifier(m).verify(proof)

            assertNotNull(result, "a token within the lifetime cap must verify")
        }

    @Test
    fun `S2 exp at maxTokenLifetimeSeconds plus 60s skew still verifies (inclusive boundary)`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(m + 60), issuedAt = NOW))

            val result = verifier(m).verify(proof)

            assertNotNull(result, "exactly max+60s must still be inside the inclusive boundary")
        }

    // -------------------------------------------------------------------------
    // S6 - exp one second past the skew boundary is rejected
    // -------------------------------------------------------------------------

    @Test
    fun `S6 exp one second past the skew boundary is rejected`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(m + 61), issuedAt = NOW))

            val result = verifier(m).verify(proof)

            assertNull(result, "max+61s must exceed the cap and be rejected")
        }

    // -------------------------------------------------------------------------
    // S7 - exp-iat span exceeding the cap is rejected even with a small remaining window
    // -------------------------------------------------------------------------

    @Test
    fun `S7 exp-minus-iat span exceeding the cap is rejected even when the remaining window is small`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(120), issuedAt = NOW.minusSeconds(m)))

            val result = verifier(m).verify(proof)

            assertNull(result, "exp-iat = m+120 must be rejected on the lifetime-span check")
        }

    // -------------------------------------------------------------------------
    // S8 - iat in the future
    // -------------------------------------------------------------------------

    @Test
    fun `S8a iat 61 seconds in the future is rejected`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(300), issuedAt = NOW.plusSeconds(61)))

            val result = verifier(m).verify(proof)

            assertNull(result, "iat 61s in the future must be rejected")
        }

    @Test
    fun `S8b iat exactly 60 seconds in the future (skew boundary) verifies`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(300), issuedAt = NOW.plusSeconds(60)))

            val result = verifier(m).verify(proof)

            assertNotNull(result, "iat exactly 60s in the future is within the inclusive skew boundary")
        }

    // -------------------------------------------------------------------------
    // verifyWithExpiry mirrors verify()'s lifetime-cap decision
    // -------------------------------------------------------------------------

    @Test
    fun `verifyWithExpiry rejects a token exceeding the lifetime cap the same way verify does`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(m + 61), issuedAt = NOW))

            val result = verifier(m).verifyWithExpiry(proof)

            assertNull(result, "verifyWithExpiry must reject a token exceeding the lifetime cap")
        }

    @Test
    fun `Probe a year-9999 exp is rejected even under the default 86400s cap`() =
        runTest {
            val defaultVerifier =
                JwksApiVerifier(
                    config =
                        ApiAuthConfig.Jwks(
                            url = "https://idp.test.example/.well-known/jwks.json",
                            issuer = testIssuer,
                            audience = testAudience,
                            algorithms = listOf("RS256"),
                            cacheTtlSeconds = 300
                        ),
                    keyProvider = mockProvider(),
                    clock = fixedClock
                )
            assertEquals(
                86400L,
                ApiAuthConfig
                    .Jwks(
                        url = "u",
                        issuer = "i",
                        audience = "a",
                        algorithms = emptyList(),
                        cacheTtlSeconds = 300
                    ).maxTokenLifetimeSeconds,
                "sanity: default cap is 86400s"
            )

            val proof = sign(claims(expiry = Instant.ofEpochSecond(253402300799L), issuedAt = NOW))
            val result = defaultVerifier.verify(proof)

            assertNull(result, "a year-9999 exp must never verify, even under the default cap")
        }
}
