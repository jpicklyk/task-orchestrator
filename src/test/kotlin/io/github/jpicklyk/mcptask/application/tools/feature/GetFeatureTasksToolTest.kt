package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class GetFeatureTasksToolTest {

    private lateinit var tool: BaseToolDefinition
    private lateinit var mockContext: ToolExecutionContext
    private val testFeatureId = UUID.randomUUID()
    private val emptyFeatureId = UUID.randomUUID()
    private val testTaskId1 = UUID.randomUUID()
    private val testTaskId2 = UUID.randomUUID()
    private val testTaskId3 = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mock repositories
        val mockFeatureRepository = mockk<FeatureRepository>()
        val mockTaskRepository = mockk<TaskRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository

        // Create feature test data
        val testFeature = Feature(
            id = testFeatureId,
            name = "Test Feature",
            summary = "Test Feature Summary", // Added summary parameter
            status = FeatureStatus.IN_DEVELOPMENT
        )

        val emptyFeature = Feature(
            id = emptyFeatureId,
            name = "Empty Feature",
            summary = "Empty Feature Summary", // Added summary parameter
            status = FeatureStatus.PLANNING
        )

        // Create task test data
        val task1 = Task(
            id = testTaskId1,
            title = "Task 1",
            summary = "Task 1 Summary", // Added summary parameter
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 7,
            createdAt = Instant.now().minusSeconds(86400 * 3), // 3 days ago
            modifiedAt = Instant.now().minusSeconds(86400) // 1 day ago
        )

        val task2 = Task(
            id = testTaskId2,
            title = "Task 2",
            summary = "Task 2 Summary", // Added summary parameter
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 4,
            createdAt = Instant.now().minusSeconds(86400 * 2), // 2 days ago
            modifiedAt = Instant.now().minusSeconds(86400) // 1 day ago
        )

        val task3 = Task(
            id = testTaskId3,
            title = "Task 3",
            summary = "Task 3 Summary", // Added summary parameter
            status = TaskStatus.COMPLETED,
            priority = Priority.LOW,
            complexity = 2,
            createdAt = Instant.now().minusSeconds(86400 * 5), // 5 days ago
            modifiedAt = Instant.now().minusSeconds(86400 * 4) // 4 days ago
        )

        val testTasks = listOf(task1, task2, task3)

        // Configure repository behavior
        coEvery { mockFeatureRepository.getById(testFeatureId) } returns Result.Success(testFeature)
        coEvery { mockFeatureRepository.getById(emptyFeatureId) } returns Result.Success(emptyFeature)
        coEvery { mockFeatureRepository.getById(not(or(testFeatureId, emptyFeatureId))) } returns Result.Error(
            RepositoryError.NotFound(UUID.randomUUID(), EntityType.FEATURE, "Feature not found")
        )

        coEvery { mockTaskRepository.findByFeature(testFeatureId, any(), any(), any()) } returns Result.Success(
            testTasks
        )
        coEvery { mockTaskRepository.findByFeature(emptyFeatureId, any(), any(), any()) } returns Result.Success(
            emptyList()
        )

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        // Initialize the tool
        tool = GetFeatureTasksTool()
    }

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "status" to JsonPrimitive("in-progress"),
                "priority" to JsonPrimitive("high"),
                "complexityMin" to JsonPrimitive(3),
                "complexityMax" to JsonPrimitive(8),
                "sortBy" to JsonPrimitive("complexity"),
                "sortDirection" to JsonPrimitive("desc"),
                "limit" to JsonPrimitive(10),
                "offset" to JsonPrimitive(0)
            )
        )

        // Should not throw any exceptions
        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate without required parameter should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "status" to JsonPrimitive("in-progress")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Missing required parameter: featureId"))
    }

    @Test
    fun `validate with invalid UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive("not-a-uuid")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid featureId format"))
    }

    @Test
    fun `validate with invalid status should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "status" to JsonPrimitive("invalid-status")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid status"))
    }

    @Test
    fun `validate with invalid priority should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "priority" to JsonPrimitive("invalid-priority")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid priority"))
    }

    @Test
    fun `validate with invalid complexity range should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "complexityMin" to JsonPrimitive(8),
                "complexityMax" to JsonPrimitive(5)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("complexityMin cannot be greater than complexityMax"))
    }

    @Test
    fun `validate with invalid sortBy should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "sortBy" to JsonPrimitive("invalid-field")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid sortBy value"))
    }

    @Test
    fun `validate with invalid sortDirection should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "sortDirection" to JsonPrimitive("invalid-direction")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid sortDirection value"))
    }

    @Test
    fun `validate with invalid limit should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "limit" to JsonPrimitive(0)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("limit must be at least 1"))
    }

    @Test
    fun `validate with invalid offset should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "offset" to JsonPrimitive(-1)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("offset must be at least 0"))
    }

    @Test
    fun `execute should return all tasks for feature`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString())
            )
        )

        val result = tool.execute(params, mockContext) as JsonObject

        // Verify success response
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((result["message"] as JsonPrimitive).content.contains("Found 3 tasks for feature"))

        // Verify feature information
        val data = result["data"] as JsonObject
        assertEquals(testFeatureId.toString(), (data["featureId"] as JsonPrimitive).content)
        assertEquals("Test Feature", (data["featureName"] as JsonPrimitive).content)

        // Verify the task count
        assertEquals(3, (data["total"] as JsonPrimitive).content.toInt())
        assertTrue(data.containsKey("items"))
    }

    @Test
    fun `execute with status filter should return filtered tasks`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "status" to JsonPrimitive("in-progress")
            )
        )

        val result = tool.execute(params, mockContext) as JsonObject

        // Verify success response
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())

        // Verify the filtered count (only 1 task is in progress)
        val data = result["data"] as JsonObject
        assertEquals(1, (data["total"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `execute with priority filter should return filtered tasks`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "priority" to JsonPrimitive("high")
            )
        )

        val result = tool.execute(params, mockContext) as JsonObject

        // Verify success response
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())

        // Verify filtered count (only 1 task has high priority)
        val data = result["data"] as JsonObject
        assertEquals(1, (data["total"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `execute with complexity range filter should return filtered tasks`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "complexityMin" to JsonPrimitive(3),
                "complexityMax" to JsonPrimitive(6)
            )
        )

        val result = tool.execute(params, mockContext) as JsonObject

        // Verify success response
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())

        // Verify filtered count (only a task with complexity 4 should be included)
        val data = result["data"] as JsonObject
        assertEquals(1, (data["total"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `execute with pagination should return paginated results`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(testFeatureId.toString()),
                "limit" to JsonPrimitive(1),
                "offset" to JsonPrimitive(1)
            )
        )

        val result = tool.execute(params, mockContext) as JsonObject

        // Verify success response
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())

        // Verify pagination info
        val data = result["data"] as JsonObject
        assertEquals(3, (data["total"] as JsonPrimitive).content.toInt()) // The total count remains 3
        assertEquals(1, (data["limit"] as JsonPrimitive).content.toInt())
        assertEquals(1, (data["offset"] as JsonPrimitive).content.toInt())
        assertEquals(true, (data["hasMore"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `execute with empty feature should return success with zero tasks`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(emptyFeatureId.toString())
            )
        )

        val result = tool.execute(params, mockContext) as JsonObject

        // Verify success response with zero tasks
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((result["message"] as JsonPrimitive).content.contains("No tasks found for feature"))

        // Verify empty data
        val data = result["data"] as JsonObject
        assertEquals(emptyFeatureId.toString(), (data["featureId"] as JsonPrimitive).content)
        assertEquals("Empty Feature", (data["featureName"] as JsonPrimitive).content)
        assertEquals(0, (data["total"] as JsonPrimitive).content.toInt())
        assertEquals(false, (data["hasMore"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `execute with non-existent feature should return error`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "featureId" to JsonPrimitive(nonExistentId.toString())
            )
        )

        val result = tool.execute(params, mockContext) as JsonObject

        // Verify error response
        assertEquals(false, (result["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((result["message"] as JsonPrimitive).content.contains("Feature not found"))
    }
}