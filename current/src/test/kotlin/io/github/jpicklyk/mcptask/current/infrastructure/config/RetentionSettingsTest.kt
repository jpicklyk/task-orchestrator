package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 0da29ea1) for [RetentionSettings] and its [AppConfig.retention] wiring: scenario
 * S10 of the frozen test-plan.
 *
 * Oracles (frozen task-scope, "Env (V7)" and decision D3; none read from an implementation): defaults are
 * RETENTION_CALL_LOG_DAYS=90, RETENTION_EVENTS_DAYS=180, RETENTION_AUDIT_EVENTS_DAYS=0, RETENTION_LEASE_HISTORY_DAYS=365; 0
 * means forever; an invalid value (non-integer, negative, blank) is a startup failure, an IllegalArgumentException that names
 * the variable and the raw value and propagates out of AppConfig.fromEnv; values are trimmed; "+90" and "090" parse as 90.
 * Every expected number below is an input this test chose.
 *
 * Forbidden-construct declaration: none.
 */
class RetentionSettingsTest {
    private fun parse(vararg vars: Pair<String, String>): RetentionSettings {
        val env = vars.toMap()
        return RetentionSettings.parse { name -> env[name] }
    }

    @Test
    fun `S10 nothing set yields the documented defaults 90, 180, 0 and 365`() {
        val s = parse()
        assertEquals(90, s.callLogDays)
        assertEquals(180, s.eventsDays)
        assertEquals(0, s.auditEventsDays)
        assertEquals(365, s.leaseHistoryDays)
        assertEquals(RetentionSettings(90, 180, 0, 365), RetentionSettings.DEFAULT)
        assertEquals(RetentionSettings.DEFAULT, s)
    }

    @Test
    fun `S10 the variable names are the documented RETENTION_ names`() {
        assertEquals("RETENTION_CALL_LOG_DAYS", RetentionSettings.CALL_LOG_DAYS_VAR)
        assertEquals("RETENTION_EVENTS_DAYS", RetentionSettings.EVENTS_DAYS_VAR)
        assertEquals("RETENTION_AUDIT_EVENTS_DAYS", RetentionSettings.AUDIT_EVENTS_DAYS_VAR)
        assertEquals("RETENTION_LEASE_HISTORY_DAYS", RetentionSettings.LEASE_HISTORY_DAYS_VAR)
    }

    @Test
    fun `S10 each variable sets exactly its own field and leaves the others at their defaults`() {
        assertEquals(RetentionSettings(7, 180, 0, 365), parse("RETENTION_CALL_LOG_DAYS" to "7"))
        assertEquals(RetentionSettings(90, 7, 0, 365), parse("RETENTION_EVENTS_DAYS" to "7"))
        assertEquals(RetentionSettings(90, 180, 7, 365), parse("RETENTION_AUDIT_EVENTS_DAYS" to "7"))
        assertEquals(RetentionSettings(90, 180, 0, 7), parse("RETENTION_LEASE_HISTORY_DAYS" to "7"))
    }

    @Test
    fun `S10 zero means forever and is accepted for every window`() {
        val s =
            parse(
                "RETENTION_CALL_LOG_DAYS" to "0",
                "RETENTION_EVENTS_DAYS" to "0",
                "RETENTION_AUDIT_EVENTS_DAYS" to "0",
                "RETENTION_LEASE_HISTORY_DAYS" to "0"
            )
        assertEquals(RetentionSettings(0, 0, 0, 0), s)
    }

    @Test
    fun `S10 a value is trimmed before parsing`() {
        assertEquals(7, parse("RETENTION_EVENTS_DAYS" to " 7 ").eventsDays)
        assertEquals(7, parse("RETENTION_EVENTS_DAYS" to "\t7\n").eventsDays)
    }

    @Test
    fun `S10 probe - a plus sign and leading zeros are accepted as the same integer`() {
        assertEquals(90, parse("RETENTION_CALL_LOG_DAYS" to "+90").callLogDays)
        assertEquals(90, parse("RETENTION_CALL_LOG_DAYS" to "090").callLogDays)
    }

    @Test
    fun `S10 probe - the largest int is accepted`() {
        assertEquals(Int.MAX_VALUE, parse("RETENTION_EVENTS_DAYS" to Int.MAX_VALUE.toString()).eventsDays)
    }

    private fun assertRejected(
        variable: String,
        raw: String
    ) {
        val e = assertFailsWith<IllegalArgumentException>("$variable=\"$raw\" must fail startup") { parse(variable to raw) }
        val message = assertNotNull(e.message, "the failure must explain itself")
        assertTrue(variable in message, "the message must name the variable $variable: $message")
        if (raw.isNotBlank()) assertTrue(raw.trim() in message, "the message must show the raw value \"$raw\": $message")
    }

    @Test
    fun `S10 every invalid shape is rejected for every variable, naming the variable and the value`() {
        val variables =
            listOf(
                "RETENTION_CALL_LOG_DAYS",
                "RETENTION_EVENTS_DAYS",
                "RETENTION_AUDIT_EVENTS_DAYS",
                "RETENTION_LEASE_HISTORY_DAYS"
            )
        for (v in variables) {
            for (raw in listOf("-1", "abc", "1.5", "", "   ", "9x", "1e3", "2147483648", "--5")) {
                assertRejected(v, raw)
            }
        }
    }

    @Test
    fun `S10 one invalid variable fails the parse even when the others are valid`() {
        assertFailsWith<IllegalArgumentException> {
            parse("RETENTION_CALL_LOG_DAYS" to "30", "RETENTION_EVENTS_DAYS" to "60", "RETENTION_LEASE_HISTORY_DAYS" to "oops")
        }
    }

    @Test
    fun `S10 the data class itself refuses a negative window`() {
        assertFailsWith<IllegalArgumentException> { RetentionSettings(-1, 180, 0, 365) }
        assertFailsWith<IllegalArgumentException> { RetentionSettings(90, -1, 0, 365) }
        assertFailsWith<IllegalArgumentException> { RetentionSettings(90, 180, -1, 365) }
        assertFailsWith<IllegalArgumentException> { RetentionSettings(90, 180, 0, -1) }
    }

    // ---- AppConfig wiring

    @Test
    fun `S10 AppConfig fromEnv with nothing set carries the default retention`() {
        assertEquals(RetentionSettings.DEFAULT, AppConfig.fromEnv { null }.retention)
    }

    @Test
    fun `S10 AppConfig fromEnv reads the retention variables once into AppConfig retention`() {
        val env =
            mapOf(
                "RETENTION_CALL_LOG_DAYS" to "11",
                "RETENTION_EVENTS_DAYS" to "22",
                "RETENTION_AUDIT_EVENTS_DAYS" to "33",
                "RETENTION_LEASE_HISTORY_DAYS" to "44"
            )
        assertEquals(RetentionSettings(11, 22, 33, 44), AppConfig.fromEnv { env[it] }.retention)
    }

    @Test
    fun `S10 AppConfig fromEnv rethrows an invalid retention value as a startup failure naming the variable`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromEnv { name -> if (name == "RETENTION_EVENTS_DAYS") "abc" else null }
            }
        val message = assertNotNull(e.message)
        assertTrue("RETENTION_EVENTS_DAYS" in message, message)
        assertTrue("abc" in message, message)
    }
}
