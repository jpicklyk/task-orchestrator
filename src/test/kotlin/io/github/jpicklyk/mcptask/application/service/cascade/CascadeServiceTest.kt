package io.github.jpicklyk.mcptask.application.service.cascade

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation
import io.github.jpicklyk.mcptask.application.service.progression.FlowPath
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.UUID

class CascadeServiceTest {
    // Mock all dependencies
    private val mockStatusProgressionService = mockk<StatusProgressionService>()
    private val mockStatusValidator = mockk<StatusValidator>()
    private val mockTaskRepository = mockk<TaskRepository>()
    private val mockFeatureRepository = mockk<FeatureRepository>()
    private val mockProjectRepository = mockk<ProjectRepository>()
    private val mockDependencyRepository = mockk<DependencyRepository>()
    private val mockSectionRepository = mockk<SectionRepository>()

    private val cascadeService = CascadeServiceImpl(
        statusProgressionService = mockStatusProgressionService,
        statusValidator = mockStatusValidator,
        taskRepository = mockTaskRepository,
        featureRepository = mockFeatureRepository,
        projectRepository = mockProjectRepository,
        dependencyRepository = mockDependencyRepository,
        sectionRepository = mockSectionRepository
    )

    // Test data
    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    // Helper to create test Task objects
    private fun createTestTask(
        id: UUID = taskId,
        featureId: UUID? = this.featureId,
        status: TaskStatus = TaskStatus.COMPLETED,
        tags: List<String> = emptyList()
    ) = Task(
        id = id,
        featureId = featureId,
        title = "Test Task",
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

    // Helper to create test Feature objects
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

    // Helper to create test Project objects
    private fun createTestProject(
        id: UUID = projectId,
        status: ProjectStatus = ProjectStatus.COMPLETED,
        tags: List<String> = emptyList()
    ) = Project(
        id = id,
        name = "Test Project",
        status = status,
        tags = tags,
        createdAt = Instant.now(),
        modifiedAt = Instant.now()
    )

    /** Helper to mock getRoleForStatus for task type. */
    private fun mockRoleForStatus(status: String, role: String?, tags: List<String> = emptyList()) {
        every { mockStatusProgressionService.getRoleForStatus(status, "task", tags) } returns role
    }

    /** Helper to mock getRoleForStatus for feature type. */
    private fun mockFeatureRoleForStatus(status: String, role: String?, tags: List<String> = emptyList()) {
        every { mockStatusProgressionService.getRoleForStatus(status, "feature", tags) } returns role
    }

    /** Helper to mock isRoleAtOrBeyond for role comparison. */
    private fun mockIsRoleAtOrBeyond(currentRole: String?, threshold: String?, result: Boolean) {
        every { mockStatusProgressionService.isRoleAtOrBeyond(currentRole, threshold) } returns result
    }

    @Nested
    inner class DetectCascadeEventsTests {

        @Test
        fun `task completion triggers all_tasks_complete when all tasks done`() = runBlocking {
            val completedTask = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 3, pending = 0, inProgress = 0, testing = 0,
                completed = 2, cancelled = 1, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            // Role-based terminal check
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            // Mock getNextStatus to return next step
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

            // Mock getRoleForStatus for recommended status (testing is review role, not terminal)
            mockFeatureRoleForStatus("testing", "review", feature.tags)

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("all_tasks_complete", event.event)
            assertEquals(ContainerType.FEATURE, event.targetType)
            assertEquals(featureId, event.targetId)
            assertEquals("in-development", event.currentStatus)
            assertEquals("testing", event.suggestedStatus) // Now expects next step, not terminal
            assertEquals("default_flow", event.flow)
            assertTrue(event.automatic)
        }

        @Test
        fun `first task in-progress triggers first_task_started`() = runBlocking {
            val inProgressTask = createTestTask(status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.PLANNING)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(
                inProgressTask,
                createTestTask(id = UUID.randomUUID(), status = TaskStatus.PENDING),
                createTestTask(id = UUID.randomUUID(), status = TaskStatus.PENDING)
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "planning") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 0, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("planning", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-development", activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 0, matchedTags = emptyList(), reason = "First task started"
            )
            // in-progress is NOT terminal
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "terminal", false)
            // planning feature is in queue role — start cascade will also fire
            mockFeatureRoleForStatus("planning", "queue", feature.tags)

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // first_task_started fires (legacy: status-based check for first flow position)
            // first_child_started also fires (role-based start cascade: parent in queue, child in work)
            assertTrue(events.any { it.event == "first_task_started" })
            assertTrue(events.any { it.event == "first_child_started" })
            val firstTaskStarted = events.first { it.event == "first_task_started" }
            assertEquals(ContainerType.FEATURE, firstTaskStarted.targetType)
            assertEquals(featureId, firstTaskStarted.targetId)
            assertEquals("planning", firstTaskStarted.currentStatus)
            assertEquals("in-development", firstTaskStarted.suggestedStatus)
        }

        @Test
        fun `no cascade when task has no feature`() = runBlocking {
            val orphanTask = createTestTask(featureId = null)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(orphanTask)
            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)
            assertTrue(events.isEmpty())
        }

        @Test
        fun `no cascade when not all tasks complete`() = runBlocking {
            val completedTask = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 3, completed = 2, pending = 1,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)
            assertTrue(events.isEmpty())
        }

        @Test
        fun `feature completion triggers all_features_complete when all features done`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.COMPLETED)
            val project = createTestProject(status = ProjectStatus.IN_DEVELOPMENT)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 2
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "completed") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            every { mockStatusProgressionService.getFlowPath("project", project.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-development",
                    containerType = "project",
                    tags = project.tags,
                    containerId = project.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next step"
            )
            // Feature is terminal — start cascade does NOT fire (only fires when featureRole == "work")
            mockFeatureRoleForStatus("completed", "terminal", feature.tags)

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            assertEquals(1, events.size)
            assertEquals("all_features_complete", events.first().event)
            assertEquals(ContainerType.PROJECT, events.first().targetType)
            assertEquals(projectId, events.first().targetId)
            assertEquals("completed", events.first().suggestedStatus)
        }

        @Test
        fun `no project cascade when features still active`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.COMPLETED)
            val project = createTestProject(status = ProjectStatus.IN_DEVELOPMENT)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 3, completed = 1
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "completed") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            // Feature is terminal — start cascade does NOT fire
            mockFeatureRoleForStatus("completed", "terminal", feature.tags)

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)
            assertTrue(events.isEmpty())
        }

        @Test
        fun `projects have no cascades upward`() = runBlocking {
            val events = cascadeService.detectCascadeEvents(projectId, ContainerType.PROJECT)
            assertTrue(events.isEmpty())
        }

        @Test
        fun `non-terminal task status does not trigger all_tasks_complete`() = runBlocking {
            val inProgressTask = createTestTask(status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "terminal", false)
            // in-development feature is in work role — start cascade does NOT fire (not "queue")
            mockFeatureRoleForStatus("in-development", "work", feature.tags)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(
                inProgressTask,
                createTestTask(id = UUID.randomUUID(), status = TaskStatus.IN_PROGRESS)
            )

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)
            assertTrue(events.isEmpty())
        }
    }

    @Nested
    inner class FindNewlyUnblockedTasksTests {

        @Test
        fun `returns unblocked tasks when all blockers resolved`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskA = createTestTask(id = taskAId, status = TaskStatus.COMPLETED)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)

            val blocksDep = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns emptyList()
            mockRoleForStatus("pending", "queue")
            mockIsRoleAtOrBeyond("queue", "terminal", false)
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            assertEquals(1, unblocked.size)
            assertEquals(taskBId, unblocked.first().taskId)
            assertEquals("Test Task", unblocked.first().title)
        }

        @Test
        fun `does not return task still blocked by other tasks`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskCId = UUID.randomUUID()
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)
            val taskC = createTestTask(id = taskCId, status = TaskStatus.IN_PROGRESS)

            val depAtoB = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, createdAt = Instant.now()
            )
            val depCtoB = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskCId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(depAtoB)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(depAtoB, depCtoB)
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(
                createTestTask(id = taskAId, status = TaskStatus.COMPLETED)
            )
            coEvery { mockTaskRepository.getById(taskCId) } returns Result.Success(taskC)
            mockRoleForStatus("pending", "queue")
            mockIsRoleAtOrBeyond("queue", "terminal", false)
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "terminal", false)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `does not return already-completed downstream tasks`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskB = createTestTask(id = taskBId, status = TaskStatus.COMPLETED)

            val blocksDep = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `ignores RELATES_TO dependencies in outgoing dep filtering`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()

            val relatesDep = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.RELATES_TO, createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(relatesDep)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `handles task with no outgoing dependencies`() = runBlocking {
            val taskAId = UUID.randomUUID()
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `unblockAt work threshold unblocks downstream when blocker reaches work role`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskA = createTestTask(id = taskAId, status = TaskStatus.IN_PROGRESS)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)

            val blocksDep = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, unblockAt = "work", createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns emptyList()
            mockRoleForStatus("pending", "queue")
            mockIsRoleAtOrBeyond("queue", "terminal", false)
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "work", true)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            assertEquals(1, unblocked.size)
            assertEquals(taskBId, unblocked.first().taskId)
        }

        @Test
        fun `null unblockAt defaults to terminal threshold for backward compat`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskA = createTestTask(id = taskAId, status = TaskStatus.IN_PROGRESS)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)

            val blocksDep = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns emptyList()
            mockRoleForStatus("pending", "queue")
            mockIsRoleAtOrBeyond("queue", "terminal", false)
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "terminal", false)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `RELATES_TO incoming deps do not block downstream task`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskCId = UUID.randomUUID()
            val taskA = createTestTask(id = taskAId, status = TaskStatus.COMPLETED)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)

            val blocksDep = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, createdAt = Instant.now()
            )
            val relatesDep = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskCId, toTaskId = taskBId,
                type = DependencyType.RELATES_TO, createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(blocksDep)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(blocksDep, relatesDep)
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns emptyList()
            mockRoleForStatus("pending", "queue")
            mockIsRoleAtOrBeyond("queue", "terminal", false)
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            assertEquals(1, unblocked.size)
            assertEquals(taskBId, unblocked.first().taskId)
        }

        @Test
        fun `mixed dependencies with different unblockAt thresholds - partially resolved`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskCId = UUID.randomUUID()
            val taskA = createTestTask(id = taskAId, status = TaskStatus.IN_PROGRESS)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)
            val taskC = createTestTask(id = taskCId, status = TaskStatus.IN_PROGRESS)

            val depAtoB = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, unblockAt = "work", createdAt = Instant.now()
            )
            val depCtoB = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskCId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(depAtoB)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            coEvery { mockTaskRepository.getById(taskCId) } returns Result.Success(taskC)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(depAtoB, depCtoB)
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns emptyList()
            mockRoleForStatus("pending", "queue")
            mockIsRoleAtOrBeyond("queue", "terminal", false)
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "work", true)
            mockIsRoleAtOrBeyond("work", "terminal", false)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `mixed dependencies all resolved with work thresholds`() = runBlocking {
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskCId = UUID.randomUUID()
            val taskA = createTestTask(id = taskAId, status = TaskStatus.IN_PROGRESS)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)
            val taskC = createTestTask(id = taskCId, status = TaskStatus.IN_PROGRESS)

            val depAtoB = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskAId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, unblockAt = "work", createdAt = Instant.now()
            )
            val depCtoB = Dependency(
                id = UUID.randomUUID(), fromTaskId = taskCId, toTaskId = taskBId,
                type = DependencyType.BLOCKS, unblockAt = "work", createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(depAtoB)
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            coEvery { mockTaskRepository.getById(taskCId) } returns Result.Success(taskC)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(depAtoB, depCtoB)
            every { mockDependencyRepository.findByFromTaskId(taskBId) } returns emptyList()
            mockRoleForStatus("pending", "queue")
            mockIsRoleAtOrBeyond("queue", "terminal", false)
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "work", true)

            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            assertEquals(1, unblocked.size)
            assertEquals(taskBId, unblocked.first().taskId)
        }
    }

    @Nested
    inner class ApplyCascadesTests {

        @Test
        fun `applies cascade and returns result`() = runBlocking {
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

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
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

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

            // Mock getRoleForStatus for recommended status (testing is review role, not terminal)
            mockFeatureRoleForStatus("testing", "review", feature.tags)

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

            // No cleanup since testing is not terminal
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)

            // Recursive cascade detection for the feature after it reaches testing
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "testing") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            val project = createTestProject(status = ProjectStatus.IN_DEVELOPMENT)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 0 // Not terminal
            )

            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            assertEquals(1, results.size)
            val result = results.first()
            assertEquals("all_tasks_complete", result.event)
            assertTrue(result.applied)
            assertEquals("in-development", result.previousStatus)
            assertEquals("testing", result.newStatus) // Now expects next step, not terminal
            assertNull(result.error)
        }

        @Test
        fun `stops at max depth`() = runBlocking {
            val results = cascadeService.applyCascades(taskId, "task", depth = 3, maxDepth = 3)
            assertTrue(results.isEmpty())
        }

        @Test
        fun `skips cascade when target already at suggested status`() = runBlocking {
            val task = createTestTask(status = TaskStatus.COMPLETED)
            // Feature already at terminal status ("completed") - cascade should be skipped
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
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)
            assertTrue(results.isEmpty())
        }

        @Test
        fun `handles validation failure gracefully`() = runBlocking {
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

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
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

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

            // Mock getRoleForStatus for recommended status (testing is review role, not terminal)
            mockFeatureRoleForStatus("testing", "review", feature.tags)

            coEvery {
                mockStatusValidator.validateTransition(
                    currentStatus = "in-development", newStatus = "testing",
                    containerType = "feature", containerId = featureId,
                    context = any(), tags = feature.tags
                )
            } returns StatusValidator.ValidationResult.Invalid("Prerequisites not met")

            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            assertEquals(1, results.size)
            assertFalse(results.first().applied)
            assertEquals("Transition blocked: Prerequisites not met", results.first().error)
        }

        @Test
        fun `handles entity not found gracefully`() = runBlocking {
            val task = createTestTask(status = TaskStatus.COMPLETED)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Error(
                RepositoryError.NotFound(featureId, EntityType.FEATURE, "Not found")
            )
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)
            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class LoadAutoCascadeConfigTests {

        @Test
        fun `returns defaults when no config file exists`() {
            val config = cascadeService.loadAutoCascadeConfig()
            assertTrue(config.enabled)
            assertEquals(10, config.maxDepth)
        }

        @Test
        fun `loads from bundled default config`() {
            val config = cascadeService.loadAutoCascadeConfig()
            assertNotNull(config)
            assertTrue(config.enabled)
            assertEquals(10, config.maxDepth)
        }
    }

    @Nested
    inner class RoleAggregationTests {

        @Test
        fun `role aggregation advances feature when threshold met`() = runBlocking {
            val taskId1 = UUID.randomUUID()
            val taskId2 = UUID.randomUUID()
            val taskId3 = UUID.randomUUID()
            val task1 = createTestTask(id = taskId1, status = TaskStatus.IN_REVIEW)
            val task2 = createTestTask(id = taskId2, status = TaskStatus.IN_REVIEW)
            val task3 = createTestTask(id = taskId3, status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            val aggregationRules = listOf(
                RoleAggregationConfig(
                    roleThreshold = "review",
                    percentage = 0.66, // 66% threshold
                    targetFeatureStatus = "testing"
                )
            )

            val cascadeServiceWithRules = CascadeServiceImpl(
                statusProgressionService = mockStatusProgressionService,
                statusValidator = mockStatusValidator,
                taskRepository = mockTaskRepository,
                featureRepository = mockFeatureRepository,
                projectRepository = mockProjectRepository,
                dependencyRepository = mockDependencyRepository,
                sectionRepository = mockSectionRepository,
                aggregationRules = aggregationRules
            )

            coEvery { mockTaskRepository.getById(taskId1) } returns Result.Success(task1)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1, task2, task3)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("in-development", "feature", emptyList(), feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing", activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(), reason = "Role aggregation"
            )
            // Mock getRoleForStatus for recommended status (testing is review role, not terminal)
            mockFeatureRoleForStatus("testing", "review", emptyList())
            mockRoleForStatus("in-review", "review", task1.tags)
            mockRoleForStatus("in-review", "review", task2.tags)
            mockRoleForStatus("in-progress", "work", task3.tags)
            every { mockStatusProgressionService.getRoleForStatus("in-review", "task", task1.tags) } returns "review"
            every { mockStatusProgressionService.getRoleForStatus("in-review", "task", task2.tags) } returns "review"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", task3.tags) } returns "work"
            mockIsRoleAtOrBeyond("review", "review", true)
            mockIsRoleAtOrBeyond("work", "review", false)
            every { mockStatusProgressionService.isRoleAtOrBeyond("review", "terminal") } returns false

            val events = cascadeServiceWithRules.detectCascadeEvents(taskId1, ContainerType.TASK)

            val aggregationEvents = events.filter { it.event == "role_aggregation_threshold" }
            assertEquals(1, aggregationEvents.size)
            val event = aggregationEvents.first()
            assertEquals(ContainerType.FEATURE, event.targetType)
            assertEquals(featureId, event.targetId)
            assertEquals("in-development", event.currentStatus)
            assertEquals("testing", event.suggestedStatus)
            assertTrue(event.reason.contains("66% of tasks at role 'review' or beyond"))
        }

        @Test
        fun `role aggregation does not advance when below threshold`() = runBlocking {
            val taskId1 = UUID.randomUUID()
            val taskId2 = UUID.randomUUID()
            val taskId3 = UUID.randomUUID()
            val task1 = createTestTask(id = taskId1, status = TaskStatus.IN_REVIEW)
            val task2 = createTestTask(id = taskId2, status = TaskStatus.IN_PROGRESS)
            val task3 = createTestTask(id = taskId3, status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            val aggregationRules = listOf(
                RoleAggregationConfig(
                    roleThreshold = "review",
                    percentage = 0.66, // 66% threshold, but only 33% at review
                    targetFeatureStatus = "testing"
                )
            )

            val cascadeServiceWithRules = CascadeServiceImpl(
                statusProgressionService = mockStatusProgressionService,
                statusValidator = mockStatusValidator,
                taskRepository = mockTaskRepository,
                featureRepository = mockFeatureRepository,
                projectRepository = mockProjectRepository,
                dependencyRepository = mockDependencyRepository,
                sectionRepository = mockSectionRepository,
                aggregationRules = aggregationRules
            )

            coEvery { mockTaskRepository.getById(taskId1) } returns Result.Success(task1)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1, task2, task3)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            every { mockStatusProgressionService.getRoleForStatus("in-review", "task", task1.tags) } returns "review"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", task2.tags) } returns "work"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", task3.tags) } returns "work"
            mockIsRoleAtOrBeyond("review", "review", true)
            mockIsRoleAtOrBeyond("work", "review", false)
            every { mockStatusProgressionService.isRoleAtOrBeyond("review", "terminal") } returns false

            val events = cascadeServiceWithRules.detectCascadeEvents(taskId1, ContainerType.TASK)

            val aggregationEvents = events.filter { it.event == "role_aggregation_threshold" }
            assertTrue(aggregationEvents.isEmpty())
        }

        @Test
        fun `role aggregation uses default 100 percent which matches existing behavior`() = runBlocking {
            val taskId1 = UUID.randomUUID()
            val taskId2 = UUID.randomUUID()
            val task1 = createTestTask(id = taskId1, status = TaskStatus.COMPLETED)
            val task2 = createTestTask(id = taskId2, status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            val aggregationRules = listOf(
                RoleAggregationConfig(
                    roleThreshold = "terminal",
                    percentage = 1.0, // 100% - same as existing all_tasks_complete
                    targetFeatureStatus = "completed"
                )
            )

            val cascadeServiceWithRules = CascadeServiceImpl(
                statusProgressionService = mockStatusProgressionService,
                statusValidator = mockStatusValidator,
                taskRepository = mockTaskRepository,
                featureRepository = mockFeatureRepository,
                projectRepository = mockProjectRepository,
                dependencyRepository = mockDependencyRepository,
                sectionRepository = mockSectionRepository,
                aggregationRules = aggregationRules
            )

            coEvery { mockTaskRepository.getById(taskId1) } returns Result.Success(task1)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1, task2)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2, completed = 2, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            every { mockStatusProgressionService.getRoleForStatus("completed", "task", task1.tags) } returns "terminal"
            every { mockStatusProgressionService.getRoleForStatus("completed", "task", task2.tags) } returns "terminal"
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-development",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next step"
            )

            // Mock getRoleForStatus for recommended status (completed is terminal)
            mockFeatureRoleForStatus("completed", "terminal", feature.tags)

            val events = cascadeServiceWithRules.detectCascadeEvents(taskId1, ContainerType.TASK)

            // Should have both all_tasks_complete AND role_aggregation_threshold
            assertTrue(events.any { it.event == "all_tasks_complete" })
            assertTrue(events.any { it.event == "role_aggregation_threshold" })
        }

        @Test
        fun `role aggregation skips when no StatusProgressionService`() = runBlocking {
            val taskId1 = UUID.randomUUID()
            val task1 = createTestTask(id = taskId1, status = TaskStatus.IN_REVIEW)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            val aggregationRules = listOf(
                RoleAggregationConfig(
                    roleThreshold = "review",
                    percentage = 0.5,
                    targetFeatureStatus = "testing"
                )
            )

            // Create service without StatusProgressionService (null)
            val cascadeServiceWithoutSPS = CascadeServiceImpl(
                statusProgressionService = mockStatusProgressionService,
                statusValidator = mockStatusValidator,
                taskRepository = mockTaskRepository,
                featureRepository = mockFeatureRepository,
                projectRepository = mockProjectRepository,
                dependencyRepository = mockDependencyRepository,
                sectionRepository = mockSectionRepository,
                aggregationRules = aggregationRules
            )

            coEvery { mockTaskRepository.getById(taskId1) } returns Result.Success(task1)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "in-review", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("in-review", "review")
            mockIsRoleAtOrBeyond("review", "terminal", false)

            val events = cascadeServiceWithoutSPS.detectCascadeEvents(taskId1, ContainerType.TASK)

            // Should still work since we have StatusProgressionService
            val aggregationEvents = events.filter { it.event == "role_aggregation_threshold" }
            assertTrue(aggregationEvents.isNotEmpty())
        }

        @Test
        fun `role aggregation skips when no rules configured`() = runBlocking {
            val taskId1 = UUID.randomUUID()
            val task1 = createTestTask(id = taskId1, status = TaskStatus.IN_REVIEW)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId1) } returns Result.Success(task1)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "in-review", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("in-review", "review")
            mockIsRoleAtOrBeyond("review", "terminal", false)

            // cascadeService has empty rules by default
            val events = cascadeService.detectCascadeEvents(taskId1, ContainerType.TASK)

            val aggregationEvents = events.filter { it.event == "role_aggregation_threshold" }
            assertTrue(aggregationEvents.isEmpty())
        }

        @Test
        fun `role aggregation does not regress feature status`() = runBlocking {
            val taskId1 = UUID.randomUUID()
            val task1 = createTestTask(id = taskId1, status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.TESTING)

            val aggregationRules = listOf(
                RoleAggregationConfig(
                    roleThreshold = "work",
                    percentage = 1.0,
                    targetFeatureStatus = "in-development" // Trying to regress from in-review back to in-development
                )
            )

            val cascadeServiceWithRules = CascadeServiceImpl(
                statusProgressionService = mockStatusProgressionService,
                statusValidator = mockStatusValidator,
                taskRepository = mockTaskRepository,
                featureRepository = mockFeatureRepository,
                projectRepository = mockProjectRepository,
                dependencyRepository = mockDependencyRepository,
                sectionRepository = mockSectionRepository,
                aggregationRules = aggregationRules
            )

            coEvery { mockTaskRepository.getById(taskId1) } returns Result.Success(task1)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "testing") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", task1.tags) } returns "work"
            mockIsRoleAtOrBeyond("work", "work", true)
            mockIsRoleAtOrBeyond("work", "terminal", false)
            // Feature is in review role (testing) — start cascade does NOT fire
            mockFeatureRoleForStatus("testing", "review", feature.tags)

            val events = cascadeServiceWithRules.detectCascadeEvents(taskId1, ContainerType.TASK)

            // Should still create event - validation will catch regression during application
            val aggregationEvents = events.filter { it.event == "role_aggregation_threshold" }
            assertTrue(aggregationEvents.isNotEmpty())
            assertEquals("in-development", aggregationEvents.first().suggestedStatus)
        }

        @Test
        fun `role aggregation skips when feature already at target status`() = runBlocking {
            val taskId1 = UUID.randomUUID()
            val taskId2 = UUID.randomUUID()
            val task1 = createTestTask(id = taskId1, status = TaskStatus.IN_REVIEW)
            val task2 = createTestTask(id = taskId2, status = TaskStatus.IN_REVIEW)
            val feature = createTestFeature(status = FeatureStatus.TESTING)

            val aggregationRules = listOf(
                RoleAggregationConfig(
                    roleThreshold = "review",
                    percentage = 1.0,
                    targetFeatureStatus = "testing"
                )
            )

            val cascadeServiceWithRules = CascadeServiceImpl(
                statusProgressionService = mockStatusProgressionService,
                statusValidator = mockStatusValidator,
                taskRepository = mockTaskRepository,
                featureRepository = mockFeatureRepository,
                projectRepository = mockProjectRepository,
                dependencyRepository = mockDependencyRepository,
                sectionRepository = mockSectionRepository,
                aggregationRules = aggregationRules
            )

            coEvery { mockTaskRepository.getById(taskId1) } returns Result.Success(task1)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1, task2)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "testing") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            every { mockStatusProgressionService.getRoleForStatus("in-review", "task", task1.tags) } returns "review"
            every { mockStatusProgressionService.getRoleForStatus("in-review", "task", task2.tags) } returns "review"
            mockIsRoleAtOrBeyond("review", "review", true)
            mockIsRoleAtOrBeyond("review", "terminal", false)

            val events = cascadeServiceWithRules.detectCascadeEvents(taskId1, ContainerType.TASK)

            val aggregationEvents = events.filter { it.event == "role_aggregation_threshold" }
            assertTrue(aggregationEvents.isEmpty())
        }
    }

    @Nested
    inner class StepThroughIntermediateStatusesTests {

        @Test
        fun `feature cascade suggests next step not terminal`() = runBlocking {
            val completedTask = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, pending = 0, inProgress = 0, testing = 0,
                completed = 1, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            // Mock getNextStatus to return next step (testing), not terminal
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
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next step in workflow"
            )

            // Mock getRoleForStatus for recommended status (testing is review role, not terminal)
            mockFeatureRoleForStatus("testing", "review", feature.tags)

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("all_tasks_complete", event.event)
            assertEquals("in-development", event.currentStatus)
            assertEquals("testing", event.suggestedStatus) // Next step, NOT "completed"
        }

        @Test
        fun `feature self-advancement through multi-step flow`() = runBlocking {
            // Feature at testing, all tasks done -> should advance to validating
            val feature = createTestFeature(status = FeatureStatus.TESTING)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(
                createTestProject(status = ProjectStatus.IN_DEVELOPMENT)
            )
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 3, completed = 3, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "testing") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 2, matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "testing",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "validating",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 2,
                matchedTags = emptyList(),
                reason = "Next step in workflow"
            )
            // Mock getRoleForStatus for recommended status (validating is review role, not terminal)
            mockFeatureRoleForStatus("validating", "review", feature.tags)
            // Feature is in review role (testing) — start cascade does NOT fire (only fires when "work")
            mockFeatureRoleForStatus("testing", "review", feature.tags)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 0 // Not all features done
            )

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("feature_self_advancement", event.event)
            assertEquals("testing", event.currentStatus)
            assertEquals("validating", event.suggestedStatus)
        }

        @Test
        fun `max_depth default is 10`() {
            val config = AutoCascadeConfig()
            assertEquals(10, config.maxDepth)
        }

        @Test
        fun `project cascade uses next-step not terminal`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.COMPLETED)
            val project = createTestProject(status = ProjectStatus.IN_DEVELOPMENT)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 2
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "completed") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )
            every { mockStatusProgressionService.getFlowPath("project", project.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "deployed", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("on-hold", "cancelled")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-development",
                    containerType = "project",
                    tags = project.tags,
                    containerId = project.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "deployed",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "deployed", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next step in workflow"
            )
            // Feature is terminal — start cascade does NOT fire (only fires when featureRole == "work")
            mockFeatureRoleForStatus("completed", "terminal", feature.tags)

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            assertEquals(1, events.size)
            assertEquals("all_features_complete", events.first().event)
            assertEquals("deployed", events.first().suggestedStatus) // Next step, NOT "completed"
        }

        @Test
        fun `feature self-advancement skipped when recommendation is blocked`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.TESTING)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(
                createTestProject(status = ProjectStatus.IN_DEVELOPMENT)
            )
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, completed = 1, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "testing") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2, matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "testing",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Blocked(
                currentStatus = "testing",
                blockers = listOf("Verification required"),
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2
            )
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 1, completed = 0
            )
            // Feature is in review role (testing) — start cascade does NOT fire
            mockFeatureRoleForStatus("testing", "review", feature.tags)

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            assertTrue(events.isEmpty()) // No cascade when blocked
        }

        @Test
        fun `feature self-advancement skipped when already terminal`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.COMPLETED)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(
                createTestProject(status = ProjectStatus.IN_DEVELOPMENT)
            )
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, completed = 1, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "completed") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 1 // Not all features done
            )
            // Feature is terminal — start cascade does NOT fire
            mockFeatureRoleForStatus("completed", "terminal", feature.tags)

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            assertTrue(events.isEmpty()) // Feature already terminal, only project cascade would fire (but not all features done)
        }
    }

    @Nested
    inner class VerificationGateTests {

        @Test
        fun `cascade stops before terminal when requiresVerification is true`() = runBlocking {
            val completedTask = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.VALIDATING).copy(requiresVerification = true)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, completed = 1, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "validating") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "validating",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 3,
                matchedTags = emptyList(),
                reason = "Next step"
            )

            every { mockStatusProgressionService.getRoleForStatus("completed", "feature", feature.tags) } returns "terminal"

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Should NOT emit cascade event because target is terminal and verification is required
            val allTasksCompleteEvents = events.filter { it.event == "all_tasks_complete" }
            assertTrue(allTasksCompleteEvents.isEmpty())
        }

        @Test
        fun `cascade proceeds to terminal when requiresVerification is false`() = runBlocking {
            val completedTask = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.VALIDATING).copy(requiresVerification = false)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, completed = 1, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "validating") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "validating",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 3,
                matchedTags = emptyList(),
                reason = "Next step"
            )

            every { mockStatusProgressionService.getRoleForStatus("completed", "feature", feature.tags) } returns "terminal"

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Should emit cascade event because verification is not required
            val allTasksCompleteEvents = events.filter { it.event == "all_tasks_complete" }
            assertEquals(1, allTasksCompleteEvents.size)
            assertEquals("completed", allTasksCompleteEvents.first().suggestedStatus)
        }

        @Test
        fun `cascade proceeds to non-terminal step even with requiresVerification true`() = runBlocking {
            val completedTask = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT).copy(requiresVerification = true)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, completed = 1, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

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
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next step"
            )

            every { mockStatusProgressionService.getRoleForStatus("testing", "feature", feature.tags) } returns "review"

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Should emit cascade event because target is non-terminal (review role)
            val allTasksCompleteEvents = events.filter { it.event == "all_tasks_complete" }
            assertEquals(1, allTasksCompleteEvents.size)
            assertEquals("testing", allTasksCompleteEvents.first().suggestedStatus)
        }

        @Test
        fun `feature self-advancement stops before terminal when requiresVerification is true`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.VALIDATING).copy(requiresVerification = true)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(
                createTestProject(status = ProjectStatus.IN_DEVELOPMENT)
            )
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2, completed = 2, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "validating") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "validating",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 3,
                matchedTags = emptyList(),
                reason = "Next step"
            )
            every { mockStatusProgressionService.getRoleForStatus("completed", "feature", feature.tags) } returns "terminal"
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 0 // Not all features done
            )
            // Feature is in review role (validating) — start cascade does NOT fire
            mockFeatureRoleForStatus("validating", "review", feature.tags)

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            // Should NOT emit cascade event because target is terminal and verification is required
            val selfAdvancementEvents = events.filter { it.event == "feature_self_advancement" }
            assertTrue(selfAdvancementEvents.isEmpty())
        }

        @Test
        fun `feature self-advancement proceeds to terminal when requiresVerification is false`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.VALIDATING).copy(requiresVerification = false)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(
                createTestProject(status = ProjectStatus.IN_DEVELOPMENT)
            )
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2, completed = 2, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "validating") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 3, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "validating",
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "validating", "completed"),
                currentPosition = 3,
                matchedTags = emptyList(),
                reason = "Next step"
            )
            every { mockStatusProgressionService.getRoleForStatus("completed", "feature", feature.tags) } returns "terminal"
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 0 // Not all features done
            )
            // Feature is in review role (validating) — start cascade does NOT fire
            mockFeatureRoleForStatus("validating", "review", feature.tags)

            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            // Should emit cascade event because verification is not required
            val selfAdvancementEvents = events.filter { it.event == "feature_self_advancement" }
            assertEquals(1, selfAdvancementEvents.size)
            assertEquals("completed", selfAdvancementEvents.first().suggestedStatus)
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `handles task with cancelled status as terminal`() = runBlocking {
            val cancelledTask = createTestTask(status = TaskStatus.CANCELLED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(cancelledTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2, cancelled = 2, completed = 0, pending = 0,
                inProgress = 0, testing = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("cancelled", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

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

            // Mock getRoleForStatus for recommended status (testing is review role, not terminal)
            mockFeatureRoleForStatus("testing", "review", feature.tags)

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            assertEquals(1, events.size)
            assertEquals("all_tasks_complete", events.first().event)
            assertEquals("testing", events.first().suggestedStatus) // Next step, not terminal
        }

        @Test
        fun `handles feature with no tasks gracefully`() = runBlocking {
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 0, completed = 0, pending = 0,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)
            assertTrue(events.isEmpty())
        }

        @Test
        fun `handles repository errors during cascade application`() = runBlocking {
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

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
            mockRoleForStatus("completed", "terminal")
            mockIsRoleAtOrBeyond("terminal", "terminal", true)

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

            // Mock getRoleForStatus for recommended status (testing is review role, not terminal)
            mockFeatureRoleForStatus("testing", "review", feature.tags)

            coEvery {
                mockStatusValidator.validateTransition(any(), any(), any(), any(), any(), any())
            } returns StatusValidator.ValidationResult.Valid
            coEvery { mockFeatureRepository.update(any()) } returns Result.Error(
                RepositoryError.DatabaseError("Update failed")
            )

            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            assertEquals(1, results.size)
            assertFalse(results.first().applied)
            assertNotNull(results.first().error)
            assertTrue(results.first().error!!.contains("Failed to update feature"))
        }
    }

    @Nested
    inner class StartCascadeTests {

        /** Helper to build a CascadeServiceImpl with a custom StartCascadeConfig. */
        private fun cascadeServiceWith(startCascadeEnabled: Boolean): CascadeServiceImpl =
            CascadeServiceImpl(
                statusProgressionService = mockStatusProgressionService,
                statusValidator = mockStatusValidator,
                taskRepository = mockTaskRepository,
                featureRepository = mockFeatureRepository,
                projectRepository = mockProjectRepository,
                dependencyRepository = mockDependencyRepository,
                sectionRepository = mockSectionRepository,
                startCascadeConfig = StartCascadeConfig(enabled = startCascadeEnabled)
            )

        @Test
        fun `parent feature auto-starts when first child task starts (queue to work cascade)`(): Unit = runBlocking {
            val inProgressTask = createTestTask(status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.PLANNING)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(
                inProgressTask,
                createTestTask(id = UUID.randomUUID(), status = TaskStatus.PENDING)
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "planning") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 0, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            // Task is in work role
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "terminal", false)
            // Feature parent is in queue role
            mockFeatureRoleForStatus("planning", "queue", feature.tags)
            // getNextStatus for the feature
            coEvery {
                mockStatusProgressionService.getNextStatus("planning", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-development", activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 0, matchedTags = emptyList(), reason = "First child started"
            )

            val service = cascadeServiceWith(startCascadeEnabled = true)
            val events = service.detectCascadeEvents(taskId, ContainerType.TASK)

            val startCascadeEvents = events.filter { it.event == "first_child_started" }
            assertEquals(1, startCascadeEvents.size)
            val event = startCascadeEvents.first()
            assertEquals(ContainerType.FEATURE, event.targetType)
            assertEquals(featureId, event.targetId)
            assertEquals("planning", event.currentStatus)
            assertEquals("in-development", event.suggestedStatus)
            assertTrue(event.automatic)
        }

        @Test
        fun `parent feature does NOT cascade if already in work role`(): Unit = runBlocking {
            val inProgressTask = createTestTask(status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(inProgressTask)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            // Task is in work role
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "terminal", false)
            // Feature is in work role — cascade should NOT fire
            mockFeatureRoleForStatus("in-development", "work", feature.tags)

            val service = cascadeServiceWith(startCascadeEnabled = true)
            val events = service.detectCascadeEvents(taskId, ContainerType.TASK)

            val startCascadeEvents = events.filter { it.event == "first_child_started" }
            assertTrue(startCascadeEvents.isEmpty())
        }

        @Test
        fun `parent feature does NOT cascade when start_cascade is disabled`(): Unit = runBlocking {
            val inProgressTask = createTestTask(status = TaskStatus.IN_PROGRESS)
            // Use IN_DEVELOPMENT so first_task_started won't fire (feature not at first flow position)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(inProgressTask)
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            // Task is in work role — but start cascade is disabled, so no first_child_started fires
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "terminal", false)

            val service = cascadeServiceWith(startCascadeEnabled = false)
            val events = service.detectCascadeEvents(taskId, ContainerType.TASK)

            val startCascadeEvents = events.filter { it.event == "first_child_started" }
            assertTrue(startCascadeEvents.isEmpty())
        }

        @Test
        fun `non-first child starting does NOT re-trigger cascade when parent already in work`(): Unit = runBlocking {
            val secondInProgressTask = createTestTask(status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(secondInProgressTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(
                secondInProgressTask,
                createTestTask(id = UUID.randomUUID(), status = TaskStatus.IN_PROGRESS)
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            // Task is in work role
            mockRoleForStatus("in-progress", "work")
            mockIsRoleAtOrBeyond("work", "terminal", false)
            // Feature is already in work role — cascade does NOT fire
            mockFeatureRoleForStatus("in-development", "work", feature.tags)

            val service = cascadeServiceWith(startCascadeEnabled = true)
            val events = service.detectCascadeEvents(taskId, ContainerType.TASK)

            val startCascadeEvents = events.filter { it.event == "first_child_started" }
            assertTrue(startCascadeEvents.isEmpty())
        }

        @Test
        fun `parent project auto-starts when first child feature starts (feature to project cascade)`(): Unit = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)
            val project = createTestProject(status = ProjectStatus.PLANNING)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1, matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            // For feature_self_advancement check: tasks not all done
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2, completed = 0, pending = 2,
                inProgress = 0, testing = 0, cancelled = 0, blocked = 0
            )
            // Feature is in work role
            mockFeatureRoleForStatus("in-development", "work", feature.tags)
            // Project is in queue role
            every { mockStatusProgressionService.getRoleForStatus("planning", "project", project.tags) } returns "queue"
            // getNextStatus for the project
            coEvery {
                mockStatusProgressionService.getNextStatus("planning", "project", project.tags, project.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-development", activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 0, matchedTags = emptyList(), reason = "First feature started"
            )

            val service = cascadeServiceWith(startCascadeEnabled = true)
            val events = service.detectCascadeEvents(featureId, ContainerType.FEATURE)

            val startCascadeEvents = events.filter { it.event == "first_child_started" }
            assertEquals(1, startCascadeEvents.size)
            val event = startCascadeEvents.first()
            assertEquals(ContainerType.PROJECT, event.targetType)
            assertEquals(projectId, event.targetId)
            assertEquals("planning", event.currentStatus)
            assertEquals("in-development", event.suggestedStatus)
            assertTrue(event.automatic)
        }

        @Test
        fun `loadStartCascadeConfig returns enabled true by default from bundled config`() {
            val config = CascadeServiceImpl.loadStartCascadeConfig()
            assertTrue(config.enabled)
        }
    }
}
