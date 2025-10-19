package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.application.tools.QueryContainerTool
import io.github.jpicklyk.mcptask.application.tools.dependency.ManageDependencyTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.test.mock.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Integration tests for feature-level dependency statistics calculations.
 * 
 * Tests the correct calculation and reporting of dependency statistics across features,
 * including:
 * - Task dependency counts within features
 * - Cross-feature dependency relationships
 * - Feature overview data with dependency information
 * - Statistics aggregation and accuracy
 * - Edge cases with empty features and complex dependency graphs
 */
class FeatureDependencyStatisticsTest {
    private lateinit var dependencyRepository: MockDependencyRepository
    private lateinit var taskRepository: MockTaskRepository
    private lateinit var featureRepository: MockFeatureRepository
    private lateinit var sectionRepository: MockSectionRepository
    private lateinit var projectRepository: MockProjectRepository
    private lateinit var templateRepository: MockTemplateRepository
    private lateinit var executionContext: ToolExecutionContext
    
    // Tools
    private lateinit var manageContainerTool: ManageContainerTool
    private lateinit var queryContainerTool: QueryContainerTool
    private lateinit var manageDependencyTool: ManageDependencyTool
    
    // Test entities
    private lateinit var featureA: Feature
    private lateinit var featureB: Feature
    private lateinit var featureC: Feature
    
    private lateinit var taskA1: Task
    private lateinit var taskA2: Task
    private lateinit var taskA3: Task
    private lateinit var taskB1: Task
    private lateinit var taskB2: Task
    private lateinit var taskC1: Task
    private lateinit var orphanTask: Task

    @BeforeEach
    fun setUp() {
        // Set up mock repositories
        dependencyRepository = MockDependencyRepository()
        taskRepository = MockTaskRepository()
        featureRepository = MockFeatureRepository()
        sectionRepository = MockSectionRepository()
        projectRepository = MockProjectRepository()
        templateRepository = MockTemplateRepository()

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
        manageContainerTool = ManageContainerTool()
        queryContainerTool = QueryContainerTool()
        manageDependencyTool = ManageDependencyTool()
        
        // Create test features
        featureA = Feature(
            id = UUID.randomUUID(),
            name = "Feature A - Authentication",
            summary = "User authentication and authorization features",
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.HIGH
        )
        
        featureB = Feature(
            id = UUID.randomUUID(),
            name = "Feature B - User Management",
            summary = "User profile and account management features",
            status = FeatureStatus.PLANNING,
            priority = Priority.MEDIUM
        )
        
        featureC = Feature(
            id = UUID.randomUUID(),
            name = "Feature C - Reporting",
            summary = "Analytics and reporting dashboard",
            status = FeatureStatus.PLANNING,
            priority = Priority.LOW
        )
        
        // Create test tasks
        taskA1 = Task(
            id = UUID.randomUUID(),
            title = "Task A1 - Login API",
            summary = "Implement login API endpoint",
            status = TaskStatus.IN_PROGRESS,
            featureId = featureA.id
        )
        
        taskA2 = Task(
            id = UUID.randomUUID(),
            title = "Task A2 - JWT Validation",
            summary = "Implement JWT token validation",
            status = TaskStatus.PENDING,
            featureId = featureA.id
        )
        
        taskA3 = Task(
            id = UUID.randomUUID(),
            title = "Task A3 - Password Reset",
            summary = "Implement password reset functionality",
            status = TaskStatus.COMPLETED,
            featureId = featureA.id
        )
        
        taskB1 = Task(
            id = UUID.randomUUID(),
            title = "Task B1 - User Profile",
            summary = "Create user profile management",
            status = TaskStatus.PENDING,
            featureId = featureB.id
        )
        
        taskB2 = Task(
            id = UUID.randomUUID(),
            title = "Task B2 - Account Settings",
            summary = "Implement account settings page",
            status = TaskStatus.PENDING,
            featureId = featureB.id
        )
        
        taskC1 = Task(
            id = UUID.randomUUID(),
            title = "Task C1 - Dashboard",
            summary = "Create analytics dashboard",
            status = TaskStatus.PENDING,
            featureId = featureC.id
        )
        
        orphanTask = Task(
            id = UUID.randomUUID(),
            title = "Orphan Task - Database Migration",
            summary = "Database schema migration task",
            status = TaskStatus.PENDING,
            featureId = null
        )
        
        // Add entities to repositories
        featureRepository.addFeature(featureA)
        featureRepository.addFeature(featureB)
        featureRepository.addFeature(featureC)
        
        taskRepository.addTask(taskA1)
        taskRepository.addTask(taskA2)
        taskRepository.addTask(taskA3)
        taskRepository.addTask(taskB1)
        taskRepository.addTask(taskB2)
        taskRepository.addTask(taskC1)
        taskRepository.addTask(orphanTask)
    }

    @Test
    fun `should calculate correct dependency statistics for feature with internal dependencies`() = runBlocking {
        // Create internal dependencies within Feature A
        // A1 -> A2 (A1 blocks A2)
        // A2 -> A3 (A2 blocks A3)

        createDependency(taskA1.id, taskA2.id, "BLOCKS")
        createDependency(taskA2.id, taskA3.id, "BLOCKS")

        // Get feature - v2 only returns taskCounts, not full tasks
        val featureParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("get"),
            "containerType" to JsonPrimitive("feature"),
            "id" to JsonPrimitive(featureA.id.toString())
        ))

        val featureResponse = queryContainerTool.execute(featureParams, executionContext)
        val featureResponseObj = featureResponse as JsonObject

        assertTrue(featureResponseObj["success"]?.jsonPrimitive?.boolean == true, "Feature retrieval should succeed")

        val featureData = featureResponseObj["data"]?.jsonObject
        assertNotNull(featureData, "Feature data should be present")

        // Verify taskCounts is included (v2 returns counts, not full tasks)
        val taskCounts = featureData!!["taskCounts"]?.jsonObject
        assertNotNull(taskCounts, "Task counts should be included")
        assertEquals(3, taskCounts!!["total"]?.jsonPrimitive?.int, "Should have 3 total tasks")

        // To verify dependency statistics, we need to search for tasks separately
        val tasksParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("search"),
            "containerType" to JsonPrimitive("task"),
            "featureId" to JsonPrimitive(featureA.id.toString()),
            "limit" to JsonPrimitive(100)
        ))

        val tasksResponse = queryContainerTool.execute(tasksParams, executionContext)
        val tasksResponseObj = tasksResponse as JsonObject
        assertTrue(tasksResponseObj["success"]?.jsonPrimitive?.boolean == true, "Task search should succeed")

        val tasksData = tasksResponseObj["data"]?.jsonObject
        val taskItems = tasksData!!["items"]?.jsonArray
        assertNotNull(taskItems, "Task items should be present")
        assertEquals(3, taskItems!!.size, "Should have 3 tasks")

        // Manually verify dependencies exist by checking dependency repository
        val allDeps = dependencyRepository.getAllDependencies()
        val featureDeps = allDeps.filter { dep ->
            val fromTask = taskRepository.getAllTasks().find { it.id == dep.fromTaskId }
            val toTask = taskRepository.getAllTasks().find { it.id == dep.toTaskId }
            fromTask?.featureId == featureA.id || toTask?.featureId == featureA.id
        }

        assertEquals(2, featureDeps.size, "Should have 2 dependencies in Feature A")
        assertTrue(featureDeps.any { it.fromTaskId == taskA1.id && it.toTaskId == taskA2.id }, "Should have A1->A2 dependency")
        assertTrue(featureDeps.any { it.fromTaskId == taskA2.id && it.toTaskId == taskA3.id }, "Should have A2->A3 dependency")
    }

    @Test
    fun `should calculate correct cross-feature dependency statistics`() = runBlocking {
        // Create cross-feature dependencies
        // A1 -> B1 (Auth login depends on user profile)
        // A3 -> B2 (Password reset relates to account settings)
        // B1 -> C1 (User profile blocks reporting dashboard)

        createDependency(taskA1.id, taskB1.id, "BLOCKS")
        createDependency(taskA3.id, taskB2.id, "RELATES_TO")
        createDependency(taskB1.id, taskC1.id, "BLOCKS")

        // Verify all dependencies were created correctly
        val allDeps = dependencyRepository.getAllDependencies()
        assertEquals(3, allDeps.size, "Should have 3 total dependencies")

        // Verify Feature A dependencies (outgoing only)
        val featureADeps = allDeps.filter { dep ->
            val fromTask = taskRepository.getAllTasks().find { it.id == dep.fromTaskId }
            fromTask?.featureId == featureA.id
        }
        assertEquals(2, featureADeps.size, "Feature A should have 2 outgoing dependencies")
        assertTrue(featureADeps.any { it.fromTaskId == taskA1.id && it.toTaskId == taskB1.id }, "Should have A1->B1")
        assertTrue(featureADeps.any { it.fromTaskId == taskA3.id && it.toTaskId == taskB2.id }, "Should have A3->B2")

        // Verify Feature B dependencies (incoming and outgoing)
        val featureBOutgoing = allDeps.filter { dep ->
            val fromTask = taskRepository.getAllTasks().find { it.id == dep.fromTaskId }
            fromTask?.featureId == featureB.id
        }
        val featureBIncoming = allDeps.filter { dep ->
            val toTask = taskRepository.getAllTasks().find { it.id == dep.toTaskId }
            toTask?.featureId == featureB.id
        }
        assertEquals(1, featureBOutgoing.size, "Feature B should have 1 outgoing dependency")
        assertEquals(2, featureBIncoming.size, "Feature B should have 2 incoming dependencies")
        assertTrue(featureBOutgoing.any { it.fromTaskId == taskB1.id && it.toTaskId == taskC1.id }, "Should have B1->C1")

        // Verify Feature C dependencies (incoming only)
        val featureCIncoming = allDeps.filter { dep ->
            val toTask = taskRepository.getAllTasks().find { it.id == dep.toTaskId }
            toTask?.featureId == featureC.id
        }
        assertEquals(1, featureCIncoming.size, "Feature C should have 1 incoming dependency")
        assertTrue(featureCIncoming.any { it.fromTaskId == taskB1.id && it.toTaskId == taskC1.id }, "Should have B1->C1")

        // Verify feature task counts are correct
        val featureAParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("get"),
            "containerType" to JsonPrimitive("feature"),
            "id" to JsonPrimitive(featureA.id.toString())
        ))
        val featureAResponse = queryContainerTool.execute(featureAParams, executionContext) as JsonObject
        val featureATaskCounts = featureAResponse["data"]?.jsonObject?.get("taskCounts")?.jsonObject
        assertEquals(3, featureATaskCounts?.get("total")?.jsonPrimitive?.int, "Feature A should have 3 tasks")
    }

    @Test
    fun `should handle feature with no dependencies correctly`() = runBlocking {
        // Feature C has no dependencies initially
        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("get"),
            "containerType" to JsonPrimitive("feature"),
            "id" to JsonPrimitive(featureC.id.toString())
        ))

        val response = queryContainerTool.execute(params, executionContext)
        val responseObj = response as JsonObject

        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Feature retrieval should succeed")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")

        // Verify taskCounts are returned
        val taskCounts = data!!["taskCounts"]?.jsonObject
        assertNotNull(taskCounts, "Task counts should be present")
        assertEquals(1, taskCounts!!["total"]?.jsonPrimitive?.int, "Feature C should have 1 task")

        // Verify no dependencies exist for this feature's tasks
        val allDeps = dependencyRepository.getAllDependencies()
        val featureCDeps = allDeps.filter { dep ->
            val fromTask = taskRepository.getAllTasks().find { it.id == dep.fromTaskId }
            val toTask = taskRepository.getAllTasks().find { it.id == dep.toTaskId }
            fromTask?.featureId == featureC.id || toTask?.featureId == featureC.id
        }
        assertEquals(0, featureCDeps.size, "Feature C should have 0 dependencies")
    }

    @Test
    fun `should handle empty feature correctly`() = runBlocking {
        // Create a feature with no tasks
        val emptyFeature = Feature(
            id = UUID.randomUUID(),
            name = "Empty Feature",
            summary = "Feature with no tasks",
            status = FeatureStatus.PLANNING,
            priority = Priority.LOW
        )
        featureRepository.addFeature(emptyFeature)

        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("get"),
            "containerType" to JsonPrimitive("feature"),
            "id" to JsonPrimitive(emptyFeature.id.toString())
        ))

        val response = queryContainerTool.execute(params, executionContext)
        val responseObj = response as JsonObject

        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Empty feature retrieval should succeed")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")

        // Verify taskCounts shows 0 tasks
        val taskCounts = data!!["taskCounts"]?.jsonObject
        assertNotNull(taskCounts, "Task counts should be present")
        assertEquals(0, taskCounts!!["total"]?.jsonPrimitive?.int, "Should have 0 total tasks")

        // Verify basic feature data is correct
        assertEquals(emptyFeature.id.toString(), data["id"]?.jsonPrimitive?.content, "Feature ID should match")
        assertEquals(emptyFeature.name, data["name"]?.jsonPrimitive?.content, "Feature name should match")
    }

    @Test
    fun `should calculate dependency statistics correctly in task overview`() = runBlocking {
        // Create a complex dependency graph
        createDependency(taskA1.id, taskA2.id, "BLOCKS")        // Internal to Feature A
        createDependency(taskA2.id, taskB1.id, "BLOCKS")        // A -> B cross-feature
        createDependency(taskB1.id, taskB2.id, "RELATES_TO")    // Internal to Feature B
        createDependency(taskB2.id, taskC1.id, "BLOCKS")        // B -> C cross-feature
        createDependency(orphanTask.id, taskA1.id, "BLOCKS")    // Orphan -> Feature A

        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("overview"),
            "containerType" to JsonPrimitive("task"),
            "summaryLength" to JsonPrimitive(50)
        ))

        val response = queryContainerTool.execute(params, executionContext)
        val responseObj = response as JsonObject

        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Task overview should succeed")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")

        // v2 overview returns simple list of items with count
        val items = data!!["items"]?.jsonArray
        val count = data["count"]?.jsonPrimitive?.int

        assertNotNull(items, "Items should be present")
        assertNotNull(count, "Count should be present")
        assertEquals(7, count, "Should have 7 total tasks")
        assertEquals(7, items!!.size, "Should have 7 task items in overview")

        // Verify dependencies were created correctly
        val allDeps = dependencyRepository.getAllDependencies()
        assertEquals(5, allDeps.size, "Should have 5 total dependencies")

        // Verify specific dependencies exist
        assertTrue(allDeps.any { it.fromTaskId == taskA1.id && it.toTaskId == taskA2.id }, "Should have A1->A2")
        assertTrue(allDeps.any { it.fromTaskId == taskA2.id && it.toTaskId == taskB1.id }, "Should have A2->B1")
        assertTrue(allDeps.any { it.fromTaskId == taskB1.id && it.toTaskId == taskB2.id }, "Should have B1->B2")
        assertTrue(allDeps.any { it.fromTaskId == taskB2.id && it.toTaskId == taskC1.id }, "Should have B2->C1")
        assertTrue(allDeps.any { it.fromTaskId == orphanTask.id && it.toTaskId == taskA1.id }, "Should have orphan->A1")

        // Verify tasks can be found in overview
        val taskIds = items.map { it.jsonObject["id"]?.jsonPrimitive?.content }
        assertTrue(taskIds.contains(taskA1.id.toString()), "Should include task A1")
        assertTrue(taskIds.contains(orphanTask.id.toString()), "Should include orphan task")
    }

    @Test
    fun `should handle mixed dependency types correctly in statistics`() = runBlocking {
        // Create dependencies with different types
        createDependency(taskA1.id, taskA2.id, "BLOCKS")
        createDependency(taskA2.id, taskA3.id, "IS_BLOCKED_BY")
        createDependency(taskA3.id, taskB1.id, "RELATES_TO")
        createDependency(taskB1.id, taskB2.id, "BLOCKS")

        // Verify all dependencies were created with correct types
        val allDeps = dependencyRepository.getAllDependencies()
        assertEquals(4, allDeps.size, "Should have 4 total dependencies")

        // Verify dependency types
        val a1ToA2 = allDeps.find { it.fromTaskId == taskA1.id && it.toTaskId == taskA2.id }
        assertNotNull(a1ToA2, "Should have A1->A2 dependency")
        assertEquals(DependencyType.BLOCKS, a1ToA2!!.type, "A1->A2 should be BLOCKS")

        val a2ToA3 = allDeps.find { it.fromTaskId == taskA2.id && it.toTaskId == taskA3.id }
        assertNotNull(a2ToA3, "Should have A2->A3 dependency")
        assertEquals(DependencyType.IS_BLOCKED_BY, a2ToA3!!.type, "A2->A3 should be IS_BLOCKED_BY")

        val a3ToB1 = allDeps.find { it.fromTaskId == taskA3.id && it.toTaskId == taskB1.id }
        assertNotNull(a3ToB1, "Should have A3->B1 dependency")
        assertEquals(DependencyType.RELATES_TO, a3ToB1!!.type, "A3->B1 should be RELATES_TO")

        val b1ToB2 = allDeps.find { it.fromTaskId == taskB1.id && it.toTaskId == taskB2.id }
        assertNotNull(b1ToB2, "Should have B1->B2 dependency")
        assertEquals(DependencyType.BLOCKS, b1ToB2!!.type, "B1->B2 should be BLOCKS")

        // Verify Feature A task count
        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("get"),
            "containerType" to JsonPrimitive("feature"),
            "id" to JsonPrimitive(featureA.id.toString())
        ))

        val response = queryContainerTool.execute(params, executionContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Feature retrieval should succeed")

        val taskCounts = responseObj["data"]?.jsonObject?.get("taskCounts")?.jsonObject
        assertEquals(3, taskCounts?.get("total")?.jsonPrimitive?.int, "Feature A should have 3 tasks")
    }

    @Test
    fun `should handle large dependency graphs efficiently`() = runBlocking {
        // Create a complex dependency graph with many connections
        val additionalTasks = mutableListOf<Task>()

        // Create 10 additional tasks for Feature A
        repeat(10) { i ->
            val task = Task(
                id = UUID.randomUUID(),
                title = "Additional Task A${i + 4}",
                summary = "Additional task for testing large graphs",
                status = TaskStatus.PENDING,
                featureId = featureA.id
            )
            additionalTasks.add(task)
            taskRepository.addTask(task)
        }

        // Create dependencies: each task depends on the previous one
        var previousTask = taskA1
        for (task in listOf(taskA2, taskA3) + additionalTasks) {
            createDependency(previousTask.id, task.id, "BLOCKS")
            previousTask = task
        }

        // Add some cross-connections
        createDependency(taskA2.id, additionalTasks[5].id, "RELATES_TO")
        createDependency(additionalTasks[3].id, taskB1.id, "BLOCKS")
        createDependency(additionalTasks[7].id, taskC1.id, "RELATES_TO")

        // Verify all dependencies were created
        val allDeps = dependencyRepository.getAllDependencies()
        val expectedDeps = 12 + 3  // 12 linear chain + 3 cross-connections
        assertEquals(expectedDeps, allDeps.size, "Should have $expectedDeps total dependencies")

        // Verify Feature A has correct task count
        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("get"),
            "containerType" to JsonPrimitive("feature"),
            "id" to JsonPrimitive(featureA.id.toString())
        ))

        val response = queryContainerTool.execute(params, executionContext)
        val responseObj = response as JsonObject

        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Large feature retrieval should succeed")

        val taskCounts = responseObj["data"]?.jsonObject?.get("taskCounts")?.jsonObject
        assertNotNull(taskCounts, "Task counts should be present")
        assertEquals(13, taskCounts!!["total"]?.jsonPrimitive?.int, "Feature A should have 13 tasks (3 original + 10 additional)")

        // Verify cross-feature dependencies exist
        assertTrue(allDeps.any { it.fromTaskId == additionalTasks[3].id && it.toTaskId == taskB1.id }, "Should have cross-feature dep to B1")
        assertTrue(allDeps.any { it.fromTaskId == additionalTasks[7].id && it.toTaskId == taskC1.id }, "Should have cross-feature dep to C1")

        // Verify linear chain dependencies
        assertTrue(allDeps.any { it.fromTaskId == taskA1.id && it.toTaskId == taskA2.id }, "Should have A1->A2")
        assertTrue(allDeps.any { it.fromTaskId == taskA2.id && it.toTaskId == taskA3.id }, "Should have A2->A3")
    }

    @Test
    fun `should maintain dependency statistics accuracy when dependencies are deleted`() = runBlocking {
        // Create initial dependencies
        createDependency(taskA1.id, taskA2.id, "BLOCKS")
        createDependency(taskA2.id, taskA3.id, "BLOCKS")
        createDependency(taskA1.id, taskB1.id, "RELATES_TO")

        // Verify initial state
        var allDeps = dependencyRepository.getAllDependencies()
        assertEquals(3, allDeps.size, "Should initially have 3 dependencies")

        // Verify specific dependencies exist
        val a1ToA2Initial = allDeps.find { it.fromTaskId == taskA1.id && it.toTaskId == taskA2.id }
        assertNotNull(a1ToA2Initial, "Should have A1->A2 initially")

        val a2ToA3Initial = allDeps.find { it.fromTaskId == taskA2.id && it.toTaskId == taskA3.id }
        assertNotNull(a2ToA3Initial, "Should have A2->A3 initially")

        val a1ToB1Initial = allDeps.find { it.fromTaskId == taskA1.id && it.toTaskId == taskB1.id }
        assertNotNull(a1ToB1Initial, "Should have A1->B1 initially")

        // Delete one dependency (A1->A2)
        val dependency = allDeps.first {
            it.fromTaskId == taskA1.id && it.toTaskId == taskA2.id
        }
        dependencyRepository.delete(dependency.id)

        // Verify dependency was deleted
        allDeps = dependencyRepository.getAllDependencies()
        assertEquals(2, allDeps.size, "Should have 2 dependencies after deletion")

        // Verify correct dependency was deleted
        val a1ToA2After = allDeps.find { it.fromTaskId == taskA1.id && it.toTaskId == taskA2.id }
        assertNull(a1ToA2After, "A1->A2 should be deleted")

        // Verify remaining dependencies still exist
        val a2ToA3After = allDeps.find { it.fromTaskId == taskA2.id && it.toTaskId == taskA3.id }
        assertNotNull(a2ToA3After, "A2->A3 should still exist")

        val a1ToB1After = allDeps.find { it.fromTaskId == taskA1.id && it.toTaskId == taskB1.id }
        assertNotNull(a1ToB1After, "A1->B1 should still exist")

        // Verify task count is unchanged
        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("get"),
            "containerType" to JsonPrimitive("feature"),
            "id" to JsonPrimitive(featureA.id.toString())
        ))
        val response = queryContainerTool.execute(params, executionContext) as JsonObject
        val taskCounts = response["data"]?.jsonObject?.get("taskCounts")?.jsonObject
        assertEquals(3, taskCounts?.get("total")?.jsonPrimitive?.int, "Feature A should still have 3 tasks")
    }

    private suspend fun createDependency(fromTaskId: UUID, toTaskId: UUID, type: String) {
        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(fromTaskId.toString()),
            "toTaskId" to JsonPrimitive(toTaskId.toString()),
            "type" to JsonPrimitive(type)
        ))

        val response = manageDependencyTool.execute(params, executionContext)
        val responseObj = response as JsonObject

        assertTrue(
            responseObj["success"]?.jsonPrimitive?.boolean == true,
            "Dependency creation should succeed: ${fromTaskId} -> ${toTaskId} ($type)"
        )
    }
}