package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class DeleteProjectToolTest {

    private lateinit var tool: DeleteProjectTool
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var mockContext: ToolExecutionContext

    @BeforeEach
    fun setup() {
        tool = DeleteProjectTool()
        mockProjectRepository = mockk()
        mockFeatureRepository = mockk()
        mockTaskRepository = mockk()
        mockRepositoryProvider = mockk()
        mockContext = mockk()

        every { mockContext.projectRepository() } returns mockProjectRepository
        every { mockContext.featureRepository() } returns mockFeatureRepository
        every { mockContext.taskRepository() } returns mockTaskRepository
        every { mockContext.repositoryProvider } returns mockRepositoryProvider
    }

    @Test
    fun `test validation succeeds with valid UUID`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(UUID.randomUUID().toString())
            )
        )

        tool.validateParams(params)
        // No exception thrown = test passed
    }

    @Test
    fun `test validation fails with invalid UUID`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive("not-a-uuid")
            )
        )

        var exceptionThrown = false
        try {
            tool.validateParams(params)
        } catch (_: Exception) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun `test successful project deletion with no associated entities`() = runBlocking {
        val projectId = UUID.randomUUID()
        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.PLANNING
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString())
            )
        )

        // Mock repository responses
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockFeatureRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
        coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
        coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

        // Execute tool
        val result = tool.execute(params, mockContext)

        // Verify the result is successful
        assertTrue(result.jsonObject["success"]?.jsonPrimitive?.boolean ?: false)
        assertEquals("Project deleted successfully", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Verify repository was called
        coVerify(exactly = 1) { mockProjectRepository.delete(projectId) }
    }

    @Test
    fun `test deletion prevented when project has features and cascade=false`() = runBlocking {
        val projectId = UUID.randomUUID()
        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.PLANNING
        )

        val feature = Feature(
            id = UUID.randomUUID(),
            projectId = projectId,
            name = "Test Feature",
            summary = "Test feature summary"
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "cascade" to JsonPrimitive(false)
            )
        )

        // Mock repository responses
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockFeatureRepository.findByProject(projectId, any()) } returns Result.Success(listOf(feature))
        coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())

        // Execute tool
        val result = tool.execute(params, mockContext)

        // Verify the result is an error
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals(
            ErrorCodes.DEPENDENCY_ERROR,
            result.jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        )

        // Verify project was not deleted
        coVerify(exactly = 0) { mockProjectRepository.delete(projectId) }
    }

    @Test
    fun `test deletion prevented when project has tasks and cascade=false`() = runBlocking {
        val projectId = UUID.randomUUID()
        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.PLANNING
        )

        val task = Task(
            id = UUID.randomUUID(),
            projectId = projectId,
            title = "Test Task",
            summary = "Test task summary"
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "cascade" to JsonPrimitive(false)
            )
        )

        // Mock repository responses
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockFeatureRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
        coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(listOf(task))

        // Execute tool
        val result = tool.execute(params, mockContext)

        // Verify the result is an error
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals(
            ErrorCodes.DEPENDENCY_ERROR,
            result.jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        )

        // Verify project was not deleted
        coVerify(exactly = 0) { mockProjectRepository.delete(projectId) }
    }

    @Test
    fun `test deletion allowed when project has entities and force=true`() = runBlocking {
        val projectId = UUID.randomUUID()
        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.PLANNING
        )

        val feature = Feature(
            id = UUID.randomUUID(),
            projectId = projectId,
            name = "Test Feature",
            summary = "Test feature summary"
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "force" to JsonPrimitive(true)
            )
        )

        // Mock repository responses
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockFeatureRepository.findByProject(projectId, any()) } returns Result.Success(listOf(feature))
        coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
        coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

        // Execute tool
        val result = tool.execute(params, mockContext)

        // Verify the result is successful
        assertTrue(result.jsonObject["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify project was deleted
        coVerify(exactly = 1) { mockProjectRepository.delete(projectId) }
    }

    @Test
    fun `test cascade deletion deletes associated features and tasks`() = runBlocking {
        val projectId = UUID.randomUUID()
        val featureId = UUID.randomUUID()

        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.PLANNING
        )

        val feature = Feature(
            id = featureId,
            projectId = projectId,
            name = "Test Feature",
            summary = "Test feature summary"
        )

        val featureTask = Task(
            id = UUID.randomUUID(),
            featureId = featureId,
            title = "Feature Task",
            summary = "Task associated with feature"
        )

        val directTask = Task(
            id = UUID.randomUUID(),
            projectId = projectId,
            title = "Direct Task",
            summary = "Task directly associated with project"
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString()),
                "cascade" to JsonPrimitive(true)
            )
        )

        // Mock repository responses
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockFeatureRepository.findByProject(projectId, any()) } returns Result.Success(listOf(feature))
        coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(listOf(directTask))
        coEvery { mockTaskRepository.findByFeature(featureId) } returns Result.Success(
            listOf(featureTask)
        )
        coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)
        coEvery { mockFeatureRepository.delete(any()) } returns Result.Success(true)
        coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

        // Execute tool
        val result = tool.execute(params, mockContext)

        // Verify the result is successful
        assertTrue(result.jsonObject["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify all entities were deleted
        coVerify(exactly = 1) { mockTaskRepository.delete(featureTask.id) }
        coVerify(exactly = 1) { mockTaskRepository.delete(directTask.id) }
        coVerify(exactly = 1) { mockFeatureRepository.delete(featureId) }
        coVerify(exactly = 1) { mockProjectRepository.delete(projectId) }

        // Verify response counts
        val data = result.jsonObject["data"]?.jsonObject
        assertEquals(1, data?.get("featuresAffected")?.jsonPrimitive?.int)
        assertEquals(2, data?.get("tasksAffected")?.jsonPrimitive?.int)
        assertEquals(true, data?.get("cascaded")?.jsonPrimitive?.boolean)
    }

    @Test
    fun `test error handling when project not found`() = runBlocking {
        val projectId = UUID.randomUUID()

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString())
            )
        )

        // Mock repository response for non-existent project
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Error(
            RepositoryError.NotFound(projectId, EntityType.PROJECT, "Project not found")
        )

        // Execute tool
        val result = tool.execute(params, mockContext)

        // Verify the result is an error
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals(
            ErrorCodes.RESOURCE_NOT_FOUND,
            result.jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `test error handling when project deletion fails`() = runBlocking {
        val projectId = UUID.randomUUID()
        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.PLANNING
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(projectId.toString())
            )
        )

        // Mock repository responses
        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockFeatureRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
        coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
        coEvery { mockProjectRepository.delete(projectId) } returns Result.Error(RepositoryError.DatabaseError("Database error"))

        // Execute tool
        val result = tool.execute(params, mockContext)

        // Verify the result is an error
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals(
            ErrorCodes.DATABASE_ERROR,
            result.jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        )
    }
}