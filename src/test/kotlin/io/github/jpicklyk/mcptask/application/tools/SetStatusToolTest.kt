package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class SetStatusToolTest {
    private lateinit var tool: SetStatusTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private lateinit var validTaskId: UUID
    private lateinit var validFeatureId: UUID
    private lateinit var validProjectId: UUID
    private lateinit var mockTask: Task
    private lateinit var mockFeature: Feature
    private lateinit var mockProject: Project

    @BeforeEach
    fun setup() {
        tool = SetStatusTool()

        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockFeatureRepository = mockk<FeatureRepository>()
        mockProjectRepository = mockk<ProjectRepository>()
        mockDependencyRepository = mockk<DependencyRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create test UUIDs
        validTaskId = UUID.randomUUID()
        validFeatureId = UUID.randomUUID()
        validProjectId = UUID.randomUUID()

        // Create mock entities
        mockTask = Task(
            id = validTaskId,
            title = "Test Task",
            summary = "Test task summary",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 5,
            createdAt = Instant.now().minusSeconds(3600),
            modifiedAt = Instant.now().minusSeconds(1800)
        )

        mockFeature = Feature(
            id = validFeatureId,
            name = "Test Feature",
            summary = "Test feature summary",
            status = FeatureStatus.PLANNING,
            priority = Priority.MEDIUM
        )

        mockProject = Project(
            id = validProjectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.PLANNING
        )

        // Default behavior: entity not found
        coEvery { mockTaskRepository.getById(any()) } returns Result.Error(
            RepositoryError.NotFound(validTaskId, EntityType.TASK, "Task not found")
        )
        coEvery { mockFeatureRepository.getById(any()) } returns Result.Error(
            RepositoryError.NotFound(validFeatureId, EntityType.FEATURE, "Feature not found")
        )
        coEvery { mockProjectRepository.getById(any()) } returns Result.Error(
            RepositoryError.NotFound(validProjectId, EntityType.PROJECT, "Project not found")
        )
    }

    // ========== Parameter Validation Tests ==========

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing required id parameter validation`() {
        val params = JsonObject(
            mapOf(
                "status" to JsonPrimitive("completed")
            )
        )

        // Should throw an exception for missing id
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: id"))
    }

    @Test
    fun `test invalid id format validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive("not-a-uuid"),
                "status" to JsonPrimitive("completed")
            )
        )

        // Should throw an exception for invalid id format
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid ID format"))
    }

    @Test
    fun `test missing required status parameter validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString())
            )
        )

        // Should throw an exception for missing status
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: status"))
    }

    // ========== Task Status Update Tests ==========

    @Test
    fun `test successful task status update`() = runBlocking {
        // Configure mock for task found
        coEvery { mockTaskRepository.getById(validTaskId) } returns Result.Success(mockTask)
        coEvery { mockDependencyRepository.findByFromTaskId(validTaskId) } returns emptyList()
        coEvery { mockTaskRepository.update(any()) } answers {
            Result.Success(firstArg<Task>().copy(status = TaskStatus.IN_PROGRESS, modifiedAt = Instant.now()))
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "status" to JsonPrimitive("in-progress")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertEquals("Task status updated successfully", message)

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(validTaskId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertEquals("TASK", data["entityType"]?.jsonPrimitive?.content)
        assertEquals("in-progress", data["status"]?.jsonPrimitive?.content)
        assertNotNull(data["modifiedAt"], "ModifiedAt should be present")
    }

    @Test
    fun `test task status update with blocking dependencies warning`() = runBlocking {
        // Configure mock for task found with blocking dependencies
        coEvery { mockTaskRepository.getById(validTaskId) } returns Result.Success(mockTask)

        val blockedTaskId = UUID.randomUUID()
        val blockingDependency = Dependency(
            id = UUID.randomUUID(),
            fromTaskId = validTaskId,
            toTaskId = blockedTaskId,
            type = DependencyType.BLOCKS
        )
        coEvery { mockDependencyRepository.findByFromTaskId(validTaskId) } returns listOf(blockingDependency)

        coEvery { mockTaskRepository.update(any()) } answers {
            Result.Success(firstArg<Task>().copy(status = TaskStatus.COMPLETED, modifiedAt = Instant.now()))
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(1, data!!["blockingTasksCount"]?.jsonPrimitive?.int)
        assertEquals("This task blocks 1 other task(s)", data["warning"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test invalid task status`() = runBlocking {
        // Configure mock for task found
        coEvery { mockTaskRepository.getById(validTaskId) } returns Result.Success(mockTask)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "status" to JsonPrimitive("invalid-status")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Success should be false")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertTrue(message?.contains("Invalid task status") ?: false)

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("VALIDATION_ERROR", error!!["code"]?.jsonPrimitive?.content)
        assertTrue(error["details"]?.jsonPrimitive?.content?.contains("Valid task statuses") ?: false)
    }

    @Test
    fun `test task update database error`() = runBlocking {
        // Configure mock for task found but update fails
        coEvery { mockTaskRepository.getById(validTaskId) } returns Result.Success(mockTask)
        coEvery { mockDependencyRepository.findByFromTaskId(validTaskId) } returns emptyList()
        coEvery { mockTaskRepository.update(any()) } returns Result.Error(
            RepositoryError.DatabaseError("Failed to update task", null)
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Success should be false")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertEquals("Failed to update task status", message)

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("DATABASE_ERROR", error!!["code"]?.jsonPrimitive?.content)
    }

    // ========== Feature Status Update Tests ==========

    @Test
    fun `test successful feature status update`() = runBlocking {
        // Configure mock for feature found (task not found, feature found)
        coEvery { mockFeatureRepository.getById(validFeatureId) } returns Result.Success(mockFeature)
        coEvery { mockFeatureRepository.update(any()) } answers {
            Result.Success(firstArg<Feature>().update(status = FeatureStatus.IN_DEVELOPMENT))
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validFeatureId.toString()),
                "status" to JsonPrimitive("in-development")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertEquals("Feature status updated successfully", message)

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(validFeatureId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertEquals("FEATURE", data["entityType"]?.jsonPrimitive?.content)
        assertEquals("in-development", data["status"]?.jsonPrimitive?.content)
        assertNotNull(data["modifiedAt"], "ModifiedAt should be present")
    }

    @Test
    fun `test invalid feature status`() = runBlocking {
        // Configure mock for feature found
        coEvery { mockFeatureRepository.getById(validFeatureId) } returns Result.Success(mockFeature)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validFeatureId.toString()),
                "status" to JsonPrimitive("invalid-status")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Success should be false")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertTrue(message?.contains("Invalid feature status") ?: false)

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("VALIDATION_ERROR", error!!["code"]?.jsonPrimitive?.content)
        assertTrue(error["details"]?.jsonPrimitive?.content?.contains("Valid feature statuses") ?: false)
    }

    @Test
    fun `test feature update database error`() = runBlocking {
        // Configure mock for feature found but update fails
        coEvery { mockFeatureRepository.getById(validFeatureId) } returns Result.Success(mockFeature)
        coEvery { mockFeatureRepository.update(any()) } returns Result.Error(
            RepositoryError.DatabaseError("Failed to update feature", null)
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validFeatureId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Success should be false")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertEquals("Failed to update feature status", message)

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("DATABASE_ERROR", error!!["code"]?.jsonPrimitive?.content)
    }

    // ========== Project Status Update Tests ==========

    @Test
    fun `test successful project status update`() = runBlocking {
        // Configure mock for project found (task and feature not found, project found)
        coEvery { mockProjectRepository.getById(validProjectId) } returns Result.Success(mockProject)
        coEvery { mockProjectRepository.update(any()) } answers {
            Result.Success(firstArg<Project>().update(status = ProjectStatus.IN_DEVELOPMENT))
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validProjectId.toString()),
                "status" to JsonPrimitive("in-development")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertEquals("Project status updated successfully", message)

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(validProjectId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertEquals("PROJECT", data["entityType"]?.jsonPrimitive?.content)
        assertEquals("in-development", data["status"]?.jsonPrimitive?.content)
        assertNotNull(data["modifiedAt"], "ModifiedAt should be present")
    }

    @Test
    fun `test invalid project status`() = runBlocking {
        // Configure mock for project found
        coEvery { mockProjectRepository.getById(validProjectId) } returns Result.Success(mockProject)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validProjectId.toString()),
                "status" to JsonPrimitive("invalid-status")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Success should be false")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertTrue(message?.contains("Invalid project status") ?: false)

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("VALIDATION_ERROR", error!!["code"]?.jsonPrimitive?.content)
        assertTrue(error["details"]?.jsonPrimitive?.content?.contains("Valid project statuses") ?: false)
    }

    @Test
    fun `test project update database error`() = runBlocking {
        // Configure mock for project found but update fails
        coEvery { mockProjectRepository.getById(validProjectId) } returns Result.Success(mockProject)
        coEvery { mockProjectRepository.update(any()) } returns Result.Error(
            RepositoryError.DatabaseError("Failed to update project", null)
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validProjectId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Success should be false")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertEquals("Failed to update project status", message)

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("DATABASE_ERROR", error!!["code"]?.jsonPrimitive?.content)
    }

    // ========== Entity Detection Tests ==========

    @Test
    fun `test entity not found in any repository`() = runBlocking {
        val unknownId = UUID.randomUUID()

        // All repositories return not found
        coEvery { mockTaskRepository.getById(unknownId) } returns Result.Error(
            RepositoryError.NotFound(unknownId, EntityType.TASK, "Task not found")
        )
        coEvery { mockFeatureRepository.getById(unknownId) } returns Result.Error(
            RepositoryError.NotFound(unknownId, EntityType.FEATURE, "Feature not found")
        )
        coEvery { mockProjectRepository.getById(unknownId) } returns Result.Error(
            RepositoryError.NotFound(unknownId, EntityType.PROJECT, "Project not found")
        )

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(unknownId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Success should be false")

        val message = responseObj["message"]?.jsonPrimitive?.content
        assertEquals("Entity not found", message)

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("RESOURCE_NOT_FOUND", error!!["code"]?.jsonPrimitive?.content)
        assertTrue(error["details"]?.jsonPrimitive?.content?.contains("No task, feature, or project exists") ?: false)
    }

    @Test
    fun `test auto-detection tries task repository first`() = runBlocking {
        // Configure mock so task is found
        coEvery { mockTaskRepository.getById(validTaskId) } returns Result.Success(mockTask)
        coEvery { mockDependencyRepository.findByFromTaskId(validTaskId) } returns emptyList()
        coEvery { mockTaskRepository.update(any()) } answers {
            Result.Success(firstArg<Task>().copy(status = TaskStatus.COMPLETED, modifiedAt = Instant.now()))
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data)
        assertEquals("TASK", data!!["entityType"]?.jsonPrimitive?.content, "Should detect as TASK")
    }

    @Test
    fun `test auto-detection tries feature repository second`() = runBlocking {
        // Task not found, feature found
        coEvery { mockTaskRepository.getById(validFeatureId) } returns Result.Error(
            RepositoryError.NotFound(validFeatureId, EntityType.TASK, "Task not found")
        )
        coEvery { mockFeatureRepository.getById(validFeatureId) } returns Result.Success(mockFeature)
        coEvery { mockFeatureRepository.update(any()) } answers {
            Result.Success(firstArg<Feature>().update(status = FeatureStatus.COMPLETED))
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validFeatureId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data)
        assertEquals("FEATURE", data!!["entityType"]?.jsonPrimitive?.content, "Should detect as FEATURE")
    }

    @Test
    fun `test auto-detection tries project repository third`() = runBlocking {
        // Task and feature not found, project found
        coEvery { mockTaskRepository.getById(validProjectId) } returns Result.Error(
            RepositoryError.NotFound(validProjectId, EntityType.TASK, "Task not found")
        )
        coEvery { mockFeatureRepository.getById(validProjectId) } returns Result.Error(
            RepositoryError.NotFound(validProjectId, EntityType.FEATURE, "Feature not found")
        )
        coEvery { mockProjectRepository.getById(validProjectId) } returns Result.Success(mockProject)
        coEvery { mockProjectRepository.update(any()) } answers {
            Result.Success(firstArg<Project>().update(status = ProjectStatus.COMPLETED))
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validProjectId.toString()),
                "status" to JsonPrimitive("completed")
            )
        )

        val result = tool.execute(params, context)
        assertTrue(result is JsonObject, "Response should be a JsonObject")

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data)
        assertEquals("PROJECT", data!!["entityType"]?.jsonPrimitive?.content, "Should detect as PROJECT")
    }

    // ========== Status Parsing Tests ==========

    @Test
    fun `test task status parsing handles different formats`() = runBlocking {
        coEvery { mockTaskRepository.getById(validTaskId) } returns Result.Success(mockTask)
        coEvery { mockDependencyRepository.findByFromTaskId(validTaskId) } returns emptyList()
        coEvery { mockTaskRepository.update(any()) } answers {
            Result.Success(firstArg<Task>())
        }

        // Test different status string formats
        val statusFormats = listOf(
            "in-progress" to "in-progress",
            "in_progress" to "in-progress",
            "inprogress" to "in-progress",
            "IN-PROGRESS" to "in-progress",
            "cancelled" to "cancelled",
            "canceled" to "cancelled"
        )

        for ((input, expected) in statusFormats) {
            val params = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(validTaskId.toString()),
                    "status" to JsonPrimitive(input)
                )
            )

            val result = tool.execute(params, context)
            val responseObj = result as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success, "Should parse status: $input")
        }
    }

    @Test
    fun `test feature status parsing handles different formats`() = runBlocking {
        coEvery { mockFeatureRepository.getById(validFeatureId) } returns Result.Success(mockFeature)
        coEvery { mockFeatureRepository.update(any()) } answers {
            Result.Success(firstArg<Feature>())
        }

        // Test different status string formats
        val statusFormats = listOf(
            "in-development" to "in-development",
            "in_development" to "in-development",
            "indevelopment" to "in-development",
            "IN-DEVELOPMENT" to "in-development"
        )

        for ((input, expected) in statusFormats) {
            val params = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(validFeatureId.toString()),
                    "status" to JsonPrimitive(input)
                )
            )

            val result = tool.execute(params, context)
            val responseObj = result as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success, "Should parse status: $input")
        }
    }
}
