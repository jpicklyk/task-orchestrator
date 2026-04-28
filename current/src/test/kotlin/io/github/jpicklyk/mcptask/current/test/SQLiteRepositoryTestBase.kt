package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.DependenciesTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.NotesTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.RoleTransitionsTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection
import java.sql.DriverManager

/**
 * Base class for repository integration tests that require SQLite-specific SQL syntax
 * (e.g., `datetime('now', '+N seconds')`).
 *
 * Uses a real SQLite in-memory database per test method instead of H2. This is required
 * for tests that exercise the canonical claim SQL pattern, which uses SQLite date functions
 * that H2 does not support.
 *
 * A persistent JDBC connection is held open for the lifetime of each test to prevent the
 * SQLite in-memory database from being destroyed when Exposed returns a pooled connection.
 */
abstract class SQLiteRepositoryTestBase {
    protected lateinit var database: Database
    protected lateinit var databaseManager: DatabaseManager
    protected lateinit var repositoryProvider: DefaultRepositoryProvider

    /** Holds the SQLite in-memory connection open for the lifetime of this test. */
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUpDatabase() {
        // Use a named shared-cache in-memory SQLite DB so multiple connections can reach it.
        val dbName = "testdb_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"

        // Keep a connection open so the in-memory DB is not destroyed between transactions.
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)

        // Connect Exposed to the same in-memory DB.
        database =
            Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC",
            )
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // Create schema on this specific database instance so the tables exist before any test.
        transaction(db = database) {
            SchemaUtils.create(
                WorkItemsTable,
                NotesTable,
                DependenciesTable,
                RoleTransitionsTable,
            )
        }

        databaseManager = DatabaseManager(database)
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
    }

    @AfterEach
    fun tearDownDatabase() {
        try {
            TransactionManager.closeAndUnregister(database)
        } catch (_: Exception) {
            // Ignore cleanup errors for in-memory databases
        }
        try {
            keepAliveConnection.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
    }
}
