package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
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
import java.util.*

class ManageDependencyToolTest {

    private lateinit var tool: ManageDependencyTool
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private val validFromTaskId = UUID.randomUUID()
    private val validToTaskId = UUID.randomUUID()
    private val validDependencyId = UUID.randomUUID()

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

        // Mock task not found for other IDs
        coEvery { mockTaskRepository.getById(match { it != validFromTaskId && it != validToTaskId }) } returns
                Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TASK, "Task not found"))

        // Mock successful dependency creation by default
        every { mockDependencyRepository.create(any()) } answers {
            val dependency = firstArg<Dependency>()
            dependency
        }

        // Mock no existing dependencies by default (for duplicate check)
        every { mockDependencyRepository.findByFromTaskId(any()) } returns emptyList()

        // Mock no cycle detection by default
        every { mockDependencyRepository.hasCyclicDependency(any(), any()) } returns false

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        tool = ManageDependencyTool()
    }

    // ========== OPERATION VALIDATION TESTS ==========

    @Test
    fun `validate with missing operation should throw exception`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("operation"))
    }

    @Test
    fun `validate with invalid operation should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("invalid_operation")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid operation"))
    }

    // ========== CREATE OPERATION VALIDATION TESTS ==========

    @Test
    fun `validate create with valid parameters should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate create without fromTaskId should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }
        assertTrue(exception.message!!.contains("fromTaskId"))
    }

    @Test
    fun `validate create without toTaskId should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }
        assertTrue(exception.message!!.contains("toTaskId"))
    }

    @Test
    fun `validate create with invalid fromTaskId UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
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
    fun `validate create with invalid toTaskId UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
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
    fun `validate create with same fromTaskId and toTaskId should throw validation exception`() {
        val sameTaskId = UUID.randomUUID()
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(sameTaskId.toString()),
                "toTaskId" to JsonPrimitive(sameTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }
        assertTrue(exception.message!!.contains("cannot depend on itself"))
    }

    @Test
    fun `validate create with invalid dependency type should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
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

    // ========== CREATE OPERATION EXECUTION TESTS ==========

    @Test
    fun `execute create with valid parameters should succeed`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Dependency created successfully", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(validFromTaskId.toString(), data!!["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(validToTaskId.toString(), data["toTaskId"]?.jsonPrimitive?.content)
        assertEquals("BLOCKS", data["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with default type should use BLOCKS`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val data = resultObj["data"]?.jsonObject
        assertEquals("BLOCKS", data!!["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with non-existent fromTask should return error`() = runBlocking {
        val nonExistentTaskId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(nonExistentTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Source task not found"))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with non-existent toTask should return error`() = runBlocking {
        val nonExistentTaskId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(nonExistentTaskId.toString())
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Target task not found"))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with duplicate dependency should return error`() = runBlocking {
        // Mock existing dependency
        val existingDependency = Dependency(
            id = validDependencyId,
            fromTaskId = validFromTaskId,
            toTaskId = validToTaskId,
            type = DependencyType.BLOCKS
        )
        every { mockDependencyRepository.findByFromTaskId(validFromTaskId) } returns listOf(existingDependency)

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Duplicate dependency"))
        assertEquals(ErrorCodes.VALIDATION_ERROR, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with circular dependency should return error`() = runBlocking {
        // Mock cycle detection
        every { mockDependencyRepository.hasCyclicDependency(validFromTaskId, validToTaskId) } returns true

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Circular dependency detected"))
        assertEquals(ErrorCodes.VALIDATION_ERROR, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    // ========== DELETE OPERATION VALIDATION TESTS ==========

    @Test
    fun `validate delete with dependency ID should not throw exception`() {
        val validParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(validDependencyId.toString())
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate delete with task relationship should not throw exception`() {
        val validParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate delete with deleteAll and one task ID should not throw exception`() {
        val validParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "deleteAll" to JsonPrimitive(true)
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate delete with no parameters should throw exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }
        assertTrue(exception.message!!.contains("Must specify either 'id'"))
    }

    @Test
    fun `validate delete with both ID and task relationship should throw exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(validDependencyId.toString()),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }
        assertTrue(exception.message!!.contains("Cannot specify both 'id' and task relationship"))
    }

    @Test
    fun `validate delete with deleteAll and both task IDs should throw exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "deleteAll" to JsonPrimitive(true)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }
        assertTrue(exception.message!!.contains("specify only one of 'fromTaskId' or 'toTaskId'"))
    }

    @Test
    fun `validate delete with only one task ID without deleteAll should throw exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }
        assertTrue(exception.message!!.contains("must specify both 'fromTaskId' and 'toTaskId'"))
    }

    // ========== DELETE OPERATION EXECUTION TESTS ==========

    @Test
    fun `execute delete by ID should succeed`() = runBlocking {
        val dependency = Dependency(
            id = validDependencyId,
            fromTaskId = validFromTaskId,
            toTaskId = validToTaskId,
            type = DependencyType.BLOCKS
        )

        every { mockDependencyRepository.findById(validDependencyId) } returns dependency
        every { mockDependencyRepository.delete(validDependencyId) } returns true

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(validDependencyId.toString())
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Dependency deleted successfully", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        assertEquals(1, data!!["deletedCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute delete by ID with non-existent dependency should return error`() = runBlocking {
        every { mockDependencyRepository.findById(validDependencyId) } returns null

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(validDependencyId.toString())
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Dependency not found"))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `execute delete by task relationship should succeed`() = runBlocking {
        val dependency = Dependency(
            id = validDependencyId,
            fromTaskId = validFromTaskId,
            toTaskId = validToTaskId,
            type = DependencyType.BLOCKS
        )

        every { mockDependencyRepository.findByTaskId(validFromTaskId) } returns listOf(dependency)
        every { mockDependencyRepository.delete(validDependencyId) } returns true

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Dependency deleted successfully", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        assertEquals(1, data!!["deletedCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute delete by task relationship with no matches should return error`() = runBlocking {
        every { mockDependencyRepository.findByTaskId(validFromTaskId) } returns emptyList()

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("No matching dependencies found"))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `execute delete with deleteAll should succeed`() = runBlocking {
        val dependency1 = Dependency(
            id = UUID.randomUUID(),
            fromTaskId = validFromTaskId,
            toTaskId = validToTaskId,
            type = DependencyType.BLOCKS
        )
        val dependency2 = Dependency(
            id = UUID.randomUUID(),
            fromTaskId = validFromTaskId,
            toTaskId = UUID.randomUUID(),
            type = DependencyType.RELATES_TO
        )

        every { mockDependencyRepository.findByTaskId(validFromTaskId) } returns listOf(dependency1, dependency2)
        every { mockDependencyRepository.deleteByTaskId(validFromTaskId) } returns 2

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "deleteAll" to JsonPrimitive(true)
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("2 dependencies deleted successfully", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        assertEquals(2, data!!["deletedCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute delete by task relationship with type filter should succeed`() = runBlocking {
        val blocksDependency = Dependency(
            id = validDependencyId,
            fromTaskId = validFromTaskId,
            toTaskId = validToTaskId,
            type = DependencyType.BLOCKS
        )
        val relatesDependency = Dependency(
            id = UUID.randomUUID(),
            fromTaskId = validFromTaskId,
            toTaskId = validToTaskId,
            type = DependencyType.RELATES_TO
        )

        every { mockDependencyRepository.findByTaskId(validFromTaskId) } returns listOf(blocksDependency, relatesDependency)
        every { mockDependencyRepository.delete(validDependencyId) } returns true

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Dependency deleted successfully", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        assertEquals(1, data!!["deletedCount"]?.jsonPrimitive?.int)

        // Verify only BLOCKS dependency was deleted
        val deletedDeps = data["deletedDependencies"]?.jsonArray
        assertEquals(1, deletedDeps?.size)
        assertEquals("BLOCKS", deletedDeps?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }
}
