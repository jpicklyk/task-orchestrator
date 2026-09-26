package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Independent unit coverage for item 3dcfcbab, Part 2 JtiReplayCache primitive - declared
 * (entirely new in 8534e8da) with these oracle-bearing facts read directly off the supplied
 * declarations (constants and key construction, not implementation prose):
 *  - Retention window: the key (issuer, jti) of an admitted entry lives until expiresAt + 60s
 *    (CLOCK_SKEW_SECONDS = 60L) - matching the same skew constant used elsewhere in this
 *    codebase JWT handling.
 *  - maxEntries default: 10_000; at capacity, expired entries are purged first, and if still
 *    full the oldest-inserted entry is evicted.
 *  - Key composition: string(issuer or empty) + NUL + jti - a null issuer and an empty-string
 *    issuer produce the SAME key.
 *  - checkAndRecord returns true on first sighting of a key, false on a replay.
 *
 * Uses an advancing Clock (mutable current instant, following the pattern used by this
 * codebase JwksKeyCacheTest) rather than sleeps, per the test-author skill fixed-Clock
 * requirement.
 */
class JtiReplayCacheTest {
    private val baseInstant: Instant = Instant.parse("2024-01-01T00:00:00Z")

    private class AdvancingClock(
        var current: Instant
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }

    @Test
    fun `first sighting of a key returns true, immediate replay returns false`() {
        val cache = JtiReplayCache(clock = Clock.fixed(baseInstant, ZoneOffset.UTC))
        val expiresAt = baseInstant.plusSeconds(300)

        assertTrue(cache.checkAndRecord("https://issuer.example", "jti-1", expiresAt), "first sighting must be true")
        assertFalse(cache.checkAndRecord("https://issuer.example", "jti-1", expiresAt), "replay must be false")
    }

    @Test
    fun `distinct jti values under the same issuer are independent`() {
        val cache = JtiReplayCache(clock = Clock.fixed(baseInstant, ZoneOffset.UTC))
        val expiresAt = baseInstant.plusSeconds(300)

        assertTrue(cache.checkAndRecord("https://issuer.example", "jti-a", expiresAt))
        assertTrue(cache.checkAndRecord("https://issuer.example", "jti-b", expiresAt))
    }

    @Test
    fun `S19 a replay attempt at exactly exp plus 60s is still blocked`() {
        val clock = AdvancingClock(baseInstant)
        val cache = JtiReplayCache(clock = clock)
        val expiresAt = baseInstant.plusSeconds(100)

        assertTrue(cache.checkAndRecord("iss", "jti-boundary-60s", expiresAt))

        clock.current = expiresAt.plusSeconds(60)
        assertFalse(
            cache.checkAndRecord("iss", "jti-boundary-60s", expiresAt),
            "an entry must still be live at exactly exp+60s, the same instant through which the " +
                "verifier itself still accepts the token"
        )
    }

    @Test
    fun `S19 a replay attempt one millisecond past exp plus 60s is a fresh sighting`() {
        val clock = AdvancingClock(baseInstant)
        val cache = JtiReplayCache(clock = clock)
        val expiresAt = baseInstant.plusSeconds(100)

        assertTrue(cache.checkAndRecord("iss", "jti-boundary-60s-1ms", expiresAt))

        clock.current = expiresAt.plusSeconds(60).plusMillis(1)
        assertTrue(
            cache.checkAndRecord("iss", "jti-boundary-60s-1ms", expiresAt),
            "one millisecond past exp+60s the verifier would no longer accept the token, so the " +
                "entry must already read as expired"
        )
    }

    @Test
    fun `S19 a replay attempt 999 milliseconds past exp plus 60s is a fresh sighting`() {
        val clock = AdvancingClock(baseInstant)
        val cache = JtiReplayCache(clock = clock)
        val expiresAt = baseInstant.plusSeconds(100)

        assertTrue(cache.checkAndRecord("iss", "jti-boundary-60s-999ms", expiresAt))

        clock.current = expiresAt.plusSeconds(60).plusMillis(999)
        assertTrue(
            cache.checkAndRecord("iss", "jti-boundary-60s-999ms", expiresAt),
            "999ms past exp+60s the entry must already read as expired, not only once a full " +
                "additional second (exp+61s) has elapsed"
        )
    }

    @Test
    fun `S19 a replay attempt at exp plus 61s is a fresh sighting`() {
        val clock = AdvancingClock(baseInstant)
        val cache = JtiReplayCache(clock = clock)
        val expiresAt = baseInstant.plusSeconds(100)

        assertTrue(cache.checkAndRecord("iss", "jti-boundary-61s", expiresAt))

        clock.current = expiresAt.plusSeconds(61)
        assertTrue(
            cache.checkAndRecord("iss", "jti-boundary-61s", expiresAt),
            "well past the exp+60s retention window, a new presentation of the jti must be " +
                "treated as a fresh sighting"
        )
    }

    @Test
    fun `at capacity with nothing expired the oldest-inserted entry is evicted to make room`() {
        val clock = AdvancingClock(baseInstant)
        val cache = JtiReplayCache(maxEntries = 2, clock = clock)
        val farExpiry = baseInstant.plusSeconds(10000)

        assertTrue(cache.checkAndRecord("iss", "a", farExpiry), "a: first sighting")
        assertTrue(cache.checkAndRecord("iss", "b", farExpiry), "b: first sighting")
        assertTrue(cache.checkAndRecord("iss", "c", farExpiry), "c: first sighting, triggers eviction")

        assertFalse(cache.checkAndRecord("iss", "b", farExpiry), "b: must still be recognized as a replay")
        assertTrue(cache.checkAndRecord("iss", "a", farExpiry), "a: evicted, must read as a fresh sighting")
    }

    @Test
    fun `a null issuer and an empty-string issuer compose to the same cache key`() {
        val cache = JtiReplayCache(clock = Clock.fixed(baseInstant, ZoneOffset.UTC))
        val expiresAt = baseInstant.plusSeconds(300)

        assertTrue(cache.checkAndRecord(null, "shared-jti", expiresAt), "null-issuer sighting must be first-seen")
        assertFalse(
            cache.checkAndRecord("", "shared-jti", expiresAt),
            "empty-string issuer must collide with a null issuer under the same jti (same composite key)"
        )
    }

    @Test
    fun `distinct non-null issuers with the same jti do not collide`() {
        val cache = JtiReplayCache(clock = Clock.fixed(baseInstant, ZoneOffset.UTC))
        val expiresAt = baseInstant.plusSeconds(300)

        assertTrue(cache.checkAndRecord("https://issuer-x.example", "shared-jti", expiresAt))
        assertTrue(cache.checkAndRecord("https://issuer-y.example", "shared-jti", expiresAt))
    }
}
