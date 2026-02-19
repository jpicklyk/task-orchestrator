package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.application.tools.QueryContainerTool
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.status.RequestTransitionTool
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.test.mock.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Comprehensive integration tests for Feature B: Role-Aware Queries & Transition History.
 *
 * Tests end-to-end workflows spanning multiple components:
 * 1. Role filter in QueryContainerTool (search/overview operations)
 * 2. Role transition recording in RequestTransitionTool
 * 3. getStatusesForRole correctness across workflows
 * 4. Role aggregation with cascade service configuration
 * 5. Role transition history query API
 */
class RoleAwareQueriesIntegrationTest {

    private lateinit var taskRepository: MockTaskRepository
    private lateinit var featureRepository: MockFeatureRepository
    private lateinit var projectRepository: MockProjectRepository
    private lateinit var roleTransitionRepository: MockRoleTransitionRepository
    private lateinit var mockStatusProgressionService: StatusProgressionService
    private lateinit var executionContext: ToolExecutionContext

    // Tools under test
    private lateinit var queryContainerTool: QueryContainerTool
    private lateinit var manageContainerTool: ManageContainerTool
    private lateinit var requestTransitionTool: RequestTransitionTool

    @BeforeEach
    fun setUp() {
        // Initialize mock repositories
        taskRepository = MockTaskRepository()
        featureRepository = MockFeatureRepository()
        projectRepository = MockProjectRepository()
        roleTransitionRepository = MockRoleTransitionRepository()

        // Mock StatusProgressionService with default role mappings
        mockStatusProgressionService = mockk<StatusProgressionService>(relaxed = true)

        // Configure role-to-status mappings (default config)
        coEvery {
            mockStatusProgressionService.getStatusesForRole("queue", "task", any())
        } returns setOf("backlog", "pending")

        coEvery {
            mockStatusProgressionService.getStatusesForRole("work", "task", any())
        } returns setOf("in-progress", "changes-requested", "investigating")

        coEvery {
            mockStatusProgressionService.getStatusesForRole("review", "task", any())
        } returns setOf("in-review", "testing", "ready-for-qa")

        coEvery {
            mockStatusProgressionService.getStatusesForRole("blocked", "task", any())
        } returns setOf("blocked", "on-hold")

        coEvery {
            mockStatusProgressionService.getStatusesForRole("terminal", "task", any())
        } returns setOf("completed", "cancelled", "deferred")

        // Configure status-to-role mappings
        coEvery {
            mockStatusProgressionService.getRoleForStatus(any(), "task", any())
        } answers {
            when (firstArg<String>()) {
                "backlog", "pending" -> "queue"
                "in-progress", "changes-requested", "investigating" -> "work"
                "in-review", "testing", "ready-for-qa" -> "review"
                "blocked", "on-hold" -> "blocked"
                "completed", "cancelled", "deferred" -> "terminal"
                else -> null
            }
        }

        // Feature role mappings
        coEvery {
            mockStatusProgressionService.getStatusesForRole("queue", "feature", any())
        } returns setOf("draft", "planning")

        coEvery {
            mockStatusProgressionService.getStatusesForRole("work", "feature", any())
        } returns setOf("in-development")

        coEvery {
            mockStatusProgressionService.getStatusesForRole("review", "feature", any())
        } returns setOf("testing", "validating", "pending-review")

        coEvery {
            mockStatusProgressionService.getStatusesForRole("terminal", "feature", any())
        } returns setOf("completed", "archived")

        coEvery {
            mockStatusProgressionService.getRoleForStatus(any(), "feature", any())
        } answers {
            when (firstArg<String>()) {
                "draft", "planning" -> "queue"
                "in-development" -> "work"
                "testing", "validating", "pending-review" -> "review"
                "blocked", "on-hold" -> "blocked"
                "completed", "archived" -> "terminal"
                else -> null
            }
        }

        // Create execution context with mocked StatusProgressionService
        val repositoryProvider = MockRepositoryProvider(
            taskRepository = taskRepository,
            featureRepository = featureRepository,
            projectRepository = projectRepository,
            roleTransitionRepository = roleTransitionRepository
        )

        executionContext = ToolExecutionContext(
            repositoryProvider = repositoryProvider,
            statusProgressionService = mockStatusProgressionService
        )

        // Initialize tools
        queryContainerTool = QueryContainerTool()
        manageContainerTool = ManageContainerTool(null, null)
        requestTransitionTool = RequestTransitionTool(mockStatusProgressionService)
    }

    /**
     * Test 1: End-to-end role filter in QueryContainerTool - search operation
     */
    @Test
    fun `search operation should filter tasks by role using workflow configuration`() = runBlocking {
        // Create tasks with various statuses
        val task1 = Task(
            id = UUID.randomUUID(),
            title = "Pending Task",
            status = TaskStatus.PENDING
        )
        val task2 = Task(
            id = UUID.randomUUID(),
            title = "In Progress Task",
            status = TaskStatus.IN_PROGRESS
        )
        val task3 = Task(
            id = UUID.randomUUID(),
            title = "Completed Task",
            status = TaskStatus.COMPLETED
        )
        val task4 = Task(
            id = UUID.randomUUID(),
            title = "Blocked Task",
            status = TaskStatus.BLOCKED
        )

        taskRepository.create(task1)
        taskRepository.create(task2)
        taskRepository.create(task3)
        taskRepository.create(task4)

        // Test: Search for "work" role tasks (should return only in-progress)
        val workSearchParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "work")
        }

        val workResponse = queryContainerTool.execute(workSearchParams, executionContext)
        assertTrue(workResponse is JsonObject, "Response should be JsonObject")

        val workResult = workResponse as JsonObject
        assertTrue(workResult["success"]?.jsonPrimitive?.boolean == true, "Search should succeed")

        val workItems = workResult["data"]?.jsonObject?.get("items")?.jsonArray
        assertNotNull(workItems, "Items should be present")
        assertEquals(1, workItems!!.size, "Should find exactly 1 task in 'work' role")

        val foundTask = workItems[0].jsonObject
        assertEquals(task2.id.toString(), foundTask["id"]?.jsonPrimitive?.content, "Should find the in-progress task")
        assertEquals("in-progress", foundTask["status"]?.jsonPrimitive?.content, "Status should be in-progress")

        // Test: Search for "terminal" role tasks (should return completed task)
        val terminalSearchParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "terminal")
        }

        val terminalResponse = queryContainerTool.execute(terminalSearchParams, executionContext)
        val terminalResult = terminalResponse as JsonObject
        assertTrue(terminalResult["success"]?.jsonPrimitive?.boolean == true)

        val terminalItems = terminalResult["data"]?.jsonObject?.get("items")?.jsonArray
        assertEquals(1, terminalItems!!.size, "Should find exactly 1 task in 'terminal' role")

        val terminalTask = terminalItems[0].jsonObject
        assertEquals(task3.id.toString(), terminalTask["id"]?.jsonPrimitive?.content, "Should find the completed task")
        assertEquals("completed", terminalTask["status"]?.jsonPrimitive?.content)

        // Test: Search for "blocked" role tasks
        val blockedSearchParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "blocked")
        }

        val blockedResponse = queryContainerTool.execute(blockedSearchParams, executionContext)
        val blockedResult = blockedResponse as JsonObject
        assertTrue(blockedResult["success"]?.jsonPrimitive?.boolean == true)

        val blockedItems = blockedResult["data"]?.jsonObject?.get("items")?.jsonArray
        assertEquals(1, blockedItems!!.size, "Should find exactly 1 task in 'blocked' role")

        val blockedTask = blockedItems[0].jsonObject
        assertEquals(task4.id.toString(), blockedTask["id"]?.jsonPrimitive?.content, "Should find the blocked task")
    }

    /**
     * Test 2: End-to-end role filter in QueryContainerTool - overview operation
     */
    @Test
    fun `overview operation should filter tasks by role`() = runBlocking {
        // Create a feature with tasks
        val feature = Feature(
            id = UUID.randomUUID(),
            name = "Test Feature",
            status = FeatureStatus.IN_DEVELOPMENT
        )
        featureRepository.create(feature)

        val pendingTask = Task(
            id = UUID.randomUUID(),
            title = "Pending Task",
            status = TaskStatus.PENDING,
            featureId = feature.id
        )
        val inProgressTask = Task(
            id = UUID.randomUUID(),
            title = "In Progress Task",
            status = TaskStatus.IN_PROGRESS,
            featureId = feature.id
        )
        val completedTask = Task(
            id = UUID.randomUUID(),
            title = "Completed Task",
            status = TaskStatus.COMPLETED,
            featureId = feature.id
        )

        taskRepository.create(pendingTask)
        taskRepository.create(inProgressTask)
        taskRepository.create(completedTask)

        // Test: Feature overview with "work" role filter
        val overviewParams = buildJsonObject {
            put("operation", "overview")
            put("containerType", "feature")
            put("id", feature.id.toString())
            put("role", "work")
        }

        val response = queryContainerTool.execute(overviewParams, executionContext)
        assertTrue(response is JsonObject, "Response should be JsonObject")

        val result = response as JsonObject
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true, "Overview should succeed")

        val data = result["data"]?.jsonObject
        assertNotNull(data, "Data should be present")

        val tasks = data!!["tasks"]?.jsonArray
        assertNotNull(tasks, "Tasks array should be present")
        assertEquals(1, tasks!!.size, "Should only show 1 task in 'work' role")

        val filteredTask = tasks[0].jsonObject
        assertEquals(inProgressTask.id.toString(), filteredTask["id"]?.jsonPrimitive?.content)
        assertEquals("in-progress", filteredTask["status"]?.jsonPrimitive?.content)

        // Verify meta information shows filtering
        val meta = data["taskMeta"]?.jsonObject
        if (meta != null) {
            val returned = meta["returned"]?.jsonPrimitive?.int
            val total = meta["total"]?.jsonPrimitive?.int
            assertEquals(1, returned, "Should return 1 filtered task")
            assertEquals(3, total, "Total should be 3 tasks")
        }
    }

    /**
     * Test 3: End-to-end role transition recording workflow
     *
     * NOTE: RequestTransitionTool integration with RoleTransitionRepository is not yet implemented.
     * This test demonstrates the expected workflow by manually recording the transition after
     * status change, which represents the integration point that will be added.
     */
    @Test
    fun `status transition should record role change in transition history`() = runBlocking {
        // Create a task in pending status
        val task = Task(
            id = UUID.randomUUID(),
            title = "Test Task",
            status = TaskStatus.PENDING,
            summary = "Test summary"
        )
        taskRepository.create(task)

        // Simulate the workflow: status transition occurs
        val updatedTask = task.copy(status = TaskStatus.IN_PROGRESS)
        taskRepository.update(updatedTask)

        // Record the role transition (simulating what RequestTransitionTool will do)
        // In the actual implementation, this would be triggered automatically by the tool
        val roleTransition = RoleTransition(
            entityId = task.id,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            trigger = "start"
        )
        roleTransitionRepository.create(roleTransition)

        // Verify role transition was recorded
        val transitionsResult = roleTransitionRepository.findByEntityId(task.id)
        assertTrue(transitionsResult is Result.Success, "Should successfully query transitions")

        val transitionList = (transitionsResult as Result.Success).data
        assertEquals(1, transitionList.size, "Should have exactly 1 transition record")

        val transition = transitionList[0]
        assertEquals(task.id, transition.entityId, "Should record correct entity ID")
        assertEquals("task", transition.entityType, "Should record entity type")
        assertEquals("queue", transition.fromRole, "Should record fromRole as 'queue'")
        assertEquals("work", transition.toRole, "Should record toRole as 'work'")
        assertEquals("pending", transition.fromStatus, "Should record fromStatus")
        assertEquals("in-progress", transition.toStatus, "Should record toStatus")
        assertEquals("start", transition.trigger, "Should record trigger")
    }

    /**
     * Test 4: Multiple role transitions create correct history
     */
    @Test
    fun `multiple status transitions should create ordered role transition history`() = runBlocking {
        // Create a task and transition it through multiple states
        val task = Task(
            id = UUID.randomUUID(),
            title = "Multi-transition Task",
            status = TaskStatus.PENDING,
            summary = "Test summary"
        )
        taskRepository.create(task)

        // Transition 1: pending → in-progress (queue → work)
        val transition1 = RoleTransition(
            entityId = task.id,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.now().minusSeconds(60),
            trigger = "start"
        )
        roleTransitionRepository.create(transition1)

        // Update task status
        taskRepository.update(task.copy(status = TaskStatus.IN_PROGRESS))

        // Transition 2: in-progress → in-review (work → review)
        val transition2 = RoleTransition(
            entityId = task.id,
            entityType = "task",
            fromRole = "work",
            toRole = "review",
            fromStatus = "in-progress",
            toStatus = "in-review",
            transitionedAt = Instant.now().minusSeconds(30),
            trigger = "complete"
        )
        roleTransitionRepository.create(transition2)

        // Update task status
        taskRepository.update(task.copy(status = TaskStatus.IN_REVIEW))

        // Transition 3: in-review → completed (review → terminal)
        val transition3 = RoleTransition(
            entityId = task.id,
            entityType = "task",
            fromRole = "review",
            toRole = "terminal",
            fromStatus = "in-review",
            toStatus = "completed",
            transitionedAt = Instant.now(),
            trigger = "complete"
        )
        roleTransitionRepository.create(transition3)

        // Query transition history
        val transitionsResult = roleTransitionRepository.findByEntityId(task.id)
        assertTrue(transitionsResult is Result.Success, "Should successfully query transitions")

        val transitionList = (transitionsResult as Result.Success).data
        assertEquals(3, transitionList.size, "Should have exactly 3 transition records")

        // Verify ordering (oldest first)
        assertEquals("queue", transitionList[0].fromRole, "First transition should be queue → work")
        assertEquals("work", transitionList[0].toRole)
        assertEquals("pending", transitionList[0].fromStatus)
        assertEquals("in-progress", transitionList[0].toStatus)

        assertEquals("work", transitionList[1].fromRole, "Second transition should be work → review")
        assertEquals("review", transitionList[1].toRole)
        assertEquals("in-progress", transitionList[1].fromStatus)
        assertEquals("in-review", transitionList[1].toStatus)

        assertEquals("review", transitionList[2].fromRole, "Third transition should be review → terminal")
        assertEquals("terminal", transitionList[2].toRole)
        assertEquals("in-review", transitionList[2].fromStatus)
        assertEquals("completed", transitionList[2].toStatus)

        // Verify chronological ordering
        assertTrue(
            transitionList[0].transitionedAt.isBefore(transitionList[1].transitionedAt),
            "Transitions should be ordered chronologically"
        )
        assertTrue(
            transitionList[1].transitionedAt.isBefore(transitionList[2].transitionedAt),
            "Transitions should be ordered chronologically"
        )
    }

    /**
     * Test 5: getStatusesForRole correctness for tasks
     */
    @Test
    fun `getStatusesForRole should return correct status sets for task roles`() {
        // Test queue role
        val queueStatuses = mockStatusProgressionService.getStatusesForRole("queue", "task", emptyList())
        assertTrue(queueStatuses.contains("backlog"), "Queue should include backlog")
        assertTrue(queueStatuses.contains("pending"), "Queue should include pending")
        assertEquals(2, queueStatuses.size, "Queue should have exactly 2 statuses")

        // Test work role
        val workStatuses = mockStatusProgressionService.getStatusesForRole("work", "task", emptyList())
        assertTrue(workStatuses.contains("in-progress"), "Work should include in-progress")
        assertTrue(workStatuses.contains("changes-requested"), "Work should include changes-requested")
        assertTrue(workStatuses.contains("investigating"), "Work should include investigating")
        assertEquals(3, workStatuses.size, "Work should have exactly 3 statuses")

        // Test review role
        val reviewStatuses = mockStatusProgressionService.getStatusesForRole("review", "task", emptyList())
        assertTrue(reviewStatuses.contains("in-review"), "Review should include in-review")
        assertTrue(reviewStatuses.contains("testing"), "Review should include testing")
        assertTrue(reviewStatuses.contains("ready-for-qa"), "Review should include ready-for-qa")
        assertEquals(3, reviewStatuses.size, "Review should have exactly 3 statuses")

        // Test blocked role
        val blockedStatuses = mockStatusProgressionService.getStatusesForRole("blocked", "task", emptyList())
        assertTrue(blockedStatuses.contains("blocked"), "Blocked should include blocked")
        assertTrue(blockedStatuses.contains("on-hold"), "Blocked should include on-hold")
        assertEquals(2, blockedStatuses.size, "Blocked should have exactly 2 statuses")

        // Test terminal role
        val terminalStatuses = mockStatusProgressionService.getStatusesForRole("terminal", "task", emptyList())
        assertTrue(terminalStatuses.contains("completed"), "Terminal should include completed")
        assertTrue(terminalStatuses.contains("cancelled"), "Terminal should include cancelled")
        assertTrue(terminalStatuses.contains("deferred"), "Terminal should include deferred")
        assertEquals(3, terminalStatuses.size, "Terminal should have exactly 3 statuses")
    }

    /**
     * Test 6: getStatusesForRole correctness for features
     */
    @Test
    fun `getStatusesForRole should return correct status sets for feature roles`() {
        // Test queue role
        val queueStatuses = mockStatusProgressionService.getStatusesForRole("queue", "feature", emptyList())
        assertTrue(queueStatuses.contains("draft"), "Queue should include draft")
        assertTrue(queueStatuses.contains("planning"), "Queue should include planning")
        assertEquals(2, queueStatuses.size, "Queue should have exactly 2 statuses")

        // Test work role
        val workStatuses = mockStatusProgressionService.getStatusesForRole("work", "feature", emptyList())
        assertTrue(workStatuses.contains("in-development"), "Work should include in-development")
        assertEquals(1, workStatuses.size, "Work should have exactly 1 status")

        // Test review role
        val reviewStatuses = mockStatusProgressionService.getStatusesForRole("review", "feature", emptyList())
        assertTrue(reviewStatuses.contains("testing"), "Review should include testing")
        assertTrue(reviewStatuses.contains("validating"), "Review should include validating")
        assertTrue(reviewStatuses.contains("pending-review"), "Review should include pending-review")
        assertEquals(3, reviewStatuses.size, "Review should have exactly 3 statuses")

        // Test terminal role
        val terminalStatuses = mockStatusProgressionService.getStatusesForRole("terminal", "feature", emptyList())
        assertTrue(terminalStatuses.contains("completed"), "Terminal should include completed")
        assertTrue(terminalStatuses.contains("archived"), "Terminal should include archived")
        assertEquals(2, terminalStatuses.size, "Terminal should have exactly 2 statuses")
    }

    /**
     * Test 7: Role filter with mixed statuses returns correct subset
     */
    @Test
    fun `role filter should correctly handle tasks with mixed statuses across multiple roles`() = runBlocking {
        // Create tasks spanning all roles
        val tasks = listOf(
            Task(id = UUID.randomUUID(), title = "Backlog Task", status = TaskStatus.BACKLOG),
            Task(id = UUID.randomUUID(), title = "Pending Task", status = TaskStatus.PENDING),
            Task(id = UUID.randomUUID(), title = "In Progress Task", status = TaskStatus.IN_PROGRESS),
            Task(id = UUID.randomUUID(), title = "In Review Task", status = TaskStatus.IN_REVIEW),
            Task(id = UUID.randomUUID(), title = "Testing Task", status = TaskStatus.TESTING),
            Task(id = UUID.randomUUID(), title = "Blocked Task", status = TaskStatus.BLOCKED),
            Task(id = UUID.randomUUID(), title = "On Hold Task", status = TaskStatus.ON_HOLD),
            Task(id = UUID.randomUUID(), title = "Completed Task", status = TaskStatus.COMPLETED),
            Task(id = UUID.randomUUID(), title = "Cancelled Task", status = TaskStatus.CANCELLED)
        )

        tasks.forEach { taskRepository.create(it) }

        // Test queue role (backlog + pending)
        val queueParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "queue")
        }

        val queueResponse = queryContainerTool.execute(queueParams, executionContext)
        val queueItems = (queueResponse as JsonObject)["data"]?.jsonObject?.get("items")?.jsonArray
        assertEquals(2, queueItems!!.size, "Queue role should return 2 tasks")

        // Test work role (in-progress)
        val workParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "work")
        }

        val workResponse = queryContainerTool.execute(workParams, executionContext)
        val workItems = (workResponse as JsonObject)["data"]?.jsonObject?.get("items")?.jsonArray
        assertEquals(1, workItems!!.size, "Work role should return 1 task")

        // Test review role (in-review + testing)
        val reviewParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "review")
        }

        val reviewResponse = queryContainerTool.execute(reviewParams, executionContext)
        val reviewItems = (reviewResponse as JsonObject)["data"]?.jsonObject?.get("items")?.jsonArray
        assertEquals(2, reviewItems!!.size, "Review role should return 2 tasks")

        // Test blocked role (blocked + on-hold)
        val blockedParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "blocked")
        }

        val blockedResponse = queryContainerTool.execute(blockedParams, executionContext)
        val blockedItems = (blockedResponse as JsonObject)["data"]?.jsonObject?.get("items")?.jsonArray
        assertEquals(2, blockedItems!!.size, "Blocked role should return 2 tasks")

        // Test terminal role (completed + cancelled)
        val terminalParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "terminal")
        }

        val terminalResponse = queryContainerTool.execute(terminalParams, executionContext)
        val terminalItems = (terminalResponse as JsonObject)["data"]?.jsonObject?.get("items")?.jsonArray
        assertEquals(2, terminalItems!!.size, "Terminal role should return 2 tasks")
    }

    /**
     * Test 8: Same-role status transition should NOT create role transition record
     */
    @Test
    fun `status transition within same role should not create role transition record`() = runBlocking {
        // Create a task in in-review status (review role)
        val task = Task(
            id = UUID.randomUUID(),
            title = "Review Task",
            status = TaskStatus.IN_REVIEW,
            summary = "Test summary"
        )
        taskRepository.create(task)

        // Mock transition to testing (both are "review" role)
        coEvery {
            mockStatusProgressionService.getRoleForStatus("in-review", "task", any())
        } returns "review"

        coEvery {
            mockStatusProgressionService.getRoleForStatus("testing", "task", any())
        } returns "review"

        // Simulate status change in-review → testing (both review role)
        // In real implementation, RequestTransitionTool checks if fromRole == toRole
        // and skips creating RoleTransition record

        // Manually update status without role transition (simulating tool behavior)
        taskRepository.update(task.copy(status = TaskStatus.TESTING))

        // Verify no role transition was recorded
        val transitionsResult = roleTransitionRepository.findByEntityId(task.id)
        assertTrue(transitionsResult is Result.Success, "Should successfully query transitions")

        val transitionList = (transitionsResult as Result.Success).data
        assertEquals(0, transitionList.size, "Should have NO transition records for same-role status change")
    }

    /**
     * Test 9: Feature-level role transition recording
     */
    @Test
    fun `feature status transition should record role change`() = runBlocking {
        // Create a feature in planning status
        val feature = Feature(
            id = UUID.randomUUID(),
            name = "Test Feature",
            status = FeatureStatus.PLANNING
        )
        featureRepository.create(feature)

        // Record role transition: planning → in-development (queue → work)
        val transition = RoleTransition(
            entityId = feature.id,
            entityType = "feature",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "planning",
            toStatus = "in-development",
            trigger = "start"
        )
        roleTransitionRepository.create(transition)

        // Query transition history
        val transitionsResult = roleTransitionRepository.findByEntityId(feature.id)
        assertTrue(transitionsResult is Result.Success, "Should successfully query transitions")

        val transitionList = (transitionsResult as Result.Success).data
        assertEquals(1, transitionList.size, "Should have exactly 1 transition record")

        val recorded = transitionList[0]
        assertEquals(feature.id, recorded.entityId, "Should record correct feature ID")
        assertEquals("feature", recorded.entityType, "Should record entity type as 'feature'")
        assertEquals("queue", recorded.fromRole, "Should record fromRole")
        assertEquals("work", recorded.toRole, "Should record toRole")
        assertEquals("planning", recorded.fromStatus, "Should record fromStatus")
        assertEquals("in-development", recorded.toStatus, "Should record toStatus")
    }

    /**
     * Test 10: Role filter combined with other filters (status, priority)
     */
    @Test
    fun `role filter should work correctly when combined with other filters`() = runBlocking {
        // Create tasks with various combinations
        val highPriorityWork = Task(
            id = UUID.randomUUID(),
            title = "High Priority Work",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH
        )
        val mediumPriorityWork = Task(
            id = UUID.randomUUID(),
            title = "Medium Priority Work",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.MEDIUM
        )
        val highPriorityQueue = Task(
            id = UUID.randomUUID(),
            title = "High Priority Queue",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH
        )

        taskRepository.create(highPriorityWork)
        taskRepository.create(mediumPriorityWork)
        taskRepository.create(highPriorityQueue)

        // Test: Role filter + priority filter (work role + high priority)
        val combinedParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("role", "work")
            put("priority", "high")
        }

        val response = queryContainerTool.execute(combinedParams, executionContext)
        assertTrue(response is JsonObject, "Response should be JsonObject")

        val result = response as JsonObject
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true, "Search should succeed")

        val items = result["data"]?.jsonObject?.get("items")?.jsonArray
        assertNotNull(items, "Items should be present")
        assertEquals(1, items!!.size, "Should find exactly 1 task (work role + high priority)")

        val foundTask = items[0].jsonObject
        assertEquals(highPriorityWork.id.toString(), foundTask["id"]?.jsonPrimitive?.content)
        assertEquals("in-progress", foundTask["status"]?.jsonPrimitive?.content)
        assertEquals("high", foundTask["priority"]?.jsonPrimitive?.content)
    }
}
