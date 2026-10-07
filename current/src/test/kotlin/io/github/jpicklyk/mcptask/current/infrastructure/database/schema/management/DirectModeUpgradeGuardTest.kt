package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct-mode databases have no upgrade path: the Flyway-only manager refuses one (any database that
 * holds user tables, has no flyway_schema_history and is not an exact V17 shape) and leaves it
 * untouched. Oracle: plan v4-phase1-core 3.10 ("anything else refuses with instructions"); the
 * Direct manager itself is gone from production, the test bridge only builds fixtures.
 */
class DirectModeUpgradeGuardTest {
    @TempDir
    lateinit var dir: Path

    private val registered = mutableListOf<Database>()

    @AfterEach
    fun teardown() {
        registered.forEach { TransactionManager.closeAndUnregister(it) }
        registered.clear()
    }

    private fun newUrl(): String =
        "jdbc:sqlite:" +
            dir
                .resolve("db-${System.nanoTime()}.sqlite")
                .toAbsolutePath()
                .toString()
                .replace(java.io.File.separatorChar, '/')

    private fun connectExposed(url: String) {
        registered += Database.connect(url = url, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    }

    private fun <T> withConn(
        url: String,
        block: (Connection) -> T
    ): T = DriverManager.getConnection(url).use(block)

    private fun tableExists(
        url: String,
        name: String
    ): Boolean =
        withConn(url) { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$name'").use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }
        }

    private fun directDb(): String {
        val url = newUrl()
        connectExposed(url)
        assertTrue(DirectDatabaseSchemaManager().updateSchema())
        withConn(url) { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO work_items (id, title, summary, role, priority, requires_verification, depth, created_at, " +
                        "modified_at, role_changed_at, version) VALUES (X'00000000000000000000000000000001','t','','queue'," +
                        "'medium',0,0,'2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:00:00',1)"
                )
            }
        }
        return url
    }

    private fun workItemCount(url: String): Int =
        withConn(url) { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM work_items").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Captures ERROR-level records from [FlywayDatabaseSchemaManager] during [block]. */
    private fun captureErrorLogs(block: () -> Unit): List<String> {
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

    /** The custom guard's actionable message, not Flyway's own default refusal. */
    private fun assertActionableDirectModeError(errors: List<String>) {
        assertTrue(
            errors.any { it.contains("DATABASE_PATH") },
            "expected an actionable ERROR naming the remedy (DATABASE_PATH), got: $errors"
        )
    }

    @Test
    fun `T1 flyway refuses a Direct-created database and leaves it untouched`() {
        val url = directDb()
        val errors = captureErrorLogs { assertFalse(FlywayDatabaseSchemaManager(url, repair = false).updateSchema()) }
        assertActionableDirectModeError(errors)
        assertFalse(tableExists(url, "flyway_schema_history"))
        assertTrue(tableExists(url, "work_items"))
        assertEquals(1, workItemCount(url))
    }

    @Test
    fun `T2 flyway repair also refuses a Direct-created database`() {
        val url = directDb()
        val errors = captureErrorLogs { assertFalse(FlywayDatabaseSchemaManager(url, repair = true).updateSchema()) }
        assertActionableDirectModeError(errors)
        assertFalse(tableExists(url, "flyway_schema_history"))
        assertEquals(1, workItemCount(url))
    }

    @Test
    fun `T3 flyway migrates an empty database`() {
        val url = newUrl()
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        assertTrue(tableExists(url, "flyway_schema_history"))
        assertTrue(tableExists(url, "work_items"))
    }

    @Test
    fun `T4 flyway migration is repeatable on the same database`() {
        val url = newUrl()
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        assertTrue(FlywayDatabaseSchemaManager(url, repair = true).updateSchema())
    }

    @Test
    fun `T5 flyway configuration disables clean and baseline-on-migrate`() {
        val cfg = FlywayDatabaseSchemaManager("jdbc:sqlite:unused", repair = false).flywayConfiguration()
        assertTrue(cfg.isCleanDisabled)
        assertFalse(cfg.isBaselineOnMigrate)
    }

    @Test
    fun `T6 a database whose existing table lacks declared columns is refused untouched`() {
        val url = newUrl()
        withConn(url) { c ->
            c.createStatement().use {
                it.executeUpdate("CREATE TABLE work_items (id BLOB PRIMARY KEY, title TEXT NOT NULL)")
            }
        }
        val errors = captureErrorLogs { assertFalse(FlywayDatabaseSchemaManager(url, repair = false).updateSchema()) }
        assertActionableDirectModeError(errors)
        assertFalse(tableExists(url, "flyway_schema_history"))
        assertTrue(tableExists(url, "work_items"))
    }

    @Test
    fun `T7 refusal of a Direct-created database is repeatable and never mutates it`() {
        val url = directDb()
        repeat(2) {
            captureErrorLogs { assertFalse(FlywayDatabaseSchemaManager(url, repair = false).updateSchema()) }
        }
        assertFalse(tableExists(url, "flyway_schema_history"))
        assertEquals(1, workItemCount(url))
    }

    @Test
    fun `T8 a Flyway-migrated database keeps being accepted on every later start`() {
        val url = newUrl()
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        withConn(url) { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO work_items (id, title, created_at, modified_at, role_changed_at) VALUES " +
                        "(X'00000000000000000000000000000002','t','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:00:00')"
                )
            }
        }
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        assertEquals(1, workItemCount(url))
    }
}
