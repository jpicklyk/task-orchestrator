package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class SQLiteSectionRepositoryTest {
    private lateinit var database: Database
    private lateinit var sectionRepository: SQLiteSectionRepository
    private lateinit var taskRepository: SQLiteTaskRepository
    private lateinit var databaseManager: DatabaseManager
    private lateinit var testTask: Task

    @BeforeEach
    fun setUp() {
        // Create a unique in-memory database for each test to ensure isolation
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repositories
        sectionRepository = SQLiteSectionRepository(databaseManager)
        taskRepository = SQLiteTaskRepository(databaseManager)

        // Create a test task to attach sections to
        runBlocking {
            testTask = Task(
                title = "Test Task",
                summary = "Task for section testing",
                status = TaskStatus.PENDING,
                priority = Priority.MEDIUM,
                complexity = 5
            )
            val createResult = taskRepository.create(testTask)
            assertTrue(createResult is Result.Success)
        }
    }

    @Test
    fun `should prevent duplicate ordinal when adding section`() = runBlocking {
        // Arrange - Create first section with ordinal 0
        val section1 = Section(
            entityType = EntityType.TASK,
            entityId = testTask.id,
            title = "First Section",
            usageDescription = "First section for testing",
            content = "Content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = emptyList()
        )

        val addResult1 = sectionRepository.addSection(EntityType.TASK, testTask.id, section1)
        assertTrue(addResult1 is Result.Success, "First section should be added successfully")

        // Act - Try to create second section with same ordinal
        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = testTask.id,
            title = "Second Section",
            usageDescription = "Second section with duplicate ordinal",
            content = "Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,  // Same ordinal as section1
            tags = emptyList()
        )

        val addResult2 = sectionRepository.addSection(EntityType.TASK, testTask.id, section2)

        // Assert
        assertTrue(addResult2 is Result.Error, "Should fail when adding section with duplicate ordinal")
        val error = (addResult2 as Result.Error).error
        assertTrue(error is RepositoryError.ValidationError, "Error should be a ValidationError")
        assertTrue(
            error.message.contains("ordinal 0 already exists"),
            "Error message should mention the duplicate ordinal"
        )
    }

    @Test
    fun `should prevent duplicate ordinal when updating section`() = runBlocking {
        // Arrange - Create two sections with different ordinals
        val section1 = Section(
            entityType = EntityType.TASK,
            entityId = testTask.id,
            title = "First Section",
            usageDescription = "First section",
            content = "Content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = emptyList()
        )

        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = testTask.id,
            title = "Second Section",
            usageDescription = "Second section",
            content = "Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1,
            tags = emptyList()
        )

        val addResult1 = sectionRepository.addSection(EntityType.TASK, testTask.id, section1)
        val addResult2 = sectionRepository.addSection(EntityType.TASK, testTask.id, section2)

        assertTrue(addResult1 is Result.Success)
        assertTrue(addResult2 is Result.Success)

        // Act - Try to update section2 to have the same ordinal as section1
        val updatedSection2 = (addResult2 as Result.Success).data.copy(ordinal = 0)
        val updateResult = sectionRepository.updateSection(updatedSection2)

        // Assert
        assertTrue(updateResult is Result.Error, "Should fail when updating to duplicate ordinal")
        val error = (updateResult as Result.Error).error
        assertTrue(error is RepositoryError.ValidationError, "Error should be a ValidationError")
        assertTrue(
            error.message.contains("ordinal 0 already exists"),
            "Error message should mention the duplicate ordinal"
        )
    }

    @Test
    fun `should allow updating section with same ordinal it already has`() = runBlocking {
        // Arrange - Create a section
        val section = Section(
            entityType = EntityType.TASK,
            entityId = testTask.id,
            title = "Test Section",
            usageDescription = "Test section",
            content = "Original content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = emptyList()
        )

        val addResult = sectionRepository.addSection(EntityType.TASK, testTask.id, section)
        assertTrue(addResult is Result.Success)
        val createdSection = (addResult as Result.Success).data

        // Act - Update the section's content but keep the same ordinal
        val updatedSection = createdSection.copy(content = "Updated content")
        val updateResult = sectionRepository.updateSection(updatedSection)

        // Assert
        assertTrue(updateResult is Result.Success, "Should allow updating section with its own ordinal")
        val updated = (updateResult as Result.Success).data
        assertEquals("Updated content", updated.content)
        assertEquals(0, updated.ordinal)
    }

    @Test
    fun `should successfully add sections with different ordinals`() = runBlocking {
        // Arrange
        val section1 = Section(
            entityType = EntityType.TASK,
            entityId = testTask.id,
            title = "Section 1",
            usageDescription = "First section",
            content = "Content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = emptyList()
        )

        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = testTask.id,
            title = "Section 2",
            usageDescription = "Second section",
            content = "Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1,
            tags = emptyList()
        )

        // Act
        val addResult1 = sectionRepository.addSection(EntityType.TASK, testTask.id, section1)
        val addResult2 = sectionRepository.addSection(EntityType.TASK, testTask.id, section2)

        // Assert
        assertTrue(addResult1 is Result.Success, "First section should be added successfully")
        assertTrue(addResult2 is Result.Success, "Second section with different ordinal should be added successfully")

        val sections = sectionRepository.getSectionsForEntity(EntityType.TASK, testTask.id)
        assertTrue(sections is Result.Success)
        assertEquals(2, (sections as Result.Success).data.size)
    }
}
