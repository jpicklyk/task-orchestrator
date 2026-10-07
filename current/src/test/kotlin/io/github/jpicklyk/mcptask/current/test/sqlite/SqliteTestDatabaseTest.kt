package io.github.jpicklyk.mcptask.current.test.sqlite

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** S1-S5 for the SQLite fixture: template built once, production PRAGMAs, isolation, cleanup, concurrency. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SqliteTestDatabaseTest {
    @RegisterExtension
    @JvmField
    val perMethodDb = SqliteTestDatabase.perMethod()

    private fun pragma(
        db: SqliteTestDatabase,
        name: String
    ): String? =
        java.sql.DriverManager.getConnection(db.jdbcUrl).use { c ->
            // foreign_keys and busy_timeout are per-connection, so read them on the production
            // connection (Exposed's) rather than this ad-hoc one.
            if (name == "journal_mode") {
                c.createStatement().use { st ->
                    st.executeQuery("PRAGMA journal_mode").use {
                        it.next()
                        it.getString(1)
                    }
                }
            } else {
                null
            }
        }

    private fun productionPragma(
        db: SqliteTestDatabase,
        name: String
    ): String =
        transaction(db = db.database) {
            var out = ""
            exec("PRAGMA $name") { rs -> if (rs.next()) out = rs.getString(1) }
            out
        }

    @Test
    @Order(1)
    fun `S1 the template is built once per JVM however many databases open`() {
        SqliteTestDatabase.open().use { }
        SqliteTestDatabase.open().use { }
        assertEquals(1, SqliteTemplate.buildCount.get(), "template must be built exactly once per JVM")
    }

    @Test
    fun `S2 an open copy carries the production PRAGMAs`() {
        val db = perMethodDb.db
        assertEquals("wal", productionPragma(db, "journal_mode").lowercase())
        assertEquals("wal", pragma(db, "journal_mode")?.lowercase())
        assertEquals("1", productionPragma(db, "foreign_keys"))
        assertEquals("5000", productionPragma(db, "busy_timeout"))
    }

    @Test
    fun `S3 perMethod gives every test an empty migrated database (first)`() {
        assertEmptyThenWrite()
    }

    @Test
    fun `S3 perMethod gives every test an empty migrated database (second)`() {
        assertEmptyThenWrite()
    }

    private fun assertEmptyThenWrite() {
        val before =
            transaction(db = perMethodDb.database) {
                var n = -1
                exec("SELECT count(*) FROM work_items") { rs -> if (rs.next()) n = rs.getInt(1) }
                n
            }
        assertEquals(0, before, "each test must start from an empty database")
        transaction(db = perMethodDb.database) {
            exec(
                "INSERT INTO work_items (id, title, created_at, modified_at, role_changed_at) " +
                    "VALUES (randomblob(16), 'leftover', datetime('now'), datetime('now'), datetime('now'))"
            )
        }
    }

    @Test
    fun `S4 close removes the database, its sidecars and its directory`() {
        val db = SqliteTestDatabase.open()
        val dir = db.file.parentFile
        // Touch the database so WAL sidecars exist while it is open.
        transaction(db = db.database) { exec("SELECT count(*) FROM work_items") { } }
        assertTrue(db.file.exists())
        db.close()
        assertFalse(db.file.exists(), "database file must be deleted")
        assertFalse(File(db.file.path + "-wal").exists(), "wal sidecar must be deleted")
        assertFalse(File(db.file.path + "-shm").exists(), "shm sidecar must be deleted")
        assertFalse(dir.exists(), "scope directory must be deleted")
    }

    @Test
    fun `S5 concurrent opens get distinct files`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = List(2) { Callable { SqliteTestDatabase.open() } }
            val opened = pool.invokeAll(tasks).map { it.get() }
            try {
                assertNotEquals(opened[0].file, opened[1].file)
                assertNotEquals(opened[0].jdbcUrl, opened[1].jdbcUrl)
            } finally {
                opened.forEach { it.close() }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        @RegisterExtension
        @JvmField
        val perClassDb = SqliteTestDatabase.perClass()
    }

    @Test
    fun `perClass extension exposes one live database`() {
        val first = perClassDb.file
        assertTrue(first.exists())
        assertEquals(first, perClassDb.file)
    }
}
