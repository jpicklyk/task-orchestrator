package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class CreateTaskToolTest {
    private lateinit var tool: CreateTaskTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var mockTask: Task
    private val taskId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create a mock repository provider and repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockk(relaxed = true)
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create a sample task for the mock response
        mockTask = Task(
            id = taskId,
            title = "Test Task",
            summary = "This is a test task",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 7,
            tags = listOf("test", "kotlin", "mcp")
        )

        // Define behavior for create method
        coEvery {
            mockTaskRepository.create(any())
        } returns Result.Success(mockTask)
        
        // Default behavior for template repository (no templates applied)
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(any(), any(), any())
        } returns Result.Success(emptyMap())

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = CreateTaskTool()
    }

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "status" to JsonPrimitive("pending"),
                "priority" to JsonPrimitive("high"),
                "complexity" to JsonPrimitive(7),
                "tags" to JsonPrimitive("test,kotlin,mcp")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing required parameters validation`() {
        val params = JsonObject(
            mapOf(
                "summary" to JsonPrimitive("This is a test task")
            )
        )

        // Should throw an exception for missing title
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: title"))
    }

    @Test
    fun `test invalid status parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "status" to JsonPrimitive("invalid-status")
            )
        )

        // Should throw an exception for invalid status
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid status"))
    }

    @Test
    fun `test invalid priority parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "priority" to JsonPrimitive("invalid-priority")
            )
        )

        // Should throw an exception for invalid priority
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid priority"))
    }

    @Test
    fun `test invalid complexity parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "complexity" to JsonPrimitive(11)
            )
        )

        // Should throw an exception for invalid complexity
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Complexity must be between 1 and 10"))
    }

    @Test
    fun `test invalid feature ID parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "featureId" to JsonPrimitive("not-a-uuid")
            )
        )

        // Should throw an exception for invalid feature ID
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid feature ID format"))
    }

    @Test
    fun `test successful task creation`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "status" to JsonPrimitive("pending"),
                "priority" to JsonPrimitive("high"),
                "complexity" to JsonPrimitive(7),
                "tags" to JsonPrimitive("test,kotlin,mcp")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertNotNull(resultObj["data"])
        assertEquals("Task created successfully", (resultObj["message"] as JsonPrimitive).content)

        // Check that the data contains the expected fields
        val data = resultObj["data"] as JsonObject
        assertNotNull(data["id"])
        assertEquals("Test Task", (data["title"] as JsonPrimitive).content)
        assertEquals("pending", (data["status"] as JsonPrimitive).content)
        assertEquals("high", (data["priority"] as JsonPrimitive).content)
        assertEquals(7, (data["complexity"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `test task creation with repository error`() = runBlocking {
        // Setup repository to return an error
        coEvery {
            mockTaskRepository.create(any())
        } returns Result.Error(RepositoryError.DatabaseError("Database connection failed"))

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Failed to create task"))

        // Check that the error details are correct
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.DATABASE_ERROR, (error["code"] as JsonPrimitive).content)
    }

    @Test
    fun `test task creation with validation error in repository`() = runBlocking {
        // Setup repository to return a validation error
        coEvery {
            mockTaskRepository.create(any())
        } returns Result.Error(RepositoryError.ValidationError("Task title cannot be empty"))

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Failed to create task"))

        // Check that the error details are correct
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.DATABASE_ERROR, (error["code"] as JsonPrimitive).content)
        assertTrue((error["details"] as JsonPrimitive).content.contains("ValidationError"))
    }

    @Test
    fun `test task creation with null values`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task")
                // No status provided, should use default
                // No priority provided, should use default
                // No complexity provided, should use default
                // No featureId provided
                // No tags provided
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check that the data contains the expected default fields
        val data = resultObj["data"] as JsonObject
        assertEquals("Test Task", (data["title"] as JsonPrimitive).content)
        assertEquals("pending", (data["status"] as JsonPrimitive).content) // Default status
        assertEquals("high", (data["priority"] as JsonPrimitive).content) // Default priority (from the mock)
        assertEquals(7, (data["complexity"] as JsonPrimitive).content.toInt()) // Default complexity (from the mock)
    }

    @Test
    fun `test task creation with feature ID`() = runBlocking {
        val featureId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "featureId" to JsonPrimitive(featureId.toString())
            )
        )

        // Create a task with a feature ID
        val taskWithFeature = mockTask.copy(featureId = featureId)

        // Setup repository to return a task with feature ID
        coEvery {
            mockTaskRepository.create(any())
        } returns Result.Success(taskWithFeature)

        // Setup feature repository to return success for the feature ID validation
        coEvery {
            mockRepositoryProvider.featureRepository().getById(featureId)
        } returns Result.Success(mockk())

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check that the data contains the feature ID
        val data = resultObj["data"] as JsonObject
        assertEquals(featureId.toString(), (data["featureId"] as JsonPrimitive).content)
    }

    @Test
    fun `test task creation with unexpected exception`() = runBlocking {
        // Setup repository to throw an exception
        coEvery {
            mockTaskRepository.create(any())
        } throws RuntimeException("Unexpected error")

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Failed to create task"))

        // Check that the error details are correct
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.INTERNAL_ERROR, (error["code"] as JsonPrimitive).content)
        assertTrue((error["details"] as JsonPrimitive).content.contains("Unexpected error"))
    }
}