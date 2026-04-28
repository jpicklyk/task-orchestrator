package io.github.jpicklyk.mcptask.current.infrastructure.database

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests verifying that [DatabaseManager] applies the busy_timeout PRAGMA correctly
 * to SQLite connections.  Each test uses a unique in-memory SQLite database so they are fully
 * isolated.
 *
 * Because env vars cannot be set programmatically on a standard JVM, the busy_timeout path
 * exercised here always uses the default (5000 ms).  The validation logic is covered separately
 * in [DatabaseConfigTest] which calls [DatabaseConfig.resolveBusyTimeoutMs] directly.
 */
class DatabaseManagerBusyTimeoutTest {
    private val managers = mutableListOf<DatabaseManager>()

    /** Create a fresh in-memory SQLite DatabaseManager and track it for cleanup. */
    private fun buildManager(): DatabaseManager {
        val dbName = "test_busy_${System.nanoTime()}"
        val manager = DatabaseManager()
        // Use SQLite in-memory URL with a named cache so the same connection is reused within
        // the same JVM process (required for pragma verification to work on the same connection).
        val initialized = manager.initialize("jdbc:sqlite:file:$dbName?mode=memory&cache=shared")
        assertTrue(initialized, "DatabaseManager should initialize successfully")
        managers += manager
        return manager
    }

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    // ------------------------------------------------------------------
    // Default busy_timeout — verifies PRAGMA is applied
    // ------------------------------------------------------------------

    @Test
    fun `busy_timeout pragma is applied on new connection`() {
        val manager = buildManager()
        val db = manager.getDatabase()

        val pragmaValue =
            transaction(db) {
                var value = -1L
                exec("PRAGMA busy_timeout") { rs ->
                    if (rs.next()) value = rs.getLong(1)
                }
                value
            }
        // Default is 5000 ms.  SQLite may round or normalise the value, but it must be
        // at least 5000 ms (and typically equals 5000).
        assertTrue(pragmaValue >= 5000L, "Expected busy_timeout >= 5000 ms but got $pragmaValue")
    }

    @Test
    fun `busy_timeout is a non-negative value after initialization`() {
        val manager = buildManager()
        val db = manager.getDatabase()

        val pragmaValue =
            transaction(db) {
                var value = -1L
                exec("PRAGMA busy_timeout") { rs ->
                    if (rs.next()) value = rs.getLong(1)
                }
                value
            }
        assertTrue(pragmaValue >= 0L, "busy_timeout should be non-negative, got $pragmaValue")
    }

    // ------------------------------------------------------------------
    // Verify DatabaseManager initializes successfully (no exception)
    // ------------------------------------------------------------------

    @Test
    fun `initialize returns true for a valid in-memory SQLite path`() {
        val manager = DatabaseManager()
        val dbName = "test_init_${System.nanoTime()}"
        val result = manager.initialize("jdbc:sqlite:file:$dbName?mode=memory&cache=shared")
        managers += manager
        assertEquals(true, result)
    }
}
