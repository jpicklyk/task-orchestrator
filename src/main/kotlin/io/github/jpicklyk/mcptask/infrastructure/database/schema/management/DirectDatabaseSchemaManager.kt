package io.github.jpicklyk.mcptask.infrastructure.database.schema.management

import io.github.jpicklyk.mcptask.infrastructure.database.schema.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Simple implementation of DatabaseSchemaManager that creates tables directly.
 * This will be replaced with a Flyway implementation in the future.
 */
class DirectDatabaseSchemaManager(private val database: Database) : DatabaseSchemaManager {
    private val logger = LoggerFactory.getLogger(DirectDatabaseSchemaManager::class.java)

    // All application tables in dependency order
    private val tables = listOf(
        ProjectsTable,
        TemplatesTable,
        FeaturesTable,
        EntityTagsTable,
        SectionsTable,
        TemplateSectionsTable,
        TaskTable,
        DependenciesTable,
        WorkSessionsTable,
        TaskLocksTable,
        EntityAssignmentsTable
    )

    override fun updateSchema(): Boolean {
        logger.info("Creating/updating database schema...")

        return try {
            // First try with the batched approach
            createTablesInBatches() || createAllTablesInSingleTransaction()
        } catch (e: Exception) {
            logger.error("Failed to create/update schema with all methods: ${e.message}", e)
            false
        }
    }

    /**
     * Attempt to create tables in batches to respect dependencies
     */
    private fun createTablesInBatches(): Boolean {
        return try {
            logger.info("Attempting to create tables in batches...")

            // First batch - tables with no dependencies
            val baseTablesSuccess = transaction(database) {
                logger.info("Creating base tables (ProjectsTable, TemplatesTable)...")
                try {
                    SchemaUtils.create(ProjectsTable)
                    SchemaUtils.create(TemplatesTable)
                    logger.info("Base tables created successfully")
                    true
                } catch (e: Exception) {
                    logger.error("Error creating base tables: ${e.message}", e)
                    false
                }
            }

            if (!baseTablesSuccess) {
                logger.error("Failed to create base tables")
                return false
            }

            // Second batch - tables with single dependencies
            val secondaryTablesSuccess = transaction(database) {
                logger.info("Creating tables with single dependencies...")
                try {
                    SchemaUtils.create(FeaturesTable)
                    SchemaUtils.create(EntityTagsTable)
                    SchemaUtils.create(SectionsTable)
                    SchemaUtils.create(TemplateSectionsTable)
                    logger.info("Tables with single dependencies created successfully")
                    true
                } catch (e: Exception) {
                    logger.error("Error creating secondary tables: ${e.message}", e)
                    false
                }
            }

            if (!secondaryTablesSuccess) {
                logger.error("Failed to create secondary tables")
                return false
            }

            // Third batch - tables with multiple dependencies
            val finalTablesSuccess = transaction(database) {
                logger.info("Creating tables with multiple dependencies...")
                try {
                    SchemaUtils.create(TaskTable)
                    SchemaUtils.create(DependenciesTable)  // depends on TaskTable
                    logger.info("Tables with multiple dependencies created successfully")
                    true
                } catch (e: Exception) {
                    logger.error("Error creating final tables: ${e.message}", e)
                    false
                }
            }

            if (!finalTablesSuccess) {
                logger.error("Failed to create final tables")
                return false
            }

            // Fourth batch - locking system tables  
            val lockingTablesSuccess = transaction(database) {
                logger.info("Creating locking system tables...")
                try {
                    SchemaUtils.create(WorkSessionsTable)
                    SchemaUtils.create(TaskLocksTable)  // depends on WorkSessionsTable
                    SchemaUtils.create(EntityAssignmentsTable)  // depends on WorkSessionsTable
                    logger.info("Locking system tables created successfully")
                    true
                } catch (e: Exception) {
                    logger.error("Error creating locking tables: ${e.message}", e)
                    false
                }
            }

            if (!lockingTablesSuccess) {
                logger.error("Failed to create locking tables")
                return false
            }

            true
        } catch (e: Exception) {
            logger.error("Failed to create tables in batches: ${e.message}", e)
            false
        }
    }

    /**
     * Fallback method to create all tables in a single transaction
     */
    private fun createAllTablesInSingleTransaction(): Boolean {
        return try {
            logger.info("Attempting to create all tables in a single transaction...")

            val success = transaction(database) {
                try {
                    SchemaUtils.create(*tables.toTypedArray())
                    true
                } catch (e: Exception) {
                    logger.error("Error creating all tables in single transaction: ${e.message}", e)
                    false
                }
            }

            // Verify all tables were created
            val allCreated = verifyTablesCreated()
            if (allCreated) {
                logger.info("All tables created successfully using single transaction approach")
            } else {
                logger.error("Not all tables were created using single transaction approach")
            }

            allCreated
        } catch (e: Exception) {
            logger.error("Failed to create tables in single transaction: ${e.message}", e)
            false
        }
    }

    /**
     * Verify that all tables were actually created in the database
     * NOTE: THIS FOR SOME REASON DOES NOT WORK!
     */
    private fun verifyTablesCreated(): Boolean {
        val tableNames = tables.map { it.tableName }
        var allTablesCreated = true

        try {
            // Use transaction to check if tables exist
            transaction(database) {
                // Get all tables in the database using SchemaUtils
                val existingTables = SchemaUtils.listTables()

                // Log all existing tables for debugging
                logger.info("Existing tables in database: ${existingTables.joinToString()}")

                // Check each expected table
                for (tableName in tableNames) {
                    val exists = existingTables.any { it.equals(tableName, ignoreCase = true) }

                    if (!exists) {
                        logger.warn("Table $tableName was not created!")
                        allTablesCreated = false
                    } else {
                        logger.info("Table $tableName was created successfully")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error verifying tables: ${e.message}", e)
            return false
        }

        return allTablesCreated
    }

    override fun getCurrentVersion(): Int {
        // Simple implementation - always returns version 1
        // Flyway implementation will return the actual version
        return 1
    }
}