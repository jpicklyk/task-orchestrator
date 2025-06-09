package io.github.jpicklyk.mcptask.infrastructure.database.schema.management

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class DatabaseSchemaIntegrationTest {
    private val logger = LoggerFactory.getLogger(DatabaseSchemaIntegrationTest::class.java)
    private lateinit var database: Database
    private lateinit var schemaManager: DatabaseSchemaManager

    @BeforeEach
    fun setup() {
        logger.info("Setting up test database")

        database = Database.connect(
            url = "jdbc:h2:mem:tagtest;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )

        schemaManager = DirectDatabaseSchemaManager(database)
        logger.info("Test database initialized")
    }

    @Test
    fun `test schema creation`() {
        // Update schema
        logger.info("Starting schema update...")
        val success = schemaManager.updateSchema()
        logger.info("Schema update completed with result: $success")

        // Verify schema was updated successfully
        assertTrue(success, "Schema update should succeed")

        // Verify schema version
        assertEquals(1, schemaManager.getCurrentVersion(), "Schema should be at version 1")

        // Verify tables were created
        val tableNames = listOf(
            "projects", "features", "tasks", "templates",
            "entity_tags", "sections", "template_sections", "dependencies"
        )

        // Verify that all tables exist
        logger.info("Verifying tables exist...")
        for (tableName in tableNames) {
            val exists = tableExists(tableName)
            logger.info("Table $tableName exists: $exists")
            assertTrue(exists, "$tableName table should exist")
        }

        // Verify that projects table has expected columns
        logger.info("Verifying projects table structure...")
        val projectsColumns = getTableColumns("projects")

        // Check required columns exist
        assertTrue(projectsColumns.contains("id"), "Projects table should have id column")
        assertTrue(projectsColumns.contains("name"), "Projects table should have name column")
        assertTrue(projectsColumns.contains("status"), "Projects table should have status column")
        assertTrue(projectsColumns.contains("created_at"), "Projects table should have created_at column")
        assertTrue(projectsColumns.contains("search_vector"), "Projects table should have search_vector column")

        // Verify that tasks table has expected columns
        logger.info("Verifying tasks table structure...")
        val tasksColumns = getTableColumns("tasks")

        // Check required columns exist
        assertTrue(tasksColumns.contains("id"), "Tasks table should have id column")
        assertTrue(tasksColumns.contains("project_id"), "Tasks table should have project_id column")
        assertTrue(tasksColumns.contains("feature_id"), "Tasks table should have feature_id column")
        assertTrue(tasksColumns.contains("title"), "Tasks table should have title column")
        assertTrue(tasksColumns.contains("status"), "Tasks table should have status column")
        assertTrue(tasksColumns.contains("priority"), "Tasks table should have priority column")
        assertTrue(tasksColumns.contains("complexity"), "Tasks table should have complexity column")
    }

    // Helper method to check if a table exists - Fixed for H2 database
    private fun tableExists(tableName: String): Boolean {
        return try {
            transaction(database) {
                // Use H2-specific INFORMATION_SCHEMA to check table existence
                val result = exec(
                    """
                    SELECT COUNT(*) as table_count 
                    FROM INFORMATION_SCHEMA.TABLES 
                    WHERE UPPER(TABLE_NAME) = UPPER('$tableName')
                """
                ) { rs ->
                    rs.next()
                    rs.getInt("table_count")
                }

                val exists = result != null && result > 0
                logger.info("Checking if table $tableName exists: $exists")
                exists
            }
        } catch (e: Exception) {
            logger.error("Error checking if table $tableName exists", e)
            false
        }
    }

    // Helper method to get column names for a table - Fixed for H2 database
    private fun getTableColumns(tableName: String): List<String> {
        val columns = mutableListOf<String>()

        try {
            transaction(database) {
                // Use H2-specific INFORMATION_SCHEMA to get column information
                exec(
                    """
                    SELECT COLUMN_NAME 
                    FROM INFORMATION_SCHEMA.COLUMNS 
                    WHERE UPPER(TABLE_NAME) = UPPER('$tableName')
                    ORDER BY ORDINAL_POSITION
                """
                ) { rs ->
                    while (rs.next()) {
                        val columnName = rs.getString("COLUMN_NAME").lowercase()
                        columns.add(columnName)
                        logger.info("Table $tableName column: $columnName")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error getting columns for table $tableName", e)
        }

        return columns
    }
}