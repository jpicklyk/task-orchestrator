package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.domain.validation.ValidationException
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
import java.util.*

class CreateDependencyToolTest {

    private lateinit var tool: BaseToolDefinition
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private val validFromTaskId = UUID.randomUUID()
    private val validToTaskId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockDependencyRepository = mockk<DependencyRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository

        // Create sample tasks for mock responses
        val mockFromTask = Task(
            id = validFromTaskId,
            title = "From Task",
            summary = "Source task description",
            status = TaskStatus.PENDING
        )

        val mockToTask = Task(
            id = validToTaskId,
            title = "To Task", 
            summary = "Target task description",
            status = TaskStatus.PENDING
        )

        // Mock successful task retrieval for valid IDs
        coEvery { mockTaskRepository.getById(validFromTaskId) } returns Result.Success(mockFromTask)
        coEvery { mockTaskRepository.getById(validToTaskId) } returns Result.Success(mockToTask)

        // Mock task not found for other IDs (any ID that's not the valid ones)
        coEvery { mockTaskRepository.getById(match { it != validFromTaskId && it != validToTaskId }) } returns
                Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TASK, "Task not found"))

        // Mock successful dependency creation by default
        every { mockDependencyRepository.create(any()) } answers { 
            val dependency = firstArg<Dependency>()
            dependency
        }

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        tool = CreateDependencyTool()
    }

    // Validation Tests

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate without fromTaskId should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `validate without toTaskId should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `validate with invalid fromTaskId UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive("not-a-uuid"),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid fromTaskId format"))
    }

    @Test
    fun `validate with invalid toTaskId UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive("not-a-uuid")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid toTaskId format"))
    }

    @Test
    fun `validate with same fromTaskId and toTaskId should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validFromTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("A task cannot depend on itself"))
    }

    @Test
    fun `validate with invalid dependency type should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "type" to JsonPrimitive("INVALID_TYPE")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid dependency type"))
    }

    @Test
    fun `validate with valid dependency types should not throw exceptions`() {
        val validTypes = listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO")

        validTypes.forEach { type ->
            val validParams = JsonObject(
                mapOf(
                    "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                    "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                    "type" to JsonPrimitive(type)
                )
            )

            assertDoesNotThrow { tool.validateParams(validParams) }
        }
    }

    // Execution Tests

    @Test
    fun `execute with valid parameters should return success response`() = runBlocking {
        val validParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val response = tool.execute(validParams, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Dependency created") ?: false,
            "Message should contain 'Dependency created'"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertNotNull(data!!["id"], "Dependency ID should not be null")
        assertEquals(validFromTaskId.toString(), data["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(validToTaskId.toString(), data["toTaskId"]?.jsonPrimitive?.content)
        assertEquals("BLOCKS", data["type"]?.jsonPrimitive?.content)
        assertNotNull(data["createdAt"], "CreatedAt should not be null")

        assertTrue(responseObj["error"] is JsonNull, "Error should be null")
        assertNotNull(responseObj["metadata"], "Metadata should not be null")
    }

    @Test
    fun `execute with default type should use BLOCKS`() = runBlocking {
        val paramsWithoutType = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val response = tool.execute(paramsWithoutType, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals("BLOCKS", data!!["type"]?.jsonPrimitive?.content, "Default type should be BLOCKS")
    }

    @Test
    fun `execute with non-existent fromTask should return resource not found error`() = runBlocking {
        val nonExistentFromTaskId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(nonExistentFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Source task not found") ?: false,
            "Message should contain 'Source task not found'"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be RESOURCE_NOT_FOUND"
        )
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("No task exists with fromTaskId") ?: false,
            "Error details should mention fromTaskId"
        )
    }

    @Test
    fun `execute with non-existent toTask should return resource not found error`() = runBlocking {
        val nonExistentToTaskId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(nonExistentToTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Target task not found") ?: false,
            "Message should contain 'Target task not found'"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be RESOURCE_NOT_FOUND"
        )
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("No task exists with toTaskId") ?: false,
            "Error details should mention toTaskId"
        )
    }

    @Test
    fun `execute with database error on fromTask retrieval should return database error`() = runBlocking {
        // Mock database error when retrieving from task
        coEvery { mockTaskRepository.getById(validFromTaskId) } returns
                Result.Error(RepositoryError.DatabaseError("Database connection failed"))

        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Error retrieving source task") ?: false,
            "Message should indicate source task retrieval error"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.DATABASE_ERROR, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be DATABASE_ERROR"
        )
    }

    @Test
    fun `execute with database error on toTask retrieval should return database error`() = runBlocking {
        // Mock database error when retrieving to task
        coEvery { mockTaskRepository.getById(validToTaskId) } returns
                Result.Error(RepositoryError.DatabaseError("Database connection failed"))

        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Error retrieving target task") ?: false,
            "Message should indicate target task retrieval error"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.DATABASE_ERROR, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be DATABASE_ERROR"
        )
    }

    @Test
    fun `execute with validation exception during creation should return validation error`() = runBlocking {
        // Mock validation exception during dependency creation
        every { mockDependencyRepository.create(any()) } throws
                ValidationException("Dependency would create a cycle")

        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Validation failed") ?: false,
            "Message should indicate validation failure"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be VALIDATION_ERROR"
        )
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("cycle") ?: false,
            "Error details should mention cycle"
        )
    }

    @Test
    fun `execute with unexpected exception should return internal error`() = runBlocking {
        // Mock unexpected exception
        coEvery { mockTaskRepository.getById(validFromTaskId) } throws
                RuntimeException("Unexpected error")

        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Internal error") ?: false,
            "Message should indicate internal error"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.INTERNAL_ERROR, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be INTERNAL_ERROR"
        )
    }

    @Test
    fun `execute with different dependency types should work correctly`() = runBlocking {
        val dependencyTypes = listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO")

        dependencyTypes.forEach { type ->
            val params = JsonObject(
                mapOf(
                    "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                    "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                    "type" to JsonPrimitive(type)
                )
            )

            val response = tool.execute(params, mockContext)
            assertTrue(response is JsonObject, "Response should be a JsonObject for type $type")

            val responseObj = response as JsonObject
            assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true for type $type")

            val data = responseObj["data"]?.jsonObject
            assertNotNull(data, "Data object should not be null for type $type")
            assertEquals(type, data!!["type"]?.jsonPrimitive?.content, "Type should match for $type")
        }
    }
}