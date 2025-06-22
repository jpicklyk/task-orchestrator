package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteFeatureRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for project isolation functionality.
 * Verifies that entities from different projects are properly isolated in queries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectIsolationTest {
    private lateinit var databaseManager: DatabaseManager
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var featureRepository: SQLiteFeatureRepository
    private lateinit var taskRepository: SQLiteTaskRepository

    @BeforeAll
    fun setup() {
        databaseManager = DatabaseManager(":memory:")
        databaseManager.initialize()
        projectRepository = SQLiteProjectRepository(databaseManager)
        featureRepository = SQLiteFeatureRepository(databaseManager)
        taskRepository = SQLiteTaskRepository(databaseManager)
    }

    @BeforeEach
    fun setupTestData() = runBlocking {
        // Create two projects
        val project1 = Project(
            id = UUID.randomUUID(),
            name = "Project Alpha",
            description = "First test project",
            status = ProjectStatus.ACTIVE,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("project-alpha")
        )

        val project2 = Project(
            id = UUID.randomUUID(),
            name = "Project Beta",
            description = "Second test project",
            status = ProjectStatus.ACTIVE,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("project-beta")
        )

        projectRepository.create(project1)
        projectRepository.create(project2)

        // Create features for each project
        val feature1Project1 = Feature(
            id = UUID.randomUUID(),
            projectId = project1.id,
            name = "Feature 1 - Project Alpha",
            summary = "Feature belonging to project 1",
            status = FeatureStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("feature-alpha")
        )

        val feature2Project1 = Feature(
            id = UUID.randomUUID(),
            projectId = project1.id,
            name = "Feature 2 - Project Alpha",
            summary = "Another feature belonging to project 1",
            status = FeatureStatus.PENDING,
            priority = Priority.MEDIUM,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("feature-alpha")
        )

        val feature1Project2 = Feature(
            id = UUID.randomUUID(),
            projectId = project2.id,
            name = "Feature 1 - Project Beta",
            summary = "Feature belonging to project 2",
            status = FeatureStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("feature-beta")
        )

        featureRepository.create(feature1Project1)
        featureRepository.create(feature2Project1)
        featureRepository.create(feature1Project2)

        // Create tasks for each project and feature
        val task1Project1 = Task(
            id = UUID.randomUUID(),
            projectId = project1.id,
            featureId = feature1Project1.id,
            title = "Task 1 - Project Alpha",
            summary = "Task belonging to project 1",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 5,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("task-alpha")
        )

        val task2Project1 = Task(
            id = UUID.randomUUID(),
            projectId = project1.id,
            featureId = feature1Project1.id,
            title = "Task 2 - Project Alpha",
            summary = "Another task belonging to project 1",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("task-alpha")
        )

        val task1Project2 = Task(
            id = UUID.randomUUID(),
            projectId = project2.id,
            featureId = feature1Project2.id,
            title = "Task 1 - Project Beta",
            summary = "Task belonging to project 2",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 8,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("task-beta")
        )

        val task2Project2 = Task(
            id = UUID.randomUUID(),
            projectId = project2.id,
            featureId = null, // Task without feature
            title = "Task 2 - Project Beta",
            summary = "Another task belonging to project 2",
            status = TaskStatus.PENDING,
            priority = Priority.LOW,
            complexity = 2,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("task-beta")
        )

        taskRepository.create(task1Project1)
        taskRepository.create(task2Project1)
        taskRepository.create(task1Project2)
        taskRepository.create(task2Project2)
    }

    @AfterEach
    fun cleanupDatabase() = runBlocking {
        databaseManager.clearAllData()
    }

    @AfterAll
    fun teardown() {
        databaseManager.close()
    }

    @Test
    fun `findByFilters should only return tasks from specified project`() = runBlocking {
        // Get all projects to get their IDs
        val projectsResult = projectRepository.findAll()
        assertTrue(projectsResult is Result.Success)
        val projects = projectsResult.data
        assertEquals(2, projects.size)

        val project1 = projects.find { it.name == "Project Alpha" }!!
        val project2 = projects.find { it.name == "Project Beta" }!!

        // Query tasks for project 1
        val project1TasksResult = taskRepository.findByFilters(
            projectId = project1.id,
            limit = 10
        )
        assertTrue(project1TasksResult is Result.Success)
        val project1Tasks = project1TasksResult.data
        assertEquals(2, project1Tasks.size)
        assertTrue(project1Tasks.all { it.projectId == project1.id })
        assertTrue(project1Tasks.all { it.title.contains("Alpha") })

        // Query tasks for project 2
        val project2TasksResult = taskRepository.findByFilters(
            projectId = project2.id,
            limit = 10
        )
        assertTrue(project2TasksResult is Result.Success)
        val project2Tasks = project2TasksResult.data
        assertEquals(2, project2Tasks.size)
        assertTrue(project2Tasks.all { it.projectId == project2.id })
        assertTrue(project2Tasks.all { it.title.contains("Beta") })
    }

    @Test
    fun `findByFilters should only return features from specified project`() = runBlocking {
        // Get all projects to get their IDs
        val projectsResult = projectRepository.findAll()
        assertTrue(projectsResult is Result.Success)
        val projects = projectsResult.data

        val project1 = projects.find { it.name == "Project Alpha" }!!
        val project2 = projects.find { it.name == "Project Beta" }!!

        // Query features for project 1
        val project1FeaturesResult = featureRepository.findByFilters(
            projectId = project1.id,
            limit = 10
        )
        assertTrue(project1FeaturesResult is Result.Success)
        val project1Features = project1FeaturesResult.data
        assertEquals(2, project1Features.size)
        assertTrue(project1Features.all { it.projectId == project1.id })
        assertTrue(project1Features.all { it.name.contains("Alpha") })

        // Query features for project 2
        val project2FeaturesResult = featureRepository.findByFilters(
            projectId = project2.id,
            limit = 10
        )
        assertTrue(project2FeaturesResult is Result.Success)
        val project2Features = project2FeaturesResult.data
        assertEquals(1, project2Features.size)
        assertTrue(project2Features.all { it.projectId == project2.id })
        assertTrue(project2Features.all { it.name.contains("Beta") })
    }

    @Test
    fun `findByFilters with status filter should respect project isolation`() = runBlocking {
        // Get all projects to get their IDs
        val projectsResult = projectRepository.findAll()
        assertTrue(projectsResult is Result.Success)
        val projects = projectsResult.data

        val project1 = projects.find { it.name == "Project Alpha" }!!
        val project2 = projects.find { it.name == "Project Beta" }!!

        // Query IN_PROGRESS tasks for project 1
        val project1InProgressResult = taskRepository.findByFilters(
            projectId = project1.id,
            status = TaskStatus.IN_PROGRESS,
            limit = 10
        )
        assertTrue(project1InProgressResult is Result.Success)
        val project1InProgress = project1InProgressResult.data
        assertEquals(1, project1InProgress.size)
        assertEquals("Task 1 - Project Alpha", project1InProgress[0].title)

        // Query IN_PROGRESS tasks for project 2
        val project2InProgressResult = taskRepository.findByFilters(
            projectId = project2.id,
            status = TaskStatus.IN_PROGRESS,
            limit = 10
        )
        assertTrue(project2InProgressResult is Result.Success)
        val project2InProgress = project2InProgressResult.data
        assertEquals(1, project2InProgress.size)
        assertEquals("Task 1 - Project Beta", project2InProgress[0].title)
    }

    @Test
    fun `findByFilters with priority filter should respect project isolation`() = runBlocking {
        // Get all projects to get their IDs
        val projectsResult = projectRepository.findAll()
        assertTrue(projectsResult is Result.Success)
        val projects = projectsResult.data

        val project1 = projects.find { it.name == "Project Alpha" }!!
        val project2 = projects.find { it.name == "Project Beta" }!!

        // Query HIGH priority tasks for project 1
        val project1HighPriorityResult = taskRepository.findByFilters(
            projectId = project1.id,
            priority = Priority.HIGH,
            limit = 10
        )
        assertTrue(project1HighPriorityResult is Result.Success)
        val project1HighPriority = project1HighPriorityResult.data
        assertEquals(1, project1HighPriority.size)
        assertTrue(project1HighPriority.all { it.projectId == project1.id })

        // Query HIGH priority tasks for project 2
        val project2HighPriorityResult = taskRepository.findByFilters(
            projectId = project2.id,
            priority = Priority.HIGH,
            limit = 10
        )
        assertTrue(project2HighPriorityResult is Result.Success)
        val project2HighPriority = project2HighPriorityResult.data
        assertEquals(1, project2HighPriority.size)
        assertTrue(project2HighPriority.all { it.projectId == project2.id })
    }

    @Test
    fun `findByFilters with tag filter should respect project isolation`() = runBlocking {
        // Get all projects to get their IDs
        val projectsResult = projectRepository.findAll()
        assertTrue(projectsResult is Result.Success)
        val projects = projectsResult.data

        val project1 = projects.find { it.name == "Project Alpha" }!!
        val project2 = projects.find { it.name == "Project Beta" }!!

        // Query tasks with "task-alpha" tag - should only return project 1 tasks
        val alphaTagResult = taskRepository.findByFilters(
            projectId = project1.id,
            tags = listOf("task-alpha"),
            limit = 10
        )
        assertTrue(alphaTagResult is Result.Success)
        val alphaTagTasks = alphaTagResult.data
        assertEquals(2, alphaTagTasks.size)
        assertTrue(alphaTagTasks.all { it.projectId == project1.id })

        // Query tasks with "task-beta" tag - should only return project 2 tasks
        val betaTagResult = taskRepository.findByFilters(
            projectId = project2.id,
            tags = listOf("task-beta"),
            limit = 10
        )
        assertTrue(betaTagResult is Result.Success)
        val betaTagTasks = betaTagResult.data
        assertEquals(2, betaTagTasks.size)
        assertTrue(betaTagTasks.all { it.projectId == project2.id })
    }

    @Test
    fun `findByFilters with null projectId should return all entities`() = runBlocking {
        // Query all tasks without project filter
        val allTasksResult = taskRepository.findByFilters(
            projectId = null,
            limit = 10
        )
        assertTrue(allTasksResult is Result.Success)
        val allTasks = allTasksResult.data
        assertEquals(4, allTasks.size) // Should return all 4 tasks

        // Query all features without project filter
        val allFeaturesResult = featureRepository.findByFilters(
            projectId = null,
            limit = 10
        )
        assertTrue(allFeaturesResult is Result.Success)
        val allFeatures = allFeaturesResult.data
        assertEquals(3, allFeatures.size) // Should return all 3 features
    }

    @Test
    fun `findByFilters with non-existent projectId should return empty list`() = runBlocking {
        val nonExistentProjectId = UUID.randomUUID()

        // Query tasks for non-existent project
        val tasksResult = taskRepository.findByFilters(
            projectId = nonExistentProjectId,
            limit = 10
        )
        assertTrue(tasksResult is Result.Success)
        assertEquals(0, tasksResult.data.size)

        // Query features for non-existent project
        val featuresResult = featureRepository.findByFilters(
            projectId = nonExistentProjectId,
            limit = 10
        )
        assertTrue(featuresResult is Result.Success)
        assertEquals(0, featuresResult.data.size)
    }

    @Test
    fun `countByFilters should respect project isolation`() = runBlocking {
        // Get all projects to get their IDs
        val projectsResult = projectRepository.findAll()
        assertTrue(projectsResult is Result.Success)
        val projects = projectsResult.data

        val project1 = projects.find { it.name == "Project Alpha" }!!
        val project2 = projects.find { it.name == "Project Beta" }!!

        // Count tasks for project 1
        val project1CountResult = taskRepository.countByFilters(
            projectId = project1.id
        )
        assertTrue(project1CountResult is Result.Success)
        assertEquals(2L, project1CountResult.data)

        // Count tasks for project 2
        val project2CountResult = taskRepository.countByFilters(
            projectId = project2.id
        )
        assertTrue(project2CountResult is Result.Success)
        assertEquals(2L, project2CountResult.data)

        // Count all tasks (no project filter)
        val allCountResult = taskRepository.countByFilters(
            projectId = null
        )
        assertTrue(allCountResult is Result.Success)
        assertEquals(4L, allCountResult.data)
    }
}