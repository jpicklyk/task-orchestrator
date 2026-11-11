package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Independent tests (test-author seat, item f98690c9) for [AppConfig.mcpSessionIdleTimeout] and its environment variable
 * `MCP_SESSION_IDLE_TIMEOUT_MINUTES`: scenarios S4 (the default) and S7 (the accepted and refused shapes) of the frozen test-plan.
 *
 * Oracles (frozen diagnosis "Fix" and test-plan; none read from an implementation): unset means 120 minutes (2 hours); a value
 * is a positive whole number of minutes, surrounding whitespace and a leading `+` are tolerated (the shapes
 * `RetentionSettingsTest` pins for the sibling retention variables, kotlin-sdk 0.16.0 requires a positive timeout); a blank,
 * zero, negative, non-integer or Int-overflowing value fails startup as an [IllegalArgumentException] that names the variable
 * and shows the raw value, and it propagates out of [AppConfig.fromEnv]. Every expected number below is an input this test
 * chose, or the documented 2 h default.
 *
 * Negative-assertion conditions (what makes each fail without the feature): the rejection cases fail if the parser clamps or
 * defaults a bad value instead of throwing (no exception) or throws without naming the variable (message check); the accepted
 * cases fail if whitespace or `+` is refused.
 *
 * Forbidden-construct declaration: none.
 */
class McpSessionIdleTimeoutConfigTest {
    private fun fromEnv(value: String?) = AppConfig.fromEnv { name -> if (name == VAR) value else null }

    @Test
    fun `S4 nothing set yields the documented default of 120 minutes`() {
        assertEquals(120.minutes, AppConfig.fromEnv { null }.mcpSessionIdleTimeout)
        assertEquals(2.hours, AppConfig.DEFAULT_MCP_SESSION_IDLE_TIMEOUT)
        assertEquals(AppConfig.DEFAULT_MCP_SESSION_IDLE_TIMEOUT, AppConfig.fromEnv { null }.mcpSessionIdleTimeout)
    }

    @Test
    fun `S4 the data class carries the default in its parameter`() {
        assertEquals(2.hours, AppConfig.fromEnv { null }.copy().mcpSessionIdleTimeout)
        assertEquals(30.minutes, AppConfig.fromEnv { null }.copy(mcpSessionIdleTimeout = 30.minutes).mcpSessionIdleTimeout)
    }

    @Test
    fun `S7 a positive whole number of minutes is accepted, with surrounding whitespace and a leading plus`() {
        for (raw in listOf("45", " 45 ", "+45", "045")) {
            assertEquals(45.minutes, fromEnv(raw).mcpSessionIdleTimeout, "MCP_SESSION_IDLE_TIMEOUT_MINUTES=\"$raw\"")
        }
        assertEquals(1.minutes, fromEnv("1").mcpSessionIdleTimeout, "the smallest positive value")
        assertEquals(Int.MAX_VALUE.minutes, fromEnv(Int.MAX_VALUE.toString()).mcpSessionIdleTimeout, "the largest Int value")
    }

    @Test
    fun `S7 zero, negative, non-integer and blank values fail startup naming the variable and the value`() {
        for (raw in listOf("0", "-5", "abc", "", "  ", "1.5", "45m")) {
            val e =
                assertFailsWith<IllegalArgumentException>("MCP_SESSION_IDLE_TIMEOUT_MINUTES=\"$raw\" must fail startup") {
                    fromEnv(raw)
                }
            val message = assertNotNull(e.message, "the failure must explain itself")
            assertTrue(VAR in message, "the message must name the variable: $message")
            if (raw.isNotBlank()) assertTrue(raw.trim() in message, "the message must show the raw value \"$raw\": $message")
        }
    }

    @Test
    fun `S7 a value beyond the Int range fails startup like the sibling retention variables`() {
        val raw = "2147483648"
        val e = assertFailsWith<IllegalArgumentException>("MCP_SESSION_IDLE_TIMEOUT_MINUTES=\"$raw\" must fail startup") { fromEnv(raw) }
        val message = assertNotNull(e.message, "the failure must explain itself")
        assertTrue(VAR in message, "the message must name the variable: $message")
        assertTrue(raw in message, "the message must show the raw value \"$raw\": $message")
    }

    @Test
    fun `S7 one invalid idle timeout fails the whole parse even when no other variable is set`() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv { name -> if (name == VAR) "0" else null } }
    }

    private companion object {
        const val VAR = "MCP_SESSION_IDLE_TIMEOUT_MINUTES"
    }
}
