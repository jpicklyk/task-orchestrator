package io.github.jpicklyk.mcptask.current.infrastructure.database

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [DatabaseConfig.resolveBusyTimeoutMs] — validates the env-var parsing
 * and boundary logic without requiring actual environment variable manipulation.
 */
class DatabaseConfigTest {
    // ------------------------------------------------------------------
    // Default behaviour (unset env var)
    // ------------------------------------------------------------------

    @Test
    fun `returns default 5000 when env var is null`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs(null)
        assertEquals(5000L, result)
    }

    // ------------------------------------------------------------------
    // Valid values
    // ------------------------------------------------------------------

    @Test
    fun `returns parsed value when env var is a valid Long`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("10000")
        assertEquals(10000L, result)
    }

    @Test
    fun `returns parsed value for recommended maximum 30000`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("30000")
        assertEquals(30000L, result)
    }

    @Test
    fun `accepts values above the hard-ceiling documentation threshold`() {
        // Hard ceiling (30 000 ms) is documentation-only; code must NOT reject values above it.
        val result = DatabaseConfig.resolveBusyTimeoutMs("60000")
        assertEquals(60000L, result)
    }

    @Test
    fun `returns exactly 100 when env var is exactly the floor value`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("100")
        assertEquals(100L, result)
    }

    // ------------------------------------------------------------------
    // Unparseable input → default
    // ------------------------------------------------------------------

    @Test
    fun `returns default when env var is not a valid Long`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("not-a-number")
        assertEquals(5000L, result)
    }

    @Test
    fun `returns default when env var is an empty string`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("")
        assertEquals(5000L, result)
    }

    @Test
    fun `returns default 5000 when env var is whitespace only`() {
        // NICE-N4: "   ".toLongOrNull() returns null, so whitespace must fall back to the default.
        val result = DatabaseConfig.resolveBusyTimeoutMs("   ")
        assertEquals(5000L, result)
    }

    @Test
    fun `returns default when env var is a decimal float string`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("5000.5")
        assertEquals(5000L, result)
    }

    // ------------------------------------------------------------------
    // Below-floor input → 100 ms sanity floor
    // ------------------------------------------------------------------

    @Test
    fun `applies 100 ms floor when env var is below 100`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("50")
        assertEquals(100L, result)
    }

    @Test
    fun `applies 100 ms floor for zero`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("0")
        assertEquals(100L, result)
    }

    @Test
    fun `applies 100 ms floor for negative value`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("-1")
        assertEquals(100L, result)
    }

    @Test
    fun `applies 100 ms floor for value of 99`() {
        val result = DatabaseConfig.resolveBusyTimeoutMs("99")
        assertEquals(100L, result)
    }
}
