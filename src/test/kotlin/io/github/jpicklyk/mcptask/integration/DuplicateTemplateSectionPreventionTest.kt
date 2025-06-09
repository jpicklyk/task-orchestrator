package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*

/**
 * Integration test for preventing duplicate sections when applying the same template multiple times.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateTemplateSectionPreventionTest {
    private lateinit var database: Database
    private lateinit var taskRepository: TaskRepository
    private lateinit var templateRepository: TemplateRepository
    private lateinit var sectionRepository: SectionRepository
    private lateinit var databaseManager: DatabaseManager

    // Test data IDs
    private val testTaskId = UUID.randomUUID()
    private val testTemplateId1 = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create a unique in-memory database for each test
        val dbName = "test_duplicate_prevention_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repositories
        sectionRepository = SQLiteSectionRepository(databaseManager)
        taskRepository = SQLiteTaskRepository(databaseManager)
        templateRepository = SQLiteTemplateRepository(sectionRepository)

        // Create test entities
        runBlocking {
            createTestTask()
            createTestTemplate()
        }
    }

    private suspend fun createTestTask() {
        val testTask = Task(
            id = testTaskId,
            title = "Test Task for Duplicate Prevention",
            summary = "This is a test task for testing duplicate section prevention",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 5
        )
        val taskResult = taskRepository.create(testTask)
        assertTrue(taskResult.isSuccess(), "Failed to create test task")
    }

    private suspend fun createTestTemplate() {
        // Create test template
        val testTemplate1 = Template(
            id = testTemplateId1,
            name = "Test Template 1",
            description = "First test template for duplicate prevention testing",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "TestUser",
            tags = listOf("test", "template1")
        )
        
        val template1Result = templateRepository.createTemplate(testTemplate1)
        assertTrue(template1Result.isSuccess(), "Failed to create test template 1")

        // Add sections to template 1
        addSectionsToTemplate()
    }

    private suspend fun addSectionsToTemplate() {
        val template1Sections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = testTemplateId1,
                title = "Requirements",
                usageDescription = "Key requirements for this task",
                contentSample = "List all requirements here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("requirements")
            ),
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = testTemplateId1,
                title = "Implementation Notes",
                usageDescription = "Technical details for implementation",
                contentSample = "Describe implementation approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("implementation")
            )
        )

        template1Sections.forEach { section ->
            val result = templateRepository.addTemplateSection(testTemplateId1, section)
            assertTrue(result.isSuccess(), "Failed to add section '${section.title}' to template 1")
        }
    }

    @Test
    fun `applying the same template twice should prevent duplicates`() = runBlocking {
        // First application - should succeed
        val firstApplication = templateRepository.applyTemplate(testTemplateId1, EntityType.TASK, testTaskId)
        assertTrue(firstApplication.isSuccess(), "First template application should succeed")
        
        val firstSections = (firstApplication as Result.Success).data
        assertEquals(2, firstSections.size, "First application should create 2 sections")

        // Verify sections were created in the database
        val sectionsAfterFirst = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(sectionsAfterFirst.isSuccess(), "Should be able to retrieve sections after first application")
        assertEquals(2, (sectionsAfterFirst as Result.Success).data.size, "Should have 2 sections after first application")

        // Second application - should prevent duplicates and return an error
        val secondApplication = templateRepository.applyTemplate(testTemplateId1, EntityType.TASK, testTaskId)
        
        // Should return an error indicating duplicates
        assertTrue(secondApplication.isError(), "Second template application should fail due to duplicates")
        val error = (secondApplication as Result.Error).error
        assertTrue(
            error.message.contains("already exist", ignoreCase = true) ||
            error.message.contains("duplicate", ignoreCase = true),
            "Error message should indicate duplicate sections: ${error.message}"
        )
        
        // Verify final state - should still have only the original 2 sections
        val sectionsAfterSecond = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(sectionsAfterSecond.isSuccess(), "Should be able to retrieve sections after second application")
        val finalSections = (sectionsAfterSecond as Result.Success).data
        
        assertEquals(2, finalSections.size, "Should still have only 2 sections after failed duplicate application")
        
        // Check that we don't have exactly duplicate sections by title
        val sectionTitles = finalSections.map { it.title }
        val uniqueTitles = sectionTitles.toSet()
        assertEquals(uniqueTitles.size, sectionTitles.size, "Should not have duplicate section titles")
        
        // Verify expected section titles are present
        assertTrue(sectionTitles.contains("Requirements"), "Should have Requirements section")
        assertTrue(sectionTitles.contains("Implementation Notes"), "Should have Implementation Notes section")
    }

    @Test
    fun `applying different templates should work even after duplicate prevention`() = runBlocking {
        // Create second template with different section titles
        val testTemplateId2 = UUID.randomUUID()
        val testTemplate2 = Template(
            id = testTemplateId2,
            name = "Test Template 2",
            description = "Second test template with different sections",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "TestUser",
            tags = listOf("test", "template2")
        )
        
        val template2Result = templateRepository.createTemplate(testTemplate2)
        assertTrue(template2Result.isSuccess(), "Failed to create test template 2")

        // Add different sections to template 2
        val template2Sections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = testTemplateId2,
                title = "Design Documentation",
                usageDescription = "Design details for this task",
                contentSample = "Describe design approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("design")
            ),
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = testTemplateId2,
                title = "Acceptance Criteria",
                usageDescription = "Criteria for task completion",
                contentSample = "List acceptance criteria here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("acceptance")
            )
        )

        template2Sections.forEach { section ->
            val result = templateRepository.addTemplateSection(testTemplateId2, section)
            assertTrue(result.isSuccess(), "Failed to add section '${section.title}' to template 2")
        }

        // Apply first template
        val firstApplication = templateRepository.applyTemplate(testTemplateId1, EntityType.TASK, testTaskId)
        assertTrue(firstApplication.isSuccess(), "First template application should succeed")

        // Attempt to apply first template again (should fail)
        val duplicateApplication = templateRepository.applyTemplate(testTemplateId1, EntityType.TASK, testTaskId)
        assertTrue(duplicateApplication.isError(), "Duplicate template application should fail")

        // Apply second template (should succeed since it has different section titles)
        val secondApplication = templateRepository.applyTemplate(testTemplateId2, EntityType.TASK, testTaskId)
        assertTrue(secondApplication.isSuccess(), "Different template application should succeed")

        // Verify final state
        val finalSections = sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId)
        assertTrue(finalSections.isSuccess(), "Should retrieve final sections")
        val sections = (finalSections as Result.Success).data

        assertEquals(4, sections.size, "Should have 4 sections total (2 from each template)")

        val sectionTitles = sections.map { it.title }.toSet()
        assertTrue(sectionTitles.contains("Requirements"), "Should have Requirements from template 1")
        assertTrue(sectionTitles.contains("Implementation Notes"), "Should have Implementation Notes from template 1")
        assertTrue(sectionTitles.contains("Design Documentation"), "Should have Design Documentation from template 2")
        assertTrue(sectionTitles.contains("Acceptance Criteria"), "Should have Acceptance Criteria from template 2")
    }

    @Test
    fun `applying multiple templates with some duplicates should handle appropriately`() = runBlocking {
        // Apply first template
        val firstApplication = templateRepository.applyTemplate(testTemplateId1, EntityType.TASK, testTaskId)
        assertTrue(firstApplication.isSuccess(), "First template application should succeed")

        // Create a second template with different sections
        val testTemplateId2 = UUID.randomUUID()
        val testTemplate2 = Template(
            id = testTemplateId2,
            name = "Test Template 2",
            description = "Second test template with unique sections",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "TestUser",
            tags = listOf("test", "template2")
        )
        
        val template2Result = templateRepository.createTemplate(testTemplate2)
        assertTrue(template2Result.isSuccess(), "Failed to create test template 2")

        // Add sections to template 2
        val template2Sections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = testTemplateId2,
                title = "Design Documentation",
                usageDescription = "Design details for this task",
                contentSample = "Describe design approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("design")
            )
        )

        template2Sections.forEach { section ->
            val result = templateRepository.addTemplateSection(testTemplateId2, section)
            assertTrue(result.isSuccess(), "Failed to add section '${section.title}' to template 2")
        }

        // Try to apply multiple templates where one would cause duplicates
        val multipleApplication = templateRepository.applyMultipleTemplates(
            listOf(testTemplateId1, testTemplateId2), // testTemplateId1 already applied
            EntityType.TASK,
            testTaskId
        )

        // The application should handle this appropriately - either succeed with just the non-duplicate
        // or fail with an appropriate error message
        if (multipleApplication.isSuccess()) {
            val results = (multipleApplication as Result.Success).data
            // Should only contain the template that doesn't cause duplicates
            assertFalse(results.containsKey(testTemplateId1), "Should not apply duplicate template")
            assertTrue(results.containsKey(testTemplateId2), "Should apply non-duplicate template")
        } else {
            val error = (multipleApplication as Result.Error).error
            assertTrue(
                error.message.contains("duplicate", ignoreCase = true) ||
                error.message.contains("already exist", ignoreCase = true),
                "Error should indicate duplicate issue: ${error.message}"
            )
        }
    }
}
