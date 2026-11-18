package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Timestamp column-type guard (P7, AR-49): every persisted instant goes through `UtcTimestampColumnType`, so no
 * stored value depends on the JVM default timezone. Production code must not import Exposed's javatime
 * `timestamp`/`datetime` column factories (named or wildcard), declare a column with one (`= timestamp(` or
 * `by datetime(`), or reference the removed `SqliteInstantColumnType` / `timestampSqlite`. Outside
 * `UtcTimestampColumnType.kt` it also must not name a `Java*ColumnType` or the javatime package at all;
 * that file itself uses `JavaInstantColumnType` as a read delegate for non-SQLite dialects.
 */
class TimestampColumnTypeTest {
    companion object {
        const val EXEMPT = "infrastructure/sqlite/schema/UtcTimestampColumnType.kt"

        val FORBIDDEN =
            listOf(
                Regex("""import\s+org\.jetbrains\.exposed\.v1\.javatime\.(?:(timestamp|datetime|date|time|timestampWithTimeZone)\b|\*)"""),
                Regex("""\bSqliteInstantColumnType\b"""),
                Regex("""\btimestampSqlite\s*\("""),
                Regex("""(?:=|\bby)\s*(timestamp|datetime|date|time|timestampWithTimeZone)\s*\(""")
            )

        /** Rules that apply to every file except [EXEMPT]. */
        val FORBIDDEN_OUTSIDE_EXEMPT =
            listOf(
                Regex("""\bJava\w*ColumnType\b"""),
                Regex("""\borg\.jetbrains\.exposed\.v1\.javatime\.""")
            )

        fun violations(
            text: String,
            path: String = ""
        ): Int {
            val rules = if (path == EXEMPT) FORBIDDEN else FORBIDDEN + FORBIDDEN_OUTSIDE_EXEMPT
            return text
                .lines()
                .filter { GuardSupport.isCodeLine(it) }
                .map { GuardSupport.stripStrings(it) }
                .count { line -> rules.any { it.containsMatchIn(line) } }
        }
    }

    @Test
    fun `no production file uses a non-UTC timestamp column type`() {
        val sources = GuardSupport.productionSources()
        assertTrue(sources.any { it.path == EXEMPT }, "exempt file is not a production source (renamed or deleted?): $EXEMPT")
        val actual = GuardSupport.countByFile(sources) { violations(it.text, it.path) }
        assertEquals(emptyMap(), actual, "persisted timestamps must use utcTimestamp/utcTimestampText; offenders: $actual")
    }

    @Test
    fun `the guard flags each forbidden shape`() {
        val samples =
            listOf(
                "import org.jetbrains.exposed.v1.javatime.timestamp",
                "import org.jetbrains.exposed.v1.javatime.*",
                "class X : SqliteInstantColumnType()",
                "val a = timestampSqlite()",
                "val a = timestamp()",
                "val a = datetime(\"x\")",
                "val a = timestampWithTimeZone(\"x\")",
                "val a by datetime(\"x\")"
            )
        samples.forEach { assertTrue(violations(it, EXEMPT) == 1, "not flagged: $it") }
        assertEquals(0, violations("val a = utcTimestamp()"))
    }

    @Test
    fun `the Java column type and javatime package are flagged outside the exempt file only`() {
        val samples =
            listOf(
                "registerColumn(name, JavaInstantColumnType())",
                "val t = org.jetbrains.exposed.v1.javatime.JavaInstantColumnType()"
            )
        samples.forEach {
            assertEquals(1, violations(it, "infrastructure/sqlite/schema/Other.kt"), "not flagged outside: $it")
            assertEquals(0, violations(it, EXEMPT), "wrongly flagged inside the exempt file: $it")
        }
    }
}
