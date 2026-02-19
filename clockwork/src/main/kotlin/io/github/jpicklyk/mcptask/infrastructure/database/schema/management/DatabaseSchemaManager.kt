package io.github.jpicklyk.mcptask.infrastructure.database.schema.management

/**
 * Manages database schema creation and updates.
 * Designed to be replaceable with a Flyway-based implementation in the future.
 */
interface DatabaseSchemaManager {
    /**
     * Create or update the database schema to the latest version.
     * @return True if schema was created/updated successfully, false otherwise
     */
    fun updateSchema(): Boolean

    /**
     * Get the current schema version.
     * @return The current version of the database schema
     */
    fun getCurrentVersion(): Int
}
