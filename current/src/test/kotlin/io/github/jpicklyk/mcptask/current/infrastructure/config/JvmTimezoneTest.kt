package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test-author suite for item dc7693e1 (AR-49): [JvmTimezone.enforceUtc] is the runtime backstop
 * pinning non-Docker JVM launches to UTC, mirroring the `-Duser.timezone=UTC` Dockerfile CMD flag.
 *
 * The test JVM itself is UTC-pinned (see `current/build.gradle.kts`), so every test here explicitly
 * sets a non-default `TimeZone.getDefault()` before calling [JvmTimezone.enforceUtc] and restores
 * the original default in a `finally`/`@AfterEach` — leaking a changed default would poison later
 * tests in the same JVM.
 *
 * Oracle: the item's frozen `task-scope` note (queue phase) — override + WARN for a non-UTC
 * default, no-op for one already UTC-equivalent (`UTC`, `Etc/UTC`, `GMT`, `Z`).
 */
class JvmTimezoneTest {
    private lateinit var originalDefault: TimeZone
    private val logger = LoggerFactory.getLogger(JvmTimezoneTest::class.java)

    @BeforeEach
    fun captureOriginal() {
        originalDefault = TimeZone.getDefault()
    }

    @AfterEach
    fun restoreOriginal() {
        TimeZone.setDefault(originalDefault)
    }

    // ---- S1: non-UTC default is overridden ----

    @Test
    fun `S1 non-UTC default timezone is overridden to UTC and returns true`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

        val overridden = JvmTimezone.enforceUtc(logger)

        assertTrue(overridden, "expected enforceUtc to report an override")
        assertEquals("UTC", TimeZone.getDefault().id)
    }

    // ---- S2: already-UTC default is left alone ----

    @Test
    fun `S2 default already UTC is left unchanged and returns false`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val overridden = JvmTimezone.enforceUtc(logger)

        assertFalse(overridden, "expected enforceUtc to report no override for an already-UTC default")
        assertEquals("UTC", TimeZone.getDefault().id)
    }

    @Test
    fun `S2 default already Etc UTC is treated as UTC-equivalent`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"))

        val overridden = JvmTimezone.enforceUtc(logger)

        assertFalse(overridden, "expected Etc/UTC to be treated as UTC-equivalent")
        assertEquals("Etc/UTC", TimeZone.getDefault().id)
    }

    @Test
    fun `S2 default already GMT is treated as UTC-equivalent`() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"))

        val overridden = JvmTimezone.enforceUtc(logger)

        assertFalse(overridden, "expected GMT to be treated as UTC-equivalent")
        assertEquals("GMT", TimeZone.getDefault().id)
    }

    // ---- Adversarial probe: a zero-offset-but-not-UTC-ID zone still gets overridden ----

    @Test
    fun `probe a non-UTC zone id with zero raw offset is still overridden`() {
        // Africa/Abidjan has a raw offset of 0 but is not one of the UTC-equivalent IDs -- the
        // implementation must compare by ID, not by offset, or this would be silently accepted.
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Abidjan"))

        val overridden = JvmTimezone.enforceUtc(logger)

        assertTrue(overridden, "a non-UTC-ID zone must be overridden even with a zero raw offset")
        assertEquals("UTC", TimeZone.getDefault().id)
    }
}
