package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteSectionRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTemplateRepository
import io.github.jpicklyk.mcptask.infrastructure.database.schema.FeaturesTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TaskTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TemplateSectionsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TemplatesTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.util.*

/**
 * Integration test for template ordinal conflict resolution.
 * Tests the real database behavior when applying templates to entities with existing sections.
 */
class TemplateOrdinalConflictTest {

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var sectionRepository: SQLiteSectionRepository
    private lateinit var templateRepository: SQLiteTemplateRepository
    private lateinit var dbFile: File

    private val testTaskId = UUID.randomUUID()
    private val testFeatureId = UUID.randomUUID()
    private val testTemplate1Id = UUID.randomUUID()
    private val testTemplate2Id = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create a unique database file for this test
        dbFile = File.createTempFile("test_ordinal_conflict", ".db")
        dbFile.deleteOnExit()

        // Initialize database and repositories
        database = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        sectionRepository = SQLiteSectionRepository(databaseManager)
        templateRepository = SQLiteTemplateRepository(sectionRepository)

        // Create tables
        transaction(database) {

            // Insert test task
            TaskTable.insert {
                it[id] = testTaskId
                it[title] = "Test Task"
                it[summary] = "A test task for ordinal conflict testing"
                it[status] = TaskStatus.PENDING
                it[priority] = Priority.MEDIUM
                it[complexity] = 5
                it[createdAt] = Instant.now()
                it[modifiedAt] = Instant.now()
                it[searchVector] = "test task ordinal conflict testing"
            }

            // Insert test feature
            FeaturesTable.insert {
                it[id] = testFeatureId
                it[name] = "Test Feature"
                it[summary] = "A test feature for ordinal conflict testing"
                it[status] = FeatureStatus.PLANNING
                it[priority] = Priority.HIGH
                it[createdAt] = Instant.now()
                it[modifiedAt] = Instant.now()
                it[searchVector] = "test feature ordinal conflict testing"
            }

            // Create test template 1
            TemplatesTable.insert {
                it[id] = testTemplate1Id
                it[name] = "Test Template 1"
                it[description] = "First test template"
                it[targetEntityType] = EntityType.TASK.name
                it[isBuiltIn] = false
                it[isProtected] = false
                it[isEnabled] = true
                it[createdBy] = "Test"
                it[tags] = "test,template1"
                it[createdAt] = Instant.now()
                it[modifiedAt] = Instant.now()
            }

            // Create template 1 sections
            listOf(
                Triple("Section A", "First section", 0),
                Triple("Section B", "Second section", 1),
                Triple("Section C", "Third section", 2)
            ).forEach { (title, description, ordinal) ->
                TemplateSectionsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[templateId] = testTemplate1Id
                    it[TemplateSectionsTable.title] = title
                    it[usageDescription] = description
                    it[contentSample] = "Sample content for $title"
                    it[contentFormat] = ContentFormat.MARKDOWN.name
                    it[TemplateSectionsTable.ordinal] = ordinal
                    it[isRequired] = false
                    it[TemplateSectionsTable.tags] = "test"
                }
            }

            // Create test template 2
            TemplatesTable.insert {
                it[id] = testTemplate2Id
                it[name] = "Test Template 2"
                it[description] = "Second test template"
                it[targetEntityType] = EntityType.TASK.name
                it[isBuiltIn] = false
                it[isProtected] = false
                it[isEnabled] = true
                it[createdBy] = "Test"
                it[tags] = "test,template2"
                it[createdAt] = Instant.now()
                it[modifiedAt] = Instant.now()
            }

            // Create template 2 sections
            listOf(
                Triple("Section X", "X section", 0),
                Triple("Section Y", "Y section", 1)
            ).forEach { (title, description, ordinal) ->
                TemplateSectionsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[templateId] = testTemplate2Id
                    it[TemplateSectionsTable.title] = title
                    it[usageDescription] = description
                    it[contentSample] = "Sample content for $title"
                    it[contentFormat] = ContentFormat.MARKDOWN.name
                    it[TemplateSectionsTable.ordinal] = ordinal
                    it[isRequired] = false
                    it[TemplateSectionsTable.tags] = "test"
                }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `test applying template to empty entity`() = runBlocking {
        // Apply template to task with no existing sections
        val result = templateRepository.applyTemplate(testTemplate1Id, EntityType.TASK, testTaskId)

        assertTrue(result is Result.Success)
        val appliedSections = (result as Result.Success).data
        assertEquals(3, appliedSections.size)

        // Verify sections were created with correct ordinals
        val sections = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(sections is Result.Success)
        val createdSections = (sections as Result.Success).data

        assertEquals(3, createdSections.size)
        assertEquals("Section A", createdSections[0].title)
        assertEquals(0, createdSections[0].ordinal)
        assertEquals("Section B", createdSections[1].title)
        assertEquals(1, createdSections[1].ordinal)
        assertEquals("Section C", createdSections[2].title)
        assertEquals(2, createdSections[2].ordinal)
    }

    @Test
    fun `test applying template to entity with existing sections`() = runBlocking {
        // First, add some existing sections to the task
        val existingSection1 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = testTaskId,
            title = "Existing Section 1",
            usageDescription = "Pre-existing section",
            content = "Existing content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("existing")
        )

        val existingSection2 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = testTaskId,
            title = "Existing Section 2",
            usageDescription = "Another pre-existing section",
            content = "Existing content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1,
            tags = listOf("existing")
        )

        // Add existing sections
        sectionRepository.addSection(EntityType.TASK, testTaskId, existingSection1)
        sectionRepository.addSection(EntityType.TASK, testTaskId, existingSection2)

        // Now apply template - should start ordinals at 2
        val result = templateRepository.applyTemplate(testTemplate1Id, EntityType.TASK, testTaskId)

        assertTrue(result is Result.Success)
        val appliedSections = (result as Result.Success).data
        assertEquals(3, appliedSections.size)

        // Verify all sections exist with correct ordinals
        val allSections = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(allSections is Result.Success)
        val sections = (allSections as Result.Success).data

        assertEquals(5, sections.size) // 2 existing + 3 from template

        // Check existing sections are unchanged
        assertEquals("Existing Section 1", sections[0].title)
        assertEquals(0, sections[0].ordinal)
        assertEquals("Existing Section 2", sections[1].title)
        assertEquals(1, sections[1].ordinal)

        // Check template sections start at ordinal 2
        assertEquals("Section A", sections[2].title)
        assertEquals(2, sections[2].ordinal)
        assertEquals("Section B", sections[3].title)
        assertEquals(3, sections[3].ordinal)
        assertEquals("Section C", sections[4].title)
        assertEquals(4, sections[4].ordinal)
    }

    @Test
    fun `test applying multiple templates to empty entity`() = runBlocking {
        // Apply both templates to task with no existing sections
        val result = templateRepository.applyMultipleTemplates(
            listOf(testTemplate1Id, testTemplate2Id),
            EntityType.TASK,
            testTaskId
        )

        assertTrue(result is Result.Success)
        val appliedTemplates = (result as Result.Success).data
        assertEquals(2, appliedTemplates.size)

        // Verify sections from both templates were created
        val sections = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(sections is Result.Success)
        val createdSections = (sections as Result.Success).data

        assertEquals(5, createdSections.size) // 3 from template1 + 2 from template2

        // Check template 1 sections (ordinals 0-2)
        assertEquals("Section A", createdSections[0].title)
        assertEquals(0, createdSections[0].ordinal)
        assertEquals("Section B", createdSections[1].title)
        assertEquals(1, createdSections[1].ordinal)
        assertEquals("Section C", createdSections[2].title)
        assertEquals(2, createdSections[2].ordinal)

        // Check template 2 sections (ordinals 3-4)
        assertEquals("Section X", createdSections[3].title)
        assertEquals(3, createdSections[3].ordinal)
        assertEquals("Section Y", createdSections[4].title)
        assertEquals(4, createdSections[4].ordinal)
    }

    @Test
    fun `test applying multiple templates to entity with existing sections`() = runBlocking {
        // Add existing sections first
        val existingSection = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = testTaskId,
            title = "Pre-existing",
            usageDescription = "Existing section",
            content = "Existing content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("existing")
        )

        sectionRepository.addSection(EntityType.TASK, testTaskId, existingSection)

        // Apply both templates
        val result = templateRepository.applyMultipleTemplates(
            listOf(testTemplate1Id, testTemplate2Id),
            EntityType.TASK,
            testTaskId
        )

        assertTrue(result is Result.Success)
        val appliedTemplates = (result as Result.Success).data
        assertEquals(2, appliedTemplates.size)

        // Verify ordinal sequence is correct
        val sections = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(sections is Result.Success)
        val allSections = (sections as Result.Success).data

        assertEquals(6, allSections.size) // 1 existing + 3 from template1 + 2 from template2

        // Check existing section is unchanged
        assertEquals("Pre-existing", allSections[0].title)
        assertEquals(0, allSections[0].ordinal)

        // Check template 1 sections (ordinals 1-3)
        assertEquals("Section A", allSections[1].title)
        assertEquals(1, allSections[1].ordinal)
        assertEquals("Section B", allSections[2].title)
        assertEquals(2, allSections[2].ordinal)
        assertEquals("Section C", allSections[3].title)
        assertEquals(3, allSections[3].ordinal)

        // Check template 2 sections (ordinals 4-5)
        assertEquals("Section X", allSections[4].title)
        assertEquals(4, allSections[4].ordinal)
        assertEquals("Section Y", allSections[5].title)
        assertEquals(5, allSections[5].ordinal)
    }

    @Test
    fun `test getMaxOrdinal with no sections`() = runBlocking {
        val result = sectionRepository.getMaxOrdinal(EntityType.TASK, testTaskId)
        assertTrue(result is Result.Success)
        assertEquals(-1, (result as Result.Success).data)
    }

    @Test
    fun `test getMaxOrdinal with existing sections`() = runBlocking {
        // Add sections with non-sequential ordinals
        val section1 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = testTaskId,
            title = "Section 1",
            usageDescription = "First section",
            content = "Content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = emptyList()
        )

        val section2 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = testTaskId,
            title = "Section 2",
            usageDescription = "Second section",
            content = "Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 5, // Non-sequential
            tags = emptyList()
        )

        sectionRepository.addSection(EntityType.TASK, testTaskId, section1)
        sectionRepository.addSection(EntityType.TASK, testTaskId, section2)

        val result = sectionRepository.getMaxOrdinal(EntityType.TASK, testTaskId)
        assertTrue(result is Result.Success)
        assertEquals(5, (result as Result.Success).data)
    }

    @Test
    fun `test template application with scattered ordinals`() = runBlocking {
        // Add sections with scattered ordinals
        val sections = listOf(
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Gap Section 1",
                usageDescription = "Section with ordinal 0",
                content = "Content",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                tags = emptyList()
            ),
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Gap Section 2",
                usageDescription = "Section with ordinal 10",
                content = "Content",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 10, // Large gap
                tags = emptyList()
            )
        )

        sections.forEach { section ->
            sectionRepository.addSection(EntityType.TASK, testTaskId, section)
        }

        // Apply template - should start at ordinal 11
        val result = templateRepository.applyTemplate(testTemplate1Id, EntityType.TASK, testTaskId)
        assertTrue(result is Result.Success)

        // Verify template sections start after the highest existing ordinal
        val allSections = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(allSections is Result.Success)
        val finalSections = (allSections as Result.Success).data

        assertEquals(5, finalSections.size) // 2 existing + 3 from template

        // Check that template sections start at ordinal 11
        val templateSections = finalSections.filter { it.title.startsWith("Section") }
        assertEquals(3, templateSections.size)
        assertEquals(11, templateSections[0].ordinal) // Section A
        assertEquals(12, templateSections[1].ordinal) // Section B
        assertEquals(13, templateSections[2].ordinal) // Section C
    }

    @Test
    fun `test applying template to feature works correctly`() = runBlocking {
        // Test that the fix works for features too
        val result = templateRepository.applyTemplate(testTemplate1Id, EntityType.FEATURE, testFeatureId)

        // This should fail because our test template is for TASK entity type
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).error.message.contains("Template target entity type"))
    }

    @Test
    fun `test template application preserves section order within template`() = runBlocking {
        // Apply template and verify that sections maintain their relative order
        val result = templateRepository.applyTemplate(testTemplate1Id, EntityType.TASK, testTaskId)
        assertTrue(result is Result.Success)

        val sections = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(sections is Result.Success)
        val createdSections = (sections as Result.Success).data

        // Verify the sections are in the correct order
        assertEquals(3, createdSections.size)
        assertTrue(createdSections[0].ordinal < createdSections[1].ordinal)
        assertTrue(createdSections[1].ordinal < createdSections[2].ordinal)

        // Verify the titles are in the correct template order
        assertEquals("Section A", createdSections[0].title)
        assertEquals("Section B", createdSections[1].title)
        assertEquals("Section C", createdSections[2].title)
    }
}
