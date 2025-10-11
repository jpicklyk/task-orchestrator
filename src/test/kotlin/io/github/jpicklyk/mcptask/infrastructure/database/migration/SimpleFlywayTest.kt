package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.application.service.TemplateInitializerImpl
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteSectionRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTemplateRepository
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

    @Test
    fun `V5 migration should create simplified template sections`() {
        println("=== Testing V5 Simplified Templates Migration ===")

        // Run migrations up to V5
        val migrationResult = schemaManager.updateSchema()
        assertTrue(migrationResult, "Migrations should succeed")

        val version = schemaManager.getCurrentVersion()
        println("Schema version after migrations: $version")
        assertTrue(version >= 5, "Schema should be at least version 5")

        // Initialize templates (this creates the template records that V6 will update)
        println("Initializing templates...")
        val databaseManager = DatabaseManager(database)
        val sectionRepository = SQLiteSectionRepository(databaseManager)
        val templateRepository = SQLiteTemplateRepository(sectionRepository)
        val templateInitializer = TemplateInitializerImpl(templateRepository)
        templateInitializer.initializeTemplates()
        println("Templates initialized")

        // Now run V5 migration specifically (schema manager will skip already-applied migrations)
        // V5 should update the template sections to simplified versions
        val v5Result = schemaManager.updateSchema()
        assertTrue(v5Result, "V5 migration should succeed")

        val finalVersion = schemaManager.getCurrentVersion()
        println("Final schema version: $finalVersion")
        assertTrue(finalVersion >= 5, "Schema should be at version 5 after V5 migration")

        // Verify simplified template sections were created
        transaction(database) {
            val connection = this.connection.connection as Connection

            // Test 1: Check total section count for simplified templates
            val countStmt = connection.prepareStatement("""
                SELECT COUNT(*) as total FROM template_sections ts
                JOIN templates t ON ts.template_id = t.id
                WHERE t.name IN (
                    'Definition of Done',
                    'GitHub PR Workflow',
                    'Context & Background',
                    'Testing Strategy',
                    'Requirements Specification',
                    'Local Git Branching Workflow'
                )
            """)
            val countRs = countStmt.executeQuery()
            countRs.next()
            val totalSections = countRs.getInt("total")
            countRs.close()
            countStmt.close()

            println("Total sections for simplified templates: $totalSections")
            assertTrue(totalSections == 17, "Should have exactly 17 sections (2+3+3+3+3+3)")

            // Test 2: Verify Definition of Done has 2 sections with simplified titles
            val dodStmt = connection.prepareStatement("""
                SELECT ts.title FROM template_sections ts
                JOIN templates t ON ts.template_id = t.id
                WHERE t.name = 'Definition of Done'
                ORDER BY ts.ordinal
            """)
            val dodRs = dodStmt.executeQuery()

            val dodTitles = mutableListOf<String>()
            while (dodRs.next()) {
                dodTitles.add(dodRs.getString("title"))
            }
            dodRs.close()
            dodStmt.close()

            println("Definition of Done sections: $dodTitles")
            assertTrue(dodTitles.size == 2, "Definition of Done should have 2 sections")
            assertTrue(dodTitles[0] == "Implementation Complete", "First section should be 'Implementation Complete'")
            assertTrue(dodTitles[1] == "Production Ready", "Second section should be 'Production Ready'")

            // Test 3: Verify GitHub PR Workflow has 3 sections
            val prStmt = connection.prepareStatement("""
                SELECT ts.title FROM template_sections ts
                JOIN templates t ON ts.template_id = t.id
                WHERE t.name = 'GitHub PR Workflow'
                ORDER BY ts.ordinal
            """)
            val prRs = prStmt.executeQuery()

            val prTitles = mutableListOf<String>()
            while (prRs.next()) {
                prTitles.add(prRs.getString("title"))
            }
            prRs.close()
            prStmt.close()

            println("GitHub PR Workflow sections: $prTitles")
            assertTrue(prTitles.size == 3, "GitHub PR Workflow should have 3 sections")
            assertTrue(prTitles[0] == "Pre-Push Validation", "First section should be 'Pre-Push Validation'")
            assertTrue(prTitles[1] == "Create Pull Request", "Second section should be 'Create Pull Request'")
            assertTrue(prTitles[2] == "Review & Merge", "Third section should be 'Review & Merge'")

            // Test 4: Verify content is simplified (check one section for brevity)
            val contentStmt = connection.prepareStatement("""
                SELECT LENGTH(ts.content_sample) as content_length FROM template_sections ts
                JOIN templates t ON ts.template_id = t.id
                WHERE t.name = 'Definition of Done' AND ts.title = 'Implementation Complete'
            """)
            val contentRs = contentStmt.executeQuery()
            contentRs.next()
            val contentLength = contentRs.getInt("content_length")
            contentRs.close()
            contentStmt.close()

            println("Implementation Complete section length: $contentLength chars")
            // Simplified content should be much shorter (< 1500 chars vs > 3000 for old version)
            assertTrue(contentLength < 1500, "Simplified content should be < 1500 characters")

            println("✓ All simplified template section validations passed!")
        }

        println("=== V6 Simplified Templates Migration Test Complete ===")
    }
}