package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.security.Security
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * Independent test-author coverage for item 1ec67c7f (AR-32 rec 5): every API JWT that
 * `JwksApiVerifier.verifyWithExpiry` rejects emits exactly one INFO line on the `JwksApiVerifier`
 * logger, flood-limited per reason code.
 *
 * Oracle: the frozen `task-scope` clauses ("The line", "Flood limit", "Not logged") plus the
 * `FailureKind` KDoc (CRYPTO = parse/key/signature, CLAIMS = missing/malformed/out-of-time/mismatched,
 * POLICY = algorithm/issuer/replay). Nothing here is derived from the implementation.
 *
 * Line shape (task-scope): `API JWT rejected: reason=<code> verifier=api keySource=jwks_uri suppressed=<n>`.
 * Limit (task-scope): per reason code at most 10 lines per 60s window, the window opening at the first
 * rejection and being open while now < start+60s; the first line of the next window reports the
 * count suppressed since the previous emitted line.
 */
class JwksApiVerifierRejectionLogTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private const val KID = "rejlog-rsa-key"
        private const val SECRET_KID = "kid-SECRET-2b8"
        private const val LINE_PREFIX = "API JWT rejected:"

        private val rsaKey = RSAKeyGenerator(2048).keyID(KID).generate()

        /** A second RSA key that is NOT in the verifier's key set, but carries the same kid as [rsaKey]. */
        private val impostorKey = RSAKeyGenerator(2048).keyID(KID).generate()

        /** RSA key whose kid is a secret-looking value, present in the key set so the kid resolves. */
        private val secretKidKey = RSAKeyGenerator(2048).keyID(SECRET_KID).generate()

        private val ecKey = ECKeyGenerator(Curve.P_256).keyID("rejlog-ec-key").generate()
    }

    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"
    private val freshCacheState = CacheState(fromStaleCache = false, ageSeconds = null)

    /** Whole-second base instant; the mutable verifier clock starts here and tokens are minted relative to it. */
    private val base: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private class MutableClock(
        @Volatile var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MutableClock(base)

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    private var savedLevel: Level? = null

    @BeforeEach
    fun attachAppender() {
        logger = LoggerFactory.getLogger(JwksApiVerifier::class.java) as Logger
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        savedLevel = logger.level
        logger.level = Level.TRACE
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = savedLevel
    }

    // ---------------------------------------------------------------------
    // Harness (private per-class, mirrors JwksApiVerifierExpiryTest)
    // ---------------------------------------------------------------------

    private fun jwksAuthConfig(algorithms: List<String> = listOf("RS256")): ApiAuthConfig.Jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = testIssuer,
            audience = testAudience,
            algorithms = algorithms,
            cacheTtlSeconds = 300,
        )

    private fun keySetOf(vararg keys: JWK): JWKSet = JWKSet(keys.map { it.toPublicJWK() })

    private fun mockProvider(keySet: JWKSet): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JwksResult(keySet, freshCacheState)
        coEvery { provider.refreshForUnknownKid(any()) } returns null
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun unavailableProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } throws KeySetUnavailableException("jwks endpoint down")
        coEvery { provider.refreshForUnknownKid(any()) } throws KeySetUnavailableException("jwks endpoint down")
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun verifier(
        algorithms: List<String> = listOf("RS256"),
        keySet: JWKSet = keySetOf(rsaKey, ecKey),
    ): JwksApiVerifier = JwksApiVerifier(jwksAuthConfig(algorithms), mockProvider(keySet), clock)

    private fun claims(
        subject: String = "api-caller-1",
        audience: String = testAudience,
        expiry: Instant = base.plusSeconds(300),
        extra: Map<String, Any> = emptyMap(),
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(testIssuer)
                .audience(audience)
                .expirationTime(Date.from(expiry))
        extra.forEach { (name, value) -> builder.claim(name, value) }
        return builder.build()
    }

    private fun signRsa(
        claims: JWTClaimsSet = claims(),
        key: RSAKey = rsaKey,
    ): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    private fun signEc(claims: JWTClaimsSet = claims()): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ecKey.keyID).build(), claims)
        jwt.sign(ECDSASigner(ecKey))
        return jwt.serialize()
    }

    private fun expected(
        reason: String,
        suppressed: Int,
    ): String = "$LINE_PREFIX reason=$reason verifier=api keySource=jwks_uri suppressed=$suppressed"

    /** Every captured event whose message starts with the rejection-line prefix, at any level. */
    private fun rejectionEvents(): List<ILoggingEvent> = appender.list.filter { it.formattedMessage.startsWith(LINE_PREFIX) }

    private fun rejectionMessages(): List<String> = rejectionEvents().map { it.formattedMessage }

    private fun assertRejectionLines(
        vararg lines: String,
        message: String? = null,
    ) {
        assertEquals(lines.toList(), rejectionMessages(), message)
        assertTrue(rejectionEvents().all { it.level == Level.INFO }, "every rejection line must be logged at INFO")
    }

    // ---------------------------------------------------------------------
    // S1-S7 - one INFO line per rejection kind with the mapped reason code
    // ---------------------------------------------------------------------

    @Test
    fun `S1 unparseable token is rejected with one crypto line`() =
        runTest {
            val verdict = verifier().verifyWithExpiry("not-a-jwt")
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertRejectionLines(expected("crypto", 0))
        }

    @Test
    fun `S2 tampered signature is rejected with one crypto line`() =
        runTest {
            // Signed by a different key under the same kid: the signature cannot verify against the published key.
            val token = signRsa(key = impostorKey)
            val verdict = verifier().verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertRejectionLines(expected("crypto", 0))
        }

    @Test
    fun `S3 expired token is rejected with one claims line`() =
        runTest {
            val token = signRsa(claims(expiry = base.minusSeconds(600)))
            val verdict = verifier().verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertRejectionLines(expected("claims", 0))
        }

    @Test
    fun `S4 algorithm outside the allowlist is rejected with one policy line`() =
        runTest {
            // The EC key IS in the key set, so only the RS256-only allowlist can refuse this ES256 token.
            val verdict = verifier(algorithms = listOf("RS256")).verifyWithExpiry(signEc())
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertRejectionLines(expected("policy", 0))
        }

    @Test
    fun `S5 validly signed token with a string to_scope is rejected with one claims line`() =
        runTest {
            val token = signRsa(claims(extra = mapOf("to_scope" to "x")))
            val verdict = verifier().verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertRejectionLines(expected("claims", 0))
        }

    @Test
    fun `S6 validly signed token with a non-string to_kind is rejected with one claims line`() =
        runTest {
            val token = signRsa(claims(extra = mapOf("to_kind" to 42)))
            val verdict = verifier().verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertRejectionLines(expected("claims", 0))
        }

    @Test
    fun `S7 verify wrapper logs exactly one line and returns null`() =
        runTest {
            val principal = verifier().verify("not-a-jwt")
            assertNull(principal)
            assertRejectionLines(expected("crypto", 0), message = "verify() delegates to verifyWithExpiry and must not log twice")
        }

    // ---------------------------------------------------------------------
    // S8-S10 - absence and secrecy
    // ---------------------------------------------------------------------

    @Test
    fun `S8 verified token produces no rejection line at any level`() =
        runTest {
            val v = verifier()
            val verdict = v.verifyWithExpiry(signRsa())
            assertTrue(verdict is ApiTokenVerdict.Verified, "fixture must be a valid token, got $verdict")
            assertEquals(emptyList<String>(), rejectionMessages())
            // Paired guard: the same verifier DOES log for a rejection, so the zero above is not a dead appender.
            v.verifyWithExpiry("not-a-jwt")
            assertRejectionLines(expected("crypto", 0))
        }

    @Test
    fun `S9 unavailable key set produces no rejection line`() =
        runTest {
            val unavailable = JwksApiVerifier(jwksAuthConfig(), unavailableProvider(), clock)
            val verdict = unavailable.verifyWithExpiry(signRsa())
            assertTrue(verdict is ApiTokenVerdict.Unavailable, "fixture must produce Unavailable, got $verdict")
            assertEquals(
                emptyList<String>(),
                rejectionMessages(),
                "an Unavailable verdict is not a rejection and must not log the INFO line"
            )
        }

    @Test
    fun `S10 rejection line never carries the token or any claim value`() =
        runTest {
            val token =
                signRsa(
                    claims(subject = "sub-SECRET-7f3", audience = "aud-SECRET-9c1"),
                    key = secretKidKey,
                )
            val verdict = verifier(keySet = keySetOf(rsaKey, secretKidKey)).verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertRejectionLines(expected("claims", 0))

            val forbidden = listOf(token, "SECRET", "sub-SECRET-7f3", "aud-SECRET-9c1", "kid-SECRET-2b8") + token.split('.')
            appender.list
                .filter { it.level.isGreaterOrEqual(Level.INFO) }
                .forEach { event ->
                    val rendered =
                        listOf(
                            event.formattedMessage,
                            event.message,
                            event.argumentArray?.joinToString("|") { it.toString() }.orEmpty(),
                            event.throwableProxy?.message.orEmpty(),
                        )
                    forbidden.forEach { secret ->
                        rendered.forEach { text ->
                            assertFalse(text.contains(secret), "INFO+ event leaked '$secret': $text")
                        }
                    }
                }
        }

    // ---------------------------------------------------------------------
    // S11-S15 - flood limit (10 lines per reason code per 60s window)
    // ---------------------------------------------------------------------

    private suspend fun rejectCrypto(
        verifier: JwksApiVerifier,
        times: Int,
    ) {
        repeat(times) { verifier.verifyWithExpiry("not-a-jwt") }
    }

    @Test
    fun `S11 eleventh rejection in a window is not logged`() =
        runTest {
            rejectCrypto(verifier(), 11)
            assertEquals(List(10) { expected("crypto", 0) }, rejectionMessages())
            assertTrue(rejectionEvents().all { it.level == Level.INFO })
        }

    @Test
    fun `S12 next window first line reports the suppressed count then resets`() =
        runTest {
            val v = verifier()
            rejectCrypto(v, 11)
            clock.now = base.plus(Duration.ofSeconds(60))
            v.verifyWithExpiry("not-a-jwt")
            v.verifyWithExpiry("not-a-jwt")
            assertEquals(List(10) { expected("crypto", 0) } + expected("crypto", 1) + expected("crypto", 0), rejectionMessages())
        }

    @Test
    fun `S13 window is still open one millisecond before the 60 second mark`() =
        runTest {
            val v = verifier()
            rejectCrypto(v, 11)
            clock.now = base.plus(Duration.ofMillis(59_999))
            v.verifyWithExpiry("not-a-jwt")
            assertEquals(List(10) { expected("crypto", 0) }, rejectionMessages())
        }

    @Test
    fun `S14 reason codes are limited independently`() =
        runTest {
            val v = verifier()
            rejectCrypto(v, 11)
            v.verifyWithExpiry(signRsa(claims(expiry = base.minusSeconds(600))))
            assertEquals(List(10) { expected("crypto", 0) } + expected("claims", 0), rejectionMessages())
        }

    @Test
    fun `S15 suppressed count accumulates across the whole window`() =
        runTest {
            val v = verifier()
            rejectCrypto(v, 15)
            clock.now = base.plusSeconds(60)
            v.verifyWithExpiry("not-a-jwt")
            assertEquals(List(10) { expected("crypto", 0) } + expected("crypto", 5), rejectionMessages())
        }

    // ---------------------------------------------------------------------
    // Probes
    // ---------------------------------------------------------------------

    @Test
    fun `probe concurrent rejections never exceed ten lines and lose no counts`() =
        runTest {
            val v = verifier()
            withContext(Dispatchers.Default) {
                (1..50).map { async { v.verifyWithExpiry("not-a-jwt") } }.awaitAll()
            }
            assertEquals(List(10) { expected("crypto", 0) }, rejectionMessages(), "exactly 10 lines for 50 concurrent rejections")
            clock.now = base.plusSeconds(60)
            v.verifyWithExpiry("not-a-jwt")
            assertEquals(expected("crypto", 40), rejectionMessages().last(), "the 40 suppressed rejections must all be counted")
        }

    @Test
    fun `probe empty and whitespace tokens are crypto rejections with the standard shape`() =
        runTest {
            val v = verifier()
            assertEquals(ApiTokenVerdict.Rejected, v.verifyWithExpiry(""))
            assertEquals(ApiTokenVerdict.Rejected, v.verifyWithExpiry("   "))
            assertRejectionLines(expected("crypto", 0), expected("crypto", 0))
        }

    @Test
    fun `probe token text with braces percent and newline cannot alter the line shape`() =
        runTest {
            val v = verifier()
            val hostile = "{}" + Char(10) + "%s {} reason=policy suppressed=99"
            assertEquals(ApiTokenVerdict.Rejected, v.verifyWithExpiry(hostile))
            assertRejectionLines(expected("crypto", 0))
        }

    @Test
    fun `probe the same token presented repeatedly is counted every time`() =
        runTest {
            val v = verifier()
            val token = "not-a-jwt"
            repeat(12) { v.verifyWithExpiry(token) }
            clock.now = base.plusSeconds(60)
            v.verifyWithExpiry(token)
            assertEquals(List(10) { expected("crypto", 0) } + expected("crypto", 2), rejectionMessages())
        }
}
