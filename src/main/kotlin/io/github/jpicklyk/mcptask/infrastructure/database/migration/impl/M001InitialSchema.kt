package io.github.jpicklyk.mcptask.infrastructure.database.migration.impl

import io.github.jpicklyk.mcptask.infrastructure.database.migration.Migration
import io.github.jpicklyk.mcptask.infrastructure.database.schema.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Initial database schema creation migration.
 * Creates all base tables for the application.
 */
class M001InitialSchema : Migration {
    private val logger = LoggerFactory.getLogger(M001InitialSchema::class.java)
    
    override val version = 1
    override val description = "Initial schema creation"

    override val tables = listOf(
        ProjectsTable,
        TemplatesTable,
        FeaturesTable,
        EntityTagsTable,
        SectionsTable,
        TemplateSectionsTable,
        TaskTable,
        DependenciesTable
    )

    override fun up(database: Database) {
        logger.info("Starting initial schema creation...")

        // Create tables in batches respecting dependencies
        try {
            // First create tables without dependencies
            transaction(database) {
                logger.info("Creating base tables without dependencies...")
                SchemaUtils.create(ProjectsTable)
                SchemaUtils.create(TemplatesTable)
            }

            // Then create tables with single dependencies
            transaction(database) {
                logger.info("Creating tables with single dependencies...")
                SchemaUtils.create(FeaturesTable)  // depends on ProjectsTable
                SchemaUtils.create(EntityTagsTable)
                SchemaUtils.create(SectionsTable)
                SchemaUtils.create(TemplateSectionsTable)  // depends on TemplatesTable
            }

            // Finally create tables with multiple dependencies
            transaction(database) {
                logger.info("Creating tables with multiple dependencies...")
                SchemaUtils.create(TaskTable)  // depends on ProjectsTable and FeaturesTable
                SchemaUtils.create(DependenciesTable)  // depends on TaskTable
            }

            logger.info("All tables created successfully")
        } catch (e: Exception) {
            logger.error("Error creating tables: ${e.message}")

            // Attempt to create all tables in a single transaction as a fallback
            try {
                transaction(database) {
                    logger.info("Attempting to create all tables in a single transaction...")
                    SchemaUtils.create(*tables.toTypedArray())
                    logger.info("Tables created successfully in a single transaction")
                }
            } catch (e2: Exception) {
                logger.error("Failed to create tables even with fallback method: ${e2.message}")
                throw e2
            }
        }
    }
}