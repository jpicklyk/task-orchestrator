package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import org.slf4j.LoggerFactory

/**
 * Factory for creating the appropriate DatabaseSchemaManager based on configuration.
 *
 * - useFlyway=true + jdbcUrl provided: Uses FlywayDatabaseSchemaManager (versioned migrations)
 * - useFlyway=false or no jdbcUrl: Uses DirectDatabaseSchemaManager (Exposed ORM schema creation)
 */
object SchemaManagerFactory {
    private val logger = LoggerFactory.getLogger(SchemaManagerFactory::class.java)

    /**
     * Creates a schema manager instance based on configuration.
     *
     * @param useFlyway Whether to use Flyway for database migrations
     * @param jdbcUrl JDBC URL required for Flyway mode; ignored for Direct mode
     * @param flywayRepair Whether Flyway should run repair instead of migrate (FLYWAY_REPAIR).
     *   When null, [FlywayDatabaseSchemaManager] falls back to reading the env var itself. Ignored
     *   entirely in Direct mode (useFlyway=false or no jdbcUrl) — logged explicitly below so an
     *   operator who sets FLYWAY_REPAIR=true without USE_FLYWAY=true notices it did nothing.
     * @return An instance of DatabaseSchemaManager
     */
    fun create(
        useFlyway: Boolean,
        jdbcUrl: String? = null,
        flywayRepair: Boolean? = null
    ): DatabaseSchemaManager =
        if (useFlyway && jdbcUrl != null) {
            if (flywayRepair != null) {
                FlywayDatabaseSchemaManager(jdbcUrl, flywayRepair)
            } else {
                FlywayDatabaseSchemaManager(jdbcUrl)
            }
        } else {
            if (flywayRepair == true) {
                logger.warn(
                    "FLYWAY_REPAIR=true is ignored because Flyway is not active (USE_FLYWAY=false or " +
                        "no JDBC URL) — the Direct schema manager will run normally and the process " +
                        "will keep serving instead of exiting."
                )
            }
            DirectDatabaseSchemaManager()
        }
}
