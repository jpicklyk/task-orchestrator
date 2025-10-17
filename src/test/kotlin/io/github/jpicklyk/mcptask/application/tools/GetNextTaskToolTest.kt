package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.tools.task.GetNextTaskTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class GetNextTaskToolTest {
    private lateinit var tool: GetNextTaskTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    @BeforeEach
    fun setup() {
        tool = GetNextTaskTool()

        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockDependencyRepository = mockk<DependencyRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)
    }

    // ========== Parameter Validation Tests ==========

    @Test
    fun `test valid empty parameters`() {
        val params = JsonObject(emptyMap())
        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test valid projectId parameter`() {
        val params = JsonObject(
            mapOf(
                "projectId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440000")
            )
        )
        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test valid featureId parameter`() {
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440000")
            )
        )
        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test valid limit parameter`() {
        val params = JsonObject(
            mapOf(
                "limit" to JsonPrimitive(5)
            )
        )
        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test invalid projectId format`() {
        val params = JsonObject(
            mapOf(
                "projectId" to JsonPrimitive("not-a-uuid")
            )
        )

        // Should throw an exception for invalid UUID format
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid projectId format"))
    }

    @Test
    fun `test invalid featureId format`() {
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive("not-a-uuid")
            )
        )

        // Should throw an exception for invalid UUID format
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid featureId format"))
    }

    @Test
    fun `test invalid limit too small`() {
        val params = JsonObject(
            mapOf(
                "limit" to JsonPrimitive(0)
            )
        )

        // Should throw an exception for limit < 1
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid limit"))
    }

    @Test
    fun `test invalid limit too large`() {
        val params = JsonObject(
            mapOf(
                "limit" to JsonPrimitive(21)
            )
        )

        // Should throw an exception for limit > 20
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid limit"))
    }

    // ========== Execution Tests ==========

    @Test
    fun `test get next task with single unblocked pending task`() = runBlocking {
        val taskId = UUID.randomUUID()

        val task = Task(
            id = taskId,
            title = "Implement feature",
            summary = "Pending task",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(listOf(task))
        coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        assertTrue(result is JsonObject, "Response should be a JsonObject")
        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val recommendations = data!!["recommendations"]?.jsonArray
        assertNotNull(recommendations, "Recommendations array should not be null")
        assertEquals(1, recommendations!!.size, "Should have exactly 1 recommendation")

        val recommendation = recommendations[0].jsonObject
        assertEquals(taskId.toString(), recommendation["taskId"]?.jsonPrimitive?.content)
        assertEquals("Implement feature", recommendation["title"]?.jsonPrimitive?.content)
        assertEquals("high", recommendation["priority"]?.jsonPrimitive?.content)
        assertEquals(5, recommendation["complexity"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test priority sorting - high priority first`() = runBlocking {
        val highPriorityId = UUID.randomUUID()
        val mediumPriorityId = UUID.randomUUID()
        val lowPriorityId = UUID.randomUUID()

        val highPriority = Task(
            id = highPriorityId,
            title = "High Priority",
            summary = "Important task",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5
        )

        val mediumPriority = Task(
            id = mediumPriorityId,
            title = "Medium Priority",
            summary = "Normal task",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3
        )

        val lowPriority = Task(
            id = lowPriorityId,
            title = "Low Priority",
            summary = "Low importance",
            status = TaskStatus.PENDING,
            priority = Priority.LOW,
            complexity = 2
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(
            listOf(lowPriority, mediumPriority, highPriority) // Mixed order
        )
        coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

        val params = JsonObject(mapOf("limit" to JsonPrimitive(3)))
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!

        // Should be sorted by priority: HIGH, MEDIUM, LOW
        assertEquals(3, recommendations.size)
        assertEquals("High Priority", recommendations[0].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals("Medium Priority", recommendations[1].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals("Low Priority", recommendations[2].jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test complexity sorting within same priority`() = runBlocking {
        val easyTaskId = UUID.randomUUID()
        val hardTaskId = UUID.randomUUID()

        val easyTask = Task(
            id = easyTaskId,
            title = "Easy Task",
            summary = "Low complexity",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 2
        )

        val hardTask = Task(
            id = hardTaskId,
            title = "Hard Task",
            summary = "High complexity",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 8
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(
            listOf(hardTask, easyTask) // Hard first
        )
        coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

        val params = JsonObject(mapOf("limit" to JsonPrimitive(2)))
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!

        // Should be sorted by complexity: easy (2) before hard (8)
        assertEquals(2, recommendations.size)
        assertEquals("Easy Task", recommendations[0].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals(2, recommendations[0].jsonObject["complexity"]?.jsonPrimitive?.int)
        assertEquals("Hard Task", recommendations[1].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals(8, recommendations[1].jsonObject["complexity"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test filters out blocked tasks`() = runBlocking {
        val blockerId = UUID.randomUUID()
        val blockedTaskId = UUID.randomUUID()
        val unblockedTaskId = UUID.randomUUID()

        val blocker = Task(
            id = blockerId,
            title = "Blocker",
            summary = "Not done",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 5
        )

        val blockedTask = Task(
            id = blockedTaskId,
            title = "Blocked Task",
            summary = "Cannot start yet",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 3
        )

        val unblockedTask = Task(
            id = unblockedTaskId,
            title = "Unblocked Task",
            summary = "Can start",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 4
        )

        val dependency = Dependency(
            fromTaskId = blockerId,
            toTaskId = blockedTaskId,
            type = DependencyType.BLOCKS
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(
            listOf(blocker, blockedTask, unblockedTask)
        )
        coEvery { mockDependencyRepository.findByToTaskId(blockedTaskId) } returns listOf(dependency)
        coEvery { mockDependencyRepository.findByToTaskId(unblockedTaskId) } returns emptyList()
        coEvery { mockDependencyRepository.findByToTaskId(blockerId) } returns emptyList()
        coEvery { mockTaskRepository.getById(blockerId) } returns Result.Success(blocker)

        val params = JsonObject(mapOf("limit" to JsonPrimitive(5)))
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!

        // Should only recommend unblocked tasks (blocker and unblocked)
        // Blocked task should be filtered out
        assertEquals(2, recommendations.size)
        val titles = recommendations.map { it.jsonObject["title"]?.jsonPrimitive?.content }
        assertTrue(titles.contains("Blocker"))
        assertTrue(titles.contains("Unblocked Task"))
        assertFalse(titles.contains("Blocked Task"))
    }

    @Test
    fun `test respects limit parameter`() = runBlocking {
        val tasks = (1..10).map { i ->
            Task(
                id = UUID.randomUUID(),
                title = "Task $i",
                summary = "Task number $i",
                status = TaskStatus.PENDING,
                priority = Priority.MEDIUM,
                complexity = i
            )
        }

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(tasks)
        coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

        val params = JsonObject(mapOf("limit" to JsonPrimitive(3)))
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!

        assertEquals(3, recommendations.size, "Should respect limit of 3")
        assertEquals(10, data["totalCandidates"]?.jsonPrimitive?.int, "Should report all 10 unblocked tasks")
    }

    @Test
    fun `test filter by project`() = runBlocking {
        val projectId = UUID.randomUUID()
        val taskId = UUID.randomUUID()

        val task = Task(
            id = taskId,
            title = "Project Task",
            summary = "In project",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            projectId = projectId
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findByProject(projectId, limit = 1000) } returns Result.Success(listOf(task))
        coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

        val params = JsonObject(
            mapOf(
                "projectId" to JsonPrimitive(projectId.toString())
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)

        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!
        assertEquals(1, recommendations.size)
    }

    @Test
    fun `test filter by feature`() = runBlocking {
        val featureId = UUID.randomUUID()
        val taskId = UUID.randomUUID()

        val task = Task(
            id = taskId,
            title = "Feature Task",
            summary = "In feature",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            featureId = featureId
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns Result.Success(listOf(task))
        coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(featureId.toString())
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)

        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!
        assertEquals(1, recommendations.size)
    }

    @Test
    fun `test include details`() = runBlocking {
        val taskId = UUID.randomUUID()

        val task = Task(
            id = taskId,
            title = "Task",
            summary = "Task summary",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf("tag1", "tag2")
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(listOf(task))
        coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

        val params = JsonObject(
            mapOf(
                "includeDetails" to JsonPrimitive(true)
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!
        val recommendation = recommendations[0].jsonObject

        // Should include details
        assertTrue(recommendation.containsKey("summary"))
        assertEquals("Task summary", recommendation["summary"]?.jsonPrimitive?.content)
        assertTrue(recommendation.containsKey("tags"))
        val tags = recommendation["tags"]?.jsonArray
        assertEquals(2, tags?.size)
    }

    @Test
    fun `test no unblocked tasks available`() = runBlocking {
        // All tasks are completed
        val completedTask = Task(
            id = UUID.randomUUID(),
            title = "Done",
            summary = "Already done",
            status = TaskStatus.COMPLETED,
            priority = Priority.HIGH,
            complexity = 5
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(listOf(completedTask))

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)

        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!
        assertEquals(0, recommendations.size)

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertTrue(message!!.contains("No unblocked tasks available"))
    }

    @Test
    fun `test repository error handling`() = runBlocking {
        // Mock task repository with error
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Error(
            RepositoryError.DatabaseError("Database error", null)
        )

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        // Should return empty recommendations when repository fails
        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)

        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!
        assertEquals(0, recommendations.size)
    }

    @Test
    fun `test default limit is 1`() = runBlocking {
        val tasks = (1..5).map { i ->
            Task(
                id = UUID.randomUUID(),
                title = "Task $i",
                summary = "Task $i",
                status = TaskStatus.PENDING,
                priority = Priority.MEDIUM,
                complexity = i
            )
        }

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(tasks)
        coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

        val params = JsonObject(emptyMap()) // No limit specified
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!

        assertEquals(1, recommendations.size, "Should default to 1 recommendation")
        assertEquals(5, data["totalCandidates"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test complex sorting scenario`() = runBlocking {
        // Create tasks with various priority and complexity combinations
        val tasks = listOf(
            Task(UUID.randomUUID(), title = "High-Easy", summary = "HE", status = TaskStatus.PENDING, priority = Priority.HIGH, complexity = 2),
            Task(UUID.randomUUID(), title = "High-Hard", summary = "HH", status = TaskStatus.PENDING, priority = Priority.HIGH, complexity = 8),
            Task(UUID.randomUUID(), title = "Medium-Easy", summary = "ME", status = TaskStatus.PENDING, priority = Priority.MEDIUM, complexity = 3),
            Task(UUID.randomUUID(), title = "Medium-Hard", summary = "MH", status = TaskStatus.PENDING, priority = Priority.MEDIUM, complexity = 7),
            Task(UUID.randomUUID(), title = "Low-Easy", summary = "LE", status = TaskStatus.PENDING, priority = Priority.LOW, complexity = 1),
            Task(UUID.randomUUID(), title = "Low-Hard", summary = "LH", status = TaskStatus.PENDING, priority = Priority.LOW, complexity = 9)
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(tasks.shuffled())
        coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

        val params = JsonObject(mapOf("limit" to JsonPrimitive(10)))
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val recommendations = data!!["recommendations"]?.jsonArray!!
        val titles = recommendations.map { it.jsonObject["title"]?.jsonPrimitive?.content }

        // Expected order: HIGH (easy first), MEDIUM (easy first), LOW (easy first)
        assertEquals(listOf("High-Easy", "High-Hard", "Medium-Easy", "Medium-Hard", "Low-Easy", "Low-Hard"), titles)
    }
}
