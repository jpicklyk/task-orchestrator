package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.ProjectRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteFeatureRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTaskRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*

/**
 * Test specifically for the multiple filter search bug fix.
 *
 * This test ensures that the SQLiteBusinessEntityRepository properly handles
 * multiple filters (status, priority, tags, text query) without causing
 * "WHERE clause is specified twice" database errors.
 *
 * Bug Details:
 * - Original issue: Using .where() multiple times instead of .andWhere()
 * - Error: "WHERE clause is specified twice" when combining status + priority filters
 * - Fix: Changed second and subsequent .where() calls to .andWhere()
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultipleFilterSearchBugTest {
    private lateinit var database: Database
    private lateinit var taskRepository: TaskRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var featureRepository: FeatureRepository
    private lateinit var databaseManager: DatabaseManager

    // Test data
    private val testProjectId = UUID.randomUUID()
    private val testFeatureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create a unique in-memory database for each test to ensure isolation
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repositories
        taskRepository = SQLiteTaskRepository(databaseManager)
        projectRepository = SQLiteProjectRepository(databaseManager)
        featureRepository = SQLiteFeatureRepository(databaseManager)

        // Create test project and feature first
        runBlocking {
            // Create test project
            val testProject = Project(
                id = testProjectId,
                name = "Test Project",
                summary = "A test project for filter testing",
                status = ProjectStatus.IN_DEVELOPMENT,
                tags = listOf("test")
            )
            val projectResult = projectRepository.create(testProject)
            assertTrue(projectResult.isSuccess(), "Failed to create test project")

            // Create test feature
            val testFeature = Feature(
                id = testFeatureId,
                projectId = testProjectId,
                name = "Test Feature",
                summary = "A test feature for filter testing",
                status = FeatureStatus.IN_DEVELOPMENT,
                priority = Priority.HIGH,
                tags = listOf("test")
            )
            val featureResult = featureRepository.create(testFeature)
            assertTrue(featureResult.isSuccess(), "Failed to create test feature")
        }

        // Create test tasks with various attributes to test filtering
        runBlocking {
            val tasks = listOf(
                Task(
                    id = UUID.randomUUID(),
                    title = "High Priority Pending Task",
                    summary = "This is a high priority task in pending status for authentication",
                    status = TaskStatus.PENDING,
                    priority = Priority.HIGH,
                    complexity = 7,
                    tags = listOf("oauth", "security", "api"),
                    projectId = testProjectId,
                    featureId = testFeatureId,
                    createdAt = Instant.parse("2025-01-01T10:00:00Z"),
                    modifiedAt = Instant.parse("2025-01-01T10:00:00Z")
                ),
                Task(
                    id = UUID.randomUUID(),
                    title = "Medium Priority In Progress Task",
                    summary = "This is a medium priority task in progress for user interface",
                    status = TaskStatus.IN_PROGRESS,
                    priority = Priority.MEDIUM,
                    complexity = 5,
                    tags = listOf("ui", "frontend", "react"),
                    projectId = testProjectId,
                    createdAt = Instant.parse("2025-01-02T10:00:00Z"),
                    modifiedAt = Instant.parse("2025-01-02T10:00:00Z")
                ),
                Task(
                    id = UUID.randomUUID(),
                    title = "Low Priority Completed Task",
                    summary = "This is a low priority completed task for database optimization",
                    status = TaskStatus.COMPLETED,
                    priority = Priority.LOW,
                    complexity = 3,
                    tags = listOf("database", "performance", "sql"),
                    featureId = testFeatureId,
                    createdAt = Instant.parse("2025-01-03T10:00:00Z"),
                    modifiedAt = Instant.parse("2025-01-03T10:00:00Z")
                ),
                Task(
                    id = UUID.randomUUID(),
                    title = "High Priority Pending API Task",
                    summary = "Another high priority pending task for API development",
                    status = TaskStatus.PENDING,
                    priority = Priority.HIGH,
                    complexity = 8,
                    tags = listOf("api", "backend", "kotlin"),
                    projectId = testProjectId,
                    featureId = testFeatureId,
                    createdAt = Instant.parse("2025-01-04T10:00:00Z"),
                    modifiedAt = Instant.parse("2025-01-04T10:00:00Z")
                ),
                Task(
                    id = UUID.randomUUID(),
                    title = "Medium Priority In Progress UI Task",
                    summary = "A medium priority in progress task for user interface improvements",
                    status = TaskStatus.IN_PROGRESS,
                    priority = Priority.MEDIUM,
                    complexity = 6,
                    tags = listOf("ui", "css", "design"),
                    createdAt = Instant.parse("2025-01-05T10:00:00Z"),
                    modifiedAt = Instant.parse("2025-01-05T10:00:00Z")
                )
            )

            // Insert all test tasks
            tasks.forEach { task ->
                val result = taskRepository.create(task)
                assertTrue(result.isSuccess(), "Failed to create test task: ${task.title}")
            }
        }
    }

    @Nested
    inner class SingleFilterTests {
        @Test
        fun `findByFilters with status only should work correctly`() = runBlocking {
            val result = taskRepository.findByFilters(
                projectId = null,
                statusFilter = StatusFilter(include = listOf(TaskStatus.PENDING)),
                priorityFilter = null,
                tags = null,
                textQuery = null,
                limit = 100
            )

            assertTrue(result.isSuccess(), "Single status filter should work")
            val tasks = (result as Result.Success).data
            assertEquals(2, tasks.size, "Should find 2 pending tasks")
            tasks.forEach { task: Task ->
                assertEquals(TaskStatus.PENDING, task.status)
            }
        }

        @Test
        fun `findByFilters with priority only should work correctly`() = runBlocking {
            val result = taskRepository.findByFilters(
                projectId = null,
                statusFilter = null,
                priorityFilter = StatusFilter(include = listOf(Priority.HIGH)),
                tags = null,
                textQuery = null,
                limit = 100
            )

            assertTrue(result.isSuccess(), "Single priority filter should work")
            val tasks = (result as Result.Success).data
            assertEquals(2, tasks.size, "Should find 2 high priority tasks")
            tasks.forEach { task: Task ->
                assertEquals(Priority.HIGH, task.priority)
            }
        }
    }

    @Nested
    inner class MultipleFilterTests {
        @Test
        fun `findByFilters with status AND priority should work without database error`() = runBlocking {
            // This is the specific test case that was failing before the fix
            val result = taskRepository.findByFilters(
                projectId = null,
                statusFilter = StatusFilter(include = listOf(TaskStatus.PENDING)),
                priorityFilter = StatusFilter(include = listOf(Priority.HIGH)),
                tags = null,
                textQuery = null,
                limit = 100
            )

            assertTrue(
                result.isSuccess(),
                "Status + Priority filter should work without 'WHERE clause specified twice' error"
            )
            val tasks = (result as Result.Success).data
            assertEquals(2, tasks.size, "Should find 2 high priority pending tasks")

            tasks.forEach { task: Task ->
                assertEquals(TaskStatus.PENDING, task.status)
                assertEquals(Priority.HIGH, task.priority)
            }
        }

        @Test
        fun `findByFilters with status AND priority AND tags should work correctly`() = runBlocking {
            val result = taskRepository.findByFilters(
                projectId = null,
                statusFilter = StatusFilter(include = listOf(TaskStatus.PENDING)),
                priorityFilter = StatusFilter(include = listOf(Priority.HIGH)),
                tags = listOf("api"),
                textQuery = null,
                limit = 100
            )

            assertTrue(result.isSuccess(), "Status + Priority + Tags filter should work")
            val tasks = (result as Result.Success).data
            assertEquals(2, tasks.size, "Should find 2 high priority pending API tasks")

            tasks.forEach { task: Task ->
                assertEquals(TaskStatus.PENDING, task.status)
                assertEquals(Priority.HIGH, task.priority)
                assertTrue(task.tags.contains("api"), "Task should have 'api' tag")
            }
        }

        @Test
        fun `findByFilters with status AND priority AND textQuery should work correctly`() = runBlocking {
            val result = taskRepository.findByFilters(
                projectId = null,
                statusFilter = StatusFilter(include = listOf(TaskStatus.PENDING)),
                priorityFilter = StatusFilter(include = listOf(Priority.HIGH)),
                tags = null,
                textQuery = "authentication",
                limit = 100
            )

            assertTrue(result.isSuccess(), "Status + Priority + TextQuery filter should work")
            val tasks = (result as Result.Success).data
            assertEquals(1, tasks.size, "Should find 1 high priority pending task with 'authentication' in text")

            val task = tasks.first()
            assertEquals(TaskStatus.PENDING, task.status)
            assertEquals(Priority.HIGH, task.priority)
            assertTrue(
                task.title.contains("authentication", ignoreCase = true) ||
                        task.summary.contains("authentication", ignoreCase = true),
                "Task should contain 'authentication' in title or summary"
            )
        }

        @Test
        fun `findByFilters with ALL filters (status, priority, tags, textQuery) should work correctly`() = runBlocking {
            val result = taskRepository.findByFilters(
                projectId = null,
                statusFilter = StatusFilter(include = listOf(TaskStatus.IN_PROGRESS)),
                priorityFilter = StatusFilter(include = listOf(Priority.MEDIUM)),
                tags = listOf("ui"),
                textQuery = "interface",
                limit = 100
            )

            assertTrue(result.isSuccess(), "All filters combined should work")
            val tasks = (result as Result.Success).data
            assertEquals(2, tasks.size, "Should find 2 tasks matching all criteria")

            tasks.forEach { task: Task ->
                assertEquals(TaskStatus.IN_PROGRESS, task.status)
                assertEquals(Priority.MEDIUM, task.priority)
                assertTrue(task.tags.contains("ui"), "Task should have 'ui' tag")
                assertTrue(
                    task.title.contains("interface", ignoreCase = true) ||
                            task.summary.contains("interface", ignoreCase = true),
                    "Task should contain 'interface' in title or summary"
                )
            }
        }
    }

    @Nested
    inner class CountByFiltersTests {
        @Test
        fun `countByFilters with status AND priority should work without database error`() = runBlocking {
            // Test the countByFilters method which had the same bug
            val result = taskRepository.countByFilters(
                projectId = null,
                statusFilter = StatusFilter(include = listOf(TaskStatus.PENDING)),
                priorityFilter = StatusFilter(include = listOf(Priority.HIGH)),
                tags = null,
                textQuery = null
            )

            assertTrue(result.isSuccess(), "Count with Status + Priority filter should work")
            val count = (result as Result.Success).data
            assertEquals(2L, count, "Should count 2 high priority pending tasks")
        }

        @Test
        fun `countByFilters with all filters should work correctly`() = runBlocking {
            val result = taskRepository.countByFilters(
                projectId = null,
                statusFilter = StatusFilter(include = listOf(TaskStatus.IN_PROGRESS)),
                priorityFilter = StatusFilter(include = listOf(Priority.MEDIUM)),
                tags = listOf("ui"),
                textQuery = "interface"
            )

            assertTrue(result.isSuccess(), "Count with all filters should work")
            val count = (result as Result.Success).data
            assertEquals(2L, count, "Should count 2 tasks matching all criteria")
        }
    }

    @Nested
    inner class RegressionPreventionTests {
        @Test
        fun `multiple different status and priority combinations should work`() = runBlocking {
            val testCases = listOf(
                Pair(TaskStatus.PENDING, Priority.HIGH),
                Pair(TaskStatus.IN_PROGRESS, Priority.MEDIUM),
                Pair(TaskStatus.COMPLETED, Priority.LOW),
                Pair(TaskStatus.PENDING, Priority.MEDIUM),
                Pair(TaskStatus.IN_PROGRESS, Priority.HIGH)
            )

            testCases.forEach { (status, priority) ->
                val result = taskRepository.findByFilters(
                    projectId = null,
                    statusFilter = StatusFilter(include = listOf(status)),
                    priorityFilter = StatusFilter(include = listOf(priority)),
                    tags = null,
                    textQuery = null,
                    limit = 100
                )

                assertTrue(
                    result.isSuccess(),
                    "Filter combination (status=$status, priority=$priority) should work without database error"
                )
            }
        }

        @Test
        fun `rapid sequential multiple filter calls should not cause issues`() = runBlocking {
            // Test rapid sequential calls to ensure no database connection issues
            repeat(10) { iteration ->
                val result = taskRepository.findByFilters(
                    projectId = null,
                    statusFilter = StatusFilter(include = listOf(TaskStatus.PENDING)),
                    priorityFilter = StatusFilter(include = listOf(Priority.HIGH)),
                    tags = null,
                    textQuery = null,
                    limit = 100
                )

                assertTrue(
                    result.isSuccess(),
                    "Iteration $iteration: Rapid sequential calls should work"
                )
            }
        }
    }
}
