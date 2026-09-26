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
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * Independent test-author coverage for item 3dcfcbab, Part 2: opt-in (iss, jti) replay
 * protection at the [JwksActorVerifier] level (single-verify-call granularity - the per-call
 * memo that lets the SAME proof be verified more than once inside one MCP call is a separate
 * concern, covered in application/tools/ActorVerificationScopeTest.kt).
 *
 * Oracles (task-scope Part 2 / RFC 7519 s4.1.7, frozen at queue phase):
 *  - `jtiReplayProtection = false` (default): jti is never read; the same proof may be presented
 *    to `verify()` any number of times, each producing an independent VERIFIED result.
 *  - `jtiReplayProtection = true`: jti is REQUIRED - absent/blank -> REJECTED claims "missing jti
 *    claim". After all other checks pass, `(iss ?: "", jti)` is checked-and-recorded in the
 *    verifier's [JtiReplayCache]; a second presentation of the same pair -> REJECTED,
 *    failureKind=policy, reason "jti replay detected". The cache key is the (issuer, jti) PAIR,
 *    so the same jti under a different issuer is a distinct entry.
 *
 * Deliberately independent fixtures from every other JwksActorVerifier test file.
 */
class JwksActorVerifierJtiReplayTest {
    companion object {
        private val rsaKey = RSAKeyGenerator(2048).keyID("jti-replay-test-key").generate()
        private val NOW: Instant = Instant.parse("2024-01-01T00:00:00Z")
        private val fixedClock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private fun claims(
        subject: String = "agent-1",
        issuer: String? = "https://test-issuer.example",
        audience: String = "task-orchestrator",
        expiry: Instant = NOW.plusSeconds(300),
        jti: String? = null
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .audience(audience)
                .expirationTime(Date.from(expiry))
        issuer?.let { builder.issuer(it) }
        jti?.let { builder.jwtID(it) }
        return builder.build()
    }

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("jti-replay-test-key").build(), claimsSet)
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

    private fun verifier(
        jtiReplayProtection: Boolean,
        issuer: String? = "https://test-issuer.example"
    ): JwksActorVerifier =
        JwksActorVerifier(
            config =
                VerifierConfig.Jwks(
                    jwksPath = "/unused-in-unit-tests",
                    issuer = issuer,
                    audience = "task-orchestrator",
                    requireSubMatch = true,
                    jtiReplayProtection = jtiReplayProtection
                ),
            keySetProvider = mockProvider(),
            clock = fixedClock
        )

    private fun actor(proof: String): ActorClaim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = proof)

    // -------------------------------------------------------------------------
    // S4 - protection off (default): the same proof twice is VERIFIED both times
    // -------------------------------------------------------------------------

    @Test
    fun `S4 with jtiReplayProtection false the same proof verifies twice`() =
        runTest {
            val proof = sign(claims(jti = "tok-shared"))
            val v = verifier(jtiReplayProtection = false)

            assertEquals(VerificationStatus.VERIFIED, v.verify(actor(proof)).status)
            assertEquals(VerificationStatus.VERIFIED, v.verify(actor(proof)).status)
        }

    // -------------------------------------------------------------------------
    // S5 - protection on: two DISTINCT jti values both verify
    // -------------------------------------------------------------------------

    @Test
    fun `S5 with jtiReplayProtection true two proofs with distinct jti both verify`() =
        runTest {
            val v = verifier(jtiReplayProtection = true)
            val proofA = sign(claims(jti = "tok-a"))
            val proofB = sign(claims(jti = "tok-b"))

            assertEquals(VerificationStatus.VERIFIED, v.verify(actor(proofA)).status)
            assertEquals(VerificationStatus.VERIFIED, v.verify(actor(proofB)).status)
        }

    // -------------------------------------------------------------------------
    // S9 - protection on: the SAME proof presented twice (no per-call memo in play here - two
    // direct verify() calls) -> second is REJECTED for replay.
    // -------------------------------------------------------------------------

    @Test
    fun `S9 with jtiReplayProtection true the same proof presented twice is rejected on the second call`() =
        runTest {
            val v = verifier(jtiReplayProtection = true)
            val proof = sign(claims(jti = "tok-replayed"))

            val first = v.verify(actor(proof))
            assertEquals(VerificationStatus.VERIFIED, first.status)

            val second = v.verify(actor(proof))
            assertEquals(VerificationStatus.REJECTED, second.status)
            assertEquals("policy", second.metadata["failureKind"])
            assertEquals("jti replay detected", second.reason)
        }

    // -------------------------------------------------------------------------
    // S10 - protection on, jti absent -> REJECTED claims "missing jti claim"
    // -------------------------------------------------------------------------

    @Test
    fun `S10 with jtiReplayProtection true a proof with no jti is REJECTED for missing jti claim`() =
        runTest {
            val v = verifier(jtiReplayProtection = true)
            val proof = sign(claims(jti = null))

            val result = v.verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertEquals("missing jti claim", result.reason)
        }

    @Test
    fun `S10b with jtiReplayProtection true a proof with a blank jti is REJECTED for missing jti claim`() =
        runTest {
            val v = verifier(jtiReplayProtection = true)
            val proof = sign(claims(jti = "   "))

            val result = v.verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertEquals("missing jti claim", result.reason)
        }

    // -------------------------------------------------------------------------
    // S17 - protection on: same jti, different issuer -> distinct cache keys, both VERIFIED
    // -------------------------------------------------------------------------

    @Test
    fun `S17 with jtiReplayProtection true the same jti under different issuers both verify`() =
        runTest {
            // issuer left unconstrained in config so both issuer values are accepted.
            val v = verifier(jtiReplayProtection = true, issuer = null)
            val proofX = sign(claims(issuer = "https://issuer-x.example", jti = "shared-jti"))
            val proofY = sign(claims(issuer = "https://issuer-y.example", jti = "shared-jti"))

            assertEquals(VerificationStatus.VERIFIED, v.verify(actor(proofX)).status)
            assertEquals(VerificationStatus.VERIFIED, v.verify(actor(proofY)).status)
        }

    // -------------------------------------------------------------------------
    // Probe - a REJECTED (invalid) presentation of a jti must not poison the cache; a later
    // valid presentation of the same jti must still verify.
    // -------------------------------------------------------------------------

    @Test
    fun `Probe a rejected presentation does not poison the jti cache for a later valid one`() =
        runTest {
            val v = verifier(jtiReplayProtection = true)
            // First: an otherwise-invalid proof (wrong audience) sharing the jti we care about.
            val badProof = sign(claims(audience = "wrong-audience", jti = "tok-poison-check"))
            val badResult = v.verify(actor(badProof))
            assertEquals(VerificationStatus.REJECTED, badResult.status)
            assertEquals("claims", badResult.metadata["failureKind"])

            // Second: a valid proof with the SAME jti must still verify - the rejected attempt
            // must not have recorded the jti as seen.
            val goodProof = sign(claims(jti = "tok-poison-check"))
            val goodResult = v.verify(actor(goodProof))
            assertEquals(VerificationStatus.VERIFIED, goodResult.status)
        }
}
