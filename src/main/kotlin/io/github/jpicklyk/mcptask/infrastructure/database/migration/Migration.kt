package io.github.jpicklyk.mcptask.infrastructure.database.migration

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Interface for database migrations.
 * Each migration should implement this interface to define the changes needed.
 */
interface Migration {
    /**
     * The version number of this migration.
     * Should be unique and incrementally numbered.
     */
    val version: Int

    /**
     * A description of what this migration does.
     */
    val description: String

    /**
     * Tables that will be created by this migration.
     * Used for direct schema creation when bypassing the migration system.
     */
    val tables: List<Table>
        get() = emptyList()

    /**
     * Execute the migration forward (apply the changes).
     */
    fun up(database: Database)

    /**
     * Execute the migration backward (undo the changes).
     * This is optional and can throw UnsupportedOperationException if rollback is not supported.
     */
    fun down(database: Database) {
        throw UnsupportedOperationException("Rollback not supported for migration $version")
    }

    /**
     * Creates all tables defined in this migration.
     * This provides a direct way to create tables without the migration tracking.
     * Used primarily for in-memory testing databases.
     */
    fun createTables(database: Database) {
        if (tables.isEmpty()) {
            throw UnsupportedOperationException("No tables defined for migration $version")
        }

        transaction(database) {
            SchemaUtils.create(*tables.toTypedArray())
        }
    }
}
