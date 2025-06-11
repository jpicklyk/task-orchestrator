package io.github.jpicklyk.mcptask.infrastructure.database

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.DatabaseSchemaManager
import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.SchemaManagerFactory
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection

/**
 * Manages database connections and schema management for the MCP Task Orchestrator.
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

                    // Construct JDBC URL from file path
                    logger.info("Using file-based SQLite database at: $databasePath")
                    "jdbc:sqlite:$databasePath"
                }
            }

            // Create a database connection
            database = Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC",
                setupConnection = { connection ->
                    // Enable foreign key constraints - critical for data integrity
                    connection.createStatement().execute("PRAGMA foreign_keys = ON")
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
     * Applies any needed schema updates.
     * This method will be used by both the direct and Flyway implementations.
     */
    fun updateSchema(): Boolean {
        try {
            logger.info("Updating database schema...")

            val db = getDatabase()

            // Create schema manager if not already created
            if (!::schemaManager.isInitialized) {
                val useFlyway = DatabaseConfig.useFlyway
                logger.info("Creating schema manager with Flyway support: $useFlyway")
                schemaManager = SchemaManagerFactory.createSchemaManager(db, useFlyway = useFlyway)
            }

            // Update schema using the manager
            val result = schemaManager.updateSchema()

            if (result) {
                logger.info("Database schema updated successfully to version ${schemaManager.getCurrentVersion()}")
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

            // Release database reference
            database = null

            logger.info("Database connection released")
        } catch (e: Exception) {
            logger.error("Error shutting down database", e)
        }
    }
}