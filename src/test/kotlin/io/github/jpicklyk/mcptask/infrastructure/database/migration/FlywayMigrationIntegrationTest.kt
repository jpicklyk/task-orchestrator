package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.SchemaManagerFactory
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import java.sql.Connection

/**
 * Integration tests for Flyway database migration functionality.
 * Tests the complete migration pipeline including schema creation,
 * test table validation, and migration framework functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class FlywayMigrationIntegrationTest {

    private lateinit var database: Database
    private lateinit var migrationFramework: FlywayMigrationTestFramework
    private lateinit var schemaManager: FlywayDatabaseSchemaManager

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database so Flyway and test queries use same database
        val tempFile = java.io.File.createTempFile("test_migration_integration", ".db")
        tempFile.deleteOnExit()
        
        database = Database.connect(
            url = "jdbc:sqlite:${tempFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            setupConnection = { connection ->
                connection.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
            }
        )
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // Create schema manager and test framework
        schemaManager = FlywayDatabaseSchemaManager(database)
        migrationFramework = FlywayMigrationTestFramework(database)
    }

    @AfterEach
    fun tearDown() {
        // Database is automatically cleaned up since it's in-memory
    }

    @Test
    fun `test Flyway schema manager creation`() {
        // Test that FlywayDatabaseSchemaManager can be created
        assertNotNull(schemaManager)
        
        // Test that it's specifically a FlywayDatabaseSchemaManager
        assertTrue(schemaManager is FlywayDatabaseSchemaManager)
    }

    @Test
    fun `test schema manager factory creates Flyway manager when requested`() {
        val flywayManager = SchemaManagerFactory.createSchemaManager(database, useFlyway = true)
        assertTrue(flywayManager is FlywayDatabaseSchemaManager)

        val directManager = SchemaManagerFactory.createSchemaManager(database, useFlyway = false)
        assertTrue(directManager is io.github.jpicklyk.mcptask.infrastructure.database.schema.management.DirectDatabaseSchemaManager)
    }

    @Test
    fun `test complete migration process`() {
        // Run the complete migration
        val migrationResult = schemaManager.updateSchema()
        assertTrue(migrationResult, "Migration should complete successfully")

        // Validate schema version
        val version = schemaManager.getCurrentVersion()
        assertTrue(version >= 1, "Schema version should be at least 1 after migration")
    }

    @Test
    fun `test migration framework validation suite`() {
        // First apply migrations
        val migrationResult = schemaManager.updateSchema()
        assertTrue(migrationResult, "Migration must succeed before validation")

        // Run comprehensive validation
        val validationResult = migrationFramework.runFullValidationSuite()
        assertTrue(validationResult, "All migration validation tests should pass")
    }

    @Test
    fun `test core tables exist after migration`() {
        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Validate core tables
        assertTrue(migrationFramework.validateCoreTablesExist(), "All core application tables should exist")
    }

    @Test
    fun `test migration test table functionality`() {
        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Validate migration test table
        assertTrue(migrationFramework.validateMigrationTestTable(), "Migration test table should exist with test data")

        // Test operations on migration test table
        assertTrue(migrationFramework.testMigrationTestTableOperations(), "Should be able to insert/query test table")
    }

    @Test
    fun `test index creation during migration`() {
        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Validate indexes
        assertTrue(migrationFramework.validateIndexes(), "All expected indexes should be created")
    }

    @Test
    fun `test multiple migration runs are idempotent`() {
        // Apply migrations multiple times
        assertTrue(schemaManager.updateSchema(), "First migration should succeed")
        val firstVersion = schemaManager.getCurrentVersion()

        assertTrue(schemaManager.updateSchema(), "Second migration should succeed")
        val secondVersion = schemaManager.getCurrentVersion()

        assertTrue(schemaManager.updateSchema(), "Third migration should succeed")
        val thirdVersion = schemaManager.getCurrentVersion()

        // Version should remain consistent
        assertEquals(firstVersion, secondVersion, "Version should not change on second migration")
        assertEquals(secondVersion, thirdVersion, "Version should not change on third migration")

        // Tables should still exist
        assertTrue(migrationFramework.validateCoreTablesExist(), "Tables should still exist after multiple migrations")
    }

    @Test
    fun `test schema version tracking`() {
        // Initial version should be 0 (no migrations applied)
        val initialVersion = schemaManager.getCurrentVersion()
        assertEquals(0, initialVersion, "Initial version should be 0")

        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Version should be updated
        val migratedVersion = schemaManager.getCurrentVersion()
        assertTrue(migratedVersion > initialVersion, "Version should increase after migration")
        assertTrue(migratedVersion >= 2, "Should have at least version 2 (V1 + V2 migrations)")
    }

    @Test
    fun `test migration with foreign key constraints`() {
        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Validate that foreign key constraints are working by testing a constraint violation
        // This should fail due to foreign key constraint
        var constraintViolationCaught = false
        try {
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(database) {
                exec("INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at) VALUES (randomblob(16), randomblob(16), 'Test', 'Test', 'PLANNING', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
            }
        } catch (e: Exception) {
            if (e.message?.contains("foreign key constraint", ignoreCase = true) == true) {
                constraintViolationCaught = true
            }
        }

        assertTrue(constraintViolationCaught, "Foreign key constraints should be enforced")
    }
}