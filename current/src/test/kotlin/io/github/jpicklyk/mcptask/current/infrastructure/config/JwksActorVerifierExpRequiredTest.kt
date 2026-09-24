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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.Date

/**
 * Regression tests for item d426fbfa / decision D4: [JwksActorVerifier] must REJECT an actor
 * proof that carries no `exp` claim rather than treating it as never-expiring (previously the
 * `if (expiry != null)` guard skipped the check entirely when `exp` was absent).
 *
 * Deliberately independent of [JwksActorVerifierTest] — its own RSA key pair, its own claims
 * and signing helpers — so this file makes no assumption about that file's fixtures or their
 * lifetime.
 */
class JwksActorVerifierExpRequiredTest {
    companion object {
        private val rsaKey = RSAKeyGenerator(2048).keyID("exp-required-test-key").generate()
    }

    /** Claims with no `exp` unless [expiry] is supplied — the shape needed for a missing-exp proof. */
    private fun claims(
        subject: String = "agent-1",
        issuer: String = "https://test-issuer.example",
        audience: String = "task-orchestrator",
        expiry: Instant? = null,
        notBefore: Instant? = null
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
        expiry?.let { builder.expirationTime(Date.from(it)) }
        notBefore?.let { builder.notBeforeTime(Date.from(it)) }
        return builder.build()
    }

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("exp-required-test-key").build(), claimsSet)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    /**
     * Flips the first character of the JWS signature segment so the token no longer verifies,
     * while keeping the header (and its `kid`) and payload byte-for-byte identical. Used for S13,
     * which needs a proof that is simultaneously exp-less AND cryptographically invalid.
     */
    private fun corruptSignature(jwt: String): String {
        val parts = jwt.split(".")
        require(parts.size == 3) { "expected a compact JWS with 3 segments, got ${parts.size}" }
        val sig = parts[2]
        val corrupted =
            if (sig.isNotEmpty()) {
                val flipped = if (sig[0] == 'A') 'B' else 'A'
                flipped + sig.substring(1)
            } else {
                sig
            }
        return "${parts[0]}.${parts[1]}.$corrupted"
    }

    private fun mockProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns
            JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun baseConfig(): VerifierConfig.Jwks =
        VerifierConfig.Jwks(
            jwksPath = "/unused-in-unit-tests",
            issuer = "https://test-issuer.example",
            audience = "task-orchestrator",
            requireSubMatch = true
        )

    private fun verifier(clock: Clock = Clock.systemUTC()): JwksActorVerifier =
        JwksActorVerifier(config = baseConfig(), keySetProvider = mockProvider(), clock = clock)

    private fun actor(proof: String?): ActorClaim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = proof)

    // -------------------------------------------------------------------------
    // S8 — a validly-signed, otherwise-valid proof with no exp claim is REJECTED
    // -------------------------------------------------------------------------

    @Test
    fun `S8 exp-less proof with valid signature and claims is REJECTED for missing exp`() =
        runTest {
            val proof = sign(claims(expiry = null))

            val result = verifier().verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("jwks", result.verifier)
            assertEquals("missing exp claim", result.reason)
            assertEquals("claims", result.metadata["failureKind"])
        }

    // -------------------------------------------------------------------------
    // S9 — same as S8 even when nbf is present and satisfied (exp is checked regardless of nbf)
    // -------------------------------------------------------------------------

    @Test
    fun `S9 exp-less proof with a satisfied past nbf is still REJECTED for missing exp`() =
        runTest {
            val proof = sign(claims(expiry = null, notBefore = Instant.now().minusSeconds(60)))

            val result = verifier().verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("jwks", result.verifier)
            assertEquals("missing exp claim", result.reason)
            assertEquals("claims", result.metadata["failureKind"])
        }

    // -------------------------------------------------------------------------
    // S12 — exp within the documented 60s clock-skew window on either side is VERIFIED
    // -------------------------------------------------------------------------

    @Test
    fun `S12a exp 30 seconds in the past is within clock skew and is VERIFIED`() =
        runTest {
            val proof = sign(claims(expiry = Instant.now().minusSeconds(30)))

            val result = verifier().verify(actor(proof))

            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    @Test
    fun `S12b exp 1 second in the future is VERIFIED`() =
        runTest {
            val proof = sign(claims(expiry = Instant.now().plusSeconds(1)))

            val result = verifier().verify(actor(proof))

            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // -------------------------------------------------------------------------
    // S13 — exp-less AND a bad signature: signature (crypto) is checked before exp (claims)
    // -------------------------------------------------------------------------

    @Test
    fun `S13 exp-less proof with a bad signature is REJECTED for crypto, not for missing exp`() =
        runTest {
            val exploitProof = corruptSignature(sign(claims(expiry = null)))

            val result = verifier().verify(actor(exploitProof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("crypto", result.metadata["failureKind"])
            assertNotNull(result.reason)
        }

    // -------------------------------------------------------------------------
    // S14b — a blank proof is ABSENT, not REJECTED (missing exp only applies once a proof exists)
    // -------------------------------------------------------------------------

    @Test
    fun `S14b blank proof is ABSENT not REJECTED`() =
        runTest {
            val result = verifier().verify(actor(""))

            assertEquals(VerificationStatus.ABSENT, result.status)
            assertEquals("jwks", result.verifier)
        }

    // -------------------------------------------------------------------------
    // Probe — a non-numeric exp claim must not be treated as VERIFIED
    // -------------------------------------------------------------------------

    @Test
    fun `Probe non-numeric exp claim is not VERIFIED`() =
        runTest {
            val malformedClaims =
                JWTClaimsSet
                    .Builder()
                    .subject("agent-1")
                    .issuer("https://test-issuer.example")
                    .audience("task-orchestrator")
                    .claim("exp", "not-a-number")
                    .build()
            val proof = sign(malformedClaims)

            val result = verifier().verify(actor(proof))

            assertNotEquals(VerificationStatus.VERIFIED, result.status)
        }

    // -------------------------------------------------------------------------
    // Probe — exp=0 (the epoch) is a *present* claim, so it must be rejected as expired, not as
    // missing.
    // -------------------------------------------------------------------------

    @Test
    fun `Probe exp equal to the epoch is REJECTED for a reason other than missing exp claim`() =
        runTest {
            val proof = sign(claims(expiry = Instant.EPOCH))

            val result = verifier().verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertNotNull(result.reason)
            assertNotEquals("missing exp claim", result.reason)
        }
}
