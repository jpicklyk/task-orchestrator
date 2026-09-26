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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * Independent test-author coverage for item 3dcfcbab, ADDENDUM 2 (fix cycle round 2, commit
 * 009c4867): a millisecond-truncation gap in JwksActorVerifier's expiry comparison that
 * cache-level (JtiReplayCache) tests cannot observe, since JtiReplayCache compares Instants
 * directly without going through the verifier's own exp/skew arithmetic.
 *
 * Oracles:
 *  - RFC 7519 s4.1.4 (exp): implementers MAY allow "a small leeway" for clock skew -- here
 *    AUTH_CLOCK_SKEW_SECONDS = 60L (declared constant).
 *  - Invariant: the verifier's last accepted instant must not exceed the replay cache's own
 *    inclusive retention deadline of exp+60s (task-scope Part 2's declared retention formula,
 *    expiresAt.plusSeconds(CLOCK_SKEW_SECONDS) = exp+60s) -- the verifier and the cache must agree
 *    on the same boundary to second AND sub-second precision, or a token could replay-protect
 *    against a window it is no longer even accepted within, or vice versa.
 *
 * Uses a fixed Clock built from Instant.ofEpochSecond(seconds, nanos) so "now" itself carries
 * sub-millisecond precision -- this is what actually exercises the comparison arithmetic; the
 * token's own exp claim is a whole second (JWT NumericDate has no sub-second component to lose).
 */
class JwksActorVerifierExpiryPrecisionTest {
    private val rsaKey = RSAKeyGenerator(2048).keyID("actor-expiry-precision-test-key").generate()
    private val issuer = "https://test-issuer.example"
    private val audience = "task-orchestrator"
    private val exp: Instant = Instant.ofEpochSecond(2_000_000_000L, 0)

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID("actor-expiry-precision-test-key").build(),
                claimsSet
            )
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun claimsWithExp(expiry: Instant): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject("agent-1")
            .issuer(issuer)
            .audience(audience)
            .expirationTime(Date.from(expiry))
            .build()

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
            issuer = issuer,
            audience = audience,
            requireSubMatch = true
        )

    private fun verifierAt(now: Instant): JwksActorVerifier =
        JwksActorVerifier(config = baseConfig(), keySetProvider = mockProvider(), clock = Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `token is rejected as expired one nanosecond past the exp plus 60s skew boundary`() =
        runBlocking {
            val proof = sign(claimsWithExp(exp))
            val now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).plusNanos(1)
            val result = verifierAt(now).verify(ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = proof))
            assertEquals(
                VerificationStatus.REJECTED,
                result.status,
                "one nanosecond past exp+60s the token must be rejected as expired -- a truncated " +
                    "comparison that only checks whole milliseconds or seconds would wrongly accept it"
            )
        }

    @Test
    fun `token is rejected as expired 500 microseconds past the exp plus 60s skew boundary`() =
        runBlocking {
            val proof = sign(claimsWithExp(exp))
            val now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).plusNanos(500_000)
            val result = verifierAt(now).verify(ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = proof))
            assertEquals(
                VerificationStatus.REJECTED,
                result.status,
                "500 microseconds past exp+60s the token must be rejected as expired -- still within " +
                    "the same millisecond as the boundary, so a millisecond-truncating comparison would " +
                    "wrongly accept it"
            )
        }

    @Test
    fun `token is accepted one millisecond before the exp plus 60s skew boundary (positive control)`() =
        runBlocking {
            val proof = sign(claimsWithExp(exp))
            val now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).minusMillis(1)
            val result = verifierAt(now).verify(ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = proof))
            assertEquals(
                VerificationStatus.VERIFIED,
                result.status,
                "one millisecond before the exp+60s boundary the token must still be accepted -- proves " +
                    "the fixture and harness are capable of a VERIFIED result, so the two rejection " +
                    "assertions above are not vacuously true"
            )
            assertEquals("jwks", result.verifier, "a VERIFIED result from this verifier reports verifier=jwks")
        }
}
