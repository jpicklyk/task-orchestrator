package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.repository.ProjectRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
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
 * Tests for UpdateProjectTool focusing on description and summary field updates.
 *
 * This test class verifies:
 * - Description can be added to projects without description
 * - Description can be updated independently
 * - Description can be removed (set to null)
 * - Summary can be updated independently
 * - Both fields can be updated together
 * - Validation rules are enforced
 */
class UpdateProjectToolDescriptionTest {
    private lateinit var tool: UpdateProjectTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private val projectId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockProjectRepository = mockk<ProjectRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = UpdateProjectTool()
    }

    @Test
    fun `should add description to project without description`() = runBlocking {
        // Given - Project with no description
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary",
            description = null
        )

        val newDescription = "Added description after creation"

        val projectSlot = slot<Project>()
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)
        coEvery { mockProjectRepository.update(capture(projectSlot)) } answers {
            Result.Success(projectSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "description" to JsonPrimitive(newDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify project was updated with description
        assertEquals(newDescription, projectSlot.captured.description)
        assertEquals("Original summary", projectSlot.captured.summary) // Summary unchanged
    }

    @Test
    fun `should update existing description`() = runBlocking {
        // Given - Project with existing description
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedDescription = "Updated description with new details"

        val projectSlot = slot<Project>()
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)
        coEvery { mockProjectRepository.update(capture(projectSlot)) } answers {
            Result.Success(projectSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "description" to JsonPrimitive(updatedDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify description was updated
        assertEquals(updatedDescription, projectSlot.captured.description)
        assertEquals("Original summary", projectSlot.captured.summary) // Summary unchanged
    }

    @Test
    fun `should reject JsonNull for description parameter`() = runBlocking {
        // Given - Project with description
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary",
            description = "Original description"
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
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
        // Given - Project with description
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedSummary = "Updated summary - what was accomplished"

        val projectSlot = slot<Project>()
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)
        coEvery { mockProjectRepository.update(capture(projectSlot)) } answers {
            Result.Success(projectSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "summary" to JsonPrimitive(updatedSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary was updated but description unchanged
        assertEquals(updatedSummary, projectSlot.captured.summary)
        assertEquals("Original description", projectSlot.captured.description) // Description unchanged
    }

    @Test
    fun `should update both description and summary together`() = runBlocking {
        // Given - Project with both fields
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedDescription = "Updated description - new requirements"
        val updatedSummary = "Updated summary - implementation complete"

        val projectSlot = slot<Project>()
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)
        coEvery { mockProjectRepository.update(capture(projectSlot)) } answers {
            Result.Success(projectSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
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
        assertEquals(updatedDescription, projectSlot.captured.description)
        assertEquals(updatedSummary, projectSlot.captured.summary)
    }

    @Test
    fun `should treat empty string summary as null and preserve existing value`() = runBlocking {
        // Given - Project with summary
        // Note: Empty/blank strings are treated as null by optionalString() helper
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary",
            description = "Original description"
        )

        val projectSlot = slot<Project>()
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)
        coEvery { mockProjectRepository.update(capture(projectSlot)) } answers {
            Result.Success(projectSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "summary" to JsonPrimitive("") // Empty string treated as null = keep existing
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary was preserved (empty string treated as "no change")
        assertEquals("Original summary", projectSlot.captured.summary)
        assertEquals("Original description", projectSlot.captured.description) // Description unchanged
    }

    @Test
    fun `should enforce summary max length of 500 characters`() = runBlocking {
        // Given - Project to update with long summary
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary"
        )

        val tooLongSummary = "x".repeat(501) // 501 characters - exceeds limit

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)
        // Note: Don't need to mock update() because domain validation will fail before update is called

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
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
        // Given - Project to update with summary at max length
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary"
        )

        val maxLengthSummary = "x".repeat(500) // Exactly 500 characters

        val projectSlot = slot<Project>()
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)
        coEvery { mockProjectRepository.update(capture(projectSlot)) } answers {
            Result.Success(projectSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "summary" to JsonPrimitive(maxLengthSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary at max length was accepted
        assertEquals(500, projectSlot.captured.summary.length)
    }

    @Test
    fun `should allow very long description without limit`() = runBlocking {
        // Given - Project to update with very long description
        val originalProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "Original summary",
            description = "Short description"
        )

        val veryLongDescription = "x".repeat(10000) // 10,000 characters - should be allowed

        val projectSlot = slot<Project>()
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(originalProject)
        coEvery { mockProjectRepository.update(capture(projectSlot)) } answers {
            Result.Success(projectSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "description" to JsonPrimitive(veryLongDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify long description was accepted
        assertEquals(10000, projectSlot.captured.description?.length)
    }
}
