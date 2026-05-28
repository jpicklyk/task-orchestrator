package io.github.jpicklyk.mcptask.current.infrastructure.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [JwksKeyCache] covering the five key cache scenarios.
 *
 * [JwksKeyCache] is a typealias for [DefaultJwksKeySetProvider][io.github.jpicklyk.mcptask.current.infrastructure.config.DefaultJwksKeySetProvider].
 * Tests exercise the caching contract (fresh-fetch, cache-hit, expiry, stale-on-error, hard-fail)
 * through the [JwksKeyCache] name so the alias is exercised and the security package is verified.
 */
class JwksKeyCacheTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    @TempDir
    lateinit var tempDir: Path

    private val rsaKey = RSAKeyGenerator(2048).keyID("cache-test-key").generate()

    private fun writeJwksFile(name: String = "jwks.json"): Path {
        val file = tempDir.resolve(name).toFile()
        file.writeText(JWKSet(listOf(rsaKey.toPublicJWK())).toString())
        return tempDir.resolve(name)
    }

    // -------------------------------------------------------------------------
    // 1. Fresh-fetch: cache miss → fetch → populate
    // -------------------------------------------------------------------------

    @Test
    fun `fresh fetch populates cache and returns FRESH state`() =
        runTest {
            writeJwksFile()
            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = VerifierConfig.Jwks(jwksPath = "jwks.json")
                val cache = JwksKeyCache(config)
                try {
                    val result = cache.getKeySet()
                    assertNotNull(result.keys)
                    assertEquals(1, result.keys.keys.size)
                    assertEquals("cache-test-key", result.keys.keys.first().keyID)
                    assertEquals(false, result.cacheState.fromStaleCache, "First fetch must not be marked stale")
                } finally {
                    cache.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // -------------------------------------------------------------------------
    // 2. Cache-hit within TTL: second call reuses cached key set
    // -------------------------------------------------------------------------

    @Test
    fun `cache hit within TTL returns same JWKSet instance without re-fetching`() =
        runTest {
            writeJwksFile()
            val fixedClock = Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC)
            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = VerifierConfig.Jwks(jwksPath = "jwks.json", cacheTtlSeconds = 300)
                val cache = JwksKeyCache(config, clock = fixedClock)
                try {
                    val first = cache.getKeySet()
                    val second = cache.getKeySet()
                    // Cache hit → exact same JWKSet object reference
                    assert(first.keys === second.keys) {
                        "Expected cache hit to return the same JWKSet instance within TTL"
                    }
                    assertEquals(false, second.cacheState.fromStaleCache)
                } finally {
                    cache.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // -------------------------------------------------------------------------
    // 3. Expired-and-refresh: past TTL → re-fetch → fresh result
    // -------------------------------------------------------------------------

    @Test
    fun `expired cache triggers re-fetch and returns new JWKSet instance`() =
        runTest {
            writeJwksFile()
            val baseInstant = Instant.parse("2024-06-01T00:00:00Z")
            var currentInstant = baseInstant
            val advancingClock =
                object : Clock() {
                    override fun getZone(): ZoneOffset = ZoneOffset.UTC
                    override fun withZone(zone: java.time.ZoneId): Clock = this
                    override fun instant(): Instant = currentInstant
                }
            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = VerifierConfig.Jwks(jwksPath = "jwks.json", cacheTtlSeconds = 60)
                val cache = JwksKeyCache(config, clock = advancingClock)
                try {
                    val first = cache.getKeySet()
                    // Advance past TTL
                    currentInstant = baseInstant.plusSeconds(120)
                    val second = cache.getKeySet()
                    // After expiry a new JWKSet is parsed — should NOT be the same object
                    assert(first.keys !== second.keys) {
                        "Expected a new JWKSet instance after cache expiry"
                    }
                    assertEquals(false, second.cacheState.fromStaleCache)
                } finally {
                    cache.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // -------------------------------------------------------------------------
    // 4. Expired-and-stale-on-error: refresh fails → serve stale with metadata
    // -------------------------------------------------------------------------

    @Test
    fun `stale-on-error serves cached keys when refresh fails`() =
        runTest {
            // Set up a mock HTTP server: first request succeeds, subsequent requests fail.
            val fetchCount = AtomicInteger(0)
            val mockJwks = JWKSet(listOf(rsaKey.toPublicJWK())).toString()
            val engine =
                MockEngine { _ ->
                    if (fetchCount.incrementAndGet() == 1) {
                        respond(
                            content = mockJwks,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json"),
                        )
                    } else {
                        respond(
                            content = "Service Unavailable",
                            status = HttpStatusCode.ServiceUnavailable,
                            headers = headersOf("Content-Type", "text/plain"),
                        )
                    }
                }

            val baseInstant = Instant.parse("2024-06-01T00:00:00Z")
            var currentInstant = baseInstant
            val advancingClock =
                object : Clock() {
                    override fun getZone(): ZoneOffset = ZoneOffset.UTC
                    override fun withZone(zone: java.time.ZoneId): Clock = this
                    override fun instant(): Instant = currentInstant
                }

            val config =
                VerifierConfig.Jwks(
                    jwksUri = "https://example.com/.well-known/jwks.json",
                    cacheTtlSeconds = 60,
                    staleOnError = true,
                )
            val cache = JwksKeyCache(config, clock = advancingClock, httpClientEngineForTest = engine)
            try {
                // First call: fresh fetch succeeds and populates the cache.
                val first = cache.getKeySet()
                assertEquals(false, first.cacheState.fromStaleCache)

                // Advance past TTL so the cache expires.
                currentInstant = baseInstant.plusSeconds(120)

                // Second call: re-fetch fails, but staleOnError=true → should return stale copy.
                val second = cache.getKeySet()
                assertEquals(true, second.cacheState.fromStaleCache, "Expected stale cache fallback on refresh failure")
                assertNotNull(second.cacheState.ageSeconds, "Stale entry must include age metadata")
                // Keys should match the original successful fetch.
                assertEquals(1, second.keys.keys.size)
                assertEquals("cache-test-key", second.keys.keys.first().keyID)
            } finally {
                cache.close()
            }
        }

    // -------------------------------------------------------------------------
    // 5. Expired-and-fail: staleOnError=false → exception propagates
    // -------------------------------------------------------------------------

    @Test
    fun `stale-on-error disabled rethrows exception when refresh fails`() =
        runTest {
            // First request succeeds; subsequent fail.
            val fetchCount = AtomicInteger(0)
            val mockJwks = JWKSet(listOf(rsaKey.toPublicJWK())).toString()
            val engine =
                MockEngine { _ ->
                    if (fetchCount.incrementAndGet() == 1) {
                        respond(
                            content = mockJwks,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json"),
                        )
                    } else {
                        respond(
                            content = "Internal Server Error",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf("Content-Type", "text/plain"),
                        )
                    }
                }

            val baseInstant = Instant.parse("2024-06-01T00:00:00Z")
            var currentInstant = baseInstant
            val advancingClock =
                object : Clock() {
                    override fun getZone(): ZoneOffset = ZoneOffset.UTC
                    override fun withZone(zone: java.time.ZoneId): Clock = this
                    override fun instant(): Instant = currentInstant
                }

            val config =
                VerifierConfig.Jwks(
                    jwksUri = "https://example.com/.well-known/jwks.json",
                    cacheTtlSeconds = 60,
                    staleOnError = false, // hard-fail mode
                )
            val cache = JwksKeyCache(config, clock = advancingClock, httpClientEngineForTest = engine)
            try {
                // Prime the cache.
                cache.getKeySet()

                // Advance past TTL.
                currentInstant = baseInstant.plusSeconds(120)

                // Second call should throw because staleOnError=false and refresh failed.
                var threwException = false
                try {
                    cache.getKeySet()
                } catch (e: Exception) {
                    threwException = true
                }
                assertEquals(true, threwException, "Expected an exception when staleOnError=false and refresh fails")
            } finally {
                cache.close()
            }
        }
}
