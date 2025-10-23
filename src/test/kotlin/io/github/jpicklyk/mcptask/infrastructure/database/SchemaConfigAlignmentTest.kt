package io.github.jpicklyk.mcptask.infrastructure.database

import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.sql.Connection

/**
 * Comprehensive alignment tests ensuring database CHECK constraints, YAML config allowed_statuses,
 * and Kotlin enums are perfectly aligned across all three sources.
 *
 * Tests all three entity types:
 * - Tasks: 14 statuses
 * - Features: 11 statuses
 * - Projects: 6 statuses
 *
 * Test categories:
 * 1. Database CHECK constraints → Config YAML allowed_statuses
 * 2. Kotlin enums → Config YAML allowed_statuses
 * 3. Database CHECK constraints → Kotlin enums
 * 4. Normalization consistency (hyphenated vs UPPER_SNAKE_CASE)
 */
class SchemaConfigAlignmentTest {

    private lateinit var database: Database
    private lateinit var configData: Map<String, Any>

    @BeforeEach
    fun setup() {
        // Setup SQLite in-memory database
        val tempFile = java.io.File.createTempFile("schema_test", ".db")
        tempFile.deleteOnExit()

        database = Database.connect(
            url = "jdbc:sqlite:${tempFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            setupConnection = { conn ->
                conn.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
            }
        )
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // Create tables with CHECK constraints matching V12 migration
        transaction(database) {
            exec("""
                CREATE TABLE projects (
                    id BINARY(16) PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    summary TEXT NOT NULL,
                    description TEXT,
                    status VARCHAR(20) NOT NULL CHECK (status IN (
                        'PLANNING',
                        'IN_DEVELOPMENT',
                        'ON_HOLD',
                        'CANCELLED',
                        'COMPLETED',
                        'ARCHIVED'
                    )),
                    created_at TIMESTAMP NOT NULL,
                    modified_at TIMESTAMP NOT NULL,
                    search_vector TEXT,
                    version BIGINT NOT NULL DEFAULT 1
                )
            """.trimIndent())

            exec("""
                CREATE TABLE features (
                    id BINARY(16) PRIMARY KEY,
                    project_id BINARY(16),
                    name TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    status VARCHAR(20) NOT NULL CHECK (status IN (
                        'DRAFT',
                        'PLANNING',
                        'IN_DEVELOPMENT',
                        'TESTING',
                        'VALIDATING',
                        'PENDING_REVIEW',
                        'BLOCKED',
                        'ON_HOLD',
                        'DEPLOYED',
                        'COMPLETED',
                        'ARCHIVED'
                    )),
                    priority VARCHAR(10) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
                    created_at TIMESTAMP NOT NULL,
                    modified_at TIMESTAMP NOT NULL,
                    search_vector TEXT,
                    version INTEGER NOT NULL DEFAULT 1,
                    description TEXT,
                    FOREIGN KEY (project_id) REFERENCES projects(id)
                )
            """.trimIndent())

            exec("""
                CREATE TABLE tasks (
                    id BINARY(16) PRIMARY KEY,
                    project_id BINARY(16),
                    feature_id BINARY(16),
                    title TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    status VARCHAR(20) NOT NULL CHECK (status IN (
                        'BACKLOG',
                        'PENDING',
                        'IN_PROGRESS',
                        'IN_REVIEW',
                        'CHANGES_REQUESTED',
                        'TESTING',
                        'READY_FOR_QA',
                        'INVESTIGATING',
                        'BLOCKED',
                        'ON_HOLD',
                        'DEPLOYED',
                        'COMPLETED',
                        'CANCELLED',
                        'DEFERRED'
                    )),
                    priority VARCHAR(20) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
                    complexity INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    modified_at TIMESTAMP NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    last_modified_by TEXT,
                    lock_status VARCHAR(20) NOT NULL DEFAULT 'UNLOCKED',
                    search_vector TEXT,
                    description TEXT,
                    FOREIGN KEY (project_id) REFERENCES projects(id),
                    FOREIGN KEY (feature_id) REFERENCES features(id)
                )
            """.trimIndent())
        }

        // Load config.yaml using SnakeYAML
        val configYaml = javaClass.classLoader.getResourceAsStream("claude/configuration/default-config.yaml")
            ?: throw IllegalStateException("Could not load default-config.yaml")

        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        configData = yaml.load(configYaml) as Map<String, Any>
    }

    @AfterEach
    fun tearDown() {
        transaction(database) {
            exec("DROP TABLE IF EXISTS tasks")
            exec("DROP TABLE IF EXISTS features")
            exec("DROP TABLE IF EXISTS projects")
        }
    }

    // ========== TASK STATUS ALIGNMENT TESTS (14 statuses) ==========

    @Test
    fun `task statuses - database CHECK constraint matches config YAML allowed_statuses`() {
        val dbStatuses = extractCheckConstraintValues("tasks", "status").sorted()
        val configStatuses = getConfigAllowedStatuses("tasks").map { it.toDbFormat() }.sorted()

        // Config is a subset of DB (11 vs 14) - verify all config statuses are in DB
        val missingInDb = configStatuses - dbStatuses.toSet()
        assertTrue(missingInDb.isEmpty(),
            "Config YAML contains statuses not in database CHECK constraint: $missingInDb"
        )

        // Verify count
        assertEquals(14, dbStatuses.size, "Expected 14 task statuses in database CHECK constraint")
        assertEquals(11, configStatuses.size, "Expected 11 task statuses in config.yaml (config has subset)")

        // Document the expected difference
        val extraInDb = (dbStatuses - configStatuses.toSet()).sorted()
        assertEquals(listOf("DEPLOYED", "INVESTIGATING", "READY_FOR_QA"), extraInDb,
            "Expected DB to have DEPLOYED, INVESTIGATING, READY_FOR_QA not in config"
        )
    }

    @Test
    fun `task statuses - Kotlin enum matches config YAML allowed_statuses`() {
        val enumStatuses = TaskStatus.entries.map { it.name }.sorted()
        val configStatuses = getConfigAllowedStatuses("tasks").map { it.toDbFormat() }.sorted()

        // Config has subset of enum values (11 vs 14)
        // Verify all config statuses exist in enum
        val missingInEnum = configStatuses - enumStatuses.toSet()
        assertTrue(missingInEnum.isEmpty(),
            "Config YAML contains statuses not in TaskStatus enum: $missingInEnum"
        )

        // Verify count - enum has more values than config (includes READY_FOR_QA, INVESTIGATING, DEPLOYED)
        assertEquals(14, enumStatuses.size, "Expected 14 values in TaskStatus enum")
        assertEquals(11, configStatuses.size, "Expected 11 values in config.yaml tasks.allowed_statuses")
    }

    @Test
    fun `task statuses - database CHECK constraint matches Kotlin enum`() {
        val dbStatuses = extractCheckConstraintValues("tasks", "status").sorted()
        val enumStatuses = TaskStatus.entries.map { it.name }.sorted()

        assertEquals(enumStatuses, dbStatuses,
            "Database CHECK constraint does not match TaskStatus enum.\n" +
                    "Missing in DB: ${(enumStatuses - dbStatuses.toSet())}\n" +
                    "Extra in DB: ${(dbStatuses - enumStatuses.toSet())}"
        )

        // Verify count
        assertEquals(14, dbStatuses.size, "Expected 14 task statuses in database")
        assertEquals(14, enumStatuses.size, "Expected 14 task statuses in enum")
    }

    @Test
    fun `task statuses - normalization consistency between enum and database`() {
        val enumStatuses = TaskStatus.entries.map { it.name }
        val dbStatuses = extractCheckConstraintValues("tasks", "status")

        enumStatuses.forEach { enumStatus ->
            assertTrue(dbStatuses.contains(enumStatus),
                "Enum status '$enumStatus' should be in database CHECK constraint using UPPER_SNAKE_CASE format"
            )
        }
    }

    @Test
    fun `task statuses - normalization consistency between enum and config`() {
        val enumStatuses = TaskStatus.entries.map { it.name.lowercase().replace('_', '-') }
        val configStatuses = getConfigAllowedStatuses("tasks")

        configStatuses.forEach { configStatus ->
            assertTrue(enumStatuses.contains(configStatus),
                "Config status '$configStatus' should have corresponding enum value in hyphenated lowercase format"
            )
        }
    }

    // ========== FEATURE STATUS ALIGNMENT TESTS (11 statuses) ==========

    @Test
    fun `feature statuses - database CHECK constraint matches config YAML allowed_statuses`() {
        val dbStatuses = extractCheckConstraintValues("features", "status").sorted()
        val configStatuses = getConfigAllowedStatuses("features").map { it.toDbFormat() }.sorted()

        // Config is a subset of DB (10 vs 11) - verify all config statuses are in DB
        val missingInDb = configStatuses - dbStatuses.toSet()
        assertTrue(missingInDb.isEmpty(),
            "Config YAML contains statuses not in database CHECK constraint: $missingInDb"
        )

        // Verify count
        assertEquals(11, dbStatuses.size, "Expected 11 feature statuses in database CHECK constraint")
        assertEquals(10, configStatuses.size, "Expected 10 feature statuses in config.yaml (config has subset)")

        // Document the expected difference
        val extraInDb = (dbStatuses - configStatuses.toSet()).sorted()
        assertEquals(listOf("DEPLOYED"), extraInDb,
            "Expected DB to have DEPLOYED not in config"
        )
    }

    @Test
    fun `feature statuses - Kotlin enum matches config YAML allowed_statuses`() {
        val enumStatuses = FeatureStatus.entries.map { it.name }.sorted()
        val configStatuses = getConfigAllowedStatuses("features").map { it.toDbFormat() }.sorted()

        // Config has subset of enum values (10 vs 11)
        // Verify all config statuses exist in enum
        val missingInEnum = configStatuses - enumStatuses.toSet()
        assertTrue(missingInEnum.isEmpty(),
            "Config YAML contains statuses not in FeatureStatus enum: $missingInEnum"
        )

        // Verify count - enum has more values than config (includes DEPLOYED)
        assertEquals(11, enumStatuses.size, "Expected 11 values in FeatureStatus enum")
        assertEquals(10, configStatuses.size, "Expected 10 values in config.yaml features.allowed_statuses")
    }

    @Test
    fun `feature statuses - database CHECK constraint matches Kotlin enum`() {
        val dbStatuses = extractCheckConstraintValues("features", "status").sorted()
        val enumStatuses = FeatureStatus.entries.map { it.name }.sorted()

        assertEquals(enumStatuses, dbStatuses,
            "Database CHECK constraint does not match FeatureStatus enum.\n" +
                    "Missing in DB: ${(enumStatuses - dbStatuses.toSet())}\n" +
                    "Extra in DB: ${(dbStatuses - enumStatuses.toSet())}"
        )

        // Verify count
        assertEquals(11, dbStatuses.size, "Expected 11 feature statuses in database")
        assertEquals(11, enumStatuses.size, "Expected 11 feature statuses in enum")
    }

    @Test
    fun `feature statuses - normalization consistency between enum and database`() {
        val enumStatuses = FeatureStatus.entries.map { it.name }
        val dbStatuses = extractCheckConstraintValues("features", "status")

        enumStatuses.forEach { enumStatus ->
            assertTrue(dbStatuses.contains(enumStatus),
                "Enum status '$enumStatus' should be in database CHECK constraint using UPPER_SNAKE_CASE format"
            )
        }
    }

    @Test
    fun `feature statuses - normalization consistency between enum and config`() {
        val enumStatuses = FeatureStatus.entries.map { it.name.lowercase().replace('_', '-') }
        val configStatuses = getConfigAllowedStatuses("features")

        configStatuses.forEach { configStatus ->
            assertTrue(enumStatuses.contains(configStatus),
                "Config status '$configStatus' should have corresponding enum value in hyphenated lowercase format"
            )
        }
    }

    // ========== PROJECT STATUS ALIGNMENT TESTS (6 statuses) ==========

    @Test
    fun `project statuses - database CHECK constraint matches config YAML allowed_statuses`() {
        val dbStatuses = extractCheckConstraintValues("projects", "status").sorted()
        val configStatuses = getConfigAllowedStatuses("projects").map { it.toDbFormat() }.sorted()

        assertEquals(configStatuses, dbStatuses,
            "Database CHECK constraint for projects.status does not match config.yaml allowed_statuses.\n" +
                    "Missing in DB: ${(configStatuses - dbStatuses.toSet())}\n" +
                    "Extra in DB: ${(dbStatuses - configStatuses.toSet())}"
        )

        // Verify count
        assertEquals(6, dbStatuses.size, "Expected 6 project statuses in database CHECK constraint")
        assertEquals(6, configStatuses.size, "Expected 6 project statuses in config.yaml")
    }

    @Test
    fun `project statuses - Kotlin enum matches config YAML allowed_statuses`() {
        val enumStatuses = ProjectStatus.entries.map { it.name }.sorted()
        val configStatuses = getConfigAllowedStatuses("projects").map { it.toDbFormat() }.sorted()

        assertEquals(enumStatuses, configStatuses,
            "ProjectStatus enum does not match config.yaml allowed_statuses.\n" +
                    "Missing in enum: ${(configStatuses - enumStatuses.toSet())}\n" +
                    "Extra in enum: ${(enumStatuses - configStatuses.toSet())}"
        )

        // Verify count
        assertEquals(6, enumStatuses.size, "Expected 6 values in ProjectStatus enum")
        assertEquals(6, configStatuses.size, "Expected 6 values in config.yaml")
    }

    @Test
    fun `project statuses - database CHECK constraint matches Kotlin enum`() {
        val dbStatuses = extractCheckConstraintValues("projects", "status").sorted()
        val enumStatuses = ProjectStatus.entries.map { it.name }.sorted()

        assertEquals(enumStatuses, dbStatuses,
            "Database CHECK constraint does not match ProjectStatus enum.\n" +
                    "Missing in DB: ${(enumStatuses - dbStatuses.toSet())}\n" +
                    "Extra in DB: ${(dbStatuses - enumStatuses.toSet())}"
        )

        // Verify count
        assertEquals(6, dbStatuses.size, "Expected 6 project statuses in database")
        assertEquals(6, enumStatuses.size, "Expected 6 project statuses in enum")
    }

    @Test
    fun `project statuses - normalization consistency between enum and database`() {
        val enumStatuses = ProjectStatus.entries.map { it.name }
        val dbStatuses = extractCheckConstraintValues("projects", "status")

        enumStatuses.forEach { enumStatus ->
            assertTrue(dbStatuses.contains(enumStatus),
                "Enum status '$enumStatus' should be in database CHECK constraint using UPPER_SNAKE_CASE format"
            )
        }
    }

    @Test
    fun `project statuses - normalization consistency between enum and config`() {
        val enumStatuses = ProjectStatus.entries.map { it.name.lowercase().replace('_', '-') }
        val configStatuses = getConfigAllowedStatuses("projects")

        configStatuses.forEach { configStatus ->
            assertTrue(enumStatuses.contains(configStatus),
                "Config status '$configStatus' should have corresponding enum value in hyphenated lowercase format"
            )
        }
    }

    // ========== CROSS-ENTITY VALIDATION TESTS ==========

    @Test
    fun `all entities - total status count matches expectations`() {
        val taskCount = extractCheckConstraintValues("tasks", "status").size
        val featureCount = extractCheckConstraintValues("features", "status").size
        val projectCount = extractCheckConstraintValues("projects", "status").size

        assertEquals(14, taskCount, "Expected 14 task statuses")
        assertEquals(11, featureCount, "Expected 11 feature statuses")
        assertEquals(6, projectCount, "Expected 6 project statuses")

        val totalCount = taskCount + featureCount + projectCount
        assertEquals(31, totalCount, "Expected total of 31 statuses across all entities (14 + 11 + 6)")
    }

    @Test
    fun `all entities - config YAML total status count matches expectations`() {
        val taskCount = getConfigAllowedStatuses("tasks").size
        val featureCount = getConfigAllowedStatuses("features").size
        val projectCount = getConfigAllowedStatuses("projects").size

        assertEquals(11, taskCount, "Expected 11 task statuses in config")
        assertEquals(10, featureCount, "Expected 10 feature statuses in config")
        assertEquals(6, projectCount, "Expected 6 project statuses in config")

        val totalCount = taskCount + featureCount + projectCount
        assertEquals(27, totalCount, "Expected total of 27 statuses in config across all entities (11 + 10 + 6)")
    }

    @Test
    fun `all entities - no status value overlap between entity types`() {
        val taskStatuses = extractCheckConstraintValues("tasks", "status").toSet()
        val featureStatuses = extractCheckConstraintValues("features", "status").toSet()
        val projectStatuses = extractCheckConstraintValues("projects", "status").toSet()

        // Note: There IS intentional overlap (e.g., COMPLETED, ARCHIVED, ON_HOLD appear in multiple entities)
        // This test verifies the EXPECTED overlaps match what's documented

        val taskFeatureOverlap = taskStatuses.intersect(featureStatuses)
        val taskProjectOverlap = taskStatuses.intersect(projectStatuses)
        val featureProjectOverlap = featureStatuses.intersect(projectStatuses)

        // Expected overlaps:
        // Tasks ∩ Features: TESTING, BLOCKED, ON_HOLD, DEPLOYED, COMPLETED
        // Tasks ∩ Projects: ON_HOLD, CANCELLED, COMPLETED
        // Features ∩ Projects: PLANNING, IN_DEVELOPMENT, ON_HOLD, COMPLETED, ARCHIVED

        assertEquals(setOf("TESTING", "BLOCKED", "ON_HOLD", "DEPLOYED", "COMPLETED"), taskFeatureOverlap,
            "Task-Feature overlap should only include shared statuses")
        assertEquals(setOf("ON_HOLD", "CANCELLED", "COMPLETED"), taskProjectOverlap,
            "Task-Project overlap should only include shared statuses")
        assertEquals(setOf("PLANNING", "IN_DEVELOPMENT", "ON_HOLD", "COMPLETED", "ARCHIVED"), featureProjectOverlap,
            "Feature-Project overlap should only include shared statuses")
    }

    // ========== HELPER METHODS ==========

    /**
     * Extracts CHECK constraint values from database schema.
     * Parses SQLite CREATE TABLE statement to extract CHECK constraint values.
     */
    private fun extractCheckConstraintValues(tableName: String, columnName: String): List<String> {
        return transaction(database) {
            // Query SQLite's schema to get the CREATE TABLE statement
            val createTableSql = exec("SELECT sql FROM sqlite_master WHERE type='table' AND name='$tableName'") { rs ->
                if (rs.next()) {
                    rs.getString("sql")
                } else {
                    null
                }
            } ?: throw IllegalStateException("Could not find table $tableName in schema")

            // Parse: status VARCHAR(20) NOT NULL CHECK (status IN ('VALUE1', 'VALUE2', ...))
            // Extract values between single quotes after "CHECK ($columnName IN"
            val checkPattern = Regex("CHECK\\s*\\(\\s*$columnName\\s+IN\\s*\\(([^)]+)\\)")
            val match = checkPattern.find(createTableSql)
                ?: throw IllegalStateException("Could not find CHECK constraint for $tableName.$columnName in:\n$createTableSql")

            val valuesString = match.groupValues[1]
            val values = Regex("'([^']+)'").findAll(valuesString)
                .map { it.groupValues[1] }
                .toList()
            values
        }
    }

    /**
     * Gets allowed statuses from config.yaml for the specified entity type.
     */
    @Suppress("UNCHECKED_CAST")
    /**
     * Derives allowed statuses from configured flows, emergency transitions, and terminal statuses.
     * This matches the v2.0 schema where allowed_statuses is no longer explicitly configured.
     */
    private fun getConfigAllowedStatuses(entityType: String): List<String> {
        val statusProgression = configData["status_progression"] as? Map<String, Any>
            ?: throw IllegalStateException("Config missing status_progression section")

        val entityConfig = statusProgression[entityType] as? Map<String, Any>
            ?: throw IllegalStateException("Config missing status_progression.$entityType section")

        val allowedStatuses = mutableSetOf<String>()

        // Add all statuses from all defined flows
        @Suppress("UNCHECKED_CAST")
        val allFlows = entityConfig.filterKeys { it.endsWith("_flow") }
        allFlows.values.forEach { flowValue ->
            if (flowValue is List<*>) {
                flowValue.filterIsInstance<String>().forEach { allowedStatuses.add(it) }
            }
        }

        // Add emergency transitions
        val emergencyStatuses = entityConfig["emergency_transitions"] as? List<String> ?: emptyList()
        allowedStatuses.addAll(emergencyStatuses)

        // Add terminal statuses
        val terminalStatuses = entityConfig["terminal_statuses"] as? List<String> ?: emptyList()
        allowedStatuses.addAll(terminalStatuses)

        return allowedStatuses.toList()
    }

    /**
     * Converts hyphenated lowercase config status to UPPER_SNAKE_CASE database format
     */
    private fun String.toDbFormat(): String = this.uppercase().replace('-', '_')
}
