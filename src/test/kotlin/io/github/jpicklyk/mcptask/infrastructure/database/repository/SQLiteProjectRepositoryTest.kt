package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class SQLiteProjectRepositoryTest {
    private lateinit var database: Database
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var databaseManager: DatabaseManager

    @BeforeEach
    fun setUp() {
        // Create a unique in-memory database for each test to ensure isolation
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repository
        projectRepository = SQLiteProjectRepository(databaseManager)
    }

    @Test
    fun `should create and retrieve a project`() = runBlocking {
        // Arrange
        val project = Project(
            name = "Test Project",
            summary = "Project for testing",
            status = ProjectStatus.PLANNING,
            tags = listOf("test", "sqlite")
        )

        // Act
        val createResult = projectRepository.create(project)
        val getResult = projectRepository.getById(project.id)

        // Assert
        assertTrue(createResult is Result.Success)
        assertTrue(getResult is Result.Success)

        val retrievedProject = (getResult as Result.Success).data
        assertEquals(project.id, retrievedProject.id)
        assertEquals(project.name, retrievedProject.name)
        assertEquals(project.summary, retrievedProject.summary)
        assertEquals(project.status, retrievedProject.status)
        assertEquals(project.tags, retrievedProject.tags)
    }

    @Test
    fun `should return error when retrieving non-existent project`() = runBlocking {
        // Arrange
        val nonExistentId = UUID.randomUUID()

        // Act
        val result = projectRepository.getById(nonExistentId)

        // Assert
        assertTrue(result is Result.Error)
    }

    @Test
    fun `should update a project`() = runBlocking {
        // Arrange
        val project = Project(
            name = "Original Project",
            summary = "Original summary",
            status = ProjectStatus.PLANNING
        )
        val createResult = projectRepository.create(project)
        val createdProject = (createResult as Result.Success).data

        // Act
        val updatedProject = createdProject.update(
            name = "Updated Project",
            status = ProjectStatus.IN_DEVELOPMENT,
            tags = listOf("updated")
        )
        val updateResult = projectRepository.update(updatedProject)
        val getResult = projectRepository.getById(createdProject.id)

        // Assert
        assertTrue(updateResult is Result.Success)
        assertTrue(getResult is Result.Success)

        val retrievedProject = (getResult as Result.Success).data
        assertEquals(updatedProject.id, retrievedProject.id)
        assertEquals("Updated Project", retrievedProject.name)
        assertEquals(project.summary, retrievedProject.summary)
        assertEquals(ProjectStatus.IN_DEVELOPMENT, retrievedProject.status)
        assertEquals(listOf("updated"), retrievedProject.tags)
    }

    @Test
    fun `should delete a project`() = runBlocking {
        // Arrange
        val project = Project(
            name = "Project to Delete",
            summary = "Will be deleted",
            status = ProjectStatus.PLANNING
        )
        projectRepository.create(project)

        // Act
        val deleteResult = projectRepository.delete(project.id)
        val getResult = projectRepository.getById(project.id)

        // Assert
        assertTrue(deleteResult is Result.Success)
        assertTrue(getResult is Result.Error)
    }

    @Test
    fun `should find projects by name`() = runBlocking {
        // Arrange
        val project1 = Project(name = "Alpha Project", summary = "First test project")
        val project2 = Project(name = "Beta Project", summary = "Second test project")
        val project3 = Project(name = "Gamma Project", summary = "Third test project")

        projectRepository.create(project1)
        projectRepository.create(project2)
        projectRepository.create(project3)

        // Act
        val result = projectRepository.findByName("Beta")

        // Assert
        assertTrue(result is Result.Success)
        val projects = (result as Result.Success).data
        assertEquals(1, projects.size)
        assertEquals("Beta Project", projects[0].name)
    }

    @Test
    fun `should search projects by query`() = runBlocking {
        // Arrange
        val project1 = Project(name = "Alpha Project", summary = "Contains search term")
        val project2 = Project(name = "Beta Project", summary = "Does not match")
        val project3 = Project(name = "Search Term Project", summary = "Some description")

        projectRepository.create(project1)
        projectRepository.create(project2)
        projectRepository.create(project3)

        // Act
        val result = projectRepository.search("search term")

        // Assert
        assertTrue(result is Result.Success)
        val projects = (result as Result.Success).data
        assertEquals(2, projects.size)
        assertTrue(projects.any { it.id == project1.id })
        assertTrue(projects.any { it.id == project3.id })
    }

    @Test
    fun `should find projects by status`() = runBlocking {
        // Arrange
        val project1 = Project(name = "Project 1", summary = "Summary 1", status = ProjectStatus.PLANNING)
        val project2 = Project(name = "Project 2", summary = "Summary 2", status = ProjectStatus.IN_DEVELOPMENT)
        val project3 = Project(name = "Project 3", summary = "Summary 3", status = ProjectStatus.IN_DEVELOPMENT)

        projectRepository.create(project1)
        projectRepository.create(project2)
        projectRepository.create(project3)

        // Act
        val result = projectRepository.findByStatus(ProjectStatus.IN_DEVELOPMENT)

        // Assert
        assertTrue(result is Result.Success)
        val projects = (result as Result.Success).data
        assertEquals(2, projects.size)
        assertTrue(projects.all { it.status == ProjectStatus.IN_DEVELOPMENT })
    }

    @Test
    fun `should find projects by tag`() = runBlocking {
        // Arrange
        val project1 = Project(name = "Project 1", summary = "Summary 1", tags = listOf("important", "frontend"))
        val project2 = Project(name = "Project 2", summary = "Summary 2", tags = listOf("backend"))
        val project3 = Project(name = "Project 3", summary = "Summary 3", tags = listOf("important", "backend"))

        projectRepository.create(project1)
        projectRepository.create(project2)
        projectRepository.create(project3)

        // Act
        val result = projectRepository.findByTag("important")

        // Assert
        assertTrue(result is Result.Success)
        val projects = (result as Result.Success).data
        assertEquals(2, projects.size)
        assertTrue(projects.all { it.tags.contains("important") })
    }


}