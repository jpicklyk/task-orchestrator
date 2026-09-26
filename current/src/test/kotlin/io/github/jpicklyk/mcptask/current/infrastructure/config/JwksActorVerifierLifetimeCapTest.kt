package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * Independent test-author coverage for item 3dcfcbab, Part 1 (actor-side): [JwksActorVerifier]
 * must cap accepted token lifetime at `maxTokenLifetimeSeconds` (config field, default 86400s),
 * checked with the same 60s clock skew already used for exp/nbf.
 *
 * Oracles (task-scope Part 1, frozen at queue phase):
 *  - (a) remaining window: reject if `exp - now > maxTokenLifetimeSeconds + 60`. Always applies.
 *  - (b) if `iat` present: reject if `iat > now + 60` ("iat in the future"); reject if
 *    `exp - iat > maxTokenLifetimeSeconds + 60`.
 *  - Boundaries inclusive: exactly `max + 60` passes, `max + 61` rejects.
 *  - Failure: REJECTED, failureKind=claims, reason "token lifetime exceeds maximum" (for (a)/(b)
 *    lifetime violations) or "iat in the future" (for the iat-in-future check).
 *
 * Deliberately independent fixtures (own RSA key, own claims/signing helpers, own fixed [Clock])
 * from every other JwksActorVerifier test file, per the sibling files' own stated convention.
 */
class JwksActorVerifierLifetimeCapTest {
    companion object {
        private val rsaKey = RSAKeyGenerator(2048).keyID("lifetime-cap-test-key").generate()
        private val NOW: Instant = Instant.parse("2024-01-01T00:00:00Z")
        private val fixedClock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private fun claims(
        subject: String = "agent-1",
        issuer: String = "https://test-issuer.example",
        audience: String = "task-orchestrator",
        expiry: Instant?,
        issuedAt: Instant? = null
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
        expiry?.let { builder.expirationTime(Date.from(it)) }
        issuedAt?.let { builder.issueTime(Date.from(it)) }
        return builder.build()
    }

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("lifetime-cap-test-key").build(), claimsSet)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun mockProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns
            JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun config(maxTokenLifetimeSeconds: Long): VerifierConfig.Jwks =
        VerifierConfig.Jwks(
            jwksPath = "/unused-in-unit-tests",
            issuer = "https://test-issuer.example",
            audience = "task-orchestrator",
            requireSubMatch = true,
            maxTokenLifetimeSeconds = maxTokenLifetimeSeconds
        )

    private fun verifier(maxTokenLifetimeSeconds: Long): JwksActorVerifier =
        JwksActorVerifier(
            config = config(maxTokenLifetimeSeconds),
            keySetProvider = mockProvider(),
            clock = fixedClock
        )

    private fun actor(proof: String): ActorClaim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = proof)

    // -------------------------------------------------------------------------
    // S1 - exp = NOW + M, iat = NOW -> VERIFIED
    // -------------------------------------------------------------------------

    @Test
    fun `S1 exp at exactly maxTokenLifetimeSeconds from now with a fresh iat is VERIFIED`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(m), issuedAt = NOW))

            val result = verifier(m).verify(actor(proof))

            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // -------------------------------------------------------------------------
    // S2 - exp = NOW + M + 60 (skew boundary, inclusive) -> VERIFIED
    // -------------------------------------------------------------------------

    @Test
    fun `S2 exp at maxTokenLifetimeSeconds plus 60s skew is still VERIFIED (inclusive boundary)`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(m + 60), issuedAt = NOW))

            val result = verifier(m).verify(actor(proof))

            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // -------------------------------------------------------------------------
    // S6 - exp = NOW + M + 61 -> REJECTED claims 'token lifetime exceeds maximum'
    // -------------------------------------------------------------------------

    @Test
    fun `S6 exp one second past the skew boundary is REJECTED for exceeding maximum lifetime`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(m + 61), issuedAt = NOW))

            val result = verifier(m).verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertEquals("token lifetime exceeds maximum", result.reason)
        }

    // -------------------------------------------------------------------------
    // S7 - iat = NOW - M, exp = NOW + 120 (exp - iat = M + 120) -> rejected on the (b) check
    // even though exp is still well within its own remaining-window bound.
    // -------------------------------------------------------------------------

    @Test
    fun `S7 exp-minus-iat span exceeding the cap is REJECTED even when the remaining window is small`() =
        runTest {
            val m = 600L
            // Remaining window (exp - now) is only 120s, far under m+60 - this must fail on the
            // exp-iat check specifically, proving check (b) is independent of check (a).
            val proof = sign(claims(expiry = NOW.plusSeconds(120), issuedAt = NOW.minusSeconds(m)))

            val result = verifier(m).verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertEquals("token lifetime exceeds maximum", result.reason)
        }

    // -------------------------------------------------------------------------
    // S8 - iat in the future: 61s rejected, 60s (skew boundary) accepted
    // -------------------------------------------------------------------------

    @Test
    fun `S8a iat 61 seconds in the future is REJECTED for iat in the future`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(300), issuedAt = NOW.plusSeconds(61)))

            val result = verifier(m).verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertEquals("iat in the future", result.reason)
        }

    @Test
    fun `S8b iat exactly 60 seconds in the future (skew boundary) is VERIFIED`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(300), issuedAt = NOW.plusSeconds(60)))

            val result = verifier(m).verify(actor(proof))

            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // -------------------------------------------------------------------------
    // Probes
    // -------------------------------------------------------------------------

    @Test
    fun `Probe a year-9999 exp is never VERIFIED even under the default 86400s cap`() =
        runTest {
            val farFutureExp = Instant.ofEpochSecond(253402300799L)
            val proof = sign(claims(expiry = farFutureExp, issuedAt = NOW))

            val defaultConfig =
                VerifierConfig.Jwks(
                    jwksPath = "/unused-in-unit-tests",
                    issuer = "https://test-issuer.example",
                    audience = "task-orchestrator",
                    requireSubMatch = true
                )
            assertEquals(86400L, defaultConfig.maxTokenLifetimeSeconds, "sanity: default cap is 86400s")

            val result =
                JwksActorVerifier(config = defaultConfig, keySetProvider = mockProvider(), clock = fixedClock)
                    .verify(actor(proof))

            assertNotEquals(VerificationStatus.VERIFIED, result.status)
        }

    @Test
    fun `Probe M=1 sharp boundary - exp at now+61 (skew-inclusive) passes, now+62 fails`() =
        runTest {
            val m = 1L
            val passingProof = sign(claims(expiry = NOW.plusSeconds(61), issuedAt = NOW))
            val failingProof = sign(claims(expiry = NOW.plusSeconds(62), issuedAt = NOW))

            val v = verifier(m)
            assertEquals(VerificationStatus.VERIFIED, v.verify(actor(passingProof)).status)
            assertEquals(VerificationStatus.REJECTED, v.verify(actor(failingProof)).status)
        }

    @Test
    fun `Probe iat absent - only the remaining-window check applies, no iat-in-future rejection possible`() =
        runTest {
            val m = 600L
            val proof = sign(claims(expiry = NOW.plusSeconds(m), issuedAt = null))

            val result = verifier(m).verify(actor(proof))

            assertEquals(VerificationStatus.VERIFIED, result.status)
        }
}
