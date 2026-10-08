package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/**
 * The one canonical persisted timestamp text: `yyyy-MM-dd HH:mm:ss.SSS` in UTC (fixed 23 chars,
 * millisecond truncation). Formatting and parsing never consult the JVM default timezone, so
 * stored values and SQL comparisons are correct regardless of `user.timezone`.
 *
 * [parse] also accepts the legacy shapes older databases may still hold (no fraction, `T`
 * separator, `Z` / `+-HH:MM` offsets converted to UTC, more than three fraction digits truncated).
 */
object UtcTimestamp {
    private val canonical: DateTimeFormatter =
        DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .toFormatter()
            .withZone(ZoneOffset.UTC)

    private val legacy: DateTimeFormatter =
        DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .appendLiteral(' ')
            .appendPattern("HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter()

    private val offsetSuffix = Regex("[+-]\\d{2}:\\d{2}$")

    /** Canonical text for [instant] (UTC, milliseconds, truncated). */
    fun format(instant: Instant): String = canonical.format(instant.truncatedTo(ChronoUnit.MILLIS))

    /** Parses canonical or legacy text to an [Instant]; throws [IllegalArgumentException] if unparseable. */
    fun parse(text: String): Instant {
        val s = text.trim()
        val timePart = s.drop(11)
        val hasOffset = timePart.endsWith("Z") || offsetSuffix.containsMatchIn(timePart)
        return try {
            val normalized = s.replace('T', ' ')
            if (hasOffset) {
                OffsetDateTime
                    .parse(s.replace(' ', 'T'), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant()
                    .truncatedTo(ChronoUnit.MILLIS)
            } else {
                LocalDateTime
                    .parse(normalized, legacy)
                    .toInstant(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.MILLIS)
            }
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("Unparseable timestamp: '$text'", e)
        }
    }
}

/**
 * Persists [Instant] as the canonical UTC text ([UtcTimestamp]). [sqlTypeName] keeps each column's
 * Flyway-declared type name on SQLite (`TIMESTAMP` vs `TEXT`) so schema parity is unchanged; other
 * dialects delegate to Exposed's own instant type.
 */
class UtcTimestampColumnType(
    private val sqlTypeName: String
) : ColumnType<Instant>() {
    private val delegate = JavaInstantColumnType()

    override fun sqlType(): String = if (currentDialect is SQLiteDialect) sqlTypeName else delegate.sqlType()

    override fun valueFromDB(value: Any): Instant =
        when (value) {
            is Instant -> value
            is java.sql.Timestamp -> value.toInstant()
            is String -> UtcTimestamp.parse(value)
            // Integers are not a timestamp format any writer produces; refuse rather than guess epoch millis.
            is Number -> throw IllegalArgumentException("Unparseable timestamp (integer value): $value")
            else -> delegate.valueFromDB(value)
        }

    override fun notNullValueToDB(value: Instant): Any = UtcTimestamp.format(value)

    override fun nonNullValueToString(value: Instant): String = "'${UtcTimestamp.format(value)}'"
}

/** UTC timestamp column whose Flyway declaration is `TIMESTAMP`. */
fun Table.utcTimestamp(name: String): Column<Instant> = registerColumn(name, UtcTimestampColumnType("TIMESTAMP"))

/** UTC timestamp column whose Flyway declaration is `TEXT`. */
fun Table.utcTimestampText(name: String): Column<Instant> = registerColumn(name, UtcTimestampColumnType("TEXT"))
