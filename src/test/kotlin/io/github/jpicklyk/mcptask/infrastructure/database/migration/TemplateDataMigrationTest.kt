package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Disabled
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import java.sql.Connection

/**
 * Tests for template data initialization that creates 9 built-in templates.
 *
 * This tests the FRESH INSTALL scenario for v2.0.0 where the single
 * V1__Initial_Schema.sql migration creates the complete schema AND
 * initializes all 9 built-in templates with their sections.
 *
 * Expected Templates (9):
 * 1. Definition of Done (TASK)
 * 2. Local Git Branching Workflow (TASK)
 * 3. GitHub PR Workflow (TASK)
 * 4. Context & Background (FEATURE)
 * 5. Testing Strategy (TASK)
 * 6. Requirements Specification (FEATURE)
 * 7. Technical Approach (TASK)
 * 8. Task Implementation Workflow (TASK)
 * 9. Bug Investigation Workflow (TASK)
 *
 * Total sections expected: 26 (Definition of Done has 2, all others have 3)
 *
 * Note: Since v2.0 hasn't been released yet, templates are included in V1
 * for maximum simplicity. Only one migration file needed for fresh installs.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TemplateDataMigrationTest {

    private lateinit var database: Database
    private lateinit var tempFile: java.io.File

    @BeforeEach
    fun setUp() {
        // Use file-based SQLite database for Flyway migrations
        tempFile = java.io.File.createTempFile("test_template_data_migration", ".db")
        tempFile.deleteOnExit()

        database = Database.connect(
            url = "jdbc:sqlite:${tempFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            setupConnection = { connection ->
                connection.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
            }
        )
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // Apply V1 migration using Flyway
        // V1__Initial_Schema.sql contains the complete v2.0.0 baseline:
        // - All database schema (12 tables, indexes, constraints)
        // - Template initialization (9 templates, 26 sections)
        // - Backward compatibility (migration_test_table)
        try {
            Flyway.configure()
                .dataSource("jdbc:sqlite:${tempFile.absolutePath}", null, null)
                .locations("classpath:db/migration")
                .validateMigrationNaming(false) // Disable strict validation
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
        } catch (e: org.flywaydb.core.api.FlywayException) {
            fail("Migration setup failed: ${e.message}")
        }
    }

    /**
     * Test that migrations complete successfully.
     * Migrations are already applied in setUp(), so this just validates the test setup.
     */
    @Test
    fun `test migrations complete successfully`() {
        // Migrations are applied in setUp() via Flyway
        // Verify the database has the expected tables
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Check that templates table exists and has data
        val result = statement.executeQuery("""
            SELECT COUNT(*) as count FROM templates
        """)
        result.next()
        val tableCount = result.getInt("count")
        result.close()
        statement.close()

        assertTrue(tableCount >= 0, "Templates table should exist and be queryable")
    }

    /**
     * Test that migration creates exactly 9 built-in templates.
     */
    @Test
    fun `test migration creates 9 built-in templates`() {
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Count all built-in templates
        val countResult = statement.executeQuery("""
            SELECT COUNT(*) as count FROM templates WHERE is_built_in = 1
        """)
        countResult.next()
        val templateCount = countResult.getInt("count")
        countResult.close()

        statement.close()

        assertEquals(9, templateCount, "Should have exactly 9 built-in templates")
    }

    /**
     * Test that all 9 templates have correct properties (isBuiltIn=true, isEnabled=true).
     */
    @Test
    fun `test all templates have correct built-in and enabled flags`() {

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        // Verify all built-in templates are enabled
        val result = statement.executeQuery("""
            SELECT
                COUNT(CASE WHEN is_built_in = 1 AND is_enabled = 1 THEN 1 END) as enabled_count,
                COUNT(CASE WHEN is_built_in = 1 THEN 1 END) as total_count
            FROM templates
        """)
        result.next()
        val enabledCount = result.getInt("enabled_count")
        val totalCount = result.getInt("total_count")
        result.close()

        statement.close()

        assertEquals(9, totalCount, "Should have 9 built-in templates")
        assertEquals(9, enabledCount, "All 9 built-in templates should be enabled")
    }

    /**
     * Test that each of the 9 expected templates exists with correct name and properties.
     */
    @Test
    fun `test all 9 expected templates exist with correct names`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedTemplates = mapOf(
            "Definition of Done" to "TASK",
            "Local Git Branching Workflow" to "TASK",
            "GitHub PR Workflow" to "TASK",
            "Context & Background" to "FEATURE",
            "Testing Strategy" to "TASK",
            "Requirements Specification" to "FEATURE",
            "Technical Approach" to "TASK",
            "Task Implementation Workflow" to "TASK",
            "Bug Investigation Workflow" to "TASK"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        expectedTemplates.forEach { (templateName, expectedType) ->
            val result = statement.executeQuery("""
                SELECT name, target_entity_type, is_built_in, is_protected, is_enabled
                FROM templates
                WHERE name = '$templateName'
            """)

            assertTrue(result.next(), "Template '$templateName' should exist")

            val name = result.getString("name")
            val targetType = result.getString("target_entity_type")
            val isBuiltIn = result.getBoolean("is_built_in")
            val isProtected = result.getBoolean("is_protected")
            val isEnabled = result.getBoolean("is_enabled")

            result.close()

            assertEquals(templateName, name, "Template name should match")
            assertEquals(expectedType, targetType, "Template target type should be $expectedType")
            assertTrue(isBuiltIn, "Template should be marked as built-in")
            assertTrue(isProtected, "Template should be marked as protected")
            assertTrue(isEnabled, "Template should be marked as enabled")
        }

        statement.close()
    }

    /**
     * Test that each template has the expected number of sections.
     * Definition of Done: 2 sections
     * Local Git Branching Workflow: 3 sections
     * GitHub PR Workflow: 3 sections
     * Context & Background: 3 sections
     * Testing Strategy: 3 sections
     * Requirements Specification: 3 sections
     * Technical Approach: 3 sections
     * Task Implementation Workflow: 3 sections
     * Bug Investigation Workflow: 3 sections
     */
    @Test
    fun `test all templates have correct section counts`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSectionCounts = mapOf(
            "Definition of Done" to 2,
            "Local Git Branching Workflow" to 3,
            "GitHub PR Workflow" to 3,
            "Context & Background" to 3,
            "Testing Strategy" to 3,
            "Requirements Specification" to 3,
            "Technical Approach" to 3,
            "Task Implementation Workflow" to 3,
            "Bug Investigation Workflow" to 3
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        expectedSectionCounts.forEach { (templateName, expectedCount) ->
            val result = statement.executeQuery("""
                SELECT COUNT(*) as count FROM template_sections ts
                JOIN templates t ON ts.template_id = t.id
                WHERE t.name = '$templateName'
            """)
            result.next()
            val sectionCount = result.getInt("count")
            result.close()

            assertEquals(expectedCount, sectionCount, "Template '$templateName' should have $expectedCount sections")
        }

        statement.close()
    }

    /**
     * Test that the total number of template sections is 26.
     */
    @Test
    fun `test total template sections count is 26`() {
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT COUNT(*) as count FROM template_sections
        """)
        result.next()
        val totalSections = result.getInt("count")
        result.close()

        statement.close()

        assertEquals(26, totalSections, "Should have 26 total template sections")
    }

    /**
     * Test that Definition of Done has correct section titles.
     */
    @Test
    fun `test Definition of Done has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Implementation Complete",
            "Production Ready"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Definition of Done'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Definition of Done should have expected sections in order")
    }

    /**
     * Test that Local Git Branching Workflow has correct section titles.
     */
    @Test
    fun `test Local Git Branching Workflow has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Create Branch",
            "Implement & Commit",
            "Verify & Finalize"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Local Git Branching Workflow'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Local Git Branching Workflow should have expected sections in order")
    }

    /**
     * Test that GitHub PR Workflow has correct section titles.
     */
    @Test
    fun `test GitHub PR Workflow has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Pre-Push Validation",
            "Create Pull Request",
            "Review & Merge"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'GitHub PR Workflow'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "GitHub PR Workflow should have expected sections in order")
    }

    /**
     * Test that Context & Background has correct section titles.
     */
    @Test
    fun `test Context & Background has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Why This Matters",
            "User Context",
            "Dependencies & Coordination"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Context & Background'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Context & Background should have expected sections in order")
    }

    /**
     * Test that Testing Strategy has correct section titles.
     */
    @Test
    fun `test Testing Strategy has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Test Coverage",
            "Acceptance Criteria",
            "Testing Checkpoints"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Testing Strategy'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Testing Strategy should have expected sections in order")
    }

    /**
     * Test that Requirements Specification has correct section titles.
     */
    @Test
    fun `test Requirements Specification has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Must-Have Requirements",
            "Nice-to-Have Features",
            "Constraints & Non-Functional Requirements"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Requirements Specification'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Requirements Specification should have expected sections in order")
    }

    /**
     * Test that Technical Approach has correct section titles.
     */
    @Test
    fun `test Technical Approach has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Implementation Planning Checklist",
            "Technical Decision Log",
            "Integration Points Checklist"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Technical Approach'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Technical Approach should have expected sections in order")
    }

    /**
     * Test that Task Implementation Workflow has correct section titles.
     */
    @Test
    fun `test Task Implementation Workflow has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Implementation Analysis",
            "Step-by-Step Implementation",
            "Testing & Validation"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Task Implementation Workflow'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Task Implementation Workflow should have expected sections in order")
    }

    /**
     * Test that Bug Investigation Workflow has correct section titles.
     */
    @Test
    fun `test Bug Investigation Workflow has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Investigation Process",
            "Root Cause Analysis",
            "Fix Implementation & Verification"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Bug Investigation Workflow'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Bug Investigation Workflow should have expected sections in order")
    }

    /**
     * Test that all template sections have required properties.
     */
    @Test
    fun `test all template sections have required properties`() {
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT
                COUNT(CASE WHEN title IS NOT NULL AND title != '' THEN 1 END) as titled,
                COUNT(CASE WHEN usage_description IS NOT NULL AND usage_description != '' THEN 1 END) as described,
                COUNT(CASE WHEN content_sample IS NOT NULL AND content_sample != '' THEN 1 END) as content_provided,
                COUNT(CASE WHEN content_format IS NOT NULL THEN 1 END) as format_set,
                COUNT(*) as total
            FROM template_sections
            WHERE template_id IN (SELECT id FROM templates WHERE is_built_in = 1)
        """)
        result.next()

        val titled = result.getInt("titled")
        val described = result.getInt("described")
        val contentProvided = result.getInt("content_provided")
        val formatSet = result.getInt("format_set")
        val total = result.getInt("total")

        result.close()
        statement.close()

        assertEquals(26, titled, "All 26 sections should have a title")
        assertEquals(26, described, "All 26 sections should have usage description")
        assertEquals(26, contentProvided, "All 26 sections should have content")
        assertEquals(26, formatSet, "All 26 sections should have content format set")
    }

    /**
     * Test that template descriptions are present and meaningful.
     */
    @Test
    fun `test all templates have meaningful descriptions`() {
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT
                COUNT(CASE WHEN description IS NOT NULL AND length(description) > 10 THEN 1 END) as meaningful_count,
                COUNT(*) as total
            FROM templates
            WHERE is_built_in = 1
        """)
        result.next()

        val meaningfulCount = result.getInt("meaningful_count")
        val totalCount = result.getInt("total")

        result.close()
        statement.close()

        assertEquals(9, totalCount, "Should have 9 templates")
        assertEquals(9, meaningfulCount, "All 9 templates should have meaningful descriptions (> 10 chars)")
    }

    /**
     * Test that templates have appropriate tags.
     */
    @Test
    fun `test all templates have tags`() {
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT
                COUNT(CASE WHEN tags IS NOT NULL AND tags != '' THEN 1 END) as tagged,
                COUNT(*) as total
            FROM templates
            WHERE is_built_in = 1
        """)
        result.next()

        val taggedCount = result.getInt("tagged")
        val totalCount = result.getInt("total")

        result.close()
        statement.close()

        assertEquals(9, totalCount, "Should have 9 templates")
        assertEquals(9, taggedCount, "All 9 templates should have tags")
    }

    /**
     * Test that TASK templates outnumber FEATURE templates correctly (7 TASK, 2 FEATURE).
     */
    @Test
    fun `test correct distribution of TASK and FEATURE templates`() {
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT
                target_entity_type,
                COUNT(*) as count
            FROM templates
            WHERE is_built_in = 1
            GROUP BY target_entity_type
        """)

        val counts = mutableMapOf<String, Int>()
        while (result.next()) {
            counts[result.getString("target_entity_type")] = result.getInt("count")
        }
        result.close()
        statement.close()

        assertEquals(7, counts["TASK"] ?: 0, "Should have 7 TASK templates")
        assertEquals(2, counts["FEATURE"] ?: 0, "Should have 2 FEATURE templates")
    }

    /**
     * Test that section ordinals are sequential and correct.
     */
    @Test
    fun `test section ordinals are sequential within each template`() {
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT t.name, GROUP_CONCAT(ts.ordinal) as ordinals
            FROM templates t
            LEFT JOIN template_sections ts ON t.id = ts.template_id
            WHERE t.is_built_in = 1
            GROUP BY t.id
        """)

        while (result.next()) {
            val templateName = result.getString("name")
            val ordinalsStr = result.getString("ordinals")
            assertNotNull(ordinalsStr, "Template '$templateName' should have sections")

            val ordinals = ordinalsStr.split(",").map { it.toInt() }
            val expectedOrdinals = (0 until ordinals.size).toList()

            assertEquals(expectedOrdinals, ordinals.sorted(), "Template '$templateName' should have sequential ordinals")
        }
        result.close()
        statement.close()
    }
}
