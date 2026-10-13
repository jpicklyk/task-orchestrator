package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.DidDocument
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.DidResolverRegistry
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.VerificationMethod
import io.github.jpicklyk.mcptask.current.test.publicEgressGuard
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.SecureRandom
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-author coverage for item 385444c2 (R8), key-provider half: refresh backoff (AR-32), bounded
 * staleness (`max_stale_seconds`), and the rate-limited refresh on an unknown kid, for both the
 * static JWKS path and the per-issuer DID path of [DefaultJwksKeySetProvider].
 *
 * Oracles (frozen in the item's task-scope s2 and test-plan at queue phase): D3 = "retry = 15 s
 * doubling per consecutive failure, capped at 300 s, reset on success; the entry is served stale
 * when age <= maxStaleSeconds, else KeySetUnavailableException; ageSeconds always uses the real
 * fetchedAt"; D6 = "at most one forced refresh per 30 s per provider; returns the refreshed result
 * when the fetch succeeds, null otherwise; subject to the same backoff state".
 *
 * Retry windows are probed one second inside and one second outside the window rather than on the
 * boundary, so the tests do not depend on whether the comparison is inclusive.
 */
class KeySetBackoffTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private const val URL = "https://keys.bo.example/.well-known/jwks.json"
        private val T0: Instant = Instant.parse("2026-10-13T12:00:00Z")
        private val rsaKey = RSAKeyGenerator(2048).keyID("bo-key").generate()
        private val newKey = RSAKeyGenerator(2048).keyID("bo-new").generate()
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

        @Volatile
        var delayMs = 0L

        val engine =
            MockEngine { _ ->
                fetches.incrementAndGet()
                if (delayMs > 0) delay(delayMs)
                if (failing) {
                    respond("Service Unavailable", HttpStatusCode.ServiceUnavailable, headersOf("Content-Type", "text/plain"))
                } else {
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            }
    }

    private fun jwksBody(vararg extra: com.nimbusds.jose.jwk.JWK): String =
        JWKSet(
            listOf(rsaKey.toPublicJWK()) + extra.map { it.toPublicJWK() }
        ).toString()

    private fun at(seconds: Long): Instant = T0.plusSeconds(seconds)

    private fun staticProvider(
        up: Upstream,
        clock: TestClock,
        ttl: Long = 60,
        maxStale: Long = 3600,
        staleOnError: Boolean = true,
    ): DefaultJwksKeySetProvider {
        val config = VerifierConfig.Jwks(jwksUri = URL, cacheTtlSeconds = ttl, staleOnError = staleOnError, maxStaleSeconds = maxStale)
        return DefaultJwksKeySetProvider(
            config.toKeySource(),
            Path.of("."),
            clock = clock,
            egressGuard = publicEgressGuard(),
            httpClientEngineForTest = up.engine,
        )
    }

    /** Runs [block] and returns the thrown exception, or null when it returned normally. */
    private suspend fun thrownBy(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

    // -------------------------------------------------------------------------
    // S12 - the bug fix: a failed refresh must not be retried on every call
    // -------------------------------------------------------------------------

    @Test
    fun `S12 a failed refresh backs off 15s doubling to a 300s cap and serves stale with the real age`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                val first = p.getKeySet()
                assertFalse(first.cacheState.fromStaleCache)
                assertEquals(1, up.fetches.get())

                up.failing = true
                var t = 120L
                clock.now = at(t)
                var result = p.getKeySet()
                assertTrue(result.cacheState.fromStaleCache, "the failed refresh serves the cached keys as stale")
                assertEquals(120L, result.cacheState.ageSeconds)
                assertEquals(2, up.fetches.get(), "exactly one refresh attempt at the first call past the TTL")

                var expectedFetches = 2
                // consecutive-failure retry windows: 15, 30, 60, 120, 240, then the 300 cap twice
                for (retry in listOf(15L, 30L, 60L, 120L, 240L, 300L, 300L)) {
                    clock.now = at(t + retry - 1)
                    repeat(3) { result = p.getKeySet() }
                    assertEquals(expectedFetches, up.fetches.get(), "no refetch one second inside the $retry s window")
                    assertTrue(result.cacheState.fromStaleCache)
                    assertEquals(t + retry - 1, result.cacheState.ageSeconds, "age is measured from the real fetch time")

                    clock.now = at(t + retry + 1)
                    result = p.getKeySet()
                    expectedFetches++
                    assertEquals(expectedFetches, up.fetches.get(), "exactly one refetch once the $retry s window has passed")
                    assertTrue(result.cacheState.fromStaleCache)
                    assertEquals(t + retry + 1, result.cacheState.ageSeconds)
                    t = t + retry + 1
                }
            } finally {
                p.close()
            }
        }

    @Test
    fun `S12 fifty concurrent callers during an outage cause a single refresh attempt`() =
        runBlocking(Dispatchers.Default) {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                up.failing = true
                up.delayMs = 50
                clock.now = at(120)

                val results = (1..50).map { async { p.getKeySet() } }.awaitAll()

                assertEquals(2, up.fetches.get(), "one priming fetch plus exactly one failed refresh")
                assertTrue(results.all { it.cacheState.fromStaleCache }, "every caller is served the stale keys")
            } finally {
                p.close()
            }
        }

    // -------------------------------------------------------------------------
    // S13 - success resets the backoff
    // -------------------------------------------------------------------------

    @Test
    fun `S13 a successful refresh resets the retry delay to 15s`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                up.failing = true
                clock.now = at(120) // failure 1 -> next window 15 s
                p.getKeySet()
                clock.now = at(136) // failure 2 -> next window 30 s
                p.getKeySet()
                assertEquals(3, up.fetches.get())

                up.failing = false
                clock.now = at(167) // outside the 30 s window: recovers
                val recovered = p.getKeySet()
                assertFalse(recovered.cacheState.fromStaleCache, "a successful refresh is fresh, not stale")
                assertEquals(4, up.fetches.get())

                clock.now = at(167 + 59)
                p.getKeySet()
                assertEquals(4, up.fetches.get(), "fresh again for the 60 s TTL")

                up.failing = true
                clock.now = at(167 + 61) // TTL elapsed: failure 1 after the reset
                p.getKeySet()
                assertEquals(5, up.fetches.get())

                clock.now = at(167 + 61 + 14)
                p.getKeySet()
                assertEquals(5, up.fetches.get(), "inside the reset 15 s window")

                // Without the reset the window would be 60 s (third consecutive failure).
                clock.now = at(167 + 61 + 16)
                p.getKeySet()
                assertEquals(6, up.fetches.get(), "the delay restarted at 15 s after the success")
            } finally {
                p.close()
            }
        }

    // -------------------------------------------------------------------------
    // S14 - bounded staleness
    // -------------------------------------------------------------------------

    @Test
    fun `S14 stale keys are served up to max_stale_seconds inclusive and refused one second later`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                up.failing = true

                clock.now = at(3600)
                val atLimit = p.getKeySet()
                assertTrue(atLimit.cacheState.fromStaleCache)
                assertEquals(3600L, atLimit.cacheState.ageSeconds)

                clock.now = at(3601)
                val ex = thrownBy { p.getKeySet() }
                assertTrue(ex is KeySetUnavailableException, "expected KeySetUnavailableException but was $ex")
            } finally {
                p.close()
            }
        }

    @Test
    fun `S14 a refresh that fails when the entry is already past max_stale throws and recovery is possible`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                up.failing = true

                clock.now = at(3601)
                val ex = thrownBy { p.getKeySet() }
                assertTrue(ex is KeySetUnavailableException, "expected KeySetUnavailableException but was $ex")

                up.failing = false
                clock.now = at(3601 + 20)
                val recovered = p.getKeySet()
                assertFalse(recovered.cacheState.fromStaleCache)
                assertEquals(1, recovered.keys.keys.size)
            } finally {
                p.close()
            }
        }

    @Test
    fun `S14 the limit is the configured max_stale_seconds not a constant`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock, ttl = 60, maxStale = 600)
            try {
                p.getKeySet()
                up.failing = true

                clock.now = at(600)
                assertTrue(p.getKeySet().cacheState.fromStaleCache)

                clock.now = at(601)
                val ex = thrownBy { p.getKeySet() }
                assertTrue(ex is KeySetUnavailableException, "expected KeySetUnavailableException but was $ex")
            } finally {
                p.close()
            }
        }

    // -------------------------------------------------------------------------
    // S15 - stale_on_error = false
    // -------------------------------------------------------------------------

    @Test
    fun `S15 with stale_on_error disabled the first failed refresh throws instead of serving stale`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock, staleOnError = false)
            try {
                p.getKeySet()
                up.failing = true
                clock.now = at(120)

                val ex = thrownBy { p.getKeySet() }

                assertNotNull(ex, "a failed refresh must surface as an exception when stale_on_error is false")
            } finally {
                p.close()
            }
        }

    @Test
    fun `S15 a failed first fetch with no cached entry throws even with stale_on_error enabled`() =
        runTest {
            val up = Upstream(jwksBody())
            up.failing = true
            val p = staticProvider(up, TestClock(T0))
            try {
                val ex = thrownBy { p.getKeySet() }
                assertNotNull(ex)
            } finally {
                p.close()
            }
        }

    // -------------------------------------------------------------------------
    // S16 - rate-limited refresh on an unknown kid
    // -------------------------------------------------------------------------

    @Test
    fun `S16 forced refreshes are limited to one per 30 seconds and update the cache`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                assertEquals(1, up.fetches.get())

                up.body = jwksBody(newKey)
                clock.now = at(10)
                val refreshed = p.refreshForUnknownKid("bo-new", null)
                assertNotNull(refreshed, "a forced refresh is allowed even though the cache is fresh")
                assertNotNull(refreshed!!.keys.getKeyByKeyId("bo-new"))
                assertEquals(2, up.fetches.get())

                clock.now = at(39) // 29 s after the first forced refresh
                assertNull(p.refreshForUnknownKid("bo-new", null), "rate-limited inside 30 s")
                assertEquals(2, up.fetches.get(), "a rate-limited call must not touch the network")

                clock.now = at(40) // 30 s after
                assertNotNull(p.refreshForUnknownKid("bo-new", null))
                assertEquals(3, up.fetches.get())

                clock.now = at(45)
                val cached = p.getKeySet()
                assertEquals(3, up.fetches.get(), "the forced refresh replaced the cache entry")
                assertNotNull(cached.keys.getKeyByKeyId("bo-new"))
                assertFalse(cached.cacheState.fromStaleCache)
            } finally {
                p.close()
            }
        }

    @Test
    fun `S16 a forced refresh that fails returns null and leaves the cached keys usable`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                up.failing = true
                clock.now = at(10)

                assertNull(p.refreshForUnknownKid("bo-new", null))

                val cached = p.getKeySet() // still inside the TTL
                assertNotNull(cached.keys.getKeyByKeyId("bo-key"))
            } finally {
                p.close()
            }
        }

    @Test
    fun `S16 a forced refresh is subject to the backoff after a failed scheduled refresh`() =
        runTest {
            val up = Upstream(jwksBody())
            val clock = TestClock(T0)
            val p = staticProvider(up, clock)
            try {
                p.getKeySet()
                up.failing = true
                clock.now = at(120)
                p.getKeySet() // failed refresh: backoff window of 15 s starts
                assertEquals(2, up.fetches.get())

                clock.now = at(125)
                assertNull(p.refreshForUnknownKid("bo-new", null))
                assertEquals(2, up.fetches.get(), "no network call while the backoff window is open")
            } finally {
                p.close()
            }
        }

    // -------------------------------------------------------------------------
    // S17 - the per-issuer DID path follows the same rules
    // -------------------------------------------------------------------------

    private fun didDocument(did: String): DidDocument {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pub = gen.generateKeyPair().public as Ed25519PublicKeyParameters
        val edKey = OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(pub.encoded)).keyID("key-1").build()
        val jwk =
            JsonObject(
                mapOf(
                    "kty" to JsonPrimitive("OKP"),
                    "crv" to JsonPrimitive("Ed25519"),
                    "x" to JsonPrimitive(edKey.x.toString()),
                    "kid" to JsonPrimitive("key-1"),
                ),
            )
        val vm = VerificationMethod(id = "$did#key-1", type = "JsonWebKey2020", controller = did, publicKeyJwk = jwk)
        return DidDocument(id = did, verificationMethods = listOf(vm), assertionMethod = listOf("$did#key-1"))
    }

    private class DidWorld(
        didA: String,
        didB: String,
        docA: DidDocument,
        docB: DidDocument,
    ) {
        val callsA = AtomicInteger(0)
        val callsB = AtomicInteger(0)
        val failingA = AtomicBoolean(false)
        val registry = mockk<DidResolverRegistry>(relaxed = true)

        init {
            coEvery { registry.resolve(didA) } coAnswers {
                callsA.incrementAndGet()
                if (failingA.get()) throw java.io.IOException("did host a is down")
                docA
            }
            coEvery { registry.resolve(didB) } coAnswers {
                callsB.incrementAndGet()
                docB
            }
        }
    }

    private val didA = "did:web:a.bo.example"
    private val didB = "did:web:b.bo.example"

    private fun didProvider(
        world: DidWorld,
        clock: TestClock,
    ): DefaultJwksKeySetProvider {
        val config = VerifierConfig.Jwks(didAllowlist = listOf(didA, didB), cacheTtlSeconds = 60)
        return DefaultJwksKeySetProvider(
            config.toKeySource(),
            Path.of("."),
            clock = clock,
            didResolverRegistry = world.registry,
            egressGuard = publicEgressGuard(),
        )
    }

    @Test
    fun `S17 the DID path backs off per issuer and does not refetch on every call`() =
        runTest {
            val world = DidWorld(didA, didB, didDocument(didA), didDocument(didB))
            val clock = TestClock(T0)
            val p = didProvider(world, clock)
            try {
                p.getKeySetForIssuer(didA)
                assertEquals(1, world.callsA.get())

                world.failingA.set(true)
                clock.now = at(120)
                val stale = p.getKeySetForIssuer(didA)
                assertTrue(stale.cacheState.fromStaleCache)
                assertEquals(120L, stale.cacheState.ageSeconds)
                assertEquals(2, world.callsA.get())

                clock.now = at(134) // inside the 15 s window
                repeat(3) { p.getKeySetForIssuer(didA) }
                assertEquals(2, world.callsA.get())

                clock.now = at(136) // past it: one more attempt, window becomes 30 s
                p.getKeySetForIssuer(didA)
                assertEquals(3, world.callsA.get())

                clock.now = at(136 + 29)
                p.getKeySetForIssuer(didA)
                assertEquals(3, world.callsA.get(), "the window doubled to 30 s")

                clock.now = at(136 + 31)
                p.getKeySetForIssuer(didA)
                assertEquals(4, world.callsA.get())
            } finally {
                p.close()
            }
        }

    @Test
    fun `S17 one issuer's outage does not put another issuer into backoff`() =
        runTest {
            val world = DidWorld(didA, didB, didDocument(didA), didDocument(didB))
            val clock = TestClock(T0)
            val p = didProvider(world, clock)
            try {
                p.getKeySetForIssuer(didA)
                p.getKeySetForIssuer(didB)
                world.failingA.set(true)

                clock.now = at(120)
                p.getKeySetForIssuer(didA) // A enters backoff
                val b = p.getKeySetForIssuer(didB) // B is expired and healthy: refetched

                assertEquals(2, world.callsB.get())
                assertFalse(b.cacheState.fromStaleCache)
            } finally {
                p.close()
            }
        }

    @Test
    fun `S17 the DID path refuses stale keys past max_stale_seconds`() =
        runTest {
            val world = DidWorld(didA, didB, didDocument(didA), didDocument(didB))
            val clock = TestClock(T0)
            val p = didProvider(world, clock)
            try {
                p.getKeySetForIssuer(didA)
                world.failingA.set(true)

                clock.now = at(3600)
                val atLimit = p.getKeySetForIssuer(didA)
                assertTrue(atLimit.cacheState.fromStaleCache)
                assertEquals(3600L, atLimit.cacheState.ageSeconds)

                clock.now = at(3601)
                val ex = thrownBy { p.getKeySetForIssuer(didA) }
                assertTrue(ex is KeySetUnavailableException, "expected KeySetUnavailableException but was $ex")
            } finally {
                p.close()
            }
        }

    @Test
    fun `S17 the DID path forces one refresh per 30 seconds for an unknown kid`() =
        runTest {
            val world = DidWorld(didA, didB, didDocument(didA), didDocument(didB))
            val clock = TestClock(T0)
            val p = didProvider(world, clock)
            try {
                p.getKeySetForIssuer(didA)
                assertEquals(1, world.callsA.get())

                clock.now = at(10)
                assertNotNull(p.refreshForUnknownKid("rotated", didA))
                assertEquals(2, world.callsA.get())

                clock.now = at(39)
                assertNull(p.refreshForUnknownKid("rotated", didA))
                assertEquals(2, world.callsA.get())

                clock.now = at(40)
                assertNotNull(p.refreshForUnknownKid("rotated", didA))
                assertEquals(3, world.callsA.get())
            } finally {
                p.close()
            }
        }
}
