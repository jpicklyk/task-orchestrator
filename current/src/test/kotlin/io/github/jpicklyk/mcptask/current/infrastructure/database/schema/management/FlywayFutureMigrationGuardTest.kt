package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A database migrated AHEAD of this binary (for example by a 4.0 binary, V18+) must be refused, not served
 * (AR-37 rec 1). Flyway's default `ignoreMigrationPatterns` is `*:future`, which validates such a database and lets
 * a 3.x process write rows that bypass the newer schema's invariants. The fix ignores no pattern, so 3.x keeps its
 * existing refusal of an applied version that has no local file below the latest (unlike 4.0, which tolerates it).
 *
 * Oracle: AR-37 rec 1 (release/4.0 FlywayOnlyMigrationTest S10) and main's pre-fix behaviour for S3, item `a05b4d0e`.
 */
class FlywayFutureMigrationGuardTest {
    @TempDir
    lateinit var dir: Path

    private fun newUrl(): String =
        "jdbc:sqlite:" +
            dir
                .resolve("db-${System.nanoTime()}.sqlite")
                .toAbsolutePath()
                .toString()
                .replace(java.io.File.separatorChar, '/')

    private fun manager(url: String) = FlywayDatabaseSchemaManager(url, repair = false)

    private fun migrated(): String {
        val url = newUrl()
        assertTrue(manager(url).updateSchema(), "a fresh database must migrate")
        return url
    }

    private fun insertHistory(
        url: String,
        version: String,
        success: Int
    ) {
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, " +
                        "checksum, installed_by, installed_on, execution_time, success) " +
                        "VALUES ((SELECT max(installed_rank) + 1 FROM flyway_schema_history), '$version', 'x', 'SQL', " +
                        "'V${version.replace('.', '_')}__x.sql', 0, 'test', '2026-01-01 00:00:00', 1, $success)"
                )
            }
        }
    }

    private fun errorsOf(block: () -> Unit): List<String> {
        val logbackLogger = LoggerFactory.getLogger(FlywayDatabaseSchemaManager::class.java) as Logger
        val appender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        try {
            block()
            return appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }

    @Test
    fun `S1 a successful history row for a version newer than the binary is refused`() {
        val url = migrated()
        insertHistory(url, "99", 1)
        val errors = errorsOf { assertFalse(manager(url).updateSchema(), "a database ahead of this binary must be refused") }
        assertTrue(errors.any { it.contains("99") }, "the ERROR must name the offending version 99: $errors")
    }

    @Test
    fun `S2 a failed history row for a version newer than the binary is refused`() {
        val url = migrated()
        insertHistory(url, "99", 0)
        val errors = errorsOf { assertFalse(manager(url).updateSchema()) }
        assertTrue(errors.any { it.contains("99") }, "the ERROR must name the failed version 99: $errors")
    }

    @Test
    fun `S3 an applied version with no local file below the latest is still refused, as before the fix`() {
        val url = migrated()
        insertHistory(url, "5.5", 1)
        val errors = errorsOf { assertFalse(manager(url).updateSchema(), "3.x behaviour for a locally missing version is unchanged") }
        assertTrue(errors.any { it.contains("5.5") }, "the ERROR must name the missing version 5.5: $errors")
    }

    @Test
    fun `S4 a database at the binary's latest version starts again`() {
        val url = migrated()
        assertTrue(manager(url).updateSchema())
    }

    private fun historyRows(url: String): List<String> =
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                val sql = "SELECT installed_rank, version, type, success FROM flyway_schema_history ORDER BY installed_rank"
                st.executeQuery(sql).use { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) rows += "${rs.getInt(1)}|${rs.getString(2)}|${rs.getString(3)}|${rs.getInt(4)}"
                    rows
                }
            }
        }

    @Test
    fun `S5 repair leaves a newer successful row alone and the next start still refuses`() {
        val url = migrated()
        insertHistory(url, "99", 1)
        val before = historyRows(url)
        assertTrue(FlywayDatabaseSchemaManager(url, repair = true).updateSchema(), "repair itself succeeds")
        assertEquals(before, historyRows(url), "repair must not delete or add any history row for a newer version")
        val errors = errorsOf { assertFalse(manager(url).updateSchema(), "the database is still ahead of this binary") }
        assertTrue(errors.any { it.contains("99") }, "the ERROR must still name version 99: $errors")
    }

    @Test
    fun `S6 repair still removes a failed newer row as before`() {
        val url = migrated()
        insertHistory(url, "99", 0)
        assertTrue(FlywayDatabaseSchemaManager(url, repair = true).updateSchema())
        assertTrue(historyRows(url).none { it.split("|")[1] == "99" }, "failed row removed by repair: ${historyRows(url)}")
    }
}
