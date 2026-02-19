package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.DependenciesTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.NotesTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.RoleTransitionsTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Development mode schema manager that creates tables directly via Exposed ORM.
 * Tables are created in foreign-key dependency order.
 *
 * Table dependency graph:
 * 1. WorkItemsTable (no external dependencies, self-referencing parentId)
 * 2. NotesTable (depends on WorkItemsTable)
 * 3. DependenciesTable (depends on WorkItemsTable)
 * 4. RoleTransitionsTable (depends on WorkItemsTable)
 */
class DirectDatabaseSchemaManager : DatabaseSchemaManager {
    private val logger = LoggerFactory.getLogger(DirectDatabaseSchemaManager::class.java)

    // Tables in foreign-key dependency order
    private val tables = arrayOf(
        WorkItemsTable,
        NotesTable,
        DependenciesTable,
        RoleTransitionsTable
    )

    override fun updateSchema(): Boolean {
        return try {
            logger.info("Creating/updating database schema via Direct mode...")

            transaction {
                SchemaUtils.create(*tables)
            }

            logger.info("Database schema created/updated successfully via Direct mode")
            true
        } catch (e: Exception) {
            logger.error("Failed to create/update schema: ${e.message}", e)
            false
        }
    }
}
