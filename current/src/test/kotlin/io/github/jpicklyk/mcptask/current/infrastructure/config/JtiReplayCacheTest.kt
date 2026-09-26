package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Independent unit coverage for item 3dcfcbab, Part 2's [JtiReplayCache] primitive - declared
 * (entirely new in 8534e8da) with these oracle-bearing facts read directly off the supplied
 * declarations (constants and key construction, not implementation prose):
 *  - Retention window: an admitted entry's key `(issuer, jti)` lives until `expiresAt + 60s`
 *    (`CLOCK_SKEW_SECONDS = 60L`) - i.e. exactly `exp + 60s`, matching the same skew constant used
 *    elsewhere in this codebase's JWT handling.
 *  - `maxEntries` default: `10_000`; at capacity, expired entries are purged first, and if still
 *    full the oldest-inserted entry is evicted.
 *  - Key composition: `"${issuer ?: ""}\u0000$jti"` - a null issuer and an empty-string issuer
 *    produce the SAME key.
 *  - `checkAndRecord` returns `true` on first sighting of a key, `false` on a replay.
 *
 * Uses an advancing [Clock] (mutable current instant, following this codebase's own
 * `JwksKeyCacheTest` pattern) rather than sleeps, per the test-author skill's fixed-Clock
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

    // -------------------------------------------------------------------------
    // Basic first-sighting / replay behavior
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // S19 - retention window: live until exp+60s, treated as fresh only after exp+61s
    // -------------------------------------------------------------------------

    @Test
    fun `S19a a replay attempt one second before exp plus 60s is still blocked`() {
        // The exact exp+60s instant itself is not pinned by the declarations (only the retention
        // formula expiresAt.plusSeconds(60) is declared, not whether the comparison at that exact
        // instant is inclusive or exclusive) -- probed one second inside the window instead, which
        // is unambiguous under either reading.
        val clock = AdvancingClock(baseInstant)
        val cache = JtiReplayCache(clock = clock)
        val expiresAt = baseInstant.plusSeconds(100)

        assertTrue(cache.checkAndRecord("iss", "jti-window", expiresAt))

        clock.current = expiresAt.plusSeconds(59)
        assertFalse(
            cache.checkAndRecord("iss", "jti-window", expiresAt),
            "an entry must still be live at exp+59s, one second inside the retention window"
        )
    }

    @Test
    fun `S19b once past exp plus 61s the entry is treated as expired and a new sighting is true`() {
        val clock = AdvancingClock(baseInstant)
        val cache = JtiReplayCache(clock = clock)
        val expiresAt = baseInstant.plusSeconds(100)

        assertTrue(cache.checkAndRecord("iss", "jti-window", expiresAt))

        clock.current = expiresAt.plusSeconds(61)
        assertTrue(
            cache.checkAndRecord("iss", "jti-window", expiresAt),
            "an entry past its exp+60s retention window must be treated as a fresh sighting"
        )
    }

    // -------------------------------------------------------------------------
    // Capacity eviction: maxEntries reached, no expired entries to purge -> oldest-inserted evicted
    // -------------------------------------------------------------------------

    @Test
    fun `at capacity with nothing expired the oldest-inserted entry is evicted to make room`() {
        val clock = AdvancingClock(baseInstant)
        val cache = JtiReplayCache(maxEntries = 2, clock = clock)
        val farExpiry = baseInstant.plusSeconds(10_000) // far enough that nothing purges on TTL

        assertTrue(cache.checkAndRecord("iss", "a", farExpiry), "a: first sighting")
        assertTrue(cache.checkAndRecord("iss", "b", farExpiry), "b: first sighting")
        // Cache is now at capacity (2). Recording "c" must evict the oldest entry ("a").
        assertTrue(cache.checkAndRecord("iss", "c", farExpiry), "c: first sighting, triggers eviction")

        // "b" was never evicted - it must still read as a replay. Checked BEFORE re-probing "a":
        // re-recording "a" is itself an insert that would put the cache back over capacity and
        // trigger a SECOND eviction (of "b", the new oldest), which would contaminate this
        // assertion if checked afterward.
        assertFalse(cache.checkAndRecord("iss", "b", farExpiry), "b: must still be recognized as a replay")
        // "a" was evicted - a fresh presentation is a new sighting (true), not a replay.
        assertTrue(cache.checkAndRecord("iss", "a", farExpiry), "a: evicted, must read as a fresh sighting")
    }

    // -------------------------------------------------------------------------
    // Key composition: null issuer and empty-string issuer collide (declared key construction)
    // -------------------------------------------------------------------------

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
