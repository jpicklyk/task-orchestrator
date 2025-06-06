package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class DeleteTaskToolTest {

    private lateinit var tool: BaseToolDefinition
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private val validTaskId = UUID.randomUUID()
    private val relatedTask1Id = UUID.randomUUID()
    private val relatedTask2Id = UUID.randomUUID()
    private val relatedTask3Id = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create a mock repository provider and repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockSectionRepository = mockk<SectionRepository>()
        mockDependencyRepository = mockk<DependencyRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository

        // Create a sample task for the mock response
        val mockTask = Task(
            id = validTaskId,
            title = "Test Task",
            summary = "Test Description",
            status = TaskStatus.PENDING
        )

        // Mock the getById method to return a mock task for the valid ID
        coEvery { mockTaskRepository.getById(validTaskId) } returns Result.Success(mockTask)

        // Mock the getById method to return a not found error for any other ID
        coEvery { mockTaskRepository.getById(neq(validTaskId)) } returns
                Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TASK, "Task not found"))

        // Mock the delete method to return true (successful deletion) for the valid ID
        coEvery { mockTaskRepository.delete(validTaskId) } returns Result.Success(true)

        // Mock section repository to return empty sections
        coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
        coEvery { mockSectionRepository.deleteSection(any()) } returns Result.Success(true)

        // Mock dependency repository to return no dependencies by default
        every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
        every { mockDependencyRepository.deleteByTaskId(any()) } returns 0

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        tool = DeleteTaskTool()
    }

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "hardDelete" to JsonPrimitive("true"),
                "cascade" to JsonPrimitive("true"),
                "force" to JsonPrimitive("true")
            )
        )

        // Should not throw any exceptions
        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate with invalid UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive("not-a-uuid")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid task ID format"))
    }

    @Test
    fun `validate without required ID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "hardDelete" to JsonPrimitive("true")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `execute with valid parameters should return success response`() = runBlocking {
        val validParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString())
            )
        )

        val response = tool.execute(validParams, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Task deleted") ?: false,
            "Message should contain 'Task deleted'"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(validTaskId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertTrue(data["deleted"]?.jsonPrimitive?.boolean == true, "Deleted flag should be true")
        assertEquals(0, data["sectionsDeleted"]?.jsonPrimitive?.int, "Should show 0 sections were deleted")
        assertEquals(0, data["dependenciesDeleted"]?.jsonPrimitive?.int, "Should show 0 dependencies were deleted")

        assertTrue(responseObj["error"] is JsonNull, "Error should be null")
        assertNotNull(responseObj["metadata"], "Metadata should not be null")
    }

    @Test
    fun `execute with non-existent task ID should return resource not found error`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(nonExistentId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Task not found") ?: false,
            "Message should contain 'Task not found'"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be RESOURCE_NOT_FOUND"
        )
    }

    @Test
    fun `execute with task that has sections should delete sections too`() = runBlocking {
        // Create mock sections
        val section1 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = validTaskId,
            title = "Test Section 1",
            usageDescription = "Test usage",
            content = "Test content",
            ordinal = 0
        )

        val section2 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = validTaskId,
            title = "Test Section 2",
            usageDescription = "Test usage 2",
            content = "Test content 2",
            ordinal = 1
        )

        // Mock section repository to return the sections
        coEvery {
            mockSectionRepository.getSectionsForEntity(EntityType.TASK, validTaskId)
        } returns Result.Success(listOf(section1, section2))

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "deleteSections" to JsonPrimitive("true")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(
            2, data!!["sectionsDeleted"]?.jsonPrimitive?.int,
            "Should show 2 sections were deleted"
        )
        assertEquals(
            0, data["dependenciesDeleted"]?.jsonPrimitive?.int,
            "Should show 0 dependencies were deleted"
        )
    }

    @Test
    fun `execute with database error should return database error response`() = runBlocking {
        // Mock a database error on deletion
        coEvery {
            mockTaskRepository.delete(validTaskId)
        } returns Result.Error(RepositoryError.DatabaseError("Database connection failed"))

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Failed to delete task") ?: false,
            "Message should indicate deletion failure"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.DATABASE_ERROR, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be DATABASE_ERROR"
        )
    }

    @Test
    fun `execute with unexpected exception should return internal error response`() = runBlocking {
        // Mock an unexpected exception
        coEvery {
            mockTaskRepository.getById(validTaskId)
        } throws RuntimeException("Unexpected error")

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Failed to delete task") ?: false,
            "Message should indicate deletion failure"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.INTERNAL_ERROR, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be INTERNAL_ERROR"
        )
    }

    @Test
    fun `execute with error retrieving sections should still delete task`() = runBlocking {
        // Mock error when retrieving sections
        coEvery {
            mockSectionRepository.getSectionsForEntity(any(), any())
        } returns Result.Error(RepositoryError.DatabaseError("Error retrieving sections"))

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(
            true, responseObj["success"]?.jsonPrimitive?.boolean,
            "Success should be true even with section retrieval error"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(
            0, data!!["sectionsDeleted"]?.jsonPrimitive?.int,
            "Should show 0 sections were deleted"
        )
    }

    // ========================================
    // Dependency Feature Tests
    // ========================================

    @Test
    fun `execute with task that has dependencies should fail without force parameter`() = runBlocking {
        // Create test dependencies
        val incomingDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = validTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val outgoingDep = Dependency(
            fromTaskId = validTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )

        // Mock dependency repository to return dependencies
        every { mockDependencyRepository.findByTaskId(validTaskId) } returns listOf(incomingDep, outgoingDep)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "force" to JsonPrimitive(false)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Cannot delete task with existing dependencies") ?: false,
            "Message should indicate dependency conflict"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be VALIDATION_ERROR"
        )

        // Verify dependency information is included in error
        val additionalData = error["additionalData"]?.jsonObject
        assertNotNull(additionalData, "Additional data should contain dependency information")
        assertEquals(2, additionalData!!["totalDependencies"]?.jsonPrimitive?.int, "Should show 2 total dependencies")
        assertEquals(1, additionalData["incomingDependencies"]?.jsonPrimitive?.int, "Should show 1 incoming dependency")
        assertEquals(1, additionalData["outgoingDependencies"]?.jsonPrimitive?.int, "Should show 1 outgoing dependency")
        assertEquals(2, additionalData["affectedTasks"]?.jsonPrimitive?.int, "Should show 2 affected tasks")
    }

    @Test
    fun `execute with force parameter should delete task and all dependencies`() = runBlocking {
        // Create test dependencies
        val incomingDep1 = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = validTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val incomingDep2 = Dependency(
            fromTaskId = relatedTask2Id,
            toTaskId = validTaskId,
            type = DependencyType.IS_BLOCKED_BY,
            createdAt = Instant.now()
        )

        val outgoingDep = Dependency(
            fromTaskId = validTaskId,
            toTaskId = relatedTask3Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )

        val allDependencies = listOf(incomingDep1, incomingDep2, outgoingDep)

        // Mock dependency repository
        every { mockDependencyRepository.findByTaskId(validTaskId) } returns allDependencies
        every { mockDependencyRepository.deleteByTaskId(validTaskId) } returns 3

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "force" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be true")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Task deleted successfully with 3 dependencies") ?: false,
            "Message should mention deleted dependencies"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(validTaskId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertTrue(data["deleted"]?.jsonPrimitive?.boolean == true, "Deleted flag should be true")
        assertEquals(3, data["dependenciesDeleted"]?.jsonPrimitive?.int, "Should show 3 dependencies were deleted")

        // Verify broken dependency warnings are included
        assertTrue(data["warningsBrokenDependencies"]?.jsonPrimitive?.boolean == true, "Should warn about broken dependencies")
        val brokenChains = data["brokenDependencyChains"]?.jsonObject
        assertNotNull(brokenChains, "Broken dependency chains info should be present")
        assertEquals(2, brokenChains!!["incomingDependencies"]?.jsonPrimitive?.int, "Should show 2 incoming dependencies")
        assertEquals(1, brokenChains["outgoingDependencies"]?.jsonPrimitive?.int, "Should show 1 outgoing dependency")
        assertEquals(3, brokenChains["affectedTasks"]?.jsonPrimitive?.int, "Should show 3 affected tasks")
    }

    @Test
    fun `execute with no dependencies should not include broken dependency warnings`() = runBlocking {
        // Mock dependency repository to return no dependencies
        every { mockDependencyRepository.findByTaskId(validTaskId) } returns emptyList()
        every { mockDependencyRepository.deleteByTaskId(validTaskId) } returns 0

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(validTaskId.toString()),
                "force" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(0, data!!["dependenciesDeleted"]?.jsonPrimitive?.int, "Should show 0 dependencies were deleted")

        // Should not include broken dependency warnings when there are no dependencies
        assertFalse(data.containsKey("warningsBrokenDependencies"), "Should not have broken dependency warnings")
        assertFalse(data.containsKey("brokenDependencyChains"), "Should not have broken dependency chains info")
    }
}