package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.slf4j.LoggerFactory

/**
 * Production mode schema manager that applies versioned Flyway migrations.
 * Migrations are loaded from classpath:db/migration/.
 *
 * @param jdbcUrl The JDBC URL for the database connection.
 */
class FlywayDatabaseSchemaManager(private val jdbcUrl: String) : DatabaseSchemaManager {
    private val logger = LoggerFactory.getLogger(FlywayDatabaseSchemaManager::class.java)

    override fun updateSchema(): Boolean {
        return try {
            // Check if repair mode is requested
            val shouldRepair = System.getenv("FLYWAY_REPAIR")?.toBoolean() ?: false

            if (shouldRepair) {
                logger.info("FLYWAY_REPAIR=true detected, running repair...")
                return repair()
            }

            logger.info("Starting Flyway database migration...")
            logger.info("Using database URL for Flyway: $jdbcUrl")

            // Create Flyway instance with SQLite-specific configuration
            val flyway = Flyway.configure()
                .dataSource(jdbcUrl, null, null) // SQLite: no username/password needed
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
     * Repairs the Flyway schema history table by updating checksums
     * to match current migration files. Useful when migration files
     * have been modified after being applied.
     */
    private fun repair(): Boolean {
        return try {
            logger.info("Starting Flyway repair...")

            val flyway = Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(false)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()

            flyway.repair()
            logger.info("Flyway repair completed successfully")

            true
        } catch (e: FlywayException) {
            logger.error("Flyway repair failed: ${e.message}", e)
            false
        }
    }
}
