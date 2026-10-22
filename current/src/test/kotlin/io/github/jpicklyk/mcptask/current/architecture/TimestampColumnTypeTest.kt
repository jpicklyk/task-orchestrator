package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Timestamp column-type guard (P7, AR-49): every persisted instant goes through `UtcTimestampColumnType`, so no
 * stored value depends on the JVM default timezone. Production code must not import Exposed's javatime
 * `timestamp`/`datetime` column factories or reference the removed `SqliteInstantColumnType` / `timestampSqlite`.
 * `UtcTimestampColumnType.kt` itself may use `JavaInstantColumnType` as a read delegate for non-SQLite dialects.
 */
class TimestampColumnTypeTest {
    companion object {
        val FORBIDDEN =
            listOf(
                Regex("""import\s+org\.jetbrains\.exposed\.v1\.javatime\.(timestamp|datetime|date|time|timestampWithTimeZone)\b"""),
                Regex("""\bSqliteInstantColumnType\b"""),
                Regex("""\btimestampSqlite\s*\("""),
                Regex("""=\s*timestamp\s*\(""")
            )

        fun violations(text: String): Int =
            text
                .lines()
                .filter { GuardSupport.isCodeLine(it) }
                .map { GuardSupport.stripStrings(it) }
                .count { line -> FORBIDDEN.any { it.containsMatchIn(line) } }
    }

    @Test
    fun `no production file uses a non-UTC timestamp column type`() {
        val actual = GuardSupport.countByFile(GuardSupport.productionSources()) { violations(it.text) }
        assertEquals(emptyMap(), actual, "persisted timestamps must use utcTimestamp/utcTimestampText; offenders: $actual")
    }

    @Test
    fun `the guard flags each forbidden shape`() {
        val samples =
            listOf(
                "import org.jetbrains.exposed.v1.javatime.timestamp",
                "class X : SqliteInstantColumnType()",
                "val a = timestampSqlite()",
                "val a = timestamp()"
            )
        samples.forEach { assertTrue(violations(it) == 1, "not flagged: $it") }
        assertEquals(0, violations("val a = utcTimestamp()"))
    }
}
