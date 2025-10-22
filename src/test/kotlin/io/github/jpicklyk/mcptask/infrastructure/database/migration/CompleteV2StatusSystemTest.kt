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
import kotlin.test.assertFalse

/**
 * Comprehensive tests for V12 migration that completes the v2.0 status system.
 *
 * V12: Complete v2.0 Status System Support
 * - Tasks: Add READY_FOR_QA, INVESTIGATING, DEPLOYED, TESTING (4 new)
 * - Features: Add TESTING, VALIDATING, PENDING_REVIEW, BLOCKED, DEPLOYED (5 new)
 * - Projects: Add CANCELLED, make ON_HOLD explicit (1 new + 1 clarification)
 *
 * Total new statuses: 11 (4 task + 5 feature + 2 project)
 *
 * These tests verify:
 * - V12 applies successfully on clean database
 * - V12 applies successfully on V11 database with existing data
 * - All 11 new statuses accepted by CHECK constraints
 * - Invalid statuses rejected with constraint violations
 * - Data preservation during migration (all records intact)
 * - Indexes recreated correctly
 * - Rollback scenario (if feasible)
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CompleteV2StatusSystemTest {

    private lateinit var database: Database
    private lateinit var tempFile: java.io.File

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database for Flyway migrations
        tempFile = java.io.File.createTempFile("test_v12_migration", ".db")
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
     * Test that V12 migration applies successfully on a clean database.
     * This simulates a fresh installation going directly to V12.
     */
    @Test
    fun `test V12 migration applies on clean database`() {
        // Apply all migrations including V12
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Verify we're at least at version 12
        val currentVersion = schemaManager.getCurrentVersion()
        assertTrue(currentVersion >= 12, "Should have at least version 12 after migration (was $currentVersion)")

        // Verify core tables exist
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val tables = listOf("projects", "features", "tasks")
        tables.forEach { tableName ->
            val tableCheck = statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'"
            )
            assertTrue(tableCheck.next(), "Table $tableName should exist")
            tableCheck.close()
        }

        statement.close()
    }

    /**
     * Test that V12 migration applies successfully on existing V11 database with sample data.
     * This simulates an upgrade scenario from V11 to V12.
     */
    @Test
    fun `test V12 migration applies on V11 database with existing data`() {
        // Step 1: Apply migrations up to V11 (before V12)
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("11")
            .load()

        flyway.migrate()

        // Step 2: Insert test data with V11-compatible statuses
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Create test project with V11 status (PLANNING)
        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, version)
            VALUES (
                X'11111111111111111111111111111111',
                'Test Project V11',
                'Test project summary',
                'Test project description',
                'PLANNING',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Create test feature with V11 status (DRAFT)
        statement.executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, description, status, priority, created_at, modified_at, version)
            VALUES (
                X'22222222222222222222222222222222',
                X'11111111111111111111111111111111',
                'Test Feature V11',
                'Test feature summary',
                'Test feature description',
                'DRAFT',
                'MEDIUM',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Create test task with V11 status (BACKLOG)
        statement.executeUpdate("""
            INSERT INTO tasks (id, project_id, feature_id, title, summary, description, status, priority, complexity, created_at, modified_at, version)
            VALUES (
                X'33333333333333333333333333333333',
                X'11111111111111111111111111111111',
                X'22222222222222222222222222222222',
                'Test Task V11',
                'Test task summary',
                'Test task description',
                'BACKLOG',
                'HIGH',
                5,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Count records before migration
        val projectCountBefore = statement.executeQuery("SELECT COUNT(*) as count FROM projects")
        projectCountBefore.next()
        val projectsBeforeMigration = projectCountBefore.getInt("count")
        projectCountBefore.close()

        val featureCountBefore = statement.executeQuery("SELECT COUNT(*) as count FROM features")
        featureCountBefore.next()
        val featuresBeforeMigration = featureCountBefore.getInt("count")
        featureCountBefore.close()

        val taskCountBefore = statement.executeQuery("SELECT COUNT(*) as count FROM tasks")
        taskCountBefore.next()
        val tasksBeforeMigration = taskCountBefore.getInt("count")
        taskCountBefore.close()

        statement.close()

        // Step 3: Apply V12 migration
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "V12 migration should succeed")

        // Step 4: Verify data was preserved
        val verifyStatement = connection.createStatement()

        // Count records after migration
        val projectCountAfter = verifyStatement.executeQuery("SELECT COUNT(*) as count FROM projects")
        projectCountAfter.next()
        val projectsAfterMigration = projectCountAfter.getInt("count")
        projectCountAfter.close()

        val featureCountAfter = verifyStatement.executeQuery("SELECT COUNT(*) as count FROM features")
        featureCountAfter.next()
        val featuresAfterMigration = featureCountAfter.getInt("count")
        featureCountAfter.close()

        val taskCountAfter = verifyStatement.executeQuery("SELECT COUNT(*) as count FROM tasks")
        taskCountAfter.next()
        val tasksAfterMigration = taskCountAfter.getInt("count")
        taskCountAfter.close()

        // Verify record counts match
        assertEquals(projectsBeforeMigration, projectsAfterMigration, "All projects should be preserved")
        assertEquals(featuresBeforeMigration, featuresAfterMigration, "All features should be preserved")
        assertEquals(tasksBeforeMigration, tasksAfterMigration, "All tasks should be preserved")

        // Verify specific test data
        val projectCheck = verifyStatement.executeQuery("""
            SELECT name, summary, description, status FROM projects WHERE hex(id) = '11111111111111111111111111111111'
        """)
        assertTrue(projectCheck.next(), "Test project should exist after migration")
        assertEquals("Test Project V11", projectCheck.getString("name"))
        assertEquals("Test project summary", projectCheck.getString("summary"))
        assertEquals("Test project description", projectCheck.getString("description"))
        assertEquals("PLANNING", projectCheck.getString("status"))
        projectCheck.close()

        val featureCheck = verifyStatement.executeQuery("""
            SELECT name, summary, description, status FROM features WHERE hex(id) = '22222222222222222222222222222222'
        """)
        assertTrue(featureCheck.next(), "Test feature should exist after migration")
        assertEquals("Test Feature V11", featureCheck.getString("name"))
        assertEquals("Test feature summary", featureCheck.getString("summary"))
        assertEquals("Test feature description", featureCheck.getString("description"))
        assertEquals("DRAFT", featureCheck.getString("status"))
        featureCheck.close()

        val taskCheck = verifyStatement.executeQuery("""
            SELECT title, summary, description, status FROM tasks WHERE hex(id) = '33333333333333333333333333333333'
        """)
        assertTrue(taskCheck.next(), "Test task should exist after migration")
        assertEquals("Test Task V11", taskCheck.getString("title"))
        assertEquals("Test task summary", taskCheck.getString("summary"))
        assertEquals("Test task description", taskCheck.getString("description"))
        assertEquals("BACKLOG", taskCheck.getString("status"))
        taskCheck.close()

        verifyStatement.close()
    }

    /**
     * Test that all 11 new v2.0 statuses are accepted by CHECK constraints.
     * This validates that the V12 migration correctly expands the allowed status values.
     */
    @Test
    fun `test all 11 new v2 statuses accepted by CHECK constraints`() {
        // Apply all migrations including V12
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Create base project for foreign key references
        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
            VALUES (
                X'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
                'Base Project',
                'Base project',
                'PLANNING',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Create base feature for foreign key references
        statement.executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, version)
            VALUES (
                X'BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB',
                X'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
                'Base Feature',
                'Base feature',
                'PLANNING',
                'MEDIUM',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Test new PROJECT statuses (2 new: CANCELLED, ON_HOLD)
        val projectNewStatuses = listOf("CANCELLED", "ON_HOLD")
        projectNewStatuses.forEachIndexed { index, status ->
            val projectId = String.format("C%031d", index)
            val insertResult = runCatching {
                statement.executeUpdate("""
                    INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
                    VALUES (
                        X'$projectId',
                        'Project with $status',
                        'Testing $status status',
                        '$status',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        1
                    )
                """)
            }
            assertTrue(insertResult.isSuccess, "Project status '$status' should be accepted (new in V12)")
        }

        // Test new FEATURE statuses (5 new: TESTING, VALIDATING, PENDING_REVIEW, BLOCKED, DEPLOYED)
        val featureNewStatuses = listOf("TESTING", "VALIDATING", "PENDING_REVIEW", "BLOCKED", "DEPLOYED")
        featureNewStatuses.forEachIndexed { index, status ->
            val featureId = String.format("D%031d", index)
            val insertResult = runCatching {
                statement.executeUpdate("""
                    INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, version)
                    VALUES (
                        X'$featureId',
                        X'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
                        'Feature with $status',
                        'Testing $status status',
                        '$status',
                        'MEDIUM',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        1
                    )
                """)
            }
            assertTrue(insertResult.isSuccess, "Feature status '$status' should be accepted (new in V12)")
        }

        // Test new TASK statuses (4 new: READY_FOR_QA, INVESTIGATING, DEPLOYED, TESTING)
        val taskNewStatuses = listOf("READY_FOR_QA", "INVESTIGATING", "DEPLOYED", "TESTING")
        taskNewStatuses.forEachIndexed { index, status ->
            val taskId = String.format("E%031d", index)
            val insertResult = runCatching {
                statement.executeUpdate("""
                    INSERT INTO tasks (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version)
                    VALUES (
                        X'$taskId',
                        X'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
                        X'BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB',
                        'Task with $status',
                        'Testing $status status',
                        '$status',
                        'MEDIUM',
                        5,
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        1
                    )
                """)
            }
            assertTrue(insertResult.isSuccess, "Task status '$status' should be accepted (new in V12)")
        }

        // Verify all new statuses were inserted successfully
        val projectCount = statement.executeQuery("SELECT COUNT(*) as count FROM projects WHERE status IN ('CANCELLED', 'ON_HOLD')")
        projectCount.next()
        assertEquals(projectNewStatuses.size, projectCount.getInt("count"), "All new project statuses should be inserted")
        projectCount.close()

        val featureCount = statement.executeQuery("SELECT COUNT(*) as count FROM features WHERE status IN ('TESTING', 'VALIDATING', 'PENDING_REVIEW', 'BLOCKED', 'DEPLOYED')")
        featureCount.next()
        assertEquals(featureNewStatuses.size, featureCount.getInt("count"), "All new feature statuses should be inserted")
        featureCount.close()

        val taskCount = statement.executeQuery("SELECT COUNT(*) as count FROM tasks WHERE status IN ('READY_FOR_QA', 'INVESTIGATING', 'DEPLOYED', 'TESTING')")
        taskCount.next()
        assertEquals(taskNewStatuses.size, taskCount.getInt("count"), "All new task statuses should be inserted")
        taskCount.close()

        statement.close()
    }

    /**
     * Test that invalid statuses are rejected by CHECK constraints after V12.
     * This ensures database-level validation is working correctly.
     */
    @Test
    fun `test invalid statuses rejected with constraint violations`() {
        // Apply all migrations including V12
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Test invalid PROJECT status
        val invalidProjectResult = runCatching {
            statement.executeUpdate("""
                INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
                VALUES (
                    randomblob(16),
                    'Invalid Project',
                    'Testing invalid status',
                    'INVALID_STATUS',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,
                    1
                )
            """)
        }
        assertTrue(invalidProjectResult.isFailure, "Invalid project status should be rejected")
        assertTrue(
            invalidProjectResult.exceptionOrNull()?.message?.contains("constraint", ignoreCase = true) == true,
            "Should fail with constraint violation"
        )

        // Create base project for feature/task tests
        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
            VALUES (
                X'FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF',
                'Base Project',
                'Base project',
                'PLANNING',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Test invalid FEATURE status
        val invalidFeatureResult = runCatching {
            statement.executeUpdate("""
                INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, version)
                VALUES (
                    randomblob(16),
                    X'FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF',
                    'Invalid Feature',
                    'Testing invalid status',
                    'BOGUS_STATUS',
                    'MEDIUM',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,
                    1
                )
            """)
        }
        assertTrue(invalidFeatureResult.isFailure, "Invalid feature status should be rejected")
        assertTrue(
            invalidFeatureResult.exceptionOrNull()?.message?.contains("constraint", ignoreCase = true) == true,
            "Should fail with constraint violation"
        )

        // Create base feature for task tests
        statement.executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, version)
            VALUES (
                X'EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE',
                X'FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF',
                'Base Feature',
                'Base feature',
                'PLANNING',
                'MEDIUM',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        // Test invalid TASK status
        val invalidTaskResult = runCatching {
            statement.executeUpdate("""
                INSERT INTO tasks (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version)
                VALUES (
                    randomblob(16),
                    X'FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF',
                    X'EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE',
                    'Invalid Task',
                    'Testing invalid status',
                    'NOT_A_REAL_STATUS',
                    'MEDIUM',
                    5,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,
                    1
                )
            """)
        }
        assertTrue(invalidTaskResult.isFailure, "Invalid task status should be rejected")
        assertTrue(
            invalidTaskResult.exceptionOrNull()?.message?.contains("constraint", ignoreCase = true) == true,
            "Should fail with constraint violation"
        )

        statement.close()
    }

    /**
     * Test that V12 migration preserves all existing data integrity.
     * This ensures no data loss during the table rebuild process.
     */
    @Test
    fun `test V12 migration preserves all data integrity`() {
        // Apply migrations up to V11
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("11")
            .load()

        flyway.migrate()

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Insert comprehensive test data with all V11 fields
        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, description, status, created_at, modified_at, search_vector, version)
            VALUES (
                X'44444444444444444444444444444444',
                'Integrity Test Project',
                'Project summary',
                'Project description',
                'IN_DEVELOPMENT',
                '2024-01-15 10:00:00',
                '2024-01-15 11:00:00',
                'search vector content',
                5
            )
        """)

        statement.executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, description, status, priority, created_at, modified_at, search_vector, version)
            VALUES (
                X'55555555555555555555555555555555',
                X'44444444444444444444444444444444',
                'Integrity Test Feature',
                'Feature summary',
                'Feature description',
                'PLANNING',
                'HIGH',
                '2024-01-15 12:00:00',
                '2024-01-15 13:00:00',
                'feature search vector',
                3
            )
        """)

        statement.executeUpdate("""
            INSERT INTO tasks (id, project_id, feature_id, title, summary, description, status, priority, complexity, created_at, modified_at, version, last_modified_by, lock_status, search_vector)
            VALUES (
                X'66666666666666666666666666666666',
                X'44444444444444444444444444444444',
                X'55555555555555555555555555555555',
                'Integrity Test Task',
                'Task summary',
                'Task description',
                'IN_PROGRESS',
                'MEDIUM',
                7,
                '2024-01-15 14:00:00',
                '2024-01-15 15:00:00',
                2,
                'test_user',
                'UNLOCKED',
                'task search vector'
            )
        """)

        statement.close()

        // Apply V12 migration
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "V12 migration should succeed")

        // Verify ALL fields preserved for project
        val verifyStatement = connection.createStatement()
        val projectCheck = verifyStatement.executeQuery("""
            SELECT
                hex(id) as id,
                name,
                summary,
                description,
                status,
                created_at,
                modified_at,
                search_vector,
                version
            FROM projects
            WHERE hex(id) = '44444444444444444444444444444444'
        """)

        assertTrue(projectCheck.next(), "Project should exist after migration")
        assertEquals("44444444444444444444444444444444", projectCheck.getString("id"))
        assertEquals("Integrity Test Project", projectCheck.getString("name"))
        assertEquals("Project summary", projectCheck.getString("summary"))
        assertEquals("Project description", projectCheck.getString("description"))
        assertEquals("IN_DEVELOPMENT", projectCheck.getString("status"))
        assertEquals("2024-01-15 10:00:00", projectCheck.getString("created_at"))
        assertEquals("2024-01-15 11:00:00", projectCheck.getString("modified_at"))
        assertEquals("search vector content", projectCheck.getString("search_vector"))
        assertEquals(5, projectCheck.getInt("version"))
        projectCheck.close()

        // Verify ALL fields preserved for feature
        val featureCheck = verifyStatement.executeQuery("""
            SELECT
                hex(id) as id,
                hex(project_id) as project_id,
                name,
                summary,
                description,
                status,
                priority,
                created_at,
                modified_at,
                search_vector,
                version
            FROM features
            WHERE hex(id) = '55555555555555555555555555555555'
        """)

        assertTrue(featureCheck.next(), "Feature should exist after migration")
        assertEquals("55555555555555555555555555555555", featureCheck.getString("id"))
        assertEquals("44444444444444444444444444444444", featureCheck.getString("project_id"))
        assertEquals("Integrity Test Feature", featureCheck.getString("name"))
        assertEquals("Feature summary", featureCheck.getString("summary"))
        assertEquals("Feature description", featureCheck.getString("description"))
        assertEquals("PLANNING", featureCheck.getString("status"))
        assertEquals("HIGH", featureCheck.getString("priority"))
        assertEquals("2024-01-15 12:00:00", featureCheck.getString("created_at"))
        assertEquals("2024-01-15 13:00:00", featureCheck.getString("modified_at"))
        assertEquals("feature search vector", featureCheck.getString("search_vector"))
        assertEquals(3, featureCheck.getInt("version"))
        featureCheck.close()

        // Verify ALL fields preserved for task
        val taskCheck = verifyStatement.executeQuery("""
            SELECT
                hex(id) as id,
                hex(project_id) as project_id,
                hex(feature_id) as feature_id,
                title,
                summary,
                description,
                status,
                priority,
                complexity,
                created_at,
                modified_at,
                version,
                last_modified_by,
                lock_status,
                search_vector
            FROM tasks
            WHERE hex(id) = '66666666666666666666666666666666'
        """)

        assertTrue(taskCheck.next(), "Task should exist after migration")
        assertEquals("66666666666666666666666666666666", taskCheck.getString("id"))
        assertEquals("44444444444444444444444444444444", taskCheck.getString("project_id"))
        assertEquals("55555555555555555555555555555555", taskCheck.getString("feature_id"))
        assertEquals("Integrity Test Task", taskCheck.getString("title"))
        assertEquals("Task summary", taskCheck.getString("summary"))
        assertEquals("Task description", taskCheck.getString("description"))
        assertEquals("IN_PROGRESS", taskCheck.getString("status"))
        assertEquals("MEDIUM", taskCheck.getString("priority"))
        assertEquals(7, taskCheck.getInt("complexity"))
        assertEquals("2024-01-15 14:00:00", taskCheck.getString("created_at"))
        assertEquals("2024-01-15 15:00:00", taskCheck.getString("modified_at"))
        assertEquals(2, taskCheck.getInt("version"))
        assertEquals("test_user", taskCheck.getString("last_modified_by"))
        assertEquals("UNLOCKED", taskCheck.getString("lock_status"))
        assertEquals("task search vector", taskCheck.getString("search_vector"))
        taskCheck.close()

        verifyStatement.close()
    }

    /**
     * Test that V12 migration recreates all indexes correctly.
     * This ensures query performance is maintained after migration.
     */
    @Test
    fun `test V12 migration recreates indexes correctly`() {
        // Apply all migrations including V12
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Get all indexes
        val indexQuery = statement.executeQuery(
            "SELECT name, tbl_name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
        )

        val indexes = mutableMapOf<String, MutableList<String>>()
        while (indexQuery.next()) {
            val tableName = indexQuery.getString("tbl_name")
            val indexName = indexQuery.getString("name")
            indexes.getOrPut(tableName) { mutableListOf() }.add(indexName)
        }
        indexQuery.close()

        // Expected indexes for PROJECTS (4 basic indexes from V12)
        val expectedProjectIndexes = listOf(
            "idx_projects_status",
            "idx_projects_created_at",
            "idx_projects_modified_at",
            "idx_projects_version"
        )

        expectedProjectIndexes.forEach { indexName ->
            assertTrue(
                indexes["projects"]?.contains(indexName) == true,
                "Project index '$indexName' should exist after V12 migration"
            )
        }

        // Expected indexes for FEATURES (8 indexes: 6 basic + 2 composite from V8)
        val expectedFeatureIndexes = listOf(
            "idx_features_project_id",
            "idx_features_status",
            "idx_features_priority",
            "idx_features_created_at",
            "idx_features_modified_at",
            "idx_features_version",
            "idx_features_status_priority",      // V8 composite
            "idx_features_project_status"        // V8 composite
        )

        expectedFeatureIndexes.forEach { indexName ->
            assertTrue(
                indexes["features"]?.contains(indexName) == true,
                "Feature index '$indexName' should exist after V12 migration"
            )
        }

        // Expected indexes for TASKS (10 indexes: 7 basic + 3 composite)
        val expectedTaskIndexes = listOf(
            "idx_tasks_project_id",
            "idx_tasks_feature_id",
            "idx_tasks_status",
            "idx_tasks_priority",
            "idx_tasks_version",
            "idx_tasks_lock_status",
            "idx_tasks_last_modified_by",
            "idx_tasks_feature_status",                     // V4 composite
            "idx_tasks_project_feature",                    // V4 composite
            "idx_tasks_status_priority_complexity",         // V8 composite
            "idx_tasks_feature_status_priority"             // V8 composite
        )

        expectedTaskIndexes.forEach { indexName ->
            assertTrue(
                indexes["tasks"]?.contains(indexName) == true,
                "Task index '$indexName' should exist after V12 migration"
            )
        }

        statement.close()
    }

    /**
     * Test that existing V11 statuses still work after V12 migration.
     * This ensures backward compatibility with existing data.
     */
    @Test
    fun `test existing V11 statuses still work after V12 migration`() {
        // Apply all migrations including V12
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Test all V11 project statuses still work
        val v11ProjectStatuses = listOf("PLANNING", "IN_DEVELOPMENT", "COMPLETED", "ARCHIVED")
        v11ProjectStatuses.forEach { status ->
            val insertResult = runCatching {
                statement.executeUpdate("""
                    INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
                    VALUES (
                        randomblob(16),
                        'V11 Project $status',
                        'Testing V11 status',
                        '$status',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        1
                    )
                """)
            }
            assertTrue(insertResult.isSuccess, "V11 project status '$status' should still work: ${insertResult.exceptionOrNull()?.message}")
        }

        // Test all V11 feature statuses still work
        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
            VALUES (X'DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD', 'Base Project', 'Base', 'PLANNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
        """)

        val v11FeatureStatuses = listOf("PLANNING", "IN_DEVELOPMENT", "COMPLETED", "ARCHIVED", "DRAFT", "ON_HOLD")
        v11FeatureStatuses.forEach { status ->
            val insertResult = runCatching {
                statement.executeUpdate("""
                    INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, version)
                    VALUES (
                        randomblob(16),
                        X'DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD',
                        'V11 Feature $status',
                        'Testing V11 status',
                        '$status',
                        'MEDIUM',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        1
                    )
                """)
            }
            assertTrue(insertResult.isSuccess, "V11 feature status '$status' should still work: ${insertResult.exceptionOrNull()?.message}")
        }

        // Test all V11 task statuses still work
        statement.executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, version)
            VALUES (X'CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC', X'DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD', 'Base Feature', 'Base', 'PLANNING', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
        """)

        val v11TaskStatuses = listOf(
            "PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED", "DEFERRED",
            "BACKLOG", "IN_REVIEW", "CHANGES_REQUESTED", "BLOCKED", "ON_HOLD"
        )
        v11TaskStatuses.forEach { status ->
            val insertResult = runCatching {
                statement.executeUpdate("""
                    INSERT INTO tasks (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version)
                    VALUES (
                        randomblob(16),
                        X'DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD',
                        X'CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC',
                        'V11 Task $status',
                        'Testing V11 status',
                        '$status',
                        'MEDIUM',
                        5,
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        1
                    )
                """)
            }
            assertTrue(insertResult.isSuccess, "V11 task status '$status' should still work: ${insertResult.exceptionOrNull()?.message}")
        }

        statement.close()
    }

    /**
     * Test rollback scenario for V12 migration.
     * While Flyway doesn't support automatic rollback for SQLite, we can test that
     * the database can be rebuilt from scratch at V11 if needed.
     */
    @Test
    fun `test V12 rollback scenario by rebuilding at V11`() {
        // Apply migrations up to V12
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Initial migration to V12 should succeed")

        // Verify we're at V12
        val versionAfterUpgrade = schemaManager.getCurrentVersion()
        assertTrue(versionAfterUpgrade >= 12, "Should be at least V12")

        // Simulate rollback by creating a new database at V11
        val rollbackFile = java.io.File.createTempFile("test_v11_rollback", ".db")
        rollbackFile.deleteOnExit()

        val rollbackDatabase = Database.connect(
            url = "jdbc:sqlite:${rollbackFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            setupConnection = { connection ->
                connection.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
            }
        )

        // Apply migrations only up to V11
        val flywayRollback = Flyway.configure()
            .dataSource("jdbc:sqlite:${rollbackFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("11")
            .load()

        val rollbackResult = flywayRollback.migrate()
        assertTrue(rollbackResult.migrations.isNotEmpty(), "Rollback migrations should succeed")

        // Verify we're at V11
        val rollbackSchemaManager = FlywayDatabaseSchemaManager(rollbackDatabase)
        val versionAfterRollback = rollbackSchemaManager.getCurrentVersion()
        assertEquals(11, versionAfterRollback, "Rollback database should be at V11")

        // Verify V12 statuses are NOT available at V11
        val connection = rollbackDatabase.connector().connection as Connection
        val statement = connection.createStatement()

        // Try to insert with V12-only status (should fail at V11)
        val v12StatusResult = runCatching {
            statement.executeUpdate("""
                INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
                VALUES (
                    randomblob(16),
                    'V12 Status Test',
                    'Testing V12 status at V11',
                    'CANCELLED',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,
                    1
                )
            """)
        }
        assertTrue(v12StatusResult.isFailure, "V12 status 'CANCELLED' should not work at V11")

        statement.close()
    }

    /**
     * Test that V12 migration is idempotent.
     * Running it multiple times should not cause errors.
     */
    @Test
    fun `test V12 migration is idempotent`() {
        // Apply migrations including V12
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "First migration should succeed")

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Insert test data using V12 statuses
        statement.executeUpdate("""
            INSERT INTO projects (id, name, summary, status, created_at, modified_at, version)
            VALUES (
                X'77777777777777777777777777777777',
                'Idempotent Test Project',
                'Test idempotency',
                'CANCELLED',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        statement.executeUpdate("""
            INSERT INTO features (id, project_id, name, summary, status, priority, created_at, modified_at, version)
            VALUES (
                X'88888888888888888888888888888888',
                X'77777777777777777777777777777777',
                'Idempotent Test Feature',
                'Test idempotency',
                'DEPLOYED',
                'HIGH',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        statement.executeUpdate("""
            INSERT INTO tasks (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version)
            VALUES (
                X'99999999999999999999999999999999',
                X'77777777777777777777777777777777',
                X'88888888888888888888888888888888',
                'Idempotent Test Task',
                'Test idempotency',
                'READY_FOR_QA',
                'MEDIUM',
                6,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                1
            )
        """)

        statement.close()

        // Run migrations again (should be no-op)
        assertTrue(schemaManager.updateSchema(), "Second migration run should succeed")

        // Verify data is unchanged
        val verifyStatement = connection.createStatement()

        val projectCheck = verifyStatement.executeQuery("""
            SELECT name, status FROM projects WHERE hex(id) = '77777777777777777777777777777777'
        """)
        assertTrue(projectCheck.next(), "Project should still exist")
        assertEquals("Idempotent Test Project", projectCheck.getString("name"))
        assertEquals("CANCELLED", projectCheck.getString("status"))
        projectCheck.close()

        val featureCheck = verifyStatement.executeQuery("""
            SELECT name, status FROM features WHERE hex(id) = '88888888888888888888888888888888'
        """)
        assertTrue(featureCheck.next(), "Feature should still exist")
        assertEquals("Idempotent Test Feature", featureCheck.getString("name"))
        assertEquals("DEPLOYED", featureCheck.getString("status"))
        featureCheck.close()

        val taskCheck = verifyStatement.executeQuery("""
            SELECT title, status FROM tasks WHERE hex(id) = '99999999999999999999999999999999'
        """)
        assertTrue(taskCheck.next(), "Task should still exist")
        assertEquals("Idempotent Test Task", taskCheck.getString("title"))
        assertEquals("READY_FOR_QA", taskCheck.getString("status"))
        taskCheck.close()

        verifyStatement.close()
    }
}
