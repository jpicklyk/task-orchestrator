package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.sql.Connection

class SimpleFlywayTest {

    private lateinit var database: Database
    private lateinit var schemaManager: FlywayDatabaseSchemaManager

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database so Flyway and test queries use same database
        val tempFile = java.io.File.createTempFile("test_migration", ".db")
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
    fun `simple migration test with detailed error logging`() {
        println("=== Starting Simple Flyway Migration Test ===")
        
        // Let's first check if the migration files exist
        val migrationFile1 = this::class.java.classLoader.getResource("db/migration/V1__Initial_Schema.sql")
        val migrationFile2 = this::class.java.classLoader.getResource("db/migration/V2__Add_Migration_Test_Table.sql")
        
        println("V1 migration file found: ${migrationFile1 != null}")
        println("V2 migration file found: ${migrationFile2 != null}")
        
        if (migrationFile1 != null) {
            println("V1 file path: ${migrationFile1.path}")
        }
        if (migrationFile2 != null) {
            println("V2 file path: ${migrationFile2.path}")
        }
        
        try {
            println("About to call updateSchema()...")
            val result = schemaManager.updateSchema()
            
            println("Migration result: $result")
            
            if (result) {
                println("Migration succeeded! Checking schema version...")
                val version = schemaManager.getCurrentVersion()
                println("Current schema version: $version")
                
                // Check what tables were created using the same connection approach as Flyway
                val connection = database.connector().connection as Connection
                val statement = connection.createStatement()
                val resultSet = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'flyway_%'")
                
                val tables = mutableListOf<String>()
                while (resultSet.next()) {
                    tables.add(resultSet.getString("name"))
                }
                resultSet.close()
                statement.close()
                
                println("Tables created: ${tables.sorted()}")
                
                assertTrue(true, "Migration completed successfully")
            } else {
                println("Migration failed - result was false")
                assertTrue(false, "Migration should have succeeded")
            }
            
        } catch (e: Exception) {
            println("Migration threw exception: ${e.message}")
            e.printStackTrace()
            assertTrue(false, "Migration threw exception: ${e.message}")
        }
        
        println("=== Simple Flyway Migration Test Complete ===")
    }
}