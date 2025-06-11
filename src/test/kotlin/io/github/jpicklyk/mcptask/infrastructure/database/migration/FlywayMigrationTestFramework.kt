package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.sql.Connection
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * Testing framework for validating Flyway database migrations.
 * This provides utilities for testing migration success, rollback capabilities,
 * and schema consistency validation.
 */
class FlywayMigrationTestFramework(private val database: Database) {
    private val logger = LoggerFactory.getLogger(FlywayMigrationTestFramework::class.java)
    private val schemaManager = FlywayDatabaseSchemaManager(database)

    /**
     * Validates that all core application tables exist after migration.
     */
    fun validateCoreTablesExist(): Boolean {
        val expectedTables = listOf(
            "projects", "templates", "features", "entity_tags", "sections", 
            "template_sections", "tasks", "dependencies", "work_sessions", 
            "task_locks", "entity_assignments"
        )

        try {
            val existingTables = mutableListOf<String>()
            
            // Use direct connection approach like in working SimpleFlywayTest
            val connection = database.connector().connection as Connection
            val statement = connection.createStatement()
            val resultSet = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'flyway_%'")
            
            while (resultSet.next()) {
                existingTables.add(resultSet.getString("name").lowercase())
            }
            resultSet.close()
            statement.close()
                
            logger.info("Found tables: ${existingTables.sorted()}")
            logger.info("Expected tables: ${expectedTables.sorted()}")
            
            val missingTables = expectedTables.filter { it !in existingTables }
            if (missingTables.isNotEmpty()) {
                logger.error("Missing tables: $missingTables")
                return false
            }
            
            logger.info("All core application tables exist")
            return true
        } catch (e: Exception) {
            logger.error("Error validating core tables: ${e.message}", e)
            return false
        }
    }

    /**
     * Validates that the migration_test_table exists and contains expected test data.
     */
    fun validateMigrationTestTable(): Boolean {
        try {
            // Use direct connection approach
            val connection = database.connector().connection as Connection
            
            // Check if migration_test_table exists
            val tableCheckStatement = connection.createStatement()
            val tableCheckResult = tableCheckStatement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='migration_test_table'")
            val tableExists = tableCheckResult.next()
            tableCheckResult.close()
            tableCheckStatement.close()
            
            if (!tableExists) {
                logger.error("migration_test_table does not exist")
                return false
            }
            
            // Check if test data exists
            val dataCheckStatement = connection.createStatement()
            val dataCheckResult = dataCheckStatement.executeQuery("SELECT COUNT(*) as count FROM migration_test_table WHERE test_name = 'Initial Migration Test'")
            dataCheckResult.next()
            val testDataCount = dataCheckResult.getInt("count")
            dataCheckResult.close()
            dataCheckStatement.close()
            
            if (testDataCount == 0) {
                logger.error("Expected test data not found in migration_test_table")
                return false
            }
            
            logger.info("migration_test_table validation successful")
            return true
        } catch (e: Exception) {
            logger.error("Error validating migration_test_table: ${e.message}", e)
            return false
        }
    }

    /**
     * Tests the complete migration process from scratch.
     */
    fun testFullMigration(): Boolean {
        try {
            logger.info("Testing full migration process...")
            
            // Apply migrations
            val migrationResult = schemaManager.updateSchema()
            if (!migrationResult) {
                logger.error("Migration failed")
                return false
            }
            
            // Validate schema version
            val currentVersion = schemaManager.getCurrentVersion()
            logger.info("Current schema version after migration: $currentVersion")
            
            // Validate that all tables exist
            if (!validateCoreTablesExist()) {
                logger.error("Core table validation failed")
                return false
            }
            
            // Validate test table and data
            if (!validateMigrationTestTable()) {
                logger.error("Migration test table validation failed")
                return false
            }
            
            logger.info("Full migration test successful")
            return true
        } catch (e: Exception) {
            logger.error("Full migration test failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Validates indexes are created correctly.
     */
    fun validateIndexes(): Boolean {
        try {
            val expectedIndexes = mapOf(
                "projects" to listOf("idx_projects_status", "idx_projects_created_at", "idx_projects_modified_at"),
                "templates" to listOf("idx_templates_target_entity_type", "idx_templates_is_built_in", "idx_templates_is_enabled"),
                "features" to listOf("idx_features_project_id", "idx_features_status", "idx_features_priority"),
                "migration_test_table" to listOf("idx_migration_test_name", "idx_migration_test_version")
            )
            
            // Use direct connection approach
            val connection = database.connector().connection as Connection
            val statement = connection.createStatement()
            val resultSet = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'")
            
            val existingIndexes = mutableSetOf<String>()
            while (resultSet.next()) {
                existingIndexes.add(resultSet.getString("name"))
            }
            resultSet.close()
            statement.close()
                
            logger.info("Found indexes: ${existingIndexes.sorted()}")
            
            var allIndexesExist = true
            expectedIndexes.forEach { (table, indexes) ->
                indexes.forEach { indexName ->
                    if (indexName !in existingIndexes) {
                        logger.error("Missing index: $indexName for table $table")
                        allIndexesExist = false
                    }
                }
            }
            
            if (allIndexesExist) {
                logger.info("All expected indexes exist")
            }
            
            return allIndexesExist
        } catch (e: Exception) {
            logger.error("Error validating indexes: ${e.message}", e)
            return false
        }
    }

    /**
     * Tests inserting and querying data in the migration_test_table.
     */
    fun testMigrationTestTableOperations(): Boolean {
        try {
            val testName = "Framework Test ${System.currentTimeMillis()}"
            val testDescription = "Test inserted by FlywayMigrationTestFramework"
            val migrationVersion = 999
            val testData = """{"framework_test": true, "timestamp": ${System.currentTimeMillis()}}"""
            
            // Use direct connection approach
            val connection = database.connector().connection as Connection
            
            // Insert test data
            val insertStatement = connection.createStatement()
            insertStatement.executeUpdate("""
                INSERT INTO migration_test_table (id, test_name, test_description, migration_version, created_at, test_data)
                VALUES (randomblob(16), '$testName', '$testDescription', $migrationVersion, CURRENT_TIMESTAMP, '$testData')
            """)
            insertStatement.close()
            
            // Query the data back
            val queryStatement = connection.createStatement()
            val resultSet = queryStatement.executeQuery("SELECT test_name, test_description FROM migration_test_table WHERE test_name = '$testName'")
            
            val foundRecord: Pair<String, String>? = if (resultSet.next()) {
                Pair(resultSet.getString("test_name"), resultSet.getString("test_description"))
            } else {
                null
            }
            resultSet.close()
            queryStatement.close()
            
            if (foundRecord == null) {
                logger.error("Failed to retrieve inserted test data")
                return false
            }
            
            val (retrievedName, retrievedDescription) = foundRecord
            if (retrievedName != testName || retrievedDescription != testDescription) {
                logger.error("Retrieved data doesn't match inserted data")
                return false
            }
            
            // Clean up test data
            val deleteStatement = connection.createStatement()
            deleteStatement.executeUpdate("DELETE FROM migration_test_table WHERE test_name = '$testName'")
            deleteStatement.close()
            
            logger.info("Migration test table operations successful")
            return true
        } catch (e: Exception) {
            logger.error("Error testing migration_test_table operations: ${e.message}", e)
            return false
        }
    }

    /**
     * Comprehensive migration validation suite.
     */
    fun runFullValidationSuite(): Boolean {
        logger.info("Running comprehensive migration validation suite...")
        
        val tests = listOf(
            "Full Migration" to ::testFullMigration,
            "Core Tables" to ::validateCoreTablesExist,
            "Migration Test Table" to ::validateMigrationTestTable,
            "Indexes" to ::validateIndexes,
            "Test Table Operations" to ::testMigrationTestTableOperations
        )
        
        var allTestsPassed = true
        val results = mutableMapOf<String, Boolean>()
        
        tests.forEach { (testName, testFunction) ->
            logger.info("Running test: $testName")
            try {
                val result = testFunction()
                results[testName] = result
                if (result) {
                    logger.info("✅ Test passed: $testName")
                } else {
                    logger.error("❌ Test failed: $testName")
                    allTestsPassed = false
                }
            } catch (e: Exception) {
                logger.error("💥 Test error: $testName - ${e.message}", e)
                results[testName] = false
                allTestsPassed = false
            }
        }
        
        logger.info("=== Migration Validation Results ===")
        results.forEach { (testName, result) ->
            val status = if (result) "✅ PASS" else "❌ FAIL"
            logger.info("$status: $testName")
        }
        
        if (allTestsPassed) {
            logger.info("🎉 All migration validation tests passed!")
        } else {
            logger.error("💥 Some migration validation tests failed!")
        }
        
        return allTestsPassed
    }
}