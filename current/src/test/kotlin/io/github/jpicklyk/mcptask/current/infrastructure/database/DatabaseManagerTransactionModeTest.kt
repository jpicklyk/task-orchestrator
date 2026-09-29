package io.github.jpicklyk.mcptask.current.infrastructure.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Blind regression coverage for D1 (`plans/fix-config-sync-busy.md`): every SQLite transaction
 * [DatabaseManager] opens must begin `IMMEDIATE` so concurrent read-then-write transactions queue
 * on `busy_timeout` instead of failing with `SQLITE_BUSY_SNAPSHOT` at the read-to-write upgrade.
 *
 * S1: against ONE file-backed database, 8 threads each run 5 `SELECT`-then-`UPDATE` transactions
 * on the same row, started behind a [CountDownLatch]. With the fix every transaction commits (the
 * counter reaches threads * iterations) and no thread observes an exception whose message contains
 * `SQLITE_BUSY`. Without the fix (today's plain `BEGIN`) at least one thread hits
 * `SQLITE_BUSY_SNAPSHOT` immediately, since `busy_timeout` only governs lock acquisition, not a
 * snapshot conflict raised at the write upgrade.
 *
 * S2: a plain sanity check — a single transaction still commits, and the `busy_timeout` PRAGMA
 * already covered by [DatabaseManagerBusyTimeoutTest] is unaffected by the new transaction mode.
 */
class DatabaseManagerTransactionModeTest {
    /** A minimal table created in-test (not the production schema) to isolate this scenario. */
    private object CounterTable : Table("busy_counter") {
        val id = integer("id")
        val value = integer("value")

        override val primaryKey = PrimaryKey(id)
    }

    private val managers = mutableListOf<DatabaseManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    @Test
    fun `8 threads x 5 read-then-update transactions on one row all commit without SQLITE_BUSY`(
        @TempDir tempDir: File,
    ) {
        val dbFile = File(tempDir, "busy-mode.db")
        val manager = DatabaseManager()
        val initialized = manager.initialize("jdbc:sqlite:${dbFile.absolutePath}")
        assertTrue(initialized, "DatabaseManager should initialize successfully against a file-backed DB")
        managers += manager
        val db = manager.getDatabase()

        transaction(db) {
            SchemaUtils.create(CounterTable)
            exec("INSERT INTO busy_counter (id, value) VALUES (1, 0)")
        }

        val threadCount = 8
        val iterationsPerThread = 5
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        // Every caught exception message from every transaction attempt, for diagnostics; the
        // assertion below only cares whether any of them mentions SQLITE_BUSY.
        val caughtMessages = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    repeat(iterationsPerThread) {
                        try {
                            transaction(db) {
                                var current = -1
                                exec("SELECT value FROM busy_counter WHERE id = 1") { rs ->
                                    if (rs.next()) current = rs.getInt(1)
                                }
                                exec("UPDATE busy_counter SET value = ${current + 1} WHERE id = 1")
                            }
                        } catch (e: Exception) {
                            caughtMessages += (e.message ?: e.toString())
                        }
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finishedInTime = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(finishedInTime, "all 8 threads should finish their 5 transactions within 10s")

        val finalValue =
            transaction(db) {
                var v = -1
                exec("SELECT value FROM busy_counter WHERE id = 1") { rs -> if (rs.next()) v = rs.getInt(1) }
                v
            }

        assertEquals(
            threadCount * iterationsPerThread,
            finalValue,
            "every one of the ${threadCount * iterationsPerThread} transactions must have committed its " +
                "increment; caught exceptions were: $caughtMessages",
        )
        assertTrue(
            caughtMessages.none { it.contains("SQLITE_BUSY") },
            "no thread should observe an exception mentioning SQLITE_BUSY; caught: $caughtMessages",
        )
    }

    @Test
    fun `a single transaction still commits and busy_timeout remains applied`() {
        val dbName = "test_txmode_single_${System.nanoTime()}"
        val manager = DatabaseManager()
        val initialized = manager.initialize("jdbc:sqlite:file:$dbName?mode=memory&cache=shared")
        assertTrue(initialized, "DatabaseManager should initialize successfully")
        managers += manager
        val db = manager.getDatabase()

        transaction(db) {
            SchemaUtils.create(CounterTable)
            exec("INSERT INTO busy_counter (id, value) VALUES (1, 0)")
            exec("UPDATE busy_counter SET value = 41 WHERE id = 1")
        }

        val finalValue =
            transaction(db) {
                var v = -1
                exec("SELECT value FROM busy_counter WHERE id = 1") { rs -> if (rs.next()) v = rs.getInt(1) }
                v
            }
        assertEquals(41, finalValue, "a single transaction must still commit its read-then-update")

        val pragmaValue =
            transaction(db) {
                var value = -1L
                exec("PRAGMA busy_timeout") { rs -> if (rs.next()) value = rs.getLong(1) }
                value
            }
        assertTrue(
            pragmaValue >= 5000L,
            "busy_timeout must remain applied (>= 5000 ms default) after the transaction-mode change, got $pragmaValue",
        )
    }
}
