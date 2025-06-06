package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.test.mock.MockDependencyRepository
import io.github.jpicklyk.mcptask.test.mock.MockRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class DeleteDependencyToolTest {

    private lateinit var tool: BaseToolDefinition
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockDependencyRepository: MockDependencyRepository
    
    private val task1Id = UUID.randomUUID()
    private val task2Id = UUID.randomUUID()
    private val task3Id = UUID.randomUUID()
    private val dependency1Id = UUID.randomUUID()
    private val dependency2Id = UUID.randomUUID()
    private val dependency3Id = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockDependencyRepository = MockDependencyRepository()
        
        val mockRepositoryProvider = MockRepositoryProvider(
            dependencyRepository = mockDependencyRepository
        )

        // Create test dependencies
        val dependency1 = Dependency(
            id = dependency1Id,
            fromTaskId = task1Id,
            toTaskId = task2Id,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val dependency2 = Dependency(
            id = dependency2Id,
            fromTaskId = task2Id,
            toTaskId = task3Id,
            type = DependencyType.IS_BLOCKED_BY,
            createdAt = Instant.now()
        )

        val dependency3 = Dependency(
            id = dependency3Id,
            fromTaskId = task1Id,
            toTaskId = task3Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )

        // Add test dependencies to repository
        mockDependencyRepository.addDependencies(listOf(dependency1, dependency2, dependency3))

        mockContext = ToolExecutionContext(mockRepositoryProvider)
        tool = DeleteDependencyTool()
    }

    // Validation Tests

    @Test
    fun `validate with valid dependency ID should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(dependency1Id.toString())
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate with valid task relationship should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString())
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate with valid deleteAll parameter should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "deleteAll" to JsonPrimitive(true)
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate without any parameters should throw validation exception`() {
        val invalidParams = JsonObject(emptyMap())

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Must specify either 'id'"))
    }

    @Test
    fun `validate with invalid dependency ID UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive("not-a-uuid")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid dependency ID format"))
    }

    @Test
    fun `validate with invalid fromTaskId UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive("not-a-uuid"),
                "toTaskId" to JsonPrimitive(task2Id.toString())
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
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive("not-a-uuid")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid toTaskId format"))
    }

    @Test
    fun `validate with both id and task relationship parameters should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(dependency1Id.toString()),
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Cannot specify both 'id' and task relationship parameters"))
    }

    @Test
    fun `validate with deleteAll and both task IDs should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString()),
                "deleteAll" to JsonPrimitive(true)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("When using 'deleteAll=true', specify only one of"))
    }

    @Test
    fun `validate with deleteAll but no task IDs should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "deleteAll" to JsonPrimitive(true)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        // The validation first checks if any deletion method is specified, so it should throw that error first
        assertTrue(exception.message!!.contains("Must specify either 'id' for specific dependency deletion"))
    }


    @Test
    fun `validate with only fromTaskId and no deleteAll should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("must specify both 'fromTaskId' and 'toTaskId'"))
    }

    @Test
    fun `validate with only toTaskId and no deleteAll should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "toTaskId" to JsonPrimitive(task2Id.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("must specify both 'fromTaskId' and 'toTaskId'"))
    }

    @Test
    fun `validate with invalid dependency type should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString()),
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
                    "fromTaskId" to JsonPrimitive(task1Id.toString()),
                    "toTaskId" to JsonPrimitive(task2Id.toString()),
                    "type" to JsonPrimitive(type)
                )
            )

            assertDoesNotThrow { tool.validateParams(validParams) }
        }
    }

    // Execution Tests - Delete by ID

    @Test
    fun `execute with valid dependency ID should delete successfully`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(dependency1Id.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Dependency deleted successfully") ?: false,
            "Message should contain 'Dependency deleted successfully'"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(1, data!!["deletedCount"]?.jsonPrimitive?.int, "Deleted count should be 1")

        val deletedDependencies = data["deletedDependencies"]?.jsonArray
        assertNotNull(deletedDependencies, "Deleted dependencies array should not be null")
        assertEquals(1, deletedDependencies!!.size, "Should have 1 deleted dependency")

        val deletedDep = deletedDependencies[0].jsonObject
        assertEquals(dependency1Id.toString(), deletedDep["id"]?.jsonPrimitive?.content)
        assertEquals(task1Id.toString(), deletedDep["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(task2Id.toString(), deletedDep["toTaskId"]?.jsonPrimitive?.content)
        assertEquals("BLOCKS", deletedDep["type"]?.jsonPrimitive?.content)

        // Verify dependency was actually deleted from repository
        assertNull(mockDependencyRepository.findById(dependency1Id), "Dependency should be deleted from repository")
    }

    @Test
    fun `execute with non-existent dependency ID should return resource not found error`() = runBlocking {
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
            responseObj["message"]?.jsonPrimitive?.content?.contains("Dependency not found") ?: false,
            "Message should contain 'Dependency not found'"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be RESOURCE_NOT_FOUND"
        )
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains(nonExistentId.toString()) ?: false,
            "Error details should mention the dependency ID"
        )
    }

    // Execution Tests - Delete by Task Relationship

    @Test
    fun `execute with valid task relationship should delete matching dependency`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(1, data!!["deletedCount"]?.jsonPrimitive?.int, "Deleted count should be 1")

        val deletedDependencies = data["deletedDependencies"]?.jsonArray
        assertNotNull(deletedDependencies, "Deleted dependencies array should not be null")
        assertEquals(1, deletedDependencies!!.size, "Should have 1 deleted dependency")

        val deletedDep = deletedDependencies[0].jsonObject
        assertEquals(task1Id.toString(), deletedDep["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(task2Id.toString(), deletedDep["toTaskId"]?.jsonPrimitive?.content)
        assertEquals("BLOCKS", deletedDep["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with task relationship and type filter should delete only matching type`() = runBlocking {
        // Add another dependency with different type between same tasks
        val extraDependency = Dependency(
            id = UUID.randomUUID(),
            fromTaskId = task1Id,
            toTaskId = task2Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )
        mockDependencyRepository.addDependency(extraDependency)

        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(1, data!!["deletedCount"]?.jsonPrimitive?.int, "Deleted count should be 1")

        val deletedDependencies = data["deletedDependencies"]?.jsonArray
        assertNotNull(deletedDependencies, "Deleted dependencies array should not be null")
        assertEquals(1, deletedDependencies!!.size, "Should have 1 deleted dependency")

        val deletedDep = deletedDependencies[0].jsonObject
        assertEquals("BLOCKS", deletedDep["type"]?.jsonPrimitive?.content, "Should delete only BLOCKS type")

        // Verify the RELATES_TO dependency still exists
        assertNotNull(mockDependencyRepository.findById(extraDependency.id), "RELATES_TO dependency should still exist")
    }

    @Test
    fun `execute with non-existent task relationship should return resource not found error`() = runBlocking {
        val nonExistentTaskId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(nonExistentTaskId.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("No matching dependencies found") ?: false,
            "Message should contain 'No matching dependencies found'"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be RESOURCE_NOT_FOUND"
        )
    }

    @Test
    fun `execute with task relationship and non-matching type filter should return resource not found error`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString()),
                "type" to JsonPrimitive("IS_BLOCKED_BY")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("No matching dependencies found") ?: false,
            "Message should contain 'No matching dependencies found'"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be RESOURCE_NOT_FOUND"
        )
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("of type IS_BLOCKED_BY") ?: false,
            "Error details should mention the dependency type"
        )
    }

    // Execution Tests - Delete All Dependencies

    @Test
    fun `execute with deleteAll and fromTaskId should delete all dependencies for task`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "deleteAll" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("dependencies deleted successfully") ?: false,
            "Message should contain 'dependencies deleted successfully'"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(2, data!!["deletedCount"]?.jsonPrimitive?.int, "Deleted count should be 2")

        val deletedDependencies = data["deletedDependencies"]?.jsonArray
        assertNotNull(deletedDependencies, "Deleted dependencies array should not be null")
        assertEquals(2, deletedDependencies!!.size, "Should have 2 deleted dependencies")

        // Verify all dependencies involving task1Id were deleted
        val remainingDependencies = mockDependencyRepository.findByTaskId(task1Id)
        assertEquals(0, remainingDependencies.size, "Should have no remaining dependencies for task1")
    }

    @Test
    fun `execute with deleteAll and toTaskId should delete all dependencies for task`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "toTaskId" to JsonPrimitive(task3Id.toString()),
                "deleteAll" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(2, data!!["deletedCount"]?.jsonPrimitive?.int, "Deleted count should be 2")

        val deletedDependencies = data["deletedDependencies"]?.jsonArray
        assertNotNull(deletedDependencies, "Deleted dependencies array should not be null")
        assertEquals(2, deletedDependencies!!.size, "Should have 2 deleted dependencies")

        // Verify all dependencies involving task3Id were deleted
        val remainingDependencies = mockDependencyRepository.findByTaskId(task3Id)
        assertEquals(0, remainingDependencies.size, "Should have no remaining dependencies for task3")
    }

    @Test
    fun `execute with deleteAll and type filter should delete only matching types`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "deleteAll" to JsonPrimitive(true),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        
        // Should delete all dependencies for task1, but report only BLOCKS type in response
        val deletedDependencies = data!!["deletedDependencies"]?.jsonArray
        assertNotNull(deletedDependencies, "Deleted dependencies array should not be null")
        assertEquals(1, deletedDependencies!!.size, "Should report 1 BLOCKS dependency")

        val deletedDep = deletedDependencies[0].jsonObject
        assertEquals("BLOCKS", deletedDep["type"]?.jsonPrimitive?.content, "Should report only BLOCKS type")
    }

    @Test
    fun `execute with deleteAll for task with no dependencies should return success with zero count`() = runBlocking {
        val taskWithNoDeps = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(taskWithNoDeps.toString()),
                "deleteAll" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(0, data!!["deletedCount"]?.jsonPrimitive?.int, "Deleted count should be 0")

        val deletedDependencies = data["deletedDependencies"]?.jsonArray
        assertNotNull(deletedDependencies, "Deleted dependencies array should not be null")
        assertEquals(0, deletedDependencies!!.size, "Should have 0 deleted dependencies")
    }

    // Error Handling Tests

    @Test
    fun `execute with repository delete failure should handle gracefully`() = runBlocking {
        // Configure mock to fail deletion
        mockDependencyRepository.nextDeleteResult = { false }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(dependency1Id.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true even if delete returns false")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(0, data!!["deletedCount"]?.jsonPrimitive?.int, "Deleted count should be 0")
    }

    @Test
    fun `execute with unexpected exception should return internal error`() = runBlocking {
        // Configure mock to throw exception
        mockDependencyRepository.nextFindByIdResult = { throw RuntimeException("Unexpected error") }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(dependency1Id.toString())
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
    fun `execute with multiple matching dependencies should delete all`() = runBlocking {
        // Add another dependency between same tasks with same type
        val extraDependency = Dependency(
            id = UUID.randomUUID(),
            fromTaskId = task1Id,
            toTaskId = task2Id,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )
        mockDependencyRepository.addDependency(extraDependency)

        val params = JsonObject(
            mapOf(
                "fromTaskId" to JsonPrimitive(task1Id.toString()),
                "toTaskId" to JsonPrimitive(task2Id.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(2, data!!["deletedCount"]?.jsonPrimitive?.int, "Deleted count should be 2")

        val deletedDependencies = data["deletedDependencies"]?.jsonArray
        assertNotNull(deletedDependencies, "Deleted dependencies array should not be null")
        assertEquals(2, deletedDependencies!!.size, "Should have 2 deleted dependencies")
    }
}