package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.ProjectRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class CreateProjectToolTest {
    private val mockProjectRepository = mockk<ProjectRepository>()
    private val mockRepositoryProvider = mockk<RepositoryProvider>().apply {
        coEvery { projectRepository() } returns mockProjectRepository
    }
    private val executionContext = ToolExecutionContext(mockRepositoryProvider)
    private val createProjectTool = CreateProjectTool()

    @Test
    fun `test validate params with valid params`() {
        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
            put("status", "planning")
            put("tags", "test,project")
        }

        // Should not throw exception
        createProjectTool.validateParams(params)
    }

    @Test
    fun `test validate params with minimal params`() {
        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
        }

        // Should not throw exception
        createProjectTool.validateParams(params)
    }

    @Test
    fun `test validate params with invalid status`() {
        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
            put("status", "invalid-status")
        }

        val exception = assertThrows<ToolValidationException> {
            createProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid status"))
    }

    @Test
    fun `test validate params with empty name`() {
        val params = buildJsonObject {
            put("name", "")
            put("summary", "This is a test project")
        }

        val exception = assertThrows<ToolValidationException> {
            createProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("name cannot be empty"))
    }

    @Test
    fun `test validate params with empty summary is allowed`() {
        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "")
        }

        // Empty summary is allowed (agent-generated field)
        assertDoesNotThrow {
            createProjectTool.validateParams(params)
        }
    }

    @Test
    fun `test validate params with missing required params`() {
        val params = buildJsonObject {
            put("status", "planning")
        }

        val exception = assertThrows<ToolValidationException> {
            createProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("name"))
    }

    @Test
    fun `test execute with valid params should create project`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()
        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "This is a test project",
            status = ProjectStatus.PLANNING,
            tags = listOf("test", "project"),
            createdAt = now,
            modifiedAt = now
        )

        coEvery { mockProjectRepository.create(any()) } returns Result.Success(project)

        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
            put("status", "planning")
            put("tags", "test,project")
        }

        val result = createProjectTool.execute(params, executionContext)

        // Verify result
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Project created successfully", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(projectId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertEquals("Test Project", data["name"]?.jsonPrimitive?.content)
        assertEquals("This is a test project", data["summary"]?.jsonPrimitive?.content)
        assertEquals("planning", data["status"]?.jsonPrimitive?.content)
        assertNotNull(data["createdAt"])
        assertNotNull(data["modifiedAt"])

        // Check tags
        val tags = data["tags"]?.jsonArray
        assertNotNull(tags)
        assertEquals(2, tags!!.size)
        assertTrue(tags.any { it.jsonPrimitive.content == "test" })
        assertTrue(tags.any { it.jsonPrimitive.content == "project" })
    }

    @Test
    fun `test execute with database error`() = runBlocking {
        coEvery { mockProjectRepository.create(any()) } returns Result.Error(
            RepositoryError.DatabaseError("Database connection failed")
        )

        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
        }

        val result = createProjectTool.execute(params, executionContext)

        // Verify error response
        assertTrue(result is JsonObject)
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Database error occurred", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.DATABASE_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertEquals("Database connection failed", error["details"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with validation error`() = runBlocking {
        coEvery { mockProjectRepository.create(any()) } returns Result.Error(
            RepositoryError.ValidationError("Project name already exists")
        )

        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
        }

        val result = createProjectTool.execute(params, executionContext)

        // Verify error response
        assertTrue(result is JsonObject)
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals(
            "Validation error: Project name already exists",
            result.jsonObject["message"]?.jsonPrimitive?.content
        )

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertEquals("Project name already exists", error["details"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with conflict error`() = runBlocking {
        coEvery { mockProjectRepository.create(any()) } returns Result.Error(
            RepositoryError.ConflictError("Duplicate project name")
        )

        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
        }

        val result = createProjectTool.execute(params, executionContext)

        // Verify error response
        assertTrue(result is JsonObject)
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Conflict error: Duplicate project name", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.DUPLICATE_RESOURCE, error!!["code"]?.jsonPrimitive?.content)
        assertEquals("Duplicate project name", error["details"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with unexpected error`() = runBlocking {
        coEvery { mockProjectRepository.create(any()) } returns Result.Error(
            RepositoryError.UnknownError("Unexpected error")
        )

        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
        }

        val result = createProjectTool.execute(params, executionContext)

        // Verify error response
        assertTrue(result is JsonObject)
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Failed to create project", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.INTERNAL_ERROR, error!!["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with different status values`() = runBlocking {
        // Test different status formats
        val statusTests = listOf(
            "planning" to ProjectStatus.PLANNING,
            "in-development" to ProjectStatus.IN_DEVELOPMENT,
            "in_development" to ProjectStatus.IN_DEVELOPMENT,
            "indevelopment" to ProjectStatus.IN_DEVELOPMENT,
            "completed" to ProjectStatus.COMPLETED,
            "archived" to ProjectStatus.ARCHIVED
        )

        for ((statusInput, expectedStatus) in statusTests) {
            val project = Project(
                id = UUID.randomUUID(),
                name = "Test Project",
                summary = "This is a test project",
                status = expectedStatus
            )

            coEvery { mockProjectRepository.create(any()) } returns Result.Success(project)

            val params = buildJsonObject {
                put("name", "Test Project")
                put("summary", "This is a test project")
                put("status", statusInput)
            }

            val result = createProjectTool.execute(params, executionContext)
            assertTrue(result is JsonObject)
            assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)

            val data = result.jsonObject["data"]?.jsonObject
            assertNotNull(data)

            // Verify the status was correctly parsed and returned
            val expectedStatusString = when (expectedStatus) {
                ProjectStatus.PLANNING -> "planning"
                ProjectStatus.IN_DEVELOPMENT -> "in-development"
                ProjectStatus.COMPLETED -> "completed"
                ProjectStatus.ARCHIVED -> "archived"
            }
            assertEquals(expectedStatusString, data!!["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test execute with empty tags`() = runBlocking {
        val project = Project(
            id = UUID.randomUUID(),
            name = "Test Project",
            summary = "This is a test project",
            tags = emptyList()
        )

        coEvery { mockProjectRepository.create(any()) } returns Result.Success(project)

        val params = buildJsonObject {
            put("name", "Test Project")
            put("summary", "This is a test project")
            put("tags", "")
        }

        val result = createProjectTool.execute(params, executionContext)
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)

        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)

        // Verify no tags are included in the response when tags are empty
        assertFalse(data!!.containsKey("tags"))
    }
}