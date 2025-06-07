package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.dependency.CreateDependencyTool
import io.github.jpicklyk.mcptask.application.tools.feature.CreateFeatureTool
import io.github.jpicklyk.mcptask.application.tools.feature.GetFeatureTool
import io.github.jpicklyk.mcptask.application.tools.task.CreateTaskTool
import io.github.jpicklyk.mcptask.application.tools.task.GetTaskOverviewTool
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
    private lateinit var createTaskTool: CreateTaskTool
    private lateinit var createFeatureTool: CreateFeatureTool
    private lateinit var createDependencyTool: CreateDependencyTool
    private lateinit var getFeatureTool: GetFeatureTool
    private lateinit var getTaskOverviewTool: GetTaskOverviewTool
    
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
        createTaskTool = CreateTaskTool()
        createFeatureTool = CreateFeatureTool()
        createDependencyTool = CreateDependencyTool()
        getFeatureTool = GetFeatureTool()
        getTaskOverviewTool = GetTaskOverviewTool()
        
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
        
        // Get feature with dependency information
        val params = JsonObject(mapOf(
            "id" to JsonPrimitive(featureA.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true)
        ))
        
        val response = getFeatureTool.execute(params, executionContext)
        val responseObj = response as JsonObject
        
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Feature retrieval should succeed")
        
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")
        
        val tasks = data!!["tasks"]?.jsonObject
        assertNotNull(tasks, "Tasks should be included")
        
        val dependencyStatistics = tasks!!["dependencyStatistics"]?.jsonObject
        assertNotNull(dependencyStatistics, "Dependency statistics should be included")
        
        // Verify overall feature statistics
        // Note: Dependencies are counted per task, so A1->A2 and A2->A3 means:
        // - A1 has 1 outgoing (A1->A2)
        // - A2 has 1 incoming (A1->A2) + 1 outgoing (A2->A3)
        // - A3 has 1 incoming (A2->A3)
        // Total = 1 + 2 + 1 = 4 dependencies counted
        assertEquals(4, dependencyStatistics!!["totalDependencies"]?.jsonPrimitive?.int, "Should have 4 total dependency connections")
        assertEquals(2, dependencyStatistics["totalOutgoingDependencies"]?.jsonPrimitive?.int, "Should have 2 outgoing dependencies")
        assertEquals(2, dependencyStatistics["totalIncomingDependencies"]?.jsonPrimitive?.int, "Should have 2 incoming dependencies")
        assertEquals(3, dependencyStatistics["tasksWithDependencies"]?.jsonPrimitive?.int, "All 3 tasks should have dependencies")
        
        // Verify individual task dependency counts
        val taskItems = tasks["items"]?.jsonArray
        assertNotNull(taskItems, "Task items should be present")
        assertEquals(3, taskItems!!.size, "Should have 3 tasks")
        
        // Find specific tasks and verify their dependency counts
        val taskA1Data = taskItems.find { 
            it.jsonObject["id"]?.jsonPrimitive?.content == taskA1.id.toString() 
        }?.jsonObject
        assertNotNull(taskA1Data, "Task A1 should be present")
        
        val taskA1Deps = taskA1Data!!["dependencies"]?.jsonObject?.get("counts")?.jsonObject
        assertNotNull(taskA1Deps, "Task A1 dependencies should be present")
        assertEquals(1, taskA1Deps!!["outgoing"]?.jsonPrimitive?.int, "Task A1 should have 1 outgoing dependency")
        assertEquals(0, taskA1Deps["incoming"]?.jsonPrimitive?.int, "Task A1 should have 0 incoming dependencies")
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
        
        // Get Feature A with dependency information
        val paramsA = JsonObject(mapOf(
            "id" to JsonPrimitive(featureA.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true)
        ))
        
        val responseA = getFeatureTool.execute(paramsA, executionContext)
        val responseObjA = responseA as JsonObject
        
        assertTrue(responseObjA["success"]?.jsonPrimitive?.boolean == true, "Feature A retrieval should succeed")
        
        val dataA = responseObjA["data"]?.jsonObject?.get("tasks")?.jsonObject
        val depStatsA = dataA?.get("dependencyStatistics")?.jsonObject
        
        assertNotNull(depStatsA, "Feature A dependency statistics should be present")
        // Feature A: A1->B1 and A3->B2, so A1 has 1 outgoing, A3 has 1 outgoing = 2 total
        assertEquals(2, depStatsA!!["totalDependencies"]?.jsonPrimitive?.int, "Feature A should have 2 dependencies")
        assertEquals(2, depStatsA["totalOutgoingDependencies"]?.jsonPrimitive?.int, "Feature A should have 2 outgoing dependencies")
        assertEquals(0, depStatsA["totalIncomingDependencies"]?.jsonPrimitive?.int, "Feature A should have 0 incoming dependencies")
        
        // Get Feature B with dependency information
        val paramsB = JsonObject(mapOf(
            "id" to JsonPrimitive(featureB.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true)
        ))
        
        val responseB = getFeatureTool.execute(paramsB, executionContext)
        val responseObjB = responseB as JsonObject
        
        assertTrue(responseObjB["success"]?.jsonPrimitive?.boolean == true, "Feature B retrieval should succeed")
        
        val dataB = responseObjB["data"]?.jsonObject?.get("tasks")?.jsonObject
        val depStatsB = dataB?.get("dependencyStatistics")?.jsonObject
        
        assertNotNull(depStatsB, "Feature B dependency statistics should be present")
        // Feature B: B1 has 1 incoming (A1->B1) + 1 outgoing (B1->C1), B2 has 1 incoming (A3->B2) = 3 total
        assertEquals(3, depStatsB!!["totalDependencies"]?.jsonPrimitive?.int, "Feature B should have 3 dependencies")
        assertEquals(1, depStatsB["totalOutgoingDependencies"]?.jsonPrimitive?.int, "Feature B should have 1 outgoing dependency")
        assertEquals(2, depStatsB["totalIncomingDependencies"]?.jsonPrimitive?.int, "Feature B should have 2 incoming dependencies")
        
        // Get Feature C with dependency information
        val paramsC = JsonObject(mapOf(
            "id" to JsonPrimitive(featureC.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true)
        ))
        
        val responseC = getFeatureTool.execute(paramsC, executionContext)
        val responseObjC = responseC as JsonObject
        
        assertTrue(responseObjC["success"]?.jsonPrimitive?.boolean == true, "Feature C retrieval should succeed")
        
        val dataC = responseObjC["data"]?.jsonObject?.get("tasks")?.jsonObject
        val depStatsC = dataC?.get("dependencyStatistics")?.jsonObject
        
        assertNotNull(depStatsC, "Feature C dependency statistics should be present")
        assertEquals(1, depStatsC!!["totalDependencies"]?.jsonPrimitive?.int, "Feature C should have 1 dependency")
        assertEquals(0, depStatsC["totalOutgoingDependencies"]?.jsonPrimitive?.int, "Feature C should have 0 outgoing dependencies")
        assertEquals(1, depStatsC["totalIncomingDependencies"]?.jsonPrimitive?.int, "Feature C should have 1 incoming dependency")
    }

    @Test
    fun `should handle feature with no dependencies correctly`() = runBlocking {
        // Feature C has no dependencies initially
        val params = JsonObject(mapOf(
            "id" to JsonPrimitive(featureC.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true)
        ))
        
        val response = getFeatureTool.execute(params, executionContext)
        val responseObj = response as JsonObject
        
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Feature retrieval should succeed")
        
        val data = responseObj["data"]?.jsonObject?.get("tasks")?.jsonObject
        val depStats = data?.get("dependencyStatistics")?.jsonObject
        
        assertNotNull(depStats, "Dependency statistics should be present even with no dependencies")
        assertEquals(0, depStats!!["totalDependencies"]?.jsonPrimitive?.int, "Should have 0 total dependencies")
        assertEquals(0, depStats["totalOutgoingDependencies"]?.jsonPrimitive?.int, "Should have 0 outgoing dependencies")
        assertEquals(0, depStats["totalIncomingDependencies"]?.jsonPrimitive?.int, "Should have 0 incoming dependencies")
        assertEquals(0, depStats["tasksWithDependencies"]?.jsonPrimitive?.int, "Should have 0 tasks with dependencies")
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
            "id" to JsonPrimitive(emptyFeature.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true)
        ))
        
        val response = getFeatureTool.execute(params, executionContext)
        val responseObj = response as JsonObject
        
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Empty feature retrieval should succeed")
        
        val data = responseObj["data"]?.jsonObject?.get("tasks")?.jsonObject
        assertNotNull(data, "Tasks section should be present")
        
        assertEquals(0, data!!["total"]?.jsonPrimitive?.int, "Should have 0 total tasks")
        assertEquals(0, data["included"]?.jsonPrimitive?.int, "Should have 0 included tasks")
        assertEquals(false, data["hasMore"]?.jsonPrimitive?.boolean, "Should not have more tasks")
        
        val depStats = data["dependencyStatistics"]?.jsonObject
        assertNotNull(depStats, "Dependency statistics should be present")
        assertEquals(0, depStats!!["totalDependencies"]?.jsonPrimitive?.int, "Should have 0 dependencies")
        assertEquals(0, depStats["tasksWithDependencies"]?.jsonPrimitive?.int, "Should have 0 tasks with dependencies")
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
            "summaryLength" to JsonPrimitive(50)
        ))
        
        val response = getTaskOverviewTool.execute(params, executionContext)
        val responseObj = response as JsonObject
        
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Task overview should succeed")
        
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")
        
        val features = data!!["features"]?.jsonArray
        val orphanedTasks = data["orphanedTasks"]?.jsonArray
        val counts = data["counts"]?.jsonObject
        
        assertNotNull(features, "Features should be present")
        assertNotNull(orphanedTasks, "Orphaned tasks should be present")
        assertNotNull(counts, "Counts should be present")
        
        // Verify basic counts
        assertEquals(3, counts!!["features"]?.jsonPrimitive?.int, "Should have 3 features")
        assertEquals(7, counts["tasks"]?.jsonPrimitive?.int, "Should have 7 total tasks")
        assertEquals(1, counts["orphanedTasks"]?.jsonPrimitive?.int, "Should have 1 orphaned task")
        
        // Verify each feature has the correct tasks
        assertEquals(3, features!!.size, "Should have 3 features in overview")
        
        val featureAData = features.find { 
            it.jsonObject["id"]?.jsonPrimitive?.content == featureA.id.toString() 
        }?.jsonObject
        assertNotNull(featureAData, "Feature A should be present")
        
        val featureATasks = featureAData!!["tasks"]?.jsonArray
        assertNotNull(featureATasks, "Feature A tasks should be present")
        assertEquals(3, featureATasks!!.size, "Feature A should have 3 tasks")
        
        // Verify orphaned task is listed separately
        assertEquals(1, orphanedTasks!!.size, "Should have 1 orphaned task")
        val orphanTaskData = orphanedTasks[0].jsonObject
        assertEquals(orphanTask.id.toString(), orphanTaskData["id"]?.jsonPrimitive?.content, "Orphaned task ID should match")
    }

    @Test
    fun `should handle mixed dependency types correctly in statistics`() = runBlocking {
        // Create dependencies with different types
        createDependency(taskA1.id, taskA2.id, "BLOCKS")
        createDependency(taskA2.id, taskA3.id, "IS_BLOCKED_BY")
        createDependency(taskA3.id, taskB1.id, "RELATES_TO")
        createDependency(taskB1.id, taskB2.id, "BLOCKS")
        
        val params = JsonObject(mapOf(
            "id" to JsonPrimitive(featureA.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true)
        ))
        
        val response = getFeatureTool.execute(params, executionContext)
        val responseObj = response as JsonObject
        
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Feature retrieval should succeed")
        
        val data = responseObj["data"]?.jsonObject?.get("tasks")?.jsonObject
        val depStats = data?.get("dependencyStatistics")?.jsonObject
        
        assertNotNull(depStats, "Dependency statistics should be present")
        
        // Should count all dependency types
        // A1->A2 (BLOCKS), A2->A3 (IS_BLOCKED_BY), A3->B1 (RELATES_TO)
        // A1: 1 outgoing, A2: 1 incoming + 1 outgoing, A3: 1 incoming + 1 outgoing = 5 total
        assertEquals(5, depStats!!["totalDependencies"]?.jsonPrimitive?.int, "Should count all dependency types")
        assertEquals(3, depStats["totalOutgoingDependencies"]?.jsonPrimitive?.int, "Should have 3 outgoing dependencies")
        assertEquals(2, depStats["totalIncomingDependencies"]?.jsonPrimitive?.int, "Should have 2 incoming dependencies")
        assertEquals(3, depStats["tasksWithDependencies"]?.jsonPrimitive?.int, "All tasks should have dependencies")
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
        
        val params = JsonObject(mapOf(
            "id" to JsonPrimitive(featureA.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true),
            "maxTaskCount" to JsonPrimitive(20)  // Include all tasks
        ))
        
        val response = getFeatureTool.execute(params, executionContext)
        val responseObj = response as JsonObject
        
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Large feature retrieval should succeed")
        
        val data = responseObj["data"]?.jsonObject?.get("tasks")?.jsonObject
        val depStats = data?.get("dependencyStatistics")?.jsonObject
        
        assertNotNull(depStats, "Dependency statistics should be present")
        
        // Verify statistics for large graph
        val totalDeps = depStats!!["totalDependencies"]?.jsonPrimitive?.int
        val outgoingDeps = depStats["totalOutgoingDependencies"]?.jsonPrimitive?.int
        val incomingDeps = depStats["totalIncomingDependencies"]?.jsonPrimitive?.int
        val tasksWithDeps = depStats["tasksWithDependencies"]?.jsonPrimitive?.int
        
        assertNotNull(totalDeps, "Total dependencies should be present")
        assertNotNull(outgoingDeps, "Outgoing dependencies should be present")
        assertNotNull(incomingDeps, "Incoming dependencies should be present")
        assertNotNull(tasksWithDeps, "Tasks with dependencies should be present")
        
        // Should handle the linear chain plus cross-connections
        assertTrue(totalDeps!! >= 14, "Should have at least 14 dependencies (12 linear + 2 cross)")
        assertTrue(tasksWithDeps!! >= 12, "Most tasks should have dependencies")
        assertEquals(totalDeps, outgoingDeps!! + incomingDeps!!, "Total should equal outgoing + incoming")
    }

    @Test
    fun `should maintain dependency statistics accuracy when dependencies are deleted`() = runBlocking {
        // Create initial dependencies
        createDependency(taskA1.id, taskA2.id, "BLOCKS")
        createDependency(taskA2.id, taskA3.id, "BLOCKS")
        createDependency(taskA1.id, taskB1.id, "RELATES_TO")
        
        // Get initial statistics
        val initialParams = JsonObject(mapOf(
            "id" to JsonPrimitive(featureA.id.toString()),
            "includeTasks" to JsonPrimitive(true),
            "includeTaskDependencies" to JsonPrimitive(true)
        ))
        
        val initialResponse = getFeatureTool.execute(initialParams, executionContext)
        val initialResponseObj = initialResponse as JsonObject
        val initialDepStats = initialResponseObj["data"]?.jsonObject
            ?.get("tasks")?.jsonObject
            ?.get("dependencyStatistics")?.jsonObject
        
        assertNotNull(initialDepStats, "Initial dependency statistics should be present")
        // A1->A2, A2->A3, A1->B1: A1 has 2 outgoing, A2 has 1 incoming + 1 outgoing, A3 has 1 incoming = 5 total
        assertEquals(5, initialDepStats!!["totalDependencies"]?.jsonPrimitive?.int, "Should initially have 5 dependency connections")
        
        // Delete one dependency
        val dependency = dependencyRepository.getAllDependencies().first { 
            it.fromTaskId == taskA1.id && it.toTaskId == taskA2.id 
        }
        dependencyRepository.delete(dependency.id)
        
        // Get updated statistics
        val updatedResponse = getFeatureTool.execute(initialParams, executionContext)
        val updatedResponseObj = updatedResponse as JsonObject
        val updatedDepStats = updatedResponseObj["data"]?.jsonObject
            ?.get("tasks")?.jsonObject
            ?.get("dependencyStatistics")?.jsonObject
        
        assertNotNull(updatedDepStats, "Updated dependency statistics should be present")
        // After deleting A1->A2: A2->A3, A1->B1: A1 has 1 outgoing, A2 has 1 outgoing, A3 has 1 incoming = 3 total
        assertEquals(3, updatedDepStats!!["totalDependencies"]?.jsonPrimitive?.int, "Should have 3 dependencies after deletion")
        assertEquals(2, updatedDepStats["totalOutgoingDependencies"]?.jsonPrimitive?.int, "Should have 2 outgoing dependencies")
        assertEquals(1, updatedDepStats["totalIncomingDependencies"]?.jsonPrimitive?.int, "Should have 1 incoming dependency")
    }

    private suspend fun createDependency(fromTaskId: UUID, toTaskId: UUID, type: String) {
        val params = JsonObject(mapOf(
            "fromTaskId" to JsonPrimitive(fromTaskId.toString()),
            "toTaskId" to JsonPrimitive(toTaskId.toString()),
            "type" to JsonPrimitive(type)
        ))
        
        val response = createDependencyTool.execute(params, executionContext)
        val responseObj = response as JsonObject
        
        assertTrue(
            responseObj["success"]?.jsonPrimitive?.boolean == true,
            "Dependency creation should succeed: ${fromTaskId} -> ${toTaskId} ($type)"
        )
    }
}