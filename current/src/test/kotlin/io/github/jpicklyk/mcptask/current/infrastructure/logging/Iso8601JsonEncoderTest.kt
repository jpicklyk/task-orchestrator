package io.github.jpicklyk.mcptask.current.infrastructure.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct unit coverage of [Iso8601JsonEncoder]'s wire format — the JSON-shape half of AR-78 / item
 * b5081c9b. MDC-propagation coverage (whether the right fields land in [org.slf4j.MDC] in the
 * first place) lives in [io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapterMdcTest]
 * — this class only checks what the encoder does with whatever event it is handed.
 */
class Iso8601JsonEncoderTest {
    private val loggerContext = LoggerContext()

    private fun buildEvent(
        message: String = "hello",
        level: Level = Level.INFO,
        mdc: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
        timestampMillis: Long = 1_780_000_000_123L
    ): LoggingEvent {
        val logger = loggerContext.getLogger("test.logger") as Logger
        val event = LoggingEvent("fqcn", logger, level, message, throwable, null)
        event.timeStamp = timestampMillis
        event.threadName = Thread.currentThread().name
        // Always set explicitly (even when empty) rather than leaving it null: LoggingEvent's
        // getMDCPropertyMap() lazily falls back to the live MDC.getMDCAdapter() when the field is
        // unset, which NPEs in this isolated test LoggerContext (no live MDC adapter wired for it).
        event.setMDCPropertyMap(mdc)
        return event
    }

    private fun encodeToJson(event: LoggingEvent): JsonObject {
        val encoder = Iso8601JsonEncoder()
        encoder.context = loggerContext
        encoder.start()
        val bytes = encoder.encode(event)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.endsWith("\n"), "Each event must end in a newline: $text")
        assertEquals(1, text.trim().lines().size, "Exactly one line per event: $text")
        return Json.parseToJsonElement(text.trim()) as JsonObject
    }

    private val timestampPattern = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

    @Test
    fun `S1 - encodes one JSON line with an ISO-8601 UTC timestamp and no epoch or extra fields`() {
        val json = encodeToJson(buildEvent(timestampMillis = 1_780_000_000_123L))

        val timestamp = json["timestamp"]!!.jsonPrimitive.content
        assertEquals("2026-05-28T20:26:40.123Z", timestamp)
        assertTrue(timestamp.endsWith("Z"), "timestamp must be UTC (Z-suffixed): $timestamp")

        assertEquals("INFO", json["level"]!!.jsonPrimitive.content)
        assertEquals("test.logger", json["loggerName"]!!.jsonPrimitive.content)
        assertFalse(json["threadName"]!!.jsonPrimitive.content.isBlank())
        assertEquals("hello", json["formattedMessage"]!!.jsonPrimitive.content)

        assertFalse(json.containsKey("sequenceNumber"), "sequenceNumber must be disabled")
        assertFalse(json.containsKey("nanoseconds"), "nanoseconds must be disabled")
        assertFalse(json.containsKey("message"), "raw 'message' must be disabled (formattedMessage only)")
        assertFalse(json.containsKey("context"), "context must be disabled")
        assertFalse(json.containsKey("arguments"), "arguments must be disabled")
    }

    @Test
    fun `mdc fields are included and json-escaped`() {
        val json =
            encodeToJson(
                buildEvent(
                    mdc =
                        mapOf(
                            "tool" to "manage_items",
                            "requestId" to "abc-123"
                        )
                )
            )
        val mdcObj = json["mdc"] as? JsonObject
        assertTrue(mdcObj != null, "mdc object must be present")
        assertEquals("manage_items", mdcObj!!["tool"]?.jsonPrimitive?.content)
        assertEquals("abc-123", mdcObj["requestId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `S10 - special characters in the message and MDC still produce one parseable JSON line`() {
        val nasty = "quote\" backslash\\ newline\n null\u0000 tab\t"
        val json =
            encodeToJson(
                buildEvent(
                    message = nasty,
                    mdc = mapOf("actorId" to nasty)
                )
            )
        assertEquals(nasty, json["formattedMessage"]!!.jsonPrimitive.content)
        val mdcObj = json["mdc"] as JsonObject
        assertEquals(nasty, mdcObj["actorId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `throwable is included when present`() {
        val json = encodeToJson(buildEvent(throwable = IllegalStateException("boom")))
        assertTrue(json.containsKey("throwable"), "throwable field must be present when the event carries one")
    }

    /**
     * L10 (O6): fixed-precision timestamp formatting. Each case's oracle is the pattern itself
     * (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, UTC) — epoch 1000 is the exact case that used to drop the
     * fractional part entirely under `Instant.toString()` (`...01Z` instead of `...01.000Z`).
     */
    @Test
    fun `L10 - epoch 0 formats with three fractional digits`() {
        val timestamp = encodeToJson(buildEvent(timestampMillis = 0L))["timestamp"]!!.jsonPrimitive.content
        assertEquals("1970-01-01T00:00:00.000Z", timestamp)
        assertTrue(timestampPattern.matcher(timestamp).matches(), "timestamp must match the fixed pattern: $timestamp")
        assertEquals(24, timestamp.length)
    }

    @Test
    fun `L10 - epoch 1000ms (a whole second) still carries three fractional digits`() {
        val timestamp = encodeToJson(buildEvent(timestampMillis = 1000L))["timestamp"]!!.jsonPrimitive.content
        assertEquals("1970-01-01T00:00:01.000Z", timestamp)
        assertTrue(timestampPattern.matcher(timestamp).matches(), "timestamp must match the fixed pattern: $timestamp")
        assertEquals(24, timestamp.length)
    }

    @Test
    fun `L10 - epoch 5ms formats with three fractional digits`() {
        val timestamp = encodeToJson(buildEvent(timestampMillis = 5L))["timestamp"]!!.jsonPrimitive.content
        assertEquals("1970-01-01T00:00:00.005Z", timestamp)
        assertTrue(timestampPattern.matcher(timestamp).matches(), "timestamp must match the fixed pattern: $timestamp")
        assertEquals(24, timestamp.length)
    }

    @Test
    fun `L10 - a negative epoch (pre-1970) formats correctly`() {
        val timestamp = encodeToJson(buildEvent(timestampMillis = -1L))["timestamp"]!!.jsonPrimitive.content
        assertEquals("1969-12-31T23:59:59.999Z", timestamp)
        assertTrue(timestampPattern.matcher(timestamp).matches(), "timestamp must match the fixed pattern: $timestamp")
        assertEquals(24, timestamp.length)
    }

    // Clear any MDC the JVM-wide test process might have left behind from another test class.
    init {
        MDC.clear()
    }
}
