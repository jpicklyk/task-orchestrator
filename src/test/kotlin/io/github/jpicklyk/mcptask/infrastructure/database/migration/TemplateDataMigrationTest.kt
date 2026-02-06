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
 * This tests the FRESH INSTALL scenario where Flyway migrations create the complete
 * schema AND initialize all 9 built-in templates with their sections.
 *
 * After V9 (template realignment), templates are:
 * 1. Definition of Done (TASK) - 2 sections
 * 2. Local Git Branching Workflow (TASK) - 3 sections
 * 3. GitHub PR Workflow (TASK) - 3 sections
 * 4. Context & Background (FEATURE) - 3 sections
 * 5. Test Plan (TASK) - 2 sections (was "Testing Strategy" with 3)
 * 6. Requirements Specification (FEATURE) - 3 sections
 * 7. Technical Approach (TASK) - 2 sections (was 3)
 * 8. Task Implementation (TASK) - 3 sections (was "Task Implementation Workflow")
 * 9. Bug Investigation (TASK) - 3 sections (was "Bug Investigation Workflow")
 *
 * Total sections expected: 24 (was 26 before V9 removed 2 redundant sections)
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

        // Apply all migrations using Flyway
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
            "Test Plan" to "TASK",
            "Requirements Specification" to "FEATURE",
            "Technical Approach" to "TASK",
            "Task Implementation" to "TASK",
            "Bug Investigation" to "TASK"
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
     * After V9: Technical Approach and Test Plan have 2 sections each (was 3).
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
            "Test Plan" to 2,
            "Requirements Specification" to 3,
            "Technical Approach" to 2,
            "Task Implementation" to 3,
            "Bug Investigation" to 3
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
     * Test that the total number of template sections is 24.
     * (Was 26, V9 removed Technical Decision Log and Testing Checkpoints)
     */
    @Test
    fun `test total template sections count is 24`() {
        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT COUNT(*) as count FROM template_sections
        """)
        result.next()
        val totalSections = result.getInt("count")
        result.close()

        statement.close()

        assertEquals(24, totalSections, "Should have 24 total template sections")
    }

    /**
     * Test that Definition of Done has correct section titles (renamed in V9).
     */
    @Test
    fun `test Definition of Done has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Completion Checklist",
            "Production Readiness"
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
     * Test that Test Plan has correct section titles (renamed from "Testing Strategy" in V9, deleted "Testing Checkpoints").
     */
    @Test
    fun `test Test Plan has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Test Coverage",
            "Acceptance Criteria"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Test Plan'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Test Plan should have expected sections in order")
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
     * Test that Technical Approach has correct section titles (renamed and reduced from 3 to 2 in V9).
     */
    @Test
    fun `test Technical Approach has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Technical Decisions",
            "Integration Considerations"
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
     * Test that Task Implementation has correct section titles (renamed from "Task Implementation Workflow" in V9).
     */
    @Test
    fun `test Task Implementation has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Analysis & Approach",
            "Implementation Notes",
            "Verification & Results"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Task Implementation'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Task Implementation should have expected sections in order")
    }

    /**
     * Test that Bug Investigation has correct section titles (renamed from "Bug Investigation Workflow" in V9).
     */
    @Test
    fun `test Bug Investigation has correct sections`() {
        val schemaManager = FlywayDatabaseSchemaManager(database)
        assertTrue(schemaManager.updateSchema(), "Migration should succeed")

        val expectedSections = listOf(
            "Investigation Findings",
            "Root Cause",
            "Fix & Verification"
        )

        val connection = database.connector().connection as Connection
        val statement = connection.createStatement()

        val result = statement.executeQuery("""
            SELECT title, ordinal FROM template_sections ts
            JOIN templates t ON ts.template_id = t.id
            WHERE t.name = 'Bug Investigation'
            ORDER BY ordinal
        """)

        val sections = mutableListOf<String>()
        while (result.next()) {
            sections.add(result.getString("title"))
        }
        result.close()
        statement.close()

        assertEquals(expectedSections, sections, "Bug Investigation should have expected sections in order")
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

        assertEquals(24, titled, "All 24 sections should have a title")
        assertEquals(24, described, "All 24 sections should have usage description")
        assertEquals(24, contentProvided, "All 24 sections should have content")
        assertEquals(24, formatSet, "All 24 sections should have content format set")
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
