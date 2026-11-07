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
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Independent test-author coverage for item 8847d8fa (S9-S13): a verified JWT payload that repeats a JSON
 * object key at any depth is rejected before any claim is trusted, with exactly one fixed WARN line
 * (`claim=payload reason=duplicate_key action=rejected`) that carries no token, claim value or key name.
 *
 * Oracle: frozen `task-scope` D2, D3 and citation C7 (Nimbus/Gson already rejects a duplicated TOP-LEVEL key, so
 * the new scan's WARN is asserted for nested duplicates only), the `test-plan` scenarios S9-S13, the Jackson
 * `STRICT_DUPLICATE_DETECTION` contract, and the `JwksApiVerifier` KDoc ("Fail-closed ... `to_kind` unknown
 * rejects"). WARN line shape: `API JWT claim defect: claim=<claim> reason=<code> action=<rejected|ignored>
 * suppressed=<n>` (dispatch log-line constant). Nothing here is derived from the implementation; no `src/main`
 * file was opened. Harness: per-class private builders, adapted from JwksApiVerifierClaimDefectLogTest.
 *
 * Signing raw payload JSON is required: the JWT claims builder collapses duplicates and drops nulls, so a payload
 * that repeats a key can only be produced by signing hand-written JSON.
 */
class JwksApiVerifierPayloadScanTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private const val KID = "payloadscan-rsa-key"
        private const val DEFECT_PREFIX = "API JWT claim defect:"
        private const val JWT_PACKAGE = "io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt"

        private val rsaKey = RSAKeyGenerator(2048).keyID(KID).generate()
    }

    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"
    private val freshCacheState = CacheState(fromStaleCache = false, ageSeconds = null)
    private val base: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val u1 = "11111111-1111-1111-1111-111111111111"

    private class FixedClock(
        private val now: Instant
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = FixedClock(base)

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
    // Harness
    // ---------------------------------------------------------------------

    private fun verifier(): JwksApiVerifier {
        val config =
            ApiAuthConfig.Jwks(
                url = "https://idp.test.example/.well-known/jwks.json",
                issuer = testIssuer,
                audience = testAudience,
                algorithms = listOf("RS256"),
                cacheTtlSeconds = 300
            )
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), freshCacheState)
        coEvery { provider.refreshForUnknownKid(any()) } returns null
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return JwksApiVerifier(config, provider, clock)
    }

    /** Signs hand-written JSON: the standard claims first, then [extraJson] (which may repeat any key). */
    private fun tokenWithRawClaimJson(extraJson: String): String {
        val json =
            """{"sub":"api-caller-1","iss":"$testIssuer","aud":"$testAudience","exp":${base.plusSeconds(300).epochSecond},$extraJson}"""
        val jws = JWSObject(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), Payload(json))
        jws.sign(RSASSASigner(rsaKey))
        return jws.serialize()
    }

    private fun tokenWithoutExtra(): String {
        val json =
            """{"sub":"api-caller-1","iss":"$testIssuer","aud":"$testAudience","exp":${base.plusSeconds(300).epochSecond}}"""
        val jws = JWSObject(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), Payload(json))
        jws.sign(RSASSASigner(rsaKey))
        return jws.serialize()
    }

    private fun d(
        claim: String,
        reason: String,
        action: String,
        suppressed: Int
    ): String = "$DEFECT_PREFIX claim=$claim reason=$reason action=$action suppressed=$suppressed"

    private val verifierLoggerName: String = JwksApiVerifier::class.java.name

    private fun defectEvents(): List<ILoggingEvent> =
        appender.list.filter { it.loggerName == verifierLoggerName && it.formattedMessage.startsWith(DEFECT_PREFIX) }

    private fun defectMessages(): List<String> = defectEvents().map { it.formattedMessage }

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
                        event.argumentArray?.joinToString("|") { it.toString() }.orEmpty()
                    ) + chainMessages(event.throwableProxy)
                markers.forEach { marker ->
                    rendered.forEach { text ->
                        assertFalse(text.contains(marker), "INFO+ event on ${event.loggerName} leaked '$marker': $text")
                    }
                }
            }
    }

    private fun tokenMarkers(token: String): Array<String> = (listOf(token) + token.split('.')).toTypedArray()

    private fun verifiedPrincipal(verdict: ApiTokenVerdict): Principal {
        assertTrue(verdict is ApiTokenVerdict.Verified, "fixture must verify, got $verdict")
        return (verdict as ApiTokenVerdict.Verified).token.principal
    }

    // ---------------------------------------------------------------------
    // S9 / S11: nested duplicates are rejected with the payload line
    // ---------------------------------------------------------------------

    @Test
    fun `S9 a duplicated root_ids inside to_scope is rejected with exactly one payload duplicate_key line`(): Unit =
        runTest {
            val token = tokenWithRawClaimJson(""""to_scope":{"root_ids":["$u1"],"root_ids":null}""")

            val verdict = verifier().verifyWithExpiry(token)

            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("payload", "duplicate_key", "rejected", 0)), defectMessages())
            assertTrue(defectEvents().all { it.level == Level.WARN }, "the duplicate-key line is WARN")
            assertNoLeakAtInfoOrAbove(*tokenMarkers(token), u1, "root_ids")
        }

    @Test
    fun `S9 probe - the widening shape is rejected with the payload line, not a to_scope line`(): Unit =
        runTest {
            val token = tokenWithRawClaimJson(""""to_scope":{"root_ids":["$u1"],"root_ids":["$u1"]}""")

            val verdict = verifier().verifyWithExpiry(token)

            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(
                listOf(d("payload", "duplicate_key", "rejected", 0)),
                defectMessages(),
                "even identical duplicate values are rejected"
            )
        }

    @Test
    fun `S11 a duplicate inside an unrelated nested claim is rejected with the payload line`(): Unit =
        runTest {
            val token = tokenWithRawClaimJson(""""x":{"a":1,"a":2}""")

            val verdict = verifier().verifyWithExpiry(token)

            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("payload", "duplicate_key", "rejected", 0)), defectMessages())
            assertNoLeakAtInfoOrAbove(*tokenMarkers(token))
        }

    @Test
    fun `S11 probe - a duplicate at depth three is rejected`(): Unit =
        runTest {
            val token = tokenWithRawClaimJson(""""x":{"y":{"z":{"a":1,"a":2}}}""")

            val verdict = verifier().verifyWithExpiry(token)

            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("payload", "duplicate_key", "rejected", 0)), defectMessages())
        }

    @Test
    fun `S11 probe - a duplicate inside an object held by an array is rejected`(): Unit =
        runTest {
            val token = tokenWithRawClaimJson(""""x":[{"a":1},{"b":1,"b":2}]""")

            val verdict = verifier().verifyWithExpiry(token)

            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("payload", "duplicate_key", "rejected", 0)), defectMessages())
        }

    // ---------------------------------------------------------------------
    // S10: top-level duplicate (already rejected by claims parsing; no WARN asserted)
    // ---------------------------------------------------------------------

    @Test
    fun `S10 a duplicated top-level sub is rejected`(): Unit =
        runTest {
            val token = tokenWithRawClaimJson(""""sub":"someone-else"""")

            val verdict = verifier().verifyWithExpiry(token)

            assertEquals(ApiTokenVerdict.Rejected, verdict)
        }

    // ---------------------------------------------------------------------
    // S12: to_scope present-but-empty forms are malformed, absent is unrestricted
    // ---------------------------------------------------------------------

    @Test
    fun `S12 every present-but-empty to_scope form is rejected as malformed`(): Unit =
        runTest {
            val forms =
                listOf(
                    "null" to """"to_scope":null""",
                    "empty-object" to """"to_scope":{}""",
                    "root-ids-null" to """"to_scope":{"root_ids":null}""",
                    "root-ids-empty" to """"to_scope":{"root_ids":[]}""",
                    "tags-null" to """"to_scope":{"tags_include":null}""",
                    "tags-empty" to """"to_scope":{"tags_include":[]}"""
                )
            for ((label, json) in forms) {
                appender.list.clear()

                val verdict = verifier().verifyWithExpiry(tokenWithRawClaimJson(json))

                assertEquals(ApiTokenVerdict.Rejected, verdict, "S12[$label] must be rejected")
                assertEquals(listOf(d("to_scope", "malformed", "rejected", 0)), defectMessages(), "S12[$label]")
            }
        }

    @Test
    fun `S12 an absent to_scope is verified and unrestricted with no defect line`(): Unit =
        runTest {
            val principal = verifiedPrincipal(verifier().verifyWithExpiry(tokenWithoutExtra()))

            assertNull(principal.scope.rootIds, "absent to_scope -> no root restriction")
            assertTrue(principal.scope.tagsInclude.isEmpty(), "absent to_scope -> no tag restriction")
            assertEquals(emptyList<String>(), defectMessages())
        }

    @Test
    fun `S12 a string to_scope is rejected as not_object`(): Unit =
        runTest {
            val verdict = verifier().verifyWithExpiry(tokenWithRawClaimJson(""""to_scope":"all""""))

            assertEquals(ApiTokenVerdict.Rejected, verdict)
            assertEquals(listOf(d("to_scope", "not_object", "rejected", 0)), defectMessages())
        }

    @Test
    fun `S12 a populated to_scope still narrows the principal`(): Unit =
        runTest {
            val principal =
                verifiedPrincipal(
                    verifier().verifyWithExpiry(tokenWithRawClaimJson(""""to_scope":{"root_ids":["$u1"],"tags_include":["a"]}"""))
                )

            assertEquals(setOf(UUID.fromString(u1)), principal.scope.rootIds)
            assertEquals(setOf("a"), principal.scope.tagsInclude)
        }

    // ---------------------------------------------------------------------
    // S13: nested objects without duplicates stay silent
    // ---------------------------------------------------------------------

    @Test
    fun `S13 nested objects without a repeated key verify with zero warnings`(): Unit =
        runTest {
            val token =
                tokenWithRawClaimJson(
                    """"to_scope":{"root_ids":["$u1"]},"x":{"a":{"b":1},"c":[{"a":1},{"a":2}],"d":{"a":3}}"""
                )

            val principal = verifiedPrincipal(verifier().verifyWithExpiry(token))

            assertEquals(setOf(UUID.fromString(u1)), principal.scope.rootIds)
            assertEquals(emptyList<String>(), defectMessages())
            assertEquals(
                emptyList<ILoggingEvent>(),
                jwtWarnings(),
                "a clean payload logs no WARN: ${jwtWarnings().map { it.formattedMessage }}"
            )
        }

    @Test
    fun `S13 probe - the same key name in sibling objects is not a duplicate`(): Unit =
        runTest {
            val token = tokenWithRawClaimJson(""""p":{"k":1},"q":{"k":2}""")

            val verdict = verifier().verifyWithExpiry(token)

            assertTrue(verdict is ApiTokenVerdict.Verified, "siblings may share key names, got $verdict")
            assertEquals(emptyList<String>(), defectMessages())
        }
}
