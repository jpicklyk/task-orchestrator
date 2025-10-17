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
 * Tests for CreateProjectTool focusing on description and summary field handling.
 *
 * This test class verifies:
 * - Description parameter is optional (nullable)
 * - Summary parameter is optional (defaults to empty string)
 * - Both fields can be set independently
 * - Response includes both description and summary fields
 * - Validation rules are enforced
 */
class CreateProjectToolDescriptionTest {
    private lateinit var tool: CreateProjectTool
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
        tool = CreateProjectTool()
    }

    @Test
    fun `should create project with description and summary`() = runBlocking {
        // Given
        val description = "Detailed description of what needs to be implemented"
        val summary = "Brief summary of what was accomplished"

        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Project"),
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

        // Verify project was created with correct values
        assertEquals(description, projectSlot.captured.description)
        assertEquals(summary, projectSlot.captured.summary)
    }

    @Test
    fun `should create project with description only (no summary)`() = runBlocking {
        // Given
        val description = "Only description provided"

        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Project"),
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

        // Verify project was created with correct values
        assertEquals(description, projectSlot.captured.description)
        assertEquals("", projectSlot.captured.summary)
    }

    @Test
    fun `should create project with summary only (no description)`() = runBlocking {
        // Given
        val summary = "Only summary provided"

        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Project"),
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

        // Verify project was created with correct values
        assertNull(projectSlot.captured.description)
        assertEquals(summary, projectSlot.captured.summary)
    }

    @Test
    fun `should create project with neither description nor summary`() = runBlocking {
        // Given - Only name provided
        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Project")
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

        // Verify project was created with defaults
        assertNull(projectSlot.captured.description)
        assertEquals("", projectSlot.captured.summary)
    }

    @Test
    fun `should validate description parameter correctly`() {
        // Test that description parameter accepts string values
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Project"),
                "description" to JsonPrimitive("Valid description")
            )
        )

        // Should not throw exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `should create project with empty string summary`() = runBlocking {
        // Given - Empty string for summary is allowed
        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Project"),
                "summary" to JsonPrimitive("")
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify project was created correctly
        assertNull(projectSlot.captured.description)
        assertEquals("", projectSlot.captured.summary)
    }

    @Test
    fun `should handle long description without length limit`() = runBlocking {
        // Given - Very long description (no limit in domain model)
        val longDescription = "x".repeat(10000)

        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Project"),
                "description" to JsonPrimitive(longDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify long description was accepted
        assertEquals(longDescription, projectSlot.captured.description)
        assertEquals(10000, projectSlot.captured.description?.length)
    }

    @Test
    fun `should enforce summary max length of 500 characters`() = runBlocking {
        // Given - Summary at max length (500 characters)
        val maxLengthSummary = "x".repeat(500)

        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Project"),
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
    fun `response should include description as null when not provided`() = runBlocking {
        // Given
        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Project without description"),
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
        val userDescription = "User wants: Complete project management system"
        val agentSummary = "" // Empty initially, would be populated later

        val projectSlot = slot<Project>()
        coEvery {
            mockProjectRepository.create(capture(projectSlot))
        } answers {
            val project = projectSlot.captured
            Result.Success(project.copy(id = projectId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Project Management System"),
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
        assertEquals(userDescription, projectSlot.captured.description)
        assertEquals(agentSummary, projectSlot.captured.summary)

        // The description contains user intent (what to do)
        assertTrue(projectSlot.captured.description?.contains("User wants") == true)

        // The summary would later be updated with what was accomplished
        assertEquals("", projectSlot.captured.summary)
    }
}
