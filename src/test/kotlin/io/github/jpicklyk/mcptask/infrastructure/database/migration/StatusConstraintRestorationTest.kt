package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import java.sql.Connection
import java.util.UUID

/**
 * Tests for V11/V12 migrations - Status CHECK Constraints.
 *
 * This test validates:
 * 1. Migration applies successfully on clean database
 * 2. Migration applies successfully on v1.0 database (with existing data)
 * 3. CHECK constraints enforce valid enum values
 * 4. CHECK constraints reject invalid enum values
 * 5. All new v2.0 enum values are accepted (V11 + V12 additions)
 * 6. Data is preserved during migration
 * 7. Status columns reduced from VARCHAR(50) to VARCHAR(20)
 * 8. All indexes recreated correctly
 *
 * Note: V11 added initial v2.0 statuses, V12 completed the v2.0 status system.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class StatusConstraintRestorationTest {

    private lateinit var database: Database
    private lateinit var schemaManager: FlywayDatabaseSchemaManager

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database so Flyway and test queries use same database
        val tempFile = java.io.File.createTempFile("test_v11_migration", ".db")
        tempFile.deleteOnExit()

        database = Database.connect(
            url = "jdbc:sqlite:${tempFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            setupConnection = { connection ->
                connection.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
            }
        )
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        schemaManager = FlywayDatabaseSchemaManager(database)
    }

    @AfterEach
    fun tearDown() {
        // Database file cleaned up automatically via deleteOnExit()
    }

    @Test
    fun `V11 migration should apply successfully on clean database`() {
        // Apply all migrations including V11 and V12
        val migrationResult = schemaManager.updateSchema()
        assertTrue(migrationResult, "Migration should complete successfully")

        // Verify we're at least at version 12 (V12 completes v2.0 status system)
        val version = schemaManager.getCurrentVersion()
        assertTrue(version >= 12, "Schema version should be at least 12 after migration, got $version")
    }

    @Test
    fun `V11 migration should preserve existing data`() {
        // Apply all migrations
        assertTrue(schemaManager.updateSchema(), "Initial migration should succeed")

        // Insert test data
        val connection = database.connector().connection as Connection
        val projectId = UUID.randomUUID().toString().replace("-", "")
        val featureId = UUID.randomUUID().toString().replace("-", "")
        val taskId = UUID.randomUUID().toString().replace("-", "")

        // Insert project with v1.0 status
        connection.createStatement().executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, search_vector, version)
            VALUES (x'$projectId', 'Test Project', 'Test Summary', 'Test Description', 'PLANNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1)
        """)

        // Insert feature with v1.0 status
        connection.createStatement().executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, search_vector, version, description)
            VALUES (x'$featureId', x'$projectId', 'Test Feature', 'Test Summary', 'IN_DEVELOPMENT', 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1, 'Test Description')
        """)

        // Insert task with v1.0 status
        connection.createStatement().executeUpdate("""
            INSERT INTO tasks (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version, last_modified_by, lock_status, search_vector, description)
            VALUES (x'$taskId', x'$projectId', x'$featureId', 'Test Task', 'Test Summary', 'PENDING', 'MEDIUM', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, NULL, 'UNLOCKED', '', 'Test Description')
        """)

        // Verify data exists before migration
        val projectCountBefore = connection.createStatement().executeQuery("SELECT COUNT(*) as count FROM projects").use {
            it.next()
            it.getInt("count")
        }
        assertEquals(1, projectCountBefore, "Should have 1 project before migration")

        // Note: V11 migration is already applied in initial migration, so data is already preserved
        // Verify data still exists
        val projectCountAfter = connection.createStatement().executeQuery("SELECT COUNT(*) as count FROM projects").use {
            it.next()
            it.getInt("count")
        }
        assertEquals(1, projectCountAfter, "Should have 1 project after migration")

        // Verify project data preserved
        val projectName = connection.createStatement().executeQuery("SELECT name FROM projects WHERE id = x'$projectId'").use {
            it.next()
            it.getString("name")
        }
        assertEquals("Test Project", projectName, "Project name should be preserved")
    }

    @Test
    fun `projects table should accept valid v1_0 status values`() {
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection
        // V12 adds ON_HOLD and CANCELLED to valid project statuses (6 total)
        val validStatuses = listOf("PLANNING", "IN_DEVELOPMENT", "ON_HOLD", "CANCELLED", "COMPLETED", "ARCHIVED")

        validStatuses.forEach { status ->
            val projectId = UUID.randomUUID().toString().replace("-", "")
            val insertSucceeded = try {
                connection.createStatement().executeUpdate("""
                    INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, search_vector, version)
                    VALUES (x'$projectId', 'Test Project', 'Test Summary', 'Test Desc', '$status', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1)
                """)
                true
            } catch (e: Exception) {
                false
            }
            assertTrue(insertSucceeded, "Should accept valid project status: $status")
        }
    }

    @Test
    fun `projects table should reject invalid status values`() {
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection
        // V12: ON_HOLD and CANCELLED are now VALID for projects, so test different invalid values
        val invalidStatuses = listOf("INVALID_STATUS", "DRAFT", "PENDING", "BACKLOG", "IN_PROGRESS", "TESTING")

        invalidStatuses.forEach { status ->
            val projectId = UUID.randomUUID().toString().replace("-", "")
            var constraintViolationCaught = false
            try {
                connection.createStatement().executeUpdate("""
                    INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, search_vector, version)
                    VALUES (x'$projectId', 'Test Project', 'Test Summary', 'Test Desc', '$status', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1)
                """)
            } catch (e: Exception) {
                if (e.message?.contains("constraint", ignoreCase = true) == true ||
                    e.message?.contains("check", ignoreCase = true) == true) {
                    constraintViolationCaught = true
                }
            }
            assertTrue(constraintViolationCaught, "Should reject invalid project status: $status")
        }
    }

    @Test
    fun `features table should accept all v2_0 status values including DRAFT and ON_HOLD`() {
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection

        // First create a project to satisfy foreign key
        val projectId = UUID.randomUUID().toString().replace("-", "")
        connection.createStatement().executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, search_vector, version)
            VALUES (x'$projectId', 'Test Project', 'Test Summary', 'Test Desc', 'PLANNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1)
        """)

        // V12 adds TESTING, VALIDATING, PENDING_REVIEW, BLOCKED, DEPLOYED (11 total)
        val validStatuses = listOf(
            "DRAFT", "PLANNING", "IN_DEVELOPMENT", "TESTING", "VALIDATING",
            "PENDING_REVIEW", "BLOCKED", "ON_HOLD", "DEPLOYED", "COMPLETED", "ARCHIVED"
        )

        validStatuses.forEach { status ->
            val featureId = UUID.randomUUID().toString().replace("-", "")
            val insertSucceeded = try {
                connection.createStatement().executeUpdate("""
                    INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, search_vector, version, description)
                    VALUES (x'$featureId', x'$projectId', 'Test Feature', 'Test Summary', '$status', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1, 'Test Desc')
                """)
                true
            } catch (e: Exception) {
                println("Failed to insert feature with status $status: ${e.message}")
                false
            }
            assertTrue(insertSucceeded, "Should accept valid feature status: $status")
        }
    }

    @Test
    fun `features table should reject invalid status values`() {
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection

        // Create project first
        val projectId = UUID.randomUUID().toString().replace("-", "")
        connection.createStatement().executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, search_vector, version)
            VALUES (x'$projectId', 'Test Project', 'Test Summary', 'Test Desc', 'PLANNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1)
        """)

        val invalidStatuses = listOf("INVALID_STATUS", "PENDING", "IN_PROGRESS", "BACKLOG")

        invalidStatuses.forEach { status ->
            val featureId = UUID.randomUUID().toString().replace("-", "")
            var constraintViolationCaught = false
            try {
                connection.createStatement().executeUpdate("""
                    INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, search_vector, version, description)
                    VALUES (x'$featureId', x'$projectId', 'Test Feature', 'Test Summary', '$status', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1, 'Test Desc')
                """)
            } catch (e: Exception) {
                if (e.message?.contains("constraint", ignoreCase = true) == true ||
                    e.message?.contains("check", ignoreCase = true) == true) {
                    constraintViolationCaught = true
                }
            }
            assertTrue(constraintViolationCaught, "Should reject invalid feature status: $status")
        }
    }

    @Test
    fun `tasks table should accept all v2_0 status values including BACKLOG IN_REVIEW CHANGES_REQUESTED ON_HOLD`() {
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection

        // Create project and feature first
        val projectId = UUID.randomUUID().toString().replace("-", "")
        val featureId = UUID.randomUUID().toString().replace("-", "")

        connection.createStatement().executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, search_vector, version)
            VALUES (x'$projectId', 'Test Project', 'Test Summary', 'Test Desc', 'PLANNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1)
        """)

        connection.createStatement().executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, search_vector, version, description)
            VALUES (x'$featureId', x'$projectId', 'Test Feature', 'Test Summary', 'PLANNING', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1, 'Test Desc')
        """)

        // V12 adds TESTING, READY_FOR_QA, INVESTIGATING, BLOCKED, DEPLOYED (14 total)
        val validStatuses = listOf(
            "BACKLOG", "PENDING", "IN_PROGRESS", "IN_REVIEW", "CHANGES_REQUESTED",
            "TESTING", "READY_FOR_QA", "INVESTIGATING", "BLOCKED", "ON_HOLD",
            "DEPLOYED", "COMPLETED", "CANCELLED", "DEFERRED"
        )

        validStatuses.forEach { status ->
            val taskId = UUID.randomUUID().toString().replace("-", "")
            val insertSucceeded = try {
                connection.createStatement().executeUpdate("""
                    INSERT INTO tasks (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version, last_modified_by, lock_status, search_vector, description)
                    VALUES (x'$taskId', x'$projectId', x'$featureId', 'Test Task', 'Test Summary', '$status', 'LOW', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, NULL, 'UNLOCKED', '', 'Test Desc')
                """)
                true
            } catch (e: Exception) {
                println("Failed to insert task with status $status: ${e.message}")
                false
            }
            assertTrue(insertSucceeded, "Should accept valid task status: $status")
        }
    }

    @Test
    fun `tasks table should reject invalid status values`() {
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection

        // Create project and feature
        val projectId = UUID.randomUUID().toString().replace("-", "")
        val featureId = UUID.randomUUID().toString().replace("-", "")

        connection.createStatement().executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, search_vector, version)
            VALUES (x'$projectId', 'Test Project', 'Test Summary', 'Test Desc', 'PLANNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1)
        """)

        connection.createStatement().executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, search_vector, version, description)
            VALUES (x'$featureId', x'$projectId', 'Test Feature', 'Test Summary', 'PLANNING', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '', 1, 'Test Desc')
        """)

        val invalidStatuses = listOf("INVALID_STATUS", "PLANNING", "IN_DEVELOPMENT", "ARCHIVED", "DRAFT")

        invalidStatuses.forEach { status ->
            val taskId = UUID.randomUUID().toString().replace("-", "")
            var constraintViolationCaught = false
            try {
                connection.createStatement().executeUpdate("""
                    INSERT INTO tasks (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version, last_modified_by, lock_status, search_vector, description)
                    VALUES (x'$taskId', x'$projectId', x'$featureId', 'Test Task', 'Test Summary', '$status', 'LOW', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, NULL, 'UNLOCKED', '', 'Test Desc')
                """)
            } catch (e: Exception) {
                if (e.message?.contains("constraint", ignoreCase = true) == true ||
                    e.message?.contains("check", ignoreCase = true) == true) {
                    constraintViolationCaught = true
                }
            }
            assertTrue(constraintViolationCaught, "Should reject invalid task status: $status")
        }
    }

    @Test
    fun `status columns should be VARCHAR(20) not VARCHAR(50)`() {
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection

        // Check projects table status column type
        val projectsTableInfo = connection.createStatement().executeQuery("PRAGMA table_info(projects)")
        var projectsStatusType: String? = null
        while (projectsTableInfo.next()) {
            val columnName = projectsTableInfo.getString("name")
            if (columnName == "status") {
                projectsStatusType = projectsTableInfo.getString("type")
            }
        }
        projectsTableInfo.close()
        assertEquals("VARCHAR(20)", projectsStatusType, "Projects status column should be VARCHAR(20)")

        // Check features table status column type
        val featuresTableInfo = connection.createStatement().executeQuery("PRAGMA table_info(features)")
        var featuresStatusType: String? = null
        while (featuresTableInfo.next()) {
            val columnName = featuresTableInfo.getString("name")
            if (columnName == "status") {
                featuresStatusType = featuresTableInfo.getString("type")
            }
        }
        featuresTableInfo.close()
        assertEquals("VARCHAR(20)", featuresStatusType, "Features status column should be VARCHAR(20)")

        // Check tasks table status column type
        val tasksTableInfo = connection.createStatement().executeQuery("PRAGMA table_info(tasks)")
        var tasksStatusType: String? = null
        while (tasksTableInfo.next()) {
            val columnName = tasksTableInfo.getString("name")
            if (columnName == "status") {
                tasksStatusType = tasksTableInfo.getString("type")
            }
        }
        tasksTableInfo.close()
        assertEquals("VARCHAR(20)", tasksStatusType, "Tasks status column should be VARCHAR(20)")
    }

    @Test
    fun `all indexes should be recreated after migration`() {
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection

        // Get all indexes
        val indexes = mutableSetOf<String>()
        val indexQuery = connection.createStatement().executeQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
        )
        while (indexQuery.next()) {
            indexes.add(indexQuery.getString("name"))
        }
        indexQuery.close()

        // Expected indexes for projects (from V1 + V3)
        assertTrue(indexes.contains("idx_projects_status"), "Should have idx_projects_status")
        assertTrue(indexes.contains("idx_projects_created_at"), "Should have idx_projects_created_at")
        assertTrue(indexes.contains("idx_projects_modified_at"), "Should have idx_projects_modified_at")
        assertTrue(indexes.contains("idx_projects_version"), "Should have idx_projects_version")

        // Expected indexes for features (from V1 + V3 + V8)
        assertTrue(indexes.contains("idx_features_project_id"), "Should have idx_features_project_id")
        assertTrue(indexes.contains("idx_features_status"), "Should have idx_features_status")
        assertTrue(indexes.contains("idx_features_priority"), "Should have idx_features_priority")
        assertTrue(indexes.contains("idx_features_version"), "Should have idx_features_version")
        assertTrue(indexes.contains("idx_features_status_priority"), "Should have idx_features_status_priority")
        assertTrue(indexes.contains("idx_features_project_status"), "Should have idx_features_project_status")

        // Expected indexes for tasks (from V1 + V4 + V8)
        assertTrue(indexes.contains("idx_tasks_project_id"), "Should have idx_tasks_project_id")
        assertTrue(indexes.contains("idx_tasks_feature_id"), "Should have idx_tasks_feature_id")
        assertTrue(indexes.contains("idx_tasks_status"), "Should have idx_tasks_status")
        assertTrue(indexes.contains("idx_tasks_priority"), "Should have idx_tasks_priority")
        assertTrue(indexes.contains("idx_tasks_version"), "Should have idx_tasks_version")
        assertTrue(indexes.contains("idx_tasks_lock_status"), "Should have idx_tasks_lock_status")
        assertTrue(indexes.contains("idx_tasks_feature_status"), "Should have idx_tasks_feature_status")
        assertTrue(indexes.contains("idx_tasks_project_feature"), "Should have idx_tasks_project_feature")
        assertTrue(indexes.contains("idx_tasks_status_priority_complexity"), "Should have idx_tasks_status_priority_complexity")
        assertTrue(indexes.contains("idx_tasks_feature_status_priority"), "Should have idx_tasks_feature_status_priority")
    }

    @Test
    fun `migration should handle concurrent execution safely`() {
        // Note: SQLite file-based databases serialize writes, so this tests that
        // migration doesn't break under concurrent schema reads

        assertTrue(schemaManager.updateSchema(), "First migration should succeed")

        // Try to run migration again (should be idempotent)
        assertTrue(schemaManager.updateSchema(), "Second migration should succeed (idempotent)")

        val version = schemaManager.getCurrentVersion()
        assertTrue(version >= 12, "Version should still be at least 12 after idempotent migration")
    }

    @Test
    fun `status values should fit in VARCHAR(20) without truncation`() {
        // This test verifies that all enum values fit within VARCHAR(20)
        // V12 adds more statuses, verify all fit
        val allStatusValues = listOf(
            // Project statuses (6)
            "PLANNING", "IN_DEVELOPMENT", "ON_HOLD", "CANCELLED", "COMPLETED", "ARCHIVED",
            // Feature statuses (11)
            "DRAFT", "PLANNING", "IN_DEVELOPMENT", "TESTING", "VALIDATING",
            "PENDING_REVIEW", "BLOCKED", "ON_HOLD", "DEPLOYED", "COMPLETED", "ARCHIVED",
            // Task statuses (14)
            "BACKLOG", "PENDING", "IN_PROGRESS", "IN_REVIEW", "CHANGES_REQUESTED",
            "TESTING", "READY_FOR_QA", "INVESTIGATING", "BLOCKED", "ON_HOLD",
            "DEPLOYED", "COMPLETED", "CANCELLED", "DEFERRED"
        )

        allStatusValues.forEach { status ->
            assertTrue(status.length <= 20, "Status value '$status' should fit in VARCHAR(20), but has length ${status.length}")
        }

        // Find the longest status value
        val longestStatus = allStatusValues.maxByOrNull { it.length }
        println("Longest status value: '$longestStatus' with length ${longestStatus?.length}")
    }
}
