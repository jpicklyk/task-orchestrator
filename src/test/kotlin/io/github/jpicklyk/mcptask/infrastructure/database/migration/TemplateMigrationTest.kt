package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.sql.Connection

/**
 * Tests for V5 template migration that simplifies existing templates.
 *
 * This tests the UPGRADE SCENARIO where templates already exist in the database
 * and need to be simplified. For fresh installs, templates are created by
 * TemplateInitializer from application code, not migrations.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TemplateMigrationTest {

    private lateinit var database: Database
    private lateinit var tempFile: java.io.File

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database for Flyway migrations
        tempFile = java.io.File.createTempFile("test_template_migration", ".db")
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
     * Test that V5 migration runs successfully and updates schema version.
     */
    @Test
    fun `test migration completes and reaches version 5`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)

        // Apply all migrations
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        // Validate we're at version 5 (or higher if more migrations added)
        val currentVersion = schemaManager.getCurrentVersion()
        assertTrue(currentVersion >= 5, "Should have at least version 5 after template migration (was $currentVersion)")
    }

    /**
     * Test the upgrade scenario: existing templates get simplified by V5 migration.
     *
     * This simulates a deployment that has old-style templates and is upgrading to simplified versions.
     */
    @Test
    fun `test V5 migration simplifies existing templates correctly`() {
        // Step 1: Apply migrations up to V4 (before template simplification)
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("4") // Stop at V4, before template simplification
            .load()

        flyway.migrate()

        // Step 2: Insert "old style" templates to simulate existing deployment
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Create a test template (simulating old deployment)
        statement.executeUpdate("""
            INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
            VALUES (
                randomblob(16),
                'Definition of Done',
                'Old template description',
                'TASK',
                1,
                1,
                1,
                'System',
                'old-tag',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
        """)

        // Get the template ID we just created
        val templateIdResult = statement.executeQuery("""
            SELECT id FROM templates WHERE name = 'Definition of Done'
        """)
        templateIdResult.next()
        val templateIdBytes = templateIdResult.getBytes("id")
        templateIdResult.close()

        // Insert old-style sections (more than the simplified version should have)
        // Use multiple individual INSERTs since SQLite doesn't support multi-row VALUES with BLOBs
        for (i in 1..4) {
            val insertStmt = connection.prepareStatement("""
                INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
                VALUES (randomblob(16), ?, 'Old Section $i', 'Old usage $i', 'Old content $i', 'MARKDOWN', ?, 1, 'old-section')
            """)
            insertStmt.setBytes(1, templateIdBytes)
            insertStmt.setInt(2, i - 1) // ordinal 0-indexed
            insertStmt.executeUpdate()
            insertStmt.close()
        }

        // Verify old sections exist (should be 4)
        val oldCountResult = statement.executeQuery("""
            SELECT COUNT(*) as count FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Definition of Done'
        """)
        oldCountResult.next()
        val oldSectionCount = oldCountResult.getInt("count")
        oldCountResult.close()
        assertEquals(4, oldSectionCount, "Should have 4 old-style sections before migration")

        statement.close()

        // Step 3: Now run V5 migration to simplify templates
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "V5 migration should succeed")

        // Step 4: Verify templates were simplified
        val verifyStatement = connection.createStatement()

        // Should now have only 2 sections (simplified)
        val newCountResult = verifyStatement.executeQuery("""
            SELECT COUNT(*) as count FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Definition of Done'
        """)
        newCountResult.next()
        val newSectionCount = newCountResult.getInt("count")
        newCountResult.close()

        assertEquals(2, newSectionCount, "Definition of Done should have 2 sections after simplification")

        // Verify new section titles
        val sectionsResult = verifyStatement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Definition of Done'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (sectionsResult.next()) {
            sections.add(sectionsResult.getString("title"))
        }
        sectionsResult.close()
        verifyStatement.close()

        assertEquals(2, sections.size, "Should have exactly 2 sections")
        assertEquals("Implementation Complete", sections[0], "First section should be 'Implementation Complete'")
        assertEquals("Production Ready", sections[1], "Second section should be 'Production Ready'")
    }

    /**
     * Test that V5 migration properly handles all 6 templates that were simplified.
     */
    @Test
    fun `test V5 migration handles all simplified templates`() {
        // Apply migrations up to V4
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("4")
            .load()

        flyway.migrate()

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Create all 6 templates that V5 will simplify
        val templatesToSimplify = listOf(
            "Definition of Done",
            "GitHub PR Workflow",
            "Context & Background",
            "Testing Strategy",
            "Requirements Specification",
            "Local Git Branching Workflow"
        )

        templatesToSimplify.forEach { templateName ->
            val targetType = if (templateName in listOf("Context & Background", "Requirements Specification")) {
                "FEATURE"
            } else {
                "TASK"
            }

            statement.executeUpdate("""
                INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
                VALUES (
                    randomblob(16),
                    '$templateName',
                    'Old template for $templateName',
                    '$targetType',
                    1,
                    1,
                    1,
                    'System',
                    'old-tag',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
            """)
        }

        statement.close()

        // Run V5 migration
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "V5 migration should succeed")

        // Verify all 6 templates have sections after migration
        val verifyStatement = connection.createStatement()

        templatesToSimplify.forEach { templateName ->
            val countResult = verifyStatement.executeQuery("""
                SELECT COUNT(*) as count FROM template_sections ts
                JOIN templates t ON ts.template_id = t.id
                WHERE t.name = '$templateName'
            """)
            countResult.next()
            val sectionCount = countResult.getInt("count")
            countResult.close()

            assertTrue(sectionCount >= 2, "Template '$templateName' should have at least 2 sections (has $sectionCount)")
        }

        verifyStatement.close()
    }

    /**
     * Test that V5 migration is idempotent - running it multiple times doesn't break anything.
     */
    @Test
    fun `test V5 migration is idempotent`() {
        // Apply migrations up to V4 and create template
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("4")
            .load()

        flyway.migrate()

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        statement.executeUpdate("""
            INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
            VALUES (
                randomblob(16),
                'Testing Strategy',
                'Old template',
                'TASK',
                1,
                1,
                1,
                'System',
                'old-tag',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
        """)

        statement.close()

        // Run V5 migration
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "First V5 migration should succeed")

        // Get section count after first migration
        val verifyStatement1 = connection.createStatement()
        val count1Result = verifyStatement1.executeQuery("""
            SELECT COUNT(*) as count FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Testing Strategy'
        """)
        count1Result.next()
        val sectionCountAfterFirst = count1Result.getInt("count")
        count1Result.close()
        verifyStatement1.close()

        // Run migration again (should be no-op)
        assertTrue(schemaManager.updateSchema(), "Second migration run should succeed")

        // Get section count after second migration
        val verifyStatement2 = connection.createStatement()
        val count2Result = verifyStatement2.executeQuery("""
            SELECT COUNT(*) as count FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Testing Strategy'
        """)
        count2Result.next()
        val sectionCountAfterSecond = count2Result.getInt("count")
        count2Result.close()
        verifyStatement2.close()

        assertEquals(
            sectionCountAfterFirst,
            sectionCountAfterSecond,
            "Section count should remain the same after running migration twice (idempotent)"
        )
        assertEquals(3, sectionCountAfterSecond, "Testing Strategy should have 3 sections")
    }

    /**
     * Test that V5 migration doesn't affect templates that aren't being simplified.
     */
    @Test
    fun `test V5 migration only affects targeted templates`() {
        // Apply migrations up to V4
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${tempFile.absolutePath}", "", "")
            .locations("classpath:db/migration")
            .target("4")
            .load()

        flyway.migrate()

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Create a template that V5 should NOT touch
        statement.executeUpdate("""
            INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
            VALUES (
                randomblob(16),
                'Custom User Template',
                'User-created template',
                'TASK',
                0,
                0,
                1,
                'User',
                'custom-tag',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
        """)

        // Get template ID
        val templateIdResult = statement.executeQuery("""
            SELECT id FROM templates WHERE name = 'Custom User Template'
        """)
        templateIdResult.next()
        val templateIdBytes = templateIdResult.getBytes("id")
        templateIdResult.close()

        // Add sections to user template
        for (i in 1..2) {
            val insertStmt = connection.prepareStatement("""
                INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
                VALUES (randomblob(16), ?, 'Custom Section $i', 'Custom usage', 'Custom content', 'MARKDOWN', ?, 1, 'custom')
            """)
            insertStmt.setBytes(1, templateIdBytes)
            insertStmt.setInt(2, i - 1) // ordinal 0-indexed
            insertStmt.executeUpdate()
            insertStmt.close()
        }

        statement.close()

        // Run V5 migration
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "V5 migration should succeed")

        // Verify user template sections remain unchanged
        val verifyStatement = connection.createStatement()
        val sectionsResult = verifyStatement.executeQuery("""
            SELECT title FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Custom User Template'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (sectionsResult.next()) {
            sections.add(sectionsResult.getString("title"))
        }
        sectionsResult.close()
        verifyStatement.close()

        assertEquals(2, sections.size, "User template should still have 2 sections")
        assertEquals("Custom Section 1", sections[0], "User sections should be unchanged")
        assertEquals("Custom Section 2", sections[1], "User sections should be unchanged")
    }
}
