package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
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
 * Tests for UpdateTaskTool focusing on description and summary field updates.
 *
 * This test class verifies:
 * - Description can be added to tasks without description
 * - Description can be updated independently
 * - Description can be removed (set to null)
 * - Summary can be updated independently
 * - Both fields can be updated together
 * - Validation: description cannot be blank
 * - Validation: summary cannot exceed 500 characters
 */
class UpdateTaskToolDescriptionTest {
    private lateinit var tool: UpdateTaskTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private val taskId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockTaskRepository = mockk<TaskRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockk(relaxed = true)

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = UpdateTaskTool()
    }

    @Test
    fun `should add description to task without description`() = runBlocking {
        // Given - Task with no description
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary",
            description = null
        )

        val newDescription = "Added description after creation"

        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "description" to JsonPrimitive(newDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify task was updated with description
        assertEquals(newDescription, taskSlot.captured.description)
        assertEquals("Original summary", taskSlot.captured.summary) // Summary unchanged
    }

    @Test
    fun `should update existing description`() = runBlocking {
        // Given - Task with existing description
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedDescription = "Updated description with new details"

        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "description" to JsonPrimitive(updatedDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify description was updated
        assertEquals(updatedDescription, taskSlot.captured.description)
        assertEquals("Original summary", taskSlot.captured.summary) // Summary unchanged
    }

    @Test
    fun `should reject JsonNull for description parameter`() = runBlocking {
        // Given - Task with description
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary",
            description = "Original description"
        )

        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "description" to JsonNull
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then - Should fail validation (JsonNull not supported for description)
        val jsonResult = result.jsonObject
        assertFalse(jsonResult["success"]?.jsonPrimitive?.boolean ?: true)

        val message = jsonResult["message"]?.jsonPrimitive?.content
        assertTrue(message?.contains("string") ?: false, "Error message should mention string type requirement")
    }

    @Test
    fun `should update summary independently of description`() = runBlocking {
        // Given - Task with description
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedSummary = "Updated summary - what was accomplished"

        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "summary" to JsonPrimitive(updatedSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary was updated but description unchanged
        assertEquals(updatedSummary, taskSlot.captured.summary)
        assertEquals("Original description", taskSlot.captured.description) // Description unchanged
    }

    @Test
    fun `should update both description and summary together`() = runBlocking {
        // Given - Task with both fields
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedDescription = "Updated description - new requirements"
        val updatedSummary = "Updated summary - implementation complete"

        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "description" to JsonPrimitive(updatedDescription),
                "summary" to JsonPrimitive(updatedSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify both fields were updated
        assertEquals(updatedDescription, taskSlot.captured.description)
        assertEquals(updatedSummary, taskSlot.captured.summary)
    }

    @Test
    fun `should treat empty string summary as null and preserve existing value`() = runBlocking {
        // Given - Task with summary
        // Note: Empty/blank strings are treated as null by optionalString() helper
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary",
            description = "Original description"
        )

        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "summary" to JsonPrimitive("") // Empty string treated as null = keep existing
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary was preserved (empty string treated as "no change")
        assertEquals("Original summary", taskSlot.captured.summary)
        assertEquals("Original description", taskSlot.captured.description) // Description unchanged
    }

    @Test
    fun `should enforce summary max length of 500 characters`() = runBlocking {
        // Given - Task to update with long summary
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary"
        )

        val tooLongSummary = "x".repeat(501) // 501 characters - exceeds limit

        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        // Note: Don't need to mock update() because domain validation will fail before update is called

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "summary" to JsonPrimitive(tooLongSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then - Should fail validation (domain model enforces max 500 chars)
        val jsonResult = result.jsonObject
        assertFalse(jsonResult["success"]?.jsonPrimitive?.boolean ?: true)

        val message = jsonResult["message"]?.jsonPrimitive?.content
        assertTrue(
            message?.contains("concise") == true || message?.contains("500") == true || message?.contains("Failed to update") == true,
            "Error message should mention validation failure. Actual: $message"
        )
    }

    @Test
    fun `should allow summary at max length of 500 characters`() = runBlocking {
        // Given - Task to update with summary at max length
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary"
        )

        val maxLengthSummary = "x".repeat(500) // Exactly 500 characters

        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
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

    @Test
    fun `should allow very long description without limit`() = runBlocking {
        // Given - Task to update with very long description
        val originalTask = Task(
            id = taskId,
            title = "Original Task",
            summary = "Original summary",
            description = "Short description"
        )

        val veryLongDescription = "x".repeat(10000) // 10,000 characters - should be allowed

        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "description" to JsonPrimitive(veryLongDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify long description was accepted
        assertEquals(10000, taskSlot.captured.description?.length)
    }

    @Test
    fun `should update description from user and summary from agent workflow`() = runBlocking {
        // Given - Simulating Task Manager workflow:
        // 1. Task created with user description, empty summary
        // 2. Task Manager updates summary when work is complete
        val originalTask = Task(
            id = taskId,
            title = "Implement OAuth2",
            summary = "", // Empty initially
            description = "User needs: Implement OAuth2 authentication flow with JWT tokens"
        )

        val agentSummary = "Implemented OAuth2 with JWT tokens, added refresh token support, configured CORS"

        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(originalTask)
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "summary" to JsonPrimitive(agentSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary was populated by agent, description unchanged
        assertEquals(agentSummary, taskSlot.captured.summary)
        assertEquals("User needs: Implement OAuth2 authentication flow with JWT tokens", taskSlot.captured.description)
    }
}
