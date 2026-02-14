package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.application.tools.dependency.QueryDependenciesTool
import io.github.jpicklyk.mcptask.application.tools.task.GetBlockedTasksTool
import io.github.jpicklyk.mcptask.application.tools.task.GetNextTaskTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.test.mock.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for Feature A: Role-Based Dependency Gating
 *
 * Tests end-to-end workflows spanning multiple components:
 * - StatusRole enum and role comparisons
 * - Dependency.unblockAt field
 * - StatusValidator with role-aware blocking
 * - GetNextTaskTool / GetBlockedTasksTool with role thresholds
 * - ManageDependenciesTool / QueryDependenciesTool with unblockAt
 * - Cascade behavior with role transitions
 * - Backward compatibility (null unblockAt, null StatusProgressionService)
 */
class RoleBasedDependencyGatingIntegrationTest {
    private lateinit var taskRepository: MockTaskRepository
    private lateinit var dependencyRepository: MockDependencyRepository
    private lateinit var projectRepository: MockProjectRepository
    private lateinit var featureRepository: MockFeatureRepository
    private lateinit var mockStatusProgressionService: StatusProgressionService
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setup() {
        // Create fresh mock repositories
        taskRepository = MockTaskRepository()
        dependencyRepository = MockDependencyRepository()
        projectRepository = MockProjectRepository()
        featureRepository = MockFeatureRepository()

        // Mock StatusProgressionService for consistent role mapping
        mockStatusProgressionService = mockk()
        every { mockStatusProgressionService.getRoleForStatus(any(), any(), any()) } answers {
            val status = firstArg<String>()
            when (status) {
                "pending", "backlog" -> "queue"
                "in-progress" -> "work"
                "testing", "qa-review" -> "review"
                "completed", "cancelled" -> "terminal"
                "blocked", "on-hold" -> "blocked"
                else -> "queue"
            }
        }
        every { mockStatusProgressionService.isRoleAtOrBeyond(any(), any()) } answers {
            val current = firstArg<String?>()
            val threshold = secondArg<String?>()
            StatusRole.isRoleAtOrBeyond(current, threshold)
        }

        // Create context with mock repositories
        val repositoryProvider = MockRepositoryProvider(
            projectRepository = projectRepository,
            featureRepository = featureRepository,
            taskRepository = taskRepository,
            dependencyRepository = dependencyRepository
        )

        context = ToolExecutionContext(repositoryProvider, mockStatusProgressionService)
    }

    @Test
    fun `test end-to-end unblockAt work unblocks downstream when blocker enters work role`() = runBlocking {
        // Create two tasks
        val taskA = Task(
            title = "Task A - Blocker",
            summary = "This task blocks Task B",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val taskB = Task(
            title = "Task B - Blocked",
            summary = "This task is blocked by Task A",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 3,
            tags = listOf()
        )
        taskRepository.create(taskA)
        taskRepository.create(taskB)

        // Create dependency A→B with unblockAt="work"
        val dep = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )
        dependencyRepository.create(dep)

        // Verify B is blocked initially
        val blockedTool = GetBlockedTasksTool()
        val blockedResult = blockedTool.execute(buildJsonObject {}, context)
        val blockedData = blockedResult.jsonObject["data"]?.jsonObject
        val blockedTasks = blockedData?.get("blockedTasks")?.jsonArray ?: emptyList()
        assertTrue(blockedTasks.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should be blocked initially")

        // Transition A to in-progress (work role)
        val updatedTaskA = taskA.copy(status = TaskStatus.IN_PROGRESS, modifiedAt = java.time.Instant.now())
        taskRepository.update(updatedTaskA)

        // Verify B is now unblocked
        val nextTaskTool = GetNextTaskTool()
        val nextResult = nextTaskTool.execute(buildJsonObject { put("limit", 10) }, context)
        val nextData = nextResult.jsonObject["data"]?.jsonObject
        val recommendations = nextData?.get("recommendations")?.jsonArray ?: emptyList()
        assertTrue(recommendations.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should be unblocked and available when A enters work role")
    }

    @Test
    fun `test end-to-end null unblockAt only unblocks on terminal`() = runBlocking {
        // Create two tasks
        val taskA = Task(
            title = "Task A - Blocker",
            summary = "Blocker with null unblockAt",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val taskB = Task(
            title = "Task B - Blocked",
            summary = "Blocked with legacy null unblockAt",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 3,
            tags = listOf()
        )
        taskRepository.create(taskA)
        taskRepository.create(taskB)

        // Create dependency A→B with null unblockAt (legacy behavior)
        val dep = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = null
        )
        dependencyRepository.create(dep)

        // Transition A to in-progress - B should still be blocked
        val inProgressTaskA = taskA.copy(status = TaskStatus.IN_PROGRESS, modifiedAt = java.time.Instant.now())
        taskRepository.update(inProgressTaskA)

        val blockedTool = GetBlockedTasksTool()
        val blockedResult = blockedTool.execute(buildJsonObject {}, context)
        val blockedData = blockedResult.jsonObject["data"]?.jsonObject
        val blockedTasks = blockedData?.get("blockedTasks")?.jsonArray ?: emptyList()
        assertTrue(blockedTasks.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should still be blocked when A is in-progress with null unblockAt")

        // Transition A to completed - B should now be unblocked
        val completedTaskA = inProgressTaskA.copy(status = TaskStatus.COMPLETED, modifiedAt = java.time.Instant.now())
        taskRepository.update(completedTaskA)

        val nextTaskTool = GetNextTaskTool()
        val nextResult = nextTaskTool.execute(buildJsonObject { put("limit", 10) }, context)
        val nextData = nextResult.jsonObject["data"]?.jsonObject
        val recommendations = nextData?.get("recommendations")?.jsonArray ?: emptyList()
        assertTrue(recommendations.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should be unblocked when A reaches terminal status with null unblockAt")
    }

    @Test
    fun `test end-to-end regression and transient blocking`() = runBlocking {
        // Create two tasks
        val taskA = Task(
            title = "Task A - Blocker",
            summary = "Task that will regress",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val taskB = Task(
            title = "Task B - Downstream",
            summary = "Task affected by regression",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 3,
            tags = listOf()
        )
        taskRepository.create(taskA)
        taskRepository.create(taskB)

        // Create dependency A→B with unblockAt="work"
        val dep = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )
        dependencyRepository.create(dep)

        // Transition A to in-progress (work role) - B unblocks
        val workTaskA = taskA.copy(status = TaskStatus.IN_PROGRESS, modifiedAt = java.time.Instant.now())
        taskRepository.update(workTaskA)

        val nextTool = GetNextTaskTool()
        val nextResult = nextTool.execute(buildJsonObject { put("limit", 10) }, context)
        val nextData = nextResult.jsonObject["data"]?.jsonObject
        val recommendations = nextData?.get("recommendations")?.jsonArray ?: emptyList()
        assertTrue(recommendations.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should be unblocked after A enters work role")

        // Transition A to blocked status - B should re-block
        val blockedTaskA = workTaskA.copy(status = TaskStatus.BLOCKED, modifiedAt = java.time.Instant.now())
        taskRepository.update(blockedTaskA)

        val blockedTool = GetBlockedTasksTool()
        val blockedResult = blockedTool.execute(buildJsonObject {}, context)
        val blockedData = blockedResult.jsonObject["data"]?.jsonObject
        val blockedTasks = blockedData?.get("blockedTasks")?.jsonArray ?: emptyList()
        assertTrue(blockedTasks.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should re-block when A regresses to blocked status")
    }

    @Test
    fun `test IS_BLOCKED_BY direction normalization`() = runBlocking {
        // Test the direction normalization helper methods on Dependency model
        val upstream = Task(
            title = "Upstream Task",
            summary = "This is the blocker",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val downstream = Task(
            title = "Downstream Task",
            summary = "This is blocked",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 3,
            tags = listOf()
        )
        taskRepository.create(upstream)
        taskRepository.create(downstream)

        // Create IS_BLOCKED_BY dependency (from=downstream, to=upstream)
        val isBlockedByDep = Dependency(
            fromTaskId = downstream.id,
            toTaskId = upstream.id,
            type = DependencyType.IS_BLOCKED_BY,
            unblockAt = "work"
        )

        // Verify direction normalization methods
        assertEquals(upstream.id, isBlockedByDep.getBlockerTaskId(),
            "IS_BLOCKED_BY: getBlockerTaskId should return toTaskId (upstream)")
        assertEquals(downstream.id, isBlockedByDep.getBlockedTaskId(),
            "IS_BLOCKED_BY: getBlockedTaskId should return fromTaskId (downstream)")

        // For comparison, test BLOCKS type
        val blocksDep = Dependency(
            fromTaskId = upstream.id,
            toTaskId = downstream.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )

        assertEquals(upstream.id, blocksDep.getBlockerTaskId(),
            "BLOCKS: getBlockerTaskId should return fromTaskId (upstream)")
        assertEquals(downstream.id, blocksDep.getBlockedTaskId(),
            "BLOCKS: getBlockedTaskId should return toTaskId (downstream)")

        // Both should produce the same semantic blocker/blocked relationship
        assertEquals(isBlockedByDep.getBlockerTaskId(), blocksDep.getBlockerTaskId(),
            "Both dependency types should identify the same blocker")
        assertEquals(isBlockedByDep.getBlockedTaskId(), blocksDep.getBlockedTaskId(),
            "Both dependency types should identify the same blocked task")
    }

    @Test
    fun `test cascade unblocking with mixed unblockAt thresholds`() = runBlocking {
        // Create chain: A→B→C where A→B has unblockAt="work", B→C has unblockAt=null (terminal)
        val taskA = Task(
            title = "Task A",
            summary = "First in chain",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val taskB = Task(
            title = "Task B",
            summary = "Middle in chain",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3,
            tags = listOf()
        )
        val taskC = Task(
            title = "Task C",
            summary = "End of chain",
            status = TaskStatus.PENDING,
            priority = Priority.LOW,
            complexity = 2,
            tags = listOf()
        )
        taskRepository.create(taskA)
        taskRepository.create(taskB)
        taskRepository.create(taskC)

        val depAB = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )
        val depBC = Dependency(
            fromTaskId = taskB.id,
            toTaskId = taskC.id,
            type = DependencyType.BLOCKS,
            unblockAt = null  // Requires terminal
        )
        dependencyRepository.create(depAB)
        dependencyRepository.create(depBC)

        // Transition A to work - B unblocks but C stays blocked
        val workTaskA = taskA.copy(status = TaskStatus.IN_PROGRESS, modifiedAt = java.time.Instant.now())
        taskRepository.update(workTaskA)

        val nextTool = GetNextTaskTool()
        val nextResult = nextTool.execute(buildJsonObject { put("limit", 10) }, context)
        val nextData = nextResult.jsonObject["data"]?.jsonObject
        val recommendations = nextData?.get("recommendations")?.jsonArray ?: emptyList()
        assertTrue(recommendations.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should be unblocked when A enters work")
        assertFalse(recommendations.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskC.id.toString() },
            "Task C should still be blocked (B not terminal yet)")

        // Transition B to in-progress then completed - C should unblock
        val workTaskB = taskB.copy(status = TaskStatus.IN_PROGRESS, modifiedAt = java.time.Instant.now())
        taskRepository.update(workTaskB)
        val completedTaskB = workTaskB.copy(status = TaskStatus.COMPLETED, modifiedAt = java.time.Instant.now())
        taskRepository.update(completedTaskB)

        val finalNextResult = nextTool.execute(buildJsonObject { put("limit", 10) }, context)
        val finalNextData = finalNextResult.jsonObject["data"]?.jsonObject
        val finalRecommendations = finalNextData?.get("recommendations")?.jsonArray ?: emptyList()
        assertTrue(finalRecommendations.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskC.id.toString() },
            "Task C should unblock when B reaches terminal status")
    }

    @Test
    fun `test GetBlockedTasksTool response includes role info`() = runBlocking {
        // Create blocked task with unblockAt="terminal" - blocker is still in work role
        val blocker = Task(
            title = "Blocker Task",
            summary = "In work status",
            status = TaskStatus.IN_PROGRESS,  // Maps to "work" role
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val blocked = Task(
            title = "Blocked Task",
            summary = "Waiting for terminal completion",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3,
            tags = listOf()
        )
        taskRepository.create(blocker)
        taskRepository.create(blocked)

        val dep = Dependency(
            fromTaskId = blocker.id,
            toTaskId = blocked.id,
            type = DependencyType.BLOCKS,
            unblockAt = "terminal"  // Requires completion
        )
        dependencyRepository.create(dep)

        // Call GetBlockedTasksTool and verify role info in response
        val blockedTool = GetBlockedTasksTool()
        val result = blockedTool.execute(buildJsonObject { put("includeTaskDetails", true) }, context)
        val data = result.jsonObject["data"]?.jsonObject
        val blockedTasks = data?.get("blockedTasks")?.jsonArray ?: emptyList()

        val blockedTaskObj = blockedTasks.firstOrNull { it.jsonObject["taskId"]?.jsonPrimitive?.content == blocked.id.toString() }
        assertNotNull(blockedTaskObj, "Blocked task should be in response")

        val blockedBy = blockedTaskObj?.jsonObject?.get("blockedBy")?.jsonArray
        assertNotNull(blockedBy, "blockedBy array should exist")
        assertTrue(blockedBy!!.size > 0, "blockedBy should have at least one entry")

        val blockerInfo = blockedBy.first().jsonObject
        assertEquals("terminal", blockerInfo["unblockAt"]?.jsonPrimitive?.content,
            "unblockAt should be 'terminal'")
        assertEquals("work", blockerInfo["blockerRole"]?.jsonPrimitive?.content,
            "blockerRole should be 'work' for in-progress status")
    }

    @Test
    fun `test ManageDependenciesTool creates with unblockAt`() = runBlocking {
        // Create two tasks
        val taskA = Task(
            title = "Task A",
            summary = "Source task",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val taskB = Task(
            title = "Task B",
            summary = "Target task",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3,
            tags = listOf()
        )
        taskRepository.create(taskA)
        taskRepository.create(taskB)

        // Use ManageDependenciesTool to create dependency with unblockAt
        val manageTool = ManageDependenciesTool()
        val createParams = buildJsonObject {
            put("operation", "create")
            putJsonArray("dependencies") {
                addJsonObject {
                    put("fromTaskId", taskA.id.toString())
                    put("toTaskId", taskB.id.toString())
                    put("type", "BLOCKS")
                    put("unblockAt", "work")
                }
            }
        }
        val createResult = manageTool.execute(createParams, context)
        assertTrue(createResult.jsonObject["success"]?.jsonPrimitive?.boolean == true,
            "Dependency creation should succeed")

        // Query back and verify unblockAt is persisted
        val queryTool = QueryDependenciesTool()
        val queryParams = buildJsonObject {
            put("taskId", taskA.id.toString())
            put("direction", "outgoing")
        }
        val queryResult = queryTool.execute(queryParams, context)
        val queryData = queryResult.jsonObject["data"]?.jsonObject
        val dependencies = queryData?.get("dependencies")?.jsonArray ?: emptyList()

        assertTrue(dependencies.size > 0, "Should have at least one dependency")
        val dep = dependencies.first().jsonObject
        assertEquals("work", dep["unblockAt"]?.jsonPrimitive?.content,
            "unblockAt should be persisted as 'work'")
        assertEquals("work", dep["effectiveUnblockRole"]?.jsonPrimitive?.content,
            "effectiveUnblockRole should be 'work'")
    }

    @Test
    fun `test backward compatibility with null StatusProgressionService`() = runBlocking {
        // Create context WITHOUT StatusProgressionService
        val repositoryProvider = MockRepositoryProvider(
            projectRepository = projectRepository,
            featureRepository = featureRepository,
            taskRepository = taskRepository,
            dependencyRepository = dependencyRepository
        )
        val legacyContext = ToolExecutionContext(repositoryProvider, null)

        // Create two tasks with dependency
        val taskA = Task(
            title = "Task A - Legacy",
            summary = "Blocker without service",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val taskB = Task(
            title = "Task B - Legacy",
            summary = "Blocked without service",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 3,
            tags = listOf()
        )
        taskRepository.create(taskA)
        taskRepository.create(taskB)

        val dep = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"  // Should be ignored when service is null
        )
        dependencyRepository.create(dep)

        // B should be blocked initially
        val blockedTool = GetBlockedTasksTool()
        val initialResult = blockedTool.execute(buildJsonObject {}, legacyContext)
        val initialData = initialResult.jsonObject["data"]?.jsonObject
        val initialBlockedTasks = initialData?.get("blockedTasks")?.jsonArray ?: emptyList()
        assertTrue(initialBlockedTasks.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should be blocked initially")

        // Transition A to in-progress - B should STILL be blocked (fallback to terminal-only)
        val workTaskA = taskA.copy(status = TaskStatus.IN_PROGRESS, modifiedAt = java.time.Instant.now())
        taskRepository.update(workTaskA)

        val afterWorkResult = blockedTool.execute(buildJsonObject {}, legacyContext)
        val afterWorkData = afterWorkResult.jsonObject["data"]?.jsonObject
        val afterWorkBlockedTasks = afterWorkData?.get("blockedTasks")?.jsonArray ?: emptyList()
        assertTrue(afterWorkBlockedTasks.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should still be blocked when service is null (unblockAt ignored)")

        // Transition A to completed - B should now unblock (hardcoded terminal check)
        val completedTaskA = workTaskA.copy(status = TaskStatus.COMPLETED, modifiedAt = java.time.Instant.now())
        taskRepository.update(completedTaskA)

        val nextTool = GetNextTaskTool()
        val finalResult = nextTool.execute(buildJsonObject { put("limit", 10) }, legacyContext)
        val finalData = finalResult.jsonObject["data"]?.jsonObject
        val finalRecommendations = finalData?.get("recommendations")?.jsonArray ?: emptyList()
        assertTrue(finalRecommendations.any { it.jsonObject["taskId"]?.jsonPrimitive?.content == taskB.id.toString() },
            "Task B should unblock on terminal status with null service (backward compat)")
    }

    @Test
    fun `test QueryDependenciesTool includes unblockAt and effectiveUnblockRole`() = runBlocking {
        // Create tasks with various unblockAt settings
        val taskA = Task(
            title = "Task A",
            summary = "Source",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf()
        )
        val taskB = Task(
            title = "Task B",
            summary = "Target with review threshold",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 3,
            tags = listOf()
        )
        val taskC = Task(
            title = "Task C",
            summary = "Target with null threshold",
            status = TaskStatus.PENDING,
            priority = Priority.LOW,
            complexity = 2,
            tags = listOf()
        )
        taskRepository.create(taskA)
        taskRepository.create(taskB)
        taskRepository.create(taskC)

        val depAB = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "review"
        )
        val depAC = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskC.id,
            type = DependencyType.BLOCKS,
            unblockAt = null
        )
        dependencyRepository.create(depAB)
        dependencyRepository.create(depAC)

        // Query dependencies from A
        val queryTool = QueryDependenciesTool()
        val queryParams = buildJsonObject {
            put("taskId", taskA.id.toString())
            put("direction", "outgoing")
        }
        val queryResult = queryTool.execute(queryParams, context)
        val queryData = queryResult.jsonObject["data"]?.jsonObject
        val dependencies = queryData?.get("dependencies")?.jsonArray ?: emptyList()

        assertEquals(2, dependencies.size, "Should have 2 outgoing dependencies")

        // Find dependency to B (with unblockAt="review")
        val depToB = dependencies.firstOrNull {
            it.jsonObject["toTaskId"]?.jsonPrimitive?.content == taskB.id.toString()
        }
        assertNotNull(depToB, "Dependency to B should exist")
        assertEquals("review", depToB?.jsonObject?.get("unblockAt")?.jsonPrimitive?.content,
            "unblockAt should be 'review'")
        assertEquals("review", depToB?.jsonObject?.get("effectiveUnblockRole")?.jsonPrimitive?.content,
            "effectiveUnblockRole should be 'review'")

        // Find dependency to C (with unblockAt=null)
        val depToC = dependencies.firstOrNull {
            it.jsonObject["toTaskId"]?.jsonPrimitive?.content == taskC.id.toString()
        }
        assertNotNull(depToC, "Dependency to C should exist")
        val unblockAtElement = depToC?.jsonObject?.get("unblockAt")
        assertTrue(unblockAtElement is JsonNull || unblockAtElement == null,
            "unblockAt should be null")
        assertEquals("terminal", depToC?.jsonObject?.get("effectiveUnblockRole")?.jsonPrimitive?.content,
            "effectiveUnblockRole should default to 'terminal' when unblockAt is null")
    }
}
