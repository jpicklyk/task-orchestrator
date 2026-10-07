package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct-mode databases have no upgrade path: Flyway mode must refuse one, and Direct mode must
 * fail fast when an older file lacks columns the current tables declare.
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

    @Test
    fun `T1 flyway refuses a Direct-created database and leaves it untouched`() {
        val url = directDb()
        assertFalse(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        assertFalse(tableExists(url, "flyway_schema_history"))
        assertTrue(tableExists(url, "work_items"))
        assertEquals(1, workItemCount(url))
    }

    @Test
    fun `T2 flyway repair also refuses a Direct-created database`() {
        val url = directDb()
        assertFalse(FlywayDatabaseSchemaManager(url, repair = true).updateSchema())
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
    fun `T6 direct mode fails fast when an existing table lacks declared columns`() {
        val url = newUrl()
        withConn(url) { c ->
            c.createStatement().use {
                it.executeUpdate("CREATE TABLE work_items (id BLOB PRIMARY KEY, title TEXT NOT NULL)")
            }
        }
        connectExposed(url)
        assertFalse(DirectDatabaseSchemaManager().updateSchema())
    }

    @Test
    fun `T7 direct mode is repeatable on a current-shape database`() {
        val url = newUrl()
        connectExposed(url)
        assertTrue(DirectDatabaseSchemaManager().updateSchema())
        assertTrue(DirectDatabaseSchemaManager().updateSchema())
    }

    @Test
    fun `T8 direct mode accepts a Flyway-migrated database`() {
        val url = newUrl()
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        connectExposed(url)
        assertTrue(DirectDatabaseSchemaManager().updateSchema())
    }
}
