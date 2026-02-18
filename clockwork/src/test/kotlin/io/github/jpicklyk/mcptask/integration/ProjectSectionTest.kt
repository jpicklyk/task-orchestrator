package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteSectionRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests focusing on project section associations
 */
class ProjectSectionTest {
    private lateinit var database: Database
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var sectionRepository: SQLiteSectionRepository

    @BeforeEach
    fun setUp() {
        // Create an in-memory database for testing
        database = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        val dbManager = DatabaseManager(database)
        dbManager.updateSchema()
        // Initialize repositories
        projectRepository = SQLiteProjectRepository(dbManager)
        sectionRepository = SQLiteSectionRepository(dbManager)
    }

    /**
     * Test associating sections with projects
     */
    @Test
    fun `should associate sections with projects`() = runBlocking {
        // Create a project
        val project = Project(
            name = "Project with Sections",
            summary = "Project for testing section associations",
            status = ProjectStatus.PLANNING
        )
        val projectResult = projectRepository.create(project)
        assertTrue(projectResult is Result.Success)
        val createdProject = (projectResult as Result.Success<Project>).data

        // Create sections associated with the project
        val section1 = Section(
            entityType = EntityType.PROJECT,
            entityId = createdProject.id,
            title = "Project Overview",
            usageDescription = "Overview of the project goals and scope",
            content = "This project aims to test section associations with projects",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("overview")
        )
        val section2 = Section(
            entityType = EntityType.PROJECT,
            entityId = createdProject.id,
            title = "Project Details",
            usageDescription = "Detailed information about the project",
            content = "This section contains detailed specifications for the project",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1,
            tags = listOf("details", "specifications")
        )

        val section1Result = sectionRepository.addSection(EntityType.PROJECT, createdProject.id, section1)
        val section2Result = sectionRepository.addSection(EntityType.PROJECT, createdProject.id, section2)
        assertTrue(section1Result is Result.Success)
        assertTrue(section2Result is Result.Success)

        // Retrieve sections for the project
        val projectSectionsResult = sectionRepository.getSectionsForEntity(EntityType.PROJECT, createdProject.id)
        assertTrue(projectSectionsResult is Result.Success)
        val projectSections = (projectSectionsResult as Result.Success<List<Section>>).data
        assertEquals(2, projectSections.size)

        // Verify section details
        val sortedSections = projectSections.sortedBy { it.ordinal }
        assertEquals("Project Overview", sortedSections[0].title)
        assertEquals("Project Details", sortedSections[1].title)
        assertEquals(0, sortedSections[0].ordinal)
        assertEquals(1, sortedSections[1].ordinal)
    }

    /**
     * Test updating section content
     */
    @Test
    fun `should update project section content`() = runBlocking {
        // Create a project
        val project = Project(
            name = "Project with Section to Update",
            summary = "Project for testing section updates",
            status = ProjectStatus.PLANNING
        )
        val projectResult = projectRepository.create(project)
        assertTrue(projectResult is Result.Success)
        val createdProject = (projectResult as Result.Success<Project>).data

        // Create a section for the project
        val section = Section(
            entityType = EntityType.PROJECT,
            entityId = createdProject.id,
            title = "Initial Section",
            usageDescription = "Section for testing updates",
            content = "Original content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )
        val sectionResult = sectionRepository.addSection(EntityType.PROJECT, createdProject.id, section)
        assertTrue(sectionResult is Result.Success)
        val createdSection = (sectionResult as Result.Success<Section>).data

        // Verify the initial section
        val initialSectionsResult = sectionRepository.getSectionsForEntity(EntityType.PROJECT, createdProject.id)
        assertTrue(initialSectionsResult is Result.Success)
        val initialSections = (initialSectionsResult as Result.Success<List<Section>>).data
        assertEquals(1, initialSections.size)
        assertEquals("Original content", initialSections[0].content)

        // Update the section
        val updatedSection = createdSection.copy(
            title = "Updated Section Title",
            content = "Updated content",
            tags = listOf("updated", "modified")
        )
        val updateResult = sectionRepository.updateSection(updatedSection)
        assertTrue(updateResult is Result.Success)

        // Verify section was updated
        val updatedSectionsResult = sectionRepository.getSectionsForEntity(EntityType.PROJECT, createdProject.id)
        assertTrue(updatedSectionsResult is Result.Success)
        val updatedSections = (updatedSectionsResult as Result.Success<List<Section>>).data
        assertEquals(1, updatedSections.size)

        val retrievedSection = updatedSections[0]
        assertEquals("Updated Section Title", retrievedSection.title)
        assertEquals("Updated content", retrievedSection.content)
        assertEquals(listOf("updated", "modified"), retrievedSection.tags)
        assertEquals(createdSection.entityType, retrievedSection.entityType)
        assertEquals(createdSection.entityId, retrievedSection.entityId)
    }

    /**
     * Test deleting sections
     */
    @Test
    fun `should delete project sections`() = runBlocking {
        // Create a project
        val project = Project(
            name = "Project with Sections to Delete",
            summary = "Project for testing section deletion",
            status = ProjectStatus.PLANNING
        )
        val projectResult = projectRepository.create(project)
        assertTrue(projectResult is Result.Success)
        val createdProject = (projectResult as Result.Success<Project>).data

        // Create sections for the project
        val section1 = Section(
            entityType = EntityType.PROJECT,
            entityId = createdProject.id,
            title = "Section to Keep",
            usageDescription = "This section will not be deleted",
            content = "Content to keep",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )
        val section2 = Section(
            entityType = EntityType.PROJECT,
            entityId = createdProject.id,
            title = "Section to Delete",
            usageDescription = "This section will be deleted",
            content = "Content to delete",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )

        val section1Result = sectionRepository.addSection(EntityType.PROJECT, createdProject.id, section1)
        val section2Result = sectionRepository.addSection(EntityType.PROJECT, createdProject.id, section2)
        assertTrue(section1Result is Result.Success)
        assertTrue(section2Result is Result.Success)
        val createdSection1 = (section1Result as Result.Success<Section>).data
        val createdSection2 = (section2Result as Result.Success<Section>).data

        // Verify initial sections
        val initialSectionsResult = sectionRepository.getSectionsForEntity(EntityType.PROJECT, createdProject.id)
        assertTrue(initialSectionsResult is Result.Success)
        val initialSections = (initialSectionsResult as Result.Success<List<Section>>).data
        assertEquals(2, initialSections.size)

        // Delete one section
        val deleteResult = sectionRepository.deleteSection(createdSection2.id)
        assertTrue(deleteResult is Result.Success)

        // Verify only one section remains
        val remainingSectionsResult = sectionRepository.getSectionsForEntity(EntityType.PROJECT, createdProject.id)
        assertTrue(remainingSectionsResult is Result.Success)
        val remainingSections = (remainingSectionsResult as Result.Success<List<Section>>).data
        assertEquals(1, remainingSections.size)
        assertEquals(createdSection1.id, remainingSections[0].id)
        assertEquals("Section to Keep", remainingSections[0].title)

        // Verify the deleted section no longer exists
        val deletedSectionResult = sectionRepository.getSection(createdSection2.id)
        assertTrue(deletedSectionResult is Result.Error)
    }
}
