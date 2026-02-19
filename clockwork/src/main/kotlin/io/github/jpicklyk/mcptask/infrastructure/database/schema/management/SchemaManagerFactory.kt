package io.github.jpicklyk.mcptask.infrastructure.database.schema.management

import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Factory for creating the appropriate DatabaseSchemaManager.
 * This will allow for a smooth transition to Flyway in the future.
 */
object SchemaManagerFactory {
    /**
     * Creates a schema manager instance based on configuration.
     *
     * @param database The database connection
     * @param useFlyway Whether to use Flyway for database migrations
     * @return An instance of a DatabaseSchemaManager
     * 
     * Configuration:
     * - useFlyway=false: Uses DirectDatabaseSchemaManager (legacy, backward compatible)
     * - useFlyway=true: Uses FlywayDatabaseSchemaManager (versioned migrations)
     * 
     * Environment variable: USE_FLYWAY=true to enable Flyway migrations
     */
    fun createSchemaManager(database: Database, useFlyway: Boolean = false): DatabaseSchemaManager {
        return if (useFlyway) {
            FlywayDatabaseSchemaManager(database)
        } else {
            DirectDatabaseSchemaManager(database)
        }
    }
}