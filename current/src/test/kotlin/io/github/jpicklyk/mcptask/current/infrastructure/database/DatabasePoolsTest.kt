package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent tests for the DatabaseManager pools (item 9343ad8d): S8 memory databases are refused, S11 the pool
 * PRAGMAs and transaction modes, the DATABASE_MAX_CONNECTIONS clamp (1..64), and the retained customDatabase
 * constructor (writer == reader == custom, carry-in 6). Oracle: plans/v4-phase1-core.md lines 77-81 and the
 * task-scope connections section; busy_timeout is a FIXED 1000 ms on both pools (carry-in 4).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DatabasePoolsTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager

    private suspend fun DatabaseManager.writerPragma(name: String): String =
        writeTx("S11.pragma.$name") {
            var out = ""
            exec("PRAGMA $name") { rs -> if (rs.next()) out = rs.getString(1) }
            out
        }

    private suspend fun DatabaseManager.readerPragma(name: String): String =
        readTx {
            var out = ""
            exec("PRAGMA $name") { rs -> if (rs.next()) out = rs.getString(1) }
            out
        }

    // ---------------------------------------------------------------- S8
    @Test
    fun `S8 initialize refuses shared-cache memory URLs and plain memory and accepts a file`(
        @TempDir dir: File,
    ) {
        val env = AppConfig.fromEnv { null }
        val mem1 = DatabaseManager(appConfig = env)
        assertEquals(false, mem1.initialize("jdbc:sqlite:file:p5a_mem_${System.nanoTime()}?mode=memory&cache=shared"))
        mem1.shutdown()
        val mem2 = DatabaseManager(appConfig = env)
        assertEquals(false, mem2.initialize("jdbc:sqlite::memory:"))
        mem2.shutdown()
        val mem3 = DatabaseManager(appConfig = env)
        assertEquals(false, mem3.initialize(":memory:"))
        mem3.shutdown()
        val file = DatabaseManager(appConfig = env)
        try {
            assertEquals(true, file.initialize("jdbc:sqlite:${File(dir, "ok.db").absolutePath.replace('\\', '/')}"))
        } finally {
            file.shutdown()
        }
    }

    // ---------------------------------------------------------------- S11
    @Test
    fun `S11 the writer pool carries busy_timeout 1000 foreign_keys on WAL and is not query_only`(): Unit =
        runBlocking {
            assertEquals("1000", dm.writerPragma("busy_timeout"))
            assertEquals("1", dm.writerPragma("foreign_keys"))
            assertEquals("wal", dm.writerPragma("journal_mode").lowercase())
            assertEquals("0", dm.writerPragma("query_only"))
        }

    @Test
    fun `S11 the reader pool is query_only with the same fixed busy_timeout and foreign_keys`(): Unit =
        runBlocking {
            assertEquals("1", dm.readerPragma("query_only"))
            assertEquals("1000", dm.readerPragma("busy_timeout"))
            assertEquals("1", dm.readerPragma("foreign_keys"))
        }

    @Test
    fun `S11 an open writer unit holds the write lock before its first write because it began IMMEDIATE`(): Unit =
        runBlocking {
            var message: String? = null
            db.uow().write("S11.immediate") {
                // Exposed begins the JDBC transaction on the first statement; a mere SELECT must already hold the IMMEDIATE lock.
                dm.writeTx("S11.touch") { exec("SELECT 1") { } }
                DriverManager.getConnection(db.jdbcUrl).use { raw ->
                    raw.createStatement().use { st ->
                        st.execute("PRAGMA busy_timeout=0")
                        try {
                            st.execute("BEGIN IMMEDIATE")
                            st.execute("ROLLBACK")
                        } catch (e: java.sql.SQLException) {
                            message = e.message
                        }
                    }
                }
                Outcome.Ok(Unit)
            }
            assertTrue(
                message.orEmpty().contains("SQLITE_BUSY"),
                "raw BEGIN IMMEDIATE must be BUSY while a writer unit is open, got: $message"
            )
        }

    @Test
    fun `S11 the writer and the reader are different databases with the writer preserved by getDatabase`() {
        assertFalse(dm.writer() === dm.reader())
        @Suppress("DEPRECATION")
        assertSame(dm.writer(), dm.getDatabase())
    }

    @Test
    fun `an uninitialized manager refuses writer and reader`() {
        val m = DatabaseManager(appConfig = AppConfig.fromEnv { null })
        assertFailsWith<IllegalStateException> { m.writer() }
        assertFailsWith<IllegalStateException> { m.reader() }
    }

    // ---------------------------------------------------------------- DATABASE_MAX_CONNECTIONS clamp (probe)
    private fun maxConcurrentReads(
        dir: File,
        envValue: String,
        threads: Int,
        holdMs: Long,
    ): Int {
        val manager = DatabaseManager(appConfig = AppConfig.fromEnv { if (it == "DATABASE_MAX_CONNECTIONS") envValue else null })
        try {
            assertTrue(manager.initialize("jdbc:sqlite:${File(dir, "pool-$envValue.db").absolutePath.replace('\\', '/')}"))
            // Hikari fills a pool of minimumIdle == maximumPoolSize connections in the background; let it finish.
            Thread.sleep(4_000)
            val current = AtomicInteger()
            val max = AtomicInteger()
            val start = CountDownLatch(1)
            val workers =
                (1..threads).map {
                    thread(isDaemon = true) {
                        start.await()
                        runBlocking {
                            manager.readTx {
                                // Exposed acquires the pooled connection lazily: touch it so it is held while we sleep.
                                exec("SELECT 1") { }
                                val now = current.incrementAndGet()
                                max.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                                Thread.sleep(holdMs)
                                current.decrementAndGet()
                            }
                        }
                    }
                }
            start.countDown()
            workers.forEach { it.join(30_000) }
            return max.get()
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `DATABASE_MAX_CONNECTIONS 0 is clamped up to a single reader connection`(
        @TempDir dir: File,
    ) {
        assertEquals(1, maxConcurrentReads(dir, "0", threads = 5, holdMs = 200))
    }

    @Test
    fun `DATABASE_MAX_CONNECTIONS unparseable falls back to the default of 10 readers`(
        @TempDir dir: File,
    ) {
        assertEquals(10, maxConcurrentReads(dir, "x", threads = 14, holdMs = 400))
    }

    @Test
    fun `DATABASE_MAX_CONNECTIONS 999 is clamped down to 64 readers`(
        @TempDir dir: File,
    ) {
        assertEquals(64, maxConcurrentReads(dir, "999", threads = 66, holdMs = 1_000))
    }

    // ---------------------------------------------------------------- customDatabase (file-backed SQLite) - carry-in 6
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `customDatabase makes writer and reader the same database and units still join and roll back`(
        @TempDir dir: File,
    ): Unit =
        runBlocking {
            val custom =
                Database.connect(
                    "jdbc:sqlite:" + File(dir, "custom.db").absolutePath.replace(File.separatorChar, '/'),
                    driver = "org.sqlite.JDBC"
                )
            val manager = DatabaseManager(customDatabase = custom, appConfig = AppConfig.fromEnv { null })
            try {
                assertTrue(manager.initialize("ignored"))
                assertSame(custom, manager.writer())
                assertSame(custom, manager.reader())
                manager.writeTx("Custom.ddl") { exec("CREATE TABLE p5a_probe (id INTEGER PRIMARY KEY, v INTEGER)") }
                val uow = SqliteUnitOfWork(manager, mockk<RepositoryProvider>(relaxed = true), Clock { Instant.now() })

                val committed =
                    uow.write("Custom.join") {
                        val tx = TransactionManager.currentOrNull()
                        manager.writeTx("Custom.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                        uow.write("Custom.inner") {
                            assertSame(tx, TransactionManager.currentOrNull(), "inner write joins on a custom database too")
                            Outcome.Ok(Unit)
                        }
                    }
                assertEquals(Outcome.Ok(Unit), committed)
                assertEquals(1, manager.countRows("p5a_probe"))

                val rolledBack =
                    uow.write<Unit>("Custom.rollback") {
                        manager.writeTx("Custom.row2") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                        Outcome.Err(err("declined"))
                    }
                assertTrue(rolledBack is Outcome.Err)
                assertEquals(1, manager.countRows("p5a_probe"), "an outermost Err rolls back on a custom database")
            } finally {
                manager.shutdown()
            }
        }
}
