package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.infrastructure.config.AUTH_CLOCK_SKEW_SECONDS
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * Independent test-author coverage for item 3dcfcbab, ADDENDUM 2 (fix cycle round 2, commit
 * 009c4867): the same millisecond-truncation gap probed against [JwksActorVerifier] in
 * `JwksActorVerifierExpiryPrecisionTest`, exercised here against the REST/API-side verifier,
 * [JwksApiVerifier]. Cache-level (JtiReplayCache) tests cannot observe this gap since that cache
 * compares Instants directly, never going through a verifier's own exp/skew arithmetic.
 *
 * Oracles:
 *  - RFC 7519 s4.1.4 (exp): implementers MAY allow "a small leeway" for clock skew -- here
 *    AUTH_CLOCK_SKEW_SECONDS = 60L (declared constant).
 *  - Invariant: the verifier's last accepted instant must not exceed the replay cache's own
 *    inclusive retention deadline of exp+60s (task-scope Part 2).
 */
class JwksApiVerifierExpiryPrecisionTest {
    private val rsaKey = RSAKeyGenerator(2048).keyID("api-expiry-precision-test-key").generate()
    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"
    private val exp: Instant = Instant.ofEpochSecond(2_000_000_000L, 0)

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID("api-expiry-precision-test-key").build(),
                claimsSet
            )
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun claimsWithExp(expiry: Instant): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject("agent-1")
            .issuer(testIssuer)
            .audience(testAudience)
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

    private fun jwksAuthConfig(): ApiAuthConfig.Jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = testIssuer,
            audience = testAudience,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300
        )

    private fun verifierAt(now: Instant): JwksApiVerifier =
        JwksApiVerifier(config = jwksAuthConfig(), keyProvider = mockProvider(), clock = Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `token is rejected as expired one nanosecond past the exp plus 60s skew boundary`() =
        runBlocking {
            val jwt = sign(claimsWithExp(exp))
            val now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).plusNanos(1)
            val result = verifierAt(now).verifyWithExpiry(jwt)
            assertNull(
                result,
                "one nanosecond past exp+60s the token must be rejected as expired -- a truncated " +
                    "comparison that only checks whole milliseconds or seconds would wrongly accept it"
            )
        }

    @Test
    fun `token is rejected as expired 500 microseconds past the exp plus 60s skew boundary`() =
        runBlocking {
            val jwt = sign(claimsWithExp(exp))
            val now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).plusNanos(500_000)
            val result = verifierAt(now).verifyWithExpiry(jwt)
            assertNull(
                result,
                "500 microseconds past exp+60s the token must be rejected as expired -- still within " +
                    "the same millisecond as the boundary, so a millisecond-truncating comparison would " +
                    "wrongly accept it"
            )
        }

    @Test
    fun `token is accepted one millisecond before the exp plus 60s skew boundary (positive control)`() =
        runBlocking {
            val jwt = sign(claimsWithExp(exp))
            val now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).minusMillis(1)
            val result = verifierAt(now).verifyWithExpiry(jwt)
            assertNotNull(
                result,
                "one millisecond before the exp+60s boundary the token must still be accepted -- proves " +
                    "the fixture and harness are capable of a non-null result, so the two rejection " +
                    "assertions above are not vacuously true"
            )
            assertEquals(exp, result!!.expiresAt, "the accepted token's reported expiresAt must be the raw exp claim")
        }
}
