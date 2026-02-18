package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.tools.QueryContainerTool
import io.github.jpicklyk.mcptask.application.tools.task.GetNextTaskTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.time.Instant
import java.util.*

/**
 * Comprehensive integration tests for parallel task processing feature.
 * Tests the orchestration logic used by Feature Manager for batch task recommendations.
 *
 * Coverage:
 * - Scenario 1: Basic Parallel Batch (2-5 Tasks)
 * - Scenario 2: Dependency Filtering
 * - Scenario 3: CONTINUE Mode - Incremental Batching
 * - Scenario 4: State Refresh Logic
 * - Scenario 5: Response Format Validation
 * - Scenario 6: Edge Cases
 * - Scenario 7: Integration with get_next_task Tool
 * - Scenario 8: Performance and Token Efficiency
 */
@DisplayName("Parallel Task Processing Integration Tests")
class ParallelTaskProcessingIntegrationTest {
    private lateinit var getNextTaskTool: GetNextTaskTool
    private lateinit var queryContainerTool: QueryContainerTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    @BeforeEach
    fun setup() {
        getNextTaskTool = GetNextTaskTool()
        queryContainerTool = QueryContainerTool()

        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockFeatureRepository = mockk<FeatureRepository>()
        mockDependencyRepository = mockk<DependencyRepository>()
        mockSectionRepository = mockk<SectionRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository

        context = ToolExecutionContext(mockRepositoryProvider)
    }

    // ========== Scenario 1: Basic Parallel Batch (2-5 Tasks) ==========

    @Nested
    @DisplayName("Scenario 1: Basic Parallel Batch")
    inner class BasicParallelBatchTests {

        @Test
        @DisplayName("START mode recommends batch of 3 unblocked tasks")
        fun testStartModeRecommendsBatchOfThreeTasks() = runBlocking {
            val featureId = UUID.randomUUID()

            // Create 5 tasks: T1, T2, T3 unblocked; T4, T5 blocked
            val t1 = createTask("T1", TaskStatus.PENDING, Priority.HIGH, 3)
            val t2 = createTask("T2", TaskStatus.PENDING, Priority.HIGH, 5)
            val t3 = createTask("T3", TaskStatus.PENDING, Priority.MEDIUM, 4)
            val t4 = createTask("T4", TaskStatus.PENDING, Priority.HIGH, 6)
            val t5 = createTask("T5", TaskStatus.PENDING, Priority.MEDIUM, 7)

            // T4 blocked by T1, T5 blocked by T2
            val dep1 = Dependency(fromTaskId = t1.id, toTaskId = t4.id, type = DependencyType.BLOCKS)
            val dep2 = Dependency(fromTaskId = t2.id, toTaskId = t5.id, type = DependencyType.BLOCKS)

            // Mock repository responses
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1, t2, t3, t4, t5))
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t2.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t3.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t4.id) } returns listOf(dep1)
            coEvery { mockDependencyRepository.findByToTaskId(t5.id) } returns listOf(dep2)
            coEvery { mockTaskRepository.getById(t1.id) } returns Result.Success(t1)
            coEvery { mockTaskRepository.getById(t2.id) } returns Result.Success(t2)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            // Verify response structure
            val responseObj = result as JsonObject
            assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true)

            val data = responseObj["data"]?.jsonObject
            assertNotNull(data)

            val recommendations = data!!["recommendations"]?.jsonArray
            assertNotNull(recommendations)
            assertEquals(3, recommendations!!.size, "Should recommend exactly 3 unblocked tasks")

            // Verify all recommended tasks are unblocked (T1, T2, T3)
            val taskIds = recommendations.map { it.jsonObject["taskId"]?.jsonPrimitive?.content }
            assertTrue(taskIds.contains(t1.id.toString()))
            assertTrue(taskIds.contains(t2.id.toString()))
            assertTrue(taskIds.contains(t3.id.toString()))
            assertFalse(taskIds.contains(t4.id.toString()), "T4 should be filtered (blocked)")
            assertFalse(taskIds.contains(t5.id.toString()), "T5 should be filtered (blocked)")

            // Verify sorting: High priority tasks first (T1, T2), then complexity
            val firstTask = recommendations[0].jsonObject
            assertEquals("high", firstTask["priority"]?.jsonPrimitive?.content)
            assertEquals(3, firstTask["complexity"]?.jsonPrimitive?.int)
        }

        @Test
        @DisplayName("START mode with full batch of 5 tasks")
        fun testFullBatchOfFiveTasks() = runBlocking {
            val featureId = UUID.randomUUID()

            // Create 8 tasks, 5 unblocked
            val tasks = (1..8).map { i ->
                createTask("Task $i", TaskStatus.PENDING, Priority.MEDIUM, i)
            }

            // All tasks unblocked
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks)
            tasks.forEach { task ->
                coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()
            }

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(5, recommendations.size, "Should respect limit of 5")
            assertEquals(8, data["totalCandidates"]?.jsonPrimitive?.int, "Should report all 8 candidates")

            // Verify all recommended tasks are pending
            recommendations.forEach { rec ->
                val task = tasks.find { it.id.toString() == rec.jsonObject["taskId"]?.jsonPrimitive?.content }
                assertNotNull(task)
                assertEquals(TaskStatus.PENDING, task!!.status)
            }
        }

        @Test
        @DisplayName("Single task scenario handled correctly")
        fun testSingleTaskScenario() = runBlocking {
            val featureId = UUID.randomUUID()
            val singleTask = createTask("Only Task", TaskStatus.PENDING, Priority.HIGH, 5)

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(singleTask))
            coEvery { mockDependencyRepository.findByToTaskId(singleTask.id) } returns emptyList()

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(1, recommendations.size, "Should return batch of 1")
            assertEquals(singleTask.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }
    }

    // ========== Scenario 2: Dependency Filtering ==========

    @Nested
    @DisplayName("Scenario 2: Dependency Filtering")
    inner class DependencyFilteringTests {

        @Test
        @DisplayName("Blocked tasks filtered automatically")
        fun testBlockedTasksFiltered() = runBlocking {
            val featureId = UUID.randomUUID()

            val t1 = createTask("T1", TaskStatus.PENDING, Priority.HIGH, 5)
            val t2 = createTask("T2", TaskStatus.PENDING, Priority.HIGH, 6)
            val t3 = createTask("T3", TaskStatus.PENDING, Priority.HIGH, 7)

            // T2 blocked by T1, T3 blocked by both T1 and T2
            val dep1 = Dependency(fromTaskId = t1.id, toTaskId = t2.id, type = DependencyType.BLOCKS)
            val dep2 = Dependency(fromTaskId = t1.id, toTaskId = t3.id, type = DependencyType.BLOCKS)
            val dep3 = Dependency(fromTaskId = t2.id, toTaskId = t3.id, type = DependencyType.BLOCKS)

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1, t2, t3))
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t2.id) } returns listOf(dep1)
            coEvery { mockDependencyRepository.findByToTaskId(t3.id) } returns listOf(dep2, dep3)
            coEvery { mockTaskRepository.getById(t1.id) } returns Result.Success(t1)
            coEvery { mockTaskRepository.getById(t2.id) } returns Result.Success(t2)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(1, recommendations.size, "Only T1 should be unblocked")
            assertEquals(t1.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }

        @Test
        @DisplayName("Parallel tasks with no mutual dependencies")
        fun testParallelTasksNoMutualDependencies() = runBlocking {
            val featureId = UUID.randomUUID()

            val t0 = createTask("T0", TaskStatus.COMPLETED, Priority.HIGH, 5)
            val t1 = createTask("T1", TaskStatus.PENDING, Priority.HIGH, 3)
            val t2 = createTask("T2", TaskStatus.PENDING, Priority.HIGH, 4)
            val t3 = createTask("T3", TaskStatus.PENDING, Priority.MEDIUM, 5)
            val t4 = createTask("T4", TaskStatus.PENDING, Priority.MEDIUM, 6)

            // T2 and T4 depend on T0 (completed), so they're unblocked
            // T1 and T3 have no dependencies
            val dep1 = Dependency(fromTaskId = t0.id, toTaskId = t2.id, type = DependencyType.BLOCKS)
            val dep2 = Dependency(fromTaskId = t0.id, toTaskId = t4.id, type = DependencyType.BLOCKS)

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t0, t1, t2, t3, t4))
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t2.id) } returns listOf(dep1)
            coEvery { mockDependencyRepository.findByToTaskId(t3.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t4.id) } returns listOf(dep2)
            coEvery { mockTaskRepository.getById(t0.id) } returns Result.Success(t0)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(4, recommendations.size, "All 4 tasks can run in parallel (T0 is completed)")

            val taskIds = recommendations.map { it.jsonObject["taskId"]?.jsonPrimitive?.content }
            assertTrue(taskIds.contains(t1.id.toString()))
            assertTrue(taskIds.contains(t2.id.toString()))
            assertTrue(taskIds.contains(t3.id.toString()))
            assertTrue(taskIds.contains(t4.id.toString()))
        }

        @Test
        @DisplayName("Diamond dependency pattern resolves correctly")
        fun testDiamondDependencyPattern() = runBlocking {
            val featureId = UUID.randomUUID()

            val t1 = createTask("T1", TaskStatus.PENDING, Priority.HIGH, 5)
            val t2 = createTask("T2", TaskStatus.PENDING, Priority.HIGH, 6)
            val t3 = createTask("T3", TaskStatus.PENDING, Priority.HIGH, 7)
            val t4 = createTask("T4", TaskStatus.PENDING, Priority.HIGH, 8)

            // Diamond: T1 -> T2, T1 -> T3, T2 -> T4, T3 -> T4
            val dep1 = Dependency(fromTaskId = t1.id, toTaskId = t2.id, type = DependencyType.BLOCKS)
            val dep2 = Dependency(fromTaskId = t1.id, toTaskId = t3.id, type = DependencyType.BLOCKS)
            val dep3 = Dependency(fromTaskId = t2.id, toTaskId = t4.id, type = DependencyType.BLOCKS)
            val dep4 = Dependency(fromTaskId = t3.id, toTaskId = t4.id, type = DependencyType.BLOCKS)

            // Phase 1: Initial call, only T1 ready
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1, t2, t3, t4))
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t2.id) } returns listOf(dep1)
            coEvery { mockDependencyRepository.findByToTaskId(t3.id) } returns listOf(dep2)
            coEvery { mockDependencyRepository.findByToTaskId(t4.id) } returns listOf(dep3, dep4)
            coEvery { mockTaskRepository.getById(t1.id) } returns Result.Success(t1)
            coEvery { mockTaskRepository.getById(t2.id) } returns Result.Success(t2)
            coEvery { mockTaskRepository.getById(t3.id) } returns Result.Success(t3)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)
            val data = (result as JsonObject)["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(1, recommendations.size, "Only T1 should be ready initially")
            assertEquals(t1.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)

            // Phase 2: T1 completed, T2 and T3 should be unblocked
            val t1Completed = t1.copy(status = TaskStatus.COMPLETED)
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1Completed, t2, t3, t4))
            coEvery { mockTaskRepository.getById(t1.id) } returns Result.Success(t1Completed)

            val result2 = getNextTaskTool.execute(params, context)
            val data2 = (result2 as JsonObject)["data"]?.jsonObject
            val recommendations2 = data2!!["recommendations"]?.jsonArray!!

            assertEquals(2, recommendations2.size, "T2 and T3 should be unblocked")
            val taskIds = recommendations2.map { it.jsonObject["taskId"]?.jsonPrimitive?.content }
            assertTrue(taskIds.contains(t2.id.toString()))
            assertTrue(taskIds.contains(t3.id.toString()))

            // Phase 3: T2 and T3 completed, T4 should be unblocked
            val t2Completed = t2.copy(status = TaskStatus.COMPLETED)
            val t3Completed = t3.copy(status = TaskStatus.COMPLETED)
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1Completed, t2Completed, t3Completed, t4))
            coEvery { mockDependencyRepository.findByToTaskId(t4.id) } returns listOf(dep3, dep4)
            coEvery { mockTaskRepository.getById(t2.id) } returns Result.Success(t2Completed)
            coEvery { mockTaskRepository.getById(t3.id) } returns Result.Success(t3Completed)

            val result3 = getNextTaskTool.execute(params, context)
            val data3 = (result3 as JsonObject)["data"]?.jsonObject
            val recommendations3 = data3!!["recommendations"]?.jsonArray!!

            assertEquals(1, recommendations3.size, "T4 should be unblocked after T2 and T3 complete")
            assertEquals(t4.id.toString(), recommendations3[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }
    }

    // ========== Scenario 3: CONTINUE Mode - Incremental Batching ==========

    @Nested
    @DisplayName("Scenario 3: CONTINUE Mode Incremental Batching")
    inner class ContinueModeTests {

        @Test
        @DisplayName("INCREMENTAL_BATCH mode with available capacity")
        fun testIncrementalBatchMode() = runBlocking {
            val featureId = UUID.randomUUID()

            // 5 tasks total: 2 in-progress, 1 completed, 2 newly unblocked
            val t1 = createTask("T1", TaskStatus.COMPLETED, Priority.HIGH, 5)
            val t2 = createTask("T2", TaskStatus.IN_PROGRESS, Priority.HIGH, 6)
            val t3 = createTask("T3", TaskStatus.IN_PROGRESS, Priority.MEDIUM, 7)
            val t4 = createTask("T4", TaskStatus.PENDING, Priority.HIGH, 4)
            val t5 = createTask("T5", TaskStatus.PENDING, Priority.MEDIUM, 3)

            // T4 and T5 were blocked by T1, now unblocked
            val dep1 = Dependency(fromTaskId = t1.id, toTaskId = t4.id, type = DependencyType.BLOCKS)
            val dep2 = Dependency(fromTaskId = t1.id, toTaskId = t5.id, type = DependencyType.BLOCKS)

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1, t2, t3, t4, t5))
            // Mock dependencies for all tasks
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t2.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t3.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t4.id) } returns listOf(dep1)
            coEvery { mockDependencyRepository.findByToTaskId(t5.id) } returns listOf(dep2)
            coEvery { mockTaskRepository.getById(t1.id) } returns Result.Success(t1)

            // Simulating CONTINUE mode: limit based on available capacity
            // in_flight_count = 2 (T2, T3), available_capacity = 5 - 2 = 3
            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(3) // Available capacity
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(2, recommendations.size, "Should recommend 2 newly unblocked tasks")

            val taskIds = recommendations.map { it.jsonObject["taskId"]?.jsonPrimitive?.content }
            assertTrue(taskIds.contains(t4.id.toString()))
            assertTrue(taskIds.contains(t5.id.toString()))

            // Verify mode can be determined from response
            // Feature Manager would see: 2 pending tasks available, 2 in-progress
            // This indicates INCREMENTAL_BATCH mode
        }

        @Test
        @DisplayName("WAITING mode when at capacity")
        fun testWaitingMode() = runBlocking {
            val featureId = UUID.randomUUID()

            // 5 tasks in-progress, at max capacity
            val tasks = (1..5).map { i ->
                createTask("Task $i", TaskStatus.IN_PROGRESS, Priority.MEDIUM, i)
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks)
            // Mock no dependencies for any task
            tasks.forEach { task ->
                coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()
            }

            // When at capacity, Feature Manager would call with limit=1 but get 0 results
            // (all tasks are in-progress, so no pending tasks available)
            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(1)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(0, recommendations.size, "Should return no recommendations when all tasks in-progress")

            // Feature Manager would detect WAITING mode:
            // - in_flight_count = 5 (count of IN_PROGRESS tasks)
            // - available_capacity = 0 (5 - 5)
            // - get_next_task returns 0 tasks (all are in-progress, none pending)
        }

        @Test
        @DisplayName("COMPLETE mode detection")
        fun testCompleteMode() = runBlocking {
            val featureId = UUID.randomUUID()

            // All 8 tasks completed
            val tasks = (1..8).map { i ->
                createTask("Task $i", TaskStatus.COMPLETED, Priority.MEDIUM, i)
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(0, recommendations.size, "No tasks to recommend")

            val message = responseObj["message"]?.jsonPrimitive?.content
            assertTrue(message!!.contains("No unblocked tasks available"))

            // Feature Manager would detect COMPLETE mode:
            // - in_flight_count = 0 (no in-progress tasks)
            // - get_next_task returned 0 tasks
            // - All tasks have status COMPLETED
        }

        @Test
        @DisplayName("BLOCKED mode (no tasks can proceed)")
        fun testBlockedMode() = runBlocking {
            val featureId = UUID.randomUUID()

            // 3 tasks pending, all blocked by circular dependencies
            val t1 = createTask("T1", TaskStatus.PENDING, Priority.HIGH, 5)
            val t2 = createTask("T2", TaskStatus.PENDING, Priority.HIGH, 6)
            val t3 = createTask("T3", TaskStatus.PENDING, Priority.HIGH, 7)

            // Circular: T1 -> T2 -> T3 -> T1 (invalid, but testing detection)
            val dep1 = Dependency(fromTaskId = t1.id, toTaskId = t2.id, type = DependencyType.BLOCKS)
            val dep2 = Dependency(fromTaskId = t2.id, toTaskId = t3.id, type = DependencyType.BLOCKS)
            val dep3 = Dependency(fromTaskId = t3.id, toTaskId = t1.id, type = DependencyType.BLOCKS)

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1, t2, t3))
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns listOf(dep3)
            coEvery { mockDependencyRepository.findByToTaskId(t2.id) } returns listOf(dep1)
            coEvery { mockDependencyRepository.findByToTaskId(t3.id) } returns listOf(dep2)
            coEvery { mockTaskRepository.getById(any()) } returns Result.Success(t1)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(0, recommendations.size, "No tasks should be unblocked in circular dependency")

            // Feature Manager would detect BLOCKED mode:
            // - in_flight_count = 0
            // - get_next_task returned 0 tasks
            // - Pending tasks still exist (3 tasks with status PENDING)
        }

        @Test
        @DisplayName("PARALLEL_BATCH mode (fresh batch after completion)")
        fun testParallelBatchMode() = runBlocking {
            val featureId = UUID.randomUUID()

            // Previous batch completed (not shown), 4 new tasks unblocked, 0 in-progress
            val tasks = (1..4).map { i ->
                createTask("Task $i", TaskStatus.PENDING, Priority.HIGH, i)
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks)
            tasks.forEach { task ->
                coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()
            }

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(4, recommendations.size, "Should return all 4 unblocked tasks")

            // Feature Manager would detect PARALLEL_BATCH mode:
            // - in_flight_count = 0 (no tasks in-progress)
            // - get_next_task returned 2+ tasks
            // - Fresh batch scenario
        }

        @Test
        @DisplayName("SEQUENTIAL mode (only 1 task ready)")
        fun testSequentialMode() = runBlocking {
            val featureId = UUID.randomUUID()

            val t1 = createTask("Only Ready Task", TaskStatus.PENDING, Priority.HIGH, 5)
            val t2 = createTask("Blocked Task 1", TaskStatus.PENDING, Priority.HIGH, 6)
            val t3 = createTask("Blocked Task 2", TaskStatus.PENDING, Priority.HIGH, 7)
            val t4 = createTask("Blocked Task 3", TaskStatus.PENDING, Priority.HIGH, 8)

            // Only T1 is unblocked
            val dep1 = Dependency(fromTaskId = t1.id, toTaskId = t2.id, type = DependencyType.BLOCKS)
            val dep2 = Dependency(fromTaskId = t1.id, toTaskId = t3.id, type = DependencyType.BLOCKS)
            val dep3 = Dependency(fromTaskId = t1.id, toTaskId = t4.id, type = DependencyType.BLOCKS)

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1, t2, t3, t4))
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t2.id) } returns listOf(dep1)
            coEvery { mockDependencyRepository.findByToTaskId(t3.id) } returns listOf(dep2)
            coEvery { mockDependencyRepository.findByToTaskId(t4.id) } returns listOf(dep3)
            coEvery { mockTaskRepository.getById(t1.id) } returns Result.Success(t1)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(1, recommendations.size, "Only 1 task should be ready")
            assertEquals(t1.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)

            // Feature Manager would detect SEQUENTIAL mode:
            // - get_next_task returned exactly 1 task
            // - Fall back to single-task workflow
        }
    }

    // ========== Scenario 4: State Refresh Logic ==========

    @Nested
    @DisplayName("Scenario 4: State Refresh Logic")
    inner class StateRefreshTests {

        @Test
        @DisplayName("Stateless operation - fresh query each call")
        fun testStatelessOperation() = runBlocking {
            val featureId = UUID.randomUUID()
            val feature = Feature(
                id = featureId,
                name = "Test Feature",
                description = "Test",
                summary = "",
                status = FeatureStatus.IN_DEVELOPMENT
            )

            val task1 = createTask("Task 1", TaskStatus.PENDING, Priority.HIGH, 5)
            val task2 = createTask("Task 2", TaskStatus.IN_PROGRESS, Priority.HIGH, 6)

            // Mock feature repository
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeature(featureId, any(), any(), any()) } returns
                Result.Success(listOf(task1, task2))
            coEvery { mockDependencyRepository.findByToTaskId(task1.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(task2.id) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns
                Result.Success(emptyList())

            // First call
            val params1 = JsonObject(mapOf(
                "operation" to JsonPrimitive("get"),
                "containerType" to JsonPrimitive("feature"),
                "id" to JsonPrimitive(featureId.toString()),
                "includeSections" to JsonPrimitive(false)
            ))
            val result1 = queryContainerTool.execute(params1, context)

            val responseObj1 = result1 as JsonObject
            val data1 = responseObj1["data"]?.jsonObject
            assertNotNull(data1)

            // Update state - mark task2 as completed
            val task2Completed = task2.copy(status = TaskStatus.COMPLETED)
            coEvery { mockTaskRepository.findByFeature(featureId, any(), any(), any()) } returns
                Result.Success(listOf(task1, task2Completed))

            // Second call - should reflect updated state
            val result2 = queryContainerTool.execute(params1, context)

            val responseObj2 = result2 as JsonObject
            val data2 = responseObj2["data"]?.jsonObject
            assertNotNull(data2)

            // Verify fresh state was retrieved (task2 should be completed)
            // This demonstrates stateless operation - no cached state
        }

        @Test
        @DisplayName("Task status as source of truth")
        fun testTaskStatusAsSourceOfTruth() = runBlocking {
            val featureId = UUID.randomUUID()

            val t1 = createTask("T1", TaskStatus.PENDING, Priority.HIGH, 5)

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1))
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns emptyList()

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            // First call - T1 is pending, should be recommended
            val result1 = getNextTaskTool.execute(params, context)
            val recommendations1 = (result1 as JsonObject)["data"]?.jsonObject!!["recommendations"]?.jsonArray!!
            assertEquals(1, recommendations1.size)

            // Update T1 to in-progress (simulating Task Manager assignment)
            val t1InProgress = t1.copy(status = TaskStatus.IN_PROGRESS)
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1InProgress))

            // Second call - T1 is in-progress, should NOT be recommended
            val result2 = getNextTaskTool.execute(params, context)
            val recommendations2 = (result2 as JsonObject)["data"]?.jsonObject!!["recommendations"]?.jsonArray!!
            assertEquals(0, recommendations2.size, "In-progress tasks should be filtered")

            // Status change is immediately reflected
        }
    }

    // ========== Scenario 6: Edge Cases ==========

    @Nested
    @DisplayName("Scenario 6: Edge Cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("Empty feature (no tasks)")
        fun testEmptyFeature() = runBlocking {
            val featureId = UUID.randomUUID()

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(emptyList())

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(0, recommendations.size)
            val message = responseObj["message"]?.jsonPrimitive?.content
            assertTrue(message!!.contains("No unblocked tasks available"))
        }

        @Test
        @DisplayName("All tasks completed")
        fun testAllTasksCompleted() = runBlocking {
            val featureId = UUID.randomUUID()

            val tasks = (1..3).map { i ->
                createTask("Task $i", TaskStatus.COMPLETED, Priority.MEDIUM, i)
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(0, recommendations.size)
        }

        @Test
        @DisplayName("All tasks cancelled")
        fun testAllTasksCancelled() = runBlocking {
            val featureId = UUID.randomUUID()

            val tasks = (1..3).map { i ->
                createTask("Task $i", TaskStatus.CANCELLED, Priority.MEDIUM, i)
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(0, recommendations.size)
        }

        @Test
        @DisplayName("Mixed status with partial completion")
        fun testMixedStatusPartialCompletion() = runBlocking {
            val featureId = UUID.randomUUID()

            // 10 tasks: 3 completed, 2 cancelled, 3 in-progress, 2 pending unblocked
            val tasks = mutableListOf<Task>()

            // 3 completed
            repeat(3) { i ->
                tasks.add(createTask("Completed $i", TaskStatus.COMPLETED, Priority.HIGH, i))
            }

            // 2 cancelled
            repeat(2) { i ->
                tasks.add(createTask("Cancelled $i", TaskStatus.CANCELLED, Priority.MEDIUM, i + 3))
            }

            // 3 in-progress
            repeat(3) { i ->
                tasks.add(createTask("InProgress $i", TaskStatus.IN_PROGRESS, Priority.HIGH, i + 5))
            }

            // 2 pending unblocked
            repeat(2) { i ->
                tasks.add(createTask("Pending $i", TaskStatus.PENDING, Priority.MEDIUM, i + 8))
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks)
            // Mock no dependencies for all tasks
            tasks.forEach { task ->
                coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()
            }

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(2, recommendations.size, "Should recommend 2 pending unblocked tasks")

            // Verify all recommendations are pending
            recommendations.forEach { rec ->
                val taskId = rec.jsonObject["taskId"]?.jsonPrimitive?.content
                val task = tasks.find { it.id.toString() == taskId }
                assertEquals(TaskStatus.PENDING, task!!.status)
            }
        }

        @Test
        @DisplayName("Large feature (50+ tasks)")
        fun testLargeFeature() = runBlocking {
            val featureId = UUID.randomUUID()

            // Create 50 tasks, 10 unblocked
            val tasks = (1..50).map { i ->
                createTask("Task $i", TaskStatus.PENDING, Priority.MEDIUM, i % 10)
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks)
            tasks.forEach { task ->
                coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()
            }

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            assertEquals(5, recommendations.size, "Should enforce limit of 5")
            assertEquals(50, data["totalCandidates"]?.jsonPrimitive?.int, "Should report all 50 candidates")
        }

        @Test
        @DisplayName("Rapid completion (all 5 tasks complete before next CONTINUE)")
        fun testRapidCompletion() = runBlocking {
            val featureId = UUID.randomUUID()

            // Initial state: 5 tasks in-progress
            val tasksInProgress = (1..5).map { i ->
                createTask("Task $i", TaskStatus.IN_PROGRESS, Priority.HIGH, i)
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasksInProgress)
            // Mock no dependencies for in-progress tasks
            tasksInProgress.forEach { task ->
                coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()
            }

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            // First call - all in-progress, expect 0 recommendations
            val result1 = getNextTaskTool.execute(params, context)
            val recommendations1 = (result1 as JsonObject)["data"]?.jsonObject!!["recommendations"]?.jsonArray!!
            assertEquals(0, recommendations1.size)

            // All complete simultaneously
            val tasksCompleted = tasksInProgress.map { it.copy(status = TaskStatus.COMPLETED) }

            // New batch available
            val newTasks = (6..8).map { i ->
                createTask("Task $i", TaskStatus.PENDING, Priority.HIGH, i)
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasksCompleted + newTasks)
            // Mock no dependencies for all tasks
            (tasksCompleted + newTasks).forEach { task ->
                coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()
            }

            // Second call - should return new batch
            val result2 = getNextTaskTool.execute(params, context)
            val recommendations2 = (result2 as JsonObject)["data"]?.jsonObject!!["recommendations"]?.jsonArray!!

            assertEquals(3, recommendations2.size, "Should recommend new batch")

            // Verify mode detection: PARALLEL_BATCH (in_flight_count = 0, new batch available)
        }
    }

    // ========== Scenario 7: Integration with get_next_task Tool ==========

    @Nested
    @DisplayName("Scenario 7: Integration with get_next_task")
    inner class GetNextTaskIntegrationTests {

        @Test
        @DisplayName("Automatic filtering behavior")
        fun testAutomaticFiltering() = runBlocking {
            val featureId = UUID.randomUUID()

            val t1 = createTask("Pending Unblocked", TaskStatus.PENDING, Priority.HIGH, 5)
            val t2 = createTask("In Progress", TaskStatus.IN_PROGRESS, Priority.HIGH, 6)
            val t3 = createTask("Completed", TaskStatus.COMPLETED, Priority.HIGH, 7)
            val t4 = createTask("Cancelled", TaskStatus.CANCELLED, Priority.HIGH, 8)
            val t5 = createTask("Pending Blocked", TaskStatus.PENDING, Priority.HIGH, 9)

            val dep = Dependency(fromTaskId = t2.id, toTaskId = t5.id, type = DependencyType.BLOCKS)

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(t1, t2, t3, t4, t5))
            // Mock dependencies for all tasks
            coEvery { mockDependencyRepository.findByToTaskId(t1.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t2.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t3.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t4.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(t5.id) } returns listOf(dep)
            coEvery { mockTaskRepository.getById(t2.id) } returns Result.Success(t2)

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            // Only T1 should be recommended (pending + unblocked)
            assertEquals(1, recommendations.size)
            assertEquals(t1.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }

        @Test
        @DisplayName("Priority and complexity sorting")
        fun testPriorityComplexitySorting() = runBlocking {
            val featureId = UUID.randomUUID()

            val tasks = listOf(
                createTask("High-Complex", TaskStatus.PENDING, Priority.HIGH, 7),
                createTask("High-Simple", TaskStatus.PENDING, Priority.HIGH, 3),
                createTask("Medium-Complex", TaskStatus.PENDING, Priority.MEDIUM, 8),
                createTask("Medium-Simple", TaskStatus.PENDING, Priority.MEDIUM, 2),
                createTask("Low-Complex", TaskStatus.PENDING, Priority.LOW, 9),
                createTask("Low-Simple", TaskStatus.PENDING, Priority.LOW, 1)
            )

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(tasks.shuffled())
            tasks.forEach { task ->
                coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()
            }

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(10)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!

            // Expected order: Priority HIGH (complexity 3, 7), MEDIUM (2, 8), LOW (1, 9)
            val titles = recommendations.map { it.jsonObject["title"]?.jsonPrimitive?.content }
            assertEquals(
                listOf("High-Simple", "High-Complex", "Medium-Simple", "Medium-Complex", "Low-Simple", "Low-Complex"),
                titles
            )
        }

        @Test
        @DisplayName("includeDetails flag provides full context")
        fun testIncludeDetailsFlag() = runBlocking {
            val featureId = UUID.randomUUID()

            val task = createTask(
                "Detailed Task",
                TaskStatus.PENDING,
                Priority.HIGH,
                5,
                summary = "This is a detailed summary",
                tags = listOf("backend", "api", "database")
            )

            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns
                Result.Success(listOf(task))
            coEvery { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()

            val params = JsonObject(mapOf(
                "featureId" to JsonPrimitive(featureId.toString()),
                "limit" to JsonPrimitive(5),
                "includeDetails" to JsonPrimitive(true)
            ))

            val result = getNextTaskTool.execute(params, context)

            val responseObj = result as JsonObject
            val data = responseObj["data"]?.jsonObject
            val recommendations = data!!["recommendations"]?.jsonArray!!
            val recommendation = recommendations[0].jsonObject

            // Verify full details are included
            assertTrue(recommendation.containsKey("summary"))
            assertEquals("This is a detailed summary", recommendation["summary"]?.jsonPrimitive?.content)

            assertTrue(recommendation.containsKey("tags"))
            val tags = recommendation["tags"]?.jsonArray!!
            assertEquals(3, tags.size)
        }
    }

    // ========== Helper Functions ==========

    private fun createTask(
        title: String,
        status: TaskStatus,
        priority: Priority,
        complexity: Int,
        summary: String = "Task summary",
        tags: List<String> = emptyList()
    ): Task {
        return Task(
            id = UUID.randomUUID(),
            title = title,
            summary = summary,
            status = status,
            priority = priority,
            complexity = complexity,
            tags = tags,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }
}
