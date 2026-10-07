package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.infrastructure.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.repository.writeTx
import kotlinx.coroutines.runBlocking
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
 * Regression coverage for D1 (`plans/fix-config-sync-busy.md`), rewritten for P5a (item 9343ad8d): concurrent
 * read-then-write transactions must queue instead of failing with SQLITE_BUSY. The original drove 8 raw threads
 * through `getDatabase()`; against the size-1 writer pool that times out, so the rewrite goes through `writeTx`
 * (carry-in decision 7, task-scope section 12). Oracle: plan section 3.2 line 77 (one in-process serialized writer
 * connection that begins IMMEDIATE) - the observable contract is unchanged: every increment commits and no thread
 * observes SQLITE_BUSY.
 *
 * S1: 8 threads each run 5 `SELECT`-then-`UPDATE` transactions on the same row through `writeTx`, behind a latch.
 * S2: a single transaction still commits and the pool busy_timeout (1000 ms, fixed) is applied.
 */
class DatabaseManagerTransactionModeTest {
    private val managers = mutableListOf<DatabaseManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun newManager(
        dir: File,
        name: String
    ): DatabaseManager {
        val manager = DatabaseManager()
        assertTrue(
            manager.initialize("jdbc:sqlite:${File(dir, name).absolutePath.replace('\\', '/')}"),
            "DatabaseManager should initialize successfully against a file-backed DB",
        )
        managers += manager
        return manager
    }

    private suspend fun DatabaseManager.counter(): Int =
        readTx {
            var v = -1
            exec("SELECT value FROM busy_counter WHERE id = 1") { rs -> if (rs.next()) v = rs.getInt(1) }
            v
        }

    @Test
    fun `8 threads x 5 read-then-update transactions on one row all commit without SQLITE_BUSY`(
        @TempDir tempDir: File,
    ) {
        val manager = newManager(tempDir, "busy-mode.db")
        runBlocking {
            manager.writeTx("TransactionModeTest.create") {
                exec("CREATE TABLE busy_counter (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
                exec("INSERT INTO busy_counter (id, value) VALUES (1, 0)")
            }
        }

        val threadCount = 8
        val iterationsPerThread = 5
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val caughtMessages = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    repeat(iterationsPerThread) {
                        try {
                            runBlocking {
                                manager.writeTx("TransactionModeTest.rmw") {
                                    var current = -1
                                    exec("SELECT value FROM busy_counter WHERE id = 1") { rs ->
                                        if (rs.next()) current = rs.getInt(1)
                                    }
                                    exec("UPDATE busy_counter SET value = ${current + 1} WHERE id = 1")
                                }
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
        val finishedInTime = doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(finishedInTime, "all 8 threads should finish their 5 transactions within 30s")

        val finalValue = runBlocking { manager.counter() }
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
    fun `a single transaction still commits and the fixed busy_timeout remains applied`(
        @TempDir tempDir: File,
    ): Unit =
        runBlocking {
            val manager = newManager(tempDir, "busy-mode-single.db")
            manager.writeTx("TransactionModeTest.single") {
                exec("CREATE TABLE busy_counter (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
                exec("INSERT INTO busy_counter (id, value) VALUES (1, 0)")
                exec("UPDATE busy_counter SET value = 41 WHERE id = 1")
            }
            assertEquals(41, manager.counter(), "a single transaction must still commit its read-then-update")

            val pragmaValue =
                manager.writeTx("TransactionModeTest.pragma") {
                    var value = -1L
                    exec("PRAGMA busy_timeout") { rs -> if (rs.next()) value = rs.getLong(1) }
                    value
                }
            assertEquals(1000L, pragmaValue, "pool busy_timeout is fixed at 1000 ms (carry-in decision 4)")
        }
}
