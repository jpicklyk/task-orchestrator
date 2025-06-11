package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.sql.Connection

/**
 * Specific tests for the migration_test_table functionality.
 * This table serves no business purpose except testing schema updates.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MigrationTestTableTest {

    private lateinit var database: Database
    private lateinit var schemaManager: FlywayDatabaseSchemaManager

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database so Flyway and test queries use same database
        val tempFile = java.io.File.createTempFile("test_migration_table", ".db")
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

    @Test
    fun `test migration_test_table creation and initial data`() {
        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        transaction(database) {
            // Verify table exists
            val tableExists = exec("SELECT name FROM sqlite_master WHERE type='table' AND name='migration_test_table'") {
                it.next()
            } ?: false
            assertTrue(tableExists, "migration_test_table should exist")

            // Verify initial test record exists
            val initialRecordCount = exec("SELECT COUNT(*) as count FROM migration_test_table WHERE test_name = 'Initial Migration Test'") { rs ->
                rs.next()
                rs.getInt("count")
            } as Int
            
            assertEquals(1, initialRecordCount, "Should have exactly one initial test record")

            // Verify record content
            val recordData = exec("""
                SELECT test_description, migration_version, test_data 
                FROM migration_test_table 
                WHERE test_name = 'Initial Migration Test'
            """) { rs ->
                if (rs.next()) {
                    Triple(
                        rs.getString("test_description"),
                        rs.getInt("migration_version"),
                        rs.getString("test_data")
                    )
                } else {
                    null
                }
            }

            assertTrue(recordData != null, "Should find the initial test record")
            val (description, version, testData) = recordData!!
            
            assertTrue(description.contains("validates that the migration_test_table was created successfully"))
            assertEquals(2, version, "Initial record should be from migration version 2")
            assertTrue(testData.contains("schema_validation"), "Test data should contain validation marker")
        }
    }

    @Test
    fun `test migration_test_table CRUD operations`() {
        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        transaction(database) {
            val testId = "test-${System.currentTimeMillis()}"
            val testName = "CRUD Test $testId"
            val testDescription = "Testing CRUD operations on migration_test_table"
            val migrationVersion = 100
            val testData = """{"operation": "crud_test", "id": "$testId"}"""

            // CREATE - Insert test record
            exec("""
                INSERT INTO migration_test_table (id, test_name, test_description, migration_version, created_at, test_data)
                VALUES (randomblob(16), '$testName', '$testDescription', $migrationVersion, CURRENT_TIMESTAMP, '$testData')
            """)

            // READ - Verify record was inserted
            val insertedRecord = exec("SELECT * FROM migration_test_table WHERE test_name = '$testName'") { rs ->
                if (rs.next()) {
                    mapOf(
                        "test_name" to rs.getString("test_name"),
                        "test_description" to rs.getString("test_description"),
                        "migration_version" to rs.getInt("migration_version"),
                        "test_data" to rs.getString("test_data")
                    )
                } else {
                    null
                }
            }

            assertTrue(insertedRecord != null, "Should find inserted record")
            assertEquals(testName, insertedRecord!!["test_name"])
            assertEquals(testDescription, insertedRecord["test_description"])
            assertEquals(migrationVersion, insertedRecord["migration_version"])
            assertEquals(testData, insertedRecord["test_data"])

            // UPDATE - Modify the record
            val updatedDescription = "Updated description for $testId"
            exec("UPDATE migration_test_table SET test_description = '$updatedDescription' WHERE test_name = '$testName'")

            val updatedRecord = exec("SELECT test_description FROM migration_test_table WHERE test_name = '$testName'") { rs ->
                if (rs.next()) rs.getString("test_description") else null
            }
            assertEquals(updatedDescription, updatedRecord, "Record should be updated")

            // DELETE - Remove the test record
            exec("DELETE FROM migration_test_table WHERE test_name = '$testName'")

            val deletedRecordExists = exec("SELECT COUNT(*) as count FROM migration_test_table WHERE test_name = '$testName'") { rs ->
                rs.next()
                rs.getInt("count") > 0
            } ?: false
            assertTrue(!deletedRecordExists, "Record should be deleted")
        }
    }

    @Test
    fun `test migration_test_table indexes`() {
        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        transaction(database) {
            // Check that indexes exist
            val indexes = mutableListOf<String>()
            exec("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='migration_test_table'") { rs ->
                while (rs.next()) {
                    indexes.add(rs.getString("name"))
                }
            }

            assertTrue(indexes.contains("idx_migration_test_name"), "Should have index on test_name")
            assertTrue(indexes.contains("idx_migration_test_version"), "Should have index on migration_version")
        }
    }

    @Test
    fun `test migration_test_table constraints and data types`() {
        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        transaction(database) {
            // Test NOT NULL constraints by trying to insert null values
            var nullConstraintViolated = false
            try {
                exec("INSERT INTO migration_test_table (id, test_name, migration_version, created_at) VALUES (randomblob(16), NULL, 1, CURRENT_TIMESTAMP)")
            } catch (e: Exception) {
                if (e.message?.contains("NOT NULL", ignoreCase = true) == true) {
                    nullConstraintViolated = true
                }
            }
            assertTrue(nullConstraintViolated, "Should enforce NOT NULL constraint on test_name")

            // Test that we can insert valid data with various types
            val complexTestData = """
                {
                    "string": "test value",
                    "number": 42,
                    "boolean": true,
                    "array": [1, 2, 3],
                    "nested": {"key": "value"}
                }
            """.trimIndent()

            exec("""
                INSERT INTO migration_test_table (id, test_name, test_description, migration_version, created_at, test_data)
                VALUES (randomblob(16), 'Complex Data Test', 'Testing complex JSON data', 999, CURRENT_TIMESTAMP, '$complexTestData')
            """)

            val retrievedData = exec("SELECT test_data FROM migration_test_table WHERE test_name = 'Complex Data Test'") { rs ->
                if (rs.next()) rs.getString("test_data") else null
            }

            assertTrue(retrievedData != null, "Should retrieve complex test data")
            assertTrue(retrievedData!!.contains("nested"), "Should preserve JSON structure")

            // Clean up
            exec("DELETE FROM migration_test_table WHERE test_name = 'Complex Data Test'")
        }
    }

    @Test
    fun `test migration_test_table serves its testing purpose`() {
        // This test validates that the migration_test_table actually serves its purpose
        // of testing schema updates and migration functionality

        // Apply migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        transaction(database) {
            // Verify that we can use this table to validate migration state
            val migrationValidationRecord = exec("""
                SELECT COUNT(*) as total_records, 
                       MAX(migration_version) as max_version,
                       MIN(created_at) as earliest_record
                FROM migration_test_table
            """) { rs ->
                if (rs.next()) {
                    Triple(
                        rs.getInt("total_records"),
                        rs.getInt("max_version"),
                        rs.getString("earliest_record")
                    )
                } else {
                    null
                }
            }

            assertTrue(migrationValidationRecord != null, "Should be able to query migration validation data")
            val (totalRecords, maxVersion, earliestRecord) = migrationValidationRecord!!
            
            assertTrue(totalRecords > 0, "Should have at least one test record for validation")
            assertTrue(maxVersion >= 2, "Should reflect the migration version that created test data")
            assertTrue(earliestRecord.isNotEmpty(), "Should have timestamp data for tracking")

            // Insert a test record to validate that future migrations could use this table
            // to verify schema state
            val currentTimestamp = System.currentTimeMillis()
            exec("""
                INSERT INTO migration_test_table (id, test_name, test_description, migration_version, created_at, test_data)
                VALUES (randomblob(16), 'Schema Validation Test', 'Validates current schema state', 999, CURRENT_TIMESTAMP, '{"purpose": "schema_validation", "timestamp": $currentTimestamp}')
            """)

            // Verify we can query this data for validation purposes
            val validationQuery = exec("""
                SELECT test_data FROM migration_test_table 
                WHERE test_name = 'Schema Validation Test' 
                AND test_data LIKE '%schema_validation%'
            """) { rs ->
                if (rs.next()) rs.getString("test_data") else null
            }

            assertTrue(validationQuery != null, "Should be able to use test table for schema validation")
            assertTrue(validationQuery!!.contains(currentTimestamp.toString()), "Should preserve validation timestamp")

            // Clean up validation test
            exec("DELETE FROM migration_test_table WHERE test_name = 'Schema Validation Test'")
        }
    }
}