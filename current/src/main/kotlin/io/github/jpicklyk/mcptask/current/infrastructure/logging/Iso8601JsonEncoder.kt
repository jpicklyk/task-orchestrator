package io.github.jpicklyk.mcptask.current.infrastructure.logging

import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Subclass of logback-classic's stock [JsonEncoder] that swaps the epoch-millis `timestamp` field
 * for an ISO-8601 UTC string, and trims the payload down to the fields this server's JSON log
 * lines actually need.
 *
 * The stock [JsonEncoder] (logback-classic 1.5.32, verified by `javap` against the cached jar —
 * see the AR-78 task-scope note on item b5081c9b for the exact bytecode evidence) writes
 * `timestamp` as `event.timeStamp` (epoch millis) via `appenderMemberWithLongValue`, and has no
 * built-in option to reformat it. It does expose `protected void appendCustomFields(StringBuilder,
 * ILoggingEvent)` — a no-op hook called last, immediately before the closing `}` of the JSON
 * object it writes (confirmed in bytecode: the call sits right before the `}` and `\n` are
 * appended and the buffer is turned into UTF-8 bytes). Overriding that hook to append our own
 * `timestamp` field, while disabling the stock one via [setWithTimestamp], gives an ISO-8601
 * timestamp with zero risk of colliding with (or duplicating) the stock field.
 *
 * Enabled fields: `level`, `loggerName`, `threadName`, `formattedMessage`, `mdc`, `throwable`,
 * plus this class's own `timestamp`. Disabled: `sequenceNumber`, `nanoseconds`, `context`,
 * `message` (the raw, unformatted message — redundant with `formattedMessage`), `arguments`.
 *
 * One line per event, UTF-8, ending in `\n` — unchanged from the stock encoder's framing.
 *
 * The `timestamp` field always carries exactly 3 fractional-second digits, via a fixed-pattern
 * [DateTimeFormatter] rather than [Instant.toString], which drops the fractional part entirely
 * for whole-second instants (e.g. `1970-01-01T00:00:01Z` instead of
 * `1970-01-01T00:00:01.000Z`) — a variable-width field that breaks naive fixed-offset parsing
 * and length-based assumptions downstream. [DateTimeFormatter] instances are immutable and
 * thread-safe, so a single shared companion instance is safe to reuse across concurrent log
 * events.
 */
class Iso8601JsonEncoder : JsonEncoder() {
    companion object {
        private val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }

    init {
        // JsonEncoder exposes only setters (no getters) for these flags, so Kotlin cannot
        // synthesize `var` properties for them — call the setters directly.
        setWithSequenceNumber(false)
        setWithNanoseconds(false)
        setWithContext(false)
        setWithTimestamp(false)
        setWithMessage(false)
        setWithArguments(false)
        setWithFormattedMessage(true)
        setWithMDC(true)
        setWithThrowable(true)
        setWithLevel(true)
        setWithLoggerName(true)
        setWithThreadName(true)
    }

    /**
     * Appends the ISO-8601 UTC `timestamp` field. Runs after every other field this encoder
     * writes, so a leading comma is required — [appendCustomFields] is the LAST thing written
     * before the closing `}`, and nothing after it adds a separator on our behalf.
     */
    override fun appendCustomFields(
        sb: StringBuilder,
        event: ILoggingEvent
    ) {
        sb.append(',')
        appenderMember(sb, "timestamp", TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(event.timeStamp)))
    }
}
