package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.config.EnvBoolean
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.slf4j.LoggerFactory
import java.sql.DriverManager

/**
 * Production mode schema manager that applies versioned Flyway migrations.
 * Migrations are loaded from classpath:db/migration/.
 *
 * @param jdbcUrl The JDBC URL for the database connection.
 * @param repair Whether to run Flyway repair instead of migrate. Sourced from the FLYWAY_REPAIR
 *   env var via [io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig]; defaults to
 *   reading the env directly (via the shared [EnvBoolean] vocabulary) so standalone construction
 *   keeps the prior behavior.
 */
class FlywayDatabaseSchemaManager(
    private val jdbcUrl: String,
    private val repair: Boolean = EnvBoolean.parse("FLYWAY_REPAIR", System.getenv("FLYWAY_REPAIR"), default = false)
) : DatabaseSchemaManager {
    private val logger = LoggerFactory.getLogger(FlywayDatabaseSchemaManager::class.java)

    /**
     * The single Flyway configuration used by both migrate and repair. `cleanDisabled` is true so a
     * stray `flyway.clean()` can never wipe a production database, and `baselineOnMigrate` stays at
     * Flyway's default (false): a non-empty schema without history is refused by
     * [refuseDirectModeDatabase] before Flyway runs, and baselining at 0 could never be correct
     * because V1 is not idempotent.
     *
     * No migration pattern is ignored at validation. Flyway's default (`*:future`) would validate a database whose
     * history is AHEAD of this binary (for example one a 4.0 binary migrated past V17) and let this process serve
     * and write it; with no ignore patterns, such a database fails validation and startup refuses it (AR-37).
     */
    internal fun flywayConfiguration(): FluentConfiguration =
        Flyway
            .configure()
            .dataSource(jdbcUrl, null, null) // SQLite: no username/password needed
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .ignoreMigrationPatterns(*emptyArray<String>())
            .cleanDisabled(true)

    /**
     * Returns true (after logging one ERROR) when the database already holds user tables but has
     * no `flyway_schema_history` table, i.e. it was created in Direct mode (`USE_FLYWAY=false`).
     * Direct-mode databases are disposable and have no upgrade path; the database is not modified.
     */
    private fun refuseDirectModeDatabase(): Boolean {
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                    val names = mutableListOf<String>()
                    while (rs.next()) names += rs.getString(1)
                    val hasHistory = names.contains("flyway_schema_history")
                    val hasUserTables = names.any { it != "flyway_schema_history" }
                    if (hasUserTables && !hasHistory) {
                        logger.error(
                            "Database was created in Direct mode (USE_FLYWAY=false): it contains tables but no " +
                                "flyway_schema_history. Direct-mode databases are disposable and have no upgrade path, " +
                                "so Flyway will not migrate it. Point DATABASE_PATH at a new file, or restore a " +
                                "Flyway-mode backup."
                        )
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun updateSchema(): Boolean {
        return try {
            if (refuseDirectModeDatabase()) return false

            if (repair) {
                logger.info("FLYWAY_REPAIR=true detected, running repair...")
                return repair()
            }

            logger.info("Starting Flyway database migration...")
            logger.info("Using database URL for Flyway: $jdbcUrl")

            val flyway = flywayConfiguration().load()

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
    private fun repair(): Boolean =
        try {
            logger.info("Starting Flyway repair...")

            flywayConfiguration().load().repair()
            logger.info("Flyway repair completed successfully")

            true
        } catch (e: FlywayException) {
            logger.error("Flyway repair failed: ${e.message}", e)
            false
        }
}
