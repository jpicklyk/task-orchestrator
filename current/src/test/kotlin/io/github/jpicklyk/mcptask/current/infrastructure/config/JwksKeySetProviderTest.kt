package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import io.github.jpicklyk.mcptask.current.domain.model.DidDocument
import io.github.jpicklyk.mcptask.current.domain.model.VerificationMethod
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.SecureRandom
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
                val config = VerifierConfig.Jwks(jwksPath = "test-jwks.json")
                val provider = DefaultJwksKeySetProvider(config)
                try {
                    val keySet = provider.getKeySet()
                    assertNotNull(keySet)
                    assertEquals(1, keySet.keys.size)
                    assertEquals("file-rsa-key", keySet.keys.first().keyID)
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
                val config = VerifierConfig.Jwks(jwksPath = "test-jwks.json", cacheTtlSeconds = 300)
                val provider = DefaultJwksKeySetProvider(config, clock = fixedClock)
                try {
                    val first = provider.getKeySet()
                    val second = provider.getKeySet()
                    // Same instance means the cache was used.
                    assert(first === second) { "Expected cache hit to return the same JWKSet instance" }
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
                val config = VerifierConfig.Jwks(jwksPath = "test-jwks.json", cacheTtlSeconds = 60)
                val provider = DefaultJwksKeySetProvider(config, clock = advancingClock)
                try {
                    val first = provider.getKeySet()

                    // Advance clock past TTL.
                    currentInstant = baseInstant.plusSeconds(120)

                    val second = provider.getKeySet()
                    // After TTL expires, a new JWKSet is parsed — it should equal the first but be a fresh instance.
                    assertNotNull(second)
                    assertEquals(first.keys.size, second.keys.size)
                    // The instances should NOT be the same object (cache was invalidated and refreshed).
                    assert(first !== second) { "Expected a new JWKSet instance after cache expiry" }
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
                val config = VerifierConfig.Jwks(jwksPath = "test-jwks.json", cacheTtlSeconds = 300)
                val provider = DefaultJwksKeySetProvider(config, clock = fixedClock)
                try {
                    // Launch 20 concurrent fetches — all should succeed without exceptions.
                    val results =
                        (1..20)
                            .map { async { provider.getKeySet() } }
                            .awaitAll()

                    assertEquals(20, results.size)
                    results.forEach { keySet ->
                        assertNotNull(keySet)
                        assertEquals(1, keySet.keys.size)
                        assertEquals("file-rsa-key", keySet.keys.first().keyID)
                    }
                    // All should be the same cached instance.
                    val distinct = results.distinct()
                    assertEquals(1, distinct.size, "All concurrent callers should get the same cached instance")
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
                    VerifierConfig.Jwks(
                        oidcDiscovery = "http://192.0.2.1:1/nonexistent",
                        jwksPath = "test-jwks.json"
                    )
                val provider = DefaultJwksKeySetProvider(config)
                try {
                    val keySet = provider.getKeySet()
                    assertNotNull(keySet)
                    assertEquals(1, keySet.keys.size)
                    assertEquals("file-rsa-key", keySet.keys.first().keyID)
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
                VerifierConfig.Jwks(
                    oidcDiscovery = "http://192.0.2.1:1/nonexistent"
                )
            val provider = DefaultJwksKeySetProvider(config)
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
                val config = VerifierConfig.Jwks(jwksPath = "does-not-exist.json")
                val provider = DefaultJwksKeySetProvider(config)
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
                val config = VerifierConfig.Jwks(jwksPath = "test-jwks.json")
                val provider = DefaultJwksKeySetProvider(config)
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
                    VerifierConfig.Jwks(
                        jwksPath = "test-jwks.json",
                        cacheTtlSeconds = 60,
                        staleOnError = true
                    )
                val provider = DefaultJwksKeySetProvider(config, clock = advancingClock)
                try {
                    // First successful fetch
                    val first = provider.getKeySet()
                    assertNotNull(first)

                    // Advance past TTL and delete the file to simulate endpoint unavailability
                    currentInstant = baseInstant.plusSeconds(120)
                    tempDir.resolve("test-jwks.json").toFile().delete()

                    // Should return the stale set rather than throwing
                    val stale = provider.getKeySet()
                    assertNotNull(stale)
                    assertEquals(first.keys.size, stale.keys.size)

                    // getCacheState should report stale
                    val state = provider.getCacheState()
                    assertTrue(state.fromStaleCache, "Expected fromStaleCache=true")
                    assertNotNull(state.ageSeconds)
                    assertTrue(state.ageSeconds!! >= 120L, "Expected ageSeconds >= 120, got ${state.ageSeconds}")
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
                    VerifierConfig.Jwks(
                        jwksPath = "does-not-exist.json",
                        staleOnError = true
                    )
                val provider = DefaultJwksKeySetProvider(config)
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
                    VerifierConfig.Jwks(
                        jwksPath = "test-jwks.json",
                        cacheTtlSeconds = 60,
                        staleOnError = false
                    )
                val provider = DefaultJwksKeySetProvider(config, clock = advancingClock)
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

    // 12. getCacheState returns non-stale after fresh fetch
    @Test
    fun `getCacheState returns non-stale after successful fetch`() =
        runTest {
            writeJwksFile()

            val prevUserDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
            try {
                val config = VerifierConfig.Jwks(jwksPath = "test-jwks.json")
                val provider = DefaultJwksKeySetProvider(config)
                try {
                    provider.getKeySet()
                    val state = provider.getCacheState()
                    assertTrue(!state.fromStaleCache, "Expected fromStaleCache=false after fresh fetch")
                    assertNull(state.ageSeconds)
                } finally {
                    provider.close()
                }
            } finally {
                System.setProperty("user.dir", prevUserDir)
            }
        }

    // =========================================================================
    // DID-trust path tests
    // =========================================================================

    @Nested
    inner class DidTrustPath {
        // Helper: build a synthetic DidDocument with one Ed25519 public key.
        private fun buildDidDocument(
            did: String,
            kid: String = "key-1"
        ): Pair<DidDocument, OctetKeyPair> {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val pair = gen.generateKeyPair()
            val pub = pair.public as Ed25519PublicKeyParameters
            val priv = pair.private as Ed25519PrivateKeyParameters

            val edKey =
                OctetKeyPair
                    .Builder(Curve.Ed25519, Base64URL.encode(pub.encoded))
                    .d(Base64URL.encode(priv.encoded))
                    .keyID(kid)
                    .build()

            val publicKeyJwk: Map<String, Any> =
                mapOf(
                    "kty" to "OKP",
                    "crv" to "Ed25519",
                    "x" to edKey.x.toString(),
                    "kid" to kid
                )

            val vm =
                VerificationMethod(
                    id = "$did#$kid",
                    type = "JsonWebKey2020",
                    controller = did,
                    publicKeyJwk = publicKeyJwk
                )
            val doc =
                DidDocument(
                    id = did,
                    verificationMethods = listOf(vm),
                    assertionMethod = listOf("$did#$kid")
                )
            return Pair(doc, edKey)
        }

        private fun makeFixedClock(base: Instant = Instant.parse("2024-01-01T00:00:00Z")): Clock = Clock.fixed(base, ZoneOffset.UTC)

        // DID-T1: allowlist match returns extracted JWKSet
        @Test
        fun `getKeySetForIssuer with allowlist match returns JWKSet`() =
            runTest {
                val did = "did:web:trusted.example.com"
                val (doc, _) = buildDidDocument(did)
                val mockRegistry = mockk<DidResolverRegistry>()
                coEvery { mockRegistry.resolve(did) } returns doc

                val config =
                    VerifierConfig.Jwks(
                        didAllowlist = listOf(did),
                        cacheTtlSeconds = 300
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        clock = makeFixedClock(),
                        didResolverRegistry = mockRegistry
                    )

                val jwks = provider.getKeySetForIssuer(did)
                assertNotNull(jwks)
                assertEquals(1, jwks.keys.size)
                assertEquals("key-1", jwks.keys.first().keyID)
            }

        // DID-T2: pattern match returns JWKSet
        @Test
        fun `getKeySetForIssuer with pattern match returns JWKSet`() =
            runTest {
                val pattern = "did:web:example.com:agents:*"
                val did = "did:web:example.com:agents:abc"
                val (doc, _) = buildDidDocument(did)
                val mockRegistry = mockk<DidResolverRegistry>()
                coEvery { mockRegistry.resolve(did) } returns doc

                val config =
                    VerifierConfig.Jwks(
                        didPattern = pattern,
                        cacheTtlSeconds = 300
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        clock = makeFixedClock(),
                        didResolverRegistry = mockRegistry
                    )

                val jwks = provider.getKeySetForIssuer(did)
                assertNotNull(jwks)
                assertEquals(1, jwks.keys.size)
            }

        // DID-T3: untrusted issuer throws IssuerNotTrustedException
        @Test
        fun `getKeySetForIssuer with no trust match throws IssuerNotTrustedException`() =
            runTest {
                val config =
                    VerifierConfig.Jwks(
                        didAllowlist = listOf("did:web:trusted.example.com"),
                        cacheTtlSeconds = 300
                    )
                val provider = DefaultJwksKeySetProvider(config)

                assertThrows<IssuerNotTrustedException> {
                    provider.getKeySetForIssuer("did:web:untrusted.example.com")
                }
            }

        // DID-T4: per-issuer cache hit — second call within TTL does not re-resolve
        @Test
        fun `getKeySetForIssuer cache hit within TTL does not re-resolve`() =
            runTest {
                val did = "did:web:cached.example.com"
                val (doc, _) = buildDidDocument(did)
                val mockRegistry = mockk<DidResolverRegistry>()
                coEvery { mockRegistry.resolve(did) } returns doc

                val config =
                    VerifierConfig.Jwks(
                        didAllowlist = listOf(did),
                        cacheTtlSeconds = 300
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        clock = makeFixedClock(),
                        didResolverRegistry = mockRegistry
                    )

                val first = provider.getKeySetForIssuer(did)
                val second = provider.getKeySetForIssuer(did)

                // Same cached instance
                assert(first === second) { "Expected cache hit to return same JWKSet instance" }
                // Registry should only be called once
                coVerify(exactly = 1) { mockRegistry.resolve(did) }
            }

        // DID-T5: per-issuer cache expiry triggers re-resolve
        @Test
        fun `getKeySetForIssuer cache expires and re-resolves after TTL`() =
            runTest {
                val did = "did:web:expiry.example.com"
                val (doc, _) = buildDidDocument(did)
                val mockRegistry = mockk<DidResolverRegistry>()
                coEvery { mockRegistry.resolve(did) } returns doc

                val baseInstant = Instant.parse("2024-01-01T00:00:00Z")
                var currentInstant = baseInstant
                val advancingClock =
                    object : Clock() {
                        override fun getZone(): ZoneOffset = ZoneOffset.UTC

                        override fun withZone(zone: java.time.ZoneId): Clock = this

                        override fun instant(): Instant = currentInstant
                    }

                val config =
                    VerifierConfig.Jwks(
                        didAllowlist = listOf(did),
                        cacheTtlSeconds = 60
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        clock = advancingClock,
                        didResolverRegistry = mockRegistry
                    )

                val first = provider.getKeySetForIssuer(did)

                // Advance clock past TTL
                currentInstant = baseInstant.plusSeconds(120)

                val second = provider.getKeySetForIssuer(did)

                // Should have fetched twice (initial + after expiry)
                coVerify(exactly = 2) { mockRegistry.resolve(did) }
                assertNotNull(second)
                // New parse, different instance
                assert(first !== second) { "Expected a new JWKSet instance after cache expiry" }
            }

        // DID-T6: stale-on-error for DID cache
        @Test
        fun `getKeySetForIssuer returns stale cache when resolver fails and staleOnError is true`() =
            runTest {
                val did = "did:web:stale.example.com"
                val (doc, _) = buildDidDocument(did)
                val mockRegistry = mockk<DidResolverRegistry>()

                val baseInstant = Instant.parse("2024-01-01T00:00:00Z")
                var currentInstant = baseInstant
                val advancingClock =
                    object : Clock() {
                        override fun getZone(): ZoneOffset = ZoneOffset.UTC

                        override fun withZone(zone: java.time.ZoneId): Clock = this

                        override fun instant(): Instant = currentInstant
                    }

                var resolveCount = 0
                coEvery { mockRegistry.resolve(did) } answers {
                    resolveCount++
                    if (resolveCount == 1) {
                        doc
                    } else {
                        throw DidResolutionException("network failure")
                    }
                }

                val config =
                    VerifierConfig.Jwks(
                        didAllowlist = listOf(did),
                        cacheTtlSeconds = 60,
                        staleOnError = true
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        clock = advancingClock,
                        didResolverRegistry = mockRegistry
                    )

                // First successful fetch
                val first = provider.getKeySetForIssuer(did)
                assertNotNull(first)

                // Advance past TTL so cache expires
                currentInstant = baseInstant.plusSeconds(120)

                // Second call — resolver fails, should serve stale
                val stale = provider.getKeySetForIssuer(did)
                assertNotNull(stale)
                assertEquals(first.keys.size, stale.keys.size)

                val state = provider.getCacheState()
                assertTrue(state.fromStaleCache, "Expected fromStaleCache=true")
                assertTrue(state.ageSeconds!! >= 120L, "Expected ageSeconds >= 120, got ${state.ageSeconds}")
            }

        // DID-T7: LRU eviction at 257 distinct issuers
        @Test
        fun `LRU eviction keeps cache at MAX_DID_CACHE_ENTRIES`() =
            runTest {
                val baseInstant = Instant.parse("2024-01-01T00:00:00Z")
                val fixedClock = Clock.fixed(baseInstant, ZoneOffset.UTC)

                // Build 257 distinct DIDs
                val numDids = 257
                val mockRegistry = mockk<DidResolverRegistry>()
                val dids = (1..numDids).map { i -> "did:web:host-$i.example.com" }

                dids.forEach { did ->
                    val (doc, _) = buildDidDocument(did)
                    coEvery { mockRegistry.resolve(did) } returns doc
                }

                val allowlist = dids.toList()
                val config =
                    VerifierConfig.Jwks(
                        didAllowlist = allowlist,
                        cacheTtlSeconds = 3600
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        clock = fixedClock,
                        didResolverRegistry = mockRegistry
                    )

                // Populate cache with all 257 issuers
                dids.forEach { did ->
                    provider.getKeySetForIssuer(did)
                }

                // The first DID was evicted — requesting it again should trigger re-resolution
                val firstDid = dids.first()
                provider.getKeySetForIssuer(firstDid)

                // Registry should have been called at least 258 times (257 initial + 1 re-resolve after eviction)
                coVerify(atLeast = 258) { mockRegistry.resolve(any()) }
            }

        // DID-T8: matchesGlob unit tests
        @Test
        fun `matchesGlob handles wildcard and anchoring correctly`() {
            val provider = DefaultJwksKeySetProvider(VerifierConfig.Jwks(didAllowlist = listOf("x")))

            // Exact match (no wildcard)
            assertTrue(provider.matchesGlob("did:web:example.com", "did:web:example.com"))

            // Simple trailing wildcard
            assertTrue(provider.matchesGlob("did:web:example.com:agents:abc", "did:web:example.com:agents:*"))

            // Non-match with trailing wildcard
            assertTrue(!provider.matchesGlob("did:web:other.com:agents:abc", "did:web:example.com:agents:*"))

            // Multiple wildcards
            assertTrue(provider.matchesGlob("did:web:foo.example.com:bar:baz", "did:web:*:bar:*"))

            // Empty string at end matched by *
            assertTrue(provider.matchesGlob("did:web:example.com:agents:", "did:web:example.com:agents:*"))

            // Pattern is exactly "*" — matches anything
            assertTrue(provider.matchesGlob("did:web:anything.com", "*"))

            // Anchored — prefix-only glob should NOT match if suffix doesn't match
            assertTrue(!provider.matchesGlob("did:web:example.com:agents:abc:extra", "did:web:example.com:agents:abc"))
        }

        // DID-T9: allowlist precedence over pattern
        @Test
        fun `allowlist takes precedence over pattern for trust decision`() =
            runTest {
                val did = "did:web:special.example.com"
                val (doc, _) = buildDidDocument(did)
                val mockRegistry = mockk<DidResolverRegistry>()
                coEvery { mockRegistry.resolve(did) } returns doc

                // Both allowlist and pattern would match; allowlist takes priority
                val config =
                    VerifierConfig.Jwks(
                        didAllowlist = listOf(did),
                        didPattern = "did:web:special.*",
                        cacheTtlSeconds = 300
                    )
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        clock = makeFixedClock(),
                        didResolverRegistry = mockRegistry
                    )

                // Should resolve without exception (trusted via allowlist)
                val jwks = provider.getKeySetForIssuer(did)
                assertNotNull(jwks)

                // A DID not in allowlist but matching pattern is also trusted
                val patternDid = "did:web:special.other.example.com"
                val (patternDoc, _) = buildDidDocument(patternDid)
                coEvery { mockRegistry.resolve(patternDid) } returns patternDoc

                val config2 =
                    VerifierConfig.Jwks(
                        didAllowlist = listOf(did),
                        didPattern = "did:web:special.*",
                        cacheTtlSeconds = 300
                    )
                val provider2 =
                    DefaultJwksKeySetProvider(
                        config2,
                        clock = makeFixedClock(),
                        didResolverRegistry = mockRegistry
                    )
                val jwks2 = provider2.getKeySetForIssuer(patternDid)
                assertNotNull(jwks2)
            }
    }
}
