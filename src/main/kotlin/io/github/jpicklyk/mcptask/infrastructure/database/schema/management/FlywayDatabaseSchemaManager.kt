package io.github.jpicklyk.mcptask.infrastructure.database.schema.management

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import java.sql.Connection

/**
 * Flyway-based implementation of DatabaseSchemaManager.
 * Uses Flyway to manage database migrations for versioned schema evolution.
 */
class FlywayDatabaseSchemaManager(private val database: Database) : DatabaseSchemaManager {
    private val logger = LoggerFactory.getLogger(FlywayDatabaseSchemaManager::class.java)

    override fun updateSchema(): Boolean {
        return try {
            logger.info("Starting Flyway database migration...")
            
            transaction(database) {
                // Get JDBC connection from Exposed
                val connection = TransactionManager.current().connection.connection as Connection
                
                // Create a simple DataSource wrapper for the connection
                val dataSource = createDataSourceFromConnection(connection)
                
                // Create Flyway instance with SQLite-specific configuration
                val flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .validateMigrationNaming(true)
                    .cleanDisabled(false) // Allow clean for development
                    .baselineOnMigrate(true) // Create baseline for existing databases
                    .baselineVersion("0") // Start baseline at version 0
                    .load()
                
                // Apply migrations
                val result = flyway.migrate()
                logger.info("Successfully applied ${result.migrationsExecuted} migration(s)")
                logger.info("Current schema version: ${result.targetSchemaVersion ?: "unknown"}")
            }
            
            true
        } catch (e: FlywayException) {
            logger.error("Flyway migration failed: ${e.message}", e)
            false
        } catch (e: Exception) {
            logger.error("Database migration failed: ${e.message}", e)
            false
        }
    }
    
    /**
     * Creates a simple DataSource wrapper around an existing JDBC connection.
     * This allows Flyway to use the same connection that Exposed is using.
     */
    private fun createDataSourceFromConnection(connection: Connection): DataSource {
        return object : DataSource {
            override fun getConnection(): Connection = connection
            override fun getConnection(username: String?, password: String?): Connection = connection
            override fun getLogWriter(): java.io.PrintWriter? = null
            override fun setLogWriter(out: java.io.PrintWriter?) {}
            override fun setLoginTimeout(seconds: Int) {}
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): java.util.logging.Logger = 
                java.util.logging.Logger.getLogger("FlywayDataSource")
            override fun isWrapperFor(iface: Class<*>): Boolean = false
            override fun <T> unwrap(iface: Class<T>): T = 
                throw UnsupportedOperationException("Cannot unwrap DataSource")
        }
    }

    override fun getCurrentVersion(): Int {
        return try {
            transaction(database) {
                // Get JDBC connection from Exposed
                val connection = TransactionManager.current().connection.connection as Connection
                
                // Create a simple DataSource wrapper for the connection
                val dataSource = createDataSourceFromConnection(connection)
                
                // Create Flyway instance with same configuration as updateSchema
                val flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .validateMigrationNaming(true)
                    .cleanDisabled(false)
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                
                // Get info about applied migrations
                val info = flyway.info()
                val current = info.current()
                
                if (current != null) {
                    val version = current.version.version.toIntOrNull() ?: 0
                    logger.debug("Current schema version: $version")
                    version
                } else {
                    logger.debug("No migrations applied yet, returning version 0")
                    0
                }
            }
        } catch (e: FlywayException) {
            logger.error("Failed to get current schema version from Flyway: ${e.message}", e)
            0
        } catch (e: Exception) {
            logger.error("Failed to get current schema version: ${e.message}", e)
            0
        }
    }
}