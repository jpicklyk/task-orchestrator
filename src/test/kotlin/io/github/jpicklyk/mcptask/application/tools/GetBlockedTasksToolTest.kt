package io.github.jpicklyk.mcptask.application.tools

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

class GetBlockedTasksToolTest {
    private lateinit var tool: GetBlockedTasksTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    @BeforeEach
    fun setup() {
        tool = GetBlockedTasksTool()

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

    // ========== Execution Tests ==========

    @Test
    fun `test find blocked tasks`() = runBlocking {
        val blockerId = UUID.randomUUID()
        val blockedTaskId = UUID.randomUUID()

        // Create a blocking task (in progress)
        val blockerTask = Task(
            id = blockerId,
            title = "Blocker Task",
            summary = "This blocks other tasks",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 5
        )

        // Create a blocked task (pending, waiting for blocker)
        val blockedTask = Task(
            id = blockedTaskId,
            title = "Blocked Task",
            summary = "This is blocked",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3
        )

        // Create dependency (blocker blocks blocked task)
        val dependency = Dependency(
            fromTaskId = blockerId,
            toTaskId = blockedTaskId,
            type = DependencyType.BLOCKS
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(listOf(blockerTask, blockedTask))
        coEvery { mockDependencyRepository.findByToTaskId(blockedTaskId) } returns listOf(dependency)
        coEvery { mockDependencyRepository.findByToTaskId(blockerId) } returns emptyList()
        coEvery { mockTaskRepository.getById(blockerId) } returns Result.Success(blockerTask)

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        assertTrue(result is JsonObject, "Response should be a JsonObject")
        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val blockedTasks = data!!["blockedTasks"]?.jsonArray
        assertNotNull(blockedTasks, "Blocked tasks array should not be null")
        assertEquals(1, blockedTasks!!.size, "Should have exactly 1 blocked task")

        val blockedTaskObj = blockedTasks[0].jsonObject
        assertEquals(blockedTaskId.toString(), blockedTaskObj["taskId"]?.jsonPrimitive?.content)
        assertEquals("Blocked Task", blockedTaskObj["title"]?.jsonPrimitive?.content)
        assertEquals(1, blockedTaskObj["blockerCount"]?.jsonPrimitive?.int)

        val blockers = blockedTaskObj["blockedBy"]?.jsonArray
        assertNotNull(blockers)
        assertEquals(1, blockers!!.size)
        assertEquals(blockerId.toString(), blockers[0].jsonObject["taskId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test no blocked tasks when blocker is completed`() = runBlocking {
        val blockerId = UUID.randomUUID()
        val blockedTaskId = UUID.randomUUID()

        // Create a completed blocker task
        val blockerTask = Task(
            id = blockerId,
            title = "Blocker Task",
            summary = "This is complete",
            status = TaskStatus.COMPLETED,
            priority = Priority.HIGH,
            complexity = 5
        )

        // Create a pending task
        val blockedTask = Task(
            id = blockedTaskId,
            title = "Task",
            summary = "This should not be blocked",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3
        )

        // Create dependency
        val dependency = Dependency(
            fromTaskId = blockerId,
            toTaskId = blockedTaskId,
            type = DependencyType.BLOCKS
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(listOf(blockerTask, blockedTask))
        coEvery { mockDependencyRepository.findByToTaskId(blockedTaskId) } returns listOf(dependency)
        coEvery { mockDependencyRepository.findByToTaskId(blockerId) } returns emptyList()
        coEvery { mockTaskRepository.getById(blockerId) } returns Result.Success(blockerTask)

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)

        val data = responseObj["data"]?.jsonObject
        val blockedTasks = data!!["blockedTasks"]?.jsonArray!!
        assertEquals(0, blockedTasks.size, "Should have no blocked tasks when blocker is completed")
    }

    @Test
    fun `test filter by project`() = runBlocking {
        val projectId = UUID.randomUUID()
        val blockedTaskId = UUID.randomUUID()

        val blockedTask = Task(
            id = blockedTaskId,
            title = "Blocked Task",
            summary = "In project",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3,
            projectId = projectId
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findByProject(projectId, limit = 1000) } returns Result.Success(listOf(blockedTask))
        coEvery { mockDependencyRepository.findByToTaskId(blockedTaskId) } returns emptyList()

        val params = JsonObject(
            mapOf(
                "projectId" to JsonPrimitive(projectId.toString())
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)
    }

    @Test
    fun `test filter by feature`() = runBlocking {
        val featureId = UUID.randomUUID()
        val blockedTaskId = UUID.randomUUID()

        val blockedTask = Task(
            id = blockedTaskId,
            title = "Blocked Task",
            summary = "In feature",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3,
            featureId = featureId
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns Result.Success(listOf(blockedTask))
        coEvery { mockDependencyRepository.findByToTaskId(blockedTaskId) } returns emptyList()

        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(featureId.toString())
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)
    }

    @Test
    fun `test include task details`() = runBlocking {
        val blockerId = UUID.randomUUID()
        val blockedTaskId = UUID.randomUUID()
        val featureId = UUID.randomUUID()

        // Create blocker task
        val blockerTask = Task(
            id = blockerId,
            title = "Blocker",
            summary = "Blocking",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 7,
            featureId = featureId
        )

        // Create blocked task with tags
        val blockedTask = Task(
            id = blockedTaskId,
            title = "Blocked",
            summary = "Blocked task summary",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3,
            tags = listOf("tag1", "tag2")
        )

        val dependency = Dependency(
            fromTaskId = blockerId,
            toTaskId = blockedTaskId,
            type = DependencyType.BLOCKS
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(listOf(blockerTask, blockedTask))
        coEvery { mockDependencyRepository.findByToTaskId(blockedTaskId) } returns listOf(dependency)
        coEvery { mockDependencyRepository.findByToTaskId(blockerId) } returns emptyList()
        coEvery { mockTaskRepository.getById(blockerId) } returns Result.Success(blockerTask)

        val params = JsonObject(
            mapOf(
                "includeTaskDetails" to JsonPrimitive(true)
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val blockedTasks = data!!["blockedTasks"]?.jsonArray!!

        val blockedTaskObj = blockedTasks[0].jsonObject

        // Should include summary and tags when includeTaskDetails is true
        assertTrue(blockedTaskObj.containsKey("summary"))
        assertEquals("Blocked task summary", blockedTaskObj["summary"]?.jsonPrimitive?.content)
        assertTrue(blockedTaskObj.containsKey("tags"))
        val tags = blockedTaskObj["tags"]?.jsonArray
        assertEquals(2, tags?.size)

        // Blocker should also include complexity and featureId
        val blockers = blockedTaskObj["blockedBy"]?.jsonArray!!
        val blockerObj = blockers[0].jsonObject
        assertTrue(blockerObj.containsKey("complexity"))
        assertEquals(7, blockerObj["complexity"]?.jsonPrimitive?.int)
        assertTrue(blockerObj.containsKey("featureId"))
    }

    @Test
    fun `test multiple blockers`() = runBlocking {
        val blocker1Id = UUID.randomUUID()
        val blocker2Id = UUID.randomUUID()
        val blockedTaskId = UUID.randomUUID()

        val blocker1 = Task(
            id = blocker1Id,
            title = "Blocker 1",
            summary = "First blocker",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 5
        )

        val blocker2 = Task(
            id = blocker2Id,
            title = "Blocker 2",
            summary = "Second blocker",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 4
        )

        val blockedTask = Task(
            id = blockedTaskId,
            title = "Blocked Task",
            summary = "Blocked by two tasks",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3
        )

        val dependency1 = Dependency(
            fromTaskId = blocker1Id,
            toTaskId = blockedTaskId,
            type = DependencyType.BLOCKS
        )

        val dependency2 = Dependency(
            fromTaskId = blocker2Id,
            toTaskId = blockedTaskId,
            type = DependencyType.BLOCKS
        )

        // Mock repository calls
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(listOf(blocker1, blocker2, blockedTask))
        coEvery { mockDependencyRepository.findByToTaskId(blockedTaskId) } returns listOf(dependency1, dependency2)
        coEvery { mockDependencyRepository.findByToTaskId(blocker1Id) } returns emptyList()
        coEvery { mockDependencyRepository.findByToTaskId(blocker2Id) } returns emptyList()
        coEvery { mockTaskRepository.getById(blocker1Id) } returns Result.Success(blocker1)
        coEvery { mockTaskRepository.getById(blocker2Id) } returns Result.Success(blocker2)

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val blockedTasks = data!!["blockedTasks"]?.jsonArray!!

        assertEquals(1, blockedTasks.size)
        val blockedTaskObj = blockedTasks[0].jsonObject
        assertEquals(2, blockedTaskObj["blockerCount"]?.jsonPrimitive?.int)

        val blockers = blockedTaskObj["blockedBy"]?.jsonArray!!
        assertEquals(2, blockers.size)
    }

    @Test
    fun `test no blocked tasks found`() = runBlocking {
        // Mock repository with no dependencies
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Success(emptyList())

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)

        val data = responseObj["data"]?.jsonObject
        val blockedTasks = data!!["blockedTasks"]?.jsonArray!!
        assertEquals(0, blockedTasks.size)
        assertEquals(0, data["totalBlocked"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test repository error handling`() = runBlocking {
        // Mock task repository with error
        coEvery { mockTaskRepository.findAll(limit = 1000) } returns Result.Error(
            RepositoryError.DatabaseError("Database error", null)
        )

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        // Should return empty list when repository fails
        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success)

        val data = responseObj["data"]?.jsonObject
        val blockedTasks = data!!["blockedTasks"]?.jsonArray!!
        assertEquals(0, blockedTasks.size)
    }

    @Test
    fun `test completed tasks are not checked for blocking`() = runBlocking {
        val completedTaskId = UUID.randomUUID()

        val completedTask = Task(
            id = completedTaskId,
            title = "Completed Task",
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
        val data = responseObj["data"]?.jsonObject
        val blockedTasks = data!!["blockedTasks"]?.jsonArray!!

        // Completed tasks should not be included in blocked tasks check
        assertEquals(0, blockedTasks.size)
    }
}
