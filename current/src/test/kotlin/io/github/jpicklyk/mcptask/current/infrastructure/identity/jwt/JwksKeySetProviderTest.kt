package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.jpicklyk.mcptask.current.test.publicEgressGuard
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JwksKeySetProviderTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    @TempDir
    lateinit var tempDir: Path

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val rsaKey = RSAKeyGenerator(2048).keyID("file-rsa-key").generate()

    /** Write a JWKS JSON file containing the public RSA key and return its relative path. */
    private fun writeJwksFile(name: String = "test-jwks.json"): Path {
        val jwkSet = JWKSet(listOf(rsaKey.toPublicJWK()))
        val file = tempDir.resolve(name).toFile()
        file.writeText(jwkSet.toString())
        return tempDir.resolve(name)
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    // 1. File-based loading parses a valid JWK set
    @Test
    fun `file-based loading parses JWK set`() =
        runTest {
            val jwksFile = writeJwksFile()
            // Temporarily override user.dir to point at the temp directory so the relative path resolves.
            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = KeySourceConfig(jwksPath = "test-jwks.json")
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    val result = provider.getKeySet()
                    assertNotNull(result.keys)
                    assertEquals(1, result.keys.keys.size)
                    assertEquals(
                        "file-rsa-key",
                        result.keys.keys
                            .first()
                            .keyID
                    )
                    assertEquals(false, result.cacheState.fromStaleCache)
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // 2. Cache returns same instance within TTL
    @Test
    fun `cache returns same set within TTL`() =
        runTest {
            val jwksFile = writeJwksFile()
            val fixedNow = Instant.parse("2024-01-01T00:00:00Z")
            // Fixed clock — time never advances, so cache never expires.
            val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = KeySourceConfig(jwksPath = "test-jwks.json", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        clock = fixedClock,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    val first = provider.getKeySet()
                    val second = provider.getKeySet()
                    // Same JWKSet instance means the cache was used.
                    assert(first.keys === second.keys) { "Expected cache hit to return the same JWKSet instance" }
                    assertEquals(false, second.cacheState.fromStaleCache)
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // 3. Cache expires after TTL and triggers a refetch
    @Test
    fun `cache expires after TTL`() =
        runTest {
            val jwksFile = writeJwksFile()
            val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

            // Mutable instant that we advance between calls.
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
                val config = KeySourceConfig(jwksPath = "test-jwks.json", cacheTtlSeconds = 60)
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        clock = advancingClock,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    val first = provider.getKeySet()

                    // Advance clock past TTL.
                    currentInstant = baseInstant.plusSeconds(120)

                    val second = provider.getKeySet()
                    // After TTL expires, a new JWKSet is parsed — it should equal the first but be a fresh instance.
                    assertNotNull(second.keys)
                    assertEquals(first.keys.keys.size, second.keys.keys.size)
                    // The instances should NOT be the same object (cache was invalidated and refreshed).
                    assert(first.keys !== second.keys) { "Expected a new JWKSet instance after cache expiry" }
                    assertEquals(false, second.cacheState.fromStaleCache)
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // -------------------------------------------------------------------------
    // Concurrent access
    // -------------------------------------------------------------------------

    // 4. Concurrent coroutines all get a valid key set without exceptions
    @Test
    fun `concurrent access returns valid key set for all callers`() =
        runTest {
            writeJwksFile()
            val fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = KeySourceConfig(jwksPath = "test-jwks.json", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        clock = fixedClock,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    // Launch 20 concurrent fetches — all should succeed without exceptions.
                    val results =
                        (1..20)
                            .map { async { provider.getKeySet() } }
                            .awaitAll()

                    assertEquals(20, results.size)
                    results.forEach { result ->
                        assertNotNull(result.keys)
                        assertEquals(1, result.keys.keys.size)
                        assertEquals(
                            "file-rsa-key",
                            result.keys.keys
                                .first()
                                .keyID
                        )
                        assertEquals(false, result.cacheState.fromStaleCache)
                    }
                    // All JWKSet instances should be the same cached object (identity check).
                    val distinctKeys = results.map { it.keys }.distinct()
                    assertEquals(1, distinctKeys.size, "All concurrent callers should get the same cached JWKSet instance")
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // -------------------------------------------------------------------------
    // OIDC discovery failure + fallback
    // -------------------------------------------------------------------------

    // 5. OIDC discovery fails but jwksPath succeeds — keys still loaded from file
    @Test
    fun `OIDC discovery failure falls back to file-based keys`() =
        runTest {
            writeJwksFile()

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                // oidcDiscovery points to unreachable host; jwksPath points to a valid file
                val config =
                    KeySourceConfig(
                        oidcDiscovery = "http://192.0.2.1:1/nonexistent",
                        jwksPath = "test-jwks.json"
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    val result = provider.getKeySet()
                    assertNotNull(result.keys)
                    assertEquals(1, result.keys.keys.size)
                    assertEquals(
                        "file-rsa-key",
                        result.keys.keys
                            .first()
                            .keyID
                    )
                    // OIDC discovery failed, so resolved issuer should be null
                    assertNull(provider.getResolvedIssuer())
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // 6. OIDC discovery fails and no other source — throws IllegalStateException
    @Test
    fun `OIDC discovery failure with no fallback source throws`() =
        runTest {
            val config =
                KeySourceConfig(
                    oidcDiscovery = "http://192.0.2.1:1/nonexistent"
                )
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    tempDir,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )
            try {
                assertThrows<Exception> {
                    provider.getKeySet()
                }
            } finally {
                provider.close()
            }
        }

    // 7. Missing file path throws and does not silently return empty key set
    @Test
    fun `missing jwksPath file throws`() =
        runTest {
            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = KeySourceConfig(jwksPath = "does-not-exist.json")
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    assertThrows<Exception> {
                        provider.getKeySet()
                    }
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // 8. Resolved issuer is null when no OIDC discovery is configured
    @Test
    fun `resolved issuer is null without OIDC discovery`() =
        runTest {
            writeJwksFile()

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = KeySourceConfig(jwksPath = "test-jwks.json")
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    provider.getKeySet() // trigger fetch
                    assertNull(provider.getResolvedIssuer())
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // -------------------------------------------------------------------------
    // Stale cache fallback
    // -------------------------------------------------------------------------

    // 9. staleOnError=true: when refresh fails and cache exists, returns stale set
    @Test
    fun `stale fallback returns cached set when refresh fails and staleOnError is true`() =
        runTest {
            writeJwksFile()
            val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

            var currentInstant = baseInstant
            val advancingClock =
                object : Clock() {
                    override fun getZone() = java.time.ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId): Clock = this

                    override fun instant(): Instant = currentInstant
                }

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                // TTL = 60s. staleOnError = true (default)
                val config =
                    KeySourceConfig(
                        jwksPath = "test-jwks.json",
                        cacheTtlSeconds = 60,
                        staleOnError = true
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        clock = advancingClock,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    // First successful fetch
                    val first = provider.getKeySet()
                    assertNotNull(first.keys)

                    // Advance past TTL and delete the file to simulate endpoint unavailability
                    currentInstant = baseInstant.plusSeconds(120)
                    tempDir.resolve("test-jwks.json").toFile().delete()

                    // Should return the stale set rather than throwing
                    val stale = provider.getKeySet()
                    assertNotNull(stale.keys)
                    assertEquals(first.keys.keys.size, stale.keys.keys.size)

                    // The returned JwksResult should report stale
                    assertTrue(stale.cacheState.fromStaleCache, "Expected fromStaleCache=true")
                    assertNotNull(stale.cacheState.ageSeconds)
                    assertTrue(stale.cacheState.ageSeconds!! >= 120L, "Expected ageSeconds >= 120, got ${stale.cacheState.ageSeconds}")
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // 10. staleOnError=true: no prior cache → exception propagates
    @Test
    fun `stale fallback throws when no prior cache and staleOnError is true`() =
        runTest {
            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                // File doesn't exist; no prior cache entry
                val config =
                    KeySourceConfig(
                        jwksPath = "does-not-exist.json",
                        staleOnError = true
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    assertThrows<Exception> {
                        provider.getKeySet()
                    }
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // 11. staleOnError=false: refresh failure propagates even when cache exists
    @Test
    fun `staleOnError false propagates exception despite existing cache`() =
        runTest {
            writeJwksFile()
            val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

            var currentInstant = baseInstant
            val advancingClock =
                object : Clock() {
                    override fun getZone() = java.time.ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId): Clock = this

                    override fun instant(): Instant = currentInstant
                }

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config =
                    KeySourceConfig(
                        jwksPath = "test-jwks.json",
                        cacheTtlSeconds = 60,
                        staleOnError = false
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        clock = advancingClock,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    // First successful fetch
                    provider.getKeySet()

                    // Advance past TTL and delete the file
                    currentInstant = baseInstant.plusSeconds(120)
                    tempDir.resolve("test-jwks.json").toFile().delete()

                    // Should throw because staleOnError=false
                    assertThrows<Exception> {
                        provider.getKeySet()
                    }
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // 12. JwksResult.cacheState returns non-stale after fresh fetch
    @Test
    fun `getKeySet returns non-stale cacheState after successful fetch`() =
        runTest {
            writeJwksFile()

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = KeySourceConfig(jwksPath = "test-jwks.json")
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    val result = provider.getKeySet()
                    assertTrue(!result.cacheState.fromStaleCache, "Expected fromStaleCache=false after fresh fetch")
                    assertNull(result.cacheState.ageSeconds)
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // =========================================================================
    // Cache state per-call isolation (concurrency safety)
    // =========================================================================

    /**
     * Verifies that the [CacheState] returned by each [JwksResult] reflects the state
     * of *that specific call*, not a shared instance field that could be clobbered by
     * a concurrent call.
     *
     * Strategy: Use an advancing clock to force stale→fresh→stale transitions deterministically
     * (no Thread.sleep, no real concurrency needed — if the state is correct per-return
     * value, the shared-field race is structurally impossible).
     *
     * Specifically:
     *  1. Fetch at T=0  → fresh (FRESH_CACHE)
     *  2. Advance clock past TTL, delete file to simulate unavailability
     *  3. Fetch at T=120 → stale fallback (fromStaleCache=true)
     *  4. Restore file
     *  5. Advance clock further past TTL again
     *  6. Fetch at T=300 → fresh again (new successful fetch)
     *
     * If each call returns the correct state via JwksResult, the test passes.
     * With the old shared `lastCacheState` field, step 6 returning fresh would still
     * not prove isolation — but the lack of a shared field means no concurrent clobber
     * is even possible, which is the design guarantee this test documents.
     */
    @Test
    fun `JwksResult cacheState is isolated per call — stale then fresh sequence`() =
        runTest {
            writeJwksFile()
            val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

            var currentInstant = baseInstant
            val advancingClock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId): Clock = this

                    override fun instant(): Instant = currentInstant
                }

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = KeySourceConfig(jwksPath = "test-jwks.json", cacheTtlSeconds = 60, staleOnError = true)
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        tempDir,
                        clock = advancingClock,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )
                try {
                    // Call 1: fresh fetch at T=0
                    val call1 = provider.getKeySet()
                    assertEquals(false, call1.cacheState.fromStaleCache, "Call 1 should be fresh")
                    assertNull(call1.cacheState.ageSeconds)

                    // Advance past TTL and remove file to force stale fallback
                    currentInstant = baseInstant.plusSeconds(120)
                    tempDir.resolve("test-jwks.json").toFile().delete()

                    // Call 2: stale fallback
                    val call2 = provider.getKeySet()
                    assertEquals(true, call2.cacheState.fromStaleCache, "Call 2 should be stale")
                    assertNotNull(call2.cacheState.ageSeconds)
                    assertTrue(call2.cacheState.ageSeconds!! >= 120L)

                    // Restore file and advance clock so cache expires again
                    writeJwksFile()
                    currentInstant = baseInstant.plusSeconds(300)

                    // Call 3: fresh fetch again (file is back)
                    val call3 = provider.getKeySet()
                    assertEquals(false, call3.cacheState.fromStaleCache, "Call 3 should be fresh again")
                    assertNull(call3.cacheState.ageSeconds)

                    // Verify: call2's state is still stale (not overwritten by call3)
                    assertEquals(true, call2.cacheState.fromStaleCache, "call2 state must not be mutated by call3")
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // =========================================================================
    // HTTP hardening — JWKS URI fetch path
    // =========================================================================

    /**
     * Builds a minimal valid JWKS JSON payload (public RSA key only).
     */
    private fun validJwksJson(): String {
        val key = RSAKeyGenerator(2048).keyID("hardening-test-key").generate()
        return JWKSet(listOf(key.toPublicJWK())).toString()
    }

    @Test
    fun `jwksUri fetch throws on redirect response`() =
        runTest {
            var requestCount = 0
            val engine =
                MockEngine { _ ->
                    requestCount++
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf("Location", "https://attacker.example.com/jwks.json")
                    )
                }

            val config = KeySourceConfig(jwksUri = "https://auth.example.com/jwks.json")
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    tempDir,
                    httpClientEngineForTest = engine,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )
            try {
                assertThrows<Exception> {
                    provider.getKeySet()
                }
                assertEquals(1, requestCount, "Redirect target must NOT be fetched")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `jwksUri fetch throws on non-200 status`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "Forbidden",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf("Content-Type", "text/plain")
                    )
                }

            val config = KeySourceConfig(jwksUri = "https://auth.example.com/jwks.json")
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    tempDir,
                    httpClientEngineForTest = engine,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )
            try {
                val ex =
                    assertThrows<Exception> {
                        provider.getKeySet()
                    }
                val msg = ex.message ?: ex.cause?.message ?: ""
                assertTrue(
                    msg.contains("403") || ex.cause?.message?.contains("403") == true,
                    "Exception chain should mention 403; got: ${ex.message}, cause: ${ex.cause?.message}"
                )
            } finally {
                provider.close()
            }
        }

    @Test
    fun `jwksUri fetch throws on wrong content type`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = validJwksJson(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/html")
                    )
                }

            val config = KeySourceConfig(jwksUri = "https://auth.example.com/jwks.json")
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    tempDir,
                    httpClientEngineForTest = engine,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )
            try {
                assertThrows<Exception> {
                    provider.getKeySet()
                }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `jwksUri fetch succeeds with application-jwk-set+json content type`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = validJwksJson(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/jwk-set+json")
                    )
                }

            val config = KeySourceConfig(jwksUri = "https://auth.example.com/jwks.json")
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    tempDir,
                    httpClientEngineForTest = engine,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )
            try {
                val result = provider.getKeySet()
                assertNotNull(result.keys)
                assertEquals(1, result.keys.keys.size)
            } finally {
                provider.close()
            }
        }

    // =========================================================================
    // Timeout test — gap documentation
    // =========================================================================

    // NOTE: A test for the HTTP request-timeout config (requestTimeoutMillis / connectTimeoutMillis)
    // was evaluated but deferred. Ktor's MockEngine processes requests synchronously within the
    // test coroutine dispatcher, so `delay()` inside the MockEngine lambda does not actually
    // trigger the Ktor HttpTimeout plugin's timer (which runs on its own scheduler independent of
    // the test dispatcher). Pointing the live CIO engine at a non-routable RFC 5737 address
    // (192.0.2.1) would exercise connect-timeout but introduces real wall-clock latency (~3s)
    // and requires network availability, making it unsuitable for a unit/integration test suite.
    //
    // Coverage rationale: the timeout values (REQUEST_TIMEOUT_MS, CONNECT_TIMEOUT_MS,
    // SOCKET_TIMEOUT_MS) are compile-time constants; a regression would be obvious in code
    // review. The existing redirect-rejection and non-200-status tests already exercise the
    // HttpClient pipeline indirectly. A dedicated timeout test is tracked as a future improvement
    // if an in-process HTTP server (e.g., embedded Ktor server) becomes available in the test
    // harness without adding a heavyweight dependency.
}
