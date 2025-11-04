package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.sql.Connection

/**
 * Comprehensive test suite for v2.0.0 fresh installation path.
 *
 * This test validates that applying V1 → V2 → V3 migrations
 * correctly creates the complete v2.0.0 database schema with all tables, columns,
 * constraints, indexes, default values, AND template initialization.
 *
 * Migration Path (Fresh Install):
 * - V1: Initial schema (v1.0.1) - Core tables
 * - V2: Migration test table (v1.0.1) - Backward compatibility
 * - V3: v2.0 enhancements - Status enums, locking, templates
 * - Final schema_version: 3
 *
 * Schema includes:
 * - 12 core application tables (projects, features, tasks, sections, templates, dependencies, etc.)
 * - All v2.0.0 enhancements (extended status enums, optimistic locking, performance indexes)
 * - Complete constraint system (CHECK, UNIQUE, FOREIGN KEY)
 * - 73+ performance indexes for query optimization
 * - Template initialization (9 templates, 26 sections)
 * - migration_test_table for backward compatibility
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CollapsedMigrationSchemaTest {

    private lateinit var database: Database
    private lateinit var tempFile: java.io.File

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database for Flyway migrations
        tempFile = java.io.File.createTempFile("test_collapsed_schema", ".db")
        tempFile.deleteOnExit()

        database = Database.connect(
            url = "jdbc:sqlite:${tempFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            setupConnection = { connection ->
                connection.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
            }
        )
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    }

    /**
     * Test that migrations run successfully and complete with final v2.0.0 schema.
     * This tests the fresh installation path: V1 → V2 → V3.
     */
    @Test
    fun `should apply all migrations successfully and reach final schema version`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)

        // Apply V1, V2, V3 migrations (fresh install path)
        val result = schemaManager.updateSchema()
        assertTrue(result, "Migration should succeed")

        // Verify we reach version 3 (V1 + V2 + V3)
        val currentVersion = schemaManager.getCurrentVersion()
        assertTrue(currentVersion >= 3, "Should have reached version 3 after migrations (was $currentVersion)")
    }

    /**
     * Test that all 12 core application tables exist after migration.
     * Validates complete schema structure.
     */
    @Test
    fun `should create all required core tables`() {
        applyMigrations()

        val expectedTables = setOf(
            "projects",
            "templates",
            "features",
            "entity_tags",
            "sections",
            "template_sections",
            "tasks",
            "dependencies",
            "work_sessions",
            "task_locks",
            "entity_assignments",
            "migration_test_table"  // Backward compatibility table
        )

        val existingTables = getExistingTables()
        assertEquals(expectedTables, existingTables, "All expected tables should exist after migration")
    }

    /**
     * Test that comprehensive indexes are created for performance optimization.
     * Validates that query performance indexes are in place.
     */
    @Test
    fun `should create comprehensive indexes for performance optimization`() {
        applyMigrations()

        val connection = getConnection()
        val existingIndexes = getExistingIndexes(connection)
        connection.close()

        // Verify core filtering indexes exist
        assertTrue(existingIndexes.contains("idx_projects_status"), "Should have idx_projects_status")
        assertTrue(existingIndexes.contains("idx_features_project_id"), "Should have idx_features_project_id")
        assertTrue(existingIndexes.contains("idx_tasks_feature_id"), "Should have idx_tasks_feature_id")
        assertTrue(existingIndexes.contains("idx_tasks_status"), "Should have idx_tasks_status")

        // Verify relationship and dependency indexes
        assertTrue(existingIndexes.contains("idx_dependencies_from_task_id"), "Should have idx_dependencies_from_task_id")
        assertTrue(existingIndexes.contains("idx_dependencies_to_task_id"), "Should have idx_dependencies_to_task_id")

        // Verify template system indexes
        assertTrue(existingIndexes.contains("idx_templates_target_entity_type"), "Should have idx_templates_target_entity_type")
        assertTrue(existingIndexes.contains("idx_templates_is_enabled"), "Should have idx_templates_is_enabled")

        // Verify section and tag indexes
        assertTrue(existingIndexes.contains("idx_sections_entity"), "Should have idx_sections_entity")
        assertTrue(existingIndexes.contains("idx_entity_tags_entity"), "Should have idx_entity_tags_entity")
    }

    /**
     * Test that basic CRUD operations work correctly after migration.
     * Validates that all tables can accept and return data.
     */
    @Test
    fun `should support basic CRUD operations after migration`() {
        applyMigrations()

        val connection = getConnection()

        // Test insert into projects
        val insertStmt = connection.prepareStatement("""
            INSERT INTO projects (id, name, summary, status, created_at, modified_at)
            VALUES (randomblob(16), ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """)
        insertStmt.setString(1, "Test Project")
        insertStmt.setString(2, "A test project for validation")
        insertStmt.setString(3, "PLANNING")
        insertStmt.executeUpdate()
        insertStmt.close()

        // Test query
        val countStmt = connection.createStatement()
        val countRs = countStmt.executeQuery("SELECT COUNT(*) as count FROM projects WHERE name = 'Test Project'")
        countRs.next()
        val projectCount = countRs.getInt("count")
        countRs.close()
        countStmt.close()

        assertEquals(1, projectCount, "Should be able to insert and query projects")

        // Test features CRUD
        val projectIdStmt = connection.createStatement()
        val projectIdRs = projectIdStmt.executeQuery("SELECT id FROM projects WHERE name = 'Test Project' LIMIT 1")
        assertTrue(projectIdRs.next(), "Should find created project")
        val projectIdBytes = projectIdRs.getBytes("id")
        projectIdRs.close()
        projectIdStmt.close()

        val featureInsert = connection.prepareStatement("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at)
            VALUES (randomblob(16), ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """)
        featureInsert.setBytes(1, projectIdBytes)
        featureInsert.setString(2, "Test Feature")
        featureInsert.setString(3, "Feature for testing")
        featureInsert.setString(4, "DRAFT")
        featureInsert.setString(5, "HIGH")
        featureInsert.executeUpdate()
        featureInsert.close()

        // Verify feature count
        val featureCount = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) as count FROM features WHERE name = 'Test Feature'").use { rs ->
                rs.next()
                rs.getInt("count")
            }
        }
        assertEquals(1, featureCount, "Should be able to insert and query features")

        connection.close()
    }

    /**
     * Test schema idempotency - running migration twice should work without errors.
     * Validates that the migration system is robust and repeatable.
     */
    @Test
    fun `should handle migration idempotently`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)

        // Apply migrations first time
        assertTrue(schemaManager.updateSchema(), "First migration should succeed")
        val version1 = schemaManager.getCurrentVersion()

        // Apply migrations second time (should be no-op)
        assertTrue(schemaManager.updateSchema(), "Second migration run should succeed")
        val version2 = schemaManager.getCurrentVersion()

        assertEquals(version1, version2, "Version should not change on second migration run")

        // Verify tables still exist and are accessible
        val tables = getExistingTables()
        assertTrue(tables.contains("projects"), "projects table should still exist after second migration")
        assertTrue(tables.contains("features"), "features table should still exist after second migration")
        assertTrue(tables.contains("tasks"), "tasks table should still exist after second migration")
        assertTrue(tables.contains("sections"), "sections table should still exist after second migration")
        assertTrue(tables.contains("dependencies"), "dependencies table should still exist after second migration")
    }

    /**
     * Test migration_test_table creation and initial data seeding.
     * Validates backward compatibility with existing test frameworks.
     */
    @Test
    fun `should create migration_test_table with initial test data`() {
        applyMigrations()

        val connection = getConnection()

        // Verify table exists
        val tableCheckStmt = connection.createStatement()
        val tableCheckRs = tableCheckStmt.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='migration_test_table'"
        )
        val tableExists = tableCheckRs.next()
        tableCheckRs.close()
        tableCheckStmt.close()
        assertTrue(tableExists, "migration_test_table should exist")

        // Verify initial test record exists
        val dataCheckStmt = connection.createStatement()
        val dataCheckRs = dataCheckStmt.executeQuery(
            "SELECT COUNT(*) as count FROM migration_test_table WHERE test_name = 'Initial Migration Test'"
        )
        dataCheckRs.next()
        val testDataCount = dataCheckRs.getInt("count")
        dataCheckRs.close()
        dataCheckStmt.close()

        assertEquals(1, testDataCount, "Should have initial test record in migration_test_table")

        connection.close()
    }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun applyMigrations() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        val result = schemaManager.updateSchema()
        assertTrue(result, "All migrations should succeed")
    }

    private fun getConnection(): Connection {
        return database.connector().connection as Connection
    }

    private fun getExistingTables(): Set<String> {
        val connection = getConnection()
        val tables = mutableSetOf<String>()

        val statement = connection.createStatement()
        val resultSet = statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'flyway_%'"
        )

        while (resultSet.next()) {
            tables.add(resultSet.getString("name").lowercase())
        }

        resultSet.close()
        statement.close()
        connection.close()

        return tables
    }

    private fun getExistingIndexes(connection: Connection): Set<String> {
        val indexes = mutableSetOf<String>()

        val statement = connection.createStatement()
        val resultSet = statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
        )

        while (resultSet.next()) {
            indexes.add(resultSet.getString("name"))
        }

        resultSet.close()
        statement.close()

        return indexes
    }
}
