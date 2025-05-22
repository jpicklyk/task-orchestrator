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
     * @param useFlyway Whether to use Flyway (not yet implemented)
     * @return An instance of a DatabaseSchemaManager
     */
    fun createSchemaManager(database: Database, useFlyway: Boolean = false): DatabaseSchemaManager {
        // Currently only supports DirectDatabaseSchemaManager
        // When you're ready to implement Flyway:
        // 1. Add Flyway dependencies to build.gradle.kts
        // 2. Rename FlywayDatabaseSchemaManager.kt.future to FlywayDatabaseSchemaManager.kt
        // 3. Update this method to return the Flyway implementation when useFlyway is true

        return if (useFlyway) {
            // Will be replaced with FlywayDatabaseSchemaManager in the future:
            // return FlywayDatabaseSchemaManager(database)
            DirectDatabaseSchemaManager(database)
        } else {
            DirectDatabaseSchemaManager(database)
        }
    }
}