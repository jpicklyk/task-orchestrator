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

/**
 * Tests for optimistic locking behavior under concurrent update scenarios.
 * These tests verify that version-based conflict detection works correctly
 * when multiple agents attempt to modify the same entity.
 */
class OptimisticLockingConcurrencyTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var taskRepository: SQLiteTaskRepository
    private lateinit var featureRepository: SQLiteFeatureRepository
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var sectionRepository: SQLiteSectionRepository

    @BeforeEach
    fun setUp() {
        // Create a unique in-memory database for each test
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repositories
        taskRepository = SQLiteTaskRepository(databaseManager)
        featureRepository = SQLiteFeatureRepository(databaseManager)
        projectRepository = SQLiteProjectRepository(databaseManager)
        sectionRepository = SQLiteSectionRepository(databaseManager)
    }

    @Test
    fun `should detect concurrent Task updates and return ConflictError`() = runBlocking {
        // Arrange: Create a task
        val task = Task(
            title = "Original Task",
            summary = "Will be updated concurrently",
            status = TaskStatus.PENDING
        )
        val createResult = taskRepository.create(task)
        val createdTask = (createResult as Result.Success).data

        // Simulate two agents reading the same task
        val agent1Task = createdTask.copy()
        val agent2Task = createdTask.copy()

        // Act: Agent 1 updates first (should succeed)
        val agent1Update = agent1Task.update { it.copy(title = "Agent 1 Update") }
        val agent1Result = taskRepository.update(agent1Update)

        // Act: Agent 2 tries to update with stale version (should fail)
        val agent2Update = agent2Task.update { it.copy(title = "Agent 2 Update") }
        val agent2Result = taskRepository.update(agent2Update)

        // Assert: Agent 1 succeeds
        assertTrue(agent1Result is Result.Success, "Agent 1 update should succeed")

        // Assert: Agent 2 fails with ConflictError
        assertTrue(agent2Result is Result.Error, "Agent 2 update should fail")
        val error = (agent2Result as Result.Error).error
        assertTrue(error is RepositoryError.ConflictError, "Error should be ConflictError, but was ${error::class.simpleName}")
        assertTrue(error.message.contains("Optimistic lock conflict"), "Error message should mention optimistic lock conflict")

        // Verify: Database contains Agent 1's update
        val finalResult = taskRepository.getById(createdTask.id)
        val finalTask = (finalResult as Result.Success).data
        assertEquals("Agent 1 Update", finalTask.title, "Database should contain Agent 1's update")
        assertEquals(2L, finalTask.version, "Version should be 2 after one successful update")
    }

    @Test
    fun `should detect concurrent Feature updates and return ConflictError`() = runBlocking {
        // Arrange: Create a feature
        val feature = Feature(
            name = "Original Feature",
            summary = "Will be updated concurrently",
            status = FeatureStatus.PLANNING
        )
        val createResult = featureRepository.create(feature)
        val createdFeature = (createResult as Result.Success).data

        // Simulate two agents reading the same feature
        val agent1Feature = createdFeature.copy()
        val agent2Feature = createdFeature.copy()

        // Act: Agent 1 updates first (should succeed)
        val agent1Update = agent1Feature.update(name = "Agent 1 Update")
        val agent1Result = featureRepository.update(agent1Update)

        // Act: Agent 2 tries to update with stale version (should fail)
        val agent2Update = agent2Feature.update(name = "Agent 2 Update")
        val agent2Result = featureRepository.update(agent2Update)

        // Assert: Agent 1 succeeds
        assertTrue(agent1Result is Result.Success, "Agent 1 update should succeed")

        // Assert: Agent 2 fails with ConflictError
        assertTrue(agent2Result is Result.Error, "Agent 2 update should fail")
        val error = (agent2Result as Result.Error).error
        assertTrue(error is RepositoryError.ConflictError, "Error should be ConflictError, but was ${error::class.simpleName}")
        assertTrue(error.message.contains("Optimistic lock conflict"), "Error message should mention optimistic lock conflict")

        // Verify: Database contains Agent 1's update
        val finalResult = featureRepository.getById(createdFeature.id)
        val finalFeature = (finalResult as Result.Success).data
        assertEquals("Agent 1 Update", finalFeature.name, "Database should contain Agent 1's update")
        assertEquals(2L, finalFeature.version, "Version should be 2 after one successful update")
    }

    @Test
    fun `should detect concurrent Project updates and return ConflictError`() = runBlocking {
        // Arrange: Create a project
        val project = Project(
            name = "Original Project",
            summary = "Will be updated concurrently",
            status = ProjectStatus.PLANNING
        )
        val createResult = projectRepository.create(project)
        val createdProject = (createResult as Result.Success).data

        // Simulate two agents reading the same project
        val agent1Project = createdProject.copy()
        val agent2Project = createdProject.copy()

        // Act: Agent 1 updates first (should succeed)
        val agent1Update = agent1Project.update(name = "Agent 1 Update")
        val agent1Result = projectRepository.update(agent1Update)

        // Act: Agent 2 tries to update with stale version (should fail)
        val agent2Update = agent2Project.update(name = "Agent 2 Update")
        val agent2Result = projectRepository.update(agent2Update)

        // Assert: Agent 1 succeeds
        assertTrue(agent1Result is Result.Success, "Agent 1 update should succeed")

        // Assert: Agent 2 fails with ConflictError
        assertTrue(agent2Result is Result.Error, "Agent 2 update should fail")
        val error = (agent2Result as Result.Error).error
        assertTrue(error is RepositoryError.ConflictError, "Error should be ConflictError, but was ${error::class.simpleName}")
        assertTrue(error.message.contains("Optimistic lock conflict"), "Error message should mention optimistic lock conflict")

        // Verify: Database contains Agent 1's update
        val finalResult = projectRepository.getById(createdProject.id)
        val finalProject = (finalResult as Result.Success).data
        assertEquals("Agent 1 Update", finalProject.name, "Database should contain Agent 1's update")
        assertEquals(2L, finalProject.version, "Version should be 2 after one successful update")
    }

    @Test
    fun `should detect concurrent Section updates and return ConflictError`() = runBlocking {
        // Arrange: Create a task first (sections need a parent entity)
        val task = Task(
            title = "Parent Task",
            summary = "Parent for section",
            status = TaskStatus.PENDING
        )
        val taskResult = taskRepository.create(task)
        val createdTask = (taskResult as Result.Success).data

        // Create a section
        val section = Section(
            entityType = EntityType.TASK,
            entityId = createdTask.id,
            title = "Original Section",
            usageDescription = "Will be updated concurrently",
            content = "Original content",
            ordinal = 0
        )
        val createResult = sectionRepository.addSection(EntityType.TASK, createdTask.id, section)
        val createdSection = (createResult as Result.Success).data

        // Simulate two agents reading the same section
        val agent1Section = createdSection.copy()
        val agent2Section = createdSection.copy()

        // Act: Agent 1 updates first (should succeed)
        val agent1Update = agent1Section.copy(title = "Agent 1 Update")
        val agent1Result = sectionRepository.updateSection(agent1Update)

        // Act: Agent 2 tries to update with stale version (should fail)
        val agent2Update = agent2Section.copy(title = "Agent 2 Update")
        val agent2Result = sectionRepository.updateSection(agent2Update)

        // Assert: Agent 1 succeeds
        assertTrue(agent1Result is Result.Success, "Agent 1 update should succeed")

        // Assert: Agent 2 fails with ConflictError
        assertTrue(agent2Result is Result.Error, "Agent 2 update should fail")
        val error = (agent2Result as Result.Error).error
        assertTrue(error is RepositoryError.ConflictError, "Error should be ConflictError, but was ${error::class.simpleName}")
        assertTrue(error.message.contains("Optimistic lock conflict"), "Error message should mention optimistic lock conflict")

        // Verify: Database contains Agent 1's update
        val finalResult = sectionRepository.getSection(createdSection.id)
        val finalSection = (finalResult as Result.Success).data
        assertEquals("Agent 1 Update", finalSection.title, "Database should contain Agent 1's update")
        assertEquals(2L, finalSection.version, "Version should be 2 after one successful update")
    }

    @Test
    fun `should allow sequential updates with correct version progression`() = runBlocking {
        // Arrange: Create a task
        val task = Task(
            title = "Original Task",
            summary = "Will be updated sequentially",
            status = TaskStatus.PENDING
        )
        val createResult = taskRepository.create(task)
        val createdTask = (createResult as Result.Success).data
        assertEquals(1L, createdTask.version, "Initial version should be 1")

        // Act: Update 1
        val update1 = createdTask.update { it.copy(title = "Update 1") }
        val result1 = taskRepository.update(update1)
        val task1 = (result1 as Result.Success).data
        assertEquals(2L, task1.version, "Version should be 2 after first update")

        // Act: Update 2 (using task from previous update)
        val update2 = task1.update { it.copy(title = "Update 2") }
        val result2 = taskRepository.update(update2)
        val task2 = (result2 as Result.Success).data
        assertEquals(3L, task2.version, "Version should be 3 after second update")

        // Act: Update 3
        val update3 = task2.update { it.copy(title = "Update 3") }
        val result3 = taskRepository.update(update3)
        val task3 = (result3 as Result.Success).data
        assertEquals(4L, task3.version, "Version should be 4 after third update")

        // Verify: Final state
        val finalResult = taskRepository.getById(createdTask.id)
        val finalTask = (finalResult as Result.Success).data
        assertEquals("Update 3", finalTask.title, "Database should contain final update")
        assertEquals(4L, finalTask.version, "Final version should be 4")
    }

    @Test
    fun `should reject update with old version even after multiple successful updates`() = runBlocking {
        // Arrange: Create a task
        val task = Task(
            title = "Original Task",
            summary = "Version test",
            status = TaskStatus.PENDING
        )
        val createResult = taskRepository.create(task)
        val createdTask = (createResult as Result.Success).data

        // Save the original version for later
        val staleTask = createdTask.copy()

        // Act: Perform multiple successful updates
        var currentTask = createdTask
        for (i in 1..5) {
            val updated = currentTask.update { it.copy(title = "Update $i") }
            val result = taskRepository.update(updated)
            currentTask = (result as Result.Success).data
        }

        // Verify: Current version is 6
        assertEquals(6L, currentTask.version, "Version should be 6 after 5 updates")

        // Act: Try to update with the original stale version (version 1)
        val staleUpdate = staleTask.update { it.copy(title = "Stale Update") }
        val staleResult = taskRepository.update(staleUpdate)

        // Assert: Stale update fails with ConflictError
        assertTrue(staleResult is Result.Error, "Stale update should fail")
        val error = (staleResult as Result.Error).error
        assertTrue(error is RepositoryError.ConflictError, "Error should be ConflictError")

        // Verify: Database still contains the latest update
        val finalResult = taskRepository.getById(createdTask.id)
        val finalTask = (finalResult as Result.Success).data
        assertEquals("Update 5", finalTask.title, "Database should contain Update 5")
        assertEquals(6L, finalTask.version, "Version should still be 6")
    }
}
