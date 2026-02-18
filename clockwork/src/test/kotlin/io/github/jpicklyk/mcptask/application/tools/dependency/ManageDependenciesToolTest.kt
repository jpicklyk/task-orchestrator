package io.github.jpicklyk.mcptask.application.tools.dependency

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
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

class ManageDependenciesToolTest {

    private lateinit var tool: ManageDependenciesTool
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

        // Mock successful batch create by default
        every { mockDependencyRepository.createBatch(any()) } answers {
            firstArg<List<Dependency>>()
        }

        // Mock successful dependency creation by default (for single create fallback)
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

        tool = ManageDependenciesTool()
    }

    // ========== TOOL METADATA TESTS ==========

    @Test
    fun `tool name should be manage_dependencies`() {
        assertEquals("manage_dependencies", tool.name)
    }

    @Test
    fun `tool title should be Manage Task Dependencies`() {
        assertEquals("Manage Task Dependencies", tool.title)
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

    // ========== LEGACY CREATE VALIDATION TESTS ==========

    @Test
    fun `validate legacy create with valid parameters should not throw exceptions`() {
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
    fun `validate create without any mode should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }
        assertTrue(exception.message!!.contains("Must specify one of"))
    }

    @Test
    fun `validate legacy create with same fromTaskId and toTaskId should throw validation exception`() {
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
    fun `validate legacy create with invalid fromTaskId UUID should throw validation exception`() {
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
    fun `validate legacy create with invalid dependency type should throw validation exception`() {
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

    // ========== DEPENDENCIES ARRAY VALIDATION TESTS ==========

    @Test
    fun `validate create with dependencies array should not throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString())
                            )
                        )
                    )
                )
            )
        )

        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `validate create with empty dependencies array should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(emptyList())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("must not be empty"))
    }

    @Test
    fun `validate create with dependencies array missing fromTaskId should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "toTaskId" to JsonPrimitive(validToTaskId.toString())
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("missing required 'fromTaskId'"))
    }

    @Test
    fun `validate create with dependencies array self-dependency should throw exception`() {
        val sameId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(sameId.toString()),
                                "toTaskId" to JsonPrimitive(sameId.toString())
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("cannot depend on itself"))
    }

    // ========== MODE MIXING VALIDATION TESTS ==========

    @Test
    fun `validate create with both dependencies and pattern should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString())
                            )
                        )
                    )
                ),
                "pattern" to JsonPrimitive("linear"),
                "taskIds" to JsonArray(
                    listOf(
                        JsonPrimitive(validFromTaskId.toString()),
                        JsonPrimitive(validToTaskId.toString())
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Cannot mix create modes"))
    }

    @Test
    fun `validate create with both dependencies and legacy params should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString())
                            )
                        )
                    )
                ),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Cannot mix create modes"))
    }

    // ========== PATTERN VALIDATION TESTS ==========

    @Test
    fun `validate linear pattern with valid taskIds should not throw exception`() {
        val taskC = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear"),
                "taskIds" to JsonArray(
                    listOf(
                        JsonPrimitive(validFromTaskId.toString()),
                        JsonPrimitive(validToTaskId.toString()),
                        JsonPrimitive(taskC.toString())
                    )
                )
            )
        )

        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `validate linear pattern without taskIds should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("requires 'taskIds' array"))
    }

    @Test
    fun `validate linear pattern with single taskId should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear"),
                "taskIds" to JsonArray(listOf(JsonPrimitive(validFromTaskId.toString())))
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("at least 2 task IDs"))
    }

    @Test
    fun `validate linear pattern with duplicate taskIds should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear"),
                "taskIds" to JsonArray(
                    listOf(
                        JsonPrimitive(validFromTaskId.toString()),
                        JsonPrimitive(validToTaskId.toString()),
                        JsonPrimitive(validFromTaskId.toString())
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("duplicate"))
    }

    @Test
    fun `validate fan-out pattern with valid params should not throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-out"),
                "source" to JsonPrimitive(validFromTaskId.toString()),
                "targets" to JsonArray(
                    listOf(
                        JsonPrimitive(validToTaskId.toString()),
                        JsonPrimitive(UUID.randomUUID().toString())
                    )
                )
            )
        )

        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `validate fan-out pattern without source should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-out"),
                "targets" to JsonArray(listOf(JsonPrimitive(validToTaskId.toString())))
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("requires 'source'"))
    }

    @Test
    fun `validate fan-out pattern without targets should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-out"),
                "source" to JsonPrimitive(validFromTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("requires 'targets'"))
    }

    @Test
    fun `validate fan-in pattern with valid params should not throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-in"),
                "target" to JsonPrimitive(validToTaskId.toString()),
                "sources" to JsonArray(
                    listOf(
                        JsonPrimitive(validFromTaskId.toString()),
                        JsonPrimitive(UUID.randomUUID().toString())
                    )
                )
            )
        )

        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `validate fan-in pattern without target should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-in"),
                "sources" to JsonArray(listOf(JsonPrimitive(validFromTaskId.toString())))
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("requires 'target'"))
    }

    @Test
    fun `validate fan-in pattern without sources should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-in"),
                "target" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("requires 'sources'"))
    }

    @Test
    fun `validate invalid pattern should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("invalid-pattern"),
                "taskIds" to JsonArray(
                    listOf(
                        JsonPrimitive(validFromTaskId.toString()),
                        JsonPrimitive(validToTaskId.toString())
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid pattern"))
    }

    // ========== LEGACY CREATE EXECUTION TESTS ==========

    @Test
    fun `execute legacy create with valid parameters should succeed`() = runBlocking {
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
        assertEquals(1, data!!["createdCount"]?.jsonPrimitive?.int)
        val deps = data["dependencies"]?.jsonArray
        assertEquals(1, deps?.size)
        assertEquals(validFromTaskId.toString(), deps!![0].jsonObject["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(validToTaskId.toString(), deps[0].jsonObject["toTaskId"]?.jsonPrimitive?.content)
        assertEquals("BLOCKS", deps[0].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute legacy create with default type should use BLOCKS`() = runBlocking {
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
        val deps = data!!["dependencies"]?.jsonArray
        assertEquals("BLOCKS", deps!![0].jsonObject["type"]?.jsonPrimitive?.content)
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
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Task not found"))
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
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Task not found"))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with batch validation failure should return error`() = runBlocking {
        // Mock createBatch to throw ValidationException (e.g., duplicate)
        every { mockDependencyRepository.createBatch(any()) } throws
                ValidationException("A dependency of type BLOCKS already exists between these tasks")

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
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Validation failed"))
        assertEquals(ErrorCodes.VALIDATION_ERROR, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with cycle detection failure should return error`() = runBlocking {
        // Mock createBatch to throw ValidationException for cycle
        every { mockDependencyRepository.createBatch(any()) } throws
                ValidationException("Creating this dependency would result in a circular dependency")

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
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Validation failed"))
        assertEquals(ErrorCodes.VALIDATION_ERROR, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    // ========== BATCH CREATE EXECUTION TESTS ==========

    @Test
    fun `execute create with dependencies array should succeed`() = runBlocking {
        val taskC = UUID.randomUUID()
        val mockTaskC = Task(id = taskC, title = "Task C", summary = "C", status = TaskStatus.PENDING)
        coEvery { mockTaskRepository.getById(taskC) } returns Result.Success(mockTaskC)

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                                "type" to JsonPrimitive("BLOCKS")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validToTaskId.toString()),
                                "toTaskId" to JsonPrimitive(taskC.toString()),
                                "type" to JsonPrimitive("BLOCKS")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("2 dependencies created successfully", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        assertEquals(2, data!!["createdCount"]?.jsonPrimitive?.int)
        val deps = data["dependencies"]?.jsonArray
        assertEquals(2, deps?.size)
    }

    @Test
    fun `execute create with single dependencies array item should succeed with singular message`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString())
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Dependency created successfully", resultObj["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with dependencies array uses per-item type override`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                                "type" to JsonPrimitive("RELATES_TO")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray
        assertEquals("RELATES_TO", deps!![0].jsonObject["type"]?.jsonPrimitive?.content)
    }

    // ========== PATTERN EXECUTION TESTS ==========

    @Test
    fun `execute create with linear pattern should create chain`() = runBlocking {
        val taskA = validFromTaskId
        val taskB = validToTaskId
        val taskC = UUID.randomUUID()
        val taskD = UUID.randomUUID()

        // Mock all tasks as existing
        coEvery { mockTaskRepository.getById(taskC) } returns Result.Success(
            Task(id = taskC, title = "Task C", summary = "C", status = TaskStatus.PENDING)
        )
        coEvery { mockTaskRepository.getById(taskD) } returns Result.Success(
            Task(id = taskD, title = "Task D", summary = "D", status = TaskStatus.PENDING)
        )

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear"),
                "taskIds" to JsonArray(
                    listOf(
                        JsonPrimitive(taskA.toString()),
                        JsonPrimitive(taskB.toString()),
                        JsonPrimitive(taskC.toString()),
                        JsonPrimitive(taskD.toString())
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("3 dependencies created successfully", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        assertEquals(3, data!!["createdCount"]?.jsonPrimitive?.int)

        val deps = data["dependencies"]?.jsonArray!!
        // A->B
        assertEquals(taskA.toString(), deps[0].jsonObject["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(taskB.toString(), deps[0].jsonObject["toTaskId"]?.jsonPrimitive?.content)
        // B->C
        assertEquals(taskB.toString(), deps[1].jsonObject["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(taskC.toString(), deps[1].jsonObject["toTaskId"]?.jsonPrimitive?.content)
        // C->D
        assertEquals(taskC.toString(), deps[2].jsonObject["fromTaskId"]?.jsonPrimitive?.content)
        assertEquals(taskD.toString(), deps[2].jsonObject["toTaskId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with fan-out pattern should create one-to-many`() = runBlocking {
        val sourceId = validFromTaskId
        val targetB = validToTaskId
        val targetC = UUID.randomUUID()
        val targetD = UUID.randomUUID()

        coEvery { mockTaskRepository.getById(targetC) } returns Result.Success(
            Task(id = targetC, title = "Target C", summary = "C", status = TaskStatus.PENDING)
        )
        coEvery { mockTaskRepository.getById(targetD) } returns Result.Success(
            Task(id = targetD, title = "Target D", summary = "D", status = TaskStatus.PENDING)
        )

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-out"),
                "source" to JsonPrimitive(sourceId.toString()),
                "targets" to JsonArray(
                    listOf(
                        JsonPrimitive(targetB.toString()),
                        JsonPrimitive(targetC.toString()),
                        JsonPrimitive(targetD.toString())
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("3 dependencies created successfully", resultObj["message"]?.jsonPrimitive?.content)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray!!
        assertEquals(3, deps.size)
        // All should have same source
        deps.forEach { dep ->
            assertEquals(sourceId.toString(), dep.jsonObject["fromTaskId"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `execute create with fan-in pattern should create many-to-one`() = runBlocking {
        val targetId = validToTaskId
        val sourceA = validFromTaskId
        val sourceC = UUID.randomUUID()
        val sourceD = UUID.randomUUID()

        coEvery { mockTaskRepository.getById(sourceC) } returns Result.Success(
            Task(id = sourceC, title = "Source C", summary = "C", status = TaskStatus.PENDING)
        )
        coEvery { mockTaskRepository.getById(sourceD) } returns Result.Success(
            Task(id = sourceD, title = "Source D", summary = "D", status = TaskStatus.PENDING)
        )

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-in"),
                "target" to JsonPrimitive(targetId.toString()),
                "sources" to JsonArray(
                    listOf(
                        JsonPrimitive(sourceA.toString()),
                        JsonPrimitive(sourceC.toString()),
                        JsonPrimitive(sourceD.toString())
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("3 dependencies created successfully", resultObj["message"]?.jsonPrimitive?.content)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray!!
        assertEquals(3, deps.size)
        // All should have same target
        deps.forEach { dep ->
            assertEquals(targetId.toString(), dep.jsonObject["toTaskId"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `execute create with pattern and type override should use specified type`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear"),
                "taskIds" to JsonArray(
                    listOf(
                        JsonPrimitive(validFromTaskId.toString()),
                        JsonPrimitive(validToTaskId.toString())
                    )
                ),
                "type" to JsonPrimitive("RELATES_TO")
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray!!
        assertEquals("RELATES_TO", deps[0].jsonObject["type"]?.jsonPrimitive?.content)
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

    // ========== USER SUMMARY TESTS ==========

    @Test
    fun `userSummary for single create should format correctly`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString())
            )
        )

        val result = tool.execute(params, mockContext)
        val summary = tool.userSummary(params, result, false)

        assertTrue(summary.contains("BLOCKS"))
        assertTrue(summary.contains("->"))
    }

    @Test
    fun `userSummary for batch create should show count`() {
        val resultData = buildJsonObject {
            put("success", true)
            put("message", "3 dependencies created successfully")
            put("data", buildJsonObject {
                put("createdCount", 3)
                put("dependencies", JsonArray(emptyList()))
            })
        }

        val params = JsonObject(mapOf("operation" to JsonPrimitive("create")))
        val summary = tool.userSummary(params, resultData, false)
        assertEquals("Created 3 dependencies", summary)
    }

    @Test
    fun `userSummary for delete should format correctly`() {
        val resultData = buildJsonObject {
            put("success", true)
            put("message", "Dependency deleted successfully")
            put("data", buildJsonObject {
                put("deletedCount", 1)
            })
        }

        val params = JsonObject(mapOf("operation" to JsonPrimitive("delete")))
        val summary = tool.userSummary(params, resultData, false)
        assertEquals("Deleted 1 dependency", summary)
    }

    // ========== UNBLOCK_AT VALIDATION TESTS ==========

    @Test
    fun `validate create with valid top-level unblockAt should not throw exception`() {
        for (value in listOf("queue", "work", "review", "terminal")) {
            val params = JsonObject(
                mapOf(
                    "operation" to JsonPrimitive("create"),
                    "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                    "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                    "unblockAt" to JsonPrimitive(value)
                )
            )
            assertDoesNotThrow { tool.validateParams(params) }
        }
    }

    @Test
    fun `validate create with invalid top-level unblockAt should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "unblockAt" to JsonPrimitive("invalid_role")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid unblockAt value"))
    }

    @Test
    fun `validate create with blocked as unblockAt should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "unblockAt" to JsonPrimitive("blocked")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid unblockAt value"))
    }

    @Test
    fun `validate create with invalid per-dependency unblockAt should throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                                "unblockAt" to JsonPrimitive("bad_value")
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("unblockAt is invalid"))
    }

    @Test
    fun `validate create with valid per-dependency unblockAt should not throw exception`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                                "unblockAt" to JsonPrimitive("work")
                            )
                        )
                    )
                )
            )
        )

        assertDoesNotThrow { tool.validateParams(params) }
    }

    // ========== UNBLOCK_AT EXECUTION TESTS ==========

    @Test
    fun `execute create with explicit unblockAt work should include it in response`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                "unblockAt" to JsonPrimitive("work")
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray
        assertNotNull(deps)
        assertEquals(1, deps!!.size)
        assertEquals("work", deps[0].jsonObject["unblockAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create without unblockAt should not include it in response`() = runBlocking {
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

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray
        assertNotNull(deps)
        assertEquals(1, deps!!.size)
        // unblockAt should not be present in the response when null
        assertFalse(deps[0].jsonObject.containsKey("unblockAt"))
    }

    @Test
    fun `execute create with linear pattern and top-level unblockAt should apply to all deps`() = runBlocking {
        val taskA = validFromTaskId
        val taskB = validToTaskId
        val taskC = UUID.randomUUID()

        coEvery { mockTaskRepository.getById(taskC) } returns Result.Success(
            Task(id = taskC, title = "Task C", summary = "C", status = TaskStatus.PENDING)
        )

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear"),
                "taskIds" to JsonArray(
                    listOf(
                        JsonPrimitive(taskA.toString()),
                        JsonPrimitive(taskB.toString()),
                        JsonPrimitive(taskC.toString())
                    )
                ),
                "unblockAt" to JsonPrimitive("review")
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray!!
        assertEquals(2, deps.size)
        // All should have unblockAt = "review"
        deps.forEach { dep ->
            assertEquals("review", dep.jsonObject["unblockAt"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `execute create with fan-out pattern and top-level unblockAt should apply to all deps`() = runBlocking {
        val sourceId = validFromTaskId
        val targetB = validToTaskId
        val targetC = UUID.randomUUID()

        coEvery { mockTaskRepository.getById(targetC) } returns Result.Success(
            Task(id = targetC, title = "Target C", summary = "C", status = TaskStatus.PENDING)
        )

        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-out"),
                "source" to JsonPrimitive(sourceId.toString()),
                "targets" to JsonArray(
                    listOf(
                        JsonPrimitive(targetB.toString()),
                        JsonPrimitive(targetC.toString())
                    )
                ),
                "unblockAt" to JsonPrimitive("queue")
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray!!
        assertEquals(2, deps.size)
        deps.forEach { dep ->
            assertEquals("queue", dep.jsonObject["unblockAt"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `execute create with per-dependency unblockAt overriding top-level`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "unblockAt" to JsonPrimitive("terminal"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                                "unblockAt" to JsonPrimitive("work")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray
        assertNotNull(deps)
        assertEquals(1, deps!!.size)
        // Per-dependency "work" should override top-level "terminal"
        assertEquals("work", deps[0].jsonObject["unblockAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute create with RELATES_TO and unblockAt should return validation error`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString()),
                                "type" to JsonPrimitive("RELATES_TO"),
                                "unblockAt" to JsonPrimitive("work")
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        // Should fail because Dependency.validate() rejects unblockAt on RELATES_TO
        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content!!.contains("Validation failed"))
    }

    @Test
    fun `execute create with dependencies array inherits top-level unblockAt when not specified per-dep`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "unblockAt" to JsonPrimitive("review"),
                "dependencies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "fromTaskId" to JsonPrimitive(validFromTaskId.toString()),
                                "toTaskId" to JsonPrimitive(validToTaskId.toString())
                            )
                        )
                    )
                )
            )
        )

        val result = tool.execute(params, mockContext)

        val resultObj = result.jsonObject
        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val deps = resultObj["data"]?.jsonObject?.get("dependencies")?.jsonArray
        assertNotNull(deps)
        assertEquals(1, deps!!.size)
        // Should inherit top-level unblockAt="review"
        assertEquals("review", deps[0].jsonObject["unblockAt"]?.jsonPrimitive?.content)
    }
}
