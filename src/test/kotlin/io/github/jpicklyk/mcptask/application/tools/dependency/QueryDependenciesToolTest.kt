package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.test.mock.MockDependencyRepository
import io.github.jpicklyk.mcptask.test.mock.MockRepositoryProvider
import io.github.jpicklyk.mcptask.test.mock.MockTaskRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class QueryDependenciesToolTest {

    private lateinit var tool: SimpleLockAwareToolDefinition
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
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH
        )

        val relatedTask2 = Task(
            id = relatedTask2Id,
            title = "Related Task 2",
            summary = "Second related task",
            status = TaskStatus.COMPLETED,
            priority = Priority.MEDIUM
        )

        val relatedTask3 = Task(
            id = relatedTask3Id,
            title = "Related Task 3",
            summary = "Third related task",
            status = TaskStatus.PENDING,
            priority = Priority.LOW
        )

        // Add tasks to mock repository
        mockTaskRepository.addTask(testTask)
        mockTaskRepository.addTask(relatedTask1)
        mockTaskRepository.addTask(relatedTask2)
        mockTaskRepository.addTask(relatedTask3)

        mockContext = ToolExecutionContext(mockRepositoryProvider)
        tool = QueryDependenciesTool()
    }

    // ========== VALIDATION TESTS ==========

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("direction", "all")
            put("type", "BLOCKS")
            put("includeTaskInfo", true)
        }

        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate without taskId should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("direction", "all")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `validate with invalid taskId UUID should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("taskId", "not-a-uuid")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid taskId format"))
    }

    @Test
    fun `validate with invalid direction should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("direction", "invalid_direction")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid direction"))
    }

    @Test
    fun `validate with invalid dependency type should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("type", "INVALID_TYPE")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid type"))
    }

    @Test
    fun `validate with valid direction options should not throw exceptions`() {
        val validDirections = listOf("incoming", "outgoing", "all")

        validDirections.forEach { direction ->
            val validParams = buildJsonObject {
                put("taskId", testTaskId.toString())
                put("direction", direction)
            }

            assertDoesNotThrow { tool.validateParams(validParams) }
        }
    }

    @Test
    fun `validate with valid dependency types should not throw exceptions`() {
        val validTypes = listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO", "all")

        validTypes.forEach { type ->
            val validParams = buildJsonObject {
                put("taskId", testTaskId.toString())
                put("type", type)
            }

            assertDoesNotThrow { tool.validateParams(validParams) }
        }
    }

    // ========== EXECUTION TESTS ==========

    @Test
    fun `execute with valid parameters and no dependencies should return empty result`() = runBlocking {
        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(testTaskId.toString(), data["taskId"]?.jsonPrimitive?.content)

        val counts = data["counts"]!!.jsonObject
        assertEquals(0, counts["total"]?.jsonPrimitive?.int)
        assertEquals(0, counts["incoming"]?.jsonPrimitive?.int)
        assertEquals(0, counts["outgoing"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute with nonexistent task should return error`() = runBlocking {
        val nonexistentTaskId = UUID.randomUUID()
        val params = buildJsonObject {
            put("taskId", nonexistentTaskId.toString())
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(result["message"]?.jsonPrimitive?.content?.contains("not found") == true)
    }

    @Test
    fun `execute with incoming dependencies should return correct results`() = runBlocking {
        // Create incoming dependencies (other tasks blocking this task)
        val dep1 = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS
        )
        val dep2 = Dependency(
            fromTaskId = relatedTask2Id,
            toTaskId = testTaskId,
            type = DependencyType.IS_BLOCKED_BY
        )

        mockDependencyRepository.addDependency(dep1)
        mockDependencyRepository.addDependency(dep2)

        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("direction", "incoming")
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val dependencies = data["dependencies"]!!.jsonArray

        assertEquals(2, dependencies.size)
        val counts = data["counts"]!!.jsonObject
        assertEquals(2, counts["total"]?.jsonPrimitive?.int)
        assertEquals(2, counts["incoming"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute with outgoing dependencies should return correct results`() = runBlocking {
        // Create outgoing dependencies (this task blocking other tasks)
        val dep1 = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask1Id,
            type = DependencyType.BLOCKS
        )
        val dep2 = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.RELATES_TO
        )

        mockDependencyRepository.addDependency(dep1)
        mockDependencyRepository.addDependency(dep2)

        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("direction", "outgoing")
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val dependencies = data["dependencies"]!!.jsonArray

        assertEquals(2, dependencies.size)
        val counts = data["counts"]!!.jsonObject
        assertEquals(2, counts["total"]?.jsonPrimitive?.int)
        assertEquals(2, counts["outgoing"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute with direction all should return separated incoming and outgoing`() = runBlocking {
        // Create mixed dependencies
        val incomingDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS
        )
        val outgoingDep = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.BLOCKS
        )

        mockDependencyRepository.addDependency(incomingDep)
        mockDependencyRepository.addDependency(outgoingDep)

        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("direction", "all")
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val dependencies = data["dependencies"]!!.jsonObject

        assertTrue(dependencies.containsKey("incoming"))
        assertTrue(dependencies.containsKey("outgoing"))

        val incoming = dependencies["incoming"]!!.jsonArray
        val outgoing = dependencies["outgoing"]!!.jsonArray

        assertEquals(1, incoming.size)
        assertEquals(1, outgoing.size)

        val counts = data["counts"]!!.jsonObject
        assertEquals(2, counts["total"]?.jsonPrimitive?.int)
        assertEquals(1, counts["incoming"]?.jsonPrimitive?.int)
        assertEquals(1, counts["outgoing"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute with type filter should return only matching dependencies`() = runBlocking {
        // Create dependencies of different types
        val blocksDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS
        )
        val relatesDep = Dependency(
            fromTaskId = relatedTask2Id,
            toTaskId = testTaskId,
            type = DependencyType.RELATES_TO
        )

        mockDependencyRepository.addDependency(blocksDep)
        mockDependencyRepository.addDependency(relatesDep)

        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("type", "BLOCKS")
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val dependencies = data["dependencies"]!!.jsonObject
        val incoming = dependencies["incoming"]!!.jsonArray

        assertEquals(1, incoming.size)
        val dep = incoming[0].jsonObject
        assertEquals("BLOCKS", dep["type"]?.jsonPrimitive?.content)

        val counts = data["counts"]!!.jsonObject
        assertEquals(1, counts["total"]?.jsonPrimitive?.int)
        assertEquals(1, counts["byType"]!!.jsonObject["BLOCKS"]?.jsonPrimitive?.int)
        assertEquals(0, counts["byType"]!!.jsonObject["RELATES_TO"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute with includeTaskInfo should enrich dependencies with task details`() = runBlocking {
        val dep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS
        )

        mockDependencyRepository.addDependency(dep)

        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("includeTaskInfo", true)
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val dependencies = data["dependencies"]!!.jsonObject
        val incoming = dependencies["incoming"]!!.jsonArray

        assertEquals(1, incoming.size)
        val depObj = incoming[0].jsonObject

        assertTrue(depObj.containsKey("relatedTask"))
        val relatedTask = depObj["relatedTask"]!!.jsonObject
        assertEquals("Related Task 1", relatedTask["title"]?.jsonPrimitive?.content)
        assertEquals("in-progress", relatedTask["status"]?.jsonPrimitive?.content)
        assertEquals("high", relatedTask["priority"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute without includeTaskInfo should not include task details`() = runBlocking {
        val dep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS
        )

        mockDependencyRepository.addDependency(dep)

        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("includeTaskInfo", false)
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val dependencies = data["dependencies"]!!.jsonObject
        val incoming = dependencies["incoming"]!!.jsonArray

        assertEquals(1, incoming.size)
        val depObj = incoming[0].jsonObject

        assertFalse(depObj.containsKey("relatedTask"))
    }

    @Test
    fun `execute with combined filters should apply both direction and type`() = runBlocking {
        // Create various dependencies
        val incomingBlocks = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS
        )
        val incomingRelates = Dependency(
            fromTaskId = relatedTask2Id,
            toTaskId = testTaskId,
            type = DependencyType.RELATES_TO
        )
        val outgoingBlocks = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask3Id,
            type = DependencyType.BLOCKS
        )

        mockDependencyRepository.addDependency(incomingBlocks)
        mockDependencyRepository.addDependency(incomingRelates)
        mockDependencyRepository.addDependency(outgoingBlocks)

        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("direction", "incoming")
            put("type", "BLOCKS")
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val dependencies = data["dependencies"]!!.jsonArray

        // Should only return incoming BLOCKS dependency
        assertEquals(1, dependencies.size)
        val dep = dependencies[0].jsonObject
        assertEquals("BLOCKS", dep["type"]?.jsonPrimitive?.content)
        assertEquals(relatedTask1Id.toString(), dep["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(testTaskId.toString(), dep["toTaskId"]?.jsonPrimitive?.content)

        val counts = data["counts"]!!.jsonObject
        assertEquals(1, counts["total"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute should include applied filters in response`() = runBlocking {
        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
            put("direction", "incoming")
            put("type", "BLOCKS")
            put("includeTaskInfo", true)
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val filters = data["filters"]!!.jsonObject

        assertEquals("incoming", filters["direction"]?.jsonPrimitive?.content)
        assertEquals("BLOCKS", filters["type"]?.jsonPrimitive?.content)
        assertTrue(filters["includeTaskInfo"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `execute should correctly count dependencies by type`() = runBlocking {
        // Create dependencies of each type
        val blocksDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = testTaskId,
            type = DependencyType.BLOCKS
        )
        val isBlockedByDep = Dependency(
            fromTaskId = testTaskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.IS_BLOCKED_BY
        )
        val relatesDep = Dependency(
            fromTaskId = relatedTask3Id,
            toTaskId = testTaskId,
            type = DependencyType.RELATES_TO
        )

        mockDependencyRepository.addDependency(blocksDep)
        mockDependencyRepository.addDependency(isBlockedByDep)
        mockDependencyRepository.addDependency(relatesDep)

        val params = buildJsonObject {
            put("taskId", testTaskId.toString())
        }

        val result = tool.execute(params, mockContext) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        val counts = data["counts"]!!.jsonObject
        val byType = counts["byType"]!!.jsonObject

        assertEquals(1, byType["BLOCKS"]?.jsonPrimitive?.int)
        assertEquals(1, byType["IS_BLOCKED_BY"]?.jsonPrimitive?.int)
        assertEquals(1, byType["RELATES_TO"]?.jsonPrimitive?.int)
        assertEquals(3, counts["total"]?.jsonPrimitive?.int)
    }

    // Note: shouldUseLocking() is protected and cannot be tested directly
}
