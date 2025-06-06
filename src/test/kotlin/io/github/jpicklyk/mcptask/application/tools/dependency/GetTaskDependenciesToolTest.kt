package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.test.mock.MockDependencyRepository
import io.github.jpicklyk.mcptask.test.mock.MockRepositoryProvider
import io.github.jpicklyk.mcptask.test.mock.MockTaskRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class GetTaskDependenciesToolTest {

    private lateinit var tool: BaseToolDefinition
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockTaskRepository: MockTaskRepository
    private lateinit var mockDependencyRepository: MockDependencyRepository
    
    private val testTaskId = UUID.randomUUID()
    private val relatedTask1Id = UUID.randomUUID()
    private val relatedTask2Id = UUID.randomUUID()
    private val relatedTask3Id = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockTaskRepository = MockTaskRepository()
        mockDependencyRepository = MockDependencyRepository()
        
        val mockRepositoryProvider = MockRepositoryProvider(
            taskRepository = mockTaskRepository,
            dependencyRepository = mockDependencyRepository
        )

        // Create test tasks
        val testTask = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Main task for testing",
            status = TaskStatus.PENDING
        )

        val relatedTask1 = Task(
            id = relatedTask1Id,
            title = "Related Task 1",
            summary = "First related task",
            status = TaskStatus.IN_PROGRESS
        )

        val relatedTask2 = Task(
            id = relatedTask2Id,
            title = "Related Task 2", 
            summary = "Second related task",
            status = TaskStatus.COMPLETED
        )

        val relatedTask3 = Task(
            id = relatedTask3Id,
            title = "Related Task 3",
            summary = "Third related task",
            status = TaskStatus.PENDING
        )

        // Add tasks to mock repository
        mockTaskRepository.addTask(testTask)
        mockTaskRepository.addTask(relatedTask1)
        mockTaskRepository.addTask(relatedTask2)
        mockTaskRepository.addTask(relatedTask3)

        mockContext = ToolExecutionContext(mockRepositoryProvider)
        tool = GetTaskDependenciesTool()
    }

    // Validation Tests

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("all"),
                "type" to JsonPrimitive("BLOCKS"),
                "includeTaskInfo" to JsonPrimitive(true)
            )
        )

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate without taskId should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "direction" to JsonPrimitive("all")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `validate with invalid taskId UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive("not-a-uuid")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid taskId format"))
    }

    @Test
    fun `validate with invalid direction should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("invalid_direction")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid direction filter"))
    }

    @Test
    fun `validate with invalid dependency type should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "type" to JsonPrimitive("INVALID_TYPE")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid dependency type"))
    }

    @Test
    fun `validate with valid direction options should not throw exceptions`() {
        val validDirections = listOf("incoming", "outgoing", "all")

        validDirections.forEach { direction ->
            val validParams = JsonObject(
                mapOf(
                    "taskId" to JsonPrimitive(testTaskId.toString()),
                    "direction" to JsonPrimitive(direction)
                )
            )

            assertDoesNotThrow { tool.validateParams(validParams) }
        }
    }

    @Test
    fun `validate with valid dependency types should not throw exceptions`() {
        val validTypes = listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO", "all")

        validTypes.forEach { type ->
            val validParams = JsonObject(
                mapOf(
                    "taskId" to JsonPrimitive(testTaskId.toString()),
                    "type" to JsonPrimitive(type)
                )
            )

            assertDoesNotThrow { tool.validateParams(validParams) }
        }
    }

    // Execution Tests

    @Test
    fun `execute with valid parameters and no dependencies should return empty result`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Dependencies retrieved") ?: false,
            "Message should contain 'Dependencies retrieved'"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(testTaskId.toString(), data!!["taskId"]?.jsonPrimitive?.content)

        val dependencies = data["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        val incoming = dependencies!!["incoming"]?.jsonArray
        val outgoing = dependencies["outgoing"]?.jsonArray
        assertNotNull(incoming, "Incoming array should not be null")
        assertNotNull(outgoing, "Outgoing array should not be null")
        assertEquals(0, incoming!!.size, "Should have no incoming dependencies")
        assertEquals(0, outgoing!!.size, "Should have no outgoing dependencies")

        val counts = data["counts"]?.jsonObject
        assertNotNull(counts, "Counts object should not be null")
        assertEquals(0, counts!!["total"]?.jsonPrimitive?.int, "Total count should be 0")
        assertEquals(0, counts["incoming"]?.jsonPrimitive?.int, "Incoming count should be 0")
        assertEquals(0, counts["outgoing"]?.jsonPrimitive?.int, "Outgoing count should be 0")
    }

    @Test
    fun `execute with non-existent task should return resource not found error`() = runBlocking {
        val nonExistentTaskId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(nonExistentTaskId.toString())
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
    fun `execute with mixed incoming and outgoing dependencies should return organized results`() = runBlocking {
        // Create test dependencies
        val incomingDep1 = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val incomingDep2 = Dependency(
            fromTaskId = relatedTask2Id,
            toTaskId = testTaskId,
            type = DependencyType.IS_BLOCKED_BY,
            createdAt = Instant.now()
        )

        val outgoingDep1 = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask3Id,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        mockDependencyRepository.addDependencies(listOf(incomingDep1, incomingDep2, outgoingDep1))

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        val incoming = dependencies!!["incoming"]?.jsonArray
        val outgoing = dependencies["outgoing"]?.jsonArray
        assertNotNull(incoming, "Incoming array should not be null")
        assertNotNull(outgoing, "Outgoing array should not be null")
        assertEquals(2, incoming!!.size, "Should have 2 incoming dependencies")
        assertEquals(1, outgoing!!.size, "Should have 1 outgoing dependency")

        val counts = data["counts"]?.jsonObject
        assertNotNull(counts, "Counts object should not be null")
        assertEquals(3, counts!!["total"]?.jsonPrimitive?.int, "Total count should be 3")
        assertEquals(2, counts["incoming"]?.jsonPrimitive?.int, "Incoming count should be 2")
        assertEquals(1, counts["outgoing"]?.jsonPrimitive?.int, "Outgoing count should be 1")

        val byType = counts["byType"]?.jsonObject
        assertNotNull(byType, "ByType object should not be null")
        assertEquals(2, byType!!["BLOCKS"]?.jsonPrimitive?.int, "Should have 2 BLOCKS dependencies")
        assertEquals(1, byType["IS_BLOCKED_BY"]?.jsonPrimitive?.int, "Should have 1 IS_BLOCKED_BY dependency")
        assertEquals(0, byType["RELATES_TO"]?.jsonPrimitive?.int, "Should have 0 RELATES_TO dependencies")
    }

    @Test
    fun `execute with direction filter incoming should return only incoming dependencies`() = runBlocking {
        // Create test dependencies
        val incomingDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val outgoingDep = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        mockDependencyRepository.addDependencies(listOf(incomingDep, outgoingDep))

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("incoming")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonArray
        assertNotNull(dependencies, "Dependencies array should not be null")
        assertEquals(1, dependencies!!.size, "Should have 1 filtered dependency")

        val dependency = dependencies[0].jsonObject
        assertEquals(relatedTask1Id.toString(), dependency["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(testTaskId.toString(), dependency["toTaskId"]?.jsonPrimitive?.content)

        val filters = data["filters"]?.jsonObject
        assertNotNull(filters, "Filters object should not be null")
        assertEquals("incoming", filters!!["direction"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with direction filter outgoing should return only outgoing dependencies`() = runBlocking {
        // Create test dependencies
        val incomingDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val outgoingDep = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        mockDependencyRepository.addDependencies(listOf(incomingDep, outgoingDep))

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("outgoing")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonArray
        assertNotNull(dependencies, "Dependencies array should not be null")
        assertEquals(1, dependencies!!.size, "Should have 1 filtered dependency")

        val dependency = dependencies[0].jsonObject
        assertEquals(testTaskId.toString(), dependency["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(relatedTask2Id.toString(), dependency["toTaskId"]?.jsonPrimitive?.content)

        val filters = data["filters"]?.jsonObject
        assertNotNull(filters, "Filters object should not be null")
        assertEquals("outgoing", filters!!["direction"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with type filter should return only matching dependency types`() = runBlocking {
        // Create test dependencies with different types
        val blocksDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val relatedDep = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )

        mockDependencyRepository.addDependencies(listOf(blocksDep, relatedDep))

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        // After the fix: type filters are applied consistently to incoming/outgoing arrays
        val incoming = dependencies!!["incoming"]?.jsonArray
        val outgoing = dependencies["outgoing"]?.jsonArray
        assertNotNull(incoming, "Incoming array should not be null")
        assertNotNull(outgoing, "Outgoing array should not be null")
        assertEquals(1, incoming!!.size, "Should have 1 incoming BLOCKS dependency")
        assertEquals(0, outgoing!!.size, "Should have 0 outgoing BLOCKS dependencies")

        // Verify the type filter is recorded in filters
        val filters = data["filters"]?.jsonObject
        assertNotNull(filters, "Filters object should not be null")
        assertEquals("BLOCKS", filters!!["type"]?.jsonPrimitive?.content)

        // The incoming dependency should be BLOCKS type
        val incomingDep = incoming[0].jsonObject
        assertEquals("BLOCKS", incomingDep["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with includeTaskInfo should include related task information`() = runBlocking {
        // Create test dependency
        val testDependency = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        mockDependencyRepository.addDependency(testDependency)

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "includeTaskInfo" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonObject
        val incoming = dependencies!!["incoming"]?.jsonArray
        assertNotNull(incoming, "Incoming array should not be null")
        assertEquals(1, incoming!!.size, "Should have 1 incoming dependency")

        // After the fix: includeTaskInfo is applied consistently to incoming/outgoing arrays
        val incomingDep = incoming[0].jsonObject
        val relatedTask = incomingDep["relatedTask"]?.jsonObject
        assertNotNull(relatedTask, "Related task info should be included in incoming/outgoing arrays")
        assertEquals(relatedTask1Id.toString(), relatedTask!!["id"]?.jsonPrimitive?.content)
        assertEquals("Related Task 1", relatedTask["title"]?.jsonPrimitive?.content)
        assertEquals("IN_PROGRESS", relatedTask["status"]?.jsonPrimitive?.content)

        val filters = data["filters"]?.jsonObject
        assertNotNull(filters, "Filters object should not be null")
        assertTrue(filters!!["includeTaskInfo"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `execute with includeTaskInfo and direction filter should include related task information`() = runBlocking {
        // Create test dependency
        val testDependency = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        mockDependencyRepository.addDependency(testDependency)

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("incoming"),
                "includeTaskInfo" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonArray
        assertNotNull(dependencies, "Dependencies array should not be null")
        assertEquals(1, dependencies!!.size, "Should have 1 incoming dependency")

        // When direction is not "all", the dependencyObjects are used which includes task info
        val dependencyObj = dependencies[0].jsonObject
        val relatedTask = dependencyObj["relatedTask"]?.jsonObject
        assertNotNull(relatedTask, "Related task info should be included")
        assertEquals(relatedTask1Id.toString(), relatedTask!!["id"]?.jsonPrimitive?.content)
        assertEquals("Related Task 1", relatedTask["title"]?.jsonPrimitive?.content)
        assertEquals("IN_PROGRESS", relatedTask["status"]?.jsonPrimitive?.content)

        val filters = data["filters"]?.jsonObject
        assertNotNull(filters, "Filters object should not be null")
        assertTrue(filters!!["includeTaskInfo"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `execute with combined filters should apply both direction and type filters`() = runBlocking {
        // Create test dependencies
        val incomingBlocks = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val incomingRelates = Dependency(
            fromTaskId = relatedTask2Id,
            toTaskId = testTaskId,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )

        val outgoingBlocks = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask3Id,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        mockDependencyRepository.addDependencies(listOf(incomingBlocks, incomingRelates, outgoingBlocks))

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("incoming"),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonArray
        assertNotNull(dependencies, "Dependencies array should not be null")
        assertEquals(1, dependencies!!.size, "Should have 1 dependency matching both filters")

        val dependency = dependencies[0].jsonObject
        assertEquals(relatedTask1Id.toString(), dependency["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(testTaskId.toString(), dependency["toTaskId"]?.jsonPrimitive?.content)
        assertEquals("BLOCKS", dependency["type"]?.jsonPrimitive?.content)

        val filters = data["filters"]?.jsonObject
        assertNotNull(filters, "Filters object should not be null")
        assertEquals("incoming", filters!!["direction"]?.jsonPrimitive?.content)
        assertEquals("BLOCKS", filters["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with database error on task retrieval should return database error`() = runBlocking {
        val mockTaskRepository = mockk<io.github.jpicklyk.mcptask.domain.repository.TaskRepository>()
        val mockRepositoryProvider = mockk<io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider>()
        
        coEvery { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        coEvery { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        
        coEvery { mockTaskRepository.getById(testTaskId) } returns
                Result.Error(RepositoryError.DatabaseError("Database connection failed"))

        val mockContext = ToolExecutionContext(mockRepositoryProvider)

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Success should be false")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Error retrieving task") ?: false,
            "Message should indicate task retrieval error"
        )

        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals(
            ErrorCodes.DATABASE_ERROR, error!!["code"]?.jsonPrimitive?.content,
            "Error code should be DATABASE_ERROR"
        )
    }

    @Test
    fun `execute with unexpected exception should return internal error`() = runBlocking {
        val mockTaskRepository = mockk<io.github.jpicklyk.mcptask.domain.repository.TaskRepository>()
        val mockRepositoryProvider = mockk<io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider>()
        
        coEvery { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        coEvery { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        
        coEvery { mockTaskRepository.getById(testTaskId) } throws RuntimeException("Unexpected error")

        val mockContext = ToolExecutionContext(mockRepositoryProvider)

        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString())
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
    fun `execute with default parameters should use all direction and all type filters`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val filters = data!!["filters"]?.jsonObject
        assertNotNull(filters, "Filters object should not be null")
        assertEquals("all", filters!!["direction"]?.jsonPrimitive?.content, "Default direction should be all")
        assertEquals("all", filters["type"]?.jsonPrimitive?.content, "Default type should be all")
        assertEquals(false, filters["includeTaskInfo"]?.jsonPrimitive?.boolean, "Default includeTaskInfo should be false")
    }

    @Test
    fun `type filter should apply consistently to all response structures`() = runBlocking {
        // Create mixed dependency types
        val blocksDep1 = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )
        
        val blocksDep2 = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )
        
        val relatedDep = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask3Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )
        
        mockDependencyRepository.addDependencies(listOf(blocksDep1, blocksDep2, relatedDep))

        // Test direction="all" with type filter
        val allParams = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("all"),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val allResponse = tool.execute(allParams, mockContext)
        val allData = (allResponse as JsonObject)["data"]?.jsonObject!!
        val allDeps = allData["dependencies"]?.jsonObject!!
        val allIncoming = allDeps["incoming"]?.jsonArray!!
        val allOutgoing = allDeps["outgoing"]?.jsonArray!!

        // Test direction="incoming" with type filter
        val incomingParams = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("incoming"),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val incomingResponse = tool.execute(incomingParams, mockContext)
        val incomingData = (incomingResponse as JsonObject)["data"]?.jsonObject!!
        val incomingDeps = incomingData["dependencies"]?.jsonArray!!

        // Both should return same incoming BLOCKS dependencies
        assertEquals(allIncoming.size, incomingDeps.size, "Incoming BLOCKS count should be consistent")
        assertEquals(1, allIncoming.size, "Should have 1 incoming BLOCKS dependency")
        assertEquals(1, allOutgoing.size, "Should have 1 outgoing BLOCKS dependency")
        
        // Verify all returned dependencies are BLOCKS type
        allIncoming.forEach { dep ->
            assertEquals("BLOCKS", dep.jsonObject["type"]?.jsonPrimitive?.content)
        }
        allOutgoing.forEach { dep ->
            assertEquals("BLOCKS", dep.jsonObject["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `includeTaskInfo should work with all direction combinations`() = runBlocking {
        // Create test dependency
        val testDependency = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        mockDependencyRepository.addDependency(testDependency)

        // Test direction="all" with includeTaskInfo
        val allParams = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("all"),
                "includeTaskInfo" to JsonPrimitive(true)
            )
        )

        val allResponse = tool.execute(allParams, mockContext)
        val allData = (allResponse as JsonObject)["data"]?.jsonObject!!
        val allDeps = allData["dependencies"]?.jsonObject!!
        val allIncoming = allDeps["incoming"]?.jsonArray!!

        // Test direction="incoming" with includeTaskInfo
        val incomingParams = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "direction" to JsonPrimitive("incoming"),
                "includeTaskInfo" to JsonPrimitive(true)
            )
        )

        val incomingResponse = tool.execute(incomingParams, mockContext)
        val incomingData = (incomingResponse as JsonObject)["data"]?.jsonObject!!
        val incomingDeps = incomingData["dependencies"]?.jsonArray!!

        // Both should include task info consistently
        assertEquals(1, allIncoming.size, "Should have 1 incoming dependency")
        assertEquals(1, incomingDeps.size, "Should have 1 incoming dependency")
        
        val allRelatedTask = allIncoming[0].jsonObject["relatedTask"]?.jsonObject
        val incomingRelatedTask = incomingDeps[0].jsonObject["relatedTask"]?.jsonObject
        
        assertNotNull(allRelatedTask, "Task info should be included in direction=all")
        assertNotNull(incomingRelatedTask, "Task info should be included in direction=incoming")
        assertEquals(allRelatedTask!!["id"]?.jsonPrimitive?.content, 
                     incomingRelatedTask!!["id"]?.jsonPrimitive?.content,
                     "Task info should be consistent")
    }

    @Test 
    fun `counts should reflect filtered results not raw dependencies`() = runBlocking {
        // Create dependencies of mixed types
        val blocksDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )
        
        val relatedDep1 = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )
        
        val relatedDep2 = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask3Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )
        
        mockDependencyRepository.addDependencies(listOf(blocksDep, relatedDep1, relatedDep2))

        // Apply BLOCKS type filter
        val params = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive(testTaskId.toString()),
                "type" to JsonPrimitive("BLOCKS")
            )
        )

        val response = tool.execute(params, mockContext)
        val data = (response as JsonObject)["data"]?.jsonObject!!
        val counts = data["counts"]?.jsonObject!!

        // Counts should reflect filtered results (only BLOCKS dependencies)
        assertEquals(1, counts["total"]?.jsonPrimitive?.int, "Total should be filtered count")
        assertEquals(1, counts["incoming"]?.jsonPrimitive?.int, "Incoming should be filtered count")
        assertEquals(0, counts["outgoing"]?.jsonPrimitive?.int, "Outgoing should be filtered count")
        
        val byType = counts["byType"]?.jsonObject!!
        assertEquals(1, byType["BLOCKS"]?.jsonPrimitive?.int, "Should count only BLOCKS dependencies")
        assertEquals(0, byType["IS_BLOCKED_BY"]?.jsonPrimitive?.int, "Should not count filtered out types")
        assertEquals(0, byType["RELATES_TO"]?.jsonPrimitive?.int, "Should not count filtered out types")
    }
}