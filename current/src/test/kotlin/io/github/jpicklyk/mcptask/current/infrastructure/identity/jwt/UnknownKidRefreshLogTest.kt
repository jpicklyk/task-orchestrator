package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.jpicklyk.mcptask.current.test.publicEgressGuard
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent test-author coverage for item 803b4ca9, key-provider half: when the forced JWKS refresh
 * triggered by an unknown `kid` fails, the WARN on the [DefaultJwksKeySetProvider] logger names the
 * failure but never carries the unverified `kid` taken from the token header.
 *
 * Oracles: the frozen `task-scope` clause "JwksKeySetProvider:239 -> `Forced JWKS refresh for an
 * unknown kid failed: {}` (e.message of the fetch only; no kid). No new limiter." and acceptance
 * criterion 2 ("No WARN/INFO line carries ... the kid"); the bound on how often the line can occur
 * is the already-shipped D6 rule of item 385444c2 ("at most one forced refresh per 30 s per
 * provider"; a rate-limited call returns null without touching the network).
 */
class UnknownKidRefreshLogTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private const val URL = "https://keys.kid.example/.well-known/jwks.json"
        private const val SECRET_KID = "kid-SECRET-g7"
        private const val WARN_PREFIX = "Forced JWKS refresh for an unknown kid failed"
        private val T0: Instant = Instant.parse("2026-10-15T12:00:00Z")
        private val rsaKey = RSAKeyGenerator(2048).keyID("kid-test-key").generate()
    }

    private class TestClock(
        start: Instant
    ) : Clock() {
        @Volatile
        var now: Instant = start

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    /** Counts every request that reaches the (mock) key endpoint; flips between OK and 503. */
    private class Upstream(
        @Volatile var body: String
    ) {
        val fetches = AtomicInteger(0)

        @Volatile
        var failing = false

        val engine =
            MockEngine { _ ->
                fetches.incrementAndGet()
                if (failing) {
                    respond("Service Unavailable", HttpStatusCode.ServiceUnavailable, headersOf("Content-Type", "text/plain"))
                } else {
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            }
    }

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    private var savedLevel: Level? = null

    @BeforeEach
    fun attachAppender() {
        logger = LoggerFactory.getLogger(DefaultJwksKeySetProvider::class.java) as Logger
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

    private fun jwksBody(): String = JWKSet(listOf(rsaKey.toPublicJWK())).toString()

    private fun staticProvider(
        up: Upstream,
        clock: TestClock,
    ): DefaultJwksKeySetProvider {
        val config = KeySourceConfig(jwksUri = URL, cacheTtlSeconds = 60, staleOnError = true, maxStaleSeconds = 3600)
        return DefaultJwksKeySetProvider(
            config,
            Path.of("."),
            clock = clock,
            egressGuard = publicEgressGuard(),
            httpClientEngineForTest = up.engine,
        )
    }

    private fun refreshWarnings(): List<ILoggingEvent> =
        appender.list.filter { it.level == Level.WARN && it.formattedMessage.startsWith(WARN_PREFIX) }

    private fun chainMessages(proxy: IThrowableProxy?): List<String> =
        generateSequence(proxy) { it.cause }.map { "${it.className}: ${it.message.orEmpty()}" }.toList()

    private fun assertNoKidAtInfoOrAbove() {
        appender.list
            .filter { it.level.isGreaterOrEqual(Level.INFO) }
            .forEach { event ->
                val rendered =
                    listOf(
                        event.formattedMessage,
                        event.message,
                        event.argumentArray?.joinToString("|") { it.toString() }.orEmpty(),
                    ) + chainMessages(event.throwableProxy)
                rendered.forEach { text ->
                    assertFalse(text.contains(SECRET_KID), "INFO+ event leaked the kid: $text")
                    assertFalse(text.contains("SECRET"), "INFO+ event leaked the kid marker: $text")
                }
            }
    }

    // -------------------------------------------------------------------------
    // S9 - a failed forced refresh warns once and never names the kid
    // -------------------------------------------------------------------------

    @Test
    fun `S9 a failed forced refresh logs one warning that does not carry the kid`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                assertEquals(1, up.fetches.get())

                up.failing = true
                clock.now = T0.plusSeconds(10)
                assertNull(p.refreshForUnknownKid(SECRET_KID))
                assertEquals(2, up.fetches.get(), "fixture: the forced refresh must really reach the failing upstream")

                assertEquals(1, refreshWarnings().size, "exactly one forced-refresh warning: ${appender.list.map { it.formattedMessage }}")
                assertNoKidAtInfoOrAbove()
            } finally {
                p.close()
            }
        }

    // -------------------------------------------------------------------------
    // Probes
    // -------------------------------------------------------------------------

    @Test
    fun `probe repeated forced refreshes inside the rate limit add no warnings`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                up.failing = true
                clock.now = T0.plusSeconds(10)
                repeat(20) { assertNull(p.refreshForUnknownKid(SECRET_KID)) }
                assertEquals(2, up.fetches.get(), "only the first forced refresh may reach the network")
                assertEquals(1, refreshWarnings().size, "20 attempts in one rate-limit window yield one warning")
                assertNoKidAtInfoOrAbove()
            } finally {
                p.close()
            }
        }

    @Test
    fun `probe a second failed forced refresh after the rate limit warns again without the kid`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                up.failing = true
                clock.now = T0.plusSeconds(10)
                assertNull(p.refreshForUnknownKid(SECRET_KID))
                clock.now = T0.plusSeconds(100)
                assertNull(p.refreshForUnknownKid(SECRET_KID))
                assertEquals(3, up.fetches.get(), "fixture: both forced refreshes reached the failing upstream")
                assertEquals(2, refreshWarnings().size)
                assertNoKidAtInfoOrAbove()
            } finally {
                p.close()
            }
        }

    @Test
    fun `probe a successful forced refresh logs no warning`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                clock.now = T0.plusSeconds(10)
                p.refreshForUnknownKid(SECRET_KID)
                assertEquals(emptyList<String>(), refreshWarnings().map { it.formattedMessage })
                assertNoKidAtInfoOrAbove()
            } finally {
                p.close()
            }
        }
}
