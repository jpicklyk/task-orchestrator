package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
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
}
