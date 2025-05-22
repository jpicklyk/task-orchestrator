package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.EntityType
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

class UpdateProjectToolTest {
    private val mockProjectRepository = mockk<ProjectRepository>()
    private val mockRepositoryProvider = mockk<RepositoryProvider>().apply {
        coEvery { projectRepository() } returns mockProjectRepository
    }
    private val executionContext = ToolExecutionContext(mockRepositoryProvider)
    private val updateProjectTool = UpdateProjectTool()

    @Test
    fun `test validate params with valid id`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
            put("name", "Updated Project")
        }

        // Should not throw exception
        updateProjectTool.validateParams(params)
    }

    @Test
    fun `test validate params with invalid id format`() {
        val params = buildJsonObject {
            put("id", "not-a-uuid")
            put("name", "Updated Project")
        }

        val exception = assertThrows<ToolValidationException> {
            updateProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid id format"))
    }

    @Test
    fun `test validate params with empty name`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
            put("name", "")
        }

        val exception = assertThrows<ToolValidationException> {
            updateProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("name cannot be empty if provided"))
    }

    @Test
    fun `test validate params with empty summary`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
            put("summary", "")
        }

        val exception = assertThrows<ToolValidationException> {
            updateProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("summary cannot be empty if provided"))
    }

    @Test
    fun `test validate params with invalid status`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
            put("status", "invalid-status")
        }

        val exception = assertThrows<ToolValidationException> {
            updateProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid status"))
    }

    @Test
    fun `test execute with valid params updates name only`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()
        val laterNow = now.plusSeconds(3600)

        val existingProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "This is the original project",
            status = ProjectStatus.PLANNING,
            tags = listOf("original"),
            createdAt = now,
            modifiedAt = now
        )

        val updatedProject = Project(
            id = projectId,
            name = "Updated Project",
            summary = "This is the original project",
            status = ProjectStatus.PLANNING,
            tags = listOf("original"),
            createdAt = now,
            modifiedAt = laterNow
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(existingProject)
        coEvery { mockProjectRepository.update(any()) } returns Result.Success(updatedProject)

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("name", "Updated Project")
        }

        val result = updateProjectTool.execute(params, executionContext)

        // Verify result
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Project updated successfully", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(projectId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertEquals("Updated Project", data["name"]?.jsonPrimitive?.content)
        assertEquals("This is the original project", data["summary"]?.jsonPrimitive?.content)
        assertEquals("planning", data["status"]?.jsonPrimitive?.content)
        assertNotNull(data["createdAt"])
        assertNotNull(data["modifiedAt"])
    }

    @Test
    fun `test execute with valid params updates summary only`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()
        val laterNow = now.plusSeconds(3600)

        val existingProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "This is the original project",
            status = ProjectStatus.PLANNING,
            tags = listOf("original"),
            createdAt = now,
            modifiedAt = now
        )

        val updatedProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "This is the updated project summary",
            status = ProjectStatus.PLANNING,
            tags = listOf("original"),
            createdAt = now,
            modifiedAt = laterNow
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(existingProject)
        coEvery { mockProjectRepository.update(any()) } returns Result.Success(updatedProject)

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("summary", "This is the updated project summary")
        }

        val result = updateProjectTool.execute(params, executionContext)

        // Verify result
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals("Original Project", data!!["name"]?.jsonPrimitive?.content)
        assertEquals("This is the updated project summary", data["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with non-existent id returns not found error`() = runBlocking {
        val projectId = UUID.randomUUID()

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Error(
            RepositoryError.NotFound(projectId, EntityType.PROJECT, "Project not found")
        )

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("name", "Updated Project")
        }

        val result = updateProjectTool.execute(params, executionContext)

        // Verify error response
        assertTrue(result is JsonObject)
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Project not found", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with database error`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()

        val existingProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "This is the original project",
            status = ProjectStatus.PLANNING,
            createdAt = now,
            modifiedAt = now
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(existingProject)
        coEvery { mockProjectRepository.update(any()) } returns Result.Error(
            RepositoryError.DatabaseError("Database connection failed")
        )

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("name", "Updated Project")
        }

        val result = updateProjectTool.execute(params, executionContext)

        // Verify error response
        assertTrue(result is JsonObject)
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Database error occurred", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.DATABASE_ERROR, error!!["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with validation error`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()

        val existingProject = Project(
            id = projectId,
            name = "Original Project",
            summary = "This is the original project",
            status = ProjectStatus.PLANNING,
            createdAt = now,
            modifiedAt = now
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(existingProject)
        coEvery { mockProjectRepository.update(any()) } returns Result.Error(
            RepositoryError.ValidationError("Project name already exists")
        )

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("name", "Updated Project")
        }

        val result = updateProjectTool.execute(params, executionContext)

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
            val projectId = UUID.randomUUID()
            val now = Instant.now()

            val existingProject = Project(
                id = projectId,
                name = "Original Project",
                summary = "This is the original project",
                status = ProjectStatus.PLANNING,
                createdAt = now,
                modifiedAt = now
            )

            val updatedProject = existingProject.copy(status = expectedStatus)

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(existingProject)
            coEvery { mockProjectRepository.update(any()) } returns Result.Success(updatedProject)

            val params = buildJsonObject {
                put("id", projectId.toString())
                put("status", statusInput)
            }

            val result = updateProjectTool.execute(params, executionContext)
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
}