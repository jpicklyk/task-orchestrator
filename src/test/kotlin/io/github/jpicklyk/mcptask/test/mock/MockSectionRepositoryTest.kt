package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class MockSectionRepositoryTest {

    private lateinit var repository: MockSectionRepository

    @BeforeEach
    fun setup() {
        repository = MockSectionRepository()
        repository.clear()
    }

    @Test
    fun `addSection should add valid section`() = runBlocking {
        // Arrange
        val entityId = UUID.randomUUID()
        val section = Section(
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Test Section",
            usageDescription = "For testing",
            content = "Test content",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 0
        )

        // Act
        val result = repository.addSection(EntityType.TASK, entityId, section)

        // Assert
        assertTrue(result is Result.Success)
        val addedSection = (result as Result.Success).data
        assertEquals(section.title, addedSection.title)
        assertEquals(section.content, addedSection.content)

        // Verify we can retrieve it
        val retrieveResult = repository.getSectionsForEntity(EntityType.TASK, entityId)
        assertTrue(retrieveResult is Result.Success)
        val sections = (retrieveResult as Result.Success).data
        assertEquals(1, sections.size)
        assertEquals(section.title, sections[0].title)
    }

    @Test
    fun `updateSection should update existing section`() = runBlocking {
        // Arrange
        val entityId = UUID.randomUUID()
        val section = Section(
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Original Title",
            usageDescription = "Original description",
            content = "Original content",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 0
        )

        // Add the section first
        val addResult = repository.addSection(EntityType.TASK, entityId, section)
        assertTrue(addResult is Result.Success)

        val sectionId = (addResult as Result.Success).data.id
        val updatedSection = section.copy(
            id = sectionId,
            title = "Updated Title",
            content = "Updated content"
        )

        // Act
        val updateResult = repository.updateSection(updatedSection)

        // Assert
        assertTrue(updateResult is Result.Success)
        val result = (updateResult as Result.Success).data
        assertEquals("Updated Title", result.title)
        assertEquals("Updated content", result.content)

        // Verify the update is persistent
        val getResult = repository.getSection(sectionId)
        assertTrue(getResult is Result.Success)
        assertEquals("Updated Title", (getResult as Result.Success).data.title)
    }

    @Test
    fun `deleteSection should remove section`() = runBlocking {
        // Arrange
        val entityId = UUID.randomUUID()
        val section = Section(
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Test Section",
            usageDescription = "For testing",
            content = "Test content",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 0
        )

        // Add the section first
        val addResult = repository.addSection(EntityType.TASK, entityId, section)
        assertTrue(addResult is Result.Success)

        val sectionId = (addResult as Result.Success).data.id

        // Act
        val deleteResult = repository.deleteSection(sectionId)

        // Assert
        assertTrue(deleteResult is Result.Success)
        assertTrue((deleteResult as Result.Success).data)

        // Verify the section is gone
        val getResult = repository.getSection(sectionId)
        assertTrue(getResult is Result.Error)
        assertTrue((getResult as Result.Error).error is RepositoryError.NotFound)
    }

    @Test
    fun `getSectionsForEntity should return all sections for entity`() = runBlocking {
        // Arrange
        val entityId = UUID.randomUUID()
        val section1 = Section(
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Section 1",
            usageDescription = "First section",
            content = "Content 1",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 0
        )

        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Section 2",
            usageDescription = "Second section",
            content = "Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )

        // Add sections
        repository.addSection(EntityType.TASK, entityId, section1)
        repository.addSection(EntityType.TASK, entityId, section2)

        // Act
        val result = repository.getSectionsForEntity(EntityType.TASK, entityId)

        // Assert
        assertTrue(result is Result.Success)
        val sections = (result as Result.Success).data
        assertEquals(2, sections.size)
        assertEquals("Section 1", sections[0].title)
        assertEquals("Section 2", sections[1].title)
    }

    @Test
    fun `reorderSections should update section ordinals`() = runBlocking {
        // Arrange
        val entityId = UUID.randomUUID()
        val section1 = Section(
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Section 1",
            usageDescription = "First section",
            content = "Content 1",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 0
        )

        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Section 2",
            usageDescription = "Second section",
            content = "Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )

        // Add sections
        val addResult1 = repository.addSection(EntityType.TASK, entityId, section1)
        val addResult2 = repository.addSection(EntityType.TASK, entityId, section2)

        val section1Id = (addResult1 as Result.Success).data.id
        val section2Id = (addResult2 as Result.Success).data.id

        // Act - swap the order
        val reorderResult = repository.reorderSections(
            EntityType.TASK,
            entityId,
            listOf(section2Id, section1Id)
        )

        // Assert
        assertTrue(reorderResult is Result.Success)

        // Verify the new order
        val getResult = repository.getSectionsForEntity(EntityType.TASK, entityId)
        assertTrue(getResult is Result.Success)
        val sections = (getResult as Result.Success).data
        assertEquals(2, sections.size)
        assertEquals("Section 2", sections[0].title) // Now first
        assertEquals("Section 1", sections[1].title) // Now second
    }
}
