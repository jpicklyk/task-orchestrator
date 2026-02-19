package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaManagerFactory
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection

/**
 * Manages database connections and schema management for the Current (v3) MCP Task Orchestrator.
 *
 * @param customDatabase Optional pre-configured database connection (useful for testing).
 */
class DatabaseManager(
    private val customDatabase: Database? = null
) {
    private val logger = LoggerFactory.getLogger(DatabaseManager::class.java)
    private var database: Database? = customDatabase
    private lateinit var schemaManager: DatabaseSchemaManager

    /**
     * Initializes the database connection.
     *
     * @param databasePath The path to the SQLite database file or JDBC URL
     * @return True if initialization was successful, false otherwise
     */
    fun initialize(databasePath: String): Boolean {
        // If a custom database is already provided, no need to initialize
        if (customDatabase != null) {
            logger.info("Using custom database connection")
            return true
        }

        try {
            logger.info("Initializing database at: $databasePath")

            // Determine if this is a JDBC URL, SQLite memory URL, or file path
            val jdbcUrl = when {
                databasePath.startsWith("jdbc:") -> {
                    logger.info("Using provided JDBC URL: $databasePath")
                    databasePath
                }
                databasePath.contains("?mode=memory") -> {
                    logger.info("Using in-memory SQLite database: $databasePath")
                    databasePath
                }
                else -> {
                    // For file-based database, ensure parent directories exist
                    val file = File(databasePath)
                    file.parentFile?.mkdirs()

                    logger.info("Using file-based SQLite database at: $databasePath")
                    "jdbc:sqlite:$databasePath"
                }
            }

            // Create a database connection
            database = Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC",
                setupConnection = { connection ->
                    // Use a single statement with `use` to ensure proper cleanup.
                    // PRAGMA journal_mode=WAL returns a result row — if the Statement is not
                    // closed, the open prepared statement causes SQLITE_BUSY ("SQL statements
                    // in progress") when Exposed calls setTransactionIsolation on the same
                    // connection shortly after. Closing the statement via `use` prevents this.
                    connection.createStatement().use { stmt ->
                        // Enable foreign key constraints - critical for data integrity
                        stmt.execute("PRAGMA foreign_keys = ON")
                        // WAL mode allows concurrent reads + write without full file locking.
                        // Returns the active journal mode as a result row — must close statement.
                        stmt.execute("PRAGMA journal_mode=WAL")
                        // Avoid indefinite blocking when another process holds a write lock.
                        stmt.execute("PRAGMA busy_timeout = 5000")
                    }
                }
            )
            TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

            logger.info("Database connection established successfully")
            return true
        } catch (e: Exception) {
            logger.error("Failed to initialize database: ${e.message}", e)
            return false
        }
    }

    /**
     * Applies any needed schema updates using the configured schema manager.
     *
     * @return True if schema was updated successfully, false otherwise
     */
    fun updateSchema(): Boolean {
        try {
            logger.info("Updating database schema...")

            // Ensure database is initialized
            getDatabase()

            // Create schema manager if not already created
            if (!::schemaManager.isInitialized) {
                val useFlyway = DatabaseConfig.useFlyway
                val jdbcUrl = database?.url
                logger.info("Creating schema manager (Flyway: $useFlyway)")
                schemaManager = SchemaManagerFactory.create(useFlyway, jdbcUrl)
            }

            val result = schemaManager.updateSchema()

            if (result) {
                logger.info("Database schema updated successfully")
                checkParentCycleIntegrity()
            } else {
                logger.error("Failed to update database schema")
            }

            return result
        } catch (e: Exception) {
            logger.error("Error updating database schema: ${e.message}", e)
            return false
        }
    }

    /**
     * Runs lightweight SQL queries to detect self-loop and 2-hop mutual cycles in the
     * work_items parent hierarchy. Logs a WARNING for each corrupt item found but does NOT
     * fail startup or delete data — remediation is a data decision, not an infra decision.
     *
     * Called once after schema migrations complete.
     */
    private fun checkParentCycleIntegrity() {
        try {
            transaction(db = getDatabase()) {
                exec("SELECT id FROM work_items WHERE id = parent_id") { rs ->
                    var selfLoopCount = 0
                    while (rs.next()) {
                        val id = rs.getString("id")
                        logger.warn("Data integrity issue: work item '$id' is its own parent (self-loop)")
                        selfLoopCount++
                    }
                    if (selfLoopCount > 0) {
                        logger.warn("Found $selfLoopCount self-loop(s) in work_items. These items have parent_id = id.")
                    }
                }

                exec(
                    """
                    SELECT a.id AS a_id, b.id AS b_id
                    FROM work_items a
                    JOIN work_items b ON a.parent_id = b.id AND b.parent_id = a.id
                    WHERE a.id < b.id
                    """.trimIndent()
                ) { rs ->
                    var mutualCycleCount = 0
                    while (rs.next()) {
                        val aId = rs.getString("a_id")
                        val bId = rs.getString("b_id")
                        logger.warn("Data integrity issue: mutual parent cycle between work items '$aId' and '$bId'")
                        mutualCycleCount++
                    }
                    if (mutualCycleCount > 0) {
                        logger.warn("Found $mutualCycleCount mutual 2-hop cycle(s) in work_items.")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not run parent-cycle integrity check: ${e.message}")
        }
    }

    /**
     * Get the database connection instance.
     *
     * @return The database connection
     * @throws IllegalStateException if the database has not been initialized
     */
    fun getDatabase(): Database {
        return database ?: throw IllegalStateException("Database has not been initialized")
    }

    /**
     * Shuts down the database connection.
     */
    fun shutdown() {
        try {
            logger.info("Shutting down database connection...")

            val db = database
            if (db != null) {
                TransactionManager.closeAndUnregister(db)
                logger.info("Database connection closed and unregistered")
            }
            database = null

            logger.info("Database shutdown complete")
        } catch (e: Exception) {
            logger.error("Error shutting down database", e)
        }
    }
}
