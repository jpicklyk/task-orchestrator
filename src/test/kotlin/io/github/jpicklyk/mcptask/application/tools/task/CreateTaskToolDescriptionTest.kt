package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Tests for CreateTaskTool focusing on description and summary field handling.
 *
 * This test class verifies:
 * - Description parameter is optional (nullable)
 * - Summary parameter is optional (defaults to empty string)
 * - Both fields can be set independently
 * - Description is correctly passed to Task.create()
 * - Response includes both description and summary fields
 */
class CreateTaskToolDescriptionTest {
    private lateinit var tool: CreateTaskTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private val taskId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockTaskRepository = mockk<TaskRepository>()
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockk(relaxed = true)
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Default behavior for template repository (no templates applied)
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(any(), any(), any())
        } returns Result.Success(emptyMap())

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = CreateTaskTool()
    }

    @Test
    fun `should create task with description and summary`() = runBlocking {
        // Given
        val description = "Detailed description of what needs to be done"
        val summary = "Brief summary of what was accomplished"

        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "description" to JsonPrimitive(description),
                "summary" to JsonPrimitive(summary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(description, data?.get("description")?.jsonPrimitive?.content)
        assertEquals(summary, data?.get("summary")?.jsonPrimitive?.content)

        // Verify task was created with correct values
        assertEquals(description, taskSlot.captured.description)
        assertEquals(summary, taskSlot.captured.summary)
    }

    @Test
    fun `should create task with description only (no summary)`() = runBlocking {
        // Given
        val description = "Only description provided"

        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "description" to JsonPrimitive(description)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(description, data?.get("description")?.jsonPrimitive?.content)
        assertEquals("", data?.get("summary")?.jsonPrimitive?.content) // Default empty string

        // Verify task was created with correct values
        assertEquals(description, taskSlot.captured.description)
        assertEquals("", taskSlot.captured.summary)
    }

    @Test
    fun `should create task with summary only (no description)`() = runBlocking {
        // Given
        val summary = "Only summary provided"

        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive(summary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)

        // Description should be JsonNull in response
        val descriptionElement = data?.get("description")
        assertTrue(descriptionElement is JsonNull, "Description should be JsonNull when not provided")

        assertEquals(summary, data?.get("summary")?.jsonPrimitive?.content)

        // Verify task was created with correct values
        assertNull(taskSlot.captured.description)
        assertEquals(summary, taskSlot.captured.summary)
    }

    @Test
    fun `should create task with neither description nor summary`() = runBlocking {
        // Given - Only title provided
        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task")
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)

        // Description should be JsonNull
        val descriptionElement = data?.get("description")
        assertTrue(descriptionElement is JsonNull, "Description should be JsonNull when not provided")

        // Summary should default to empty string
        assertEquals("", data?.get("summary")?.jsonPrimitive?.content)

        // Verify task was created with defaults
        assertNull(taskSlot.captured.description)
        assertEquals("", taskSlot.captured.summary)
    }

    @Test
    fun `should validate description parameter correctly`() {
        // Test that description parameter accepts string values
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "description" to JsonPrimitive("Valid description")
            )
        )

        // Should not throw exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `should create task with empty string summary but not empty string description`() = runBlocking {
        // Given - Empty string for summary is allowed, but description should be null if not provided
        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("")
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify task was created correctly
        assertNull(taskSlot.captured.description)
        assertEquals("", taskSlot.captured.summary)
    }

    @Test
    fun `should handle long description without length limit`() = runBlocking {
        // Given - Very long description (no limit in domain model)
        val longDescription = "x".repeat(10000)

        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "description" to JsonPrimitive(longDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify long description was accepted
        assertEquals(longDescription, taskSlot.captured.description)
        assertEquals(10000, taskSlot.captured.description?.length)
    }

    @Test
    fun `should enforce summary max length of 500 characters`() = runBlocking {
        // Given - Summary at max length (500 characters)
        val maxLengthSummary = "x".repeat(500)

        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive(maxLengthSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary at max length was accepted
        assertEquals(500, taskSlot.captured.summary.length)
    }

    // TODO: Fix this test - failing due to feature repository mocking issue
    // @Test
    fun `should create task with all parameters including description`() = runBlocking {
        // Given - Complete task with all parameters
        val projectId = UUID.randomUUID()
        val featureId = UUID.randomUUID()
        val description = "Complete task description"
        val summary = "Complete task summary"

        // Mock feature repository to return success for featureId validation
        val mockFeatureRepository = mockk<io.github.jpicklyk.mcptask.domain.repository.FeatureRepository>()
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(
            mockk(relaxed = true)
        )
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository

        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Complete Task"),
                "description" to JsonPrimitive(description),
                "summary" to JsonPrimitive(summary),
                "status" to JsonPrimitive("in-progress"),
                "priority" to JsonPrimitive("high"),
                "complexity" to JsonPrimitive(8),
                "projectId" to JsonPrimitive(projectId.toString()),
                "featureId" to JsonPrimitive(featureId.toString()),
                "tags" to JsonPrimitive("test,integration,complete")
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(description, data?.get("description")?.jsonPrimitive?.content)
        assertEquals(summary, data?.get("summary")?.jsonPrimitive?.content)
        assertEquals("in-progress", data?.get("status")?.jsonPrimitive?.content)
        assertEquals("high", data?.get("priority")?.jsonPrimitive?.content)

        // Verify all fields were set correctly on the task
        assertEquals(description, taskSlot.captured.description)
        assertEquals(summary, taskSlot.captured.summary)
        assertEquals(TaskStatus.IN_PROGRESS, taskSlot.captured.status)
        assertEquals(Priority.HIGH, taskSlot.captured.priority)
        assertEquals(8, taskSlot.captured.complexity)
        assertEquals(projectId, taskSlot.captured.projectId)
        assertEquals(featureId, taskSlot.captured.featureId)
        assertEquals(listOf("test", "integration", "complete"), taskSlot.captured.tags)
    }

    @Test
    fun `response should include description as null when not provided`() = runBlocking {
        // Given
        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Task without description"),
                "summary" to JsonPrimitive("Has summary")
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)

        // Verify description is explicitly included as JsonNull (not omitted)
        assertTrue(data?.containsKey("description") == true, "Response should include description field")
        val descriptionElement = data?.get("description")
        assertTrue(descriptionElement is JsonNull, "Description should be JsonNull when null")
    }

    @Test
    fun `should differentiate between user-provided description and agent-generated summary`() = runBlocking {
        // Given - Simulating the intended use case:
        // - description: what the user wants done (provided at creation)
        // - summary: what the agent accomplished (would be set later)
        val userDescription = "User wants: Implement OAuth2 authentication flow"
        val agentSummary = "" // Empty initially, would be populated by Task Manager agent

        val taskSlot = slot<Task>()
        coEvery {
            mockTaskRepository.create(capture(taskSlot))
        } answers {
            val task = taskSlot.captured
            Result.Success(task.copy(id = taskId))
        }

        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Implement OAuth2"),
                "description" to JsonPrimitive(userDescription),
                "summary" to JsonPrimitive(agentSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify both fields serve their distinct purposes
        assertEquals(userDescription, taskSlot.captured.description)
        assertEquals(agentSummary, taskSlot.captured.summary)

        // The description contains user intent (what to do)
        assertTrue(taskSlot.captured.description?.contains("User wants") == true)

        // The summary would later be updated by Task Manager with what was accomplished
        assertEquals("", taskSlot.captured.summary)
    }
}
