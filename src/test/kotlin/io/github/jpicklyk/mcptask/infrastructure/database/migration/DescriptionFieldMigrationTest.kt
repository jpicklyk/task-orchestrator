package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for V6 and V7 migrations that add and modify the description field.
 *
 * V6: Adds NOT NULL description field and migrates existing summary data
 * V7: Makes description nullable to align with domain model
 *
 * These tests verify:
 * - V6 correctly adds description and migrates data from summary
 * - V7 correctly makes description nullable
 * - Data migration correctness (existing summary → description)
 * - Backward compatibility is maintained
 * - Both fields can be set independently after migration
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DescriptionFieldMigrationTest {

    private lateinit var database: Database
    private lateinit var tempFile: java.io.File

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database for Flyway migrations
        tempFile = java.io.File.createTempFile("test_description_migration", ".db")
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
     * Test that V6 migration successfully adds description field to all three tables.
     */
    @Test
    fun `test V6 migration adds description column to tasks, features, and projects`() {
        // Apply migrations up to V6
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Verify we're at least at version 6
        val currentVersion = schemaManager.getCurrentVersion()
        assertTrue(currentVersion >= 6, "Should have at least version 6 after description migration (was $currentVersion)")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Verify description column exists in all three tables
        val tables = listOf("tasks", "features", "projects")

        tables.forEach { tableName ->
            val columnInfo = statement.executeQuery("PRAGMA table_info($tableName)")
            val columns = mutableListOf<String>()
            while (columnInfo.next()) {
                columns.add(columnInfo.getString("name"))
            }
            columnInfo.close()

            assertTrue(
                "description" in columns,
                "Table $tableName should have description column. Found columns: $columns"
            )
        }

        statement.close()
    }

    /**
     * Test the upgrade scenario: V6 migration copies existing summary to description.
     */
    @Test
    fun `test V6 migration migrates existing summary data to description field`() {
        // Step 1: Apply migrations up to V5 (before description field)
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("5")
            .load()

        flyway.migrate()

        // Step 2: Insert test data with summary values (simulating existing deployment)
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Create test project with summary
        val projectSummary = "Original project summary content"
        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
            VALUES (
                randomblob(16),
                'Test Project',
                '$projectSummary',
                'PLANNING',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Get project ID
        val projectResult = statement.executeQuery("SELECT id FROM projects WHERE name = 'Test Project'")
        projectResult.next()
        val projectIdBytes = projectResult.getBytes("id")
        projectResult.close()

        // Create test feature with summary
        val featureSummary = "Original feature summary content"
        val featureInsert = connection.prepareStatement("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, version)
            VALUES (randomblob(16), ?, 'Test Feature', ?, 'PLANNING', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
        """)
        featureInsert.setBytes(1, projectIdBytes)
        featureInsert.setString(2, featureSummary)
        featureInsert.executeUpdate()
        featureInsert.close()

        // Get feature ID
        val featureResult = statement.executeQuery("SELECT id FROM features WHERE name = 'Test Feature'")
        featureResult.next()
        val featureIdBytes = featureResult.getBytes("id")
        featureResult.close()

        // Create test task with summary
        val taskSummary = "Original task summary content"
        val taskInsert = connection.prepareStatement("""
            INSERT INTO tasks (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version)
            VALUES (randomblob(16), ?, ?, 'Test Task', ?, 'PENDING', 'HIGH', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
        """)
        taskInsert.setBytes(1, projectIdBytes)
        taskInsert.setBytes(2, featureIdBytes)
        taskInsert.setString(3, taskSummary)
        taskInsert.executeUpdate()
        taskInsert.close()

        statement.close()

        // Step 3: Apply V6 migration
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "V6 migration should succeed")

        // Step 4: Verify data was migrated correctly
        val verifyStatement = connection.createStatement()

        // Verify project: description should have original summary, summary should be empty
        val projectCheck = verifyStatement.executeQuery("""
            SELECT description, summary FROM projects WHERE name = 'Test Project'
        """)
        projectCheck.next()
        val projectDescription = projectCheck.getString("description")
        val projectNewSummary = projectCheck.getString("summary")
        projectCheck.close()

        assertEquals(projectSummary, projectDescription, "Project description should contain original summary")
        assertEquals("", projectNewSummary, "Project summary should be cleared after migration")

        // Verify feature: description should have original summary, summary should be empty
        val featureCheck = verifyStatement.executeQuery("""
            SELECT description, summary FROM features WHERE name = 'Test Feature'
        """)
        featureCheck.next()
        val featureDescription = featureCheck.getString("description")
        val featureNewSummary = featureCheck.getString("summary")
        featureCheck.close()

        assertEquals(featureSummary, featureDescription, "Feature description should contain original summary")
        assertEquals("", featureNewSummary, "Feature summary should be cleared after migration")

        // Verify task: description should have original summary, summary should be empty
        val taskCheck = verifyStatement.executeQuery("""
            SELECT description, summary FROM tasks WHERE title = 'Test Task'
        """)
        taskCheck.next()
        val taskDescription = taskCheck.getString("description")
        val taskNewSummary = taskCheck.getString("summary")
        taskCheck.close()

        assertEquals(taskSummary, taskDescription, "Task description should contain original summary")
        assertEquals("", taskNewSummary, "Task summary should be cleared after migration")

        verifyStatement.close()
    }

    /**
     * Test that V7 migration makes description nullable.
     */
    @Test
    fun `test V7 migration makes description nullable`() {
        // Apply all migrations including V7
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Verify we're at least at version 7
        val currentVersion = schemaManager.getCurrentVersion()
        assertTrue(currentVersion >= 7, "Should have at least version 7 after nullable migration (was $currentVersion)")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Insert test data with NULL description to verify it's allowed
        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, version)
            VALUES (
                randomblob(16),
                'Null Description Project',
                '',
                NULL,
                'PLANNING',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Verify NULL description was inserted successfully
        val projectCheck = statement.executeQuery("""
            SELECT description FROM projects WHERE name = 'Null Description Project'
        """)
        assertTrue(projectCheck.next(), "Project should be inserted")
        val description = projectCheck.getString("description")
        // SQLite returns empty string for NULL in some JDBC drivers, or null
        assertTrue(
            description == null || description.isEmpty(),
            "Description should be NULL or empty"
        )
        projectCheck.close()

        // Test feature with NULL description
        val projectIdResult = statement.executeQuery("""
            SELECT id FROM projects WHERE name = 'Null Description Project'
        """)
        projectIdResult.next()
        val projectIdBytes = projectIdResult.getBytes("id")
        projectIdResult.close()

        val featureInsert = connection.prepareStatement("""
            INSERT INTO features (id, project_id, name, summary, description, status, priority, created_at, modified_at, version)
            VALUES (randomblob(16), ?, 'Null Description Feature', '', NULL, 'PLANNING', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
        """)
        featureInsert.setBytes(1, projectIdBytes)
        featureInsert.executeUpdate()
        featureInsert.close()

        // Test task with NULL description
        val featureIdResult = statement.executeQuery("""
            SELECT id FROM features WHERE name = 'Null Description Feature'
        """)
        featureIdResult.next()
        val featureIdBytes = featureIdResult.getBytes("id")
        featureIdResult.close()

        val taskInsert = connection.prepareStatement("""
            INSERT INTO tasks (id, project_id, feature_id, title, summary, description, status, priority, complexity, created_at, modified_at, version)
            VALUES (randomblob(16), ?, ?, 'Null Description Task', '', NULL, 'PENDING', 'HIGH', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
        """)
        taskInsert.setBytes(1, projectIdBytes)
        taskInsert.setBytes(2, featureIdBytes)
        taskInsert.executeUpdate()
        taskInsert.close()

        statement.close()
    }

    /**
     * Test that after V7, both description and summary can be set independently.
     */
    @Test
    fun `test both description and summary can be set independently after migrations`() {
        // Apply all migrations
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Create entities with both description and summary
        val testDescription = "This is what needs to be done"
        val testSummary = "This is what was accomplished"

        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, version)
            VALUES (
                randomblob(16),
                'Both Fields Project',
                '$testSummary',
                '$testDescription',
                'IN_DEVELOPMENT',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Verify both fields are stored correctly
        val projectCheck = statement.executeQuery("""
            SELECT description, summary FROM projects WHERE name = 'Both Fields Project'
        """)
        projectCheck.next()
        val projectDescription = projectCheck.getString("description")
        val projectSummary = projectCheck.getString("summary")
        projectCheck.close()

        assertEquals(testDescription, projectDescription, "Description should be stored correctly")
        assertEquals(testSummary, projectSummary, "Summary should be stored correctly")

        statement.close()
    }

    /**
     * Test that V6 migration preserves existing data integrity (IDs, timestamps, etc.).
     */
    @Test
    fun `test V6 migration preserves data integrity`() {
        // Apply migrations up to V5
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("5")
            .load()

        flyway.migrate()

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Insert task with specific values
        statement.executeUpdate("""
            INSERT INTO tasks (id, title, summary, status, priority, complexity, created_at, modified_at, version)
            VALUES (
                X'550e8400e29b41d4a716446655440000',
                'Integrity Test Task',
                'Original summary for integrity test',
                'IN_PROGRESS',
                'HIGH',
                7,
                '2024-01-15 10:30:00',
                '2024-01-15 15:45:00',
                3
            )
        """)

        statement.close()

        // Apply V6 migration
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "V6 migration should succeed")

        // Verify all original data is preserved
        val verifyStatement = connection.createStatement()
        val taskCheck = verifyStatement.executeQuery("""
            SELECT
                hex(id) as id,
                title,
                status,
                priority,
                complexity,
                created_at,
                modified_at,
                version
            FROM tasks
            WHERE title = 'Integrity Test Task'
        """)

        assertTrue(taskCheck.next(), "Task should still exist after migration")

        assertEquals("550E8400E29B41D4A716446655440000", taskCheck.getString("id"), "ID should be preserved")
        assertEquals("Integrity Test Task", taskCheck.getString("title"), "Title should be preserved")
        assertEquals("IN_PROGRESS", taskCheck.getString("status"), "Status should be preserved")
        assertEquals("HIGH", taskCheck.getString("priority"), "Priority should be preserved")
        assertEquals(7, taskCheck.getInt("complexity"), "Complexity should be preserved")
        assertEquals("2024-01-15 10:30:00", taskCheck.getString("created_at"), "Created timestamp should be preserved")
        assertEquals("2024-01-15 15:45:00", taskCheck.getString("modified_at"), "Modified timestamp should be preserved")
        assertEquals(3, taskCheck.getInt("version"), "Version should be preserved")

        taskCheck.close()
        verifyStatement.close()
    }

    /**
     * Test that migrations are idempotent - running them multiple times doesn't break anything.
     */
    @Test
    fun `test V6 and V7 migrations are idempotent`() {
        // Apply all migrations
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "First migration should succeed")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Insert test data
        statement.executeUpdate("""
            INSERT INTO tasks (id, title, summary, description, status, priority, complexity, created_at, modified_at, version)
            VALUES (
                randomblob(16),
                'Idempotent Test Task',
                'Test summary',
                'Test description',
                'PENDING',
                'MEDIUM',
                5,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        statement.close()

        // Apply migrations again (should be no-op)
        assertTrue(schemaManager.updateSchema(), "Second migration run should succeed")

        // Verify data is unchanged
        val verifyStatement = connection.createStatement()
        val taskCheck = verifyStatement.executeQuery("""
            SELECT title, summary, description FROM tasks WHERE title = 'Idempotent Test Task'
        """)

        assertTrue(taskCheck.next(), "Task should still exist")
        assertEquals("Idempotent Test Task", taskCheck.getString("title"))
        assertEquals("Test summary", taskCheck.getString("summary"))
        assertEquals("Test description", taskCheck.getString("description"))

        taskCheck.close()
        verifyStatement.close()
    }

    /**
     * Test that empty strings in summary are migrated correctly to description.
     */
    @Test
    fun `test V6 migration handles empty summary values correctly`() {
        // Apply migrations up to V5
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("5")
            .load()

        flyway.migrate()

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Insert task with empty summary
        statement.executeUpdate("""
            INSERT INTO tasks (id, title, summary, status, priority, complexity, created_at, modified_at, version)
            VALUES (
                randomblob(16),
                'Empty Summary Task',
                '',
                'PENDING',
                'MEDIUM',
                5,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        statement.close()

        // Apply V6 migration
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "V6 migration should succeed")

        // Verify empty string is migrated to description
        val verifyStatement = connection.createStatement()
        val taskCheck = verifyStatement.executeQuery("""
            SELECT description, summary FROM tasks WHERE title = 'Empty Summary Task'
        """)

        taskCheck.next()
        val description = taskCheck.getString("description")
        val summary = taskCheck.getString("summary")
        taskCheck.close()
        verifyStatement.close()

        // Empty string should be migrated as-is
        assertEquals("", description, "Empty summary should migrate to empty description")
        assertEquals("", summary, "Summary should remain empty after migration")
    }
}
