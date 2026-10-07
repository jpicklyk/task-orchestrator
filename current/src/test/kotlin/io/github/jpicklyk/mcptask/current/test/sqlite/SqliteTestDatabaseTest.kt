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
import kotlin.test.assertFailsWith
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

    /** Writes through the production manager (so the database header is in WAL mode), then holds a raw connection open. */
    private fun holdWalConnection(db: SqliteTestDatabase): java.sql.Connection {
        transaction(db = db.database) { exec("SELECT count(*) FROM work_items") { } }
        val held = java.sql.DriverManager.getConnection(db.jdbcUrl)
        held.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM work_items").use { it.next() } }
        return held
    }

    @Test
    fun `S4 close removes the database, its sidecars and its directory`() {
        val db = SqliteTestDatabase.open()
        val dir = db.file.parentFile
        val wal = File(db.file.path + "-wal")
        val shm = File(db.file.path + "-shm")
        // Unpooled Exposed connections remove the sidecars when the last one closes, so hold one connection
        // open: the sidecars then exist, which makes the post-close absence below a real check.
        val held = holdWalConnection(db)
        try {
            assertTrue(db.file.exists())
            assertTrue(wal.exists(), "wal sidecar must exist while a connection is held (otherwise this test is vacuous)")
            assertTrue(shm.exists(), "shm sidecar must exist while a connection is held (otherwise this test is vacuous)")
        } finally {
            held.close()
        }
        db.close()
        assertFalse(db.file.exists(), "database file must be deleted")
        assertFalse(wal.exists(), "wal sidecar must be deleted")
        assertFalse(shm.exists(), "shm sidecar must be deleted")
        assertFalse(dir.exists(), "scope directory must be deleted")
    }

    @Test
    fun `S4 a leaked connection makes close fail naming the file (Windows) or still cleans up (elsewhere)`() {
        val db = SqliteTestDatabase.open()
        val dir = db.file.parentFile
        val held = holdWalConnection(db)
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        try {
            if (windows) {
                // An open handle cannot be deleted on Windows: the fixture must fail loudly, not leak silently.
                val e = assertFailsWith<IllegalStateException> { db.close() }
                assertTrue("test.db" in e.message.orEmpty(), "failure must name the leaked file: ${e.message}")
                assertTrue("leaked a connection" in e.message.orEmpty(), "failure must explain the cause: ${e.message}")
            } else {
                // POSIX unlinks open files, so a leak cannot be detected by deletion; close must simply succeed.
                db.close()
                assertFalse(dir.exists(), "scope directory must be deleted")
            }
        } finally {
            held.close()
            db.close()
        }
        assertFalse(dir.exists(), "scope directory must be deleted once the leaked connection is closed")
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
    @Order(20)
    fun `perClass database persists across tests (writer)`() {
        transaction(db = perClassDb.database) {
            exec(
                "INSERT INTO work_items (id, title, created_at, modified_at, role_changed_at) " +
                    "VALUES (randomblob(16), 'per-class-marker', datetime('now'), datetime('now'), datetime('now'))"
            )
        }
    }

    @Test
    @Order(21)
    fun `perClass database persists across tests (reader sees the earlier write)`() {
        val markers =
            transaction(db = perClassDb.database) {
                var n = -1
                exec("SELECT count(*) FROM work_items WHERE title = 'per-class-marker'") { rs -> if (rs.next()) n = rs.getInt(1) }
                n
            }
        assertEquals(1, markers, "a perClass database must be shared by every test of the class")
        assertTrue(perClassDb.file.exists())
    }
}
