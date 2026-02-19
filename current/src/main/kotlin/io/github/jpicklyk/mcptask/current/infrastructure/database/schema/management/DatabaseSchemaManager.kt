package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

/**
 * Manages database schema creation and updates.
 * Implementations provide either direct Exposed ORM schema creation (development)
 * or Flyway-based versioned migrations (production).
 */
interface DatabaseSchemaManager {
    /**
     * Create or update the database schema to the latest version.
     * @return True if schema was created/updated successfully, false otherwise
     */
    fun updateSchema(): Boolean
}
