package io.github.jpicklyk.mcptask.domain.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class ProjectTest {

    @Test
    fun `should create a valid project`() {
        // Arrange
        val name = "Test Project"
        val summary = "A test project"

        // Act
        val project = Project(
            name = name,
            summary = summary
        )

        // Assert
        assertEquals(name, project.name)
        assertEquals(summary, project.summary)
        assertEquals(ProjectStatus.PLANNING, project.status)
        assertTrue(project.tags.isEmpty())
        assertNotNull(project.id)
        assertNotNull(project.createdAt)
        assertNotNull(project.modifiedAt)
    }

    @Test
    fun `should create a project with all properties`() {
        // Arrange
        val id = UUID.randomUUID()
        val name = "Comprehensive Project"
        val summary = "A project with all properties specified"
        val status = ProjectStatus.IN_DEVELOPMENT
        val tags = listOf("important", "frontend")
        val createdAt = Instant.now().minusSeconds(3600) // 1 hour ago
        val modifiedAt = Instant.now()

        // Act
        val project = Project(
            id = id,
            name = name,
            summary = summary,
            status = status,
            createdAt = createdAt,
            modifiedAt = modifiedAt,
            tags = tags
        )

        // Assert
        assertEquals(id, project.id)
        assertEquals(name, project.name)
        assertEquals(summary, project.summary)
        assertEquals(status, project.status)
        assertEquals(createdAt, project.createdAt)
        assertEquals(modifiedAt, project.modifiedAt)
        assertEquals(tags, project.tags)
    }

    @Test
    fun `should throw exception when name is empty`() {
        // Act & Assert
        val exception = assertThrows<IllegalArgumentException> {
            Project(
                name = "",
                summary = "Valid summary"
            )
        }

        // Assert
        assertEquals("Project name cannot be empty", exception.message)
    }

    @Test
    fun `should allow empty summary for agent-generated content`() {
        // Summary is agent-generated and defaults to empty
        val project = Project(
            name = "Valid name",
            summary = ""
        )

        // Assert
        assertEquals("", project.summary)
    }

    @Test
    fun `should update project properties correctly`() {
        // Arrange
        val project = Project(
            name = "Original Name",
            summary = "Original Summary",
            status = ProjectStatus.PLANNING,
            tags = listOf("original")
        )
        val originalModifiedAt = project.modifiedAt

        // Wait to ensure the timestamps are different
        Thread.sleep(10)

        // Act
        val updatedProject = project.update(
            name = "Updated Name",
            summary = "Updated Summary",
            status = ProjectStatus.IN_DEVELOPMENT,
            tags = listOf("updated", "important")
        )

        // Assert
        assertEquals("Updated Name", updatedProject.name)
        assertEquals("Updated Summary", updatedProject.summary)
        assertEquals(ProjectStatus.IN_DEVELOPMENT, updatedProject.status)
        assertEquals(listOf("updated", "important"), updatedProject.tags)
        assertEquals(project.id, updatedProject.id)
        assertEquals(project.createdAt, updatedProject.createdAt)
        assertTrue(updatedProject.modifiedAt.isAfter(originalModifiedAt))
    }

    @Test
    fun `update should not change unspecified properties`() {
        // Arrange
        val project = Project(
            name = "Original Name",
            summary = "Original Summary",
            status = ProjectStatus.PLANNING,
            tags = listOf("original")
        )

        // Act
        val updatedProject = project.update(
            name = "Updated Name"
        )

        // Assert
        assertEquals("Updated Name", updatedProject.name)
        assertEquals("Original Summary", updatedProject.summary)
        assertEquals(ProjectStatus.PLANNING, updatedProject.status)
        assertEquals(listOf("original"), updatedProject.tags)
    }

    @Test
    fun `factory method should create valid project`() {
        // Arrange & Act
        val project = Project.create(
            name = "Factory Project",
            summary = "Created with factory method",
            status = ProjectStatus.COMPLETED,
            tags = listOf("factory", "test")
        )

        // Assert
        assertEquals("Factory Project", project.name)
        assertEquals("Created with factory method", project.summary)
        assertEquals(ProjectStatus.COMPLETED, project.status)
        assertEquals(listOf("factory", "test"), project.tags)
        assertNotNull(project.id)
        assertNotNull(project.createdAt)
        assertNotNull(project.modifiedAt)
    }
}
