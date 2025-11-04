package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.dependency.ManageDependencyTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.test.mock.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Integration tests for dependency cycle detection and prevention across the entire system.
 * 
 * Tests various cycle scenarios including:
 * - Direct cycles (A -> B -> A)
 * - Multi-hop cycles (A -> B -> C -> A)
 * - Complex dependency chains with mixed types
 * - Self-dependencies
 * - Large dependency graphs
 */
class DependencyCyclePreventionTest {
    private lateinit var dependencyRepository: MockDependencyRepository
    private lateinit var taskRepository: MockTaskRepository
    private lateinit var executionContext: ToolExecutionContext
    
    // Tools
    private lateinit var manageDependencyTool: ManageDependencyTool
    
    // Test tasks
    private lateinit var taskA: Task
    private lateinit var taskB: Task
    private lateinit var taskC: Task
    private lateinit var taskD: Task
    private lateinit var taskE: Task

    @BeforeEach
    fun setUp() {
        // Set up mock repositories
        dependencyRepository = MockDependencyRepository()
        taskRepository = MockTaskRepository()
        val sectionRepository = MockSectionRepository()
        val featureRepository = MockFeatureRepository()
        val projectRepository = MockProjectRepository()
        val templateRepository = MockTemplateRepository()

        val repositoryProvider = MockRepositoryProvider(
            taskRepository = taskRepository,
            dependencyRepository = dependencyRepository,
            sectionRepository = sectionRepository,
            featureRepository = featureRepository,
            projectRepository = projectRepository,
            templateRepository = templateRepository
        )

        executionContext = ToolExecutionContext(repositoryProvider)

        // Initialize tools
        manageDependencyTool = ManageDependencyTool()
        
        // Create test tasks
        taskA = Task(id = UUID.randomUUID(), title = "Task A", summary = "Test task A", status = TaskStatus.PENDING)
        taskB = Task(id = UUID.randomUUID(), title = "Task B", summary = "Test task B", status = TaskStatus.PENDING)
        taskC = Task(id = UUID.randomUUID(), title = "Task C", summary = "Test task C", status = TaskStatus.PENDING)
        taskD = Task(id = UUID.randomUUID(), title = "Task D", summary = "Test task D", status = TaskStatus.PENDING)
        taskE = Task(id = UUID.randomUUID(), title = "Task E", summary = "Test task E", status = TaskStatus.PENDING)
        
        // Add tasks to repository
        taskRepository.addTask(taskA)
        taskRepository.addTask(taskB)
        taskRepository.addTask(taskC)
        taskRepository.addTask(taskD)
        taskRepository.addTask(taskE)
    }

    @Test
    fun `should prevent direct cycle A blocks B then B blocks A`() = runBlocking {
        // Create A -> B dependency successfully
        val dep1Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskA.id.toString()),
            "toTaskId" to JsonPrimitive(taskB.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))

        val response1 = manageDependencyTool.execute(dep1Params, executionContext)
        val responseObj1 = response1 as JsonObject
        assertTrue(responseObj1["success"]?.jsonPrimitive?.boolean == true, "First dependency should be created successfully")
        
        // Attempt to create B -> A dependency (should fail due to cycle)
        val dep2Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskB.id.toString()),
            "toTaskId" to JsonPrimitive(taskA.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))

        val response2 = manageDependencyTool.execute(dep2Params, executionContext)
        val responseObj2 = response2 as JsonObject
        
        assertEquals(false, responseObj2["success"]?.jsonPrimitive?.boolean, "Second dependency should fail")
        
        val error = responseObj2["error"]?.jsonObject
        assertNotNull(error, "Error should be present")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content, "Should be validation error")
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("circular dependency") ?: false,
            "Error should mention circular dependency"
        )
    }

    @Test
    fun `should prevent three-task cycle A to B to C to A`() = runBlocking {
        // Create A -> B dependency
        val dep1Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskA.id.toString()),
            "toTaskId" to JsonPrimitive(taskB.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response1 = manageDependencyTool.execute(dep1Params, executionContext)
        assertTrue((response1 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // Create B -> C dependency
        val dep2Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskB.id.toString()),
            "toTaskId" to JsonPrimitive(taskC.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response2 = manageDependencyTool.execute(dep2Params, executionContext)
        assertTrue((response2 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // Attempt to create C -> A dependency (should fail due to cycle)
        val dep3Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskC.id.toString()),
            "toTaskId" to JsonPrimitive(taskA.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response3 = manageDependencyTool.execute(dep3Params, executionContext)
        val responseObj3 = response3 as JsonObject
        
        assertEquals(false, responseObj3["success"]?.jsonPrimitive?.boolean, "Third dependency should fail")
        
        val error = responseObj3["error"]?.jsonObject
        assertNotNull(error, "Error should be present")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("circular dependency") ?: false,
            "Error should mention circular dependency"
        )
    }

    @Test
    fun `should prevent self-dependency task depends on itself`() = runBlocking {
        val selfDepParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskA.id.toString()),
            "toTaskId" to JsonPrimitive(taskA.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))

        val response = manageDependencyTool.execute(selfDepParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Self-dependency should fail")
        
        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error should be present")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("cannot depend on itself") ?: false,
            "Error should mention self-dependency prevention"
        )
    }

    @Test
    fun `should prevent cycle with mixed dependency types`() = runBlocking {
        // Create A BLOCKS B
        val dep1Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskA.id.toString()),
            "toTaskId" to JsonPrimitive(taskB.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response1 = manageDependencyTool.execute(dep1Params, executionContext)
        assertTrue((response1 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // Create B RELATES_TO C
        val dep2Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskB.id.toString()),
            "toTaskId" to JsonPrimitive(taskC.id.toString()),
            "type" to JsonPrimitive("RELATES_TO")
        ))
        val response2 = manageDependencyTool.execute(dep2Params, executionContext)
        assertTrue((response2 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // Attempt to create C IS_BLOCKED_BY A (should fail due to cycle)
        val dep3Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskC.id.toString()),
            "toTaskId" to JsonPrimitive(taskA.id.toString()),
            "type" to JsonPrimitive("IS_BLOCKED_BY")
        ))
        val response3 = manageDependencyTool.execute(dep3Params, executionContext)
        val responseObj3 = response3 as JsonObject
        
        assertEquals(false, responseObj3["success"]?.jsonPrimitive?.boolean, "Mixed-type cycle should fail")
        
        val error = responseObj3["error"]?.jsonObject
        assertNotNull(error, "Error should be present")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("circular dependency") ?: false,
            "Error should mention circular dependency"
        )
    }

    @Test
    fun `should detect cycle in complex dependency graph`() = runBlocking {
        // Create a complex dependency graph:
        // A blocks B blocks C
        // A blocks D blocks E
        // Try to create E blocks A (should create cycle through A blocks D blocks E blocks A)
        
        // A -> B
        val dep1Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskA.id.toString()),
            "toTaskId" to JsonPrimitive(taskB.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response1 = manageDependencyTool.execute(dep1Params, executionContext)
        assertTrue((response1 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // B -> C
        val dep2Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskB.id.toString()),
            "toTaskId" to JsonPrimitive(taskC.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response2 = manageDependencyTool.execute(dep2Params, executionContext)
        assertTrue((response2 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // A -> D
        val dep3Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskA.id.toString()),
            "toTaskId" to JsonPrimitive(taskD.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response3 = manageDependencyTool.execute(dep3Params, executionContext)
        assertTrue((response3 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // D -> E
        val dep4Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskD.id.toString()),
            "toTaskId" to JsonPrimitive(taskE.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response4 = manageDependencyTool.execute(dep4Params, executionContext)
        assertTrue((response4 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // Attempt to create E -> A (should fail due to cycle)
        val dep5Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskE.id.toString()),
            "toTaskId" to JsonPrimitive(taskA.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response5 = manageDependencyTool.execute(dep5Params, executionContext)
        val responseObj5 = response5 as JsonObject
        
        assertEquals(false, responseObj5["success"]?.jsonPrimitive?.boolean, "Complex cycle should be detected")
        
        val error = responseObj5["error"]?.jsonObject
        assertNotNull(error, "Error should be present")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("circular dependency") ?: false,
            "Error should mention circular dependency"
        )
    }

    @Test
    fun `should allow valid dependency chains without cycles`() = runBlocking {
        // Create a valid dependency chain: A blocks B blocks C blocks D blocks E
        // This should all succeed without any cycle detection issues
        
        val dependencies = listOf(
            Triple(taskA.id, taskB.id, "BLOCKS"),
            Triple(taskB.id, taskC.id, "BLOCKS"),
            Triple(taskC.id, taskD.id, "BLOCKS"),
            Triple(taskD.id, taskE.id, "BLOCKS")
        )
        
        for ((fromId, toId, type) in dependencies) {
            val params = JsonObject(mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(fromId.toString()),
                "toTaskId" to JsonPrimitive(toId.toString()),
                "type" to JsonPrimitive(type)
            ))

            val response = manageDependencyTool.execute(params, executionContext)
            val responseObj = response as JsonObject
            
            assertTrue(
                responseObj["success"]?.jsonPrimitive?.boolean == true,
                "Valid dependency $fromId to $toId should succeed"
            )
        }
    }

    @Test
    fun `should prevent cycle detection after multiple successful dependencies`() = runBlocking {
        // Create several valid dependencies first
        val validDependencies = listOf(
            Triple(taskA.id, taskB.id, "BLOCKS"),
            Triple(taskB.id, taskC.id, "RELATES_TO"),
            Triple(taskC.id, taskD.id, "BLOCKS"),
            Triple(taskA.id, taskE.id, "IS_BLOCKED_BY")
        )
        
        // Create all valid dependencies
        for ((fromId, toId, type) in validDependencies) {
            val params = JsonObject(mapOf(
                "operation" to JsonPrimitive("create"),
                "fromTaskId" to JsonPrimitive(fromId.toString()),
                "toTaskId" to JsonPrimitive(toId.toString()),
                "type" to JsonPrimitive(type)
            ))

            val response = manageDependencyTool.execute(params, executionContext)
            assertTrue((response as JsonObject)["success"]?.jsonPrimitive?.boolean == true)
        }

        // Now attempt to create a cycle: D blocks A (should fail)
        val cycleParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskD.id.toString()),
            "toTaskId" to JsonPrimitive(taskA.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))

        val response = manageDependencyTool.execute(cycleParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Cycle should be detected after valid dependencies")
        
        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error should be present")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("circular dependency") ?: false,
            "Error should mention circular dependency"
        )
    }

    @Test
    fun `should handle duplicate dependency prevention alongside cycle detection`() = runBlocking {
        // Create a dependency
        val dep1Params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(taskA.id.toString()),
            "toTaskId" to JsonPrimitive(taskB.id.toString()),
            "type" to JsonPrimitive("BLOCKS")
        ))
        val response1 = manageDependencyTool.execute(dep1Params, executionContext)
        assertTrue((response1 as JsonObject)["success"]?.jsonPrimitive?.boolean == true)

        // Attempt to create the same dependency again (should fail due to duplicate)
        val response2 = manageDependencyTool.execute(dep1Params, executionContext)
        val responseObj2 = response2 as JsonObject
        
        assertEquals(false, responseObj2["success"]?.jsonPrimitive?.boolean, "Duplicate dependency should fail")
        
        val error = responseObj2["error"]?.jsonObject
        assertNotNull(error, "Error should be present")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("already exists") ?: false,
            "Error should mention duplicate dependency"
        )
    }
}