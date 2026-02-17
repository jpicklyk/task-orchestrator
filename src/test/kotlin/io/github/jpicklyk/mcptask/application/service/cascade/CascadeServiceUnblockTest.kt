package io.github.jpicklyk.mcptask.application.service.cascade

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.progression.FlowPath
import io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.repository.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Fix 1: IS_BLOCKED_BY candidate discovery in findNewlyUnblockedTasks
 * Tests for Fix 2: Multi-step cascade advancement to terminal status
 */
class CascadeServiceUnblockTest {

    private val mockStatusProgressionService = mockk<StatusProgressionService>()
    private val mockStatusValidator = mockk<StatusValidator>()
    private val mockTaskRepository = mockk<TaskRepository>()
    private val mockFeatureRepository = mockk<FeatureRepository>()
    private val mockProjectRepository = mockk<ProjectRepository>()
    private val mockDependencyRepository = mockk<DependencyRepository>()
    private val mockSectionRepository = mockk<SectionRepository>()
    private val mockRoleTransitionRepository = mockk<RoleTransitionRepository>()

    private val cascadeService = CascadeServiceImpl(
        statusProgressionService = mockStatusProgressionService,
        statusValidator = mockStatusValidator,
        taskRepository = mockTaskRepository,
        featureRepository = mockFeatureRepository,
        projectRepository = mockProjectRepository,
        dependencyRepository = mockDependencyRepository,
        sectionRepository = mockSectionRepository,
        aggregationRules = emptyList(),
        roleTransitionRepository = mockRoleTransitionRepository
    )

    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    private fun createTestTask(
        id: UUID = UUID.randomUUID(),
        featureId: UUID? = this.featureId,
        status: TaskStatus = TaskStatus.COMPLETED,
        tags: List<String> = emptyList()
    ) = Task(
        id = id,
        featureId = featureId,
        title = "Test Task ${id.toString().take(8)}",
        summary = "Test",
        description = "Test description",
        status = status,
        priority = Priority.MEDIUM,
        complexity = 3,
        tags = tags,
        projectId = projectId,
        createdAt = Instant.now(),
        modifiedAt = Instant.now()
    )

    private fun createTestFeature(
        id: UUID = featureId,
        projectId: UUID? = this.projectId,
        status: FeatureStatus = FeatureStatus.COMPLETED,
        tags: List<String> = emptyList()
    ) = Feature(
        id = id,
        projectId = projectId,
        name = "Test Feature",
        summary = "Test",
        status = status,
        priority = Priority.MEDIUM,
        tags = tags,
        createdAt = Instant.now(),
        modifiedAt = Instant.now()
    )

    @Nested
    inner class IsBlockedByCandidateDiscoveryTests {

        @Test
        fun `IS_BLOCKED_BY dependency discovers downstream candidate when blocker completes`() = runBlocking {
            val taskAId = UUID.randomUUID() // blocker
            val taskBId = UUID.randomUUID() // blocked (B IS_BLOCKED_BY A)
            val taskA = createTestTask(id = taskAId, status = TaskStatus.COMPLETED)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)

            // B IS_BLOCKED_BY A: fromTaskId=B (blocked), toTaskId=A (blocker)
            val isBlockedByDep = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskBId,
                toTaskId = taskAId,
                type = DependencyType.IS_BLOCKED_BY,
                createdAt = Instant.now()
            )

            // Outer loop: findByFromTaskId(A) returns no BLOCKS deps
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns emptyList()
            // Outer loop: findByToTaskId(A) returns IS_BLOCKED_BY dep (B IS_BLOCKED_BY A)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns listOf(isBlockedByDep)

            // Get downstream task B
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)

            // Inner loop: get ALL blocking deps for B
            // findByToTaskId(B) returns nothing (no BLOCKS deps pointing to B)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns emptyList()
            // findByFromTaskId(B) returns the IS_BLOCKED_BY dep
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns listOf(isBlockedByDep)

            // Role checks
            every { mockStatusProgressionService.getRoleForStatus("pending", "task", emptyList()) } returns "queue"
            every { mockStatusProgressionService.isRoleAtOrBeyond("queue", "terminal") } returns false
            every { mockStatusProgressionService.getRoleForStatus("completed", "task", emptyList()) } returns "terminal"
            every { mockStatusProgressionService.isRoleAtOrBeyond("terminal", "terminal") } returns true

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            assertEquals(1, unblocked.size)
            assertEquals(taskBId, unblocked.first().taskId)
        }

        @Test
        fun `IS_BLOCKED_BY with multiple blockers only unblocks when all resolved`() = runBlocking {
            val taskAId = UUID.randomUUID() // blocker 1 (completed)
            val taskBId = UUID.randomUUID() // blocked
            val taskCId = UUID.randomUUID() // blocker 2 (still in progress)
            val taskA = createTestTask(id = taskAId, status = TaskStatus.COMPLETED)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)
            val taskC = createTestTask(id = taskCId, status = TaskStatus.IN_PROGRESS)

            // B IS_BLOCKED_BY A
            val depBA = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskBId,
                toTaskId = taskAId,
                type = DependencyType.IS_BLOCKED_BY,
                createdAt = Instant.now()
            )
            // B IS_BLOCKED_BY C
            val depBC = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskBId,
                toTaskId = taskCId,
                type = DependencyType.IS_BLOCKED_BY,
                createdAt = Instant.now()
            )

            // Outer loop for completed task A
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns listOf(depBA)

            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            coEvery { mockTaskRepository.getById(taskCId) } returns Result.Success(taskC)

            // Inner loop: all blocking deps for B
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns emptyList()
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns listOf(depBA, depBC)

            // Role checks
            every { mockStatusProgressionService.getRoleForStatus("pending", "task", emptyList()) } returns "queue"
            every { mockStatusProgressionService.isRoleAtOrBeyond("queue", "terminal") } returns false
            every { mockStatusProgressionService.getRoleForStatus("completed", "task", emptyList()) } returns "terminal"
            every { mockStatusProgressionService.isRoleAtOrBeyond("terminal", "terminal") } returns true
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", emptyList()) } returns "work"
            every { mockStatusProgressionService.isRoleAtOrBeyond("work", "terminal") } returns false

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            // B should NOT be unblocked because C is still in progress
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `mixed BLOCKS and IS_BLOCKED_BY deps both discovered in outer loop`() = runBlocking {
            val taskAId = UUID.randomUUID() // completed blocker
            val taskBId = UUID.randomUUID() // blocked via BLOCKS (A BLOCKS B)
            val taskCId = UUID.randomUUID() // blocked via IS_BLOCKED_BY (C IS_BLOCKED_BY A)
            val taskA = createTestTask(id = taskAId, status = TaskStatus.COMPLETED)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)
            val taskC = createTestTask(id = taskCId, status = TaskStatus.PENDING)

            // A BLOCKS B
            val blocksDep = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskAId,
                toTaskId = taskBId,
                type = DependencyType.BLOCKS,
                createdAt = Instant.now()
            )
            // C IS_BLOCKED_BY A
            val isBlockedByDep = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskCId,
                toTaskId = taskAId,
                type = DependencyType.IS_BLOCKED_BY,
                createdAt = Instant.now()
            )

            // Outer loop
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns listOf(isBlockedByDep)

            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskCId) } returns Result.Success(taskC)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)

            // Inner loop for B: only the BLOCKS dep
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns emptyList()

            // Inner loop for C: only the IS_BLOCKED_BY dep
            every { mockDependencyRepository.findByToTaskId(taskCId) } returns emptyList()
            every { mockDependencyRepository.findByFromTaskId(taskCId) } returns listOf(isBlockedByDep)

            // Role checks
            every { mockStatusProgressionService.getRoleForStatus("pending", "task", emptyList()) } returns "queue"
            every { mockStatusProgressionService.isRoleAtOrBeyond("queue", "terminal") } returns false
            every { mockStatusProgressionService.getRoleForStatus("completed", "task", emptyList()) } returns "terminal"
            every { mockStatusProgressionService.isRoleAtOrBeyond("terminal", "terminal") } returns true

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            // Both B and C should be unblocked
            assertEquals(2, unblocked.size)
            val unblockedIds = unblocked.map { it.taskId }.toSet()
            assertTrue(unblockedIds.contains(taskBId))
            assertTrue(unblockedIds.contains(taskCId))
        }

        @Test
        fun `IS_BLOCKED_BY with unblockAt work threshold unblocks when blocker reaches work`() = runBlocking {
            val taskAId = UUID.randomUUID() // blocker (in-progress)
            val taskBId = UUID.randomUUID() // blocked
            val taskA = createTestTask(id = taskAId, status = TaskStatus.IN_PROGRESS)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)

            // B IS_BLOCKED_BY A with unblockAt=work
            val isBlockedByDep = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskBId,
                toTaskId = taskAId,
                type = DependencyType.IS_BLOCKED_BY,
                unblockAt = "work",
                createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns listOf(isBlockedByDep)

            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)

            every { mockDependencyRepository.findByToTaskId(taskBId) } returns emptyList()
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns listOf(isBlockedByDep)

            every { mockStatusProgressionService.getRoleForStatus("pending", "task", emptyList()) } returns "queue"
            every { mockStatusProgressionService.isRoleAtOrBeyond("queue", "terminal") } returns false
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", emptyList()) } returns "work"
            every { mockStatusProgressionService.isRoleAtOrBeyond("work", "work") } returns true

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            assertEquals(1, unblocked.size)
            assertEquals(taskBId, unblocked.first().taskId)
        }

        @Test
        fun `no candidates when only RELATES_TO IS_BLOCKED_BY deps exist`() = runBlocking {
            val taskAId = UUID.randomUUID()

            // No BLOCKS deps from A
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns emptyList()
            // Only RELATES_TO pointing to A (not IS_BLOCKED_BY)
            val relatesDep = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = UUID.randomUUID(),
                toTaskId = taskAId,
                type = DependencyType.RELATES_TO,
                createdAt = Instant.now()
            )
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns listOf(relatesDep)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            assertTrue(unblocked.isEmpty())
        }
    }

    @Nested
    inner class MultiStepCascadeAdvancementTests {

        @Test
        fun `all_tasks_complete advances feature to next step not terminal`() = runBlocking {
            val taskId = UUID.randomUUID()
            val task = createTestTask(id = taskId, status = TaskStatus.COMPLETED)
            // Feature is at "planning" -- several steps from terminal
            val feature = createTestFeature(status = FeatureStatus.PLANNING)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2, completed = 2, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            // Flow: planning -> in-development -> testing -> completed
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "planning") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 0, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            every { mockStatusProgressionService.getRoleForStatus("completed", "task", emptyList()) } returns "terminal"
            every { mockStatusProgressionService.isRoleAtOrBeyond("terminal", "terminal") } returns true

            // Mock getNextStatus to return next step (in-development)
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "planning",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-development",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 0,
                matchedTags = emptyList(),
                reason = "Next step"
            )

            // Mock getRoleForStatus for recommended status (in-development is work role, not terminal)
            every { mockStatusProgressionService.getRoleForStatus("in-development", "feature", feature.tags) } returns "work"

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("all_tasks_complete", event.event)
            assertEquals("planning", event.currentStatus)
            // Now advances to next step, not terminal
            assertEquals("in-development", event.suggestedStatus)
            assertEquals("default_flow", event.flow)
        }

        @Test
        fun `all_features_complete advances project to next step not terminal`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.COMPLETED)
            // Project at "planning" -- multiple steps from terminal
            val project = Project(
                id = projectId,
                name = "Test Project",
                status = ProjectStatus.PLANNING,
                tags = emptyList(),
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 3, completed = 3
            )
            // Feature flow path (to check isFeatureTerminal)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "completed") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            // Project flow path: planning -> in-development -> completed
            every { mockStatusProgressionService.getFlowPath("project", project.tags, "planning") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 0, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )

            // Mock getNextStatus to return next step
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "planning",
                    containerType = "project",
                    tags = project.tags,
                    containerId = project.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-development",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 0,
                matchedTags = emptyList(),
                reason = "Next step"
            )
            // Feature is terminal — start cascade does NOT fire
            every { mockStatusProgressionService.getRoleForStatus("completed", "feature", feature.tags) } returns "terminal"

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("all_features_complete", event.event)
            assertEquals("planning", event.currentStatus)
            // Now advances to next step, not terminal
            assertEquals("in-development", event.suggestedStatus)
        }

        @Test
        fun `all_tasks_complete does not fire when feature already at terminal`() = runBlocking {
            val taskId = UUID.randomUUID()
            val task = createTestTask(id = taskId, status = TaskStatus.COMPLETED)
            // Feature already at "completed" (terminal)
            val feature = createTestFeature(status = FeatureStatus.COMPLETED)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, completed = 1, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "completed") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            every { mockStatusProgressionService.getRoleForStatus("completed", "task", emptyList()) } returns "terminal"
            every { mockStatusProgressionService.isRoleAtOrBeyond("terminal", "terminal") } returns true

            // getNextStatus called but returns Terminal
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "completed",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Terminal(
                terminalStatus = "completed",
                activeFlow = "default_flow",
                reason = "Already terminal"
            )

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // getNextStatus returns Terminal, so no cascade event
            assertTrue(events.isEmpty())
        }

        @Test
        fun `cascade application advances feature to next step not terminal`() = runBlocking {
            val taskId = UUID.randomUUID()
            val task = createTestTask(id = taskId, status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            // Detection phase
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, completed = 1, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            every { mockStatusProgressionService.getRoleForStatus("completed", "task", emptyList()) } returns "terminal"
            every { mockStatusProgressionService.isRoleAtOrBeyond("terminal", "terminal") } returns true

            // Mock getNextStatus to return next step (testing)
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-development",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next step"
            )

            // Application phase: validate and apply "in-development" -> "testing"
            coEvery {
                mockStatusValidator.validateTransition(
                    currentStatus = "in-development", newStatus = "testing",
                    containerType = "feature", containerId = featureId,
                    context = any(), tags = feature.tags
                )
            } returns StatusValidator.ValidationResult.Valid

            coEvery { mockFeatureRepository.update(any()) } answers {
                Result.Success(firstArg<Feature>())
            }

            // Role transition recording
            every { mockStatusProgressionService.getRoleForStatus("in-development", "feature", emptyList()) } returns "work"
            every { mockStatusProgressionService.getRoleForStatus("testing", "feature", emptyList()) } returns "review"
            coEvery { mockRoleTransitionRepository.create(any()) } returns Result.Success(mockk())

            // No cleanup since testing is not terminal
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)

            // Recursive cascade detection for feature (now at testing)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "testing") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            val project = Project(
                id = projectId, name = "Test Project",
                status = ProjectStatus.IN_DEVELOPMENT, tags = emptyList(),
                createdAt = Instant.now(), modifiedAt = Instant.now()
            )
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 0 // Not all done
            )

            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            assertEquals(1, results.size)
            val result = results.first()
            assertTrue(result.applied)
            assertEquals("in-development", result.previousStatus)
            assertEquals("testing", result.newStatus) // Now expects next step, not terminal
            assertEquals("all_tasks_complete", result.event)
        }
    }
}
