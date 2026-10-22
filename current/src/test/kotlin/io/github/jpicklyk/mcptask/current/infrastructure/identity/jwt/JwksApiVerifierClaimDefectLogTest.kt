package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
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
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * Independent test-author coverage for item 803b4ca9: the per-token WARN lines of the API JWT path
 * carry a reason code only (never a claim value, the kid, or exception text) and are flood-limited
 * per `<claim>.<reason>` key (claim defects) or per the single key `internal` (pipeline fault).
 *
 * Oracle: the frozen `task-scope` clauses ("What" 1-2, "Flood limit", acceptance criteria 1-4) and
 * the `test-plan` scenarios S1-S14. Nothing here is derived from the implementation.
 *
 * Shapes (task-scope):
 * - claim defect, on the `JwksApiVerifier` logger, WARN:
 *   `API JWT claim defect: claim=<claim> reason=<code> action=<rejected|ignored> suppressed=<n>`
 * - pipeline fault, WARN: `JWT verification fault: reason=internal suppressed=<n>`
 * Limit: at most 10 lines per key per 60 s window, window open while now < start + 60 s, the first
 * line of the next window reports the count suppressed since the previous emitted line.
 *
 * Capture is attached to the root logger so the pipeline-fault line is seen whichever class emits it;
 * the secret checks inspect every INFO-or-higher event (DEBUG is allowed to carry detail).
 */
class JwksApiVerifierClaimDefectLogTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private const val KID = "claimdefect-rsa-key"
        private const val DEFECT_PREFIX = "API JWT claim defect:"
        private const val FAULT_PREFIX = "JWT verification fault:"
        private const val JWT_PACKAGE = "io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt"

        private val rsaKey = RSAKeyGenerator(2048).keyID(KID).generate()
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

    private lateinit var rootLogger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    private var savedLevel: Level? = null

    @BeforeEach
    fun attachAppender() {
        rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        rootLogger.addAppender(appender)
        savedLevel = rootLogger.level
        rootLogger.level = Level.DEBUG
    }

    @AfterEach
    fun detachAppender() {
        rootLogger.detachAppender(appender)
        appender.stop()
        rootLogger.level = savedLevel
    }

    // ---------------------------------------------------------------------
    // Harness (private per-class, mirrors JwksApiVerifierRejectionLogTest)
    // ---------------------------------------------------------------------

    private fun jwksAuthConfig(algorithms: List<String> = listOf("RS256")): ApiAuthConfig.Jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = testIssuer,
            audience = testAudience,
            algorithms = algorithms,
            cacheTtlSeconds = 300,
        )

    private fun providerReturning(keySet: JWKSet): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JwksResult(keySet, freshCacheState)
        coEvery { provider.refreshForUnknownKid(any()) } returns null
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun verifier(algorithms: List<String> = listOf("RS256")): JwksApiVerifier =
        JwksApiVerifier(jwksAuthConfig(algorithms), providerReturning(JWKSet(listOf(rsaKey.toPublicJWK()))), clock)

    /**
     * A verifier whose key set blows up with [message] as soon as anything reads its keys: a fault inside the
     * verification pipeline that is neither a crypto, claims nor policy rejection.
     */
    private fun faultingVerifier(message: String): JwksApiVerifier {
        val explosive = mockk<JWKSet>(relaxed = true)
        every { explosive.keys } throws IllegalStateException(message)
        every { explosive.getKeyByKeyId(any()) } throws IllegalStateException(message)
        return JwksApiVerifier(jwksAuthConfig(), providerReturning(explosive), clock)
    }

    private fun claims(extra: Map<String, Any> = emptyMap()): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject("api-caller-1")
                .issuer(testIssuer)
                .audience(testAudience)
                .expirationTime(Date.from(base.plusSeconds(300)))
        extra.forEach { (name, value) -> builder.claim(name, value) }
        return builder.build()
    }

    private fun tokenWith(extra: Map<String, Any>): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims(extra))
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    /** Signs a hand-written JSON payload so a literal JSON `null` claim survives (the claims builder drops nulls). */
    private fun tokenWithRawClaimJson(extraJson: String): String {
        val json =
            """{"sub":"api-caller-1","iss":"$testIssuer","aud":"$testAudience","exp":${base.plusSeconds(300).epochSecond},$extraJson}"""
        val jws = JWSObject(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), Payload(json))
        jws.sign(RSASSASigner(rsaKey))
        return jws.serialize()
    }

    private fun d(
        claim: String,
        reason: String,
        action: String,
        suppressed: Int,
    ): String = "$DEFECT_PREFIX claim=$claim reason=$reason action=$action suppressed=$suppressed"

    private fun f(suppressed: Int): String = "$FAULT_PREFIX reason=internal suppressed=$suppressed"

    private val verifierLoggerName: String = JwksApiVerifier::class.java.name

    private fun defectEvents(): List<ILoggingEvent> =
        appender.list.filter { it.loggerName == verifierLoggerName && it.formattedMessage.startsWith(DEFECT_PREFIX) }

    private fun defectMessages(): List<String> = defectEvents().map { it.formattedMessage }

    private fun faultEvents(): List<ILoggingEvent> = appender.list.filter { it.formattedMessage.startsWith(FAULT_PREFIX) }

    private fun faultMessages(): List<String> = faultEvents().map { it.formattedMessage }

    /** WARN-or-higher events from the jwt package, used for "a clean token logs no WARN". */
    private fun jwtWarnings(): List<ILoggingEvent> =
        appender.list.filter { it.loggerName.startsWith(JWT_PACKAGE) && it.level.isGreaterOrEqual(Level.WARN) }

    private fun chainMessages(proxy: IThrowableProxy?): List<String> =
        generateSequence(proxy) { it.cause }.map { "${it.className}: ${it.message.orEmpty()}" }.toList()

    private fun assertNoLeakAtInfoOrAbove(vararg markers: String) {
        appender.list
            .filter { it.level.isGreaterOrEqual(Level.INFO) }
            .forEach { event ->
                val rendered =
                    listOf(
                        event.formattedMessage,
                        event.message,
                        event.argumentArray?.joinToString("|") { it.toString() }.orEmpty(),
                    ) + chainMessages(event.throwableProxy)
                markers.forEach { marker ->
                    rendered.forEach { text ->
                        assertFalse(text.contains(marker), "INFO+ event on ${event.loggerName} leaked '$marker': $text")
                    }
                }
            }
    }

    private fun tokenMarkers(token: String): Array<String> = (listOf(token, "SECRET") + token.split('.')).toTypedArray()

    private fun verifiedPrincipal(verdict: ApiTokenVerdict): Principal {
        assertTrue(verdict is ApiTokenVerdict.Verified, "fixture must verify, got $verdict")
        return (verdict as ApiTokenVerdict.Verified).token.principal
    }

    // ---------------------------------------------------------------------
    // S1-S5 - rejected claim defects
    // ---------------------------------------------------------------------

    @Test
    fun `S1 string to_scope is rejected with one not_object defect line`(): Unit =
        runTest {
            val verdict = verifier().verifyWithExpiry(tokenWith(mapOf("to_scope" to "all")))
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("to_scope", "not_object", "rejected", 0)), defectMessages())
            assertTrue(defectEvents().all { it.level == Level.WARN }, "claim-defect lines are WARN")
        }

    @Test
    fun `S2 to_scope with a malformed root id is rejected with one malformed line and no value`(): Unit =
        runTest {
            val token = tokenWith(mapOf("to_scope" to mapOf("root_ids" to listOf("uuid-SECRET-a1"))))
            val verdict = verifier().verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("to_scope", "malformed", "rejected", 0)), defectMessages())
            assertNoLeakAtInfoOrAbove(*tokenMarkers(token), "uuid-SECRET-a1")
        }

    @Test
    fun `S3 to_scope with an unknown key is rejected with one malformed line and no key name`(): Unit =
        runTest {
            val token = tokenWith(mapOf("to_scope" to mapOf("rootz-SECRET-b2" to emptyList<String>())))
            val verdict = verifier().verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("to_scope", "malformed", "rejected", 0)), defectMessages())
            assertNoLeakAtInfoOrAbove(*tokenMarkers(token), "rootz-SECRET-b2")
        }

    @Test
    fun `S4 numeric to_kind is rejected with one not_string defect line`(): Unit =
        runTest {
            val verdict = verifier().verifyWithExpiry(tokenWith(mapOf("to_kind" to 42)))
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("to_kind", "not_string", "rejected", 0)), defectMessages())
        }

    @Test
    fun `S5 unknown to_kind value is rejected with one unknown_value line and no value`(): Unit =
        runTest {
            val token = tokenWith(mapOf("to_kind" to "boss-SECRET-c3"))
            val verdict = verifier().verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("to_kind", "unknown_value", "rejected", 0)), defectMessages())
            assertNoLeakAtInfoOrAbove(*tokenMarkers(token), "boss-SECRET-c3")
        }

    // ---------------------------------------------------------------------
    // S6-S7 - ignored capability defects (token still verifies with the default capability set)
    // ---------------------------------------------------------------------

    @Test
    fun `S6 string to_capabilities is ignored with one not_string_list line and no value`(): Unit =
        runTest {
            val token = tokenWith(mapOf("to_capabilities" to "admin-SECRET-d4"))
            val principal = verifiedPrincipal(verifier().verifyWithExpiry(token))
            assertEquals(setOf(ApiCapability.READ), principal.capabilities)
            assertEquals(listOf(d("to_capabilities", "not_string_list", "ignored", 0)), defectMessages())
            assertNoLeakAtInfoOrAbove(*tokenMarkers(token), "admin-SECRET-d4")
        }

    @Test
    fun `S7 several unknown capability elements produce exactly one line per token`(): Unit =
        runTest {
            val token = tokenWith(mapOf("to_capabilities" to listOf("read", "x-SECRET-e5", "y-SECRET-e5")))
            val principal = verifiedPrincipal(verifier().verifyWithExpiry(token))
            assertEquals(setOf(ApiCapability.READ), principal.capabilities)
            assertEquals(listOf(d("to_capabilities", "unknown_value", "ignored", 0)), defectMessages())
            assertNoLeakAtInfoOrAbove(*tokenMarkers(token), "x-SECRET-e5", "y-SECRET-e5")
        }

    // ---------------------------------------------------------------------
    // S8 - pipeline fault
    // ---------------------------------------------------------------------

    @Test
    fun `S8 a fault inside the pipeline is rejected with one fixed internal line and no exception text`(): Unit =
        runTest {
            val token = tokenWith(emptyMap())
            val verdict = faultingVerifier("boom-SECRET-f6").verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(f(0)), faultMessages())
            assertTrue(faultEvents().all { it.level == Level.WARN }, "the pipeline-fault line is WARN")
            assertNoLeakAtInfoOrAbove(*tokenMarkers(token), "boom-SECRET-f6", "boom")
        }

    // ---------------------------------------------------------------------
    // S10 - a clean token is silent
    // ---------------------------------------------------------------------

    @Test
    fun `S10 a clean token logs no warning at all`(): Unit =
        runTest {
            val clean =
                tokenWith(
                    mapOf(
                        "to_kind" to "human",
                        "to_scope" to mapOf("root_ids" to listOf(UUID.randomUUID().toString())),
                        "to_capabilities" to listOf("read"),
                    ),
                )
            val v = verifier()
            assertTrue(v.verifyWithExpiry(clean) is ApiTokenVerdict.Verified)
            assertEquals(emptyList<String>(), jwtWarnings().map { it.formattedMessage })
            // Paired guard: the same verifier DOES warn for a defective token, so the zero above is not a dead appender.
            v.verifyWithExpiry(tokenWith(mapOf("to_kind" to 42)))
            assertEquals(listOf(d("to_kind", "not_string", "rejected", 0)), defectMessages())
        }

    // ---------------------------------------------------------------------
    // S11-S13 - flood limit
    // ---------------------------------------------------------------------

    private suspend fun rejectNumericKind(
        verifier: JwksApiVerifier,
        times: Int,
    ) {
        val token = tokenWith(mapOf("to_kind" to 42))
        repeat(times) { verifier.verifyWithExpiry(token) }
    }

    @Test
    fun `S11 eleventh defect in a window is not logged and the next window reports the count`(): Unit =
        runTest {
            val v = verifier()
            rejectNumericKind(v, 11)
            assertEquals(List(10) { d("to_kind", "not_string", "rejected", 0) }, defectMessages())

            clock.now = base.plus(Duration.ofMillis(59_999))
            rejectNumericKind(v, 1)
            assertEquals(List(10) { d("to_kind", "not_string", "rejected", 0) }, defectMessages(), "window still open at 59.999 s")

            clock.now = base.plusSeconds(60)
            rejectNumericKind(v, 1)
            assertEquals(
                List(10) { d("to_kind", "not_string", "rejected", 0) } + d("to_kind", "not_string", "rejected", 2),
                defectMessages(),
                "the first line of the next window reports the two suppressed defects",
            )
        }

    @Test
    fun `S12 defect keys are limited independently`(): Unit =
        runTest {
            val v = verifier()
            rejectNumericKind(v, 11)
            v.verifyWithExpiry(tokenWith(mapOf("to_kind" to "zzz")))
            assertEquals(
                List(10) { d("to_kind", "not_string", "rejected", 0) } + d("to_kind", "unknown_value", "rejected", 0),
                defectMessages(),
            )
        }

    @Test
    fun `S13 the pipeline fault line is limited to ten per window and then reports the count`(): Unit =
        runTest {
            val v = faultingVerifier("boom-SECRET-f6")
            val token = tokenWith(emptyMap())
            repeat(11) { v.verifyWithExpiry(token) }
            assertEquals(List(10) { f(0) }, faultMessages())

            clock.now = base.plusSeconds(60)
            v.verifyWithExpiry(token)
            assertEquals(List(10) { f(0) } + f(1), faultMessages())
        }

    // ---------------------------------------------------------------------
    // S14 - empty / absent / null / padding of to_kind are distinct states
    // ---------------------------------------------------------------------

    @Test
    fun `S14 absent null blank and padded to_kind are classified as documented`(): Unit =
        runTest {
            val v = verifier()

            assertTrue(v.verifyWithExpiry(tokenWith(emptyMap())) is ApiTokenVerdict.Verified, "absent to_kind verifies")
            assertEquals(emptyList<String>(), defectMessages(), "absent to_kind is not a defect")

            val jsonNull = tokenWithRawClaimJson(""""to_kind":null""")
            val payloadJson = JWSObject.parse(jsonNull).payload.toString()
            assertTrue(payloadJson.contains(""""to_kind":null"""), "fixture must carry a literal JSON null, payload was $payloadJson")
            assertTrue(v.verifyWithExpiry(jsonNull) is ApiTokenVerdict.Verified, "JSON-null to_kind verifies")
            assertEquals(emptyList<String>(), defectMessages(), "JSON-null to_kind is not a defect")

            assertTrue(v.verifyWithExpiry(tokenWith(mapOf("to_kind" to " HUMAN "))) is ApiTokenVerdict.Verified)
            assertEquals(emptyList<String>(), defectMessages(), "PrincipalKind.parse is trimmed and case-insensitive")

            assertEquals(ApiTokenVerdict.Rejected, v.verifyWithExpiry(tokenWith(mapOf("to_kind" to ""))))
            assertEquals(listOf(d("to_kind", "unknown_value", "rejected", 0)), defectMessages(), "blank is an unknown kind")
        }

    // ---------------------------------------------------------------------
    // Probes
    // ---------------------------------------------------------------------

    @Test
    fun `probe concurrent defects never exceed ten lines and lose no counts`(): Unit =
        runTest {
            val v = verifier()
            val token = tokenWith(mapOf("to_kind" to 42))
            withContext(Dispatchers.Default) {
                (1..50).map { async { v.verifyWithExpiry(token) } }.awaitAll()
            }
            assertEquals(
                List(10) { d("to_kind", "not_string", "rejected", 0) },
                defectMessages(),
                "exactly 10 lines for 50 concurrent defects"
            )
            clock.now = base.plusSeconds(60)
            v.verifyWithExpiry(token)
            assertEquals(
                d("to_kind", "not_string", "rejected", 40),
                defectMessages().last(),
                "the 40 suppressed defects must all be counted"
            )
        }

    @Test
    fun `probe claim value with newline braces and percent cannot alter the line shape`(): Unit =
        runTest {
            val hostile = "a" + Char(10) + "{} %s reason=internal suppressed=99 claim=to_scope"
            val token = tokenWith(mapOf("to_kind" to hostile))
            assertEquals(ApiTokenVerdict.Rejected, verifier().verifyWithExpiry(token))
            assertEquals(listOf(d("to_kind", "unknown_value", "rejected", 0)), defectMessages())
            assertEquals(emptyList<String>(), faultMessages())
            assertNoLeakAtInfoOrAbove("suppressed=99", "%s")
        }

    @Test
    fun `probe the same defective token presented repeatedly is counted every time`(): Unit =
        runTest {
            val v = verifier()
            val token = tokenWith(mapOf("to_kind" to 42))
            repeat(12) { v.verifyWithExpiry(token) }
            clock.now = base.plusSeconds(60)
            v.verifyWithExpiry(token)
            assertEquals(
                List(10) { d("to_kind", "not_string", "rejected", 0) } + d("to_kind", "not_string", "rejected", 2),
                defectMessages(),
            )
        }

    @Test
    fun `probe an unsigned token with a secret-looking alg and an empty allowlist leaks nothing at info or above`(): Unit =
        runTest {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val header = enc.encodeToString("""{"alg":"XSECRET-h8","kid":"$KID"}""".toByteArray())
            val payload =
                enc.encodeToString(
                    """{"sub":"api-caller-1","iss":"$testIssuer","aud":"$testAudience","exp":${base.plusSeconds(
                        300
                    ).epochSecond}}""".toByteArray(),
                )
            val token = "$header.$payload.AAAA"
            val verdict = verifier(algorithms = emptyList()).verifyWithExpiry(token)
            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertNoLeakAtInfoOrAbove("XSECRET-h8", token, header, payload)
        }
}
