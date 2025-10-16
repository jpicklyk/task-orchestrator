package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.test.mock.MockRepositoryProvider
import io.github.jpicklyk.mcptask.test.mock.MockTaskRepository
import io.github.jpicklyk.mcptask.test.utils.ResponseTestUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class BulkUpdateTasksToolTest {

    private lateinit var tool: BulkUpdateTasksTool
    private lateinit var mockTaskRepository: MockTaskRepository
    private lateinit var mockContext: ToolExecutionContext
    private val testTask1Id = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val testTask2Id = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val testTask3Id = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val nonExistentTaskId = UUID.fromString("99999999-9999-9999-9999-999999999999")

    @BeforeEach
    fun setUp() {
        tool = BulkUpdateTasksTool()
        mockTaskRepository = MockTaskRepository()

        // Set up repository provider with our mocks
        val repositoryProvider = MockRepositoryProvider(
            taskRepository = mockTaskRepository
        )

        // Create mock context
        mockContext = ToolExecutionContext(repositoryProvider)

        // Set up test data
        val testTask1 = Task(
            id = testTask1Id,
            title = "Original Task 1",
            summary = "Original task 1 summary",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 5,
            featureId = null,
            projectId = null,
            tags = listOf("original", "tag1"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        val testTask2 = Task(
            id = testTask2Id,
            title = "Original Task 2",
            summary = "Original task 2 summary",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 8,
            featureId = null,
            projectId = null,
            tags = listOf("tag2"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        val testTask3 = Task(
            id = testTask3Id,
            title = "Original Task 3",
            summary = "Original task 3 summary",
            status = TaskStatus.PENDING,
            priority = Priority.LOW,
            complexity = 3,
            featureId = null,
            projectId = null,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockTaskRepository.addTask(testTask1)
        mockTaskRepository.addTask(testTask2)
        mockTaskRepository.addTask(testTask3)
    }

    // ========== VALIDATION TESTS ==========

    @Test
    fun `validate params - valid input with multiple tasks`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask2Id.toString()),
                                "priority" to JsonPrimitive("low")
                            )
                        )
                    )
                )
            )
        )

        // Should not throw an exception
        tool.validateParams(params)
    }

    @Test
    fun `validate params - missing tasks array`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Missing required parameter: tasks"))
    }

    @Test
    fun `validate params - empty tasks array`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(emptyList())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("At least one task must be provided"))
    }

    @Test
    fun `validate params - exceeds maximum tasks limit`() {
        val tasks = (1..101).map {
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(UUID.randomUUID().toString()),
                    "status" to JsonPrimitive("completed")
                )
            )
        }

        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(tasks)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Maximum 100 tasks allowed"))
    }

    @Test
    fun `validate params - missing id field`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "status" to JsonPrimitive("completed")
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("missing required field: id"))
    }

    @Test
    fun `validate params - invalid UUID format`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("not-a-uuid"),
                                "status" to JsonPrimitive("completed")
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid id"))
        assertTrue(exception.message!!.contains("valid UUID"))
    }

    @Test
    fun `validate params - no update fields provided`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString())
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("has no fields to update"))
    }

    @Test
    fun `validate params - invalid status value`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "status" to JsonPrimitive("invalid-status")
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid status"))
    }

    @Test
    fun `validate params - invalid priority value`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "priority" to JsonPrimitive("invalid-priority")
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid priority"))
    }

    @Test
    fun `validate params - complexity below minimum`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "complexity" to JsonPrimitive(0)
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid complexity"))
    }

    @Test
    fun `validate params - complexity above maximum`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "complexity" to JsonPrimitive(11)
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid complexity"))
    }

    // ========== HAPPY PATH TESTS ==========

    @Test
    fun `execute - update status on single task`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))
        assertEquals("1 tasks updated successfully", ResponseTestUtils.getResponseMessage(result))

        val data = ResponseTestUtils.getResponseData(result)!!
        assertEquals(1, data["updated"]?.jsonPrimitive?.int)
        assertEquals(0, data["failed"]?.jsonPrimitive?.int)

        // Verify task was actually updated
        val updatedTask = (mockTaskRepository.getById(testTask1Id) as Result.Success).data
        assertEquals(TaskStatus.COMPLETED, updatedTask.status)
    }

    @Test
    fun `execute - update status on multiple tasks`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask2Id.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask3Id.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))
        assertEquals("3 tasks updated successfully", ResponseTestUtils.getResponseMessage(result))

        val data = ResponseTestUtils.getResponseData(result)!!
        assertEquals(3, data["updated"]?.jsonPrimitive?.int)
        assertEquals(0, data["failed"]?.jsonPrimitive?.int)

        // Verify all tasks were updated
        assertEquals(TaskStatus.COMPLETED, (mockTaskRepository.getById(testTask1Id) as Result.Success).data.status)
        assertEquals(TaskStatus.COMPLETED, (mockTaskRepository.getById(testTask2Id) as Result.Success).data.status)
        assertEquals(TaskStatus.COMPLETED, (mockTaskRepository.getById(testTask3Id) as Result.Success).data.status)
    }

    @Test
    fun `execute - mixed field updates on different tasks`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "status" to JsonPrimitive("in-progress"),
                                "priority" to JsonPrimitive("high")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask2Id.toString()),
                                "complexity" to JsonPrimitive(10)
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask3Id.toString()),
                                "tags" to JsonPrimitive("urgent,backend")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))
        assertEquals("3 tasks updated successfully", ResponseTestUtils.getResponseMessage(result))

        // Verify different fields were updated on each task
        val task1 = (mockTaskRepository.getById(testTask1Id) as Result.Success).data
        assertEquals(TaskStatus.IN_PROGRESS, task1.status)
        assertEquals(Priority.HIGH, task1.priority)

        val task2 = (mockTaskRepository.getById(testTask2Id) as Result.Success).data
        assertEquals(10, task2.complexity)

        val task3 = (mockTaskRepository.getById(testTask3Id) as Result.Success).data
        assertEquals(listOf("urgent", "backend"), task3.tags)
    }

    @Test
    fun `execute - update all fields on a task`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "title" to JsonPrimitive("Updated Title"),
                                "description" to JsonPrimitive("Updated description"),
                                "status" to JsonPrimitive("completed"),
                                "priority" to JsonPrimitive("high"),
                                "complexity" to JsonPrimitive(9),
                                "tags" to JsonPrimitive("updated,complete")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        val updatedTask = (mockTaskRepository.getById(testTask1Id) as Result.Success).data
        assertEquals("Updated Title", updatedTask.title)
        assertEquals("Updated description", updatedTask.summary)
        assertEquals(TaskStatus.COMPLETED, updatedTask.status)
        assertEquals(Priority.HIGH, updatedTask.priority)
        assertEquals(9, updatedTask.complexity)
        assertEquals(listOf("updated", "complete"), updatedTask.tags)
    }

    @Test
    fun `execute - response contains minimal fields only`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val data = ResponseTestUtils.getResponseData(result)!!
        val items = data["items"]!!.jsonArray

        assertEquals(1, items.size)

        val item = items[0].jsonObject
        assertTrue(item.containsKey("id"))
        assertTrue(item.containsKey("status"))
        assertTrue(item.containsKey("modifiedAt"))

        // Verify it doesn't contain unnecessary fields
        assertFalse(item.containsKey("title"))
        assertFalse(item.containsKey("summary"))
        assertFalse(item.containsKey("priority"))
        assertFalse(item.containsKey("complexity"))
        assertFalse(item.containsKey("tags"))
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `execute - task not found`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(nonExistentTaskId.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertFalse(ResponseTestUtils.isSuccessResponse(result))
        val error = ResponseTestUtils.getResponseError(result)!!
        assertEquals(ErrorCodes.OPERATION_FAILED, error["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute - partial failure with some tasks not found`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(nonExistentTaskId.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask2Id.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))
        assertEquals("2 tasks updated successfully, 1 failed", ResponseTestUtils.getResponseMessage(result))

        val data = ResponseTestUtils.getResponseData(result)!!
        assertEquals(2, data["updated"]?.jsonPrimitive?.int)
        assertEquals(1, data["failed"]?.jsonPrimitive?.int)

        // Verify failures array contains the non-existent task
        val failures = data["failures"]!!.jsonArray
        assertEquals(1, failures.size)

        val failure = failures[0].jsonObject
        assertEquals(1, failure["index"]?.jsonPrimitive?.int)
        assertEquals(nonExistentTaskId.toString(), failure["id"]?.jsonPrimitive?.content)

        // Verify successful tasks were updated
        assertEquals(TaskStatus.COMPLETED, (mockTaskRepository.getById(testTask1Id) as Result.Success).data.status)
        assertEquals(TaskStatus.COMPLETED, (mockTaskRepository.getById(testTask2Id) as Result.Success).data.status)
    }

    @Test
    fun `execute - all tasks fail to update`() = runBlocking {
        val nonExistent1 = UUID.randomUUID()
        val nonExistent2 = UUID.randomUUID()

        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(nonExistent1.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(nonExistent2.toString()),
                                "status" to JsonPrimitive("completed")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertFalse(ResponseTestUtils.isSuccessResponse(result))
        assertEquals("Failed to update any tasks", ResponseTestUtils.getResponseMessage(result))

        val error = ResponseTestUtils.getResponseError(result)!!
        assertEquals(ErrorCodes.OPERATION_FAILED, error["code"]?.jsonPrimitive?.content)
    }

    // ========== PERFORMANCE TESTS ==========

    @Test
    fun `execute - handles 50 task updates efficiently`() = runBlocking {
        // Add 50 test tasks
        val taskIds = mutableListOf<UUID>()
        repeat(50) { i ->
            val taskId = UUID.randomUUID()
            taskIds.add(taskId)
            mockTaskRepository.addTask(
                Task(
                    id = taskId,
                    title = "Task $i",
                    summary = "Summary $i",
                    status = TaskStatus.PENDING,
                    priority = Priority.MEDIUM,
                    complexity = 5,
                    featureId = null,
                    projectId = null,
                    tags = emptyList(),
                    createdAt = Instant.now(),
                    modifiedAt = Instant.now()
                )
            )
        }

        val taskUpdates = taskIds.map { taskId ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(taskId.toString()),
                    "status" to JsonPrimitive("completed")
                )
            )
        }

        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(taskUpdates)
            )
        )

        val startTime = System.currentTimeMillis()
        val result = tool.execute(params, mockContext)
        val duration = System.currentTimeMillis() - startTime

        assertTrue(ResponseTestUtils.isSuccessResponse(result))
        assertEquals("50 tasks updated successfully", ResponseTestUtils.getResponseMessage(result))

        // Verify reasonable performance (should be fast with mock repository)
        assertTrue(duration < 1000, "Execution took ${duration}ms, expected < 1000ms")

        // Verify all tasks were updated
        taskIds.forEach { taskId ->
            val task = (mockTaskRepository.getById(taskId) as Result.Success).data
            assertEquals(TaskStatus.COMPLETED, task.status)
        }
    }

    @Test
    fun `execute - handles 100 task updates at maximum limit`() = runBlocking {
        // Add 100 test tasks
        val taskIds = mutableListOf<UUID>()
        repeat(100) { i ->
            val taskId = UUID.randomUUID()
            taskIds.add(taskId)
            mockTaskRepository.addTask(
                Task(
                    id = taskId,
                    title = "Task $i",
                    summary = "Summary $i",
                    status = TaskStatus.PENDING,
                    priority = Priority.MEDIUM,
                    complexity = 5,
                    featureId = null,
                    projectId = null,
                    tags = emptyList(),
                    createdAt = Instant.now(),
                    modifiedAt = Instant.now()
                )
            )
        }

        val taskUpdates = taskIds.map { taskId ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(taskId.toString()),
                    "priority" to JsonPrimitive("high")
                )
            )
        }

        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(taskUpdates)
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))
        assertEquals("100 tasks updated successfully", ResponseTestUtils.getResponseMessage(result))

        // Verify all tasks were updated
        taskIds.forEach { taskId ->
            val task = (mockTaskRepository.getById(taskId) as Result.Success).data
            assertEquals(Priority.HIGH, task.priority)
        }
    }

    // ========== SUMMARY/DESCRIPTION PARAMETER TESTS ==========

    @Test
    fun `execute - update with summary parameter`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "summary" to JsonPrimitive("Updated summary using summary parameter")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        val updatedTask = (mockTaskRepository.getById(testTask1Id) as Result.Success).data
        assertEquals("Updated summary using summary parameter", updatedTask.summary)
    }

    @Test
    fun `execute - update with description parameter for backwards compatibility`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "description" to JsonPrimitive("Updated summary using deprecated description parameter")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        val updatedTask = (mockTaskRepository.getById(testTask1Id) as Result.Success).data
        assertEquals("Updated summary using deprecated description parameter", updatedTask.summary)
    }

    @Test
    fun `execute - summary parameter takes precedence over description parameter`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "summary" to JsonPrimitive("Summary from summary parameter"),
                                "description" to JsonPrimitive("Summary from description parameter")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        val updatedTask = (mockTaskRepository.getById(testTask1Id) as Result.Success).data
        assertEquals("Summary from summary parameter", updatedTask.summary,
            "Summary parameter should take precedence over description parameter")
    }

    @Test
    fun `execute - mixed summary and description parameters across multiple tasks`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "summary" to JsonPrimitive("Task 1 updated with summary")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask2Id.toString()),
                                "description" to JsonPrimitive("Task 2 updated with description")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask3Id.toString()),
                                "summary" to JsonPrimitive("Task 3 summary wins"),
                                "description" to JsonPrimitive("Task 3 description loses")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))
        assertEquals("3 tasks updated successfully", ResponseTestUtils.getResponseMessage(result))

        // Verify each task was updated correctly
        val task1 = (mockTaskRepository.getById(testTask1Id) as Result.Success).data
        assertEquals("Task 1 updated with summary", task1.summary)

        val task2 = (mockTaskRepository.getById(testTask2Id) as Result.Success).data
        assertEquals("Task 2 updated with description", task2.summary)

        val task3 = (mockTaskRepository.getById(testTask3Id) as Result.Success).data
        assertEquals("Task 3 summary wins", task3.summary,
            "Summary parameter should take precedence over description parameter")
    }

    @Test
    fun `validate params - accepts summary as valid update field`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "summary" to JsonPrimitive("Valid summary field")
                            )
                        )
                    )
                )
            )
        )

        // Should not throw an exception
        tool.validateParams(params)
    }

    @Test
    fun `validate params - accepts description as valid update field for backwards compatibility`() {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "description" to JsonPrimitive("Valid description field")
                            )
                        )
                    )
                )
            )
        )

        // Should not throw an exception
        tool.validateParams(params)
    }

    // ========== SPECIAL SCENARIOS ==========

    @Test
    fun `execute - supports various status format variations`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "tasks" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask1Id.toString()),
                                "status" to JsonPrimitive("in-progress")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask2Id.toString()),
                                "status" to JsonPrimitive("in_progress")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testTask3Id.toString()),
                                "status" to JsonPrimitive("inprogress")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        // All three variations should result in IN_PROGRESS status
        assertEquals(TaskStatus.IN_PROGRESS, (mockTaskRepository.getById(testTask1Id) as Result.Success).data.status)
        assertEquals(TaskStatus.IN_PROGRESS, (mockTaskRepository.getById(testTask2Id) as Result.Success).data.status)
        assertEquals(TaskStatus.IN_PROGRESS, (mockTaskRepository.getById(testTask3Id) as Result.Success).data.status)
    }
}
