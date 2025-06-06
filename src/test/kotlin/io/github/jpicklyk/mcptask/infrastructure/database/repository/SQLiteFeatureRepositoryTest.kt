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

class SQLiteFeatureRepositoryTest {
    private lateinit var database: Database
    private lateinit var featureRepository: SQLiteFeatureRepository
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var databaseManager: DatabaseManager

    @BeforeEach
    fun setUp() {
        // Create a unique in-memory database for each test to ensure isolation
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repositories
        featureRepository = SQLiteFeatureRepository(databaseManager)
        projectRepository = SQLiteProjectRepository(databaseManager)
    }

    @Test
    fun `should create and retrieve a feature without project association`() = runBlocking {
        // Arrange
        val feature = Feature(
            name = "Test Feature",
            summary = "Feature for testing",
            status = FeatureStatus.PLANNING,
            priority = Priority.HIGH,
            tags = listOf("test", "sqlite")
        )

        // Act
        val createResult = featureRepository.create(feature)
        val getResult = featureRepository.getById(feature.id)

        // Assert
        assertTrue(createResult is Result.Success)
        assertTrue(getResult is Result.Success)

        val retrievedFeature = (getResult as Result.Success).data
        assertEquals(feature.id, retrievedFeature.id)
        assertEquals(feature.name, retrievedFeature.name)
        assertEquals(feature.summary, retrievedFeature.summary)
        assertEquals(feature.status, retrievedFeature.status)
        assertEquals(feature.priority, retrievedFeature.priority)
        assertEquals(feature.tags, retrievedFeature.tags)
        assertNull(retrievedFeature.projectId, "ProjectId should be null")
    }

    @Test
    fun `should create and retrieve a feature with project association`() = runBlocking {
        // Arrange - Create a project first
        val project = Project(
            name = "Test Project",
            summary = "Project for testing",
            status = ProjectStatus.PLANNING
        )
        val projectResult = projectRepository.create(project)
        assertTrue(projectResult is Result.Success)

        // Create a feature with project association
        val feature = Feature(
            name = "Test Feature",
            summary = "Feature for testing",
            projectId = project.id,
            status = FeatureStatus.PLANNING,
            priority = Priority.HIGH,
            tags = listOf("test", "project-association")
        )

        // Act
        val createResult = featureRepository.create(feature)
        val getResult = featureRepository.getById(feature.id)

        // Assert
        assertTrue(createResult is Result.Success)
        assertTrue(getResult is Result.Success)

        val retrievedFeature = (getResult as Result.Success).data
        assertEquals(feature.id, retrievedFeature.id)
        assertEquals(feature.name, retrievedFeature.name)
        assertEquals(feature.summary, retrievedFeature.summary)
        assertEquals(project.id, retrievedFeature.projectId)
        assertEquals(feature.status, retrievedFeature.status)
        assertEquals(feature.priority, retrievedFeature.priority)
        assertEquals(feature.tags, retrievedFeature.tags)
    }

    @Test
    fun `should return validation error when creating feature with non-existent project ID`() = runBlocking {
        // Arrange
        val nonExistentProjectId = UUID.randomUUID()
        val feature = Feature(
            name = "Invalid Feature",
            summary = "Feature with invalid project reference",
            projectId = nonExistentProjectId
        )

        // Act
        val result = featureRepository.create(feature)

        // Assert
        assertTrue(result is Result.Error)
        val error = result as Result.Error
        assertTrue(error.error is RepositoryError.ValidationError)
        assertTrue((error.error as RepositoryError.ValidationError).message.contains("does not exist"))
    }

    @Test
    fun `should update a feature's project association`() = runBlocking {
        // Arrange
        // Create an initial feature without a project
        val feature = Feature(
            name = "Original Feature",
            summary = "Original summary without project",
            status = FeatureStatus.PLANNING
        )
        val createResult = featureRepository.create(feature)
        assertTrue(createResult is Result.Success)

        // Create a project
        val project = Project(
            name = "Test Project",
            summary = "Project for association test",
            status = ProjectStatus.PLANNING
        )
        val projectResult = projectRepository.create(project)
        assertTrue(projectResult is Result.Success)

        // Act
        // Update feature to associate with a project
        val updatedFeature = feature.copy(projectId = project.id)
        val updateResult = featureRepository.update(updatedFeature)
        val getResult = featureRepository.getById(feature.id)

        // Assert
        assertTrue(updateResult is Result.Success)
        assertTrue(getResult is Result.Success)

        val retrievedFeature = (getResult as Result.Success).data
        assertEquals(feature.id, retrievedFeature.id)
        assertEquals(feature.name, retrievedFeature.name)
        assertEquals(feature.summary, retrievedFeature.summary)
        assertEquals(project.id, retrievedFeature.projectId)
    }

    @Test
    fun `should remove a feature's project association`() = runBlocking {
        // Arrange
        // Create a project
        val project = Project(
            name = "Test Project",
            summary = "Project for association test",
            status = ProjectStatus.PLANNING
        )
        val projectResult = projectRepository.create(project)
        assertTrue(projectResult is Result.Success)

        // Create a feature with project association
        val feature = Feature(
            name = "Project-Associated Feature",
            summary = "Feature initially with project",
            projectId = project.id
        )
        val createResult = featureRepository.create(feature)
        assertTrue(createResult is Result.Success)

        // Act
        // Update feature to remove the project association
        val updatedFeature = feature.copy(projectId = null)
        val updateResult = featureRepository.update(updatedFeature)
        val getResult = featureRepository.getById(feature.id)

        // Assert
        assertTrue(updateResult is Result.Success)
        assertTrue(getResult is Result.Success)

        val retrievedFeature = (getResult as Result.Success).data
        assertEquals(feature.id, retrievedFeature.id)
        assertEquals(feature.name, retrievedFeature.name)
        assertEquals(feature.summary, retrievedFeature.summary)
        assertNull(retrievedFeature.projectId, "ProjectId should be null after update")
    }

    @Test
    fun `should find features by project ID`() = runBlocking {
        // Arrange
        // Create two projects
        val project1 = Project(
            name = "Project 1",
            summary = "First test project",
            status = ProjectStatus.PLANNING
        )
        val project2 = Project(
            name = "Project 2",
            summary = "Second test project",
            status = ProjectStatus.IN_DEVELOPMENT
        )
        projectRepository.create(project1)
        projectRepository.create(project2)

        // Create features with different project associations
        val feature1 = Feature(
            name = "Feature 1",
            summary = "Feature for project 1",
            projectId = project1.id
        )
        val feature2 = Feature(
            name = "Feature 2",
            summary = "Another feature for project 1",
            projectId = project1.id
        )
        val feature3 = Feature(
            name = "Feature 3",
            summary = "Feature for project 2",
            projectId = project2.id
        )
        val feature4 = Feature(
            name = "Feature 4",
            summary = "Feature without project"
        )

        featureRepository.create(feature1)
        featureRepository.create(feature2)
        featureRepository.create(feature3)
        featureRepository.create(feature4)

        // Act
        val result = featureRepository.findByFilters(projectId = project1.id)

        // Assert
        assertTrue(result is Result.Success)
        val features = (result as Result.Success).data
        assertEquals(2, features.size)
        assertTrue(features.all { it.projectId == project1.id })
        assertTrue(features.any { it.name == "Feature 1" })
        assertTrue(features.any { it.name == "Feature 2" })
    }

    @Test
    fun `should return error when finding features for non-existent project ID`() = runBlocking {
        // Arrange
        val nonExistentProjectId = UUID.randomUUID()

        // Act
        val result = featureRepository.findByFilters(projectId = nonExistentProjectId)

        // Assert
        assertTrue(result is Result.Success)
        val features = (result as Result.Success).data
        assertEquals(0, features.size, "Should return empty list for non-existent project")
    }

    @Test
    fun `should filter features by project ID in findAll method`() = runBlocking {
        // Arrange
        // Create a project
        val project = Project(
            name = "Test Project",
            summary = "Project for filtering test",
            status = ProjectStatus.PLANNING
        )
        projectRepository.create(project)

        // Create features with different project associations
        val feature1 = Feature(
            name = "Feature 1",
            summary = "Feature for project",
            projectId = project.id
        )
        val feature2 = Feature(
            name = "Feature 2",
            summary = "Another feature for project",
            projectId = project.id
        )
        val feature3 = Feature(
            name = "Feature 3",
            summary = "Feature without project"
        )

        featureRepository.create(feature1)
        featureRepository.create(feature2)
        featureRepository.create(feature3)

        // Act
        val allResult = featureRepository.findAll()
        val filteredResult = featureRepository.findByProject(project.id)

        // Assert
        assertTrue(allResult is Result.Success<List<Feature>>)
        assertTrue(filteredResult is Result.Success<List<Feature>>)

        val allFeatures = (allResult as Result.Success<List<Feature>>).data
        val filteredFeatures = (filteredResult as Result.Success<List<Feature>>).data

        assertEquals(3, allFeatures.size, "Should find all features")
        assertEquals(2, filteredFeatures.size, "Should find only project-associated features")
        assertTrue(filteredFeatures.all { it.projectId == project.id })
    }

    @Test
    fun `should filter features by project ID in search method`() = runBlocking {
        // Arrange
        // Create a project
        val project = Project(
            name = "Test Project",
            summary = "Project for search test",
            status = ProjectStatus.PLANNING
        )
        projectRepository.create(project)

        // Create features with different project associations
        val feature1 = Feature(
            name = "Searchable Feature 1",
            summary = "Contains search term",
            projectId = project.id
        )
        val feature2 = Feature(
            name = "Searchable Feature 2",
            summary = "Contains search term",
            projectId = project.id
        )
        val feature3 = Feature(
            name = "Searchable Feature 3",
            summary = "Contains search term",
            projectId = null
        )

        featureRepository.create(feature1)
        featureRepository.create(feature2)
        featureRepository.create(feature3)

        // Act
        val allSearchResult = featureRepository.search("search term")
        val filteredSearchResult = featureRepository.findByFilters(
            projectId = project.id,
            textQuery = "search term"
        )

        // Assert
        assertTrue(allSearchResult is Result.Success<List<Feature>>)
        assertTrue(filteredSearchResult is Result.Success<List<Feature>>)

        val allSearchFeatures = (allSearchResult as Result.Success<List<Feature>>).data
        val filteredSearchFeatures = (filteredSearchResult as Result.Success<List<Feature>>).data

        assertEquals(3, allSearchFeatures.size, "Should find all matching features")
        assertEquals(2, filteredSearchFeatures.size, "Should find only matching project-associated features")
        assertTrue(filteredSearchFeatures.all { it.projectId == project.id })
    }
}