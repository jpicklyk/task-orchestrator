package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.infrastructure.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.repository.writeTx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Integration tests verifying the busy_timeout the [DatabaseManager] pools apply to their SQLite connections.
 *
 * Rewritten for P5a (item 9343ad8d). Oracle: carry-in decision 4 and task-scope section 2 - both pools use a FIXED
 * 1 s busy_timeout (1000 ms) regardless of DATABASE_BUSY_TIMEOUT_MS, which keeps governing only Flyway and
 * StartupCompaction; the 10 s unit deadline is the request-path budget. The previous expectation of >= 5000 ms no
 * longer holds, and a `mode=memory` URL is now refused (S8) so these tests use a file-backed database.
 * The validation logic of DATABASE_BUSY_TIMEOUT_MS is covered separately in [DatabaseConfigTest].
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DatabaseManagerBusyTimeoutTest {
    private val managers = mutableListOf<DatabaseManager>()

    private fun buildManager(dir: File): DatabaseManager {
        val manager = DatabaseManager()
        val url = "jdbc:sqlite:${File(dir, "busy_${System.nanoTime()}.db").absolutePath.replace('\\', '/')}"
        val initialized = manager.initialize(url)
        assertTrue(initialized, "DatabaseManager should initialize successfully against a file-backed database")
        managers += manager
        return manager
    }

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private suspend fun DatabaseManager.writerBusyTimeout(): Long =
        writeTx("BusyTimeoutTest.pragma") {
            var value = -1L
            exec("PRAGMA busy_timeout") { rs -> if (rs.next()) value = rs.getLong(1) }
            value
        }

    private suspend fun DatabaseManager.readerBusyTimeout(): Long =
        readTx {
            var value = -1L
            exec("PRAGMA busy_timeout") { rs -> if (rs.next()) value = rs.getLong(1) }
            value
        }

    @Test
    fun `writer busy_timeout pragma is the fixed 1000 ms of the pool`(
        @TempDir dir: File,
    ): Unit =
        runBlocking {
            assertEquals(1000L, buildManager(dir).writerBusyTimeout())
        }

    @Test
    fun `reader busy_timeout pragma is the fixed 1000 ms of the pool`(
        @TempDir dir: File,
    ): Unit =
        runBlocking {
            assertEquals(1000L, buildManager(dir).readerBusyTimeout())
        }

    @Test
    fun `busy_timeout is a non-negative value after initialization`(
        @TempDir dir: File,
    ): Unit =
        runBlocking {
            val manager = buildManager(dir)
            assertTrue(manager.writerBusyTimeout() >= 0L)
            assertTrue(manager.readerBusyTimeout() >= 0L)
        }

    @Test
    fun `initialize returns true for a valid file-backed SQLite path`(
        @TempDir dir: File,
    ) {
        val manager = DatabaseManager()
        val result = manager.initialize("jdbc:sqlite:${File(dir, "init.db").absolutePath.replace('\\', '/')}")
        managers += manager
        assertEquals(true, result)
    }

    @Test
    fun `initialize returns false for an in-memory SQLite URL (S8)`() {
        val manager = DatabaseManager()
        val result = manager.initialize("jdbc:sqlite:file:test_init_${System.nanoTime()}?mode=memory&cache=shared")
        managers += manager
        assertFalse(result, "P5a refuses mode=memory databases: the two-pool design needs a shared file")
    }
}
