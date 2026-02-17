package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

/**
 * Factory for creating the appropriate DatabaseSchemaManager based on configuration.
 *
 * - useFlyway=true + jdbcUrl provided: Uses FlywayDatabaseSchemaManager (versioned migrations)
 * - useFlyway=false or no jdbcUrl: Uses DirectDatabaseSchemaManager (Exposed ORM schema creation)
 */
object SchemaManagerFactory {
    /**
     * Creates a schema manager instance based on configuration.
     *
     * @param useFlyway Whether to use Flyway for database migrations
     * @param jdbcUrl JDBC URL required for Flyway mode; ignored for Direct mode
     * @return An instance of DatabaseSchemaManager
     */
    fun create(useFlyway: Boolean, jdbcUrl: String? = null): DatabaseSchemaManager {
        return if (useFlyway && jdbcUrl != null) {
            FlywayDatabaseSchemaManager(jdbcUrl)
        } else {
            DirectDatabaseSchemaManager()
        }
    }
}
